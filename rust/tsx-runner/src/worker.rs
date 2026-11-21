use crate::admission::{self, Admitted, Profile};
use crate::broker::Broker;
use crate::checkpoint::{Completion, Receipt};
use crate::operators::{STATE_ABI, SUPPORTED_OPS};
use crate::publication::PublicationGrant;
use crate::runner::{self, Prepared};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::IpAddr;
use std::path::{Component, Path, PathBuf};
use tiny_http::{Header, Method, Request, Response, Server};

pub struct Config {
    pub node: String,
    pub site: String,
    pub bootstrap: String,
    pub artifact_digest: String,
    pub state: PathBuf,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub schema: String,
    pub request_hash: String,
    pub node: String,
    pub site: String,
    pub artifact_digest: String,
    pub state: String,
    pub activation_retryable: bool,
    pub input_topic: String,
    pub input_series: Vec<String>,
    pub output_series: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub receipt: Option<Receipt>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sequence: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub previous_request_hash: Option<Option<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub publication_epoch: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prepared_output_digest: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prepared_output_records: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Journal {
    request_text: String,
    phase: String,
    publication_started: bool,
    prepared: Option<Prepared>,
    error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    publication_grant: Option<PublicationGrant>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    schema: String,
    checksum: String,
    payload: Value,
}

#[derive(Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct IncrementState {
    head: Option<IncrementHead>,
    candidate: Option<Journal>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    publication: Option<PublicationInstallation>,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PublicationInstallation {
    grant: PublicationGrant,
    reset: bool,
    expected_head: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct IncrementHead {
    request_text: String,
    completion: Completion,
    input_owners: BTreeMap<String, i32>,
    input_series: Vec<String>,
    output_series: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    publication_epoch: Option<String>,
}

pub struct Worker {
    config: Config,
    active: Option<String>,
    _lock: File,
    lock_owner_pid: u32,
    controller: Option<IpAddr>,
    profile: Profile,
    admission_facts: Box<dyn crate::physical::AdmissionFacts>,
}

impl Drop for Worker {
    fn drop(&mut self) {
        if self.lock_owner_pid == std::process::id() {
            if let Err(error) = self._lock.unlock() {
                eprintln!("cannot release worker state lock: {error}");
            }
        }
    }
}

impl Worker {
    pub fn open(config: Config) -> Result<Self, String> {
        Self::open_profiled(config, Profile::STANDARD)
    }

    /// Opens the worker with the admission ceilings of `profile`. Journals written under one profile are
    /// read back only under a profile at least as large.
    pub fn open_profiled(config: Config, profile: Profile) -> Result<Self, String> {
        Self::open_with_facts(config, profile, Box::new(crate::physical::UnavailableFacts))
    }

    pub fn open_with_facts(
        config: Config,
        profile: Profile,
        admission_facts: Box<dyn crate::physical::AdmissionFacts>,
    ) -> Result<Self, String> {
        if !identifier(&config.node)
            || !identifier(&config.site)
            || !config
                .artifact_digest
                .strip_prefix("sha256:")
                .is_some_and(hash)
        {
            return Err("invalid worker identity or artifact digest".into());
        }
        admission::validate_bootstrap(&config.bootstrap)?;
        directory(&config.state, true)?;
        private_directory(&config.state)?;
        let lock_path = config.state.join(".worker.lock");
        regular_or_absent(&lock_path)?;
        let lock = private_lock(&lock_path)?;
        lock.try_lock()
            .map_err(|e| format!("worker state is locked or cannot be locked: {e}"))?;
        let mut worker = Self {
            config,
            active: None,
            _lock: lock,
            lock_owner_pid: std::process::id(),
            controller: None,
            profile,
            admission_facts,
        };
        for entry in fs::read_dir(&worker.config.state)
            .map_err(|e| format!("cannot list worker state: {e}"))?
        {
            let entry = entry.map_err(|e| format!("cannot read worker state entry: {e}"))?;
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| "invalid worker state entry")?;
            if name == ".worker.lock" {
                continue;
            }
            if name == "increments" {
                directory(&entry.path(), false)?;
                continue;
            }
            if !hash(&name) {
                return Err("unknown worker state entry".into());
            }
            let (journal, _) = worker.recover(&name)?;
            if busy(&journal) && worker.active.replace(name).is_some() {
                return Err("worker state contains multiple unfinished requests".into());
            }
        }
        let increments = worker.increment_load()?;
        if increments.candidate.as_ref().is_some_and(busy) && worker.active.is_some() {
            return Err("worker state contains multiple unfinished requests".into());
        }
        Ok(worker)
    }

    pub fn capabilities(&self) -> Value {
        let operators = ["decode.avro-soe", "dq.checks", "convert.unit", "resample"];
        let mut catalog_operators = vec!["decode.avro-soe"];
        catalog_operators.extend_from_slice(SUPPORTED_OPS);
        catalog_operators.extend_from_slice(crate::catalog::CATALOG_OPS);
        let mut capabilities = json!({"schema":"opentsx.local-worker.v1","node":self.config.node,
            "site":self.config.site,"artifactDigest":self.config.artifact_digest,
            "version":env!("CARGO_PKG_VERSION"),"catalog":"tsx-catalog/1",
            "requestSchema":"opentsx.local-bounded-run.v1","stateAbi":STATE_ABI,
            "incrementalRequestSchema":"opentsx.local-increment.v1",
            "fingerprint":crate::wire::FINGERPRINT,"operators":operators,
            "profile":self.profile.name,
            "limits":{"records":self.profile.records,"payloadBytes":self.profile.payload_bytes,
                "inputBytes":self.profile.input_bytes,"samples":self.profile.samples,
                "outputBytes":self.profile.output_bytes,"series":self.profile.series,
                "stateBytes":self.profile.state_bytes,"requestBytes":self.profile.request_bytes,
                "partitions":admission::MAX_PARTITIONS,
                "incrementalChains":1,
                "episodeSamples":admission::MAX_EPISODE_SAMPLES}});
        if self.controller.is_some() {
            capabilities["publicationFencing"] = json!(true);
        }
        capabilities["catalogExecution"] = json!({"stateAbi":"opentsx.rust.catalog-state.v1","contextRequiredForExtendedCaps":true,
            "operators":catalog_operators,"limits":{"series":self.profile.context_series,"samples":self.profile.context_samples},"signedRunOperators":["decode.avro-soe"]});
        capabilities
    }

    pub fn prepare(&mut self, text: &str, broker: &mut impl Broker) -> Result<Status, String> {
        let mut run = self.admit(text)?;
        self.verify_physical(text, broker.fenced())?;
        if run.increment.is_some() {
            return self.increment_prepare(text, run, broker);
        }
        if self.increment_load()?.candidate.as_ref().is_some_and(busy) {
            return Err("worker is busy with an unfinished increment".into());
        }
        let request_hash = run.request_hash.clone();
        if self.job_path(&request_hash)?.exists() {
            let (journal, _) = self.load(&request_hash)?;
            if journal.request_text != text {
                return Err("request hash conflicts with persisted bytes".into());
            }
            return self.status(&request_hash);
        }
        if self.active.is_some() {
            return Err("worker is busy with another unfinished request".into());
        }
        let authority = self.increment_load()?.publication;
        let publication_grant =
            broker.capture_grant(&run, authority.as_ref().map(|value| &value.grant))?;
        verify_capture(&run, publication_grant.as_ref(), authority.as_ref())?;
        let prepared = runner::prepare(&mut run, broker);
        let mut journal = Journal {
            request_text: text.into(),
            phase: "failed".into(),
            publication_started: false,
            prepared: None,
            error: None,
            publication_grant,
        };
        match prepared {
            Ok(prepared) => {
                runner::validate_prepared(&run, &prepared)?;
                if let Some(request) = crate::physical::request(text, self.profile.request_bytes)? {
                    for channel in request.plan["channels"].as_array().ok_or_else(|| {
                        crate::physical::refused("deployment.channel", "/channels")
                    })? {
                        if channel["assignment"] == run.assignment["assignment"]
                            && channel["bufferRecords"]
                                .as_str()
                                .and_then(|text| text.parse::<usize>().ok())
                                .is_none_or(|records| prepared.records.len() > records)
                        {
                            return Err(crate::physical::refused(
                                "deployment.channel",
                                "/channels/bufferRecords",
                            ));
                        }
                    }
                }
                journal.prepared = Some(prepared);
                journal.phase = "prepared".into();
            }
            Err(error) => journal.error = Some(bounded_error(error)),
        }
        self.save(&request_hash, &journal)?;
        if busy(&journal) {
            self.active = Some(request_hash.clone());
        }
        self.status_from(&request_hash, &journal, &run)
    }

    pub fn activate(
        &mut self,
        request_hash: &str,
        broker: &mut impl Broker,
    ) -> Result<Status, String> {
        if self.increment_contains(request_hash)? {
            return self.increment_activate(request_hash, broker);
        }
        let (journal, _) = self.load(request_hash)?;
        self.verify_physical(&journal.request_text, broker.fenced())?;
        let (mut journal, run) = self.recover(request_hash)?;
        if journal.phase == "completed" {
            return self.status(request_hash);
        }
        if journal.phase == "cancelled" || journal.prepared.is_none() {
            return Err("request has no activatable prepared output".into());
        }
        if self.active.as_deref() != Some(request_hash) {
            return Err("request is not the current unfinished worker unit".into());
        }
        journal.phase = "publishing".into();
        journal.publication_started = true;
        journal.error = None;
        self.save(request_hash, &journal)?;
        let result = runner::activate_captured(
            &run,
            journal.prepared.as_ref().ok_or("prepared output missing")?,
            broker,
            &self.job_path(request_hash)?.join("completed.json"),
            journal.publication_grant.as_ref(),
        );
        match result {
            Ok(_) => {
                journal.phase = "completed".into();
                journal.error = None;
            }
            Err(error) => {
                journal.phase = "failed".into();
                journal.error = Some(bounded_error(error));
            }
        }
        self.save(request_hash, &journal)?;
        if !busy(&journal) {
            self.active = None;
        }
        self.status(request_hash)
    }

    pub fn status(&mut self, request_hash: &str) -> Result<Status, String> {
        if self.increment_contains(request_hash)? {
            return self.increment_status(request_hash);
        }
        let (journal, run) = self.recover(request_hash)?;
        self.status_from(request_hash, &journal, &run)
    }

    pub fn abort(&mut self, request_hash: &str) -> Result<Status, String> {
        if self.increment_contains(request_hash)? {
            return self.increment_abort(request_hash);
        }
        let (mut journal, run) = self.recover(request_hash)?;
        if journal.phase == "cancelled" {
            return self.status_from(request_hash, &journal, &run);
        }
        if journal.phase != "prepared" || journal.publication_started {
            return Err("publication may have begun or request is not prepared".into());
        }
        journal.phase = "cancelled".into();
        self.save(request_hash, &journal)?;
        self.active = None;
        self.status_from(request_hash, &journal, &run)
    }

    fn admit(&self, text: &str) -> Result<Admitted, String> {
        let physical = crate::physical::request(text, self.profile.request_bytes)?;
        let mut run = admission::admit_profiled(
            physical
                .as_ref()
                .map_or(text, |request| &request.local_text),
            &self.config.artifact_digest,
            &self.profile,
        )
        .map_err(|error| {
            if physical.is_some() {
                crate::physical::refused("deployment.assignment", "/requestText")
            } else {
                error
            }
        })?;
        if physical.is_some() {
            run.request_text = text.into();
            run.request_hash = tsx_contracts::sha256(text);
        }
        if run.assignment["node"] != self.config.node
            || run.assignment["site"] != self.config.site
            || run.bootstrap != self.config.bootstrap
        {
            if physical.is_some() {
                return Err(crate::physical::refused(
                    "deployment.capability",
                    "/assignmentTexts",
                ));
            }
            return Err("assignment node, site or broker does not match this worker".into());
        }
        Ok(run)
    }

    fn verify_physical(&mut self, text: &str, fenced: bool) -> Result<(), String> {
        let Some(request) = crate::physical::request(text, self.profile.request_bytes)? else {
            return Ok(());
        };
        if fenced || self.controller.is_some() {
            return Err(crate::physical::refused(
                "deployment.capability",
                "/capabilities/publicationFencing",
            ));
        }
        let observation = self
            .admission_facts
            .observe(&request.plan)
            .map_err(|error| error.to_string())?;
        tsx_contracts::physical::admit(
            &request.physical_text,
            &observation.facts,
            &observation.now_micros,
        )
        .map_err(|error| error.to_string())?;
        let current = self.capabilities();
        let run = admission::admit_profiled(
            &request.local_text,
            &self.config.artifact_digest,
            &self.profile,
        )?;
        if run.request.context.is_some() {
            return Err(crate::physical::refused(
                "deployment.capability",
                "/requestText/context",
            ));
        }
        let assignment = &run.assignment;
        for (index, channel) in request.plan["channels"]
            .as_array()
            .ok_or_else(|| crate::physical::refused("deployment.channel", "/channels"))?
            .iter()
            .enumerate()
        {
            if channel["assignment"] == assignment["assignment"]
                && channel["bufferRecords"]
                    .as_str()
                    .and_then(|text| text.parse::<usize>().ok())
                    .is_none_or(|records| {
                        records < run.request.limits.records || records < run.request.limits.samples
                    })
            {
                return Err(crate::physical::refused(
                    "deployment.channel",
                    &format!("/channels/{index}/bufferRecords"),
                ));
            }
            if channel["assignment"] == assignment["assignment"]
                && channel["to"] == "$sink"
                && (channel["topic"] != run.request.sink.topic
                    || channel["binding"] != run.request.sink.binding
                    || channel["partitionCount"] != run.request.sink.partition_count
                    || run.pipeline.schema_name() != crate::wire::SCHEMA_NAME)
            {
                return Err(crate::physical::refused(
                    "deployment.channel",
                    &format!("/channels/{index}"),
                ));
            }
        }
        let capability = request.plan["capabilities"]
            .as_array()
            .and_then(|capabilities| {
                capabilities
                    .iter()
                    .find(|capability| capability["assignment"] == assignment["assignment"])
            })
            .ok_or_else(|| crate::physical::refused("deployment.capability", "/capabilities"))?;
        let report = tsx_contracts::parse_json(
            capability["reportText"].as_str().ok_or_else(|| {
                crate::physical::refused("deployment.capability", "/capabilities")
            })?,
            self.profile.request_bytes,
        )
        .map_err(|_| crate::physical::refused("deployment.capability", "/capabilities"))?;
        if report != current {
            return Err(crate::physical::refused(
                "deployment.capability",
                "/capabilities",
            ));
        }
        Ok(())
    }

    fn job_path(&self, request_hash: &str) -> Result<PathBuf, String> {
        if !hash(request_hash) {
            return Err("invalid request hash".into());
        }
        Ok(self.config.state.join(request_hash))
    }

    fn load(&self, request_hash: &str) -> Result<(Journal, Admitted), String> {
        let dir = self.job_path(request_hash)?;
        directory(&dir, false)?;
        let path = dir.join("prepared.json");
        regular_or_absent(&path)?;
        let file = File::open(&path).map_err(|e| format!("cannot open prepared request: {e}"))?;
        let mut bytes = Vec::new();
        file.take(self.profile.journal_bytes as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("cannot read prepared request: {e}"))?;
        if bytes.len() > self.profile.journal_bytes {
            return Err("prepared request exceeds byte limit".into());
        }
        let text = std::str::from_utf8(&bytes).map_err(|_| "prepared request is not UTF-8")?;
        let value =
            tsx_contracts::parse_json(text, self.profile.journal_bytes).map_err(str::to_string)?;
        let envelope: Envelope =
            serde_json::from_value(value).map_err(|_| "invalid prepared envelope")?;
        let canonical = serde_json::to_vec(&envelope.payload)
            .map_err(|e| format!("cannot validate prepared request: {e}"))?;
        if envelope.schema != "opentsx.local-prepared.v1"
            || tsx_contracts::sha256_bytes(&canonical) != envelope.checksum
        {
            return Err("prepared request ABI or checksum mismatch".into());
        }
        let journal: Journal = serde_json::from_value(envelope.payload)
            .map_err(|_| "invalid prepared request payload")?;
        let run = self.admit(&journal.request_text)?;
        if run.request_hash != request_hash {
            return Err("persisted request hash mismatch".into());
        }
        if let Some(grant) = &journal.publication_grant {
            grant.validate_run(&run)?;
        }
        if let Some(prepared) = &journal.prepared {
            runner::validate_prepared(&run, prepared)?;
        }
        if !matches!(
            journal.phase.as_str(),
            "prepared" | "publishing" | "completed" | "failed" | "cancelled"
        ) || (journal.prepared.is_none() && journal.phase != "failed")
            || (matches!(journal.phase.as_str(), "prepared" | "cancelled")
                && journal.publication_started)
            || (matches!(journal.phase.as_str(), "publishing" | "completed")
                && !journal.publication_started)
            || (journal.phase == "failed"
                && journal
                    .error
                    .as_ref()
                    .is_none_or(|e| e.len() > 4096 || e.is_empty()))
            || (journal.phase != "failed" && journal.error.is_some())
        {
            return Err("invalid persisted worker lifecycle".into());
        }
        Ok((journal, run))
    }

    fn recover(&mut self, request_hash: &str) -> Result<(Journal, Admitted), String> {
        let (mut journal, run) = self.load(request_hash)?;
        if journal.publication_started {
            let store = crate::checkpoint::Store::open(
                &self.job_path(request_hash)?.join("completed.json"),
                run.request.limits.state_bytes + 65_536,
            )?;
            match store.load()? {
                Some(saved) => {
                    runner::verify_completion(&run, &saved)?;
                    if journal
                        .prepared
                        .as_ref()
                        .is_none_or(|p| p.completion.receipt != saved.receipt)
                    {
                        return Err("checkpoint conflicts with persisted preparation".into());
                    }
                    if journal.phase != "completed" {
                        journal.phase = "completed".into();
                        journal.error = None;
                        self.save(request_hash, &journal)?;
                    }
                    if self.active.as_deref() == Some(request_hash) {
                        self.active = None;
                    }
                }
                None if journal.phase == "completed" => {
                    return Err("completed worker journal has no authoritative checkpoint".into());
                }
                None => {}
            }
        }
        Ok((journal, run))
    }

    fn save(&self, request_hash: &str, journal: &Journal) -> Result<(), String> {
        let payload =
            serde_json::to_value(journal).map_err(|e| format!("cannot encode preparation: {e}"))?;
        let canonical =
            serde_json::to_vec(&payload).map_err(|e| format!("cannot encode preparation: {e}"))?;
        let envelope = Envelope {
            schema: "opentsx.local-prepared.v1".into(),
            checksum: tsx_contracts::sha256_bytes(&canonical),
            payload,
        };
        let bytes =
            serde_json::to_vec(&envelope).map_err(|e| format!("cannot encode preparation: {e}"))?;
        if bytes.len() > self.profile.journal_bytes {
            return Err("preparation exceeds persistence byte limit".into());
        }
        let dir = self.job_path(request_hash)?;
        directory(&dir, true)?;
        private_directory(&dir)?;
        let path = dir.join("prepared.json");
        let pending = dir.join(".prepared.pending");
        regular_or_absent(&path)?;
        regular_or_absent(&pending)?;
        let mut file = private_output(&pending)?;
        file.write_all(&bytes)
            .and_then(|()| file.sync_all())
            .map_err(|e| format!("cannot synchronize preparation: {e}"))?;
        fs::rename(&pending, &path).map_err(|e| format!("cannot commit preparation: {e}"))?;
        sync_directory(&dir)?;
        sync_directory(&self.config.state)
    }

    fn status_from(
        &self,
        request_hash: &str,
        journal: &Journal,
        run: &Admitted,
    ) -> Result<Status, String> {
        let mut state = journal.phase.clone();
        let mut error = journal.error.clone();
        if state == "publishing" {
            state = "failed".into();
            error = Some("activation was interrupted; retry the identical prepared request".into());
        }
        let receipt = if journal.phase == "completed" {
            let saved = crate::checkpoint::Store::open(
                &self.job_path(request_hash)?.join("completed.json"),
                run.request.limits.state_bytes + 65_536,
            )?
            .load()?
            .ok_or("completed worker journal has no authoritative checkpoint")?;
            runner::verify_completion(run, &saved)?;
            if journal
                .prepared
                .as_ref()
                .is_none_or(|p| p.completion.receipt != saved.receipt)
            {
                return Err("completed checkpoint conflicts with prepared output".into());
            }
            Some(saved.receipt)
        } else {
            None
        };
        Ok(Status {
            schema: "opentsx.local-worker-status.v1".into(),
            request_hash: request_hash.into(),
            node: self.config.node.clone(),
            site: self.config.site.clone(),
            artifact_digest: self.config.artifact_digest.clone(),
            state,
            activation_retryable: journal.publication_started
                && journal.prepared.is_some()
                && !matches!(journal.phase.as_str(), "completed" | "cancelled"),
            input_topic: run.cut.topic.clone(),
            input_series: journal
                .prepared
                .as_ref()
                .map(|p| p.input_series.clone())
                .unwrap_or_default(),
            output_series: journal
                .prepared
                .as_ref()
                .map(|p| p.output_series.clone())
                .unwrap_or_default(),
            receipt,
            error,
            sequence: None,
            previous_request_hash: None,
            publication_epoch: journal
                .publication_grant
                .as_ref()
                .map(|grant| grant.epoch.clone()),
            prepared_output_digest: journal.publication_grant.as_ref().and_then(|_| {
                journal
                    .prepared
                    .as_ref()
                    .map(|prepared| prepared.completion.receipt.output_digest.clone())
            }),
            prepared_output_records: journal.publication_grant.as_ref().and_then(|_| {
                journal
                    .prepared
                    .as_ref()
                    .map(|prepared| prepared.completion.receipt.output_records.to_string())
            }),
        })
    }

    fn increment_load(&self) -> Result<IncrementState, String> {
        let dir = self.config.state.join("increments");
        match fs::symlink_metadata(&dir) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(IncrementState::default())
            }
            Err(error) => return Err(format!("cannot inspect increment directory: {error}")),
            Ok(_) => directory(&dir, false)?,
        }
        let path = dir.join("checkpoint.json");
        regular_or_absent(&path)?;
        let file = match File::open(&path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(IncrementState::default())
            }
            Err(error) => return Err(format!("cannot open increment checkpoint: {error}")),
        };
        let mut bytes = Vec::new();
        file.take(self.profile.increment_bytes() as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("cannot read increment checkpoint: {e}"))?;
        if bytes.len() > self.profile.increment_bytes() {
            return Err("increment checkpoint exceeds byte limit".into());
        }
        let text = std::str::from_utf8(&bytes).map_err(|_| "increment checkpoint is not UTF-8")?;
        let value = tsx_contracts::parse_json(text, self.profile.increment_bytes())
            .map_err(str::to_string)?;
        let envelope: Envelope =
            serde_json::from_value(value).map_err(|_| "invalid increment envelope")?;
        let canonical = serde_json::to_vec(&envelope.payload)
            .map_err(|e| format!("cannot validate increment checkpoint: {e}"))?;
        if envelope.schema != "opentsx.local-increment-checkpoint.v1"
            || tsx_contracts::sha256_bytes(&canonical) != envelope.checksum
        {
            return Err("increment checkpoint ABI or checksum mismatch".into());
        }
        let state: IncrementState = serde_json::from_value(envelope.payload)
            .map_err(|_| "invalid increment checkpoint payload")?;
        if let Some(publication) = &state.publication {
            publication.grant.validate()?;
            if publication.grant.node != self.config.node
                || publication
                    .expected_head
                    .as_ref()
                    .is_some_and(|head| !hash(head))
            {
                return Err("invalid persisted publication installation".into());
            }
        }
        if let Some(head) = &state.head {
            let run = self.admit(&head.request_text)?;
            if run.increment.is_none() {
                return Err("increment head contains a bounded request".into());
            }
            runner::verify_completion(&run, &head.completion)?;
            if let Some(epoch) = &head.publication_epoch {
                let epoch = admission::canonical_i64(epoch, "publication epoch")?;
                let current = state
                    .publication
                    .as_ref()
                    .ok_or("publication authority is missing")?;
                let current = admission::canonical_i64(&current.grant.epoch, "publication epoch")?;
                if epoch <= 0 || epoch > current {
                    return Err("increment head has an invalid publication epoch".into());
                }
            }
            runner::check_retained_owners(&run, &head.completion, &head.input_owners)?;
            if head.input_series != head.input_owners.keys().cloned().collect::<Vec<_>>()
                || head.output_series
                    != runner::retained_output_series(&run, &head.completion, &head.input_owners)?
            {
                return Err("increment head series manifest mismatch".into());
            }
        }
        if let Some(candidate) = &state.candidate {
            let run = self.admit(&candidate.request_text)?;
            self.increment_successor(&run, &candidate.request_text, state.head.as_ref())?;
            validate_journal(candidate, &run)?;
            verify_capture(
                &run,
                candidate.publication_grant.as_ref(),
                state.publication.as_ref(),
            )?;
            if candidate.phase == "completed" {
                return Err("completed increment must be the authoritative head".into());
            }
            if let (Some(head), Some(prepared)) = (&state.head, &candidate.prepared) {
                if head
                    .input_owners
                    .iter()
                    .any(|(id, partition)| prepared.input_owners.get(id) != Some(partition))
                    || head
                        .output_series
                        .iter()
                        .any(|id| !prepared.output_series.contains(id))
                {
                    return Err("increment candidate loses retained series ownership".into());
                }
            }
        }
        Ok(state)
    }

    fn increment_save(&self, state: &IncrementState) -> Result<(), String> {
        let payload = serde_json::to_value(state)
            .map_err(|e| format!("cannot encode increment checkpoint: {e}"))?;
        let canonical = serde_json::to_vec(&payload)
            .map_err(|e| format!("cannot encode increment checkpoint: {e}"))?;
        let envelope = Envelope {
            schema: "opentsx.local-increment-checkpoint.v1".into(),
            checksum: tsx_contracts::sha256_bytes(&canonical),
            payload,
        };
        let bytes = serde_json::to_vec(&envelope)
            .map_err(|e| format!("cannot encode increment checkpoint: {e}"))?;
        if bytes.len() > self.profile.increment_bytes() {
            return Err("increment checkpoint exceeds byte limit".into());
        }
        let dir = self.config.state.join("increments");
        directory(&dir, true)?;
        private_directory(&dir)?;
        let path = dir.join("checkpoint.json");
        let pending = dir.join(".checkpoint.pending");
        regular_or_absent(&path)?;
        regular_or_absent(&pending)?;
        let mut file = private_output(&pending)?;
        file.write_all(&bytes)
            .and_then(|()| file.sync_all())
            .map_err(|e| format!("cannot synchronize increment checkpoint: {e}"))?;
        fs::rename(&pending, &path)
            .map_err(|e| format!("cannot commit increment checkpoint: {e}"))?;
        sync_directory(&dir)?;
        sync_directory(&self.config.state)
    }

    fn increment_successor(
        &self,
        run: &Admitted,
        text: &str,
        head: Option<&IncrementHead>,
    ) -> Result<(), String> {
        let increment = run.increment.as_ref().ok_or("increment request required")?;
        match head {
            None if increment.sequence != 1
                || increment.previous_request_hash.is_some()
                || run.cut.positions.iter().any(|p| p.from != 0) =>
            {
                Err("first increment must begin at offset zero with no predecessor".into())
            }
            None => Ok(()),
            Some(head) => {
                let prior = self.admit(&head.request_text)?;
                let prior_increment = prior
                    .increment
                    .as_ref()
                    .ok_or("increment predecessor missing")?;
                if prior_increment.sequence.checked_add(1) != Some(increment.sequence)
                    || increment.previous_request_hash.as_deref()
                        != Some(head.completion.request_hash.as_str())
                    || admission::increment_context(text, &self.profile)?
                        != admission::increment_context(&head.request_text, &self.profile)?
                    || run.cut.positions.len() != head.completion.receipt.positions.len()
                    || run
                        .cut
                        .positions
                        .iter()
                        .zip(&head.completion.receipt.positions)
                        .any(|(p, saved)| {
                            p.partition.to_string() != saved.partition
                                || p.from.to_string() != saved.next
                        })
                {
                    return Err("stale, forked or incompatible increment successor".into());
                }
                Ok(())
            }
        }
    }

    fn increment_contains(&self, request_hash: &str) -> Result<bool, String> {
        if !hash(request_hash) {
            return Err("invalid request hash".into());
        }
        let state = self.increment_load()?;
        Ok(state
            .head
            .as_ref()
            .is_some_and(|head| head.completion.request_hash == request_hash)
            || state.candidate.as_ref().is_some_and(|candidate| {
                tsx_contracts::sha256(&candidate.request_text) == request_hash
            }))
    }

    fn increment_prepare(
        &mut self,
        text: &str,
        mut run: Admitted,
        broker: &mut impl Broker,
    ) -> Result<Status, String> {
        let mut state = self.increment_load()?;
        if state
            .head
            .as_ref()
            .is_some_and(|head| head.completion.request_hash == run.request_hash)
            || state
                .candidate
                .as_ref()
                .is_some_and(|candidate| candidate.request_text == text)
        {
            return self.increment_status(&run.request_hash);
        }
        if self.active.is_some() || state.candidate.is_some() {
            return Err("worker is busy with another increment candidate".into());
        }
        self.increment_successor(&run, text, state.head.as_ref())?;
        let publication_grant =
            broker.capture_grant(&run, state.publication.as_ref().map(|value| &value.grant))?;
        verify_capture(&run, publication_grant.as_ref(), state.publication.as_ref())?;
        let owners = if let Some(head) = &state.head {
            run.pipeline.restore(&head.completion.state)?;
            head.input_owners.clone()
        } else {
            BTreeMap::new()
        };
        let mut candidate = Journal {
            request_text: text.into(),
            phase: "failed".into(),
            publication_started: false,
            prepared: None,
            error: None,
            publication_grant,
        };
        match runner::prepare_continued(&mut run, broker, owners) {
            Ok(prepared) => {
                runner::validate_prepared(&run, &prepared)?;
                candidate.prepared = Some(prepared);
                candidate.phase = "prepared".into();
            }
            Err(error) => candidate.error = Some(bounded_error(error)),
        }
        state.candidate = Some(candidate);
        self.increment_save(&state)?;
        self.increment_status(&run.request_hash)
    }

    fn increment_activate(
        &mut self,
        request_hash: &str,
        broker: &mut impl Broker,
    ) -> Result<Status, String> {
        let mut state = self.increment_load()?;
        if state
            .head
            .as_ref()
            .is_some_and(|head| head.completion.request_hash == request_hash)
        {
            return self.increment_status(request_hash);
        }
        let candidate = state
            .candidate
            .as_mut()
            .ok_or("increment candidate missing")?;
        if tsx_contracts::sha256(&candidate.request_text) != request_hash
            || candidate.phase == "cancelled"
            || candidate.prepared.is_none()
        {
            return Err("request has no activatable increment candidate".into());
        }
        let run = self.admit(&candidate.request_text)?;
        runner::validate_prepared(
            &run,
            candidate
                .prepared
                .as_ref()
                .ok_or("increment output missing")?,
        )?;
        candidate.phase = "publishing".into();
        candidate.publication_started = true;
        candidate.error = None;
        self.increment_save(&state)?;
        let candidate = state
            .candidate
            .as_mut()
            .ok_or("increment candidate missing")?;
        let prepared = candidate
            .prepared
            .as_ref()
            .ok_or("increment output missing")?;
        match broker.publish_captured(
            &run,
            &prepared.records,
            candidate.publication_grant.as_ref(),
        ) {
            Ok(()) => {
                let prepared = candidate
                    .prepared
                    .take()
                    .ok_or("increment output missing")?;
                state.head = Some(IncrementHead {
                    request_text: candidate.request_text.clone(),
                    completion: prepared.completion,
                    input_owners: prepared.input_owners,
                    input_series: prepared.input_series,
                    output_series: prepared.output_series,
                    publication_epoch: candidate
                        .publication_grant
                        .as_ref()
                        .map(|grant| grant.epoch.clone()),
                });
                state.candidate = None;
            }
            Err(error) => {
                candidate.phase = "failed".into();
                candidate.error = Some(bounded_error(error));
            }
        }
        self.increment_save(&state)?;
        self.increment_status(request_hash)
    }

    fn increment_status(&self, request_hash: &str) -> Result<Status, String> {
        let state = self.increment_load()?;
        if let Some(head) = &state.head {
            if head.completion.request_hash == request_hash {
                let run = self.admit(&head.request_text)?;
                let mut status = self.increment_status_value(
                    &run,
                    "completed",
                    false,
                    (head.input_series.clone(), head.output_series.clone()),
                    Some(head.completion.receipt.clone()),
                    None,
                )?;
                status.publication_epoch = head.publication_epoch.clone();
                if head.publication_epoch.is_some() {
                    status.prepared_output_digest =
                        Some(head.completion.receipt.output_digest.clone());
                    status.prepared_output_records =
                        Some(head.completion.receipt.output_records.to_string());
                }
                return Ok(status);
            }
        }
        let candidate = state
            .candidate
            .as_ref()
            .filter(|candidate| tsx_contracts::sha256(&candidate.request_text) == request_hash)
            .ok_or("increment request is unknown or stale")?;
        let run = self.admit(&candidate.request_text)?;
        let (inputs, outputs) = if let Some(prepared) = &candidate.prepared {
            (
                prepared.input_series.clone(),
                prepared.output_series.clone(),
            )
        } else if let Some(head) = &state.head {
            (head.input_series.clone(), head.output_series.clone())
        } else {
            (Vec::new(), Vec::new())
        };
        let publishing = candidate.phase == "publishing";
        let mut status = self.increment_status_value(
            &run,
            if publishing {
                "failed"
            } else {
                &candidate.phase
            },
            candidate.publication_started && candidate.prepared.is_some(),
            (inputs, outputs),
            None,
            if publishing {
                Some("activation was interrupted; retry the identical prepared request".into())
            } else {
                candidate.error.clone()
            },
        )?;
        status.publication_epoch = candidate
            .publication_grant
            .as_ref()
            .map(|grant| grant.epoch.clone());
        if candidate.publication_grant.is_some() {
            status.prepared_output_digest = candidate
                .prepared
                .as_ref()
                .map(|prepared| prepared.completion.receipt.output_digest.clone());
            status.prepared_output_records = candidate
                .prepared
                .as_ref()
                .map(|prepared| prepared.completion.receipt.output_records.to_string());
        }
        Ok(status)
    }

    fn increment_status_value(
        &self,
        run: &Admitted,
        state: &str,
        activation_retryable: bool,
        inventory: (Vec<String>, Vec<String>),
        receipt: Option<Receipt>,
        error: Option<String>,
    ) -> Result<Status, String> {
        let increment = run.increment.as_ref().ok_or("increment request required")?;
        Ok(Status {
            schema: "opentsx.local-worker-status.v1".into(),
            request_hash: run.request_hash.clone(),
            node: self.config.node.clone(),
            site: self.config.site.clone(),
            artifact_digest: self.config.artifact_digest.clone(),
            state: state.into(),
            activation_retryable,
            input_topic: run.cut.topic.clone(),
            input_series: inventory.0,
            output_series: inventory.1,
            receipt,
            error,
            sequence: Some(increment.sequence.to_string()),
            previous_request_hash: Some(increment.previous_request_hash.clone()),
            publication_epoch: None,
            prepared_output_digest: None,
            prepared_output_records: None,
        })
    }

    fn increment_abort(&mut self, request_hash: &str) -> Result<Status, String> {
        let mut state = self.increment_load()?;
        let candidate = state
            .candidate
            .as_mut()
            .filter(|candidate| tsx_contracts::sha256(&candidate.request_text) == request_hash)
            .ok_or("completed increments cannot be aborted")?;
        if candidate.phase == "cancelled" {
            return self.increment_status(request_hash);
        }
        if candidate.phase != "prepared" || candidate.publication_started {
            return Err("increment publication may have begun or request is not prepared".into());
        }
        candidate.phase = "cancelled".into();
        self.increment_save(&state)?;
        self.increment_status(request_hash)
    }

    pub fn install_publication(&mut self, text: &str) -> Result<Value, String> {
        let value = tsx_contracts::parse_json(text, self.profile.request_bytes)
            .map_err(|_| "invalid publication installation")?;
        if !value.is_object()
            || value
                .as_object()
                .is_none_or(|fields| fields.len() != 3 || !fields.contains_key("expectedHead"))
        {
            return Err("publication installation requires grant, reset and expectedHead".into());
        }
        let installation: PublicationInstallation =
            serde_json::from_value(value).map_err(|_| "invalid publication installation")?;
        installation.grant.validate()?;
        if installation.grant.node != self.config.node
            || installation
                .expected_head
                .as_ref()
                .is_some_and(|head| !hash(head))
        {
            return Err("publication installation does not match this worker".into());
        }
        let mut state = self.increment_load()?;
        if let Some(current) = &state.publication {
            if current.grant.operation == installation.grant.operation {
                if current != &installation {
                    return Err("publication operation conflicts with recorded content".into());
                }
                return Ok(installed_publication(&installation.grant));
            }
            if admission::canonical_i64(&installation.grant.epoch, "publication epoch")?
                <= admission::canonical_i64(&current.grant.epoch, "publication epoch")?
            {
                return Err("publication epoch must advance".into());
            }
            if !installation.reset
                && (current.grant.deployment != installation.grant.deployment
                    || current.grant.input_topic != installation.grant.input_topic
                    || current.grant.output_topic != installation.grant.output_topic)
            {
                return Err("publication resume cannot change deployment or topic identity".into());
            }
        }
        if self.active.is_some()
            || state
                .candidate
                .as_ref()
                .is_some_and(|candidate| candidate.phase != "cancelled")
        {
            return Err("publication installation requires no pending candidate".into());
        }
        let head = state
            .head
            .as_ref()
            .map(|head| head.completion.request_hash.clone());
        if head != installation.expected_head {
            return Err("publication installation does not match the completed head".into());
        }
        if installation.reset {
            let previous = state
                .head
                .as_ref()
                .ok_or("publication reset requires a completed head")?;
            let previous = self.admit(&previous.request_text)?;
            if previous.cut.topic != installation.grant.input_topic
                || previous.assignment["deployment"].as_str()
                    == Some(&installation.grant.deployment)
                || previous.request.sink.topic == installation.grant.output_topic
            {
                return Err("publication reset requires the same whole input topic and a distinct deployment and output".into());
            }
            state.head = None;
        } else if let Some(head) = &state.head {
            installation
                .grant
                .validate_run(&self.admit(&head.request_text)?)?;
        }
        let reply = installed_publication(&installation.grant);
        state.candidate = None;
        state.publication = Some(installation);
        self.increment_save(&state)?;
        Ok(reply)
    }

    pub fn publication_status(&self) -> Result<Value, String> {
        let state = self.increment_load()?;
        Ok(match state.publication {
            Some(publication) => json!({"publicationEpoch":publication.grant.epoch,
                "deployment":publication.grant.deployment,"operation":publication.grant.operation}),
            None => json!({"publicationEpoch":null,"deployment":null,"operation":null}),
        })
    }

    pub fn handle(&mut self, request: &mut Request, broker: &mut impl Broker) -> (u16, Value) {
        let route = request.url().to_string();
        let method = request.method().clone();
        if method == Method::Post
            && self.controller.is_some_and(|controller| {
                request
                    .remote_addr()
                    .is_none_or(|peer| peer.ip() != controller)
            })
        {
            return (
                403,
                json!({"error":"worker mutations require the configured controller peer"}),
            );
        }
        let body = match request_body(request, self.profile.request_bytes) {
            Ok(body) => body,
            Err((status, error)) => return (status, json!({"error":error})),
        };
        if method == Method::Get && route == "/v1/capabilities" && body.is_empty() {
            return (200, self.capabilities());
        }
        if method == Method::Get
            && route == "/v1/publication"
            && body.is_empty()
            && self.controller.is_some()
        {
            return match self.publication_status() {
                Ok(status) => (200, status),
                Err(_) => (500, json!({"error":"cannot load publication status"})),
            };
        }
        if method == Method::Post && route == "/v1/publication" && self.controller.is_some() {
            let text = match std::str::from_utf8(&body) {
                Ok(text) => text,
                Err(_) => {
                    return (
                        400,
                        json!({"error":"publication installation is not UTF-8"}),
                    )
                }
            };
            return match self.install_publication(text) {
                Ok(reply) => (200, reply),
                Err(error) => (409, json!({"error":error})),
            };
        }
        if method == Method::Post && (route == "/v1/prepare" || route == "/v1/prepare-physical") {
            let text = match std::str::from_utf8(&body) {
                Ok(text) => text,
                Err(_) => return (400, json!({"error":"request is not UTF-8"})),
            };
            let physical = crate::physical::request(text, self.profile.request_bytes);
            let result = match physical {
                Ok(Some(_)) if route == "/v1/prepare-physical" => self.prepare(text, broker),
                Ok(None) if route == "/v1/prepare" => self.prepare(text, broker),
                Ok(_) => Err(crate::physical::refused("deployment.version", "/schema")),
                Err(error)
                    if route == "/v1/prepare-physical"
                        && crate::physical::refusal_value(&error).is_none() =>
                {
                    Err(crate::physical::refused("deployment.json", "/"))
                }
                Err(error) => Err(error),
            };
            return json_result(result, 422);
        }
        if !body.is_empty() {
            return (
                400,
                json!({"error":"this operation requires an empty body"}),
            );
        }
        if method == Method::Get {
            if let Some(id) = route.strip_prefix("/v1/jobs/").filter(|id| hash(id)) {
                return json_result(self.status(id), 404);
            }
        }
        if method == Method::Post {
            if let Some(id) = route.strip_prefix("/v1/activate/").filter(|id| hash(id)) {
                return json_result(self.activate(id, broker), 409);
            }
            if let Some(id) = route.strip_prefix("/v1/abort/").filter(|id| hash(id)) {
                return json_result(self.abort(id), 409);
            }
        }
        (404, json!({"error":"unknown worker operation"}))
    }

    pub fn serve(self, listen: &str, broker: impl Broker) -> Result<(), String> {
        if broker.fenced() {
            return Err("fenced publication requires a controller peer".into());
        }
        self.serve_loop(listen, broker)
    }

    pub fn serve_with_controller(
        mut self,
        listen: &str,
        broker: impl Broker,
        controller: IpAddr,
    ) -> Result<(), String> {
        if !broker.fenced() {
            return Err("a controller peer requires fenced publication".into());
        }
        self.controller = Some(controller);
        self.serve_loop(listen, broker)
    }

    fn serve_loop(mut self, listen: &str, mut broker: impl Broker) -> Result<(), String> {
        let server =
            Server::http(listen).map_err(|e| format!("cannot bind worker HTTP listener: {e}"))?;
        loop {
            let mut request = server
                .recv()
                .map_err(|e| format!("cannot receive worker request: {e}"))?;
            let (status, value) = self.handle(&mut request, &mut broker);
            let bytes = serde_json::to_vec(&value)
                .map_err(|e| format!("cannot encode worker response: {e}"))?;
            let header = Header::from_bytes("Content-Type", "application/json")
                .map_err(|_| "cannot encode response content type")?;
            if let Err(error) = request.respond(
                Response::from_data(bytes)
                    .with_status_code(status)
                    .with_header(header),
            ) {
                eprintln!("cannot deliver worker response: {error}");
            }
        }
    }
}

