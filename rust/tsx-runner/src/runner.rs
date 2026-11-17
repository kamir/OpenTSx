use crate::admission::{Admitted, MAX_EPISODE_SAMPLES};
use crate::broker::{Broker, OutputRecord};
use crate::checkpoint::{CompletedPosition, Completion, Receipt, Store};
use crate::wire;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Prepared {
    pub completion: Completion,
    pub records: Vec<OutputRecord>,
    pub input_series: Vec<String>,
    pub output_series: Vec<String>,
    pub input_owners: BTreeMap<String, i32>,
}

pub fn execute(
    mut run: Admitted,
    broker: &mut impl Broker,
    checkpoint: &Path,
) -> Result<Receipt, String> {
    if run.increment.is_some() {
        return Err("increment requests require the worker's durable predecessor state".into());
    }
    let store = Store::open(checkpoint, run.request.limits.state_bytes + 65_536)?;
    if let Some(saved) = store.load()? {
        verify_completion(&run, &saved)?;
        return Ok(saved.receipt);
    }
    let prepared = prepare(&mut run, broker)?;
    store.validate(&prepared.completion)?;
    broker.publish(&run, &prepared.records)?;
    store.commit(&prepared.completion)?;
    Ok(prepared.completion.receipt)
}

pub fn prepare(run: &mut Admitted, broker: &mut impl Broker) -> Result<Prepared, String> {
    if run.increment.is_some() {
        return Err("increment requests require the worker's durable predecessor state".into());
    }
    prepare_continued(run, broker, BTreeMap::new())
}

pub(crate) fn prepare_continued(
    run: &mut Admitted,
    broker: &mut impl Broker,
    mut owners: BTreeMap<String, i32>,
) -> Result<Prepared, String> {
    let mut records = broker.read(run)?;
    if records.len() > run.request.limits.records {
        return Err("input record limit exceeded".into());
    }
    records.sort_by_key(|r| (r.partition, r.offset));
    let mut next: Vec<_> = run.cut.positions.iter().map(|p| p.from).collect();
    let mut episodes = Vec::with_capacity(records.len());
    let mut input_bytes = 0usize;
    let mut samples = 0usize;
    for record in &records {
        let partition =
            usize::try_from(record.partition).map_err(|_| "negative input partition")?;
        let position = run
            .cut
            .positions
            .get(partition)
            .ok_or("input record has an unassigned partition")?;
        if record.offset != next[partition] || record.offset >= position.to {
            return Err("input records do not exactly cover the admitted cut".into());
        }
        next[partition] = next[partition]
            .checked_add(1)
            .ok_or("input offset overflow")?;
        input_bytes = input_bytes
            .checked_add(record.payload.len())
            .ok_or("input byte count overflow")?;
        if input_bytes > run.request.limits.input_bytes {
            return Err("input byte limit exceeded".into());
        }
        let remaining = run
            .request
            .limits
            .samples
            .checked_sub(samples)
            .ok_or("input sample limit exceeded")?;
        let episode = wire::decode(
            &record.payload,
            run.request.limits.payload_bytes,
            remaining.min(MAX_EPISODE_SAMPLES),
        )?;
        if record.key != episode.series.series_id.as_bytes() {
            return Err("Kafka key does not match the decoded series identity".into());
        }
        if let Some(previous) = owners.insert(episode.series.series_id.clone(), record.partition) {
            if previous != record.partition {
                return Err("one input series occurs in multiple internal partitions".into());
            }
        }
        if owners.len() > run.request.limits.series {
            return Err("input series limit exceeded".into());
        }
        samples = samples
            .checked_add(episode.values.len())
            .ok_or("sample count overflow")?;
        episodes.push(episode);
    }
    if next.iter().zip(&run.cut.positions).any(|(n, p)| *n != p.to) {
        return Err("input records omit part of the whole-topic cut".into());
    }
    let output = run.pipeline.process(&episodes)?;
    let mut prepared = Vec::with_capacity(output.len());
    let mut output_bytes = 0usize;
    let mut digest_bytes = Vec::new();
    for episode in &output {
        let payload = wire::encode(episode)?;
        output_bytes = output_bytes
            .checked_add(payload.len())
            .ok_or("output byte count overflow")?;
        if payload.len() > run.request.limits.payload_bytes
            || output_bytes > run.request.limits.output_bytes
        {
            return Err("output byte limit exceeded before publication".into());
        }
        let timestamp = episode.t_start.div_euclid(1_000);
        if timestamp <= 0 {
            return Err("local Kafka replay requires positive millisecond timestamps".into());
        }
        let key = episode.series.series_id.clone();
        digest_bytes.extend_from_slice(&(key.len() as u64).to_be_bytes());
        digest_bytes.extend_from_slice(key.as_bytes());
        digest_bytes.extend_from_slice(&timestamp.to_be_bytes());
        digest_bytes.extend_from_slice(&(payload.len() as u64).to_be_bytes());
        digest_bytes.extend_from_slice(&payload);
        prepared.push(OutputRecord {
            key,
            timestamp,
            payload,
        });
    }
    let state = run.pipeline.snapshot()?;
    let state_bytes =
        serde_json::to_vec(&state).map_err(|e| format!("cannot encode retained state: {e}"))?;
    let receipt = Receipt {
        schema: "opentsx.local-receipt.v1".into(),
        request_hash: run.request_hash.clone(),
        artifact_digest: assignment_field(run, "artifactDigest")?,
        deployment: assignment_field(run, "deployment")?,
        generation: assignment_field(run, "generation")?,
        assignment: assignment_field(run, "assignment")?,
        node: assignment_field(run, "node")?,
        input_topic: run.cut.topic.clone(),
        output_topic: run.request.sink.topic.clone(),
        positions: completed_positions(run),
        input_records: records.len(),
        output_records: prepared.len(),
        output_digest: tsx_contracts::sha256_bytes(&digest_bytes),
        state_digest: tsx_contracts::sha256_bytes(&state_bytes),
    };
    let completion = Completion {
        request_hash: run.request_hash.clone(),
        receipt: receipt.clone(),
        state,
    };
    let mut output_series: BTreeSet<_> = prepared.iter().map(|r| r.key.clone()).collect();
    if let Some(pending) = completion.state["outputOwners"].as_object() {
        output_series.extend(pending.keys().cloned());
    }
    if run.pipeline.is_passthrough() {
        output_series.extend(
            owners
                .keys()
                .filter(|id| {
                    run.pipeline.expected_series().is_empty()
                        || run.pipeline.expected_series().contains(*id)
                })
                .cloned(),
        );
    }
    check_retained_owners(run, &completion, &owners)?;
    Ok(Prepared {
        completion,
        records: prepared,
        input_series: owners.keys().cloned().collect(),
        output_series: output_series.into_iter().collect(),
        input_owners: owners,
    })
}

