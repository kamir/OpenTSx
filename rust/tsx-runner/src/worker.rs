use crate::admission::{self, Admitted, REQUEST_BYTES};
use crate::broker::Broker;
use crate::checkpoint::{Completion, Receipt};
use crate::operators::{STATE_ABI, SUPPORTED_OPS};
use crate::runner::{self, Prepared};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use tiny_http::{Header, Method, Request, Response, Server};

const JOURNAL_BYTES: usize = 85_000_000;
const INCREMENT_BYTES: usize = JOURNAL_BYTES + 1_048_576 + 262_144;

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
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Journal {
    request_text: String,
    phase: String,
    publication_started: bool,
    prepared: Option<Prepared>,
    error: Option<String>,
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
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct IncrementHead {
    request_text: String,
    completion: Completion,
    input_owners: BTreeMap<String, i32>,
    input_series: Vec<String>,
    output_series: Vec<String>,
}

pub struct Worker {
    config: Config,
    active: Option<String>,
    _lock: File,
}

impl Worker {
    pub fn open(config: Config) -> Result<Self, String> {
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
        let lock_path = config.state.join(".worker.lock");
        regular_or_absent(&lock_path)?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&lock_path)
            .map_err(|e| format!("cannot open worker lock: {e}"))?;
        lock.try_lock()
            .map_err(|e| format!("worker state is locked or cannot be locked: {e}"))?;
        let mut worker = Self {
            config,
            active: None,
            _lock: lock,
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
        let mut operators = vec!["decode.avro-soe"];
        operators.extend_from_slice(SUPPORTED_OPS);
        json!({"schema":"opentsx.local-worker.v1","node":self.config.node,
            "site":self.config.site,"artifactDigest":self.config.artifact_digest,
            "version":env!("CARGO_PKG_VERSION"),"catalog":"tsx-catalog/1",
            "requestSchema":"opentsx.local-bounded-run.v1","stateAbi":STATE_ABI,
            "incrementalRequestSchema":"opentsx.local-increment.v1",
            "fingerprint":crate::wire::FINGERPRINT,"operators":operators,
            "limits":{"records":10000,"payloadBytes":1048576,"inputBytes":16777216,
                "samples":100000,"outputBytes":16777216,"series":64,"stateBytes":1048576,
                "requestBytes":REQUEST_BYTES,"partitions":admission::MAX_PARTITIONS,
                "incrementalChains":1,
                "episodeSamples":admission::MAX_EPISODE_SAMPLES}})
    }

    pub fn prepare(&mut self, text: &str, broker: &mut impl Broker) -> Result<Status, String> {
        let mut run = self.admit(text)?;
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
        let prepared = runner::prepare(&mut run, broker);
        let mut journal = Journal {
            request_text: text.into(),
            phase: "failed".into(),
            publication_started: false,
            prepared: None,
            error: None,
        };
        match prepared {
            Ok(prepared) => {
                runner::validate_prepared(&run, &prepared)?;
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
        let result = runner::activate(
            &run,
            journal.prepared.as_ref().ok_or("prepared output missing")?,
            broker,
            &self.job_path(request_hash)?.join("completed.json"),
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
        let run = admission::admit(text, &self.config.artifact_digest)?;
        if run.assignment["node"] != self.config.node
            || run.assignment["site"] != self.config.site
            || run.bootstrap != self.config.bootstrap
        {
            return Err("assignment node, site or broker does not match this worker".into());
        }
        Ok(run)
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
        file.take(JOURNAL_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("cannot read prepared request: {e}"))?;
        if bytes.len() > JOURNAL_BYTES {
            return Err("prepared request exceeds byte limit".into());
        }
        let text = std::str::from_utf8(&bytes).map_err(|_| "prepared request is not UTF-8")?;
        let value = tsx_contracts::parse_json(text, JOURNAL_BYTES).map_err(str::to_string)?;
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
        if bytes.len() > JOURNAL_BYTES {
            return Err("preparation exceeds persistence byte limit".into());
        }
        let dir = self.job_path(request_hash)?;
        directory(&dir, true)?;
        let path = dir.join("prepared.json");
        let pending = dir.join(".prepared.pending");
        regular_or_absent(&path)?;
        regular_or_absent(&pending)?;
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&pending)
            .map_err(|e| format!("cannot open pending preparation: {e}"))?;
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
        file.take(INCREMENT_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| format!("cannot read increment checkpoint: {e}"))?;
        if bytes.len() > INCREMENT_BYTES {
            return Err("increment checkpoint exceeds byte limit".into());
        }
        let text = std::str::from_utf8(&bytes).map_err(|_| "increment checkpoint is not UTF-8")?;
        let value = tsx_contracts::parse_json(text, INCREMENT_BYTES).map_err(str::to_string)?;
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
        if let Some(head) = &state.head {
            let run = self.admit(&head.request_text)?;
            if run.increment.is_none() {
                return Err("increment head contains a bounded request".into());
            }
            runner::verify_completion(&run, &head.completion)?;
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
        if bytes.len() > INCREMENT_BYTES {
            return Err("increment checkpoint exceeds byte limit".into());
        }
        let dir = self.config.state.join("increments");
        directory(&dir, true)?;
        let path = dir.join("checkpoint.json");
        let pending = dir.join(".checkpoint.pending");
        regular_or_absent(&path)?;
        regular_or_absent(&pending)?;
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&pending)
            .map_err(|e| format!("cannot open pending increment checkpoint: {e}"))?;
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
                    || admission::increment_context(text)?
                        != admission::increment_context(&head.request_text)?
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
        match broker.publish(&run, &prepared.records) {
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
                return self.increment_status_value(
                    &run,
                    "completed",
                    false,
                    (head.input_series.clone(), head.output_series.clone()),
                    Some(head.completion.receipt.clone()),
                    None,
                );
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
        self.increment_status_value(
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
        )
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

    pub fn handle(&mut self, request: &mut Request, broker: &mut impl Broker) -> (u16, Value) {
        let route = request.url().to_string();
        let method = request.method().clone();
        let body = match request_body(request) {
            Ok(body) => body,
            Err((status, error)) => return (status, json!({"error":error})),
        };
        if method == Method::Get && route == "/v1/capabilities" && body.is_empty() {
            return (200, self.capabilities());
        }
        if method == Method::Post && route == "/v1/prepare" {
            let text = match std::str::from_utf8(&body) {
                Ok(text) => text,
                Err(_) => return (400, json!({"error":"request is not UTF-8"})),
            };
            let result = self.prepare(text, broker);
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

    pub fn serve(mut self, listen: &str, mut broker: impl Broker) -> Result<(), String> {
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
        Err(error) => (
            if error.contains("busy") {
                409
            } else {
                error_status
            },
            json!({"error":error}),
        ),
    }
}

fn validate_journal(journal: &Journal, run: &Admitted) -> Result<(), String> {
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

fn request_body(request: &mut Request) -> Result<Vec<u8>, (u16, String)> {
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
    if length > REQUEST_BYTES {
        return Err((413, "request exceeds byte limit".into()));
    }
    let mut body = Vec::new();
    request
        .as_reader()
        .take(REQUEST_BYTES as u64 + 1)
        .read_to_end(&mut body)
        .map_err(|e| (400, format!("cannot read request: {e}")))?;
    if body.len() > REQUEST_BYTES {
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
    #[test]
    fn http_without_body_headers_has_zero_length() {
        assert_eq!(super::framed_length(None, &[]), Ok(0));
    }
}