fn json_result(result: Result<Status, String>, error_status: u16) -> (u16, Value) {
    match result {
        Ok(status) => match serde_json::to_value(status) {
            Ok(value) => (200, value),
            Err(error) => (
                500,
                json!({"error":format!("cannot encode worker status: {error}")}),
            ),
        },
        Err(error) => match crate::physical::refusal_value(&error) {
            Some(refusal) => (
                error_status,
                json!({"error":refusal["message"],"refusal":refusal}),
            ),
            None => (
                if error.contains("busy") {
                    409
                } else {
                    error_status
                },
                json!({"error":error}),
            ),
        },
    }
}

fn validate_journal(journal: &Journal, run: &Admitted) -> Result<(), String> {
    if let Some(grant) = &journal.publication_grant {
        grant.validate_run(run)?;
    }
    if let Some(prepared) = &journal.prepared {
        runner::validate_prepared(run, prepared)?;
    }
    if !matches!(
        journal.phase.as_str(),
        "prepared" | "publishing" | "completed" | "failed" | "cancelled"
    ) || (journal.prepared.is_none()
        && (journal.phase != "failed" || journal.publication_started))
        || (matches!(journal.phase.as_str(), "prepared" | "cancelled")
            && journal.publication_started)
        || (matches!(journal.phase.as_str(), "publishing" | "completed")
            && !journal.publication_started)
        || (journal.phase == "failed"
            && journal
                .error
                .as_ref()
                .is_none_or(|e| e.len() > 4096 || e.is_empty()))
        || (journal.phase != "failed" && journal.error.is_some())
    {
        return Err("invalid persisted worker lifecycle".into());
    }
    Ok(())
}

