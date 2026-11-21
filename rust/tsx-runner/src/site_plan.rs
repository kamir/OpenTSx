use crate::admission::{self, Binding, Limits};
use crate::catalog::{Context, Pipeline};
use crate::operators;
use crate::run_plan::{self, Refusal};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ed25519_dalek::{
    pkcs8::{DecodePrivateKey, DecodePublicKey},
    Signer, SigningKey, VerifyingKey,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File};
use std::io::Read;
#[cfg(unix)]
use std::os::unix::fs::{DirBuilderExt, MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};

pub const STATE_ABI: &str = "opentsx.rust.site-state.v1";
pub const CONFIG_BYTES: usize = 65_536;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::federation::tests::Fixture;
    use ed25519_dalek::pkcs8::EncodePublicKey;

    #[test]
    fn site_configuration_rejects_a_shared_driver_and_site_signing_key() {
        let fixture = Fixture::new();
        fs::write(
            fixture.directory.join("driver.pem"),
            fixture
                .config
                .site_key
                .verifying_key()
                .to_public_key_pem(Default::default())
                .unwrap(),
        )
        .unwrap();
        let error = SiteConfig::load(&fixture.directory.join("config.json"))
            .err()
            .expect("shared signing roles must be rejected");
        assert_eq!(
            error.message,
            "driver and site signing keys must be distinct"
        );
    }

    #[test]
    fn private_keys_and_state_reject_public_permissions_and_links() {
        for name in ["site.pem", "client.key", "state"] {
            let fixture = Fixture::new();
            let path = fixture.directory.join(name);
            fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
            assert!(SiteConfig::load(&fixture.directory.join("config.json")).is_err());
        }
        let fixture = Fixture::new();
        let path = fixture.directory.join("site.pem");
        fs::rename(&path, fixture.directory.join("original.pem")).unwrap();
        std::os::unix::fs::symlink("original.pem", path).unwrap();
        assert!(SiteConfig::load(&fixture.directory.join("config.json")).is_err());
    }

    #[test]
    fn configuration_identity_binds_policy_and_tls_material() {
        let fixture = Fixture::new();
        fs::write(fixture.directory.join("ca.crt"), b"replacement public CA").unwrap();
        assert!(fixture.config.check_tls_pins().is_err());
        let replacement = SiteConfig::load(&fixture.directory.join("config.json")).unwrap();
        assert_ne!(fixture.config.identity_hash(), replacement.identity_hash());
        assert!(fixture.plan.assert_config(&replacement).is_err());
    }

    #[test]
    fn profile_selects_orbit_status_delivery_and_binds_the_configuration_identity() {
        let fixture = Fixture::new();
        assert!(!fixture.config.orbit());
        let path = fixture.directory.join("config.json");
        let original: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        for (profile, orbit) in [("ground", false), ("orbit", true)] {
            let mut value = original.clone();
            value["profile"] = json!(profile);
            fs::write(&path, value.to_string()).unwrap();
            let config = SiteConfig::load(&path).unwrap();
            assert_eq!(config.orbit(), orbit);
            assert_ne!(config.identity_hash(), fixture.config.identity_hash());
        }
        for (profile, message) in [
            (json!(null), "site configuration profile is invalid"),
            (json!("Orbit"), "site configuration profile is invalid"),
            (json!(1), "site configuration fields are invalid"),
        ] {
            let mut value = original.clone();
            value["profile"] = profile;
            fs::write(&path, value.to_string()).unwrap();
            assert_eq!(SiteConfig::load(&path).err().unwrap().message, message);
        }
    }

    /// D-T21: a signed site plan whose assignment declares outputs is refused until runtimes write them.
    #[test]
    fn a_site_plan_with_assignment_outputs_is_refused() {
        let fixture = Fixture::new();
        let mut document = fixture.plan.document().clone();
        let mut assignment: Value =
            serde_json::from_str(document["assignmentText"].as_str().unwrap()).unwrap();
        assignment["outputs"] = json!([{"binding":"site","topic":"tsx.b3test.repartition.flow.vector.v2",
            "partitionCount":"1","port":"out","kind":"repartition"}]);
        document["assignmentText"] = json!(assignment.to_string());
        let error = BoundedPlan::verify(
            &fixture.sign_document(&document),
            &fixture.config,
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )
        .err()
        .expect("refused");
        assert!(
            format!("{error:?}").contains("not executed by this runtime yet"),
            "{error:?}"
        );
    }

    /// TASK-020 U01 membership: a node belongs to one site, so expected membership may not list it for two sites.
    #[test]
    fn expected_membership_refuses_one_node_for_two_sites() {
        let fixture = Fixture::new();
        let artifact = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let member = |node: &str| {
            let mut document = fixture.plan.document().clone();
            let mut other = document["federation"]["expectedAssignments"][0].clone();
            other["assignment"] = json!("a2");
            other["site"] = json!("s2");
            other["node"] = json!(node);
            document["federation"]["expectedAssignments"]
                .as_array_mut()
                .unwrap()
                .push(other);
            fixture.sign_document(&document)
        };
        assert!(BoundedPlan::verify(&member("node-2"), &fixture.config, artifact).is_ok());
        let own = fixture.plan.document()["federation"]["expectedAssignments"][0]["node"]
            .as_str()
            .unwrap()
            .to_string();
        assert!(BoundedPlan::verify(&member(&own), &fixture.config, artifact).is_err());
    }

    #[test]
    fn signed_statistics_require_status_permission_in_the_local_policy() {
        let fixture = Fixture::new();
        let path = fixture.directory.join("config.json");
        let mut value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        value["policy"]["outputs"]["site"] = json!(["aggregates"]);
        fs::write(&path, value.to_string()).unwrap();
        let config = SiteConfig::load(&path).unwrap();
        let signed = fixture.sign_document(fixture.plan.document());
        assert!(BoundedPlan::verify(
            &signed,
            &config,
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )
        .is_err());
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Driver {
    url: String,
    ca: PathBuf,
    certificate: PathBuf,
    private_key: PathBuf,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Configuration {
    schema: String,
    site: String,
    node: String,
    driver: Driver,
    driver_public_key: PathBuf,
    site_private_key: PathBuf,
    qualification: PathBuf,
    jobs: Vec<String>,
    bindings: BTreeMap<String, Binding>,
    policy: Value,
    state: PathBuf,
    limits: Limits,
    profile: Option<String>,
}

pub struct SiteConfig {
    site: String,
    node: String,
    driver: Driver,
    driver_key: VerifyingKey,
    site_key: SigningKey,
    jobs: Vec<String>,
    bindings: BTreeMap<String, Binding>,
    policy: Value,
    directory: PathBuf,
    limits: Limits,
    identity_hash: String,
    tls_pins: [String; 3],
    qualification: crate::site_qualification::Qualification,
    orbit: bool,
}

impl SiteConfig {
    pub fn load(path: &Path) -> Result<Self, Refusal> {
        let bytes = read_file(path, CONFIG_BYTES, false)?;
        let text =
            std::str::from_utf8(&bytes).map_err(|_| invalid("site configuration is not UTF-8"))?;
        let mut value = tsx_contracts::parse_json(text, CONFIG_BYTES)
            .map_err(|_| invalid("site configuration JSON is invalid"))?;
        let mut document: Configuration = serde_json::from_value(value.clone())
            .map_err(|_| invalid("site configuration fields are invalid"))?;
        if document.schema != "opentsx.rust-site-config.v1" {
            return Err(invalid("site configuration schema is invalid"));
        }
        if !identifier(&document.site)
            || !identifier(&document.node)
            || document.jobs.is_empty()
            || document.jobs.len() > 128
            || document.jobs.iter().any(|job| !identifier(job))
            || document.jobs.iter().collect::<BTreeSet<_>>().len() != document.jobs.len()
        {
            return Err(invalid(
                "site configuration identity or job inventory is invalid",
            ));
        }
        admission::validate_run_limits(&document.limits).map_err(invalid)?;
        let orbit = match document.profile.as_deref() {
            None if value.get("profile").is_none() => false,
            Some("ground") => false,
            Some("orbit") => true,
            _ => return Err(invalid("site configuration profile is invalid")),
        };
        if document.bindings.len() > 16
            || !document.bindings.contains_key("site")
            || !document.bindings.contains_key("signal")
        {
            return Err(invalid(
                "site configuration requires site and signal bindings",
            ));
        }
        for (name, binding) in &document.bindings {
            if !identifier(name)
                || binding.inputs.len() > 128
                || binding.outputs.len() > 128
                || !unique(&binding.inputs)
                || !unique(&binding.outputs)
                || binding
                    .inputs
                    .iter()
                    .chain(&binding.outputs)
                    .any(|topic| !tsx_contracts::valid_topic(topic))
            {
                return Err(invalid("site configuration topic inventory is invalid"));
            }
            admission::validate_bootstrap(&binding.bootstrap).map_err(invalid)?;
        }
        if document.bindings["site"].bootstrap == document.bindings["signal"].bootstrap {
            return Err(invalid(
                "site and signal bindings must use distinct endpoints",
            ));
        }
        fields(
            &document.policy,
            &["domains", "inputs", "outputs", "catalogs"],
        )?;
        strings(&document.policy["domains"], true)?;
        strings(&document.policy["catalogs"], true)?;
        if document.policy["domains"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| !identifier(v.as_str().unwrap()))
            || !document.policy["inputs"].is_object()
            || !document.policy["outputs"].is_object()
        {
            return Err(invalid("site configuration policy is invalid"));
        }
        for (binding, topics) in document.policy["inputs"].as_object().unwrap() {
            let configured = document
                .bindings
                .get(binding)
                .ok_or_else(|| invalid("input policy names an unknown binding"))?;
            let topics = strings(topics, false)?;
            if topics
                .iter()
                .any(|topic| !configured.inputs.contains(topic))
            {
                return Err(invalid("input policy exceeds its local binding"));
            }
        }
        for (cluster, kinds) in document.policy["outputs"].as_object().unwrap() {
            if !["site", "signal"].contains(&cluster.as_str())
                || strings(kinds, false)?
                    .iter()
                    .any(|kind| !["aggregates", "signals", "dag-status"].contains(&kind.as_str()))
            {
                return Err(invalid(
                    "output policy contains a forbidden cluster or kind",
                ));
            }
        }
        let canonical =
            fs::canonicalize(path).map_err(|_| invalid("cannot resolve site configuration"))?;
        let base = canonical
            .parent()
            .ok_or_else(|| invalid("site configuration has no parent"))?;
        let driver_key_path = resolve_file(base, &document.driver_public_key)?;
        let site_key_path = resolve_file(base, &document.site_private_key)?;
        let driver_key_bytes = read_file(&driver_key_path, 16_384, false)?;
        let driver_pem = std::str::from_utf8(&driver_key_bytes)
            .map_err(|_| invalid("driver public key is not UTF-8"))?;
        let driver_key = VerifyingKey::from_public_key_pem(driver_pem)
            .map_err(|_| invalid("driver public key is not Ed25519 SPKI PEM"))?;
        let site_key_bytes = read_file(&site_key_path, 16_384, true)?;
        let site_pem = std::str::from_utf8(&site_key_bytes)
            .map_err(|_| invalid("site signing key is not UTF-8"))?;
        let site_key = SigningKey::from_pkcs8_pem(site_pem)
            .map_err(|_| invalid("site signing key is not Ed25519 PKCS8 PEM"))?;
        if driver_key == site_key.verifying_key() {
            return Err(invalid("driver and site signing keys must be distinct"));
        }
        document.driver.ca = resolve_file(base, &document.driver.ca)?;
        document.driver.certificate = resolve_file(base, &document.driver.certificate)?;
        document.driver.private_key = resolve_file(base, &document.driver.private_key)?;
        let tls_pins = [
            tsx_contracts::sha256_bytes(&read_file(&document.driver.ca, CONFIG_BYTES, false)?),
            tsx_contracts::sha256_bytes(&read_file(
                &document.driver.certificate,
                CONFIG_BYTES,
                false,
            )?),
            tsx_contracts::sha256_bytes(&read_file(
                &document.driver.private_key,
                CONFIG_BYTES,
                true,
            )?),
        ];
        let qualification_path = resolve_file(base, &document.qualification)?;
        let qualification_bytes = read_file(&qualification_path, 2_097_152, false)?;
        let qualification_text = std::str::from_utf8(&qualification_bytes)
            .map_err(|_| invalid("qualification is not UTF-8"))?;
        let qualification_value = tsx_contracts::parse_json(qualification_text, 2_097_152)
            .map_err(|_| invalid("qualification JSON is invalid"))?;
        let qualification = crate::site_qualification::Qualification::parse(
            qualification_value,
            &document.bindings["site"].bootstrap,
        )?;
        let directory = resolve_state(base, &document.state)?;
        value["state"] = json!(path_text(&directory)?);
        value["driverPublicKey"] = json!(path_text(&driver_key_path)?);
        value["sitePrivateKey"] = json!(path_text(&site_key_path)?);
        value["qualification"] = json!(path_text(&qualification_path)?);
        value["driver"]["ca"] = json!(path_text(&document.driver.ca)?);
        value["driver"]["certificate"] = json!(path_text(&document.driver.certificate)?);
        value["driver"]["privateKey"] = json!(path_text(&document.driver.private_key)?);
        value["pins"] = json!({"driver":tsx_contracts::sha256_bytes(driver_key.as_bytes()),"site":tsx_contracts::sha256_bytes(site_key.verifying_key().as_bytes()),"tls":tls_pins});
        value["pins"]["qualification"] = qualification.identity().clone();
        let identity_hash = tsx_contracts::sha256_bytes(
            &serde_json::to_vec(&value)
                .map_err(|_| invalid("cannot encode site configuration identity"))?,
        );
        Ok(Self {
            site: document.site,
            node: document.node,
            driver: document.driver,
            driver_key,
            site_key,
            jobs: document.jobs,
            bindings: document.bindings,
            policy: document.policy,
            directory,
            limits: document.limits,
            identity_hash,
            tls_pins,
            qualification,
            orbit,
        })
    }

    pub fn site(&self) -> &str {
        &self.site
    }
    pub fn node(&self) -> &str {
        &self.node
    }
    pub fn jobs(&self) -> &[String] {
        &self.jobs
    }
    pub fn policy(&self) -> &Value {
        &self.policy
    }
    pub fn allowed_job(&self, job: &str) -> bool {
        self.jobs.iter().any(|v| v == job)
    }
    pub fn binding(&self, name: &str) -> Result<&Binding, Refusal> {
        self.bindings.get(name).ok_or_else(policy)
    }
    pub fn limits(&self) -> &Limits {
        &self.limits
    }
    pub fn orbit(&self) -> bool {
        self.orbit
    }
    pub fn directory(&self) -> &Path {
        &self.directory
    }
    pub fn identity_hash(&self) -> &str {
        &self.identity_hash
    }
    pub fn driver_endpoint(&self) -> &str {
        &self.driver.url
    }
    pub fn ca(&self) -> &Path {
        &self.driver.ca
    }
    pub fn certificate(&self) -> &Path {
        &self.driver.certificate
    }
    pub fn private_key(&self) -> &Path {
        &self.driver.private_key
    }
    pub fn qualification(&self) -> &Value {
        self.qualification.value()
    }
    pub fn qualification_at(&self, now: i64, artifact: &str) -> Result<(), Refusal> {
        self.qualification.check_artifact(artifact)?;
        if now < self.qualification.issued() || now >= self.qualification.expires() {
            return Err(Refusal::new(
                "plan.expired",
                "local runtime qualification is expired or not yet active",
            ));
        }
        Ok(())
    }

    pub fn check_tls_pins(&self) -> Result<(), Refusal> {
        let current = [
            tsx_contracts::sha256_bytes(&read_file(self.ca(), CONFIG_BYTES, false)?),
            tsx_contracts::sha256_bytes(&read_file(self.certificate(), CONFIG_BYTES, false)?),
            tsx_contracts::sha256_bytes(&read_file(self.private_key(), CONFIG_BYTES, true)?),
        ];
        if current != self.tls_pins {
            return Err(policy());
        }
        Ok(())
    }

    pub fn sign_report(&self, report: &Value) -> Result<String, Refusal> {
        let bytes = serde_json::to_vec(report).map_err(|_| invalid("cannot encode site report"))?;
        if bytes.len() > run_plan::MAX_PAYLOAD_BYTES {
            return Err(invalid("site report exceeds its byte limit"));
        }
        let signing = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode("{\"alg\":\"EdDSA\"}"),
            URL_SAFE_NO_PAD.encode(bytes)
        );
        let signature = self.site_key.sign(signing.as_bytes());
        Ok(format!(
            "{signing}.{}",
            URL_SAFE_NO_PAD.encode(signature.to_bytes())
        ))
    }
}

pub struct BoundedPlan {
    document: Value,
    assignment: Value,
    fragment: Value,
    command_hash: String,
    config_hash: String,
    issued_micros: i64,
    expires_micros: i64,
    local_binding: String,
    qualification_issued: i64,
    qualification_expires: i64,
}

impl BoundedPlan {
    pub fn verify(compact: &str, config: &SiteConfig, artifact: &str) -> Result<Self, Refusal> {
        config.qualification.check_artifact(artifact)?;
        let bytes = run_plan::verify_jws(compact, &config.driver_key)?;
        let text =
            std::str::from_utf8(&bytes).map_err(|_| invalid("signed plan payload is not UTF-8"))?;
        let document = tsx_contracts::parse_json(text, run_plan::MAX_PAYLOAD_BYTES)
            .map_err(|_| invalid("signed plan payload is invalid JSON"))?;
        fields(
            &document,
            &[
                "schema",
                "job",
                "planVersion",
                "issued",
                "expires",
                "site",
                "catalog",
                "specHash",
                "assignmentText",
                "inputContracts",
                "outputs",
                "federation",
            ],
        )?;
        if document["schema"] != "opentsx.runner-plan.v1"
            || !identifier(text_field(&document, "job")?)
        {
            return Err(invalid("runner plan identity is invalid"));
        }
        let version = document["planVersion"]
            .as_i64()
            .filter(|v| (1..=i32::MAX as i64).contains(v))
            .ok_or_else(|| invalid("planVersion must be a positive int32"))?;
        if document["site"] != config.site() {
            return Err(Refusal::new(
                "plan.site",
                "plan site or node is not this runtime",
            ));
        }
        if !config.allowed_job(text_field(&document, "job")?) {
            return Err(policy());
        }
        if document["catalog"] != "tsx-catalog/1"
            || !strings(&config.policy["catalogs"], true)?.contains(&"tsx-catalog/1".into())
        {
            return Err(Refusal::new(
                "plan.catalog",
                "catalog is not available at this site",
            ));
        }
        let issued_micros = instant(&document, "issued")?;
        let expires_micros = instant(&document, "expires")?;
        if expires_micros <= issued_micros {
            return Err(invalid("expires must follow issued"));
        }
        let assignment_text = text_field(&document, "assignmentText")?;
        tsx_contracts::validate(assignment_text).map_err(invalid)?;
        let assignment = tsx_contracts::parse_json(assignment_text, 65_536).map_err(invalid)?;
        if assignment.get("outputs").is_some() {
            return Err(invalid(
                "assignment outputs are not executed by this runtime yet (D-T21)",
            ));
        }
        if assignment["site"] != config.site() || assignment["node"] != config.node() {
            return Err(Refusal::new(
                "plan.site",
                "plan site or node is not this runtime",
            ));
        }
        if assignment["runtime"] != "rust" || assignment["mode"] != "bounded" {
            return Err(Refusal::new(
                "plan.catalog",
                "the B3 profile requires bounded Rust statistics",
            ));
        }
        for (left, right) in [
            ("deployment", "job"),
            ("site", "site"),
            ("catalog", "catalog"),
            ("specHash", "specHash"),
        ] {
            if assignment[left] != document[right] {
                return Err(invalid("runner and assignment identities differ"));
            }
        }
        let generation = version.to_string();
        if assignment["generation"].as_str() != Some(generation.as_str())
            || assignment["artifactDigest"] != artifact
        {
            return Err(invalid(
                "assignment version or artifact differs from this runtime",
            ));
        }
        let inputs = assignment["inputs"]
            .as_array()
            .ok_or_else(|| invalid("input inventory is invalid"))?;
        let outputs = document["outputs"]
            .as_array()
            .ok_or_else(|| invalid("output inventory is invalid"))?;
        let contracts = document["inputContracts"]
            .as_array()
            .ok_or_else(|| invalid("input contracts are invalid"))?;
        if inputs.len() != 1 || outputs.len() != 1 || contracts.len() != 1 {
            return Err(invalid(
                "bounded Rust statistics require one whole input and one output",
            ));
        }
        let input = &inputs[0];
        let output = &outputs[0];
        let contract = &contracts[0];
        fields(contract, &["binding", "topic", "fingerprints"])?;
        fields(
            output,
            &["sink", "cluster", "topic", "partitionCount", "fingerprints"],
        )?;
        let local_binding = text_field(input, "binding")?.to_string();
        let input_topic = text_field(input, "topic")?;
        let source = config.binding(&local_binding)?;
        let site = config.binding("site")?;
        let signal = config.binding("signal")?;
        if source.bootstrap != site.bootstrap
            || !source.inputs.contains(&input_topic.to_string())
            || !strings(&config.policy["inputs"][&local_binding], true)?
                .contains(&input_topic.to_string())
        {
            return Err(policy());
        }
        if contract["binding"] != input["binding"]
            || contract["topic"] != input["topic"]
            || strings(&contract["fingerprints"], true)?
                != vec![crate::wire::FINGERPRINT.to_string()]
        {
            return Err(invalid(
                "input contract differs from the Episode assignment",
            ));
        }
        let output_topic = text_field(output, "topic")?;
        if output["sink"].as_str() != Some(output_topic) {
            return Err(invalid("statistics sink identity differs from its topic"));
        }
        if output["cluster"] != "site"
            || !site.outputs.contains(&output_topic.to_string())
            || !signal.outputs.contains(&output_topic.to_string())
            || input_topic == output_topic
            || !allowed_topic(input_topic, config)
            || !allowed_topic(output_topic, config)
        {
            return Err(policy());
        }
        let domain = input_topic.split('.').nth(1).ok_or_else(policy)?;
        if output_topic != format!("tsx.{domain}.aggregates.v2")
            || !strings(&config.policy["outputs"]["site"], true)?.contains(&"aggregates".into())
            || !strings(&config.policy["outputs"]["site"], true)?.contains(&"dag-status".into())
            || !strings(&config.policy["outputs"]["signal"], true)?.contains(&"aggregates".into())
            || !site
                .outputs
                .contains(&format!("tsx.{domain}.dag-status.v1"))
        {
            return Err(policy());
        }
        if strings(&output["fingerprints"], true)?
            != vec![crate::stats::STATS_FINGERPRINT.to_string()]
        {
            return Err(invalid("statistics output fingerprint differs"));
        }
        let partitions = admission::canonical_i64(
            text_field(output, "partitionCount")?,
            "output partitionCount",
        )
        .map_err(invalid)?;
        if !(1..=128).contains(&partitions) {
            return Err(invalid("output partition count is invalid"));
        }
        let fragment = tsx_contracts::parse_json(text_field(&assignment, "fragmentText")?, 32_768)
            .map_err(invalid)?;
        let nodes = fragment["nodes"]
            .as_array()
            .ok_or_else(|| invalid("nodes must be an array"))?;
        for node in nodes {
            let op = text_field(node, "op")?;
            if op != "decode.avro-soe" && op != "stats.welford-ddsketch" {
                return Err(Refusal::new(
                    "plan.catalog",
                    format!("operator {op} is not admitted by the bounded statistics profile"),
                ));
            }
            if node.get("version").is_some_and(|v| v.as_i64() != Some(1)) {
                return Err(Refusal::new(
                    "plan.catalog",
                    "operator version is not available",
                ));
            }
            if op == "stats.welford-ddsketch" {
                if node["params"]
                    .get("alpha")
                    .is_some_and(|v| v.as_f64() != Some(0.01))
                {
                    return Err(invalid("federated statistics require alpha 0.01"));
                }
                if node["params"]["windows"]
                    .as_array()
                    .is_some_and(|windows| windows.iter().any(|v| v == "GLOBAL"))
                {
                    return Err(invalid(format!(
                        "node {}: a federated statistic needs a tumbling window, not GLOBAL",
                        text_field(node, "id")?
                    )));
                }
            }
        }
        let sinks = fragment["sinks"]
            .as_array()
            .ok_or_else(|| invalid("sinks must be a non-empty array"))?;
        if sinks.len() != 1
            || sinks[0]["topic"] != output["topic"]
            || sinks[0]["schema"] != "org.opentsx.model.v2.SeriesStats"
            || !nodes.iter().any(|node| {
                node["id"] == sinks[0]["from"] && node["op"] == "stats.welford-ddsketch"
            })
        {
            return Err(invalid(
                "bounded statistics require a matching terminal statistics sink",
            ));
        }
        let pipeline = Pipeline::compile(
            &fragment,
            text_field(&assignment, "specHash")?,
            issued_micros,
            operators::Limits {
                max_series: config.limits.series,
                max_samples: config.limits.samples,
                max_output_samples: config.limits.samples,
                max_state_bytes: config.limits.state_bytes,
            },
            Some(&Context::default()),
            config.site(),
        )
        .map_err(invalid)?;
        if pipeline.source_topic() != input_topic || pipeline.sink_topic() != Some(output_topic) {
            return Err(invalid(
                "fragment source or sink differs from the assignment",
            ));
        }
        let federation = &document["federation"];
        fields(
            federation,
            &[
                "route",
                "expectedAssignments",
                "scopes",
                "groupBy",
                "maxDelay",
            ],
        )?;
        if federation["route"] != "signal-cluster" {
            return Err(Refusal::new(
                "plan.catalog",
                "the bounded Rust profile requires signal-cluster delivery",
            ));
        }
        strings(&federation["groupBy"], false)?;
        let delay_text = text_field(federation, "maxDelay")?;
        let delay = if delay_text == "PT0S" {
            0
        } else {
            crate::stats::parse_duration(delay_text).map_err(invalid)?
        };
        if delay < 0 {
            return Err(invalid("maxDelay must be nonnegative"));
        }
        let members = federation["expectedAssignments"]
            .as_array()
            .filter(|v| !v.is_empty() && v.len() <= 128)
            .ok_or_else(|| invalid("expected assignment inventory is invalid"))?;
        let mut own = false;
        let mut assignments = BTreeSet::new();
        let mut sites = BTreeSet::new();
        let mut nodes = BTreeSet::new();
        for member in members {
            fields(
                member,
                &[
                    "assignment",
                    "site",
                    "node",
                    "generation",
                    "fence",
                    "fragmentHash",
                ],
            )?;
            for field in ["assignment", "site", "node"] {
                if !identifier(text_field(member, field)?) {
                    return Err(invalid("expected assignment identity is invalid"));
                }
            }
            if admission::canonical_i64(text_field(member, "fence")?, "member fence")
                .map_err(invalid)?
                <= 0
                || !hex(text_field(member, "fragmentHash")?, 64)
            {
                return Err(invalid("expected assignment fence or hash is invalid"));
            }
            if !assignments.insert(text_field(member, "assignment")?)
                || !sites.insert(text_field(member, "site")?)
                || !nodes.insert(text_field(member, "node")?)
                || member["generation"] != assignment["generation"]
            {
                return Err(invalid("expected assignment inventory conflicts"));
            }
            if member["assignment"] == assignment["assignment"] {
                if ["site", "node", "generation", "fence", "fragmentHash"]
                    .iter()
                    .any(|field| member[*field] != assignment[*field])
                {
                    return Err(invalid("own expected assignment differs"));
                }
                own = true;
            }
        }
        if !own
            || !federation["scopes"].is_array()
            || federation["scopes"].as_array().unwrap().is_empty()
        {
            return Err(invalid("federation membership or scopes are incomplete"));
        }
        let scopes = federation["scopes"].as_array().unwrap();
        if scopes.len() > 1024 {
            return Err(invalid("aggregate scopes exceed 1024 entries"));
        }
        let mut scope_ids = BTreeSet::new();
        for scope in scopes {
            fields(
                scope,
                &["groupId", "windowType", "startMicros", "endMicros"],
            )?;
            let group = text_field(scope, "groupId")?;
            let window = text_field(scope, "windowType")?;
            let duration = crate::stats::parse_duration(window).map_err(invalid)?;
            let from = admission::canonical_i64(text_field(scope, "startMicros")?, "scope start")
                .map_err(invalid)?;
            let to = admission::canonical_i64(text_field(scope, "endMicros")?, "scope end")
                .map_err(invalid)?;
            if duration <= 0
                || !hex(group, 16)
                || window != canonical_duration(duration)
                || to.checked_sub(from) != Some(duration)
                || from.rem_euclid(duration) != 0
                || !scope_ids.insert((group, window, from))
            {
                return Err(invalid("aggregate scope identity or window is invalid"));
            }
        }
        Ok(Self {
            document,
            assignment,
            fragment,
            command_hash: tsx_contracts::sha256(compact),
            config_hash: config.identity_hash().into(),
            issued_micros,
            expires_micros,
            local_binding,
            qualification_issued: config.qualification.issued(),
            qualification_expires: config.qualification.expires(),
        })
    }

    pub fn document(&self) -> &Value {
        &self.document
    }
    pub fn assignment(&self) -> &Value {
        &self.assignment
    }
    pub fn fragment(&self) -> &Value {
        &self.fragment
    }
    pub fn job(&self) -> &str {
        self.document["job"].as_str().unwrap()
    }
    pub fn version(&self) -> i32 {
        self.document["planVersion"].as_i64().unwrap() as i32
    }
    pub fn command_hash(&self) -> &str {
        &self.command_hash
    }
    pub fn issued_micros(&self) -> i64 {
        self.issued_micros
    }
    pub fn created_at_micros(&self) -> i64 {
        self.issued_micros
    }
    pub fn local_binding(&self) -> &str {
        &self.local_binding
    }
    pub fn signal_binding(&self) -> &str {
        "signal"
    }
    pub fn assert_config(&self, config: &SiteConfig) -> Result<(), Refusal> {
        if self.config_hash != config.identity_hash() {
            Err(policy())
        } else {
            Ok(())
        }
    }
    pub fn activate_at(&self, now_micros: i64) -> Result<(), Refusal> {
        if now_micros < self.qualification_issued || now_micros >= self.qualification_expires {
            return Err(Refusal::new(
                "plan.expired",
                "local runtime qualification is expired or not yet active",
            ));
        }
        if now_micros < self.issued_micros || now_micros >= self.expires_micros {
            Err(Refusal::new(
                "plan.expired",
                "plan is expired or not yet active",
            ))
        } else {
            Ok(())
        }
    }
    pub fn checkpoint_identity(&self) -> Value {
        let mut result = json!({"schema":"opentsx.site-continuation.v1","job":self.job(),"planVersion":self.version(),"stateAbi":STATE_ABI,"commandHash":self.command_hash()});
        for field in [
            "catalog",
            "specHash",
            "fragmentHash",
            "assignment",
            "generation",
            "fence",
            "site",
            "node",
            "artifactDigest",
            "inputs",
        ] {
            result[field] = self.assignment[field].clone();
        }
        result
    }
}

pub(crate) fn fields(value: &Value, names: &[&str]) -> Result<(), Refusal> {
    let object = value
        .as_object()
        .ok_or_else(|| invalid("expected a JSON object"))?;
    if object.len() != names.len() || names.iter().any(|name| !object.contains_key(*name)) {
        return Err(invalid("object fields are invalid"));
    }
    Ok(())
}

pub(crate) fn text_field<'a>(value: &'a Value, name: &str) -> Result<&'a str, Refusal> {
    value[name]
        .as_str()
        .filter(|v| !v.is_empty())
        .ok_or_else(|| invalid(format!("{name} must be a non-empty string")))
}

