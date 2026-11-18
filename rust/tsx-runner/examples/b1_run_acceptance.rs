#[path = "support/b1_native_read.rs"]
mod native_read;

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use std::time::{Duration, Instant};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, KafkaBroker, OutputRecord};
use tsx_runner::run_loop::{self, RunBroker, StopToken, Watermark};
use tsx_runner::run_plan::{RunConfig, SignedPlan};

const NAMES: [&str; 6] = [
    "normal",
    "crash-1",
    "crash-150",
    "crash-299",
    "unknown",
    "malformed",
];

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Fixture {
    schema: String,
    artifact_digest: String,
    input_records: usize,
    series: usize,
    samples: usize,
    status_topic: String,
    cases: Vec<Case>,
    stdin: Case,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Case {
    name: String,
    plan: String,
    config: String,
    input_topic: String,
    output_topic: String,
    partitions: usize,
    input_count: usize,
    expected_distinct: usize,
    expected_records: usize,
    fail_after: Option<usize>,
    expected_error: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    schema: &'static str,
    runner_version: &'static str,
    artifact_digest: String,
    executed_cases: usize,
    passed_cases: usize,
    failed_cases: usize,
    cases: Vec<Row>,
    stdin: Row,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Row {
    name: String,
    records: u64,
    output_records: usize,
    failed_prefix: usize,
    checkpoint: bool,
    exit_code: i32,
    status_state: String,
    error: Option<String>,
    signal: Option<&'static str>,
    stopped_replay: bool,
    matched: bool,
}

fn main() {
    match execute() {
        Ok(report) => match serde_json::to_string(&report) {
            Ok(text) => println!("{text}"),
            Err(error) => {
                eprintln!("cannot encode signed-run acceptance: {error}");
                std::process::exit(1);
            }
        },
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}

fn execute() -> Result<Report, String> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 2 {
        return Err("usage: b1_run_acceptance <tsx-runner-binary> <fixture-directory>".into());
    }
    let binary = fs::canonicalize(&args[0])
        .map_err(|error| format!("cannot locate the runner binary: {error}"))?;
    let directory = fs::canonicalize(&args[1])
        .map_err(|error| format!("cannot locate the signed-run fixture: {error}"))?;
    let artifact = format!(
        "sha256:{}",
        tsx_contracts::sha256_bytes(&read_bounded(&binary, 268_435_456)?)
    );
    let fixture = parse_fixture(
        &read_bounded(&directory.join("run-fixture.json"), 65_536)?,
        &artifact,
    )?;
    let bootstrap = std::env::var("KAFSCALE_BOOTSTRAP")
        .map_err(|_| "KAFSCALE_BOOTSTRAP must point to the conformance Proxy")?;
    if bootstrap.trim().is_empty() {
        return Err("KAFSCALE_BOOTSTRAP is empty".into());
    }
    let reader: BaseConsumer = ClientConfig::new()
        .set("bootstrap.servers", &bootstrap)
        .set("enable.auto.commit", "false")
        .set("enable.auto.offset.store", "false")
        .set("auto.offset.reset", "error")
        .set("allow.auto.create.topics", "false")
        .set("isolation.level", "read_uncommitted")
        .create()
        .map_err(|error| format!("cannot create the acceptance reader: {error}"))?;
    let mut cases = Vec::with_capacity(6);
    for case in &fixture.cases {
        cases.push(run_case(
            &binary, &directory, &fixture, case, &bootstrap, &reader,
        )?);
    }
    let stdin = run_case(
        &binary,
        &directory,
        &fixture,
        &fixture.stdin,
        &bootstrap,
        &reader,
    )?;
    let report = Report {
        schema: "opentsx.rust-b1-run-acceptance.v1",
        runner_version: env!("CARGO_PKG_VERSION"),
        artifact_digest: artifact,
        executed_cases: cases.len() + 1,
        passed_cases: cases.len() + 1,
        failed_cases: 0,
        cases,
        stdin,
    };
    let bytes = serde_json::to_vec_pretty(&report)
        .map_err(|error| format!("cannot encode the run report: {error}"))?;
    create_file(&directory.join("rust-run-report.json"), &bytes)?;
    Ok(report)
}

fn parse_fixture(bytes: &[u8], artifact: &str) -> Result<Fixture, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "the run fixture is not UTF-8")?;
    let document = tsx_contracts::parse_json(text, 65_536).map_err(str::to_string)?;
    let fixture: Fixture = serde_json::from_value(document)
        .map_err(|error| format!("invalid run fixture: {error}"))?;
    if fixture.schema != "opentsx.rust-b1-run-fixture.v1"
        || fixture.artifact_digest != artifact
        || fixture.input_records != 300
        || fixture.series != 100
        || fixture.samples != 180_000
        || fixture.status_topic != "tsx.b1run.dag-status.v1"
        || fixture.cases.len() != NAMES.len()
    {
        return Err("the fixture does not describe the required signed W scenario".into());
    }
    for (case, name) in fixture
        .cases
        .iter()
        .zip(NAMES)
        .chain(std::iter::once((&fixture.stdin, "stdin")))
    {
        let bad = matches!(name, "unknown" | "malformed");
        let empty = name == "stdin";
        let prefix = match name {
            "crash-1" => Some(1),
            "crash-150" => Some(150),
            "crash-299" => Some(299),
            _ => None,
        };
        if case.name != name
            || case.plan != format!("{name}/plan.jws")
            || case.config != format!("{name}/config.json")
            || case.fail_after != prefix
            || case.partitions != if bad || empty { 1 } else { 3 }
            || case.input_count
                != if empty {
                    0
                } else if bad {
                    2
                } else {
                    300
                }
            || case.expected_distinct != if bad || empty { 0 } else { 300 }
            || case.expected_records
                != if bad || empty {
                    0
                } else {
                    300 + prefix.unwrap_or(0)
                }
            || case.expected_error.is_some() != bad
            || case.input_topic.is_empty()
            || case.output_topic.is_empty()
        {
            return Err(format!("the signed-run case metadata differs: {name}"));
        }
    }
    Ok(fixture)
}

