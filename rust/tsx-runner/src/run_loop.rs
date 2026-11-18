use crate::admission::Position;
use crate::broker::Broker;
use crate::checkpoint::{CompletedPosition, Completion, Receipt, Store};
use crate::run_plan::{Refusal, RunConfig, SignedPlan};
use crate::runner;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Condvar, Mutex,
};
use std::thread::JoinHandle;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};

const STATE_ABI: &str = "opentsx.rust-run-state.v1";
const RECEIPT_ABI: &str = "opentsx.rust-run-receipt.v1";
const HEARTBEAT: Duration = Duration::from_secs(15);
const IDLE_POLL: Duration = Duration::from_secs(1);
static SIGNALLED: AtomicBool = AtomicBool::new(false);
static SIGNAL_OWNER: AtomicBool = AtomicBool::new(false);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Watermark {
    pub partition: i32,
    pub low: i64,
    pub high: i64,
}

pub trait RunBroker: Broker + Clone + Send + 'static {
    fn watermarks(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
    ) -> Result<Vec<Watermark>, String>;
    fn publish_status(
        &mut self,
        bootstrap: &str,
        topic: &str,
        key: &str,
        payload: &[u8],
    ) -> Result<(), String>;
}

#[derive(Debug)]
pub enum RunError {
    Refused(Refusal),
    Runtime(String),
}

impl std::fmt::Display for RunError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Refused(error) => write!(formatter, "{}: {}", error.code, error.message),
            Self::Runtime(error) => formatter.write_str(error),
        }
    }
}