fn strings(value: &Value, nonempty: bool) -> Result<Vec<String>, Refusal> {
    let values = value
        .as_array()
        .ok_or_else(|| invalid("expected a string inventory"))?;
    let mut result = Vec::new();
    let mut seen = BTreeSet::new();
    for value in values {
        let text = value
            .as_str()
            .filter(|v| !v.is_empty())
            .ok_or_else(|| invalid("expected a string inventory"))?;
        if !seen.insert(text) {
            return Err(invalid("string inventory contains duplicates"));
        }
        result.push(text.to_string());
    }
    if nonempty && result.is_empty() {
        return Err(invalid("string inventory is empty"));
    }
    Ok(result)
}

fn unique(values: &[String]) -> bool {
    values.iter().collect::<BTreeSet<_>>().len() == values.len()
}
fn hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn canonical_duration(micros: i64) -> String {
    let hours = micros / 3_600_000_000;
    let minutes = (micros % 3_600_000_000) / 60_000_000;
    let rest = micros % 60_000_000;
    let mut out = "PT".to_string();
    if hours != 0 {
        out.push_str(&format!("{hours}H"));
    }
    if minutes != 0 {
        out.push_str(&format!("{minutes}M"));
    }
    if rest != 0 || micros == 0 {
        let seconds = rest / 1_000_000;
        let fraction = rest % 1_000_000;
        if fraction == 0 {
            out.push_str(&format!("{seconds}S"));
        } else {
            out.push_str(&format!(
                "{seconds}.{}S",
                format!("{fraction:06}").trim_end_matches('0')
            ));
        }
    }
    out
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value
            .bytes()
            .enumerate()
            .all(|(i, b)| b.is_ascii_lowercase() || b.is_ascii_digit() || i > 0 && b == b'-')
}
fn allowed_topic(topic: &str, config: &SiteConfig) -> bool {
    tsx_contracts::valid_topic(topic)
        && topic.split('.').nth(1).is_some_and(|domain| {
            config.policy["domains"]
                .as_array()
                .unwrap()
                .iter()
                .any(|v| v.as_str() == Some(domain))
        })
}
fn invalid(message: impl Into<String>) -> Refusal {
    Refusal::new("plan.invalid", message)
}
fn policy() -> Refusal {
    Refusal::new("plan.policy", "plan violates local site policy")
}