fn run_case(
    binary: &Path,
    directory: &Path,
    fixture: &Fixture,
    case: &Case,
    bootstrap: &str,
    reader: &BaseConsumer,
) -> Result<Row, String> {
    let config_path = child_path(directory, &case.config)?;
    let plan_path = child_path(directory, &case.plan)?;
    let config = RunConfig::load(&config_path)
        .map_err(|error| format!("{}: {}", error.code, error.message))?;
    let compact = String::from_utf8(read_bounded(&plan_path, 180_224)?)
        .map_err(|_| "the fixture plan is not UTF-8")?;
    let plan = SignedPlan::verify(&compact, &config, &fixture.artifact_digest)
        .map_err(|error| format!("{}: {}", error.code, error.message))?;
    if plan.job != format!("b1-{}", case.name)
        || plan.input_topic != case.input_topic
        || plan.output_topic != case.output_topic
        || plan.status_topic != fixture.status_topic
        || plan.output_partitions != case.partitions as i32
        || plan.positions.len() != case.partitions
        || config.bindings[&plan.binding].bootstrap != bootstrap
        || config.checkpoint != directory.join(&case.name).join("checkpoint.json")
    {
        return Err(format!(
            "the signed grant differs from the run fixture: {}",
            case.name
        ));
    }
    if config.checkpoint.exists()
        || run_loop::intent_path(&config.checkpoint)?.exists()
        || topic_count(reader, &case.output_topic, case.partitions)? != 0
    {
        return Err(format!(
            "the run case is not disposable and fresh: {}",
            case.name
        ));
    }
    let mut failed_prefix = 0;
    if let Some(prefix) = case.fail_after {
        let acknowledged = Arc::new(AtomicUsize::new(0));
        let mut broker = FaultBroker {
            broker: KafkaBroker,
            prefix,
            acknowledged: acknowledged.clone(),
        };
        let error = match run_loop::execute(&plan, &config, &mut broker, &StopToken::new()) {
            Err(error) => error,
            Ok(_) => return Err("the injected acknowledged failure unexpectedly completed".into()),
        };
        if error.to_string() != format!("injected failure after {prefix} acknowledgements") {
            return Err(format!("unexpected fault result: {error}"));
        }
        failed_prefix = acknowledged.load(Ordering::SeqCst);
        if failed_prefix != prefix
            || config.checkpoint.exists()
            || topic_count(reader, &case.output_topic, case.partitions)? != prefix
        {
            return Err(format!(
                "the acknowledged failure prefix differs: {}",
                case.name
            ));
        }
    }
    let stdin = case.name == "stdin";
    let status_start = topic_count(reader, &fixture.status_topic, 1)?;
    let mut process = start(binary, directory, case, "activation", stdin)?;
    let expected_records = if case.expected_error.is_some() {
        0
    } else {
        case.input_count as u64
    };
    let signal = if case.expected_error.is_some() {
        None
    } else if matches!(case.name.as_str(), "crash-1" | "stdin") {
        Some("SIGINT")
    } else {
        Some("SIGTERM")
    };
    if let Some(signal) = signal {
        wait_idle(
            &mut process,
            reader,
            &fixture.status_topic,
            &plan.job,
            expected_records,
            status_start,
        )?;
        let number = if signal == "SIGINT" {
            libc::SIGINT
        } else {
            libc::SIGTERM
        };
        if unsafe { libc::kill(process.child.id() as libc::pid_t, number) } != 0 {
            return Err(format!(
                "cannot stop the signed-run child: {}",
                std::io::Error::last_os_error()
            ));
        }
    }
    let status = process.finish(reader)?;
    let stdout = read_bounded(&process.stdout, 1_048_576)?;
    let stderr = String::from_utf8(read_bounded(&process.stderr, 1_048_576)?)
        .map_err(|_| "the runner error is not UTF-8")?;
    let expected_code = if case.expected_error.is_some() { 3 } else { 0 };
    if status.code() != Some(expected_code) {
        return Err(format!(
            "signed-run {} exited {:?}: {stderr}",
            case.name,
            status.code()
        ));
    }
    let outcome = if let Some(error) = &case.expected_error {
        if !stdout.is_empty() || stderr.trim_end() != error {
            return Err(format!(
                "the contextual schema error differs for {}: {stderr}",
                case.name
            ));
        }
        None
    } else {
        if !stderr.is_empty() {
            return Err(format!(
                "the stopped runner wrote unexpected stderr: {stderr}"
            ));
        }
        Some(validate_outcome(
            &stdout,
            &plan.job,
            expected_records,
            case.partitions,
        )?)
    };
    let output_records = topic_count(reader, &case.output_topic, case.partitions)?;
    let checkpoint = config.checkpoint.is_file();
    let expected_checkpoint = case.expected_error.is_none() && case.input_count != 0;
    let latest = latest_status(reader, &fixture.status_topic, &plan.job)?
        .ok_or("the run has no acknowledged status")?;
    let state = if case.expected_error.is_some() {
        "failed"
    } else {
        "stopped"
    };
    if output_records != case.expected_records
        || checkpoint != expected_checkpoint
        || latest["state"] != state
        || latest["records"].as_u64() != Some(expected_records)
        || latest.get("error").and_then(Value::as_str) != case.expected_error.as_deref()
    {
        return Err(format!("the observed run result differs: {}", case.name));
    }
    let stopped_replay = if let Some(outcome) = outcome {
        let intent = run_loop::intent_path(&config.checkpoint)?;
        let old_intent = read_bounded(&intent, 2_097_152)?;
        let old_checkpoint = if checkpoint {
            Some(read_bounded(&config.checkpoint, 2_097_152)?)
        } else {
            None
        };
        let status_records = topic_count(reader, &fixture.status_topic, 1)?;
        let mut replay = start(binary, directory, case, "stopped-replay", !stdin)?;
        let replay_status = replay.finish(reader)?;
        let replay_outcome = validate_outcome(
            &read_bounded(&replay.stdout, 1_048_576)?,
            &plan.job,
            expected_records,
            case.partitions,
        )?;
        if replay_status.code() != Some(0)
            || !read_bounded(&replay.stderr, 1_048_576)?.is_empty()
            || replay_outcome != outcome
            || read_bounded(&intent, 2_097_152)? != old_intent
            || if let Some(original) = old_checkpoint {
                read_bounded(&config.checkpoint, 2_097_152)? != original
            } else {
                config.checkpoint.exists()
            }
            || topic_count(reader, &case.output_topic, case.partitions)? != output_records
            || topic_count(reader, &fixture.status_topic, 1)? != status_records
        {
            return Err(format!(
                "the stopped grant performed I/O or changed its result: {}",
                case.name
            ));
        }
        true
    } else {
        false
    };
    Ok(Row {
        name: case.name.clone(),
        records: expected_records,
        output_records,
        failed_prefix,
        checkpoint,
        exit_code: expected_code,
        status_state: state.into(),
        error: case.expected_error.clone(),
        signal,
        stopped_replay,
        matched: true,
    })
}