fn request_body(request: &mut Request, limit: usize) -> Result<Vec<u8>, (u16, String)> {
    let lengths: Vec<_> = request
        .headers()
        .iter()
        .filter(|h| h.field.equiv("Content-Length"))
        .collect();
    if lengths.len() > 1
        || request.headers().iter().any(|h| {
            h.field.equiv("Transfer-Encoding")
                || h.field.equiv("Content-Encoding")
                || h.field.equiv("Upgrade")
        })
    {
        return Err((400, "unsupported or ambiguous request framing".into()));
    }
    let length = framed_length(request.body_length(), request.headers())?;
    if length > limit {
        return Err((413, "request exceeds byte limit".into()));
    }
    let mut body = Vec::new();
    request
        .as_reader()
        .take(limit as u64 + 1)
        .read_to_end(&mut body)
        .map_err(|e| (400, format!("cannot read request: {e}")))?;
    if body.len() > limit {
        return Err((413, "request exceeds byte limit".into()));
    }
    if body.len() != length {
        return Err((400, "request length mismatch".into()));
    }
    Ok(body)
}

fn framed_length(length: Option<usize>, headers: &[Header]) -> Result<usize, (u16, String)> {
    match headers
        .iter()
        .find(|header| header.field.equiv("Content-Length"))
    {
        Some(header)
            if header.value.as_str().is_empty()
                || !header.value.as_str().bytes().all(|b| b.is_ascii_digit()) =>
        {
            Err((400, "invalid Content-Length".into()))
        }
        Some(_) => length.ok_or((400, "invalid Content-Length".into())),
        None => Ok(0),
    }
}