pub fn activate(
    run: &Admitted,
    prepared: &Prepared,
    broker: &mut impl Broker,
    checkpoint: &Path,
) -> Result<Receipt, String> {
    if run.increment.is_some() {
        return Err("increment requests require the worker's durable predecessor state".into());
    }
    validate_prepared(run, prepared)?;
    let store = Store::open(checkpoint, run.request.limits.state_bytes + 65_536)?;
    if let Some(saved) = store.load()? {
        verify_completion(run, &saved)?;
        if saved.receipt != prepared.completion.receipt {
            return Err("checkpoint conflicts with prepared output".into());
        }
        return Ok(saved.receipt);
    }
    store.validate(&prepared.completion)?;
    broker.publish(run, &prepared.records)?;
    store.commit(&prepared.completion)?;
    Ok(prepared.completion.receipt.clone())
}

pub fn validate_prepared(run: &Admitted, prepared: &Prepared) -> Result<(), String> {
    verify_completion(run, &prepared.completion)?;
    if prepared.records.len() != prepared.completion.receipt.output_records
        || prepared.input_series.len() > run.request.limits.series
        || prepared.output_series.len() > run.request.limits.series
        || prepared.input_series != prepared.input_owners.keys().cloned().collect::<Vec<_>>()
        || !ordered_identities(&prepared.input_series)
        || !ordered_identities(&prepared.output_series)
    {
        return Err("invalid prepared series inventory or record count".into());
    }
    check_retained_owners(run, &prepared.completion, &prepared.input_owners)?;
    let mut bytes = 0usize;
    let mut framed = Vec::new();
    let mut output_series = BTreeSet::new();
    for record in &prepared.records {
        bytes = bytes
            .checked_add(record.payload.len())
            .ok_or("prepared size overflow")?;
        if bytes > run.request.limits.output_bytes {
            return Err("prepared output exceeds byte limit".into());
        }
        let episode = wire::decode(
            &record.payload,
            run.request.limits.payload_bytes,
            MAX_EPISODE_SAMPLES,
        )?;
        if record.key != episode.series.series_id
            || record.timestamp <= 0
            || record.timestamp != episode.t_start.div_euclid(1_000)
        {
            return Err("prepared output identity or timestamp mismatch".into());
        }
        output_series.insert(record.key.clone());
        framed.extend_from_slice(&(record.key.len() as u64).to_be_bytes());
        framed.extend_from_slice(record.key.as_bytes());
        framed.extend_from_slice(&record.timestamp.to_be_bytes());
        framed.extend_from_slice(&(record.payload.len() as u64).to_be_bytes());
        framed.extend_from_slice(&record.payload);
    }
    if let Some(pending) = prepared.completion.state["outputOwners"].as_object() {
        output_series.extend(pending.keys().cloned());
    }
    if run.pipeline.is_passthrough() {
        output_series.extend(
            prepared
                .input_owners
                .keys()
                .filter(|id| {
                    run.pipeline.expected_series().is_empty()
                        || run.pipeline.expected_series().contains(*id)
                })
                .cloned(),
        );
    }
    if tsx_contracts::sha256_bytes(&framed) != prepared.completion.receipt.output_digest
        || prepared.output_series != output_series.into_iter().collect::<Vec<_>>()
    {
        return Err("prepared output digest or inventory mismatch".into());
    }
    Ok(())
}

