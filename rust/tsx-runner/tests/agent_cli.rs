#[path = "common/runner.rs"]
mod cli_runner;
#[path = "common/run_profile.rs"]
mod profile;

use serde_json::{json, Value};
use std::fs;
use std::io::Write;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::run_loop::{self, RunBroker, StopToken, Watermark};
use tsx_runner::run_plan::{RunConfig, SignedPlan};

static NEXT: AtomicU64 = AtomicU64::new(0);

#[test]
fn agent_help_names_the_explicit_local_configuration() {
    let output = cli_runner::runner()
        .args(["agent", "--help"])
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(0), "{output:?}");
    assert!(output.stderr.is_empty(), "{output:?}");
    assert!(String::from_utf8(output.stdout)
        .unwrap()
        .contains("agent --config <file>"));
}

#[test]
fn conform_help_names_the_locally_provisioned_job() {
    let output = cli_runner::runner()
        .args(["conform", "--help"])
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(0), "{output:?}");
    assert!(output.stderr.is_empty(), "{output:?}");
    assert!(String::from_utf8(output.stdout)
        .unwrap()
        .contains("conform --config <file> --job <job>"));
}

#[test]
fn agent_refuses_invalid_local_configuration_before_network_access() {
    let path = std::env::temp_dir().join(format!(
        "tsx-agent-cli-{}-{}.json",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::SeqCst)
    ));
    fs::write(&path, b"{}").unwrap();
    let output = cli_runner::runner()
        .args(["agent", "--config"])
        .arg(&path)
        .output()
        .unwrap();
    fs::remove_file(&path).unwrap();
    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(output.stdout.is_empty(), "{output:?}");
    assert_eq!(
        output.stderr,
        b"plan.invalid: site configuration fields are invalid\n"
    );
}

#[derive(Clone)]
struct StoppedBroker;

impl Broker for StoppedBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        Err("a requested Stop must not fetch input".into())
    }
    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        Err("a requested Stop must not publish output".into())
    }
}
impl RunBroker for StoppedBroker {
    fn watermarks(&mut self, _: &str, _: &str, _: usize) -> Result<Vec<Watermark>, String> {
        Err("a requested Stop must not fetch watermarks".into())
    }
    fn publish_status(&mut self, _: &str, _: &str, _: &str, _: &[u8]) -> Result<(), String> {
        Ok(())
    }
}

#[test]
fn documented_signed_plan_file_and_stdin_replay_the_same_saved_stop() {
    let fixture = profile::Fixture::new();
    let artifact = format!(
        "sha256:{}",
        tsx_contracts::sha256_bytes(&fs::read(env!("CARGO_BIN_EXE_tsx-runner")).unwrap())
    );
    let mut document = fixture.plan_document();
    let mut assignment: Value =
        serde_json::from_str(document["assignmentText"].as_str().unwrap()).unwrap();
    assignment["artifactDigest"] = json!(artifact);
    document["assignmentText"] = json!(assignment.to_string());
    document["issued"] = json!("1970-01-01T00:00:01Z");
    document["expires"] = json!("9999-12-31T23:59:59Z");
    let signed = fixture.sign(&document);
    let config = RunConfig::load(&fixture.config_path).unwrap();
    let plan = SignedPlan::verify(&signed, &config, &artifact).unwrap();
    let stop = StopToken::new();
    stop.request().unwrap();
    let outcome = run_loop::execute(&plan, &config, &mut StoppedBroker, &stop).unwrap();
    let signed_path = fixture.directory.join("plan.jws");
    fs::write(&signed_path, &signed).unwrap();
    let mut outputs = Vec::new();
    for stdin in [false, true] {
        let mut command = cli_runner::runner();
        command.args(["run", "--plan"]);
        if stdin {
            command.arg("-");
        } else {
            command.arg(&signed_path);
        }
        command
            .arg("--config")
            .arg(&fixture.config_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let mut child = command.spawn().unwrap();
        if stdin {
            child
                .stdin
                .take()
                .unwrap()
                .write_all(signed.as_bytes())
                .unwrap();
        } else {
            drop(child.stdin.take());
        }
        let output = child.wait_with_output().unwrap();
        assert_eq!(output.status.code(), Some(0), "{output:?}");
        assert!(output.stderr.is_empty(), "{output:?}");
        assert_eq!(
            serde_json::from_slice::<Value>(&output.stdout).unwrap(),
            serde_json::to_value(&outcome).unwrap()
        );
        outputs.push(output.stdout);
    }
    assert_eq!(outputs[0], outputs[1]);
}
