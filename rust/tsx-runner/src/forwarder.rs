use crate::admission::{Cut, Position};
use crate::checkpoint::Store;
use crate::federation::{self, FederationIo, KafkaFederationIo};
use crate::run_loop::RunError;
use crate::site_plan::{BoundedPlan, SiteConfig};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const SCHEMA: &str = "opentsx.rust.aggregate-forwarder-state.v1";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct State {
    identity: Value,
    topic: String,
    high: BTreeMap<i32, i64>,
    next: BTreeMap<i32, i64>,
    seen: BTreeSet<String>,
    completed: bool,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardOutcome {
    pub state: &'static str,
    pub completed: bool,
    pub forwarded_records: usize,
    pub error: Option<String>,
}

pub fn forward(plan: &BoundedPlan, config: &SiteConfig) -> Result<ForwardOutcome, RunError> {
    guarded_forward(plan, config, &mut || Ok(()))
}

pub fn forward_with_after_ack(
    plan: &BoundedPlan,
    config: &SiteConfig,
    mut after_ack: impl FnMut() -> Result<(), String>,
) -> Result<ForwardOutcome, RunError> {
    guarded_forward(plan, config, &mut after_ack)
}

fn guarded_forward(
    plan: &BoundedPlan,
    config: &SiteConfig,
    after_ack: &mut impl FnMut() -> Result<(), String>,
) -> Result<ForwardOutcome, RunError> {
    plan.assert_config(config)?;
    let output = federation::output(plan)?;
    let source = config.binding("site")?;
    let _reservation =
        crate::run_reservation::acquire(plan.job(), &source.bootstrap, &output.topic)?;
    forward_with(plan, config, &mut KafkaFederationIo, after_ack).map_err(RunError::Runtime)
}

pub(crate) fn forward_with(
    plan: &BoundedPlan,
    config: &SiteConfig,
    io: &mut impl FederationIo,
    after_ack: &mut impl FnMut() -> Result<(), String>,
) -> Result<ForwardOutcome, String> {
    plan.assert_config(config).map_err(|e| e.to_string())?;
    let execution = federation::completed_state(plan, config)?;
    let expected: BTreeMap<_, _> = execution
        .records
        .iter()
        .map(|record| (record.key.clone(), record))
        .collect();
    let output = federation::output(plan)?;
    validate_topic(&output.topic)?;
    let source = config.binding("site").map_err(|e| e.to_string())?;
    let target = config
        .binding(plan.signal_binding())
        .map_err(|e| e.to_string())?;
    if source.bootstrap == target.bootstrap {
        return Err("forwarder requires distinct site and signal endpoints".into());
    }
    let identity = json!({"authority":plan.checkpoint_identity(),"configuration":config.identity_hash(),"topic":output.topic,"source":source.bootstrap,"target":target.bootstrap,"partitionCount":output.partitions});
    let store = Store::open(
        &config
            .directory()
            .join(format!("{}-{}-forward.json", plan.job(), plan.version())),
        config
            .limits()
            .state_bytes
            .checked_add(65_536)
            .ok_or("forwarder checkpoint bound overflow")?,
    )?;
    let mut state = if let Some(value) = store.load_document(SCHEMA)? {
        let state: State = serde_json::from_value(value)
            .map_err(|e| format!("invalid forwarder checkpoint: {e}"))?;
        validate_state(
            &state,
            &identity,
            &output.topic,
            output.partitions,
            expected.keys().map(String::as_str),
            config.limits().state_bytes,
        )?;
        state
    } else {
        let watermarks = io.watermarks(&source.bootstrap, &output.topic, output.partitions)?;
        if watermarks.len() != output.partitions {
            return Err("forwarder source partition inventory changed".into());
        }
        let mut high = BTreeMap::new();
        for (index, position) in watermarks.iter().enumerate() {
            if position.partition != index as i32 || position.low != 0 || position.high < 0 {
                return Err("forwarder source outbox has lost its admitted retained log".into());
            }
            high.insert(position.partition, position.high);
        }
        if high.values().all(|n| *n == 0) && !expected.is_empty() {
            return Err("forwarder site outbox omits acknowledged partials".into());
        }
        let state = State {
            identity: identity.clone(),
            topic: output.topic.clone(),
            next: high.keys().map(|p| (*p, 0)).collect(),
            high,
            seen: BTreeSet::new(),
            completed: false,
        };
        save(&store, &state, config.limits().state_bytes)?;
        state
    };
    if state.completed {
        return Ok(done(0));
    }
    let mut forwarded = 0usize;
    while state
        .next
        .iter()
        .any(|(partition, next)| *next < state.high[partition])
    {
        let watermarks = io.watermarks(&source.bootstrap, &output.topic, output.partitions)?;
        if watermarks.len() != output.partitions {
            return Err("forwarder source partition inventory changed".into());
        }
        for (index, position) in watermarks.iter().enumerate() {
            if position.partition != index as i32
                || position.low > state.next[&position.partition]
                || position.high != state.high[&position.partition]
            {
                return Err(
                    "forwarder source position is outside the immutable retained outbox".into(),
                );
            }
        }
        let cut = Cut {
            topic: output.topic.clone(),
            positions: state
                .next
                .iter()
                .map(|(partition, next)| Position {
                    partition: *partition,
                    from: *next,
                    to: state.high[partition],
                })
                .collect(),
        };
        let mut limits = config.limits().clone();
        limits.records = limits.records.min(256);
        let records = io.read(&source.bootstrap, &cut, &limits)?;
        if records.is_empty() {
            return Err("forwarder source ended before its retained outbox cut".into());
        }
        let mut proposed = state.clone();
        let mut batch = Vec::new();
        let mut bytes = 0usize;
        for input in records {
            let next = proposed
                .next
                .get_mut(&input.partition)
                .ok_or("forwarder read an undeclared partition")?;
            if input.offset != *next || input.offset >= proposed.high[&input.partition] {
                return Err("forwarder source offsets are missing or reordered".into());
            }
            federation::validate_partial(plan, &input.record)?;
            if expected.get(&input.record.key).copied() != Some(&input.record) {
                return Err(
                    "forwarder source partial differs from the acknowledged bounded result".into(),
                );
            }
            bytes = bytes
                .checked_add(input.record.payload.len())
                .ok_or("forwarder batch byte overflow")?;
            if bytes > limits.output_bytes || batch.len() >= limits.records {
                return Err("forwarder source exceeded its admitted output batch".into());
            }
            *next = next.checked_add(1).ok_or("forwarder cursor overflow")?;
            proposed.seen.insert(input.record.key.clone());
            batch.push(input.record);
        }
        if let Err(error) = io
            .publish(&target.bootstrap, &output.topic, output.partitions, &batch)
            .and_then(|()| after_ack())
        {
            return Ok(ForwardOutcome {
                state: "waiting",
                completed: false,
                forwarded_records: forwarded,
                error: Some(error),
            });
        }
        forwarded = forwarded
            .checked_add(batch.len())
            .ok_or("forwarder record count overflow")?;
        state = proposed;
        save(&store, &state, config.limits().state_bytes)?;
    }
    if state.seen != expected.keys().cloned().collect() {
        return Err("forwarder completed cut is missing signed window partials".into());
    }
    state.completed = true;
    save(&store, &state, config.limits().state_bytes)?;
    Ok(done(forwarded))
}

fn done(records: usize) -> ForwardOutcome {
    ForwardOutcome {
        state: "completed",
        completed: true,
        forwarded_records: records,
        error: None,
    }
}

fn save(store: &Store, state: &State, limit: usize) -> Result<(), String> {
    let value = serde_json::to_value(state)
        .map_err(|e| format!("cannot encode forwarder checkpoint: {e}"))?;
    if serde_json::to_vec(&value)
        .map_err(|e| format!("cannot measure forwarder checkpoint: {e}"))?
        .len()
        > limit
    {
        return Err("forwarder checkpoint exceeds its admitted state limit".into());
    }
    store.commit_document(SCHEMA, &value)
}

fn validate_state<'a>(
    state: &State,
    identity: &Value,
    topic: &str,
    partitions: usize,
    expected: impl Iterator<Item = &'a str>,
    limit: usize,
) -> Result<(), String> {
    if &state.identity != identity
        || state.topic != topic
        || state.high.len() != partitions
        || state.next.len() != partitions
    {
        return Err("forwarder checkpoint authority or topic inventory changed".into());
    }
    for partition in 0..partitions as i32 {
        let high = *state
            .high
            .get(&partition)
            .ok_or("forwarder high-watermark inventory is incomplete")?;
        let next = *state
            .next
            .get(&partition)
            .ok_or("forwarder cursor inventory is incomplete")?;
        if high < 0 || next < 0 || next > high {
            return Err("forwarder checkpoint position is invalid".into());
        }
    }
    let expected: BTreeSet<_> = expected.map(str::to_string).collect();
    if !state.seen.is_subset(&expected)
        || state.completed && (state.seen != expected || state.next != state.high)
    {
        return Err(
            "forwarder checkpoint completion does not cover its exact partial inventory".into(),
        );
    }
    if serde_json::to_vec(state)
        .map_err(|e| format!("cannot measure forwarder state: {e}"))?
        .len()
        > limit
    {
        return Err("forwarder checkpoint exceeds its admitted state limit".into());
    }
    Ok(())
}

