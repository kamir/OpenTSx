use crate::admission::{Admitted, MAX_EPISODE_SAMPLES};
use crate::broker::{Broker, OutputRecord};
use crate::checkpoint::{CompletedPosition, Completion, Receipt, Store};
use crate::wire;
use std::collections::BTreeMap;
use std::path::Path;

pub fn execute(
    mut run: Admitted,
    broker: &mut impl Broker,
    checkpoint: &Path,
) -> Result<Receipt, String> {
    let store = Store::open(checkpoint, run.request.limits.state_bytes + 65_536)?;
    if let Some(saved) = store.load()? {
        verify_completion(&run, &saved)?;
        return Ok(saved.receipt);
    }
    let mut records = broker.read(&run)?;
    if records.len() > run.request.limits.records {
        return Err("input record limit exceeded".into());
    }
    records.sort_by_key(|r| (r.partition, r.offset));
    let mut next: Vec<_> = run.cut.positions.iter().map(|p| p.from).collect();
    let mut owners = BTreeMap::new();
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
        artifact_digest: assignment_field(&run, "artifactDigest")?,
        deployment: assignment_field(&run, "deployment")?,
        generation: assignment_field(&run, "generation")?,
        assignment: assignment_field(&run, "assignment")?,
        node: assignment_field(&run, "node")?,
        input_topic: run.cut.topic.clone(),
        output_topic: run.request.sink.topic.clone(),
        positions: completed_positions(&run),
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
    store.validate(&completion)?;
    broker.publish(&run, &prepared)?;
    store.commit(&completion)?;
    Ok(receipt)
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

fn verify_completion(run: &Admitted, saved: &Completion) -> Result<(), String> {
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