#[derive(Clone)]
struct FaultBroker {
    broker: KafkaBroker,
    prefix: usize,
    acknowledged: Arc<AtomicUsize>,
}

impl Broker for FaultBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.broker.read(run)
    }
    fn publish(&mut self, run: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        if self.prefix == 0 || self.prefix >= records.len() {
            return Err("the failure prefix is outside the first cut".into());
        }
        self.broker.publish(run, &records[..self.prefix])?;
        self.acknowledged.store(self.prefix, Ordering::SeqCst);
        Err(format!(
            "injected failure after {} acknowledgements",
            self.prefix
        ))
    }
}

impl RunBroker for FaultBroker {
    fn watermarks(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
    ) -> Result<Vec<Watermark>, String> {
        self.broker.watermarks(bootstrap, topic, partitions)
    }
    fn publish_status(
        &mut self,
        bootstrap: &str,
        topic: &str,
        key: &str,
        payload: &[u8],
    ) -> Result<(), String> {
        self.broker.publish_status(bootstrap, topic, key, payload)
    }
}

struct Process {
    child: Child,
    stdout: PathBuf,
    stderr: PathBuf,
    reaped: bool,
}

impl Process {
    fn finish(&mut self, reader: &BaseConsumer) -> Result<ExitStatus, String> {
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {
            if let Some(status) = self
                .child
                .try_wait()
                .map_err(|error| format!("cannot wait for the runner: {error}"))?
            {
                self.reaped = true;
                return Ok(status);
            }
            if Instant::now() >= deadline {
                return Err("the signed runner did not terminate within 120 seconds".into());
            }
            poll_reader(reader)?;
        }
    }
}

