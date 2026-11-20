#[path = "common/runner.rs"]
mod cli_runner;
#[path = "common/run_profile.rs"]
mod profile;

use profile::Fixture;
use serde_json::{json, Value};
use std::fs;
use std::io::Write;
use std::process::{Output, Stdio};
use std::sync::{Arc, Mutex};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::run_loop::{self, RunBroker, StopToken, Watermark};
use tsx_runner::run_plan::{RunConfig, SignedPlan};

#[derive(Clone, Default)]
struct StoppedBroker {
    states: Arc<Mutex<Vec<String>>>,
}

impl Broker for StoppedBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        panic!("a requested Stop must not fetch input")
    }

    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        panic!("a requested Stop must not publish output")
    }
}

impl RunBroker for StoppedBroker {
    fn watermarks(&mut self, _: &str, _: &str, _: usize) -> Result<Vec<Watermark>, String> {
        panic!("a requested Stop must not query broker bounds")
    }

    fn publish_status(
        &mut self,
        _: &str,
        topic: &str,
        _: &str,
        payload: &[u8],
    ) -> Result<(), String> {
        assert_eq!(topic, profile::STATUS_TOPIC);
        let status: Value = serde_json::from_slice(payload).unwrap();
        assert_eq!(status["schema"], "opentsx.dag-status.v1");
        self.states
            .lock()
            .unwrap()
            .push(status["state"].as_str().unwrap().to_string());
        Ok(())
    }
}

fn signed_plan(fixture: &Fixture) -> (String, String) {
    let executable = fs::read(env!("CARGO_BIN_EXE_tsx-runner")).unwrap();
    let artifact = format!("sha256:{}", tsx_contracts::sha256_bytes(&executable));
    let mut document = fixture.plan_document();
    let mut assignment: Value =
        serde_json::from_str(document["assignmentText"].as_str().unwrap()).unwrap();
    assignment["artifactDigest"] = json!(artifact);
    document["assignmentText"] = json!(serde_json::to_string(&assignment).unwrap());
    document["issued"] = json!("1970-01-01T00:00:01Z");
    document["expires"] = json!("9999-12-31T23:59:59Z");
    (fixture.sign(&document), artifact)
}

fn launch(fixture: &Fixture, compact: &str) -> Output {
    let mut child = cli_runner::runner()
        .args(["run", "--plan", "-", "--config"])
        .arg(&fixture.config_path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let mut input = child.stdin.take().unwrap();
    input.write_all(compact.as_bytes()).unwrap();
    drop(input);
    child.wait_with_output().unwrap()
}

#[test]
fn forced_command_stdin_returns_zero_for_a_saved_stop_without_reactivation() {
    let fixture = Fixture::new();
    let (compact, artifact) = signed_plan(&fixture);
    let config = RunConfig::load(&fixture.config_path).unwrap();
    let plan = SignedPlan::verify(&compact, &config, &artifact).unwrap();
    let stop = StopToken::new();
    stop.request().unwrap();
    let mut broker = StoppedBroker::default();
    let outcome = run_loop::execute(&plan, &config, &mut broker, &stop).unwrap();
    assert_eq!(outcome.records, 0);
    assert_eq!(outcome.output_records, 0);
    assert_eq!(
        broker.states.lock().unwrap().last().map(String::as_str),
        Some("stopped")
    );
    let intent = run_loop::intent_path(&config.checkpoint).unwrap();
    let saved = fs::read(&intent).unwrap();
    assert!(!config.checkpoint.exists());

    for _ in 0..2 {
        let output = launch(&fixture, &compact);
        assert_eq!(output.status.code(), Some(0), "{output:?}");
        assert!(output.stderr.is_empty(), "{output:?}");
        assert_eq!(
            serde_json::from_slice::<Value>(&output.stdout).unwrap(),
            serde_json::to_value(&outcome).unwrap()
        );
        assert_eq!(fs::read(&intent).unwrap(), saved);
        assert!(!config.checkpoint.exists());
    }
}

#[test]
fn forced_command_stdin_returns_two_with_the_exact_signed_refusal() {
    let fixture = Fixture::new();
    let compact = fixture.sign(&fixture.plan_document());
    let (prefix, signature) = compact.rsplit_once('.').unwrap();
    let mut changed = signature.as_bytes().to_vec();
    changed[0] = if changed[0] == b'A' { b'B' } else { b'A' };
    let compact = format!("{prefix}.{}", String::from_utf8(changed).unwrap());

    let output = launch(&fixture, &compact);
    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(output.stdout.is_empty(), "{output:?}");
    assert_eq!(
        output.stderr,
        b"plan.signature: plan signature is invalid\n"
    );
    assert!(!fixture.directory.join("checkpoint.json").exists());
    assert!(
        !run_loop::intent_path(&fixture.directory.join("checkpoint.json"))
            .unwrap()
            .exists()
    );
}

#[test]
fn forced_command_stdin_returns_three_for_corrupt_progress_before_broker_access() {
    let fixture = Fixture::new();
    let (compact, artifact) = signed_plan(&fixture);
    let config = RunConfig::load(&fixture.config_path).unwrap();
    SignedPlan::verify(&compact, &config, &artifact).unwrap();
    let broken = serde_json::to_vec(&json!({
        "schema":"opentsx.local-checkpoint.v1",
        "checksum":"0".repeat(64),
        "payload":{}
    }))
    .unwrap();
    fs::write(&config.checkpoint, &broken).unwrap();

    let output = launch(&fixture, &compact);
    assert_eq!(output.status.code(), Some(3), "{output:?}");
    assert!(output.stdout.is_empty(), "{output:?}");
    assert_eq!(output.stderr, b"checkpoint checksum mismatch\n");
    assert_eq!(fs::read(&config.checkpoint).unwrap(), broken);
    assert!(!run_loop::intent_path(&config.checkpoint).unwrap().exists());
}