pub(crate) fn check_retained_owners(
    run: &Admitted,
    completion: &Completion,
    owners: &BTreeMap<String, i32>,
) -> Result<(), String> {
    if owners.len() > run.request.limits.series
        || !ordered_identities(&owners.keys().cloned().collect::<Vec<_>>())
        || owners.values().any(|partition| {
            usize::try_from(*partition)
                .ok()
                .is_none_or(|p| p >= run.cut.positions.len())
        })
        || completion.state["series"]
            .as_object()
            .is_none_or(|entries| entries.keys().any(|id| !owners.contains_key(id)))
    {
        return Err("invalid cumulative input ownership".into());
    }
    if run.increment.is_some() {
        let bytes = serde_json::to_vec(
            &serde_json::json!({"pipeline":completion.state,"inputOwners":owners}),
        )
        .map_err(|e| format!("cannot encode cumulative ownership: {e}"))?;
        if bytes.len() > run.request.limits.state_bytes {
            return Err("cumulative retained state exceeds stateBytes".into());
        }
    }
    Ok(())
}

pub(crate) fn retained_output_series(
    run: &Admitted,
    completion: &Completion,
    owners: &BTreeMap<String, i32>,
) -> Result<Vec<String>, String> {
    if run.pipeline.is_passthrough() {
        Ok(owners
            .keys()
            .filter(|id| {
                run.pipeline.expected_series().is_empty()
                    || run.pipeline.expected_series().contains(*id)
            })
            .cloned()
            .collect())
    } else {
        let entries = completion.state["outputOwners"]
            .as_object()
            .ok_or("retained output ownership is missing")?;
        Ok(entries.keys().cloned().collect())
    }
}

fn ordered_identities(ids: &[String]) -> bool {
    ids.iter().all(|id| {
        id.len() == 16
            && id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    }) && ids.windows(2).all(|pair| pair[0] < pair[1])
}

fn completed_positions(run: &Admitted) -> Vec<CompletedPosition> {
    run.cut
        .positions
        .iter()
        .map(|p| CompletedPosition {
            partition: p.partition.to_string(),
            next: p.to.to_string(),
        })
        .collect()
}

fn assignment_field(run: &Admitted, name: &str) -> Result<String, String> {
    run.assignment[name]
        .as_str()
        .map(str::to_string)
        .ok_or_else(|| format!("assignment field missing: {name}"))
}

pub(crate) fn verify_completion(run: &Admitted, saved: &Completion) -> Result<(), String> {
    if saved.request_hash != run.request_hash || saved.receipt.request_hash != run.request_hash {
        return Err("checkpoint conflicts with this exact local request".into());
    }
    let receipt = &saved.receipt;
    let input_records = run
        .cut
        .positions
        .iter()
        .try_fold(0usize, |total, position| {
            let count = usize::try_from(position.to - position.from)
                .map_err(|_| "input cut size overflow")?;
            total.checked_add(count).ok_or("input cut size overflow")
        })?;
    let output_count_valid = if run.pipeline.is_passthrough() {
        if run.pipeline.expected_series().is_empty() {
            receipt.output_records == input_records
        } else {
            receipt.output_records <= input_records
        }
    } else {
        receipt.output_records <= run.request.limits.samples
    };
    if receipt.schema != "opentsx.local-receipt.v1"
        || receipt.artifact_digest != assignment_field(run, "artifactDigest")?
        || receipt.deployment != assignment_field(run, "deployment")?
        || receipt.generation != assignment_field(run, "generation")?
        || receipt.assignment != assignment_field(run, "assignment")?
        || receipt.node != assignment_field(run, "node")?
        || receipt.input_topic != run.cut.topic
        || receipt.output_topic != run.request.sink.topic
        || receipt.positions != completed_positions(run)
        || receipt.input_records != input_records
        || !output_count_valid
    {
        return Err("checkpoint receipt does not match its assignment".into());
    }
    let bytes =
        serde_json::to_vec(&saved.state).map_err(|e| format!("cannot read retained state: {e}"))?;
    if tsx_contracts::sha256_bytes(&bytes) != receipt.state_digest {
        return Err("retained state digest mismatch".into());
    }
    let mut pipeline = run.pipeline.clone();
    pipeline.restore(&saved.state)?;
    Ok(())
}
