mod common;

use serde_json::{json, Value};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tsx_contracts::physical as contracts;
use tsx_runner::admission::Admitted;
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::physical::{AdmissionFacts, Observation};
use tsx_runner::worker::{Config, Worker};
use tsx_runner::{admission::Profile, physical, wire};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-physical-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn config(&self) -> Config {
        Config {
            node: "worker-1".into(),
            site: "local".into(),
            bootstrap: "localhost:9092".into(),
            artifact_digest: common::ARTIFACT_DIGEST.into(),
            state: self.0.clone(),
        }
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[derive(Default)]
struct MemoryBroker {
    reads: usize,
    publishes: usize,
    captures: usize,
    output: Vec<OutputRecord>,
    fenced: bool,
}

impl Broker for MemoryBroker {
    fn fenced(&self) -> bool {
        self.fenced
    }
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        Ok((0..2)
            .map(|offset| {
                let episode =
                    common::episode(vec![1000.0, 2000.0], 10_000_000 + offset * 5_000_000);
                InputRecord {
                    partition: 0,
                    offset,
                    key: episode.series.series_id.as_bytes().to_vec(),
                    payload: wire::encode(&episode).unwrap(),
                }
            })
            .collect())
    }

    fn publish(&mut self, _: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        self.publishes += 1;
        self.output.extend_from_slice(records);
        Ok(())
    }

    fn capture_grant(
        &mut self,
        _: &Admitted,
        _: Option<&tsx_runner::publication::PublicationGrant>,
    ) -> Result<Option<tsx_runner::publication::PublicationGrant>, String> {
        self.captures += 1;
        Ok(None)
    }
}

#[derive(Clone)]
struct HostFacts(Arc<Mutex<(Value, String)>>);

impl AdmissionFacts for HostFacts {
    fn observe(&mut self, _: &Value) -> Result<Observation, contracts::Refusal> {
        let (facts, now_micros) = self.0.lock().unwrap().clone();
        Ok(Observation { facts, now_micros })
    }
}

fn fixture(capabilities: Value) -> (Value, Value, Value) {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/datamodel-2026/testvectors/physical-deployment.v1.json");
    let vectors: Value = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    let case = vectors["cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["name"] == "complete-bounded-deployment")
        .unwrap();
    let mut plan: Value = serde_json::from_str(case["input"].as_str().unwrap()).unwrap();
    let mut dag: Value = serde_json::from_str(plan["sourceDagText"].as_str().unwrap()).unwrap();
    dag["source"]["series"] = json!([common::episode(vec![1.0], 0).series.series_id]);
    let dag_text = dag.to_string();
    let mut assignment: Value =
        serde_json::from_str(plan["assignmentTexts"][0].as_str().unwrap()).unwrap();
    assignment["site"] = json!("local");
    assignment["sourceDagText"] = json!(dag_text);
    assignment["specHash"] = json!(tsx_contracts::sha256(&dag_text));
    assignment["fragmentText"] = json!(dag_text);
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&dag_text));
    assignment["inputs"][0]["positions"][1]["to"] = json!("0");
    plan["assignmentTexts"][0] = json!(assignment.to_string());
    plan["sourceDagText"] = json!(dag_text);
    plan["specHash"] = json!(tsx_contracts::sha256(&dag_text));
    plan["computationText"] = json!(contracts::encode_computation(&dag).unwrap());
    plan["computationHash"] = json!(tsx_contracts::sha256(
        plan["computationText"].as_str().unwrap()
    ));
    plan["capabilities"][0]["reportText"] = json!(capabilities.to_string());
    plan["capabilities"][0]["digest"] = json!(tsx_contracts::sha256(
        plan["capabilities"][0]["reportText"].as_str().unwrap()
    ));
    for channel in plan["channels"].as_array_mut().unwrap() {
        channel["residency"] = json!(["local"]);
    }
    plan["planHash"] = json!(contracts::plan_hash(&plan).unwrap());
    let mut facts = case["facts"].clone();
    facts["capabilities"] = plan["capabilities"].clone();
    facts["residency"][0]["sites"] = json!(["local"]);
    let mut request = common::request();
    request["assignmentText"] = plan["assignmentTexts"][0].clone();
    request["createdAtMicros"] = plan["createdAtMicros"].clone();
    request["bindings"]["local"]["inputs"] = json!(["tsx.wind.episodes.v2"]);
    request["bindings"]["local"]["outputs"] = json!(["tsx.wind.clean.v2"]);
    request["sink"]["name"] = json!("wind-clean");
    request["sink"]["topic"] = json!("tsx.wind.clean.v2");
    contracts::admit(&plan.to_string(), &facts, "120").unwrap();
    (plan, facts, request)
}

