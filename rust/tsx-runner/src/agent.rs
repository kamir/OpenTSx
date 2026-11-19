use crate::checkpoint::Store;
use crate::run_loop::{RunError, StopToken};
use crate::run_plan::Refusal;
use crate::site_client::{SiteClient, SiteResponse};
use crate::site_plan::{fields, text_field, BoundedPlan, SiteConfig};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::time::{Duration, Instant};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};

const SCHEMA: &str = "opentsx.rust-agent-state.v1";
const STATE_BYTES: usize = 32 * 1024 * 1024;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Command {
    job: String,
    version: i32,
    hash: String,
    compact: String,
    phase: String,
    records: u64,
    outcome: Option<Value>,
    terminal: Option<Value>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Acknowledgement {
    sequence: String,
    body: Value,
    outcome: Value,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt {
    body: Value,
    outcome: Value,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct State {
    configuration: String,
    artifact: String,
    cursor: String,
    commands: BTreeMap<String, Command>,
    latest: BTreeMap<String, i32>,
    receipts: BTreeMap<String, Receipt>,
    pending: Option<Acknowledgement>,
}

trait Runtime {
    fn now(&self) -> Result<i64, String>;
    fn wait(&mut self, stop: &StopToken, seconds: u64) -> Result<(), String>;
    fn request(&mut self, method: &str, path: &str, body: &[u8]) -> Result<SiteResponse, String>;
    fn conform(&mut self, job: &str) -> Result<Value, String>;
    fn execute(&mut self, plan: &BoundedPlan, stop: &StopToken) -> Result<Value, RunError>;
    fn forward(
        &mut self,
        plan: &BoundedPlan,
        stop: &StopToken,
        records: u64,
    ) -> Result<Value, RunError>;
    fn status(
        &mut self,
        plan: &BoundedPlan,
        state: &str,
        records: u64,
        error: Option<&str>,
    ) -> Result<(), String>;
    fn terminal_status(&mut self, plan: &BoundedPlan, value: &Value) -> Result<(), String>;
}

enum Failure {
    Control(String),
    Run(RunError),
}

impl From<RunError> for Failure {
    fn from(error: RunError) -> Self {
        Self::Run(error)
    }
}
impl From<String> for Failure {
    fn from(error: String) -> Self {
        Self::Run(RunError::Runtime(error))
    }
}
impl From<Refusal> for Failure {
    fn from(error: Refusal) -> Self {
        Self::Run(RunError::Refused(error))
    }
}

struct Live<'a> {
    config: &'a SiteConfig,
    artifact: &'a str,
    client: SiteClient,
}

impl Runtime for Live<'_> {
    fn now(&self) -> Result<i64, String> {
        i64::try_from(OffsetDateTime::now_utc().unix_timestamp_nanos() / 1000)
            .map_err(|_| "agent clock exceeds supported microseconds".into())
    }
    fn wait(&mut self, stop: &StopToken, seconds: u64) -> Result<(), String> {
        let start = Instant::now();
        wait_with(
            stop,
            seconds,
            || start.elapsed(),
            |duration| stop.wait(duration),
        )
    }
    fn request(&mut self, method: &str, path: &str, body: &[u8]) -> Result<SiteResponse, String> {
        self.client.request(method, path, body)
    }
    fn conform(&mut self, job: &str) -> Result<Value, String> {
        crate::conformance::conform_once(self.config, self.artifact, job)
    }
    fn execute(&mut self, plan: &BoundedPlan, stop: &StopToken) -> Result<Value, RunError> {
        heartbeat(self.config, self.artifact, plan, stop, 0, || {
            serde_json::to_value(crate::federation::execute(plan, self.config, stop)?)
                .map_err(|error| RunError::Runtime(format!("cannot encode site outcome: {error}")))
        })
    }
    fn forward(
        &mut self,
        plan: &BoundedPlan,
        stop: &StopToken,
        records: u64,
    ) -> Result<Value, RunError> {
        heartbeat(self.config, self.artifact, plan, stop, records, || {
            serde_json::to_value(crate::forwarder::forward(plan, self.config)?).map_err(|error| {
                RunError::Runtime(format!("cannot encode forward outcome: {error}"))
            })
        })
    }
    fn status(
        &mut self,
        plan: &BoundedPlan,
        state: &str,
        records: u64,
        error: Option<&str>,
    ) -> Result<(), String> {
        publish_status(
            &self.client,
            self.config,
            self.artifact,
            plan,
            state,
            records,
            error,
        )
    }
    fn terminal_status(&mut self, plan: &BoundedPlan, value: &Value) -> Result<(), String> {
        send_status(&self.client, self.config, plan, value)
    }
}

fn wait_with(
    stop: &StopToken,
    seconds: u64,
    mut elapsed: impl FnMut() -> Duration,
    mut pause: impl FnMut(Duration) -> Result<(), String>,
) -> Result<(), String> {
    let interval = Duration::from_secs(seconds);
    loop {
        if stop.is_requested()? {
            return Ok(());
        }
        let elapsed = elapsed();
        if elapsed >= interval {
            return Ok(());
        }
        pause((interval - elapsed).min(Duration::from_secs(1)))?;
    }
}

fn client(config: &SiteConfig) -> Result<SiteClient, RunError> {
    config.check_tls_pins()?;
    SiteClient::new(
        config.driver_endpoint(),
        config.ca(),
        config.certificate(),
        config.private_key(),
    )
    .map_err(RunError::Runtime)
}

pub fn run(config: &SiteConfig, artifact: &str, stop: &StopToken) -> Result<Value, RunError> {
    let mut runtime = Live {
        config,
        artifact,
        client: client(config)?,
    };
    run_with(config, artifact, stop, &mut runtime)
}

fn run_with(
    config: &SiteConfig,
    artifact: &str,
    stop: &StopToken,
    runtime: &mut impl Runtime,
) -> Result<Value, RunError> {
    let (store, mut state) = load(config, artifact)?;
    while !stop.is_requested()? {
        let delay = match poll_once(config, artifact, stop, &store, &mut state, runtime) {
            Ok(_) => 30,
            Err(Failure::Control(error)) => {
                eprintln!("{error}");
                300
            }
            Err(Failure::Run(error)) => return Err(error),
        };
        runtime.wait(stop, delay)?;
    }
    Ok(json!({"schema":"opentsx.rust-agent-outcome.v1","state":"stopped","cursor":state.cursor}))
}

pub fn run_signed(
    compact: &str,
    config: &SiteConfig,
    artifact: &str,
    stop: &StopToken,
) -> Result<Value, RunError> {
    let plan = BoundedPlan::verify(compact, config, artifact)?;
    let (store, mut state) = load(config, artifact)?;
    let key = command_key(plan.job(), plan.version());
    if let Some(saved) = state.commands.get(&key) {
        same_command(saved, &plan)?;
        if saved.phase == "done" {
            return recorded(
                saved
                    .outcome
                    .as_ref()
                    .ok_or("saved terminal outcome is missing")?,
            )
            .and_then(signed_outcome);
        }
    }
    let mut runtime = Live {
        config,
        artifact,
        client: client(config)?,
    };
    process(
        &plan,
        compact,
        config,
        artifact,
        stop,
        &store,
        &mut state,
        &mut runtime,
    )
    .and_then(|outcome| signed_outcome(outcome).map_err(Failure::Run))
    .map_err(|error| match error {
        Failure::Run(error) => error,
        Failure::Control(error) => RunError::Runtime(error),
    })
}

fn load(config: &SiteConfig, artifact: &str) -> Result<(Store, State), RunError> {
    let store = Store::open(&config.directory().join("agent.json"), STATE_BYTES)?;
    let state = match store.load_document(SCHEMA)? {
        Some(value) => serde_json::from_value(value)
            .map_err(|error| format!("invalid agent checkpoint: {error}"))?,
        None => State {
            configuration: config.identity_hash().into(),
            artifact: artifact.into(),
            cursor: "0".into(),
            commands: BTreeMap::new(),
            latest: BTreeMap::new(),
            receipts: BTreeMap::new(),
            pending: None,
        },
    };
    validate(&state, config, artifact)?;
    Ok((store, state))
}

fn validate(state: &State, config: &SiteConfig, artifact: &str) -> Result<(), RunError> {
    sequence(&state.cursor)?;
    if state.configuration != config.identity_hash() || state.artifact != artifact {
        return Err("agent checkpoint authority differs from local configuration".into());
    }
    let mut latest = BTreeMap::new();
    for (key, command) in &state.commands {
        let plan = BoundedPlan::verify(&command.compact, config, artifact)?;
        if key != &command_key(plan.job(), plan.version())
            || command.job != plan.job()
            || command.version != plan.version()
            || command.hash != plan.command_hash()
            || !["execution", "forward", "terminal", "done"].contains(&command.phase.as_str())
            || ["terminal", "done"].contains(&command.phase.as_str()) != command.outcome.is_some()
            || command.outcome.is_some() != command.terminal.is_some()
        {
            return Err("agent checkpoint command identity or phase differs".into());
        }
        if let Some(outcome) = &command.outcome {
            validate_outcome(outcome)?;
            validate_terminal(
                command
                    .terminal
                    .as_ref()
                    .ok_or("saved terminal status is missing")?,
                command,
                &plan,
                config,
                artifact,
            )?;
        }
        latest
            .entry(command.job.clone())
            .and_modify(|version: &mut i32| *version = (*version).max(command.version))
            .or_insert(command.version);
    }
    if latest != state.latest
        || state
            .commands
            .values()
            .filter(|command| command.phase != "done")
            .count()
            > 1
    {
        return Err("agent checkpoint command inventory differs".into());
    }
    if let Some(pending) = &state.pending {
        if sequence(&pending.sequence)? <= sequence(&state.cursor)? {
            return Err("agent pending acknowledgement is behind its cursor".into());
        }
        validate_ack(&pending.body)?;
        validate_outcome(&pending.outcome)?;
        let key = receipt_key(&pending.body)?;
        if state.receipts.get(&key).is_none_or(|receipt| {
            receipt.body != pending.body || receipt.outcome != pending.outcome
        }) {
            return Err("agent pending acknowledgement differs from its receipt".into());
        }
    }
    for (key, receipt) in &state.receipts {
        validate_ack(&receipt.body)?;
        validate_outcome(&receipt.outcome)?;
        if key != &receipt_key(&receipt.body)?
            || (receipt.body["result"] == "refused") != (receipt.outcome["result"] == "refused")
        {
            return Err("agent receipt identity differs".into());
        }
        if receipt.body["result"] == "refused" {
            if receipt.body["code"] != receipt.outcome["code"]
                || receipt.body["message"] != receipt.outcome["message"]
            {
                return Err("agent refusal receipt differs".into());
            }
        } else {
            let command = state
                .commands
                .get(&command_key(
                    text_field(&receipt.body, "job")?,
                    receipt.body["planVersion"]
                        .as_i64()
                        .ok_or("invalid receipt version")? as i32,
                ))
                .ok_or("accepted receipt has no accepted command")?;
            if command.hash != receipt.body["commandHash"]
                || command.outcome.as_ref() != Some(&receipt.outcome)
            {
                return Err("accepted receipt differs from recorded command".into());
            }
        }
    }
    Ok(())
}

fn save(store: &Store, state: &State) -> Result<(), String> {
    let value = serde_json::to_value(state)
        .map_err(|error| format!("cannot encode agent checkpoint: {error}"))?;
    store.commit_document(SCHEMA, &value)
}

fn reserve_outcome(state: &State) -> Result<(), String> {
    let bytes = serde_json::to_vec(state)
        .map_err(|error| format!("cannot encode accepted intent: {error}"))?;
    if bytes.len() > STATE_BYTES - 65_536 {
        return Err("agent checkpoint cannot reserve its outcome and acknowledgement".into());
    }
    Ok(())
}

fn signed_outcome(outcome: Value) -> Result<Value, RunError> {
    if outcome["result"] == "failed" {
        return Err(text_field(&outcome, "message")?.to_string().into());
    }
    if outcome["state"] == "waiting" {
        let error = outcome["error"]
            .as_str()
            .ok_or("pending forwarding outcome has no error")?;
        return Err(format!("aggregate forwarding is pending: {error}").into());
    }
    Ok(outcome)
}

fn poll_once(
    config: &SiteConfig,
    artifact: &str,
    stop: &StopToken,
    store: &Store,
    state: &mut State,
    runtime: &mut impl Runtime,
) -> Result<Option<Value>, Failure> {
    if state.pending.is_some() {
        return acknowledge(config, store, state, runtime).map(Some);
    }
    if let Some(command) = state
        .commands
        .values()
        .find(|command| command.phase != "done")
        .cloned()
    {
        let plan = BoundedPlan::verify(&command.compact, config, artifact)?;
        let outcome = process(
            &plan,
            &command.compact,
            config,
            artifact,
            stop,
            store,
            state,
            runtime,
        )?;
        if outcome["state"] == "waiting" {
            return Ok(Some(outcome));
        }
    }
    let path = format!("/v1/sites/{}/plans?after={}", config.site(), state.cursor);
    let mut response = runtime
        .request("GET", &path, &[])
        .map_err(Failure::Control)?;
    if response.status == 409 {
        for job in config.jobs() {
            let conformance = match runtime.conform(job) {
                Ok(value) => value,
                Err(error) if error == crate::conformance::UNKNOWN_JOB => {
                    eprintln!("no published plan for permitted job {job}");
                    continue;
                }
                Err(error) => return Err(Failure::Control(error)),
            };
            if conformance["result"] != "accepted"
                || conformance["challenge"]["contract"]["job"] != *job
            {
                return Err(Failure::Control(format!(
                    "job conformance was not accepted for {job}"
                )));
            }
        }
        response = runtime
            .request("GET", &path, &[])
            .map_err(Failure::Control)?;
    }
    if response.status != 200 {
        return Err(Failure::Control(format!(
            "plan delivery HTTP status {}",
            response.status
        )));
    }
    let page = parse(&response.body)?;
    fields(&page, &["deliveries", "next"])?;
    let rows = page["deliveries"]
        .as_array()
        .ok_or_else(|| invalid("delivery inventory is invalid"))?;
    if rows.len() > 1 {
        return Err(invalid("delivery page exceeds one entry").into());
    }
    let next = text_field(&page, "next")?;
    if rows.is_empty() {
        if next != state.cursor {
            return Err(invalid("empty delivery page changes its cursor").into());
        }
        return Ok(None);
    }
    let row = &rows[0];
    fields(row, &["sequence", "job", "planVersion", "plan"])?;
    let delivered = text_field(row, "sequence")?;
    let job = text_field(row, "job")?;
    let version = row["planVersion"]
        .as_i64()
        .filter(|value| (1..=i32::MAX as i64).contains(value))
        .ok_or_else(|| invalid("delivery plan version is invalid"))? as i32;
    if !identifier(job) || sequence(delivered)? <= sequence(&state.cursor)? || next != delivered {
        return Err(invalid("delivery envelope or cursor is invalid").into());
    }
    let compact = text_field(row, "plan")?;
    let hash = tsx_contracts::sha256(compact);
    let receipt_id = format!("{job}/{version}/{hash}");
    let result = if let Some(receipt) = state.receipts.get(&receipt_id) {
        recorded(&receipt.outcome).map_err(Failure::Run)
    } else {
        match BoundedPlan::verify(compact, config, artifact) {
            Ok(plan) if plan.job() == job && plan.version() == version => process(
                &plan, compact, config, artifact, stop, store, state, runtime,
            ),
            Ok(_) => {
                Err(invalid("signed plan differs from authenticated delivery identity").into())
            }
            Err(error) => Err(error.into()),
        }
    };
    let (ack, outcome) = match result {
        Ok(outcome) if outcome["state"] == "waiting" => return Ok(Some(outcome)),
        Ok(outcome) => (
            json!({"job":job,"planVersion":version,"commandHash":hash,"result":"accepted"}),
            outcome,
        ),
        Err(Failure::Run(RunError::Refused(error))) => {
            let outcome = refusal_outcome(&error);
            (
                json!({"job":job,"planVersion":version,"commandHash":hash,"result":"refused","code":error.code,"message":error.message}),
                outcome,
            )
        }
        Err(error) => return Err(error),
    };
    state.receipts.insert(
        receipt_id,
        Receipt {
            body: ack.clone(),
            outcome: outcome.clone(),
        },
    );
    state.pending = Some(Acknowledgement {
        sequence: delivered.into(),
        body: ack,
        outcome,
    });
    save(store, state)?;
    acknowledge(config, store, state, runtime).map(Some)
}

fn acknowledge(
    config: &SiteConfig,
    store: &Store,
    state: &mut State,
    runtime: &mut impl Runtime,
) -> Result<Value, Failure> {
    let pending = state
        .pending
        .as_ref()
        .ok_or_else(|| "agent has no pending acknowledgement".to_string())?;
    let body = serde_json::to_vec(&pending.body)
        .map_err(|error| format!("cannot encode delivery acknowledgement: {error}"))?;
    let response = runtime
        .request(
            "POST",
            &format!(
                "/v1/sites/{}/deliveries/{}/ack",
                config.site(),
                pending.sequence
            ),
            &body,
        )
        .map_err(Failure::Control)?;
    if response.status != 204 || !response.body.is_empty() {
        return Err(Failure::Control(format!(
            "delivery acknowledgement HTTP status {}",
            response.status
        )));
    }
    let outcome = pending.outcome.clone();
    state.cursor = pending.sequence.clone();
    state.pending = None;
    save(store, state)?;
    Ok(outcome)
}

#[allow(clippy::too_many_arguments)]
fn process(
    plan: &BoundedPlan,
    compact: &str,
    config: &SiteConfig,
    artifact: &str,
    stop: &StopToken,
    store: &Store,
    state: &mut State,
    runtime: &mut impl Runtime,
) -> Result<Value, Failure> {
    let key = command_key(plan.job(), plan.version());
    if let Some(command) = state.commands.get(&key) {
        same_command(command, plan)?;
        if command.phase == "terminal" {
            return finish_terminal(plan, store, state, runtime);
        }
        if let Some(outcome) = &command.outcome {
            return recorded(outcome).map_err(Failure::Run);
        }
    } else {
        if state
            .commands
            .values()
            .any(|command| command.phase != "done")
        {
            return Err(
                Refusal::new("plan.busy", "another accepted assignment has not retired").into(),
            );
        }
        if state
            .latest
            .get(plan.job())
            .is_some_and(|version| *version > plan.version())
        {
            return Err(version_refusal().into());
        }
        let now = runtime.now()?;
        config.qualification_at(now, artifact)?;
        plan.activate_at(now)?;
        let conformance = runtime.conform(plan.job()).map_err(Failure::Control)?;
        let contract = &conformance["challenge"]["contract"];
        if conformance["result"] != "accepted"
            || contract["job"] != plan.job()
            || contract["planVersion"] != plan.version()
            || contract["commandHash"] != plan.command_hash()
        {
            return Err(version_refusal().into());
        }
        state.commands.insert(
            key.clone(),
            Command {
                job: plan.job().into(),
                version: plan.version(),
                hash: plan.command_hash().into(),
                compact: compact.into(),
                phase: "execution".into(),
                records: 0,
                outcome: None,
                terminal: None,
            },
        );
        state.latest.insert(plan.job().into(), plan.version());
        reserve_outcome(state)?;
        save(store, state)?;
    }
    let records = state.commands[&key].records;
    runtime
        .status(plan, "running", records, None)
        .map_err(Failure::Control)?;
    let work = (|| -> Result<Value, RunError> {
        if state.commands[&key].phase == "execution" {
            let outcome = runtime.execute(plan, stop)?;
            validate_execution(&outcome)?;
            state
                .commands
                .get_mut(&key)
                .ok_or("accepted command disappeared")?
                .records = outcome["inputRecords"]
                .as_u64()
                .ok_or("site execution records are invalid")?;
            if outcome["state"] == "stopped" {
                return Ok(outcome);
            }
            state
                .commands
                .get_mut(&key)
                .ok_or("accepted command disappeared")?
                .phase = "forward".into();
            save(store, state)?;
        }
        let outcome = runtime.forward(plan, stop, state.commands[&key].records)?;
        validate_forward(&outcome)?;
        Ok(outcome)
    })();
    let (outcome, status, error) = match work {
        Ok(outcome) => (outcome, "stopped", None),
        Err(error) => {
            let outcome = match &error {
                RunError::Refused(refusal) => refusal_outcome(refusal),
                RunError::Runtime(message) => json!({"result":"failed","message":message}),
            };
            (outcome, "failed", Some(error.to_string()))
        }
    };
    if outcome["state"] == "waiting" {
        return Ok(outcome);
    }
    let records = state.commands[&key].records;
    validate_outcome(&outcome)?;
    let terminal = status_document(
        config,
        artifact,
        plan,
        status,
        records,
        error.as_deref(),
        runtime.now()?,
    )?;
    let command = state
        .commands
        .get_mut(&key)
        .ok_or_else(|| "accepted command disappeared".to_string())?;
    command.phase = "terminal".into();
    command.outcome = Some(outcome);
    command.terminal = Some(terminal);
    save(store, state)?;
    finish_terminal(plan, store, state, runtime)
}

fn finish_terminal(
    plan: &BoundedPlan,
    store: &Store,
    state: &mut State,
    runtime: &mut impl Runtime,
) -> Result<Value, Failure> {
    let key = command_key(plan.job(), plan.version());
    let command = state
        .commands
        .get(&key)
        .ok_or_else(|| "accepted command disappeared".to_string())?;
    let terminal = command
        .terminal
        .as_ref()
        .ok_or_else(|| "saved terminal status is missing".to_string())?;
    if let Err(reporting) = runtime.terminal_status(plan, terminal) {
        let primary = terminal["error"]
            .as_str()
            .map(|error| format!("{error}; "))
            .unwrap_or_default();
        return Err(Failure::Control(format!(
            "{primary}terminal status was not acknowledged: {reporting}"
        )));
    }
    state
        .commands
        .get_mut(&key)
        .ok_or_else(|| "accepted command disappeared".to_string())?
        .phase = "done".into();
    save(store, state)?;
    recorded(
        state.commands[&key]
            .outcome
            .as_ref()
            .ok_or_else(|| "saved terminal outcome is missing".to_string())?,
    )
    .map_err(Failure::Run)
}

fn same_command(command: &Command, plan: &BoundedPlan) -> Result<(), Refusal> {
    if command.hash != plan.command_hash() {
        Err(version_refusal())
    } else {
        Ok(())
    }
}
fn version_refusal() -> Refusal {
    Refusal::new(
        "plan.version",
        "plan version is older or its accepted bytes changed",
    )
}
fn invalid(message: &str) -> Refusal {
    Refusal::new("plan.invalid", message)
}
fn command_key(job: &str, version: i32) -> String {
    format!("{job}/{version}")
}
fn receipt_key(body: &Value) -> Result<String, RunError> {
    Ok(format!(
        "{}/{}/{}",
        text_field(body, "job")?,
        body["planVersion"]
            .as_i64()
            .ok_or("invalid receipt version")?,
        text_field(body, "commandHash")?
    ))
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}
fn hex(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}
fn sequence(text: &str) -> Result<i64, String> {
    crate::admission::canonical_i64(text, "delivery sequence").and_then(|value| {
        if value >= 0 {
            Ok(value)
        } else {
            Err("delivery sequence is negative".into())
        }
    })
}
fn parse(bytes: &[u8]) -> Result<Value, Refusal> {
    let text = std::str::from_utf8(bytes).map_err(|_| invalid("delivery JSON is not UTF-8"))?;
    tsx_contracts::parse_json(text, crate::site_client::RESPONSE_BYTES)
        .map_err(|_| invalid("delivery JSON is invalid"))
}
fn refusal_outcome(error: &Refusal) -> Value {
    json!({"result":"refused","code":error.code,"message":error.message})
}
fn recorded(outcome: &Value) -> Result<Value, RunError> {
    if outcome["result"] == "refused" {
        let code = refusal_code(
            outcome["code"]
                .as_str()
                .ok_or("saved refusal code is invalid")?,
        )?;
        Err(Refusal::new(
            code,
            outcome["message"]
                .as_str()
                .ok_or("saved refusal message is invalid")?,
        )
        .into())
    } else {
        Ok(outcome.clone())
    }
}
fn refusal_code(code: &str) -> Result<&'static str, String> {
    [
        "plan.signature",
        "plan.expired",
        "plan.version",
        "plan.site",
        "plan.catalog",
        "plan.invalid",
        "plan.policy",
        "plan.busy",
    ]
    .into_iter()
    .find(|candidate| *candidate == code)
    .ok_or_else(|| "saved refusal code is invalid".into())
}
fn validate_ack(value: &Value) -> Result<(), RunError> {
    if value["result"] == "accepted" {
        fields(value, &["job", "planVersion", "commandHash", "result"])?;
    } else if value["result"] == "refused" {
        fields(
            value,
            &[
                "job",
                "planVersion",
                "commandHash",
                "result",
                "code",
                "message",
            ],
        )?;
        refusal_code(text_field(value, "code")?)?;
        text_field(value, "message")?;
    } else {
        return Err("saved acknowledgement result is invalid".into());
    }
    if !identifier(text_field(value, "job")?)
        || value["planVersion"]
            .as_i64()
            .filter(|v| (1..=i32::MAX as i64).contains(v))
            .is_none()
        || !hex(text_field(value, "commandHash")?)
    {
        return Err("saved acknowledgement identity is invalid".into());
    }
    Ok(())
}
fn validate_execution(value: &Value) -> Result<(), String> {
    fields(
        value,
        &[
            "state",
            "completed",
            "inputRecords",
            "outputRecords",
            "increments",
        ],
    )
    .map_err(|e| e.to_string())?;
    if !["completed", "stopped"]
        .iter()
        .any(|state| value["state"] == *state)
        || value["completed"].as_bool() != Some(value["state"] == "completed")
        || ["inputRecords", "outputRecords", "increments"]
            .iter()
            .any(|field| value[field].as_u64().is_none())
    {
        return Err("site execution outcome is invalid".into());
    }
    Ok(())
}
fn validate_forward(value: &Value) -> Result<(), String> {
    fields(value, &["state", "completed", "forwardedRecords", "error"])
        .map_err(|e| e.to_string())?;
    if !["completed", "waiting"]
        .iter()
        .any(|state| value["state"] == *state)
        || value["completed"].as_bool() != Some(value["state"] == "completed")
        || value["forwardedRecords"].as_u64().is_none()
        || !(value["error"].is_null()
            || value["error"]
                .as_str()
                .is_some_and(|text| !text.is_empty() && text.len() <= 4096))
    {
        return Err("aggregate forwarding outcome is invalid".into());
    }
    Ok(())
}
fn validate_outcome(value: &Value) -> Result<(), RunError> {
    if value["result"] == "failed" {
        fields(value, &["result", "message"])?;
        if text_field(value, "message")?.len() > 8192 {
            return Err("saved runtime failure exceeds its byte limit".into());
        }
        return Ok(());
    }
    if value["result"] == "refused" {
        fields(value, &["result", "code", "message"])?;
        refusal_code(text_field(value, "code")?)?;
        text_field(value, "message")?;
        return Ok(());
    }
    if value.get("inputRecords").is_some() {
        validate_execution(value)?;
    } else {
        validate_forward(value)?;
    }
    if value["state"] == "waiting" {
        return Err("saved terminal outcome is waiting".into());
    }
    Ok(())
}