impl Drop for Process {
    fn drop(&mut self) {
        if !self.reaped {
            if let Err(error) = self.child.kill() {
                eprintln!("cannot kill the unfinished signed-run child: {error}");
            }
            if let Err(error) = self.child.wait() {
                eprintln!("cannot reap the signed-run child: {error}");
            }
        }
    }
}

fn start(
    binary: &Path,
    directory: &Path,
    case: &Case,
    label: &str,
    stdin: bool,
) -> Result<Process, String> {
    let stdout = directory.join(&case.name).join(format!("{label}.stdout"));
    let stderr = directory.join(&case.name).join(format!("{label}.stderr"));
    let output = File::create_new(&stdout)
        .map_err(|error| format!("cannot create runner stdout: {error}"))?;
    let errors = File::create_new(&stderr)
        .map_err(|error| format!("cannot create runner stderr: {error}"))?;
    let mut command = runner_command(binary)?;
    let plan = if stdin {
        PathBuf::from("-")
    } else {
        child_path(directory, &case.plan)?
    };
    command
        .arg("run")
        .arg("--plan")
        .arg(plan)
        .arg("--config")
        .arg(child_path(directory, &case.config)?)
        .stdin(if stdin { Stdio::piped() } else { Stdio::null() })
        .stdout(output)
        .stderr(errors);
    let child = command
        .spawn()
        .map_err(|error| format!("cannot start the signed runner: {error}"))?;
    let mut process = Process {
        child,
        stdout,
        stderr,
        reaped: false,
    };
    if stdin {
        let mut pipe = process
            .child
            .stdin
            .take()
            .ok_or("the runner stdin pipe is absent")?;
        pipe.write_all(&read_bounded(&child_path(directory, &case.plan)?, 180_224)?)
            .map_err(|error| format!("cannot write the signed plan to stdin: {error}"))?;
    }
    Ok(process)
}