fn setup() -> (Directory, Worker, HostFacts, String) {
    let directory = Directory::new();
    let worker = Worker::open(directory.config()).unwrap();
    let (plan, facts, request) = fixture(worker.capabilities());
    drop(worker);
    let provider = HostFacts(Arc::new(Mutex::new((facts, "120".into()))));
    let worker = Worker::open_with_facts(
        directory.config(),
        Profile::STANDARD,
        Box::new(provider.clone()),
    )
    .unwrap();
    let text = physical::wrapper(&plan.to_string(), &request.to_string());
    (directory, worker, provider, text)
}

fn prepared_bytes(directory: &Directory, request_hash: &str) -> Vec<u8> {
    fs::read(directory.0.join(request_hash).join("prepared.json")).unwrap()
}

#[test]
fn malformed_physical_request_has_located_refusal_before_broker_access() {
    let directory = Directory::new();
    let mut worker = Worker::open(directory.config()).unwrap();
    let text = json!({"schema":"opentsx.local-physical-bounded-run.v1",
        "physicalText":"{}","requestText":common::request().to_string()})
    .to_string();
    let mut broker = MemoryBroker::default();
    let error = worker.prepare(&text, &mut broker).unwrap_err();
    let refusal: Value = serde_json::from_str(&error).expect("structured physical refusal");
    assert_eq!(refusal["schema"], "opentsx.plan-refusal.v1");
    assert_eq!(refusal["code"], "deployment.fields");
    assert_eq!(refusal["field"], "/");
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}

#[test]
fn authoritative_facts_prepare_publish_retain_exact_envelope_and_retry() {
    use tiny_http::{Method, TestRequest};
    let (directory, mut worker, provider, text) = setup();
    let mut broker = MemoryBroker::default();
    let mut request = TestRequest::new()
        .with_method(Method::Post)
        .with_path("/v1/prepare-physical")
        .with_body(Box::leak(text.clone().into_boxed_str()))
        .into();
    let (code, response) = worker.handle(&mut request, &mut broker);
    assert_eq!(code, 200);
    let prepared = worker
        .status(response["requestHash"].as_str().unwrap())
        .unwrap();
    assert_eq!(prepared.state, "prepared");
    assert_eq!((broker.reads, broker.publishes, broker.captures), (1, 0, 1));
    assert!(broker.output.is_empty());
    let journal: Value =
        serde_json::from_slice(&prepared_bytes(&directory, &prepared.request_hash)).unwrap();
    assert_eq!(journal["payload"]["requestText"], text);
    assert_eq!(prepared.request_hash, tsx_contracts::sha256(&text));
    let mut request = TestRequest::new()
        .with_method(Method::Post)
        .with_path(&format!("/v1/activate/{}", prepared.request_hash))
        .into();
    let (code, response) = worker.handle(&mut request, &mut broker);
    assert_eq!(code, 200);
    let completed = worker.status(&prepared.request_hash).unwrap();
    assert_eq!(response, serde_json::to_value(&completed).unwrap());
    assert_eq!(completed.state, "completed");
    assert_eq!(broker.output.len(), 2);
    assert_eq!(
        wire::decode(&broker.output[0].payload, 1024, 1000)
            .unwrap()
            .values,
        [1.0, 2.0]
    );
    drop(worker);
    let mut worker =
        Worker::open_with_facts(directory.config(), Profile::STANDARD, Box::new(provider)).unwrap();
    assert_eq!(worker.prepare(&text, &mut broker).unwrap(), completed);
    assert_eq!(
        worker
            .activate(&prepared.request_hash, &mut broker)
            .unwrap(),
        completed
    );
    assert_eq!((broker.reads, broker.publishes, broker.captures), (1, 1, 1));
}