fn busy(journal: &Journal) -> bool {
    journal.phase == "prepared"
        || journal.phase == "publishing"
        || (journal.publication_started && journal.phase != "completed")
}

fn installed_publication(grant: &PublicationGrant) -> Value {
    json!({"operation":grant.operation,"publicationEpoch":grant.epoch,"node":grant.node,"state":"installed"})
}

fn verify_capture(
    run: &Admitted,
    captured: Option<&PublicationGrant>,
    installed: Option<&PublicationInstallation>,
) -> Result<(), String> {
    match (captured, installed) {
        (None, None) => Ok(()),
        (Some(captured), Some(installed)) if captured == &installed.grant => {
            captured.validate_run(run)
        }
        _ => Err("prepared publication grant does not match the installed authority".into()),
    }
}

fn private_directory(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
            .map_err(|_| "cannot make worker state private".into())
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Err("private worker state requires Unix filesystem permissions".into())
    }
}

fn private_output(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.create(true).write(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        options.mode(0o600);
        let file = options
            .open(path)
            .map_err(|_| "cannot open private worker checkpoint")?;
        file.set_permissions(fs::Permissions::from_mode(0o600))
            .map_err(|_| "cannot make worker checkpoint private")?;
        Ok(file)
    }
    #[cfg(not(unix))]
    {
        let _ = options;
        Err("private worker checkpoints require Unix filesystem permissions".into())
    }
}