impl std::error::Error for RunError {}
impl From<Refusal> for RunError {
    fn from(value: Refusal) -> Self {
        Self::Refused(value)
    }
}
impl From<String> for RunError {
    fn from(value: String) -> Self {
        Self::Runtime(value)
    }
}
impl From<&str> for RunError {
    fn from(value: &str) -> Self {
        Self::Runtime(value.into())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunOutcome {
    pub schema: &'static str,
    pub job: String,
    pub plan_version: i32,
    pub records: u64,
    pub output_records: u64,
    pub positions: Vec<CompletedPosition>,
}

#[derive(Clone, Default)]
pub struct StopToken {
    inner: Arc<(Mutex<bool>, Condvar)>,
}

impl StopToken {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn request(&self) -> Result<(), String> {
        *self.inner.0.lock().map_err(|_| "stop state is poisoned")? = true;
        self.inner.1.notify_all();
        Ok(())
    }
    pub fn is_requested(&self) -> Result<bool, String> {
        Ok(SIGNALLED.load(Ordering::SeqCst)
            || *self.inner.0.lock().map_err(|_| "stop state is poisoned")?)
    }
    fn wait(&self, duration: Duration) -> Result<(), String> {
        let state = self.inner.0.lock().map_err(|_| "stop state is poisoned")?;
        if *state || SIGNALLED.load(Ordering::SeqCst) {
            return Ok(());
        }
        let _state = self
            .inner
            .1
            .wait_timeout_while(state, duration, |stopped| {
                !*stopped && !SIGNALLED.load(Ordering::SeqCst)
            })
            .map_err(|_| "stop state is poisoned")?;
        Ok(())
    }
}

pub struct SignalGuard {
    previous: [(i32, libc::sigaction); 2],
    restored: bool,
}

extern "C" fn signal_stop(_: libc::c_int) {
    SIGNALLED.store(true, Ordering::SeqCst);
}

impl SignalGuard {
    pub fn install() -> Result<Self, String> {
        if SIGNAL_OWNER
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return Err("run signal handlers already have an owner".into());
        }
        SIGNALLED.store(false, Ordering::SeqCst);
        let mut action: libc::sigaction = unsafe { std::mem::zeroed() };
        action.sa_sigaction = signal_stop as *const () as usize;
        if unsafe { libc::sigemptyset(&mut action.sa_mask) } != 0 {
            SIGNAL_OWNER.store(false, Ordering::SeqCst);
            return Err(format!(
                "cannot initialize the signal mask: {}",
                std::io::Error::last_os_error()
            ));
        }
        let mut previous = [
            (libc::SIGINT, unsafe { std::mem::zeroed() }),
            (libc::SIGTERM, unsafe { std::mem::zeroed() }),
        ];
        for index in 0..previous.len() {
            if unsafe { libc::sigaction(previous[index].0, &action, &mut previous[index].1) } != 0 {
                let mut error = format!(
                    "cannot install a run signal handler: {}",
                    std::io::Error::last_os_error()
                );
                for (signal, old) in &previous[..index] {
                    if unsafe { libc::sigaction(*signal, old, std::ptr::null_mut()) } != 0 {
                        error.push_str(&format!(
                            "; cannot restore a signal handler: {}",
                            std::io::Error::last_os_error()
                        ));
                    }
                }
                SIGNAL_OWNER.store(false, Ordering::SeqCst);
                return Err(error);
            }
        }
        Ok(Self {
            previous,
            restored: false,
        })
    }
    pub fn restore(mut self) -> Result<(), String> {
        self.restore_inner()
    }
    fn restore_inner(&mut self) -> Result<(), String> {
        self.restored = true;
        let mut errors = Vec::new();
        for (signal, action) in &self.previous {
            if unsafe { libc::sigaction(*signal, action, std::ptr::null_mut()) } != 0 {
                errors.push(format!(
                    "cannot restore a run signal handler: {}",
                    std::io::Error::last_os_error()
                ));
            }
        }
        SIGNAL_OWNER.store(false, Ordering::SeqCst);
        if errors.is_empty() {
            Ok(())
        } else {
            Err(errors.join("; "))
        }
    }
}

impl Drop for SignalGuard {
    fn drop(&mut self) {
        if !self.restored {
            if let Err(error) = self.restore_inner() {
                eprintln!("{error}");
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Identity {
    command_hash: String,
    config_hash: String,
    assignment_hash: String,
    job: String,
    version: i32,
    artifact: String,
    starts: Vec<CompletedPosition>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum Phase {
    Active,
    Stopped,
    Failed,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Saved {
    schema: String,
    identity: Identity,
    phase: Phase,
    error: Option<String>,
    positions: Vec<CompletedPosition>,
    pipeline: Value,
    owners: BTreeMap<String, i32>,
    cursor: usize,
    sequence: u64,
    records: u64,
    outputs: u64,
}

impl Saved {
    fn outcome(&self) -> RunOutcome {
        RunOutcome {
            schema: "opentsx.rust-run-outcome.v1",
            job: self.identity.job.clone(),
            plan_version: self.identity.version,
            records: self.records,
            output_records: self.outputs,
            positions: self.positions.clone(),
        }
    }
}

#[derive(Clone)]
struct StatusContext {
    bootstrap: String,
    topic: String,
    job: String,
    site: String,
    version: i32,
    spec_hash: String,
    artifact: String,
    input: String,
}

struct Health<B> {
    broker: B,
    context: StatusContext,
    state: &'static str,
    records: u64,
    error: Option<String>,
    failure: Option<String>,
    done: bool,
    clock: fn() -> Result<i64, String>,
}

impl<B: RunBroker> Health<B> {
    fn check(&self) -> Result<(), String> {
        match &self.failure {
            Some(error) => Err(error.clone()),
            None => Ok(()),
        }
    }
    fn tick(&mut self) -> Result<(), String> {
        self.check()?;
        let result = (|| {
            let at = OffsetDateTime::from_unix_timestamp_nanos(i128::from((self.clock)()?) * 1_000)
                .map_err(|_| "status time is outside the supported range")?
                .format(&Rfc3339)
                .map_err(|_| "cannot encode the status timestamp")?;
            let context = &self.context;
            let mut record = json!({"schema":"opentsx.dag-status.v1","dag":context.job,"job":context.job,
                "site":context.site,"planVersion":context.version,"track":"B","topic":context.input,
                "state":self.state,"specHash":context.spec_hash,"records":self.records,"watermark":null,
                "waitingFor":[],"waitingForCount":0,"skippedBlocks":0,"at":at,
                "artifact":{"version":env!("CARGO_PKG_VERSION"),"digest":context.artifact}});
            if let Some(error) = &self.error {
                record["error"] = json!(error);
            }
            let bytes = serde_json::to_vec(&record)
                .map_err(|error| format!("cannot encode run status: {error}"))?;
            self.broker
                .publish_status(&context.bootstrap, &context.topic, &context.job, &bytes)
        })();
        if let Err(error) = &result {
            self.failure = Some(format!("run status was not acknowledged: {error}"));
        }
        self.check()
    }
    fn transition(
        &mut self,
        state: &'static str,
        records: u64,
        error: Option<String>,
    ) -> Result<(), String> {
        self.check()?;
        let changed = self.state != state || self.error != error;
        self.state = state;
        self.records = records;
        self.error = error;
        if changed {
            self.tick()?;
        }
        self.check()
    }
}

struct Heartbeats<B: RunBroker> {
    shared: Arc<(Mutex<Health<B>>, Condvar)>,
    thread: Option<JoinHandle<Result<(), String>>>,
}

impl<B: RunBroker> Heartbeats<B> {
    fn start(
        broker: B,
        context: StatusContext,
        records: u64,
        clock: fn() -> Result<i64, String>,
    ) -> Result<Self, String> {
        let mut health = Health {
            broker,
            context,
            state: "running",
            records,
            error: None,
            failure: None,
            done: false,
            clock,
        };
        health.tick()?;
        let shared = Arc::new((Mutex::new(health), Condvar::new()));
        let background = shared.clone();
        let thread = std::thread::Builder::new()
            .name("tsx-run-status".into())
            .spawn(move || loop {
                let health = background
                    .0
                    .lock()
                    .map_err(|_| "status health is poisoned".to_string())?;
                let (mut health, timeout) = background
                    .1
                    .wait_timeout_while(health, HEARTBEAT, |state| !state.done)
                    .map_err(|_| "status health is poisoned".to_string())?;
                if health.done {
                    return Ok(());
                }
                if timeout.timed_out() {
                    health.tick()?;
                }
            })
            .map_err(|error| format!("cannot start run heartbeats: {error}"))?;
        Ok(Self {
            shared,
            thread: Some(thread),
        })
    }
    fn finish(&mut self) -> Result<(), String> {
        let stopped = match self.shared.0.lock() {
            Ok(mut health) => {
                health.done = true;
                self.shared.1.notify_all();
                Ok(())
            }
            Err(_) => Err("status health is poisoned".to_string()),
        };
        let joined = match self.thread.take() {
            Some(thread) => thread
                .join()
                .map_err(|_| "run heartbeat worker failed".to_string())
                .and_then(|result| result),
            None => Ok(()),
        };
        match (stopped, joined) {
            (Ok(()), Ok(())) => Ok(()),
            (Err(error), Ok(())) | (Ok(()), Err(error)) => Err(error),
            (Err(first), Err(second)) => Err(format!("{first}; {second}")),
        }
    }
}

impl<B: RunBroker> Drop for Heartbeats<B> {
    fn drop(&mut self) {
        if self.thread.is_some() {
            if let Err(error) = self.finish() {
                eprintln!("{error}");
            }
        }
    }
}

pub fn execute<B: RunBroker>(
    plan: &SignedPlan,
    config: &RunConfig,
    broker: &mut B,
    stop: &StopToken,
) -> Result<RunOutcome, RunError> {
    execute_with_clock(plan, config, broker, stop, now_micros)
}

fn execute_with_clock<B: RunBroker>(
    plan: &SignedPlan,
    config: &RunConfig,
    broker: &mut B,
    stop: &StopToken,
    clock: fn() -> Result<i64, String>,
) -> Result<RunOutcome, RunError> {
    plan.validate_authority()?;
    config.require_validated()?;
    let identity = identity(plan, config)?;
    let progress = open_store(&config.checkpoint, config.limits.state_bytes + 65_536)?;
    let intent = open_store(
        &intent_path(&config.checkpoint)?,
        config.limits.state_bytes + 65_536,
    )?;
    let accepted = intent.load()?;
    let checkpoint = progress.load()?;
    let (mut saved, mut receipt) = if let Some(accepted) = accepted {
        let (accepted_state, accepted_receipt) = decode_saved(accepted, &identity, plan, config)?;
        if accepted_state.phase != Phase::Active {
            if let Some(checkpoint) = checkpoint {
                let (state, _) = decode_saved(checkpoint, &identity, plan, config)?;
                let mut expected = accepted_state.clone();
                expected.phase = Phase::Active;
                expected.error = None;
                if state != expected {
                    return Err("the terminal intent differs from the progress checkpoint".into());
                }
            } else if accepted_state.sequence != 0 {
                return Err("the terminal intent has no progress checkpoint".into());
            }
            return match accepted_state.phase {
                Phase::Stopped => Ok(accepted_state.outcome()),
                Phase::Failed => Err(accepted_state
                    .error
                    .ok_or("failed intent omitted its error")?
                    .into()),
                Phase::Active => Err("invalid terminal phase".into()),
            };
        }
        if accepted_state.sequence != 0 {
            return Err("the active intent is not the original admission".into());
        }
        match checkpoint {
            Some(checkpoint) => {
                let (state, receipt) = decode_saved(checkpoint, &identity, plan, config)?;
                if state.phase != Phase::Active {
                    return Err("the progress checkpoint is not active".into());
                }
                (state, receipt)
            }
            None => (accepted_state, accepted_receipt),
        }
    } else {
        if checkpoint.is_some() {
            return Err("a progress checkpoint without accepted authority cannot resume".into());
        }
        plan.require_fresh(clock()?)?;
        initial(plan, config, identity)?
    };
    let binding = config
        .bindings
        .get(&plan.binding)
        .ok_or("the verified run binding is missing")?;
    let _reservation =
        crate::run_reservation::acquire(&plan.job, &binding.bootstrap, &plan.input_topic)?;
    let context = context(plan, config)?;
    let mut heartbeats = Heartbeats::start(broker.clone(), context, saved.records, clock)?;
    let result = (|| {
        {
            let mut health = heartbeats
                .shared
                .0
                .lock()
                .map_err(|_| "status health is poisoned")?;
            health.records = saved.records;
            health.check()?;
            if intent.load()?.is_none() {
                intent.commit(&completion(&saved, &receipt)?)?;
            }
        }
        while !stop.is_requested()? {
            {
                heartbeats
                    .shared
                    .0
                    .lock()
                    .map_err(|_| "status health is poisoned")?
                    .check()?;
            }
            let binding = config
                .bindings
                .get(&plan.binding)
                .ok_or("the verified run binding is missing")?;
            let watermarks =
                broker.watermarks(&binding.bootstrap, &plan.input_topic, saved.positions.len())?;
            if stop.is_requested()? {
                break;
            }
            let (cut, cursor, count) = select_cut(
                &saved.positions,
                &watermarks,
                saved.cursor,
                config.limits.records,
            )?;
            if count == 0 {
                heartbeats
                    .shared
                    .0
                    .lock()
                    .map_err(|_| "status health is poisoned")?
                    .transition("idle", saved.records, None)?;
                stop.wait(IDLE_POLL)?;
                continue;
            }
            if stop.is_requested()? {
                break;
            }
            heartbeats
                .shared
                .0
                .lock()
                .map_err(|_| "status health is poisoned")?
                .transition("running", saved.records, None)?;
            let mut admitted = plan.admit_cut(config, &cut)?;
            admitted.pipeline.restore(&saved.pipeline)?;
            let prepared = runner::prepare_continued(&mut admitted, broker, saved.owners.clone())?;
            if prepared.completion.receipt.input_records != count {
                return Err(RunError::Runtime(
                    "the prepared cut changed its record count".into(),
                ));
            }
            let mut next = saved.clone();
            next.pipeline = prepared.completion.state.clone();
            next.owners = prepared.input_owners.clone();
            next.positions = prepared.completion.receipt.positions.clone();
            next.cursor = cursor;
            next.sequence = next
                .sequence
                .checked_add(1)
                .ok_or("run sequence overflow")?;
            next.records = next
                .records
                .checked_add(count as u64)
                .filter(|value| *value <= i64::MAX as u64)
                .ok_or("run record count overflow")?;
            next.outputs = next
                .outputs
                .checked_add(prepared.records.len() as u64)
                .ok_or("run output count overflow")?;
            validate_state_budget(&next, config)?;
            let next_receipt = prepared.completion.receipt;
            let completed = completion(&next, &next_receipt)?;
            progress.validate(&completed)?;
            broker.publish(&admitted, &prepared.records)?;
            commit_healthy(&heartbeats.shared.0, &progress, &completed, next.records)?;
            receipt = next_receipt;
            saved = next;
        }
        saved.phase = Phase::Stopped;
        {
            let mut health = heartbeats
                .shared
                .0
                .lock()
                .map_err(|_| "status health is poisoned")?;
            health.transition("stopped", saved.records, None)?;
            health.check()?;
            intent.commit(&completion(&saved, &receipt)?)?;
        }
        Ok(saved.outcome())
    })();
    let result = match result {
        Ok(outcome) => Ok(outcome),
        Err(error) => {
            let primary = error.to_string();
            let reported = (|| -> Result<(), String> {
                let mut health = heartbeats
                    .shared
                    .0
                    .lock()
                    .map_err(|_| "status health is poisoned")?;
                health.transition("failed", saved.records, Some(primary.clone()))?;
                if primary.starts_with("record ") {
                    saved.phase = Phase::Failed;
                    saved.error = Some(primary.clone());
                    intent.commit(&completion(&saved, &receipt)?)?;
                }
                Ok(())
            })();
            match reported {
                Ok(()) => Err(error),
                Err(secondary) if secondary == primary => Err(error),
                Err(secondary) => Err(RunError::Runtime(format!("{primary}; {secondary}"))),
            }
        }
    };
    match (result, heartbeats.finish()) {
        (value, Ok(())) => value,
        (Ok(_), Err(error)) => Err(error.into()),
        (Err(primary), Err(secondary)) if primary.to_string() == secondary => Err(primary),
        (Err(primary), Err(secondary)) => Err(format!("{primary}; {secondary}").into()),
    }
}

fn commit_healthy<B: RunBroker>(
    health: &Mutex<Health<B>>,
    progress: &Store,
    completed: &Completion,
    records: u64,
) -> Result<(), String> {
    let mut health = health.lock().map_err(|_| "status health is poisoned")?;
    health.check()?;
    progress.commit(completed)?;
    health.records = records;
    Ok(())
}

fn identity(plan: &SignedPlan, config: &RunConfig) -> Result<Identity, RunError> {
    let assignment = serde_json::to_vec(&plan.assignment)
        .map_err(|error| format!("cannot encode the verified assignment: {error}"))?;
    Ok(Identity {
        command_hash: plan.command_hash.clone(),
        config_hash: config.identity_hash.clone(),
        assignment_hash: tsx_contracts::sha256_bytes(&assignment),
        job: plan.job.clone(),
        version: plan.plan_version,
        artifact: plan.assignment["artifactDigest"]
            .as_str()
            .ok_or("the verified artifact is missing")?
            .into(),
        starts: plan
            .positions
            .iter()
            .map(|position| CompletedPosition {
                partition: position.partition.to_string(),
                next: position.from.to_string(),
            })
            .collect(),
    })
}

fn context(plan: &SignedPlan, config: &RunConfig) -> Result<StatusContext, String> {
    Ok(StatusContext {
        bootstrap: config
            .bindings
            .get(&plan.binding)
            .ok_or("the verified binding is missing")?
            .bootstrap
            .clone(),
        topic: plan.status_topic.clone(),
        job: plan.job.clone(),
        site: config.site.clone(),
        version: plan.plan_version,
        spec_hash: plan.assignment["specHash"]
            .as_str()
            .ok_or("the verified spec hash is missing")?
            .into(),
        artifact: plan.assignment["artifactDigest"]
            .as_str()
            .ok_or("the verified artifact is missing")?
            .into(),
        input: plan.input_topic.clone(),
    })
}

fn initial(
    plan: &SignedPlan,
    config: &RunConfig,
    identity: Identity,
) -> Result<(Saved, Receipt), RunError> {
    let admitted = plan.admit_cut(config, &plan.positions)?;
    let pipeline = admitted.pipeline.snapshot()?;
    let receipt = Receipt {
        schema: RECEIPT_ABI.into(),
        request_hash: plan.command_hash.clone(),
        artifact_digest: identity.artifact.clone(),
        deployment: plan.job.clone(),
        generation: plan.plan_version.to_string(),
        assignment: plan.assignment["assignment"]
            .as_str()
            .ok_or("assignment identity is missing")?
            .into(),
        node: config.node.clone(),
        input_topic: plan.input_topic.clone(),
        output_topic: plan.output_topic.clone(),
        positions: identity.starts.clone(),
        input_records: 0,
        output_records: 0,
        output_digest: tsx_contracts::sha256_bytes(&[]),
        state_digest: String::new(),
    };
    let saved = Saved {
        schema: STATE_ABI.into(),
        identity,
        phase: Phase::Active,
        error: None,
        positions: receipt.positions.clone(),
        pipeline,
        owners: BTreeMap::new(),
        cursor: 0,
        sequence: 0,
        records: 0,
        outputs: 0,
    };
    Ok((saved, receipt))
}

fn completion(saved: &Saved, receipt: &Receipt) -> Result<Completion, String> {
    let state =
        serde_json::to_value(saved).map_err(|error| format!("cannot encode run state: {error}"))?;
    let bytes =
        serde_json::to_vec(&state).map_err(|error| format!("cannot encode run state: {error}"))?;
    let mut receipt = receipt.clone();
    receipt.schema = RECEIPT_ABI.into();
    receipt.request_hash = saved.identity.command_hash.clone();
    receipt.positions = saved.positions.clone();
    receipt.state_digest = tsx_contracts::sha256_bytes(&bytes);
    Ok(Completion {
        request_hash: saved.identity.command_hash.clone(),
        receipt,
        state,
    })
}

fn decode_saved(
    completion: Completion,
    identity: &Identity,
    plan: &SignedPlan,
    config: &RunConfig,
) -> Result<(Saved, Receipt), RunError> {
    let bytes = serde_json::to_vec(&completion.state)
        .map_err(|error| format!("cannot validate run state: {error}"))?;
    let saved: Saved = serde_json::from_value(completion.state)
        .map_err(|_| "the checkpoint is not a signed-run checkpoint")?;
    if saved.schema != STATE_ABI {
        return Err("the signed-run state ABI is incompatible".into());
    }
    if &saved.identity != identity {
        let code = if saved.identity.config_hash != identity.config_hash {
            "plan.policy"
        } else if saved.identity.version < identity.version {
            "plan.busy"
        } else {
            "plan.version"
        };
        return Err(Refusal::new(
            code,
            "the accepted grant or local configuration differs from this exact run",
        )
        .into());
    }
    let receipt = completion.receipt;
    if saved.cursor >= identity.starts.len()
        || saved.outputs > saved.records
        || saved.sequence > saved.records
        || receipt.schema != RECEIPT_ABI
        || completion.request_hash != identity.command_hash
        || receipt.request_hash != identity.command_hash
        || receipt.artifact_digest != identity.artifact
        || receipt.deployment != identity.job
        || receipt.generation != identity.version.to_string()
        || receipt.assignment != plan.assignment["assignment"]
        || receipt.node != config.node
        || receipt.input_topic != plan.input_topic
        || receipt.output_topic != plan.output_topic
        || receipt.positions != saved.positions
        || receipt.state_digest != tsx_contracts::sha256_bytes(&bytes)
        || receipt.input_records > config.limits.records
        || receipt.output_records > receipt.input_records
        || receipt.input_records as u64 > saved.records
        || receipt.output_records as u64 > saved.outputs
        || (saved.sequence == 0) != (receipt.input_records == 0)
        || receipt.output_digest.len() != 64
        || !receipt
            .output_digest
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        || (saved.phase == Phase::Failed) != saved.error.is_some()
    {
        return Err("the run checkpoint receipt or counters are inconsistent".into());
    }
    if saved.positions.len() != identity.starts.len() {
        return Err("the run checkpoint omits internal partitions".into());
    }
    let mut total = 0_u64;
    let mut positions = Vec::with_capacity(saved.positions.len());
    for (index, (position, start)) in saved.positions.iter().zip(&identity.starts).enumerate() {
        if position.partition != index.to_string() || position.partition != start.partition {
            return Err("the run checkpoint partition inventory changed".into());
        }
        let next = crate::admission::canonical_i64(&position.next, "next")?;
        let initial = crate::admission::canonical_i64(&start.next, "start")?;
        if next < initial {
            return Err("the run checkpoint moved before its signed start".into());
        }
        total = total
            .checked_add((next - initial) as u64)
            .ok_or("run checkpoint count overflow")?;
        positions.push(Position {
            partition: index as i32,
            from: next,
            to: next,
        });
    }
    if total != saved.records || (saved.sequence == 0) != (saved.records == 0) {
        return Err("the run checkpoint does not match its completed offsets".into());
    }
    let mut admitted = plan.admit_cut(config, &positions)?;
    admitted.pipeline.restore(&saved.pipeline)?;
    if saved.owners.len() > config.limits.series
        || (saved.records == 0) != saved.owners.is_empty()
        || saved.owners.iter().any(|(id, partition)| {
            id.len() != 16
                || !id
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
                || usize::try_from(*partition).ok().is_none_or(|part| {
                    part >= positions.len() || positions[part].from == plan.positions[part].from
                })
        })
    {
        return Err("the run checkpoint has invalid cumulative series ownership".into());
    }
    validate_state_budget(&saved, config)?;
    Ok((saved, receipt))
}

fn validate_state_budget(saved: &Saved, config: &RunConfig) -> Result<(), String> {
    let bytes = serde_json::to_vec(&json!({"pipeline":saved.pipeline,"inputOwners":saved.owners}))
        .map_err(|error| format!("cannot encode cumulative run state: {error}"))?;
    if bytes.len() > config.limits.state_bytes {
        return Err("cumulative run state exceeds stateBytes".into());
    }
    Ok(())
}

pub fn intent_path(checkpoint: &Path) -> Result<PathBuf, String> {
    let name = checkpoint
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or("the run checkpoint needs a file name")?;
    Ok(checkpoint.with_file_name(format!(".{name}.run-intent")))
}

fn open_store(path: &Path, limit: usize) -> Result<Store, RunError> {
    Store::open(path, limit).map_err(|error| {
        if error.starts_with("checkpoint is locked") {
            RunError::Refused(Refusal::new("plan.busy", error))
        } else {
            RunError::Runtime(error)
        }
    })
}

fn select_cut(
    positions: &[CompletedPosition],
    watermarks: &[Watermark],
    cursor: usize,
    limit: usize,
) -> Result<(Vec<Position>, usize, usize), String> {
    if positions.is_empty()
        || positions.len() != watermarks.len()
        || cursor >= positions.len()
        || limit == 0
    {
        return Err("invalid whole-topic cursor or watermark inventory".into());
    }
    let mut cut = Vec::with_capacity(positions.len());
    for (index, (position, watermark)) in positions.iter().zip(watermarks).enumerate() {
        let from = crate::admission::canonical_i64(&position.next, "next")?;
        if position.partition != index.to_string()
            || watermark.partition != index as i32
            || watermark.low < 0
            || watermark.high < watermark.low
            || from < watermark.low
            || from > watermark.high
        {
            return Err(
                "the signed run cut is unavailable; offsets are never reset or clipped".into(),
            );
        }
        cut.push(Position {
            partition: index as i32,
            from,
            to: from,
        });
    }
    let mut remaining = limit;
    let mut next_cursor = cursor;
    for step in 0..positions.len() {
        let index = (cursor + step) % positions.len();
        let available = u64::try_from(watermarks[index].high - cut[index].from)
            .map_err(|_| "negative available offset count")?;
        let take = available.min(remaining as u64) as usize;
        cut[index].to += take as i64;
        remaining -= take;
        if take != 0 {
            next_cursor = (index + 1) % positions.len();
        }
        if remaining == 0 {
            break;
        }
    }
    Ok((cut, next_cursor, limit - remaining))
}

fn now_micros() -> Result<i64, String> {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "the runtime clock is before the Unix epoch")?;
    i64::try_from(elapsed.as_micros())
        .map_err(|_| "the runtime clock exceeds signed microseconds".into())
}

#[cfg(test)]
#[path = "../tests/common/run_profile.rs"]
pub(crate) mod run_profile;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::broker::{InputRecord, OutputRecord};
    use run_profile::{Fixture, ARTIFACT_DIGEST};

    fn clock() -> Result<i64, String> {
        Ok(1_794_960_000_000_000)
    }
    fn expired_clock() -> Result<i64, String> {
        Ok(1_795_132_800_000_000)
    }

    #[derive(Default)]
    struct Script {
        input: Vec<InputRecord>,
        output: Vec<OutputRecord>,
        statuses: Vec<Value>,
        cuts: Vec<Vec<(i32, i64, i64)>>,
        reads: usize,
        watermark_reads: usize,
        fail_after: Option<usize>,
        checkpoint_fault: Option<PathBuf>,
        status_failure: Option<usize>,
        status_gate: Option<Arc<(std::sync::Barrier, std::sync::Barrier)>>,
        stop_after: usize,
        stop_when_idle: bool,
    }

    #[derive(Clone)]
    struct FakeBroker {
        script: Arc<Mutex<Script>>,
        stop: StopToken,
    }

    impl FakeBroker {
        fn new(input: Vec<InputRecord>) -> Self {
            Self {
                script: Arc::new(Mutex::new(Script {
                    stop_after: input.len(),
                    input,
                    ..Script::default()
                })),
                stop: StopToken::new(),
            }
        }
    }

    impl Broker for FakeBroker {
        fn read(&mut self, run: &crate::admission::Admitted) -> Result<Vec<InputRecord>, String> {
            let mut script = self.script.lock().unwrap();
            script.reads += 1;
            script.cuts.push(
                run.cut
                    .positions
                    .iter()
                    .map(|position| (position.partition, position.from, position.to))
                    .collect(),
            );
            Ok(script
                .input
                .iter()
                .filter(|record| {
                    run.cut.positions.iter().any(|position| {
                        position.partition == record.partition
                            && record.offset >= position.from
                            && record.offset < position.to
                    })
                })
                .cloned()
                .collect())
        }
        fn publish(
            &mut self,
            _: &crate::admission::Admitted,
            records: &[OutputRecord],
        ) -> Result<(), String> {
            let mut script = self.script.lock().unwrap();
            if let Some(count) = script.fail_after {
                script.output.extend(records.iter().take(count).cloned());
                return Err(format!("injected failure after {count} acknowledgements"));
            }
            script.output.extend_from_slice(records);
            if let Some(path) = &script.checkpoint_fault {
                std::fs::create_dir(path).unwrap();
            }
            if script.output.len() >= script.stop_after {
                self.stop.request()?;
            }
            Ok(())
        }
    }

    impl RunBroker for FakeBroker {
        fn watermarks(
            &mut self,
            _: &str,
            _: &str,
            partitions: usize,
        ) -> Result<Vec<Watermark>, String> {
            let mut script = self.script.lock().unwrap();
            script.watermark_reads += 1;
            Ok((0..partitions)
                .map(|partition| Watermark {
                    partition: partition as i32,
                    low: 0,
                    high: script
                        .input
                        .iter()
                        .filter(|record| record.partition == partition as i32)
                        .map(|record| record.offset + 1)
                        .max()
                        .unwrap_or(0),
                })
                .collect())
        }
        fn publish_status(
            &mut self,
            _: &str,
            _: &str,
            _: &str,
            payload: &[u8],
        ) -> Result<(), String> {
            let mut script = self.script.lock().unwrap();
            if let Some(gate) = script.status_gate.take() {
                gate.0.wait();
                gate.1.wait();
            }
            if script.status_failure == Some(script.statuses.len() + 1) {
                return Err("injected status failure".into());
            }
            let status: Value = serde_json::from_slice(payload).unwrap();
            if script.stop_when_idle && status["state"] == "idle" {
                self.stop.request()?;
            }
            script.statuses.push(status);
            Ok(())
        }
    }

    fn input() -> Vec<InputRecord> {
        let text =
            include_str!("../../../opentsx-model/src/test/resources/testvectors/wire-format.json");
        let normalized = text
            .lines()
            .map(|line| {
                if line.trim() == "NaN," {
                    line.replace("NaN", "\"NaN\"")
                } else {
                    line.into()
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        let vector: Value = serde_json::from_str(&normalized).unwrap();
        vector["episodes"]
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
            .map(|(partition, row)| {
                let hex = row["hex"].as_str().unwrap();
                let payload: Vec<_> = (0..hex.len())
                    .step_by(2)
                    .map(|index| u8::from_str_radix(&hex[index..index + 2], 16).unwrap())
                    .collect();
                let episode = crate::wire::decode(&payload, 1_048_576, 1_000_000).unwrap();
                InputRecord {
                    partition: partition as i32,
                    offset: 0,
                    key: episode.series.series_id.into_bytes(),
                    payload,
                }
            })
            .collect()
    }

    fn verified(fixture: &Fixture, document: &Value) -> (SignedPlan, RunConfig) {
        let config = RunConfig::load(&fixture.config_path).unwrap();
        let plan = SignedPlan::verify(&fixture.sign(document), &config, ARTIFACT_DIGEST).unwrap();
        (plan, config)
    }

    #[test]
    fn acknowledged_cuts_stop_durably_and_stopped_replay_has_no_io() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let mut broker = FakeBroker::new(input());
        let outcome =
            execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock).unwrap();
        assert_eq!((outcome.records, outcome.output_records), (2, 2));
        assert_eq!(
            outcome
                .positions
                .iter()
                .map(|position| position.next.as_str())
                .collect::<Vec<_>>(),
            ["1", "1"]
        );
        assert!(config.checkpoint.is_file());
        let checkpoint = std::fs::read(&config.checkpoint).unwrap();
        let intent = std::fs::read(intent_path(&config.checkpoint).unwrap()).unwrap();
        let script = broker.script.lock().unwrap();
        let before = (
            script.reads,
            script.watermark_reads,
            script.statuses.len(),
            script.output.len(),
        );
        assert_eq!(
            script
                .statuses
                .iter()
                .map(|row| row["state"].as_str().unwrap())
                .collect::<Vec<_>>(),
            ["running", "stopped"]
        );
        assert!(script.statuses.iter().all(|row| row["track"] == "B"
            && row["site"] == "local"
            && row["artifact"]["digest"] == ARTIFACT_DIGEST));
        drop(script);
        let replay = execute_with_clock(
            &plan,
            &config,
            &mut broker,
            &StopToken::new(),
            expired_clock,
        )
        .unwrap();
        assert_eq!(outcome, replay);
        let script = broker.script.lock().unwrap();
        assert_eq!(
            before,
            (
                script.reads,
                script.watermark_reads,
                script.statuses.len(),
                script.output.len()
            )
        );
        assert_eq!(checkpoint, std::fs::read(&config.checkpoint).unwrap());
        assert_eq!(
            intent,
            std::fs::read(intent_path(&config.checkpoint).unwrap()).unwrap()
        );
    }

    #[test]
    fn failed_publication_replays_identical_bytes_after_expiry_without_advancing() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let broker = FakeBroker::new(input());
        broker.script.lock().unwrap().fail_after = Some(1);
        let error = execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock)
            .unwrap_err();
        assert!(error.to_string().contains("injected failure after 1"));
        assert!(!config.checkpoint.exists());
        assert!(intent_path(&config.checkpoint).unwrap().is_file());
        let partial = broker.script.lock().unwrap().output[0].payload.clone();
        broker.script.lock().unwrap().fail_after = None;
        let result = execute_with_clock(
            &plan,
            &config,
            &mut broker.clone(),
            &broker.stop,
            expired_clock,
        )
        .unwrap();
        assert_eq!(result.records, 2);
        let script = broker.script.lock().unwrap();
        assert_eq!(script.output.len(), 3);
        assert_eq!(partial, script.output[1].payload);
        assert_eq!(script.statuses.last().unwrap()["state"], "stopped");
    }

    #[test]
    fn acknowledged_output_before_failed_checkpoint_replays_the_same_cut() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let mut broker = FakeBroker::new(input());
        let pending = config.checkpoint.with_file_name(".checkpoint.json.pending");
        broker.script.lock().unwrap().checkpoint_fault = Some(pending.clone());
        assert!(
            execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock).is_err()
        );
        assert!(!config.checkpoint.exists());
        let original: Vec<_> = broker
            .script
            .lock()
            .unwrap()
            .output
            .iter()
            .map(|record| record.payload.clone())
            .collect();
        std::fs::remove_dir(pending).unwrap();
        broker.script.lock().unwrap().checkpoint_fault = None;
        broker.stop = StopToken::new();
        let replay = execute_with_clock(
            &plan,
            &config,
            &mut broker.clone(),
            &broker.stop,
            expired_clock,
        )
        .unwrap();
        assert_eq!(replay.records, 2);
        let script = broker.script.lock().unwrap();
        assert_eq!(
            original,
            script.output[2..]
                .iter()
                .map(|record| record.payload.clone())
                .collect::<Vec<_>>()
        );
    }

    #[test]
    fn malformed_and_unknown_first_records_fail_exactly_without_output_or_progress() {
        for kind in ["unknown", "malformed", "empty"] {
            let fixture = Fixture::new();
            let (plan, config) = verified(&fixture, &fixture.plan_document());
            let mut records = input();
            if kind == "empty" {
                records[0].payload.clear();
            } else if kind == "malformed" {
                records[0].payload = vec![0xc3, 1, 0];
            } else {
                records[0].payload[2..10].copy_from_slice(&0xe51b48188baa6606_u64.to_le_bytes());
            }
            let mut broker = FakeBroker::new(records);
            let expected = if kind != "unknown" {
                "record tsx.b1.episodes.v2/0@0 is not Avro single-object encoded".to_string()
            } else {
                format!("record tsx.b1.episodes.v2/0@0 has schema fingerprint e51b48188baa6606, not in plan {} version 1", fixture.job)
            };
            let error =
                execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock)
                    .unwrap_err();
            assert_eq!(error.to_string(), expected);
            assert!(!config.checkpoint.exists());
            let script = broker.script.lock().unwrap();
            assert!(script.output.is_empty());
            assert_eq!(script.statuses.last().unwrap()["state"], "failed");
            assert_eq!(script.statuses.last().unwrap()["error"], expected);
            let reads = script.reads;
            let statuses = script.statuses.len();
            drop(script);
            assert_eq!(
                execute_with_clock(
                    &plan,
                    &config,
                    &mut broker,
                    &StopToken::new(),
                    expired_clock
                )
                .unwrap_err()
                .to_string(),
                expected
            );
            let script = broker.script.lock().unwrap();
            assert_eq!((script.reads, script.statuses.len()), (reads, statuses));
        }
    }