#[test]
fn unavailable_authoritative_provider_refuses_before_capture_read_or_journal() {
    let (directory, worker, _, text) = setup();
    drop(worker);
    let mut worker = Worker::open(directory.config()).unwrap();
    let mut broker = MemoryBroker::default();
    let error = worker.prepare(&text, &mut broker).unwrap_err();
    let refusal: Value = serde_json::from_str(&error).unwrap();
    assert_eq!(refusal["code"], "deployment.incarnation");
    assert_eq!(refusal["node"], "decode");
    assert_eq!(refusal["field"], "/channels/0/incarnation");
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}

#[test]
fn changed_incarnation_blocks_activation_and_prepared_retry_without_mutation() {
    let (directory, mut worker, provider, text) = setup();
    let mut broker = MemoryBroker::default();
    let prepared = worker.prepare(&text, &mut broker).unwrap();
    let before = prepared_bytes(&directory, &prepared.request_hash);
    provider.0.lock().unwrap().0["topics"][0]["incarnation"] = json!("recreated-topic");
    for error in [
        worker.prepare(&text, &mut broker).unwrap_err(),
        worker
            .activate(&prepared.request_hash, &mut broker)
            .unwrap_err(),
    ] {
        let refusal: Value = serde_json::from_str(&error).unwrap();
        assert_eq!(refusal["code"], "deployment.incarnation");
        assert_eq!(refusal["node"], "decode");
    }
    assert_eq!(prepared_bytes(&directory, &prepared.request_hash), before);
    assert!(!directory
        .0
        .join(&prepared.request_hash)
        .join("completed.json")
        .exists());
    assert_eq!((broker.reads, broker.publishes, broker.captures), (1, 0, 1));
}

#[test]
fn stale_facts_block_recovery_before_journal_reconciliation() {
    let (directory, mut worker, provider, text) = setup();
    let mut broker = MemoryBroker::default();
    let prepared = worker.prepare(&text, &mut broker).unwrap();
    worker
        .activate(&prepared.request_hash, &mut broker)
        .unwrap();
    let mut journal: Value =
        serde_json::from_slice(&prepared_bytes(&directory, &prepared.request_hash)).unwrap();
    journal["payload"]["phase"] = json!("publishing");
    journal["checksum"] = json!(tsx_contracts::sha256_bytes(
        &serde_json::to_vec(&journal["payload"]).unwrap()
    ));
    fs::write(
        directory
            .0
            .join(&prepared.request_hash)
            .join("prepared.json"),
        journal.to_string(),
    )
    .unwrap();
    let before = prepared_bytes(&directory, &prepared.request_hash);
    provider.0.lock().unwrap().1 = "201".into();
    let refusal: Value = serde_json::from_str(
        &worker
            .activate(&prepared.request_hash, &mut broker)
            .unwrap_err(),
    )
    .unwrap();
    assert_eq!(refusal["code"], "deployment.freshness");
    assert_eq!(prepared_bytes(&directory, &prepared.request_hash), before);
    assert_eq!((broker.reads, broker.publishes, broker.captures), (1, 1, 1));
}

#[test]
fn physical_profile_refuses_continuation_context_and_smaller_retention_bound() {
    let (directory, mut worker, _, text) = setup();
    let wrapper: Value = serde_json::from_str(&text).unwrap();
    let mut request: Value =
        serde_json::from_str(wrapper["requestText"].as_str().unwrap()).unwrap();
    let plan = wrapper["physicalText"].as_str().unwrap();
    let mut broker = MemoryBroker::default();
    request["schema"] = json!("opentsx.local-increment.v1");
    let refusal: Value = serde_json::from_str(
        &worker
            .prepare(&physical::wrapper(plan, &request.to_string()), &mut broker)
            .unwrap_err(),
    )
    .unwrap();
    assert_eq!(refusal["code"], "deployment.assignment");
    request["schema"] = json!("opentsx.local-bounded-run.v1");
    request["context"] = json!({});
    let refusal: Value = serde_json::from_str(
        &worker
            .prepare(&physical::wrapper(plan, &request.to_string()), &mut broker)
            .unwrap_err(),
    )
    .unwrap();
    assert_eq!(refusal["code"], "deployment.capability");
    request.as_object_mut().unwrap().remove("context");
    request["limits"]["samples"] = json!(10001);
    let refusal: Value = serde_json::from_str(
        &worker
            .prepare(&physical::wrapper(plan, &request.to_string()), &mut broker)
            .unwrap_err(),
    )
    .unwrap();
    assert_eq!(refusal["code"], "deployment.channel");
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}