fn private_lock(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true).truncate(false);
    #[cfg(unix)]
    {
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        options.mode(0o600);
        let file = options
            .open(path)
            .map_err(|_| "cannot open private worker lock")?;
        file.set_permissions(fs::Permissions::from_mode(0o600))
            .map_err(|_| "cannot make worker lock private")?;
        Ok(file)
    }
    #[cfg(not(unix))]
    {
        let _ = options;
        Err("private worker checkpoints require Unix filesystem permissions".into())
    }
}

fn bounded_error(mut error: String) -> String {
    if error.len() > 4096 {
        let mut boundary = 4096;
        while !error.is_char_boundary(boundary) {
            boundary -= 1;
        }
        error.truncate(boundary);
    }
    if error.is_empty() {
        "operation failed without an error description".into()
    } else {
        error
    }
}

fn hash(text: &str) -> bool {
    text.len() == 64
        && text
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn identifier(text: &str) -> bool {
    !text.is_empty()
        && text.len() <= 63
        && text
            .bytes()
            .enumerate()
            .all(|(i, b)| b.is_ascii_lowercase() || b.is_ascii_digit() || (i > 0 && b == b'-'))
}

fn regular_or_absent(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_file() => Ok(()),
        Ok(_) => Err("worker file paths must be regular files".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("cannot inspect worker file: {error}")),
    }
}