fn publish_status(
    client: &SiteClient,
    config: &SiteConfig,
    artifact: &str,
    plan: &BoundedPlan,
    state: &str,
    records: u64,
    error: Option<&str>,
) -> Result<(), String> {
    let now = i64::try_from(OffsetDateTime::now_utc().unix_timestamp_nanos() / 1000)
        .map_err(|_| "status clock exceeds supported microseconds")?;
    let value = status_document(config, artifact, plan, state, records, error, now)?;
    send_status(client, config, plan, &value)
}

#[allow(clippy::too_many_arguments)]
fn status_document(
    config: &SiteConfig,
    artifact: &str,
    plan: &BoundedPlan,
    state: &str,
    records: u64,
    error: Option<&str>,
    now: i64,
) -> Result<Value, String> {
    let at = OffsetDateTime::from_unix_timestamp_nanos(i128::from(now) * 1000)
        .map_err(|_| "status time exceeds supported range")?
        .format(&Rfc3339)
        .map_err(|_| "cannot format agent status timestamp")?;
    let mut value = json!({"schema":"opentsx.dag-status.v1","dag":plan.job(),"job":plan.job(),"site":config.site(),
        "planVersion":plan.version(),"track":"B","specHash":plan.document()["specHash"],"state":state,"records":records,
        "watermark":null,"waitingFor":[],"skippedBlocks":0,"at":at,"artifact":{"version":env!("CARGO_PKG_VERSION"),"digest":artifact}});
    if let Some(error) = error {
        value["error"] = json!(error);
    }
    Ok(value)
}

