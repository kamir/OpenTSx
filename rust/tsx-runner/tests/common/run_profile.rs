use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ed25519_dalek::{pkcs8::EncodePublicKey, Signer, SigningKey};
use serde_json::{json, Value};
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

pub const ARTIFACT_DIGEST: &str = concat!(
    "sha256:",
    "aaaaaaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaa"
);
pub const INPUT_TOPIC: &str = "tsx.b1.episodes.v2";
pub const OUTPUT_TOPIC: &str = "tsx.b1.clean.v2";
pub const STATUS_TOPIC: &str = "tsx.b1.dag-status.v1";

pub struct Fixture {
    pub directory: PathBuf,
    pub config_path: PathBuf,
    pub job: String,
    key: SigningKey,
}

impl Fixture {
    pub fn new() -> Self {
        let sequence = NEXT_DIRECTORY.fetch_add(1, Ordering::SeqCst);
        let directory = std::env::temp_dir().join(format!(
            "opentsx-signed-run-{}-{}",
            std::process::id(),
            sequence
        ));
        fs::create_dir(&directory).unwrap();
        let mut seed = [0u8; 32];
        fs::File::open("/dev/urandom")
            .unwrap()
            .read_exact(&mut seed)
            .unwrap();
        let key = SigningKey::from_bytes(&seed);
        seed.fill(0);
        fs::write(
            directory.join("driver-public.pem"),
            key.verifying_key()
                .to_public_key_pem(Default::default())
                .unwrap(),
        )
        .unwrap();
        let fixture = Self {
            config_path: directory.join("run.json"),
            directory,
            job: format!("b1-flow-{}-{sequence}", std::process::id()),
            key,
        };
        fixture.write_config(&fixture.config_document());
        fixture
    }

    pub fn config_document(&self) -> Value {
        json!({
            "schema":"opentsx.rust-run-config.v1","site":"local","node":"worker-1",
            "driverPublicKey":"driver-public.pem",
            "bindings":{"local":{"bootstrap":"127.0.0.1:39093","inputs":[INPUT_TOPIC],
                "outputs":[OUTPUT_TOPIC,STATUS_TOPIC]}},
            "policy":{"domains":["b1"],"catalogs":["tsx-catalog/1"]},
            "checkpoint":self.directory.join("checkpoint.json"),
            "limits":{"records":10000,"payloadBytes":1048576,"inputBytes":16777216,
                "samples":1000000,"outputBytes":16777216,"series":1024,"stateBytes":1048576}
        })
    }

    pub fn write_config(&self, value: &Value) {
        fs::write(&self.config_path, serde_json::to_vec(value).unwrap()).unwrap();
    }

    pub fn plan_document(&self) -> Value {
        let dag = json!({
            "schema":"opentsx.dag.v2","dag":self.job,
            "source":{"kind":"kafscale-broker","topics":[INPUT_TOPIC]},
            "nodes":[{"id":"decode","op":"decode.avro-soe"}],
            "sinks":[{"name":"pass","from":"decode","schema":"opentsx.episode.v2","topic":OUTPUT_TOPIC}]
        }).to_string();
        let assignment = json!({
            "schema":"opentsx.execution-assignment.v1","deployment":self.job,"generation":"1",
            "assignment":"assignment-1","attempt":"attempt-1","fence":"1","fragment":"fragment-1",
            "site":"local","node":"worker-1","runtime":"rust","catalog":"tsx-catalog/1",
            "artifactDigest":ARTIFACT_DIGEST,"specHash":tsx_contracts::sha256(&dag),"sourceDagText":dag,
            "fragmentHash":tsx_contracts::sha256(&dag),"fragmentText":dag,"mode":"continuous",
            "inputs":[{"binding":"local","topic":INPUT_TOPIC,"partitionCount":"2","positions":[
                {"partition":"0","from":"0"},{"partition":"1","from":"0"}]}]
        });
        json!({
            "schema":"opentsx.runner-plan.v1","job":self.job,"planVersion":1,
            "issued":"2026-11-17T00:00:00Z","expires":"2026-11-19T00:00:00Z",
            "site":"local","catalog":"tsx-catalog/1","specHash":assignment["specHash"],
            "assignmentText":assignment.to_string(),
            "inputContracts":[{"binding":"local","topic":INPUT_TOPIC,"fingerprints":["8985f02b48c77195"]}],
            "outputs":[{"sink":"pass","cluster":"site","topic":OUTPUT_TOPIC,"partitionCount":"2",
                "fingerprints":["8985f02b48c77195"]}]
        })
    }

    pub fn sign(&self, value: &Value) -> String {
        self.sign_raw(&serde_json::to_vec(value).unwrap())
    }

    pub fn sign_raw(&self, payload: &[u8]) -> String {
        self.sign_header(payload, "{\"alg\":\"EdDSA\"}")
    }

    pub fn sign_header(&self, payload: &[u8], header: &str) -> String {
        let input = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(header),
            URL_SAFE_NO_PAD.encode(payload)
        );
        let signature = self.key.sign(input.as_bytes());
        format!("{input}.{}", URL_SAFE_NO_PAD.encode(signature.to_bytes()))
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.directory).unwrap();
    }
}