fn runner_command(binary: &Path) -> Result<Command, String> {
    match std::env::var("CARGO_TARGET_ARMV7_UNKNOWN_LINUX_MUSLEABIHF_RUNNER") {
        Ok(prefix) => {
            let words: Vec<_> = prefix.split_ascii_whitespace().collect();
            let program = words.first().ok_or("the ARMv7 runner prefix is empty")?;
            let mut command = Command::new(program);
            command.args(&words[1..]).arg(binary);
            Ok(command)
        }
        Err(std::env::VarError::NotPresent) => Ok(Command::new(binary)),
        Err(std::env::VarError::NotUnicode(_)) => {
            Err("the ARMv7 runner prefix is not UTF-8".into())
        }
    }
}

fn wait_idle(
    process: &mut Process,
    reader: &BaseConsumer,
    topic: &str,
    job: &str,
    records: u64,
    status_start: usize,
) -> Result<(), String> {
    let deadline = Instant::now() + Duration::from_secs(600);
    loop {
        if let Some(status) = process
            .child
            .try_wait()
            .map_err(|error| format!("cannot inspect the running child: {error}"))?
        {
            process.reaped = true;
            let stderr = String::from_utf8(read_bounded(&process.stderr, 1_048_576)?)
                .map_err(|_| "the runner error is not UTF-8")?;
            return Err(format!(
                "the signed runner stopped before idle: {status}: {stderr}"
            ));
        }
        if let Some(status) = latest_status_from(reader, topic, job, status_start)? {
            if status["state"] == "idle" && status["records"].as_u64() == Some(records) {
                return Ok(());
            }
            if status["state"] == "failed" {
                return Err(format!("the signed run failed before idle: {status}"));
            }
        }
        if Instant::now() >= deadline {
            return Err("the signed run did not reach the acknowledged idle boundary".into());
        }
        poll_reader(reader)?;
    }
}

fn latest_status(reader: &BaseConsumer, topic: &str, job: &str) -> Result<Option<Value>, String> {
    latest_status_from(reader, topic, job, 0)
}

fn latest_status_from(
    reader: &BaseConsumer,
    topic: &str,
    job: &str,
    start: usize,
) -> Result<Option<Value>, String> {
    let count = topic_count(reader, topic, 1)?;
    if count < start {
        return Err("the acceptance status topic moved backwards".into());
    }
    if count == start {
        return Ok(None);
    }
    let mut latest = None;
    for record in native_read::read(reader, topic, 0, start as i64, count - start)? {
        if record.partition != 0 || record.offset < 0 {
            return Err("the status record position is invalid".into());
        }
        if record.key == job.as_bytes() {
            let status: Value = serde_json::from_slice(&record.payload)
                .map_err(|error| format!("invalid acknowledged run status: {error}"))?;
            if status["schema"] != "opentsx.dag-status.v1"
                || status["job"] != job
                || status["track"] != "B"
            {
                return Err("the run status identity differs".into());
            }
            latest = Some(status);
        }
    }
    Ok(latest)
}

fn topic_count(reader: &BaseConsumer, topic: &str, partitions: usize) -> Result<usize, String> {
    let metadata = reader
        .fetch_metadata(Some(topic), Duration::from_secs(30))
        .map_err(|error| format!("cannot inspect the acceptance topic: {error}"))?;
    let topics: Vec<_> = metadata
        .topics()
        .iter()
        .filter(|entry| entry.name() == topic)
        .collect();
    if topics.len() != 1
        || topics[0].error().is_some()
        || topics[0].partitions().len() != partitions
        || topics[0].partitions().iter().any(|partition| {
            partition.id() < 0
                || partition.id() as usize >= partitions
                || partition.error().is_some()
        })
    {
        return Err(format!("the acceptance topic inventory differs: {topic}"));
    }
    let mut ids: Vec<_> = topics[0]
        .partitions()
        .iter()
        .map(|partition| partition.id())
        .collect();
    ids.sort_unstable();
    if ids != (0..partitions as i32).collect::<Vec<_>>() {
        return Err("the acceptance topic omits or duplicates a partition".into());
    }
    let mut total = 0usize;
    for partition in 0..partitions {
        let (low, high) = reader
            .fetch_watermarks(topic, partition as i32, Duration::from_secs(30))
            .map_err(|error| format!("cannot inspect acceptance watermarks: {error}"))?;
        if low != 0 || !(0..=10_000).contains(&high) {
            return Err(format!(
                "the disposable acceptance topic has invalid watermarks: {topic}"
            ));
        }
        total = total
            .checked_add(high as usize)
            .ok_or("acceptance topic count overflow")?;
    }
    Ok(total)
}

