use serde_json::{json, Value};
use std::fs;
use std::path::Path;
use tsx_runner::run_loop::{RunError, StopToken};
use tsx_runner::site_plan::{BoundedPlan, SiteConfig};

fn read(path: &Path, maximum: u64) -> Result<Vec<u8>, RunError> {
    let metadata =
        fs::symlink_metadata(path).map_err(|e| format!("cannot inspect B3 fixture input: {e}"))?;
    if !metadata.is_file() || metadata.len() > maximum {
        return Err("B3 fixture input must be a bounded regular file".into());
    }
    let bytes = fs::read(path).map_err(|e| format!("cannot read B3 fixture input: {e}"))?;
    if bytes.len() as u64 > maximum {
        return Err("B3 fixture input grew past its bound".into());
    }
    Ok(bytes)
}

fn execute(mode: &str, config: &Path, signed: &Path) -> Result<Value, RunError> {
    let binary =
        std::env::current_exe().map_err(|e| format!("cannot locate B3 fixture executable: {e}"))?;
    let artifact = format!(
        "sha256:{}",
        tsx_contracts::sha256_bytes(&read(&binary, 268_435_456)?)
    );
    let config = SiteConfig::load(config)?;
    let compact = String::from_utf8(read(signed, 524_288)?)
        .map_err(|_| "signed B3 fixture plan is not UTF-8")?;
    let plan = BoundedPlan::verify(&compact, &config, &artifact)?;
    let mut result = match mode {
        "conform" => {
            let conformance =
                tsx_runner::conformance::conform_once(&config, &artifact, plan.job())?;
            Ok(json!({"state":"conformed","completed":true,"conformance":conformance}))
        }
        "execute" => serde_json::to_value(tsx_runner::federation::execute(
            &plan,
            &config,
            &StopToken::new(),
        )?),
        "forward" => serde_json::to_value(tsx_runner::forwarder::forward(&plan, &config)?),
        "forward-ack-loss" => {
            let outcome = tsx_runner::forwarder::forward_with_after_ack(&plan, &config, || {
                Err("simulated failure after target acknowledgement before cursor commit".into())
            })?;
            if outcome.completed
                || outcome.error.as_deref()
                    != Some("simulated failure after target acknowledgement before cursor commit")
            {
                return Err(
                    "B3 fixture did not reach the exact target-ACK failure boundary".into(),
                );
            }
            serde_json::to_value(outcome)
        }
        _ => return Err("unknown B3 fixture mode".into()),
    }
    .map_err(|e| format!("cannot encode B3 fixture result: {e}"))?;
    result["schema"] = json!("opentsx.rust-b3-offline-result.v1");
    result["version"] = json!(env!("CARGO_PKG_VERSION"));
    result["artifactDigest"] = json!(artifact);
    result["job"] = json!(plan.job());
    Ok(result)
}

fn main() {
    let arguments: Vec<_> = std::env::args().collect();
    if arguments.len() == 2 && arguments[1] == "--version" {
        println!("tsx-runner {}", env!("CARGO_PKG_VERSION"));
        return;
    }
    if arguments.len() == 2 && arguments[1] == "--help" {
        println!(
            "b3_offline <conform|execute|forward|forward-ack-loss> <site-config> <signed-plan>"
        );
        return;
    }
    let result = if arguments.len() == 4 {
        execute(
            &arguments[1],
            Path::new(&arguments[2]),
            Path::new(&arguments[3]),
        )
    } else {
        Err("expected B3 fixture mode, site configuration and signed plan".into())
    };
    match result {
        Ok(result) => println!("{result}"),
        Err(RunError::Refused(error)) => {
            eprintln!("{}: {}", error.code, error.message);
            std::process::exit(2);
        }
        Err(RunError::Runtime(error)) => {
            eprintln!("{error}");
            std::process::exit(3);
        }
    }
}