pub(crate) fn instant(value: &Value, name: &str) -> Result<i64, Refusal> {
    let text = text_field(value, name)?;
    if !text.ends_with('Z') {
        return Err(invalid(format!("{name} must be UTC")));
    }
    let time =
        OffsetDateTime::parse(text, &Rfc3339).map_err(|_| invalid(format!("invalid {name}")))?;
    i64::try_from(time.unix_timestamp_nanos() / 1000)
        .map_err(|_| invalid(format!("{name} is outside the supported clock")))
}

fn path_text(path: &Path) -> Result<&str, Refusal> {
    path.to_str()
        .ok_or_else(|| invalid("local path is not UTF-8"))
}
fn resolve_file(base: &Path, path: &Path) -> Result<PathBuf, Refusal> {
    if path.as_os_str().is_empty() {
        return Err(invalid("local file path is empty"));
    }
    let joined = if path.is_absolute() {
        path.to_path_buf()
    } else {
        base.join(path)
    };
    regular_file(&joined, false)?;
    fs::canonicalize(joined).map_err(|_| invalid("cannot resolve local file"))
}

fn read_file(path: &Path, limit: usize, private: bool) -> Result<Vec<u8>, Refusal> {
    regular_file(path, private)?;
    let mut bytes = Vec::new();
    File::open(path)
        .map_err(|_| invalid("cannot open local input"))?
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| invalid("cannot read local input"))?;
    if bytes.len() > limit {
        return Err(invalid("local input exceeds its byte limit"));
    }
    Ok(bytes)
}