fn validate_topic(topic: &str) -> Result<(), String> {
    if !tsx_contracts::valid_topic(topic)
        || !(topic.ends_with(".aggregates.v2") || topic.ends_with(".signals.v1"))
    {
        return Err("forwarder permits only admitted aggregate and signal topics".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_and_unversioned_topics_cannot_leave_a_site() {
        assert!(validate_topic("tsx.test.aggregates.v2").is_ok());
        assert!(validate_topic("tsx.test.signals.v1").is_ok());
        for topic in [
            "tsx.test.episodes.v2",
            "tsx.test.clean.v2",
            "tsx.test.series-stats.v2",
            "tsx.test.aggregates.v1",
            "../aggregates.v2",
        ] {
            assert!(validate_topic(topic).is_err(), "{topic}");
        }
    }

    #[test]
    fn restored_forwarder_cannot_skip_missing_windows_or_switch_authority() {
        let mut state = State {
            identity: json!({"job":"test"}),
            topic: "tsx.test.aggregates.v2".into(),
            high: BTreeMap::from([(0, 2)]),
            next: BTreeMap::from([(0, 1)]),
            seen: BTreeSet::from(["one".into()]),
            completed: false,
        };
        assert!(validate_state(
            &state,
            &state.identity,
            &state.topic,
            1,
            ["one", "two"].into_iter(),
            4096
        )
        .is_ok());
        state.completed = true;
        assert!(validate_state(
            &state,
            &state.identity,
            &state.topic,
            1,
            ["one", "two"].into_iter(),
            4096
        )
        .is_err());
        state.completed = false;
        assert!(validate_state(
            &state,
            &json!({"job":"other"}),
            &state.topic,
            1,
            ["one", "two"].into_iter(),
            4096
        )
        .is_err());
        state.next.insert(0, 3);
        assert!(validate_state(
            &state,
            &state.identity,
            &state.topic,
            1,
            ["one", "two"].into_iter(),
            4096
        )
        .is_err());
    }
}