fn directory(path: &Path, create: bool) -> Result<(), String> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map_err(|e| format!("cannot locate worker directory: {e}"))?
            .join(path)
    };
    let mut current = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::RootDir | Component::Prefix(_) => current.push(component.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                return Err("worker state paths cannot contain parent traversal".into())
            }
            Component::Normal(part) => {
                current.push(part);
                match fs::symlink_metadata(&current) {
                    Ok(metadata) if metadata.is_dir() => {}
                    Ok(_) => {
                        return Err(
                            "worker directories cannot contain links or regular files".into()
                        )
                    }
                    Err(error) if create && error.kind() == std::io::ErrorKind::NotFound => {
                        fs::create_dir(&current)
                            .map_err(|e| format!("cannot create worker directory: {e}"))?;
                        if let Some(parent) = current.parent() {
                            sync_directory(parent)?;
                        }
                    }
                    Err(error) => return Err(format!("cannot inspect worker directory: {error}")),
                }
            }
        }
    }
    Ok(())
}

fn sync_directory(path: &Path) -> Result<(), String> {
    File::open(path)
        .and_then(|file| file.sync_all())
        .map_err(|e| format!("cannot synchronize worker directory: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn directory() -> PathBuf {
        let path = std::env::temp_dir().canonicalize().unwrap().join(format!(
            "tsx-worker-owner-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        path
    }

    fn owner(path: &Path) -> Result<Worker, String> {
        Worker::open(Config {
            node: "worker-1".into(),
            site: "local".into(),
            bootstrap: "localhost:9092".into(),
            artifact_digest: format!("sha256:{}", tsx_contracts::sha256("artifact")),
            state: path.to_path_buf(),
        })
    }

    #[test]
    fn worker_owner_drop_releases_inherited_descriptors_without_unlocking_a_successor() {
        let directory = directory();
        let worker = owner(&directory).unwrap();
        let inherited = worker._lock.try_clone().unwrap();
        assert!(owner(&directory).is_err());
        drop(worker);
        let successor = owner(&directory).unwrap();
        drop(inherited);
        assert!(owner(&directory).is_err());
        drop(successor);
        drop(owner(&directory).unwrap());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn worker_body_errors_release_ownership_with_an_inherited_descriptor_alive() {
        let directory = directory();
        let mut inherited = None;
        let result: Result<(), String> = (|| {
            let worker = owner(&directory)?;
            inherited = Some(worker._lock.try_clone().unwrap());
            Err("rejected worker body".into())
        })();
        assert_eq!(result, Err("rejected worker body".into()));
        let successor = owner(&directory).unwrap();
        drop(inherited);
        assert!(owner(&directory).is_err());
        drop(successor);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn worker_validation_errors_release_the_acquired_lock() {
        let directory = directory();
        let unknown = directory.join("unknown.json");
        fs::write(&unknown, "{}").unwrap();
        assert!(owner(&directory).is_err());
        fs::remove_file(unknown).unwrap();
        drop(owner(&directory).unwrap());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn worker_drop_respects_the_acquiring_process_identity() {
        let directory = directory();
        let mut worker = owner(&directory).unwrap();
        let inherited = worker._lock.try_clone().unwrap();
        worker.lock_owner_pid = std::process::id().wrapping_add(1);
        drop(worker);
        assert!(owner(&directory).is_err());
        inherited.unlock().unwrap();
        drop(inherited);
        drop(owner(&directory).unwrap());
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn http_without_body_headers_has_zero_length() {
        assert_eq!(super::framed_length(None, &[]), Ok(0));
    }

    #[test]
    fn fenced_mutations_require_the_actual_controller_peer_and_ignore_forwarded_headers() {
        use super::{Config, Worker};
        use crate::admission::Admitted;
        use crate::broker::{Broker, InputRecord, OutputRecord};
        use serde_json::json;
        use std::fs;
        use tiny_http::{Header, Method, TestRequest};

        struct NoIo;
        impl Broker for NoIo {
            fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
                panic!("unexpected input read")
            }
            fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
                panic!("unexpected publication")
            }
        }

        let path = std::env::temp_dir()
            .canonicalize()
            .unwrap()
            .join(format!("tsx-controller-peer-{}", std::process::id()));
        fs::create_dir(&path).unwrap();
        let mut worker = Worker::open(Config {
            node: "worker-1".into(),
            site: "local".into(),
            bootstrap: "localhost:9092".into(),
            artifact_digest: format!("sha256:{}", tsx_contracts::sha256("artifact")),
            state: path.clone(),
        })
        .unwrap();
        assert!(worker.capabilities().get("publicationFencing").is_none());
        worker.controller = Some("172.31.0.10".parse().unwrap());
        assert_eq!(worker.capabilities()["publicationFencing"], true);
        for route in [
            "/v1/publication",
            "/v1/prepare",
            "/v1/activate/unknown",
            "/v1/abort/unknown",
        ] {
            let mut request = TestRequest::new()
                .with_method(Method::Post)
                .with_path(route)
                .with_remote_addr("172.31.0.11:12345".parse().unwrap())
                .with_header(Header::from_bytes("X-Forwarded-For", "172.31.0.10").unwrap())
                .with_body("{}")
                .into();
            assert_eq!(worker.handle(&mut request, &mut NoIo).0, 403);
        }
        let mut trusted = TestRequest::new()
            .with_method(Method::Post)
            .with_path("/v1/publication")
            .with_remote_addr("172.31.0.10:12345".parse().unwrap())
            .with_body("{}")
            .into();
        assert_eq!(worker.handle(&mut trusted, &mut NoIo).0, 409);
        let mut status = TestRequest::new().with_path("/v1/publication").into();
        assert_eq!(
            worker.handle(&mut status, &mut NoIo),
            (
                200,
                json!({"publicationEpoch":null,"deployment":null,"operation":null})
            )
        );
        drop(worker);
        fs::remove_dir_all(path).unwrap();
    }
}