fn regular_file(path: &Path, private: bool) -> Result<(), Refusal> {
    let metadata = fs::symlink_metadata(path).map_err(|_| invalid("cannot inspect local input"))?;
    if !metadata.is_file() {
        return Err(policy());
    }
    #[cfg(unix)]
    if metadata.uid() != unsafe { libc::geteuid() }
        || metadata.permissions().mode() & if private { 0o077 } else { 0o022 } != 0
    {
        return Err(policy());
    }
    Ok(())
}

fn resolve_state(base: &Path, path: &Path) -> Result<PathBuf, Refusal> {
    if path.as_os_str().is_empty() {
        return Err(invalid("state directory is empty"));
    }
    let path = if path.is_absolute() {
        path.to_path_buf()
    } else {
        base.join(path)
    };
    match fs::symlink_metadata(&path) {
        Ok(metadata) => {
            if !metadata.is_dir() {
                return Err(policy());
            }
            #[cfg(unix)]
            if metadata.uid() != unsafe { libc::geteuid() }
                || metadata.permissions().mode() & 0o077 != 0
            {
                return Err(policy());
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let mut builder = fs::DirBuilder::new();
            builder.recursive(true);
            #[cfg(unix)]
            builder.mode(0o700);
            builder
                .create(&path)
                .map_err(|_| invalid("cannot create private state directory"))?;
        }
        Err(_) => return Err(invalid("cannot inspect state directory")),
    }
    fs::canonicalize(path).map_err(|_| invalid("cannot resolve state directory"))
}