#[test]
fn physical_http_endpoint_preserves_refusal_and_legacy_endpoint_scope() {
    use tiny_http::{Method, TestRequest};
    let (directory, worker, _, text) = setup();
    drop(worker);
    let mut worker = Worker::open(directory.config()).unwrap();
    let mut broker = MemoryBroker::default();
    for (path, code) in [
        ("/v1/prepare-physical", "deployment.incarnation"),
        ("/v1/prepare", "deployment.version"),
    ] {
        let mut request = TestRequest::new()
            .with_method(Method::Post)
            .with_path(path)
            .with_body(Box::leak(text.clone().into_boxed_str()))
            .into();
        let (status, response) = worker.handle(&mut request, &mut broker);
        assert_eq!(status, 422);
        assert_eq!(response["refusal"]["schema"], "opentsx.plan-refusal.v1");
        assert_eq!(response["refusal"]["code"], code);
        assert_eq!(response["error"], response["refusal"]["message"]);
        assert!(!response.to_string().contains("localhost"));
    }
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}

#[test]
fn unsupported_publication_gateway_refuses_before_preparation() {
    let (directory, mut worker, _, text) = setup();
    let mut broker = MemoryBroker {
        fenced: true,
        ..MemoryBroker::default()
    };
    let refusal: Value =
        serde_json::from_str(&worker.prepare(&text, &mut broker).unwrap_err()).unwrap();
    assert_eq!(refusal["code"], "deployment.capability");
    assert_eq!(refusal["field"], "/capabilities/publicationFencing");
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}

#[test]
fn physical_http_json_refusal_never_echoes_request_text() {
    use tiny_http::{Method, TestRequest};
    let directory = Directory::new();
    let mut worker = Worker::open(directory.config()).unwrap();
    let mut broker = MemoryBroker::default();
    let mut request = TestRequest::new()
        .with_method(Method::Post)
        .with_path("/v1/prepare-physical")
        .with_body("{\"schema\":\"private-endpoint.example\",\"schema\":\"duplicate\"}")
        .into();
    let (status, response) = worker.handle(&mut request, &mut broker);
    assert_eq!(status, 422);
    assert_eq!(response["refusal"]["code"], "deployment.json");
    assert!(!response.to_string().contains("private-endpoint"));
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}

#[test]
fn trusted_snapshot_cannot_replace_actual_worker_capabilities() {
    let (directory, mut worker, provider, text) = setup();
    let wrapper: Value = serde_json::from_str(&text).unwrap();
    let mut plan: Value = serde_json::from_str(wrapper["physicalText"].as_str().unwrap()).unwrap();
    let mut report: Value =
        serde_json::from_str(plan["capabilities"][0]["reportText"].as_str().unwrap()).unwrap();
    report["limits"]["series"] = json!(65);
    plan["capabilities"][0]["reportText"] = json!(report.to_string());
    plan["capabilities"][0]["digest"] = json!(tsx_contracts::sha256(&report.to_string()));
    plan["planHash"] = json!(contracts::plan_hash(&plan).unwrap());
    provider.0.lock().unwrap().0["capabilities"] = plan["capabilities"].clone();
    let text = physical::wrapper(&plan.to_string(), wrapper["requestText"].as_str().unwrap());
    let mut broker = MemoryBroker::default();
    let refusal: Value =
        serde_json::from_str(&worker.prepare(&text, &mut broker).unwrap_err()).unwrap();
    assert_eq!(refusal["code"], "deployment.capability");
    assert_eq!(refusal["field"], "/capabilities");
    assert_eq!((broker.reads, broker.publishes, broker.captures), (0, 0, 0));
    assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
}