fn send_status(
    client: &SiteClient,
    config: &SiteConfig,
    plan: &BoundedPlan,
    value: &Value,
) -> Result<(), String> {
    let bytes = serde_json::to_vec(&value)
        .map_err(|error| format!("cannot encode agent status: {error}"))?;
    let response = client.request(
        "POST",
        &format!("/v1/sites/{}/jobs/{}/status", config.site(), plan.job()),
        &bytes,
    )?;
    if response.status != 204 || !response.body.is_empty() {
        return Err(format!("site status HTTP status {}", response.status));
    }
    Ok(())
}

fn validate_terminal(
    value: &Value,
    command: &Command,
    plan: &BoundedPlan,
    config: &SiteConfig,
    artifact: &str,
) -> Result<(), RunError> {
    let outcome = command
        .outcome
        .as_ref()
        .ok_or("saved terminal outcome is missing")?;
    let error = match outcome["result"].as_str() {
        Some("failed") => Some(text_field(outcome, "message")?.to_string()),
        Some("refused") => Some(format!(
            "{}: {}",
            text_field(outcome, "code")?,
            text_field(outcome, "message")?
        )),
        _ => None,
    };
    let now = crate::site_plan::instant(value, "at")?;
    let expected = status_document(
        config,
        artifact,
        plan,
        if error.is_some() { "failed" } else { "stopped" },
        command.records,
        error.as_deref(),
        now,
    )?;
    if value != &expected {
        return Err("saved terminal status differs from its outcome".into());
    }
    Ok(())
}

