#[path = "../tests/common/run_profile.rs"]
mod profile;

use serde_json::{json, Value};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;
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
        Err("the SSH fixture must not read broker input".into())
    }

    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        Err("the SSH fixture must not publish broker output".into())
    }
}

impl RunBroker for StoppedBroker {
    fn watermarks(&mut self, _: &str, _: &str, _: usize) -> Result<Vec<Watermark>, String> {
        Err("the SSH fixture must not query broker bounds".into())
    }

    fn publish_status(
        &mut self,
        _: &str,
        topic: &str,
        _: &str,
        payload: &[u8],
    ) -> Result<(), String> {
        if topic != profile::STATUS_TOPIC {
            return Err("the SSH fixture status topic changed".into());
        }
        let value: Value =
            serde_json::from_slice(payload).map_err(|_| "the SSH fixture status is not JSON")?;
        let state = value["state"]
            .as_str()
            .ok_or("the SSH fixture status omitted its state")?;
        self.states
            .lock()
            .map_err(|_| "the SSH fixture status state is poisoned")?
            .push(state.to_string());
        Ok(())
    }
}

fn main() {
    match execute() {
        Ok(value) => match serde_json::to_string(&value) {
            Ok(text) => println!("{text}"),
            Err(error) => {
                eprintln!("cannot encode the SSH fixture result: {error}");
                std::process::exit(1);
            }
        },
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}

fn execute() -> Result<Value, String> {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    if arguments.len() != 2 {
        return Err("usage: b3_ssh <tsx-runner-binary> <new-fixture-directory>".into());
    }
    let binary = Path::new(&arguments[0])
        .canonicalize()
        .map_err(|error| format!("cannot locate the SSH runner: {error}"))?;
    let directory = Path::new(&arguments[1]);
    fs::create_dir(directory)
        .map_err(|error| format!("cannot create the SSH fixture directory: {error}"))?;
    fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
        .map_err(|error| format!("cannot protect the SSH fixture directory: {error}"))?;
    let directory = directory
        .canonicalize()
        .map_err(|error| format!("cannot locate the SSH fixture directory: {error}"))?;
    let artifact = format!(
        "sha256:{}",
        tsx_contracts::sha256_bytes(&read(&binary, 268_435_456)?)
    );
    let signing = profile::Fixture::new();
    write(
        &directory.join("driver-public.pem"),
        &read(&signing.directory.join("driver-public.pem"), 16_384)?,
    )?;
    let mut config_document = signing.config_document();
    config_document["checkpoint"] = json!(directory.join("checkpoint.json"));
    let config_path = directory.join("run.json");
    write(&config_path, &encode(&config_document)?)?;
    let config = RunConfig::load(&config_path).map_err(|error| error.to_string())?;
    let mut document = signing.plan_document();
    let mut assignment: Value = serde_json::from_str(
        document["assignmentText"]
            .as_str()
            .ok_or("the SSH fixture assignment is missing")?,
    )
    .map_err(|_| "the SSH fixture assignment is invalid")?;
    assignment["artifactDigest"] = json!(artifact);
    document["assignmentText"] = json!(serde_json::to_string(&assignment)
        .map_err(|_| "cannot encode the SSH fixture assignment")?);
    document["issued"] = json!("1970-01-01T00:00:01Z");
    document["expires"] = json!("9999-12-31T23:59:59Z");
    let compact = signing.sign(&document);
    let plan =
        SignedPlan::verify(&compact, &config, &artifact).map_err(|error| error.to_string())?;
    let stop = StopToken::new();
    stop.request()?;
    let mut broker = StoppedBroker::default();
    let outcome =
        run_loop::execute(&plan, &config, &mut broker, &stop).map_err(|error| error.to_string())?;
    if outcome.records != 0
        || outcome.output_records != 0
        || broker
            .states
            .lock()
            .map_err(|_| "the SSH fixture state is poisoned")?
            .last()
            .map(String::as_str)
            != Some("stopped")
        || config.checkpoint.exists()
    {
        return Err("the SSH fixture did not retain an empty stopped outcome".into());
    }
    let (prefix, signature) = compact
        .rsplit_once('.')
        .ok_or("the SSH fixture JWS is invalid")?;
    let mut altered = signature.as_bytes().to_vec();
    altered[0] = if altered[0] == b'A' { b'B' } else { b'A' };
    let refused = format!(
        "{prefix}.{}",
        String::from_utf8(altered).map_err(|_| "the SSH fixture signature is not UTF-8")?
    );
    write(&directory.join("plan.jws"), compact.as_bytes())?;
    write(&directory.join("refused.jws"), refused.as_bytes())?;
    let manifest = json!({"schema":"opentsx.rust-ssh-fixture.v1",
        "runnerVersion":env!("CARGO_PKG_VERSION"),"artifactDigest":artifact,
        "config":"run.json","plan":"plan.jws","refusedPlan":"refused.jws",
        "checkpoint":"checkpoint.json","intent":".checkpoint.json.run-intent","outcome":outcome});
    write(&directory.join("fixture.json"), &encode(&manifest)?)?;
    Ok(manifest)
}

fn encode(value: &Value) -> Result<Vec<u8>, String> {
    serde_json::to_vec(value).map_err(|error| format!("cannot encode SSH fixture JSON: {error}"))
}

fn read(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
    let file =
        File::open(path).map_err(|error| format!("cannot open SSH fixture input: {error}"))?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("cannot read SSH fixture input: {error}"))?;
    if bytes.len() > limit {
        return Err("the SSH fixture input exceeds its byte limit".into());
    }
    Ok(bytes)
}

fn write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .map_err(|error| format!("cannot create SSH fixture output: {error}"))?;
    file.write_all(bytes)
        .and_then(|_| file.sync_all())
        .map_err(|error| format!("cannot persist SSH fixture output: {error}"))
}