fn poll_reader(reader: &BaseConsumer) -> Result<(), String> {
    if let Some(event) = reader.poll(Duration::from_millis(100)) {
        return Err(match event {
            Err(error) => format!("the acceptance reader failed: {error}"),
            Ok(_) => "the group-free metadata reader received an unexpected record".into(),
        });
    }
    Ok(())
}

fn validate_outcome(
    bytes: &[u8],
    job: &str,
    records: u64,
    partitions: usize,
) -> Result<Value, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "the stopped outcome is not UTF-8")?;
    let value = tsx_contracts::parse_json(text, 1_048_576).map_err(str::to_string)?;
    let object = value
        .as_object()
        .ok_or("the stopped outcome is not an object")?;
    let fields = [
        "schema",
        "job",
        "planVersion",
        "records",
        "outputRecords",
        "positions",
    ];
    let positions = value["positions"]
        .as_array()
        .ok_or("the stopped outcome omits its positions")?;
    if object.len() != fields.len()
        || fields.iter().any(|field| !object.contains_key(*field))
        || value["schema"] != "opentsx.rust-run-outcome.v1"
        || value["job"] != job
        || value["planVersion"] != 1
        || value["records"].as_u64() != Some(records)
        || value["outputRecords"].as_u64() != Some(records)
        || positions.len() != partitions
    {
        return Err("the stopped outcome differs from the executed grant".into());
    }
    let mut total = 0_u64;
    for (index, position) in positions.iter().enumerate() {
        if position.as_object().is_none_or(|object| object.len() != 2)
            || position["partition"].as_str() != Some(index.to_string().as_str())
        {
            return Err("the stopped outcome partition inventory differs".into());
        }
        let offset = position["next"]
            .as_str()
            .ok_or("the stopped outcome offset is not a string")?;
        let next = tsx_runner::admission::canonical_i64(offset, "next")?;
        total = total
            .checked_add(next as u64)
            .ok_or("stopped outcome count overflow")?;
    }
    if total != records {
        return Err("the stopped outcome offsets differ from its count".into());
    }
    Ok(value)
}

fn child_path(directory: &Path, name: &str) -> Result<PathBuf, String> {
    let path = Path::new(name);
    if path
        .components()
        .any(|part| !matches!(part, Component::Normal(_)))
    {
        return Err("the fixture path is not a relative child path".into());
    }
    let candidate = fs::canonicalize(directory.join(path))
        .map_err(|error| format!("cannot locate the fixture child: {error}"))?;
    if !candidate.starts_with(directory) || !candidate.is_file() {
        return Err("the fixture child escapes its directory or is not a file".into());
    }
    Ok(candidate)
}

fn read_bounded(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
    let file =
        File::open(path).map_err(|error| format!("cannot open acceptance input: {error}"))?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("cannot read acceptance input: {error}"))?;
    if bytes.len() > limit {
        return Err("acceptance input exceeds its byte limit".into());
    }
    Ok(bytes)
}