fn heartbeat(
    config: &SiteConfig,
    artifact: &str,
    plan: &BoundedPlan,
    stop: &StopToken,
    records: u64,
    execution: impl FnOnce() -> Result<Value, RunError>,
) -> Result<Value, RunError> {
    let client = client(config)?;
    let finished = StopToken::new();
    std::thread::scope(|scope| {
        let worker = scope.spawn(|| -> Result<(), String> {
            loop {
                finished.wait(Duration::from_secs(15))?;
                if finished.is_requested()? {
                    return Ok(());
                }
                if let Err(error) =
                    publish_status(&client, config, artifact, plan, "running", records, None)
                {
                    let stopping = stop.request();
                    return Err(match stopping {
                        Ok(()) => error,
                        Err(stopping) => format!("{error}; cannot stop execution: {stopping}"),
                    });
                }
            }
        });
        let result = execution();
        let stopping = finished.request();
        let health = worker
            .join()
            .map_err(|_| "agent heartbeat worker failed".to_string())
            .and_then(|value| value);
        match (result, stopping.and(health)) {
            (Ok(value), Ok(())) => Ok(value),
            (Err(error), Ok(())) => Err(error),
            (Ok(_), Err(error)) => Err(RunError::Runtime(error)),
            (Err(error), Err(reporting)) => Err(RunError::Runtime(format!(
                "{error}; heartbeat failed: {reporting}"
            ))),
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::federation::tests::Fixture;
    use std::collections::VecDeque;

    const ARTIFACT: &str =
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const NOW: i64 = 1_790_812_800_000_000;
    const EXPIRED: i64 = NOW + 366 * 86_400 * 1_000_000;

    struct Fake {
        now: i64,
        challenge: Value,
        pages: VecDeque<Value>,
        requests: Vec<(String, String, Vec<u8>)>,
        statuses: Vec<String>,
        status_records: Vec<u64>,
        conformations: usize,
        executions: usize,
        forwards: usize,
        failed_acks: usize,
        waiting: usize,
        unavailable: bool,
        delays: Vec<u64>,
        intent: std::path::PathBuf,
        busy: bool,
        needs_conformance: bool,
        missing_jobs: Vec<String>,
        terminal_received: Option<String>,
        terminal_version: i32,
        lost_terminal_responses: usize,
        execution_error: Option<String>,
        terminal_payloads: Vec<Value>,
    }

    impl Fake {
        fn new(fixture: &Fixture) -> Self {
            Self {
                now: NOW,
                challenge: json!({"result":"accepted","challenge":{"contract":{"job":fixture.plan.job(),"planVersion":fixture.plan.version(),"commandHash":fixture.plan.command_hash()}}}),
                pages: VecDeque::new(),
                requests: Vec::new(),
                statuses: Vec::new(),
                status_records: Vec::new(),
                conformations: 0,
                executions: 0,
                forwards: 0,
                failed_acks: 0,
                waiting: 0,
                unavailable: false,
                delays: Vec::new(),
                intent: fixture.config.directory().join("agent.json"),
                busy: false,
                needs_conformance: false,
                missing_jobs: Vec::new(),
                terminal_received: None,
                terminal_version: 0,
                lost_terminal_responses: 0,
                execution_error: None,
                terminal_payloads: Vec::new(),
            }
        }
        fn deliver(&mut self, fixture: &Fixture, compact: &str, sequence: &str) {
            self.pages.push_back(json!({"deliveries":[{"sequence":sequence,"job":fixture.plan.job(),"planVersion":fixture.plan.version(),"plan":compact}],"next":sequence}));
        }
        fn acknowledge(&self) -> Value {
            let request = self
                .requests
                .iter()
                .rev()
                .find(|(_, path, _)| path.ends_with("/ack"))
                .unwrap();
            serde_json::from_slice(&request.2).unwrap()
        }
    }

    impl Runtime for Fake {
        fn now(&self) -> Result<i64, String> {
            Ok(self.now)
        }
        fn wait(&mut self, stop: &StopToken, seconds: u64) -> Result<(), String> {
            self.delays.push(seconds);
            stop.request()
        }
        fn request(
            &mut self,
            method: &str,
            path: &str,
            body: &[u8],
        ) -> Result<SiteResponse, String> {
            self.requests
                .push((method.into(), path.into(), body.to_vec()));
            if self.unavailable {
                return Err("injected control disconnection".into());
            }
            if method == "GET" {
                if self.needs_conformance {
                    return Ok(SiteResponse { status: 409, body: b"{\"error\":\"request-refused\",\"message\":\"site conformance is required\"}".to_vec() });
                }
                return Ok(SiteResponse {
                    status: 200,
                    body: self
                        .pages
                        .pop_front()
                        .expect("unexpected delivery request")
                        .to_string()
                        .into_bytes(),
                });
            }
            if self.failed_acks > 0 {
                self.failed_acks -= 1;
                return Err("injected lost acknowledgement response".into());
            }
            Ok(SiteResponse {
                status: 204,
                body: Vec::new(),
            })
        }
        fn conform(&mut self, job: &str) -> Result<Value, String> {
            self.conformations += 1;
            if self.missing_jobs.iter().any(|value| value == job) {
                return Err(crate::conformance::UNKNOWN_JOB.into());
            }
            self.needs_conformance = false;
            Ok(self.challenge.clone())
        }
        fn execute(&mut self, plan: &BoundedPlan, _stop: &StopToken) -> Result<Value, RunError> {
            self.executions += 1;
            if let Some(error) = &self.execution_error {
                return Err(error.clone().into());
            }
            let envelope: Value =
                serde_json::from_slice(&std::fs::read(&self.intent).unwrap()).unwrap();
            assert_eq!(
                envelope["payload"]["commands"][command_key(plan.job(), plan.version())]["phase"],
                "execution"
            );
            if self.busy {
                let _reservation = crate::run_reservation::acquire(
                    plan.job(),
                    "127.0.0.1:39092",
                    "tsx.b3test.episodes.v2",
                )?;
            }
            Ok(
                json!({"state":"completed","completed":true,"inputRecords":2,"outputRecords":1,"increments":2}),
            )
        }
        fn forward(
            &mut self,
            _plan: &BoundedPlan,
            _stop: &StopToken,
            records: u64,
        ) -> Result<Value, RunError> {
            self.forwards += 1;
            assert_eq!(records, 2);
            if self.waiting > 0 {
                self.waiting -= 1;
                return Ok(
                    json!({"state":"waiting","completed":false,"forwardedRecords":0,"error":"signal unavailable"}),
                );
            }
            Ok(json!({"state":"completed","completed":true,"forwardedRecords":1,"error":null}))
        }
        fn status(
            &mut self,
            plan: &BoundedPlan,
            state: &str,
            records: u64,
            _error: Option<&str>,
        ) -> Result<(), String> {
            self.statuses.push(state.into());
            self.status_records.push(records);
            if state == "running"
                && self.terminal_received.is_some()
                && self.terminal_version == plan.version()
            {
                return Err("terminal assignment cannot change state: HTTP409".into());
            }
            if ["stopped", "failed"].contains(&state) {
                self.terminal_received = Some(state.into());
                self.terminal_version = plan.version();
                if self.lost_terminal_responses > 0 {
                    self.lost_terminal_responses -= 1;
                    return Err("injected lost terminal response".into());
                }
            }
            Ok(())
        }
        fn terminal_status(&mut self, plan: &BoundedPlan, value: &Value) -> Result<(), String> {
            self.terminal_payloads.push(value.clone());
            self.status(
                plan,
                value["state"].as_str().ok_or("terminal state missing")?,
                value["records"]
                    .as_u64()
                    .ok_or("terminal records missing")?,
                value["error"].as_str(),
            )
        }
    }

    fn poll(fixture: &Fixture, runtime: &mut Fake) -> Result<Option<Value>, Failure> {
        let (store, mut state) = load(&fixture.config, ARTIFACT).map_err(Failure::Run)?;
        poll_once(
            &fixture.config,
            ARTIFACT,
            &StopToken::new(),
            &store,
            &mut state,
            runtime,
        )
    }
    fn compact(fixture: &Fixture) -> String {
        fixture.sign_document(fixture.plan.document())
    }
    fn ok(result: Result<Option<Value>, Failure>) -> Value {
        match result {
            Ok(Some(value)) => value,
            Ok(None) => panic!("missing outcome"),
            Err(Failure::Control(error)) => panic!("{error}"),
            Err(Failure::Run(error)) => panic!("{error}"),
        }
    }

    #[test]
    fn agent_delivery_persists_intent_outcome_and_acknowledged_cursor() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.deliver(&fixture, &compact(&fixture), "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["state"], "completed");
        assert_eq!(
            (runtime.conformations, runtime.executions, runtime.forwards),
            (1, 1, 1)
        );
        assert_eq!(runtime.statuses, ["running", "stopped"]);
        assert_eq!(runtime.status_records, [0, 2]);
        let (_, state) = load(&fixture.config, ARTIFACT).unwrap();
        assert_eq!(state.cursor, "1");
        assert!(state.pending.is_none());
        assert_eq!(state.commands.len(), 1);
        assert_eq!(runtime.acknowledge()["result"], "accepted");
    }

    #[test]
    fn agent_lost_ack_response_replays_exact_bytes_before_expired_conformance_or_page() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.failed_acks = 1;
        runtime.deliver(&fixture, &compact(&fixture), "7");
        assert!(matches!(
            poll(&fixture, &mut runtime),
            Err(Failure::Control(_))
        ));
        let original = runtime.requests.last().unwrap().2.clone();
        {
            let (_, state) = load(&fixture.config, ARTIFACT).unwrap();
            assert_eq!(state.cursor, "0");
            assert!(state.pending.is_some());
        }
        runtime.now = EXPIRED;
        runtime.challenge = Value::Null;
        assert_eq!(ok(poll(&fixture, &mut runtime))["state"], "completed");
        assert_eq!(runtime.requests.last().unwrap().2, original);
        assert_eq!(
            (runtime.conformations, runtime.executions, runtime.forwards),
            (1, 1, 1)
        );
        assert_eq!(runtime.requests.len(), 3);
    }

    #[test]
    fn agent_forward_recovery_does_not_repeat_completed_site_work() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.waiting = 1;
        let signed = compact(&fixture);
        runtime.deliver(&fixture, &signed, "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["state"], "waiting");
        assert_eq!(runtime.requests.len(), 1);
        runtime.now = EXPIRED;
        runtime.challenge = Value::Null;
        runtime.deliver(&fixture, &signed, "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["state"], "completed");
        assert_eq!(
            (runtime.conformations, runtime.executions, runtime.forwards),
            (1, 1, 2)
        );
    }

    #[test]
    fn agent_refusals_use_authenticated_envelope_and_exact_codes() {
        for code in [
            "plan.signature",
            "plan.expired",
            "plan.site",
            "plan.catalog",
            "plan.invalid",
            "plan.policy",
        ] {
            let fixture = Fixture::new();
            let mut runtime = Fake::new(&fixture);
            let mut document = fixture.plan.document().clone();
            match code {
                "plan.signature" => document["job"] = json!("untrusted-payload-job"),
                "plan.expired" => document["expires"] = json!("2026-01-02T00:00:00Z"),
                "plan.site" => document["site"] = json!("different-site"),
                "plan.catalog" => document["catalog"] = json!("tsx-catalog/2"),
                "plan.invalid" => {
                    document.as_object_mut().unwrap().remove("federation");
                }
                "plan.policy" => document["outputs"][0]["cluster"] = json!("signal"),
                _ => unreachable!(),
            }
            let mut signed = fixture.sign_document(&document);
            if code == "plan.signature" {
                let last = signed.pop().unwrap();
                signed.push(if last == 'A' { 'B' } else { 'A' });
            }
            runtime.deliver(&fixture, &signed, "1");
            let outcome = ok(poll(&fixture, &mut runtime));
            assert_eq!(outcome["code"], code, "{code}");
            let ack = runtime.acknowledge();
            assert_eq!(ack["code"], code);
            assert_eq!(ack["job"], fixture.plan.job());
            assert_eq!(ack["planVersion"], 1);
            assert_eq!(ack["commandHash"], tsx_contracts::sha256(&signed));
            assert_eq!(ack.as_object().unwrap().len(), 6);
            assert_eq!(runtime.executions, 0);
            assert_eq!(runtime.forwards, 0);
        }
    }

    #[test]
    fn agent_refusal_receipt_is_idempotent_after_expiry_and_cursor_advance() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        let mut document = fixture.plan.document().clone();
        document["catalog"] = json!("tsx-catalog/2");
        let signed = fixture.sign_document(&document);
        runtime.deliver(&fixture, &signed, "1");
        let first = ok(poll(&fixture, &mut runtime));
        runtime.now = EXPIRED;
        runtime.deliver(&fixture, &signed, "2");
        assert_eq!(ok(poll(&fixture, &mut runtime)), first);
        let (_, state) = load(&fixture.config, ARTIFACT).unwrap();
        assert!(serde_json::to_value(state).unwrap()["receipts"]
            .as_object()
            .is_some_and(|values| values.len() == 1));
    }

    #[test]
    fn agent_busy_uses_real_whole_topic_reservation() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.busy = true;
        let _owner = crate::run_reservation::acquire(
            fixture.plan.job(),
            "127.0.0.1:39092",
            "tsx.b3test.episodes.v2",
        )
        .unwrap();
        runtime.deliver(&fixture, &compact(&fixture), "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["code"], "plan.busy");
        assert_eq!(runtime.forwards, 0);
        assert_eq!(runtime.statuses, ["running", "failed"]);
    }

    #[test]
    fn agent_changed_accepted_bytes_and_stale_challenge_refuse_before_execution() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.deliver(&fixture, &compact(&fixture), "1");
        ok(poll(&fixture, &mut runtime));
        let mut document = fixture.plan.document().clone();
        document["issued"] = json!("2026-01-02T00:00:00Z");
        runtime.deliver(&fixture, &fixture.sign_document(&document), "2");
        assert_eq!(ok(poll(&fixture, &mut runtime))["code"], "plan.version");
        assert_eq!(runtime.executions, 1);
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.challenge["challenge"]["contract"]["commandHash"] = json!("0".repeat(64));
        runtime.deliver(&fixture, &compact(&fixture), "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["code"], "plan.version");
        assert_eq!(runtime.executions, 0);
    }

    #[test]
    fn agent_signed_identity_cannot_replace_authenticated_delivery_identity() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.deliver(&fixture, &compact(&fixture), "1");
        runtime.pages[0]["deliveries"][0]["job"] = json!("authenticated-other-job");
        assert_eq!(ok(poll(&fixture, &mut runtime))["code"], "plan.invalid");
        assert_eq!(runtime.acknowledge()["job"], "authenticated-other-job");
        assert_eq!(runtime.conformations, 0);
    }

    #[test]
    fn agent_corrupt_or_rebound_checkpoint_fails_without_control_io() {
        let fixture = Fixture::new();
        std::fs::write(fixture.config.directory().join("agent.json"), b"corrupt").unwrap();
        assert!(load(&fixture.config, ARTIFACT).is_err());
        std::fs::remove_file(fixture.config.directory().join("agent.json")).unwrap();
        {
            let (store, mut state) = load(&fixture.config, ARTIFACT).unwrap();
            state.artifact = format!("sha256:{}", "b".repeat(64));
            save(&store, &state).unwrap();
        }
        assert!(load(&fixture.config, ARTIFACT).is_err());
    }

    #[test]
    fn agent_poll_and_disconnection_backoff_use_injected_wait_without_sleep() {
        for (unavailable, expected) in [(false, 30), (true, 300)] {
            let fixture = Fixture::new();
            let mut runtime = Fake::new(&fixture);
            runtime.unavailable = unavailable;
            runtime.pages.push_back(json!({"deliveries":[],"next":"0"}));
            let outcome =
                run_with(&fixture.config, ARTIFACT, &StopToken::new(), &mut runtime).unwrap();
            assert_eq!(runtime.delays, [expected]);
            assert_eq!(outcome["state"], "stopped");
            assert_eq!(runtime.requests.len(), 1);
        }
    }

    fn versioned(fixture: &Fixture, version: i32) -> String {
        let mut document = fixture.plan.document().clone();
        document["planVersion"] = json!(version);
        let mut assignment: Value =
            serde_json::from_str(document["assignmentText"].as_str().unwrap()).unwrap();
        assignment["generation"] = json!(version.to_string());
        document["assignmentText"] = json!(assignment.to_string());
        document["federation"]["expectedAssignments"][0]["generation"] = json!(version.to_string());
        fixture.sign_document(&document)
    }
    fn deliver_version(
        runtime: &mut Fake,
        fixture: &Fixture,
        signed: &str,
        version: i32,
        sequence: &str,
    ) {
        runtime.deliver(fixture, signed, sequence);
        runtime.pages.back_mut().unwrap()["deliveries"][0]["planVersion"] = json!(version);
        runtime.challenge["challenge"]["contract"]["planVersion"] = json!(version);
        runtime.challenge["challenge"]["contract"]["commandHash"] =
            json!(tsx_contracts::sha256(signed));
    }

    #[test]
    fn agent_retains_older_identical_outcome_but_refuses_unseen_stale_activation() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        let first = compact(&fixture);
        runtime.deliver(&fixture, &first, "1");
        let saved = ok(poll(&fixture, &mut runtime));
        let third = versioned(&fixture, 3);
        deliver_version(&mut runtime, &fixture, &third, 3, "2");
        ok(poll(&fixture, &mut runtime));
        let second = versioned(&fixture, 2);
        deliver_version(&mut runtime, &fixture, &second, 2, "3");
        assert_eq!(ok(poll(&fixture, &mut runtime))["code"], "plan.version");
        runtime.now = EXPIRED;
        runtime.challenge = Value::Null;
        runtime.deliver(&fixture, &first, "4");
        assert_eq!(ok(poll(&fixture, &mut runtime)), saved);
        assert_eq!(
            (runtime.executions, runtime.forwards, runtime.conformations),
            (2, 2, 2)
        );
        let (_, state) = load(&fixture.config, ARTIFACT).unwrap();
        assert_eq!(state.commands.len(), 2);
        assert_eq!(state.latest[fixture.plan.job()], 3);
    }

    #[test]
    fn agent_semantically_changed_pending_ack_fails_before_network_io() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.failed_acks = 1;
        runtime.deliver(&fixture, &compact(&fixture), "1");
        assert!(matches!(
            poll(&fixture, &mut runtime),
            Err(Failure::Control(_))
        ));
        {
            let (store, mut state) = load(&fixture.config, ARTIFACT).unwrap();
            state.pending.as_mut().unwrap().body["commandHash"] = json!("b".repeat(64));
            save(&store, &state).unwrap();
        }
        assert!(load(&fixture.config, ARTIFACT).is_err());
        assert_eq!(runtime.requests.len(), 2);
    }

    #[test]
    fn agent_reserves_terminal_receipt_capacity_before_execution() {
        let fixture = Fixture::new();
        let (_, mut state) = load(&fixture.config, ARTIFACT).unwrap();
        assert!(reserve_outcome(&state).is_ok());
        state.artifact = "x".repeat(STATE_BYTES - 65_536);
        assert_eq!(
            reserve_outcome(&state).unwrap_err(),
            "agent checkpoint cannot reserve its outcome and acknowledgement"
        );
    }

    #[test]
    fn agent_pending_signed_forwarding_is_runtime_error_and_retains_continuation() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.waiting = 1;
        runtime.deliver(&fixture, &compact(&fixture), "1");
        let outcome = ok(poll(&fixture, &mut runtime));
        assert!(
            matches!(signed_outcome(outcome),Err(RunError::Runtime(error)) if error=="aggregate forwarding is pending: signal unavailable")
        );
        let (_, state) = load(&fixture.config, ARTIFACT).unwrap();
        assert_eq!(
            state.commands[&command_key(fixture.plan.job(), 1)].phase,
            "forward"
        );
        assert_eq!(
            state.commands[&command_key(fixture.plan.job(), 1)].records,
            2
        );
        assert!(state.pending.is_none());
    }

    #[test]
    fn agent_wait_slices_preserve_poll_interval_and_observe_stop_without_sleep() {
        use std::cell::Cell;
        for seconds in [30, 300] {
            let elapsed = Cell::new(Duration::ZERO);
            let mut pauses = Vec::new();
            wait_with(
                &StopToken::new(),
                seconds,
                || elapsed.get(),
                |duration| {
                    pauses.push(duration);
                    elapsed.set(elapsed.get() + duration);
                    Ok(())
                },
            )
            .unwrap();
            assert_eq!(elapsed.get(), Duration::from_secs(seconds));
            assert_eq!(pauses.len(), seconds as usize);
            assert!(pauses
                .iter()
                .all(|duration| *duration == Duration::from_secs(1)));
        }
        let stop = StopToken::new();
        let mut pauses = 0;
        wait_with(
            &stop,
            300,
            || Duration::ZERO,
            |duration| {
                assert_eq!(duration, Duration::from_secs(1));
                pauses += 1;
                stop.request()
            },
        )
        .unwrap();
        assert_eq!(pauses, 1);
    }

    #[test]
    fn agent_lost_stopped_response_retries_only_durable_terminal_status() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.lost_terminal_responses = 1;
        let signed = compact(&fixture);
        runtime.deliver(&fixture, &signed, "1");
        assert!(poll(&fixture, &mut runtime).is_err());
        runtime.now = EXPIRED;
        runtime.challenge = Value::Null;
        runtime.deliver(&fixture, &signed, "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["state"], "completed");
        assert_eq!(runtime.statuses, ["running", "stopped", "stopped"]);
        assert_eq!(
            (runtime.executions, runtime.forwards, runtime.conformations),
            (1, 1, 1)
        );
        assert_eq!(runtime.terminal_payloads.len(), 2);
        assert_eq!(runtime.terminal_payloads[0], runtime.terminal_payloads[1]);
        let (_, state) = load(&fixture.config, ARTIFACT).unwrap();
        assert_eq!(state.cursor, "1");
    }

    #[test]
    fn agent_lost_failed_response_retains_original_error_and_never_reexecutes() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.lost_terminal_responses = 1;
        runtime.execution_error = Some("injected primary execution failure".into());
        runtime.deliver(&fixture, &compact(&fixture), "1");
        match poll(&fixture, &mut runtime) {
            Err(Failure::Control(error)) => assert!(
                error.contains("injected primary execution failure")
                    && error.contains("injected lost terminal response")
            ),
            _ => panic!("primary and reporting errors were not retained"),
        }
        runtime.now = EXPIRED;
        runtime.challenge = Value::Null;
        runtime.deliver(&fixture, &compact(&fixture), "1");
        let outcome = ok(poll(&fixture, &mut runtime));
        assert!(
            matches!(signed_outcome(outcome),Err(RunError::Runtime(error)) if error=="injected primary execution failure")
        );
        assert_eq!(runtime.acknowledge()["result"], "accepted");
        assert_eq!(runtime.statuses, ["running", "failed", "failed"]);
        assert_eq!(
            (runtime.executions, runtime.forwards, runtime.conformations),
            (1, 0, 1)
        );
        assert_eq!(runtime.terminal_payloads.len(), 2);
        assert_eq!(runtime.terminal_payloads[0], runtime.terminal_payloads[1]);
    }

    #[test]
    fn agent_refreshes_local_job_conformance_before_retrying_a_blocked_delivery() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.needs_conformance = true;
        runtime.deliver(&fixture, &compact(&fixture), "1");
        ok(poll(&fixture, &mut runtime));
        assert_eq!(runtime.executions, 1);
        assert_eq!(
            runtime
                .requests
                .iter()
                .filter(|(method, _, _)| method == "GET")
                .count(),
            2
        );
        runtime.needs_conformance = true;
        let second = versioned(&fixture, 2);
        deliver_version(&mut runtime, &fixture, &second, 2, "2");
        ok(poll(&fixture, &mut runtime));
        assert_eq!(runtime.executions, 2);
        assert_eq!(
            runtime
                .requests
                .iter()
                .filter(|(method, _, _)| method == "GET")
                .count(),
            4
        );
    }

    #[test]
    fn unpublished_permitted_jobs_do_not_starve_other_local_jobs() {
        let mut fixture = Fixture::new();
        let path = fixture.directory.join("config.json");
        let mut document: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        document["jobs"] = json!(["future", "bounded-test"]);
        std::fs::write(&path, document.to_string()).unwrap();
        fixture.config = SiteConfig::load(&path).unwrap();
        fixture.plan = BoundedPlan::verify(&compact(&fixture), &fixture.config, ARTIFACT).unwrap();
        let mut runtime = Fake::new(&fixture);
        runtime.missing_jobs.push("future".into());
        runtime.needs_conformance = true;
        runtime.deliver(&fixture, &compact(&fixture), "1");
        ok(poll(&fixture, &mut runtime));
        assert_eq!(runtime.executions, 1);
        assert_eq!(runtime.conformations, 3);
    }

    #[test]
    fn another_unfinished_grant_refuses_before_conformance_or_saved_state_changes() {
        let fixture = Fixture::new();
        let mut runtime = Fake::new(&fixture);
        runtime.waiting = 1;
        runtime.deliver(&fixture, &compact(&fixture), "1");
        assert_eq!(ok(poll(&fixture, &mut runtime))["state"], "waiting");
        let signed = versioned(&fixture, 2);
        let plan = BoundedPlan::verify(&signed, &fixture.config, ARTIFACT).unwrap();
        let (store, mut state) = load(&fixture.config, ARTIFACT).unwrap();
        let before = std::fs::read(&runtime.intent).unwrap();
        let conformations = runtime.conformations;
        let result = process(
            &plan,
            &signed,
            &fixture.config,
            ARTIFACT,
            &StopToken::new(),
            &store,
            &mut state,
            &mut runtime,
        );
        assert!(
            matches!(result, Err(Failure::Run(RunError::Refused(error))) if error.code == "plan.busy")
        );
        assert_eq!(runtime.conformations, conformations);
        assert_eq!(std::fs::read(&runtime.intent).unwrap(), before);
    }
}