    #[test]
    fn round_robin_cuts_include_empty_partitions_and_cannot_starve_a_backlogged_peer() {
        let fixture = Fixture::new();
        let mut document = fixture.config_document();
        document["limits"]["records"] = json!(1);
        fixture.write_config(&document);
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let mut records = input();
        let mut extra = records[0].clone();
        extra.offset = 1;
        records.push(extra);
        let broker = FakeBroker::new(records);
        broker.script.lock().unwrap().stop_after = 2;
        let outcome =
            execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock).unwrap();
        assert_eq!(
            outcome
                .positions
                .iter()
                .map(|position| position.next.as_str())
                .collect::<Vec<_>>(),
            ["1", "1"]
        );
        let script = broker.script.lock().unwrap();
        assert_eq!(
            script.cuts,
            [vec![(0, 0, 1), (1, 0, 0)], vec![(0, 1, 1), (1, 0, 1)]]
        );
    }

    #[test]
    fn empty_topic_waits_for_stop_and_persists_no_progress_checkpoint() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let broker = FakeBroker::new(Vec::new());
        broker.script.lock().unwrap().stop_when_idle = true;
        let outcome =
            execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock).unwrap();
        assert_eq!(outcome.records, 0);
        assert!(!config.checkpoint.exists());
        let script = broker.script.lock().unwrap();
        assert_eq!(
            script
                .statuses
                .iter()
                .map(|row| row["state"].as_str().unwrap())
                .collect::<Vec<_>>(),
            ["running", "idle", "stopped"]
        );
    }

    #[test]
    fn status_failure_prevents_initial_authority_and_preserves_primary_processing_failure() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let broker = FakeBroker::new(input());
        broker.script.lock().unwrap().status_failure = Some(1);
        assert!(
            execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock)
                .unwrap_err()
                .to_string()
                .contains("status")
        );
        assert!(!config.checkpoint.exists());
        assert!(!intent_path(&config.checkpoint).unwrap().exists());
        assert_eq!(broker.script.lock().unwrap().reads, 0);
        {
            let mut script = broker.script.lock().unwrap();
            script.status_failure = Some(2);
            script.fail_after = Some(1);
        }
        let error = execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock)
            .unwrap_err()
            .to_string();
        assert!(error.contains("injected failure after 1 acknowledgements"));
        assert!(error.contains("injected status failure"));
        assert!(!config.checkpoint.exists());
    }

    #[test]
    fn invalid_watermarks_and_retention_loss_never_reset_or_clip_positions() {
        let positions = vec![CompletedPosition {
            partition: "0".into(),
            next: "7".into(),
        }];
        for watermarks in [
            vec![],
            vec![Watermark {
                partition: 1,
                low: 0,
                high: 10,
            }],
            vec![Watermark {
                partition: 0,
                low: 8,
                high: 10,
            }],
            vec![Watermark {
                partition: 0,
                low: 0,
                high: 6,
            }],
        ] {
            assert!(select_cut(&positions, &watermarks, 0, 10).is_err());
        }
    }

    #[test]
    fn post_load_authority_mutation_is_refused_before_any_broker_io() {
        let fixture = Fixture::new();
        let (mut plan, config) = verified(&fixture, &fixture.plan_document());
        plan.expires_micros = i64::MAX;
        let broker = FakeBroker::new(input());
        assert!(matches!(
            execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock),
            Err(RunError::Refused(_))
        ));
        let script = broker.script.lock().unwrap();
        assert_eq!(
            (script.reads, script.watermark_reads, script.statuses.len()),
            (0, 0, 0)
        );
        assert!(!config.checkpoint.exists());
    }

    #[test]
    fn terminal_intent_requires_the_entire_progress_state_to_agree() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let broker = FakeBroker::new(input());
        execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock).unwrap();
        let original = std::fs::read(&config.checkpoint).unwrap();
        for field in ["cursor", "outputs", "pipeline", "owners", "phase"] {
            std::fs::write(&config.checkpoint, &original).unwrap();
            let store =
                Store::open(&config.checkpoint, config.limits.state_bytes + 65_536).unwrap();
            let mut completed = store.load().unwrap().unwrap();
            match field {
                "cursor" => completed.state["cursor"] = json!(1),
                "outputs" => {
                    completed.state["outputs"] = json!(1);
                    completed.receipt.output_records = 1;
                }
                "pipeline" => completed.state["pipeline"]["skippedBlocks"] = json!("1"),
                "owners" => {
                    for value in completed.state["owners"]
                        .as_object_mut()
                        .unwrap()
                        .values_mut()
                    {
                        *value = json!(1 - value.as_i64().unwrap());
                    }
                }
                "phase" => {
                    completed.state["phase"] = json!("failed");
                    completed.state["error"] = json!("unexpected terminal progress");
                }
                _ => unreachable!(),
            }
            completed.receipt.state_digest =
                tsx_contracts::sha256_bytes(&serde_json::to_vec(&completed.state).unwrap());
            store.commit(&completed).unwrap();
            drop(store);
            assert!(
                execute_with_clock(
                    &plan,
                    &config,
                    &mut broker.clone(),
                    &StopToken::new(),
                    expired_clock
                )
                .is_err(),
                "{field}"
            );
        }
    }

    #[test]
    fn heartbeat_ticks_publish_without_a_transition_and_fail_closed() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let broker = FakeBroker::new(Vec::new());
        let mut health = Health {
            broker: broker.clone(),
            context: context(&plan, &config).unwrap(),
            state: "idle",
            records: 7,
            error: None,
            failure: None,
            done: false,
            clock,
        };
        health.tick().unwrap();
        health.tick().unwrap();
        {
            let mut script = broker.script.lock().unwrap();
            assert_eq!(script.statuses.len(), 2);
            assert_eq!(script.statuses[0], script.statuses[1]);
            assert_eq!(script.statuses[0]["records"], 7);
            assert_eq!(script.statuses[0]["at"], "2026-11-18T00:00:00Z");
            script.status_failure = Some(3);
        }
        let failure = health.tick().unwrap_err();
        assert!(failure.contains("injected status failure"));
        assert_eq!(health.tick(), Err(failure.clone()));
        assert_eq!(health.transition("running", 8, None), Err(failure));
        assert_eq!(broker.script.lock().unwrap().statuses.len(), 2);
    }

    #[test]
    fn an_inflight_heartbeat_failure_prevents_the_waiting_checkpoint_commit() {
        let fixture = Fixture::new();
        let (plan, config) = verified(&fixture, &fixture.plan_document());
        let broker = FakeBroker::new(Vec::new());
        let gate = Arc::new((std::sync::Barrier::new(2), std::sync::Barrier::new(2)));
        {
            let mut script = broker.script.lock().unwrap();
            script.status_failure = Some(1);
            script.status_gate = Some(gate.clone());
        }
        let health = Arc::new(Mutex::new(Health {
            broker,
            context: context(&plan, &config).unwrap(),
            state: "idle",
            records: 0,
            error: None,
            failure: None,
            done: false,
            clock,
        }));
        let ticker_health = health.clone();
        let ticker = std::thread::spawn(move || ticker_health.lock().unwrap().tick());
        gate.0.wait();
        let store = Store::open(&config.checkpoint, config.limits.state_bytes + 65_536).unwrap();
        let (saved, receipt) = initial(&plan, &config, identity(&plan, &config).unwrap()).unwrap();
        let completed = completion(&saved, &receipt).unwrap();
        let (attempted, waiting) = std::sync::mpsc::sync_channel(0);
        let commit_health = health.clone();
        let committer = std::thread::spawn(move || {
            attempted.send(()).unwrap();
            commit_healthy(&commit_health, &store, &completed, 0)
        });
        waiting.recv().unwrap();
        gate.1.wait();
        let failure = ticker.join().unwrap().unwrap_err();
        assert_eq!(committer.join().unwrap(), Err(failure));
        assert!(!config.checkpoint.exists());
    }

    #[test]
    fn expired_new_grants_and_changed_accepted_identity_are_refused_before_io() {
        let fixture = Fixture::new();
        let document = fixture.plan_document();
        let (plan, config) = verified(&fixture, &document);
        let broker = FakeBroker::new(input());
        assert!(matches!(
            execute_with_clock(
                &plan,
                &config,
                &mut broker.clone(),
                &broker.stop,
                expired_clock
            ),
            Err(RunError::Refused(_))
        ));
        assert_eq!(broker.script.lock().unwrap().watermark_reads, 0);
        assert!(!intent_path(&config.checkpoint).unwrap().exists());
        execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock).unwrap();
        let before = broker.script.lock().unwrap().statuses.len();
        let mut changed = document.clone();
        changed["expires"] = json!("2026-11-20T00:00:00Z");
        let (changed, _) = verified(&fixture, &changed);
        assert!(matches!(
            execute_with_clock(
                &changed,
                &config,
                &mut broker.clone(),
                &StopToken::new(),
                clock
            ),
            Err(RunError::Refused(_))
        ));
        assert_eq!(broker.script.lock().unwrap().statuses.len(), before);
    }

    #[test]
    fn a_plan_verified_against_another_valid_configuration_is_refused_before_io() {
        let fixture = Fixture::new();
        let (plan, _) = verified(&fixture, &fixture.plan_document());
        let mut document = fixture.config_document();
        document["bindings"]["local"]["bootstrap"] = json!("127.0.0.1:39094");
        fixture.write_config(&document);
        let config = RunConfig::load(&fixture.config_path).unwrap();
        let broker = FakeBroker::new(input());
        let result = execute_with_clock(&plan, &config, &mut broker.clone(), &broker.stop, clock);
        assert!(matches!(result, Err(RunError::Refused(_))));
        let script = broker.script.lock().unwrap();
        assert_eq!(
            (script.reads, script.watermark_reads, script.statuses.len()),
            (0, 0, 0)
        );
        assert!(!config.checkpoint.exists());
        assert!(!intent_path(&config.checkpoint).unwrap().exists());
    }

    #[test]
    fn the_same_job_and_topic_cannot_run_from_another_checkpoint_while_owned() {
        let fixture = Fixture::new();
        let compact = fixture.sign(&fixture.plan_document());
        let config = RunConfig::load(&fixture.config_path).unwrap();
        let plan = SignedPlan::verify(&compact, &config, ARTIFACT_DIGEST).unwrap();
        let mut document = fixture.config_document();
        document["checkpoint"] = json!(fixture.directory.join("contender.json"));
        fixture.write_config(&document);
        let contender_config = RunConfig::load(&fixture.config_path).unwrap();
        let contender_plan =
            SignedPlan::verify(&compact, &contender_config, ARTIFACT_DIGEST).unwrap();
        let owner = FakeBroker::new(Vec::new());
        owner.stop.request().unwrap();
        let gate = Arc::new((std::sync::Barrier::new(2), std::sync::Barrier::new(2)));
        owner.script.lock().unwrap().status_gate = Some(gate.clone());
        let contender = FakeBroker::new(Vec::new());
        contender.stop.request().unwrap();
        let unrelated_fixture = Fixture::new();
        let (unrelated_plan, unrelated_config) =
            verified(&unrelated_fixture, &unrelated_fixture.plan_document());
        let unrelated = FakeBroker::new(Vec::new());
        unrelated.stop.request().unwrap();
        std::thread::scope(|scope| {
            let running = scope.spawn(|| {
                execute_with_clock(&plan, &config, &mut owner.clone(), &owner.stop, clock)
            });
            gate.0.wait();
            let result = execute_with_clock(
                &contender_plan,
                &contender_config,
                &mut contender.clone(),
                &contender.stop,
                clock,
            );
            let unrelated_result = execute_with_clock(
                &unrelated_plan,
                &unrelated_config,
                &mut unrelated.clone(),
                &unrelated.stop,
                clock,
            );
            gate.1.wait();
            running.join().unwrap().unwrap();
            assert_eq!(unrelated_result.unwrap().records, 0);
            assert!(
                matches!(
                    result,
                    Err(RunError::Refused(Refusal {
                        code: "plan.busy",
                        ..
                    }))
                ),
                "{result:?}"
            );
            let script = contender.script.lock().unwrap();
            assert_eq!(
                (script.reads, script.watermark_reads, script.statuses.len()),
                (0, 0, 0)
            );
        });
        assert_eq!(
            execute_with_clock(
                &contender_plan,
                &contender_config,
                &mut contender.clone(),
                &contender.stop,
                clock
            )
            .unwrap()
            .records,
            0
        );
    }
}