fn create_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    File::create_new(path)
        .and_then(|mut file| file.write_all(bytes))
        .map_err(|error| format!("cannot write acceptance evidence: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn document() -> Value {
        let row = |name: &str| {
            let bad = matches!(name, "unknown" | "malformed");
            let empty = name == "stdin";
            let prefix = match name {
                "crash-1" => Some(1),
                "crash-150" => Some(150),
                "crash-299" => Some(299),
                _ => None,
            };
            json!({"name":name,"plan":format!("{name}/plan.jws"),"config":format!("{name}/config.json"),
                "inputTopic":"tsx.b1run.input.v2","outputTopic":format!("tsx.b1run.{name}.out.v2"),
                "partitions":if bad || empty {1} else {3},"inputCount":if empty {0} else if bad {2} else {300},
                "expectedDistinct":if bad || empty {0} else {300},"expectedRecords":if bad || empty {0} else {300 + prefix.unwrap_or(0)},
                "failAfter":prefix,"expectedError":if bad {Some("contextual schema failure")} else {None}})
        };
        json!({"schema":"opentsx.rust-b1-run-fixture.v1","artifactDigest":"sha256:actual",
            "inputRecords":300,"series":100,"samples":180000,"statusTopic":"tsx.b1run.dag-status.v1",
            "cases":NAMES.into_iter().map(row).collect::<Vec<_>>(),"stdin":row("stdin")})
    }

    #[test]
    fn all_seven_required_manifest_invocations_are_counted_without_empty_success() {
        let value = document();
        let parsed = parse_fixture(&serde_json::to_vec(&value).unwrap(), "sha256:actual").unwrap();
        assert_eq!(
            parsed
                .cases
                .iter()
                .map(|case| case.name.as_str())
                .collect::<Vec<_>>(),
            NAMES
        );
        assert_eq!(parsed.stdin.name, "stdin");
        for field in ["cases", "stdin", "artifactDigest", "samples", "unexpected"] {
            let mut mutation = value.clone();
            match field {
                "cases" => mutation[field] = json!([]),
                "stdin" => mutation
                    .as_object_mut()
                    .unwrap()
                    .remove(field)
                    .map(|_| ())
                    .unwrap(),
                "artifactDigest" => mutation[field] = json!("sha256:other"),
                "samples" => mutation[field] = json!(0),
                _ => mutation[field] = json!(true),
            }
            assert!(
                parse_fixture(&serde_json::to_vec(&mutation).unwrap(), "sha256:actual").is_err(),
                "{field}"
            );
        }
    }

    #[test]
    fn wrong_prefixes_case_order_paths_counts_and_error_expectations_are_rejected() {
        let value = document();
        for field in [
            "name",
            "plan",
            "config",
            "failAfter",
            "expectedRecords",
            "expectedDistinct",
            "expectedError",
            "partitions",
        ] {
            let mut mutation = value.clone();
            mutation["cases"][1][field] = match field {
                "name" => json!("normal"),
                "plan" => json!("../plan.jws"),
                "config" => json!("/tmp/config.json"),
                "expectedError" => json!("invented"),
                _ => json!(0),
            };
            assert!(
                parse_fixture(&serde_json::to_vec(&mutation).unwrap(), "sha256:actual").is_err(),
                "{field}"
            );
        }
    }

    #[test]
    fn stopped_outcomes_require_exact_schema_counts_and_whole_topic_cursors() {
        let value = json!({"schema":"opentsx.rust-run-outcome.v1","job":"b1-normal","planVersion":1,
            "records":300,"outputRecords":300,"positions":[{"partition":"0","next":"100"},{"partition":"1","next":"100"},{"partition":"2","next":"100"}]});
        assert_eq!(
            validate_outcome(&serde_json::to_vec(&value).unwrap(), "b1-normal", 300, 3).unwrap(),
            value
        );
        for field in [
            "schema",
            "job",
            "planVersion",
            "records",
            "outputRecords",
            "positions",
            "unexpected",
        ] {
            let mut mutation = value.clone();
            mutation[field] = match field {
                "schema" | "job" => json!("wrong"),
                "positions" => json!([]),
                _ => json!(0),
            };
            assert!(
                validate_outcome(&serde_json::to_vec(&mutation).unwrap(), "b1-normal", 300, 3)
                    .is_err(),
                "{field}"
            );
        }
        for field in ["partition", "next"] {
            let mut mutation = value.clone();
            mutation["positions"][0][field] = json!("1");
            assert!(
                validate_outcome(&serde_json::to_vec(&mutation).unwrap(), "b1-normal", 300, 3)
                    .is_err(),
                "{field}"
            );
        }
    }
}
