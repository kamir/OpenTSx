use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, KafkaBroker, OutputRecord};
use tsx_runner::worker::{Config, Worker};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema: String,
    node: String,
    site: String,
    artifact_digest: String,
    requests: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    schema: &'static str,
    version: &'static str,
    artifact_digest: String,
    increments: usize,
    input_records: usize,
    output_records: usize,
    restarts: usize,
    completed_retries: usize,
}

#[derive(Default)]
struct CountedBroker {
    reads: usize,
    publications: usize,
}

impl Broker for CountedBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        KafkaBroker.read(run)
    }

    fn publish(&mut self, run: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        self.publications += 1;
        KafkaBroker.publish(run, records)
    }
}

fn read(path: &Path, limit: u64) -> Result<Vec<u8>, String> {
    let metadata =
        fs::symlink_metadata(path).map_err(|e| format!("cannot inspect parity input: {e}"))?;
    if !metadata.is_file() || metadata.len() > limit {
        return Err("parity input must be a bounded regular file".into());
    }
    let bytes = fs::read(path).map_err(|e| format!("cannot read parity input: {e}"))?;
    if bytes.len() as u64 > limit {
        return Err("parity input grew past its bound".into());
    }
    Ok(bytes)
}

fn execute(bootstrap: &str, directory: &Path) -> Result<Report, String> {
    let directory = directory
        .canonicalize()
        .map_err(|e| format!("cannot resolve parity input directory: {e}"))?;
    let binary =
        std::env::current_exe().map_err(|e| format!("cannot locate parity executable: {e}"))?;
    let artifact_digest = format!(
        "sha256:{}",
        tsx_contracts::sha256_bytes(&read(&binary, 268_435_456)?)
    );
    let manifest: Manifest =
        serde_json::from_slice(&read(&directory.join("manifest.json"), 1_048_576)?)
            .map_err(|e| format!("invalid parity manifest: {e}"))?;
    if manifest.schema != "opentsx.rust-b2-parity-fixture.v1"
        || manifest.artifact_digest != artifact_digest
        || manifest.requests.is_empty()
        || manifest.requests.len() > 10_000
    {
        return Err("parity manifest context differs from this executable".into());
    }
    let config = || Config {
        node: manifest.node.clone(),
        site: manifest.site.clone(),
        bootstrap: bootstrap.into(),
        artifact_digest: artifact_digest.clone(),
        state: directory.join("worker"),
    };
    let mut broker = CountedBroker::default();
    let mut report = Report {
        schema: "opentsx.rust-b2-parity.v1",
        version: env!("CARGO_PKG_VERSION"),
        artifact_digest: artifact_digest.clone(),
        increments: 0,
        input_records: 0,
        output_records: 0,
        restarts: 0,
        completed_retries: 0,
    };
    for (index, name) in manifest.requests.iter().enumerate() {
        if name != &format!("request-{:05}.json", index + 1) {
            return Err("parity requests must use the ordered canonical names".into());
        }
        let text = String::from_utf8(read(&directory.join(name), 65_536)?)
            .map_err(|_| "parity request is not UTF-8")?;
        let request_hash = tsx_contracts::sha256(&text);
        let mut worker = Worker::open(config())?;
        let prepared = worker.prepare(&text, &mut broker)?;
        if prepared.state != "prepared" || prepared.request_hash != request_hash {
            return Err(format!(
                "parity preparation did not succeed: {}",
                prepared.error.as_deref().unwrap_or(&prepared.state)
            ));
        }
        drop(worker);
        report.restarts += 1;
        let mut worker = Worker::open(config())?;
        let activated = worker.activate(&request_hash, &mut broker)?;
        if activated.state != "completed" {
            return Err(format!(
                "parity activation did not succeed: {}",
                activated.error.as_deref().unwrap_or(&activated.state)
            ));
        }
        let receipt = activated
            .receipt
            .ok_or("parity completion has no receipt")?;
        report.input_records = report
            .input_records
            .checked_add(receipt.input_records)
            .ok_or("parity input count overflow")?;
        report.output_records = report
            .output_records
            .checked_add(receipt.output_records)
            .ok_or("parity output count overflow")?;
        drop(worker);
        report.restarts += 1;
        let mut worker = Worker::open(config())?;
        let before = (broker.reads, broker.publications);
        let retry = worker.prepare(&text, &mut broker)?;
        let repeated = worker.activate(&request_hash, &mut broker)?;
        if retry.state != "completed"
            || repeated.state != "completed"
            || (broker.reads, broker.publications) != before
        {
            return Err("completed parity retry performed I/O or changed its outcome".into());
        }
        report.completed_retries += 1;
        report.increments += 1;
    }
    Ok(report)
}

fn main() {
    let arguments: Vec<_> = std::env::args().collect();
    if arguments.len() == 2 && arguments[1] == "--help" {
        println!("b2_parity <proxy-bootstrap> <request-directory>");
        return;
    }
    let result = if arguments.len() == 3 {
        execute(&arguments[1], &PathBuf::from(&arguments[2]))
    } else {
        Err("expected Proxy bootstrap and request directory".into())
    };
    match result.and_then(|report| {
        serde_json::to_string(&report).map_err(|e| format!("cannot encode parity evidence: {e}"))
    }) {
        Ok(report) => println!("{report}"),
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(3);
        }
    }
}
