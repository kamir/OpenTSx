use crate::admission::{Cut, Limits, Position, MAX_EPISODE_SAMPLES};
use crate::broker::{self, FederationInputRecord, KafkaBroker};
use crate::catalog::{Context, Pipeline};
use crate::checkpoint::Store;
use crate::model::{series_id, Episode};
use crate::operators::Limits as OperatorLimits;
use crate::run_loop::{RunBroker, RunError, StopToken, Watermark};
use crate::site_plan::{BoundedPlan, SiteConfig};
use crate::stats::{StatsAccumulator, StatsRecord};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

pub use crate::broker::FederationRecord;

pub(crate) const EXECUTION_SCHEMA: &str = "opentsx.rust.bounded-federation-state.v1";
const PARTIAL_HEADERS: [&str; 11] = [
    "tsx-schema",
    "tsx-job",
    "tsx-site",
    "tsx-plan-version",
    "tsx-revision",
    "tsx-generation",
    "tsx-assignment",
    "tsx-fence",
    "tsx-spec-hash",
    "tsx-fragment-hash",
    "tsx-evidence",
];

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ExecutionState {
    identity: Value,
    phase: String,
    next: BTreeMap<i32, i64>,
    owners: BTreeMap<String, i32>,
    groups: BTreeMap<String, String>,
    pipeline: Value,
    statistics: BTreeMap<String, Vec<u8>>,
    pub(crate) records: Vec<FederationRecord>,
    input_records: usize,
    increments: usize,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FederationOutcome {
    pub state: &'static str,
    pub completed: bool,
    pub input_records: usize,
    pub output_records: usize,
    pub increments: usize,
}

pub trait FederationIo {
    fn read(
        &mut self,
        bootstrap: &str,
        cut: &Cut,
        limits: &Limits,
    ) -> Result<Vec<FederationInputRecord>, String>;
    fn watermarks(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
    ) -> Result<Vec<Watermark>, String>;
    fn publish(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
        records: &[FederationRecord],
    ) -> Result<(), String>;
}

pub(crate) struct KafkaFederationIo;

impl FederationIo for KafkaFederationIo {
    fn read(
        &mut self,
        bootstrap: &str,
        cut: &Cut,
        limits: &Limits,
    ) -> Result<Vec<FederationInputRecord>, String> {
        broker::read_federation_prefix(bootstrap, cut, limits)
    }

    fn watermarks(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
    ) -> Result<Vec<Watermark>, String> {
        KafkaBroker.watermarks(bootstrap, topic, partitions)
    }

    fn publish(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
        records: &[FederationRecord],
    ) -> Result<(), String> {
        broker::publish_federation_records(bootstrap, topic, partitions, records)
    }
}

pub fn execute(
    plan: &BoundedPlan,
    config: &SiteConfig,
    stop: &StopToken,
) -> Result<FederationOutcome, RunError> {
    plan.assert_config(config)?;
    let cut = input_cut(plan)?;
    let source = config.binding(plan.local_binding())?;
    let _reservation = crate::run_reservation::acquire(plan.job(), &source.bootstrap, &cut.topic)?;
    execute_with(plan, config, stop, &mut KafkaFederationIo)
}

pub(crate) fn execute_with(
    plan: &BoundedPlan,
    config: &SiteConfig,
    stop: &StopToken,
    io: &mut impl FederationIo,
) -> Result<FederationOutcome, RunError> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| "system clock is before the Unix epoch")?
        .as_micros();
    let now = i64::try_from(now).map_err(|_| "system clock exceeds supported microseconds")?;
    execute_at(plan, config, stop, io, now)
}

fn execute_at(
    plan: &BoundedPlan,
    config: &SiteConfig,
    stop: &StopToken,
    io: &mut impl FederationIo,
    now: i64,
) -> Result<FederationOutcome, RunError> {
    plan.assert_config(config)?;
    let limits = config.limits();
    let cut = input_cut(plan)?;
    let source = config
        .binding(plan.local_binding())
        .map_err(|e| e.to_string())?;
    let target = output(plan)?;
    let site = config.binding("site").map_err(|e| e.to_string())?;
    let context = Context::default();
    let mut pipeline = Pipeline::compile(
        plan.fragment(),
        field(plan.document(), "specHash")?,
        plan.created_at_micros(),
        OperatorLimits {
            max_series: limits.series,
            max_samples: limits.samples,
            max_output_samples: limits.samples,
            max_state_bytes: limits.state_bytes,
        },
        Some(&context),
        config.site(),
    )?;
    if pipeline.schema_name() != "org.opentsx.model.v2.SeriesStats" {
        return Err("bounded federation requires SeriesStats output".into());
    }
    let store = execution_store(plan, config)?;
    let mut state = if let Some(value) = store.load_document(EXECUTION_SCHEMA)? {
        let state: ExecutionState = serde_json::from_value(value)
            .map_err(|e| format!("invalid bounded federation checkpoint: {e}"))?;
        validate_state(plan, config, &cut, &state)?;
        pipeline.restore(&state.pipeline)?;
        state
    } else {
        plan.activate_at(now)?;
        let state = ExecutionState {
            identity: execution_identity(plan, config),
            phase: "processing".into(),
            next: cut
                .positions
                .iter()
                .map(|p| (p.partition, p.from))
                .collect(),
            owners: BTreeMap::new(),
            groups: BTreeMap::new(),
            pipeline: pipeline.snapshot()?,
            statistics: BTreeMap::new(),
            records: Vec::new(),
            input_records: 0,
            increments: 0,
        };
        save(&store, &state)?;
        state
    };
    if state.phase == "published" {
        return Ok(outcome(&state, true));
    }
    while state.phase == "processing" {
        if stop.is_requested()? {
            return Ok(outcome(&state, false));
        }
        let remaining = remaining_cut(&cut, &state.next)?;
        if remaining.positions.iter().all(|p| p.from == p.to) {
            stage_statistics(&mut state, pipeline.process_records(&[], true)?, limits)?;
            state.pipeline = pipeline.snapshot()?;
            state.records = grouped_partials(plan, &state)?;
            state.statistics.clear();
            state.phase = "ready".into();
            validate_state(plan, config, &cut, &state)?;
            save(&store, &state)?;
            break;
        }
        let mut batch_limits = limits.clone();
        batch_limits.records = batch_limits.records.min(100);
        let records = io.read(&source.bootstrap, &remaining, &batch_limits)?;
        if records.is_empty() {
            return Err("bounded federation input ended before the admitted cut".into());
        }
        let mut episodes = Vec::new();
        let mut samples = 0usize;
        let mut bytes = 0usize;
        for record in records {
            let position = remaining
                .positions
                .iter()
                .find(|p| p.partition == record.partition)
                .ok_or("federation record partition is not admitted")?;
            let next = state
                .next
                .get_mut(&record.partition)
                .ok_or("federation cursor is missing")?;
            if record.offset != *next || record.offset >= position.to {
                return Err("federation records do not exactly extend the admitted cut".into());
            }
            let episode = decode_episode(plan, &record.record, limits)?;
            let count = episode.values.len();
            if samples
                .checked_add(count)
                .ok_or("federation sample count overflow")?
                > limits.samples
            {
                if episodes.is_empty() {
                    return Err("federation episode exceeds the admitted sample limit".into());
                }
                break;
            }
            samples += count;
            bytes = bytes
                .checked_add(record.record.payload.len())
                .ok_or("federation byte count overflow")?;
            if bytes > limits.input_bytes || episodes.len() >= batch_limits.records {
                return Err("federation reader exceeded its bounded prefix".into());
            }
            let id = &episode.series.series_id;
            if state
                .owners
                .insert(id.clone(), record.partition)
                .is_some_and(|p| p != record.partition)
            {
                return Err("a federation source series changed its internal partition".into());
            }
            let group = group_id(plan, &episode)?;
            if state
                .groups
                .insert(id.clone(), group.clone())
                .is_some_and(|old| old != group)
            {
                return Err("a federation source series changed its group".into());
            }
            if state.owners.len() > limits.series {
                return Err("federation source series limit exceeded".into());
            }
            *next = next.checked_add(1).ok_or("federation cursor overflow")?;
            state.input_records = state
                .input_records
                .checked_add(1)
                .ok_or("federation input count overflow")?;
            episodes.push(episode);
        }
        stage_statistics(
            &mut state,
            pipeline.process_records(&episodes, false)?,
            limits,
        )?;
        state.pipeline = pipeline.snapshot()?;
        state.increments = state
            .increments
            .checked_add(1)
            .ok_or("federation increment count overflow")?;
        validate_state(plan, config, &cut, &state)?;
        save(&store, &state)?;
    }
    if stop.is_requested()? {
        return Ok(outcome(&state, false));
    }
    io.publish(
        &site.bootstrap,
        &target.topic,
        target.partitions,
        &state.records,
    )?;
    state.phase = "published".into();
    save(&store, &state)?;
    Ok(outcome(&state, true))
}

fn outcome(state: &ExecutionState, completed: bool) -> FederationOutcome {
    FederationOutcome {
        state: if completed { "completed" } else { "stopped" },
        completed,
        input_records: state.input_records,
        output_records: state.records.len(),
        increments: state.increments,
    }
}

pub(crate) struct Output {
    pub(crate) topic: String,
    pub(crate) partitions: usize,
}

pub(crate) fn output(plan: &BoundedPlan) -> Result<Output, String> {
    let outputs = array(plan.document(), "outputs")?;
    if outputs.len() != 1 || outputs[0]["cluster"] != "site" {
        return Err("bounded federation requires one site output".into());
    }
    let topic = field(&outputs[0], "topic")?;
    if !topic.ends_with(".aggregates.v2") || !tsx_contracts::valid_topic(topic) {
        return Err("bounded federation output must be an aggregate topic".into());
    }
    let count = decimal(field(&outputs[0], "partitionCount")?)?;
    if count == 0 || count > 128 {
        return Err("invalid federation output partition count".into());
    }
    Ok(Output {
        topic: topic.into(),
        partitions: count as usize,
    })
}

pub(crate) fn input_cut(plan: &BoundedPlan) -> Result<Cut, String> {
    let inputs = array(plan.assignment(), "inputs")?;
    if inputs.len() != 1 {
        return Err("bounded federation requires one whole input topic".into());
    }
    let mut positions = Vec::new();
    for (index, value) in array(&inputs[0], "positions")?.iter().enumerate() {
        let partition = decimal(field(value, "partition")?)?;
        let from = decimal(field(value, "from")?)?;
        let to = decimal(field(value, "to")?)?;
        if partition != index as u64 || from > to || to > i64::MAX as u64 {
            return Err("invalid bounded federation cut".into());
        }
        positions.push(Position {
            partition: index as i32,
            from: from as i64,
            to: to as i64,
        });
    }
    if positions.is_empty() || positions.len() > 128 {
        return Err("invalid bounded federation partition inventory".into());
    }
    Ok(Cut {
        topic: field(&inputs[0], "topic")?.into(),
        positions,
    })
}

pub(crate) fn remaining_cut(cut: &Cut, next: &BTreeMap<i32, i64>) -> Result<Cut, String> {
    if next.len() != cut.positions.len() {
        return Err("federation cursor inventory changed".into());
    }
    let positions = cut
        .positions
        .iter()
        .map(|p| {
            let from = *next
                .get(&p.partition)
                .ok_or("federation partition cursor is missing")?;
            if from < p.from || from > p.to {
                return Err("federation cursor is outside its admitted cut".into());
            }
            Ok(Position {
                partition: p.partition,
                from,
                to: p.to,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;
    Ok(Cut {
        topic: cut.topic.clone(),
        positions,
    })
}

pub(crate) fn execution_store(plan: &BoundedPlan, config: &SiteConfig) -> Result<Store, String> {
    let name = format!("{}-{}-federation.json", plan.job(), plan.version());
    Store::open(
        &config.directory().join(name),
        journal_limit(config.limits())?,
    )
}

pub(crate) fn journal_limit(limits: &Limits) -> Result<usize, String> {
    limits
        .output_bytes
        .checked_mul(5)
        .and_then(|n| n.checked_add(limits.state_bytes))
        .and_then(|n| n.checked_add(262_144))
        .ok_or_else(|| "federation journal size overflow".into())
}

fn save(store: &Store, state: &ExecutionState) -> Result<(), String> {
    store.commit_document(
        EXECUTION_SCHEMA,
        &serde_json::to_value(state)
            .map_err(|e| format!("cannot encode federation checkpoint: {e}"))?,
    )
}

pub(crate) fn completed_state(
    plan: &BoundedPlan,
    config: &SiteConfig,
) -> Result<ExecutionState, String> {
    let store = execution_store(plan, config)?;
    let value = store
        .load_document(EXECUTION_SCHEMA)?
        .ok_or("site federation work has not completed")?;
    let state: ExecutionState =
        serde_json::from_value(value).map_err(|e| format!("invalid federation checkpoint: {e}"))?;
    validate_state(plan, config, &input_cut(plan)?, &state)?;
    if state.phase != "published" {
        return Err("site federation work has not been acknowledged".into());
    }
    Ok(state)
}

fn validate_state(
    plan: &BoundedPlan,
    config: &SiteConfig,
    cut: &Cut,
    state: &ExecutionState,
) -> Result<(), String> {
    if state.identity != execution_identity(plan, config)
        || !["processing", "ready", "published"].contains(&state.phase.as_str())
    {
        return Err("federation checkpoint authority changed".into());
    }
    remaining_cut(cut, &state.next)?;
    let consumed = cut.positions.iter().try_fold(0usize, |n, p| {
        n.checked_add(
            usize::try_from(state.next[&p.partition] - p.from)
                .map_err(|_| "invalid federation consumed count")?,
        )
        .ok_or("federation consumed count overflow")
    })?;
    if consumed != state.input_records
        || state.owners.len() != state.groups.len()
        || state.owners.len() > config.limits().series
    {
        return Err("federation checkpoint inventory does not match its cursor".into());
    }
    for (id, partition) in &state.owners {
        if !hex_id(id)
            || !state.groups.get(id).is_some_and(|g| hex_id(g))
            || !state.next.contains_key(partition)
        {
            return Err("invalid federation checkpoint source ownership".into());
        }
    }
    let retained = serde_json::to_vec(
        &json!({"pipeline":state.pipeline,"owners":state.owners,"groups":state.groups}),
    )
    .map_err(|e| format!("cannot measure federation state: {e}"))?;
    if retained.len() > config.limits().state_bytes {
        return Err("federation operator state exceeds its admitted limit".into());
    }
    let expected_scopes = scopes(plan)?;
    let mut bytes = 0usize;
    for (key, value) in &state.statistics {
        let statistics = StatsRecord::decode_soe(value, config.limits().payload_bytes)?;
        if !state.groups.contains_key(&statistics.series_id) || *key != statistic_key(&statistics) {
            return Err("federation checkpoint contains an unknown source statistic".into());
        }
        let scope = Scope {
            group: state.groups[&statistics.series_id].clone(),
            kind: statistics.window_type,
            from: statistics.window_start_micros,
            to: statistics.window_end_micros,
        };
        if !expected_scopes.contains_key(&scope.key()) {
            return Err("staged federation statistic is outside the signed scopes".into());
        }
        bytes = bytes
            .checked_add(value.len())
            .ok_or("federation staged bytes overflow")?;
    }
    let mut identities = BTreeSet::new();
    for record in &state.records {
        validate_partial(plan, record)?;
        if !identities.insert(&record.key) {
            return Err("federation checkpoint repeats a completed scope".into());
        }
        bytes = bytes
            .checked_add(record.payload.len())
            .ok_or("federation staged bytes overflow")?;
    }
    if bytes > config.limits().output_bytes {
        return Err("federation staged output exceeds its admitted limit".into());
    }
    let full = cut
        .positions
        .iter()
        .all(|p| state.next[&p.partition] == p.to);
    if state.phase != "processing" && (!full || state.records.len() != expected_scopes.len()) {
        return Err(
            "federation completion omits part of its signed source cut or scope inventory".into(),
        );
    }
    if state.phase == "processing" && !state.records.is_empty() {
        return Err("federation checkpoint publishes premature evidence".into());
    }
    Ok(())
}

fn execution_identity(plan: &BoundedPlan, config: &SiteConfig) -> Value {
    json!({"continuation":plan.checkpoint_identity(),"configuration":config.identity_hash()})
}

fn decode_episode(
    plan: &BoundedPlan,
    record: &FederationRecord,
    limits: &Limits,
) -> Result<Episode, String> {
    let schema = headers(record)?;
    if schema.get("tsx-schema").map(String::as_str) != Some(crate::wire::SCHEMA_NAME) {
        return Err("federation input schema header differs from its contract".into());
    }
    let episode = crate::wire::decode(
        &record.payload,
        limits.payload_bytes,
        MAX_EPISODE_SAMPLES.min(limits.samples),
    )?;
    if record.key != episode.series.series_id {
        return Err("federation Kafka key differs from its Episode series".into());
    }
    let selected = plan.fragment()["source"]
        .get("series")
        .and_then(Value::as_array);
    if selected.is_some_and(|ids| {
        !ids.iter()
            .any(|id| id.as_str() == Some(&episode.series.series_id))
    }) {
        return Err("federation input series is outside its signed source inventory".into());
    }
    Ok(episode)
}

fn group_id(plan: &BoundedPlan, episode: &Episode) -> Result<String, String> {
    let mut tags = BTreeMap::new();
    for name in array(&plan.document()["federation"], "groupBy")? {
        let name = name.as_str().ok_or("federation group tag must be text")?;
        if let Some(value) = episode.series.tags.get(name) {
            tags.insert(name.into(), value.clone());
        }
    }
    series_id(&episode.series.metric, &tags)
}

fn stage_statistics(
    state: &mut ExecutionState,
    records: Vec<crate::broker::OutputRecord>,
    limits: &Limits,
) -> Result<(), String> {
    for record in records {
        let statistics = StatsRecord::decode_soe(&record.payload, limits.payload_bytes)?;
        if statistics.window_type == "GLOBAL" {
            continue;
        }
        let key = statistic_key(&statistics);
        if let Some(previous) = state.statistics.insert(key, record.payload.clone()) {
            if previous != record.payload {
                return Err(
                    "federation source statistic changed under one closed-window identity".into(),
                );
            }
        }
    }
    Ok(())
}

fn statistic_key(statistics: &StatsRecord) -> String {
    format!(
        "{}|{}|{}|{}",
        statistics.series_id,
        statistics.window_type,
        statistics.window_start_micros,
        statistics.window_end_micros
    )
}

#[derive(Clone, Debug)]
struct Scope {
    group: String,
    kind: String,
    from: i64,
    to: i64,
}

impl Scope {
    fn key(&self) -> String {
        format!("{}|{}|{}|{}", self.group, self.kind, self.from, self.to)
    }
}

fn scopes(plan: &BoundedPlan) -> Result<BTreeMap<String, Scope>, String> {
    let mut out = BTreeMap::new();
    for value in array(&plan.document()["federation"], "scopes")? {
        let scope = Scope {
            group: field(value, "groupId")?.into(),
            kind: field(value, "windowType")?.into(),
            from: signed_decimal(field(value, "startMicros")?)?,
            to: signed_decimal(field(value, "endMicros")?)?,
        };
        if !hex_id(&scope.group)
            || scope.from >= scope.to
            || scope.kind == "GLOBAL"
            || out.insert(scope.key(), scope).is_some()
        {
            return Err("invalid or duplicate signed federation scope".into());
        }
    }
    if out.is_empty() || out.len() > 1024 {
        return Err("signed federation scope inventory exceeds its bound".into());
    }
    Ok(out)
}

fn grouped_partials(
    plan: &BoundedPlan,
    state: &ExecutionState,
) -> Result<Vec<FederationRecord>, String> {
    let scopes = scopes(plan)?;
    let statistics_node = array(plan.fragment(), "nodes")?
        .iter()
        .find(|node| node["op"] == "stats.welford-ddsketch")
        .ok_or("federation statistics node is missing")?;
    let alpha = statistics_node["params"]
        .get("alpha")
        .map_or(Ok(0.01), |v| {
            v.as_f64().ok_or("statistics alpha must be numeric")
        })?;
    let mut grouped: BTreeMap<String, BTreeMap<String, StatsRecord>> = BTreeMap::new();
    for bytes in state.statistics.values() {
        let statistics = StatsRecord::decode_soe(bytes, 1_048_576)?;
        let group = state
            .groups
            .get(&statistics.series_id)
            .ok_or("federation grouping is missing")?;
        let scope = Scope {
            group: group.clone(),
            kind: statistics.window_type.clone(),
            from: statistics.window_start_micros,
            to: statistics.window_end_micros,
        };
        if !scopes.contains_key(&scope.key()) {
            return Err("federation statistic is outside its signed scopes".into());
        }
        if grouped
            .entry(scope.key())
            .or_default()
            .insert(statistics.series_id.clone(), statistics)
            .is_some()
        {
            return Err("duplicate federation source-window statistic".into());
        }
    }
    scopes
        .into_values()
        .map(|scope| {
            let mut combined = StatsAccumulator::new(alpha)?;
            if let Some(raw) = grouped.remove(&scope.key()) {
                for value in raw.into_values() {
                    let other = StatsAccumulator {
                        valid_count: value.valid_count,
                        nan_count: value.nan_count,
                        min: value.min,
                        max: value.max,
                        mean: if value.valid_count == 0 {
                            0.0
                        } else {
                            value.mean
                        },
                        m2: if value.valid_count == 0 {
                            0.0
                        } else {
                            value.m2
                        },
                        gap_count: value.gap_count,
                        first_micros: i64::MAX,
                        last_micros: value.last_seen_micros.unwrap_or(i64::MIN),
                        sketch: value.sketch,
                    };
                    combined.merge(&other)?;
                }
            }
            partial(
                plan,
                &combined.to_record(&scope.group, &scope.kind, scope.from, scope.to),
            )
        })
        .collect()
}

fn partial(plan: &BoundedPlan, statistics: &StatsRecord) -> Result<FederationRecord, String> {
    let assignment = plan.assignment();
    let payload = statistics.encode_soe()?;
    let evidence = json!({"schema":"opentsx.aggregate-evidence.v1","kind":"bounded-source-cut","job":plan.job(),
        "site":field(assignment,"site")?,"planVersion":plan.version(),"generation":field(assignment,"generation")?,
        "assignment":field(assignment,"assignment")?,"fence":field(assignment,"fence")?,"specHash":field(assignment,"specHash")?,
        "fragmentHash":field(assignment,"fragmentHash")?,"inputs":assignment["inputs"],"payloadSha256":tsx_contracts::sha256_bytes(&payload)});
    let mut headers = BTreeMap::new();
    for (name, value) in [
        ("tsx-schema", "org.opentsx.model.v2.SeriesStats".to_string()),
        ("tsx-job", plan.job().to_owned()),
        ("tsx-site", field(assignment, "site")?.into()),
        ("tsx-plan-version", plan.version().to_string()),
        ("tsx-revision", "0".into()),
        ("tsx-generation", field(assignment, "generation")?.into()),
        ("tsx-assignment", field(assignment, "assignment")?.into()),
        ("tsx-fence", field(assignment, "fence")?.into()),
        ("tsx-spec-hash", field(assignment, "specHash")?.into()),
        (
            "tsx-fragment-hash",
            field(assignment, "fragmentHash")?.into(),
        ),
        ("tsx-evidence", evidence.to_string()),
    ] {
        headers.insert(name.to_string(), value.into_bytes());
    }
    let record = FederationRecord {
        key: format!(
            "{}|{}|{}|{}|{}",
            plan.job(),
            field(assignment, "site")?,
            statistics.series_id,
            statistics.window_type,
            statistics.window_start_micros
        ),
        timestamp: statistics.window_end_micros.div_euclid(1000),
        payload,
        headers: headers.into_iter().collect(),
    };
    validate_partial(plan, &record)?;
    Ok(record)
}

pub(crate) fn validate_partial(
    plan: &BoundedPlan,
    record: &FederationRecord,
) -> Result<(), String> {
    let headers = headers(record)?;
    if headers.keys().map(String::as_str).collect::<BTreeSet<_>>()
        != PARTIAL_HEADERS.into_iter().collect()
    {
        return Err("federation partial header set does not match its contract".into());
    }
    let statistics = StatsRecord::decode_soe(&record.payload, 1_048_576)?;
    let assignment = plan.assignment();
    for (header, value) in [
        ("tsx-schema", "org.opentsx.model.v2.SeriesStats"),
        ("tsx-job", plan.job()),
        ("tsx-site", field(assignment, "site")?),
        ("tsx-generation", field(assignment, "generation")?),
        ("tsx-assignment", field(assignment, "assignment")?),
        ("tsx-fence", field(assignment, "fence")?),
        ("tsx-spec-hash", field(assignment, "specHash")?),
        ("tsx-fragment-hash", field(assignment, "fragmentHash")?),
    ] {
        if headers.get(header).map(String::as_str) != Some(value) {
            return Err(format!(
                "federation partial binding does not match: {header}"
            ));
        }
    }
    if headers["tsx-plan-version"] != plan.version().to_string() || headers["tsx-revision"] != "0" {
        return Err("federation partial revision or version is invalid".into());
    }
    let expected = format!(
        "{}|{}|{}|{}|{}",
        plan.job(),
        field(assignment, "site")?,
        statistics.series_id,
        statistics.window_type,
        statistics.window_start_micros
    );
    if record.key != expected || record.timestamp != statistics.window_end_micros.div_euclid(1000) {
        return Err("federation partial key or timestamp does not match its statistics".into());
    }
    let scope = Scope {
        group: statistics.series_id,
        kind: statistics.window_type,
        from: statistics.window_start_micros,
        to: statistics.window_end_micros,
    };
    if !scopes(plan)?.contains_key(&scope.key()) {
        return Err("federation partial is outside its signed scope".into());
    }
    let evidence =
        tsx_contracts::parse_json(&headers["tsx-evidence"], 131_072).map_err(str::to_string)?;
    let expected = json!({"schema":"opentsx.aggregate-evidence.v1","kind":"bounded-source-cut","job":plan.job(),"site":field(assignment,"site")?,
        "planVersion":plan.version(),"generation":field(assignment,"generation")?,"assignment":field(assignment,"assignment")?,
        "fence":field(assignment,"fence")?,"specHash":field(assignment,"specHash")?,"fragmentHash":field(assignment,"fragmentHash")?,
        "inputs":assignment["inputs"],"payloadSha256":tsx_contracts::sha256_bytes(&record.payload)});
    if evidence != expected {
        return Err(
            "federation partial evidence does not prove its exact admitted cut and bytes".into(),
        );
    }
    Ok(())
}

pub(crate) fn headers(record: &FederationRecord) -> Result<BTreeMap<String, String>, String> {
    if record.payload.len() > 1_048_576
        || record.key.len() > 4096
        || record.timestamp < 0
        || record.headers.len() > 64
    {
        return Err("federation record exceeds its bounds".into());
    }
    let mut output = BTreeMap::new();
    let mut bytes = record.payload.len() + record.key.len();
    for (name, value) in &record.headers {
        bytes = bytes
            .checked_add(name.len())
            .and_then(|n| n.checked_add(value.len()))
            .ok_or("federation record size overflow")?;
        if bytes > 1_048_576 {
            return Err("federation record exceeds its aggregate byte bound".into());
        }
        let value =
            std::str::from_utf8(value).map_err(|_| "federation header value is not UTF-8")?;
        if output.insert(name.clone(), value.to_owned()).is_some() {
            return Err("duplicate federation header".into());
        }
    }
    Ok(output)
}

pub(crate) fn field<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    value
        .get(name)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("missing federation text field {name}"))
}
fn array<'a>(value: &'a Value, name: &str) -> Result<&'a Vec<Value>, String> {
    value
        .get(name)
        .and_then(Value::as_array)
        .ok_or_else(|| format!("missing federation array {name}"))
}
pub(crate) fn decimal(value: &str) -> Result<u64, String> {
    let n = value
        .parse::<u64>()
        .map_err(|_| "invalid federation decimal")?;
    if n.to_string() != value {
        return Err("noncanonical federation decimal".into());
    }
    Ok(n)
}
fn signed_decimal(value: &str) -> Result<i64, String> {
    let n = value
        .parse::<i64>()
        .map_err(|_| "invalid federation signed decimal")?;
    if n.to_string() != value {
        return Err("noncanonical federation signed decimal".into());
    }
    Ok(n)
}
fn hex_id(value: &str) -> bool {
    value.len() == 16
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
    use ed25519_dalek::{
        pkcs8::{EncodePrivateKey, EncodePublicKey},
        Signer, SigningKey,
    };
    use std::fs;
    use std::io::Read;
    use std::os::unix::fs::PermissionsExt;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);
    const INPUT: &str = "tsx.b3test.episodes.v2";
    const OUTPUT: &str = "tsx.b3test.aggregates.v2";
    const ARTIFACT: &str =
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const NOW: i64 = 1_790_812_800_000_000;

    pub(crate) struct Fixture {
        pub(crate) directory: PathBuf,
        pub(crate) config: SiteConfig,
        pub(crate) plan: BoundedPlan,
        driver_key: SigningKey,
        input: Vec<FederationInputRecord>,
    }

    impl Fixture {
        pub(crate) fn new() -> Self {
            Self::with_jobs(&["bounded-test"])
        }

        /// A fixture whose site configuration permits `jobs`; the signed plan is for `bounded-test`.
        pub(crate) fn with_jobs(jobs: &[&str]) -> Self {
            let directory = std::env::temp_dir().join(format!(
                "tsx-b3-fed-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::SeqCst)
            ));
            fs::create_dir(&directory).unwrap();
            fs::set_permissions(&directory, fs::Permissions::from_mode(0o700)).unwrap();
            let mut seed = [0u8; 32];
            fs::File::open("/dev/urandom")
                .unwrap()
                .read_exact(&mut seed)
                .unwrap();
            let key = SigningKey::from_bytes(&seed);
            fs::File::open("/dev/urandom")
                .unwrap()
                .read_exact(&mut seed)
                .unwrap();
            let site_key = SigningKey::from_bytes(&seed);
            seed.fill(0);
            for (name, bytes) in [
                (
                    "driver.pem",
                    key.verifying_key()
                        .to_public_key_pem(Default::default())
                        .unwrap()
                        .into_bytes(),
                ),
                (
                    "site.pem",
                    site_key
                        .to_pkcs8_pem(Default::default())
                        .unwrap()
                        .as_bytes()
                        .to_vec(),
                ),
                ("ca.crt", b"local fixture CA bytes".to_vec()),
                ("client.crt", b"local fixture certificate bytes".to_vec()),
                ("client.key", b"local fixture key bytes".to_vec()),
            ] {
                let path = directory.join(name);
                fs::write(&path, bytes).unwrap();
                fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
            }
            let config_doc = json!({"schema":"opentsx.rust-site-config.v1","site":"s1","node":"node-1",
                "driver":{"url":"https://localhost:18443","ca":"ca.crt","certificate":"client.crt","privateKey":"client.key"},
                "driverPublicKey":"driver.pem","sitePrivateKey":"site.pem","qualification":"qualification.json","jobs":jobs,
                "bindings":{"site":{"bootstrap":"127.0.0.1:39092","inputs":[INPUT],"outputs":[OUTPUT,"tsx.b3test.dag-status.v1"]},
                    "signal":{"bootstrap":"127.0.0.1:39093","inputs":[],"outputs":[OUTPUT]}},
                "policy":{"domains":["b3test"],"inputs":{"site":[INPUT]},"outputs":{"site":["aggregates","dag-status"],"signal":["aggregates"]},"catalogs":["tsx-catalog/1"]},
                "state":"state","limits":{"records":1,"payloadBytes":1048576,"inputBytes":16777216,"samples":1000000,"outputBytes":16777216,"series":1024,"stateBytes":1048576}});
            let path = directory.join("config.json");
            let cases = [("topics.admin","admin"),("topics.batch","batch"),("records.order","order"),("records.codec","codec0"),("records.codec","codec1"),("records.codec","codec2"),("records.codec","codec3"),("records.large","large"),("offsets.committed","committed"),("offsets.explicit","explicit")].into_iter().map(|(family,name)|json!({"family":family,"case":name,"status":"pass","detail":"synthetic parser fixture"})).collect::<Vec<_>>();
            let qualification = json!({"schema":"opentsx.rust-b3-qualification.v1","artifact":{"version":env!("CARGO_PKG_VERSION"),"digest":ARTIFACT,"stateAbi":crate::site_plan::STATE_ABI,"catalog":"tsx-catalog/1"},
                "endpoint":"127.0.0.1:39092","issued":"2026-01-01T00:00:00Z","expires":"2027-01-01T00:00:00Z","vectors":crate::site_qualification::vector_hashes(),
                "tests":{"passed":259,"failed":0,"ignored":0,"measured":0,"filtered":0,"sharedVectorTests":["synthetic shared vector check"],"acceptanceExampleTests":["synthetic acceptance example check"],"vectorSuites":crate::site_qualification::SUITES},
                "protocol":{"schema":"opentsx.rust-b1-conformance.v1","runnerVersion":env!("CARGO_PKG_VERSION"),"endpoint":"127.0.0.1:39092","requiredCases":10,"executedCases":10,"passedCases":10,"failedCases":0,"requiredFamilies":7,"executedFamilies":7,
                    "cases":cases},
                "logs":["0".repeat(64),"1".repeat(64)]});
            let mut qualification = qualification;
            qualification["placement"] = json!({"java":{"schema":"opentsx.rust-b1-placement-result.v1","keys":10000,"partitions":12,"matched":10000},
                "rust":{"schema":"opentsx.rust-b1-placement-result.v1","keys":10000,"partitions":12,"matched":10000,"fixtureSha256":"2".repeat(64)}});
            fs::write(
                directory.join("qualification.json"),
                qualification.to_string(),
            )
            .unwrap();
            fs::write(&path, config_doc.to_string()).unwrap();
            fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).unwrap();
            let config = SiteConfig::load(&path).unwrap();
            let episodes = [
                episode("one", vec![1.0, 2.0]),
                episode("two", vec![3.0, f64::NAN]),
            ];
            let fragment=json!({"schema":"opentsx.dag.v2","dag":"bounded-test","source":{"kind":"kafscale-broker","topics":[INPUT],"series":episodes.iter().map(|e|e.series.series_id.clone()).collect::<Vec<_>>()},
                "nodes":[{"id":"decode","op":"decode.avro-soe"},{"id":"stats","op":"stats.welford-ddsketch","in":["decode"],"params":{"alpha":0.01,"windows":["PT10M"]}}],
                "sinks":[{"from":"stats","topic":OUTPUT,"schema":"org.opentsx.model.v2.SeriesStats"}]}).to_string();
            let hash = tsx_contracts::sha256(&fragment);
            let assignment = json!({"schema":"opentsx.execution-assignment.v1","deployment":"bounded-test","generation":"1","assignment":"a1","attempt":"attempt-1","fence":"1","fragment":"fragment-1","site":"s1","node":"node-1","runtime":"rust","catalog":"tsx-catalog/1","artifactDigest":ARTIFACT,"specHash":hash,"sourceDagText":fragment,"fragmentHash":hash,"fragmentText":fragment,"mode":"bounded","inputs":[{"binding":"site","topic":INPUT,"partitionCount":"1","positions":[{"partition":"0","from":"0","to":"2"}]}]});
            let group = series_id(
                "learning.value",
                &BTreeMap::from([("park".into(), "p1".into())]),
            )
            .unwrap();
            let document = json!({"schema":"opentsx.runner-plan.v1","job":"bounded-test","planVersion":1,"issued":"2026-01-01T00:00:00Z","expires":"2027-01-01T00:00:00Z","site":"s1","catalog":"tsx-catalog/1","specHash":hash,"assignmentText":assignment.to_string(),
                "inputContracts":[{"binding":"site","topic":INPUT,"fingerprints":[crate::wire::FINGERPRINT]}],"outputs":[{"sink":OUTPUT,"cluster":"site","topic":OUTPUT,"partitionCount":"1","fingerprints":[crate::stats::STATS_FINGERPRINT]}],
                "federation":{"route":"signal-cluster","expectedAssignments":[{"assignment":"a1","site":"s1","node":"node-1","generation":"1","fence":"1","fragmentHash":hash}],"scopes":[{"groupId":group,"windowType":"PT10M","startMicros":"600000000","endMicros":"1200000000"}],"groupBy":["park"],"maxDelay":"PT1M"}});
            let input = format!(
                "{}.{}",
                URL_SAFE_NO_PAD.encode(b"{\"alg\":\"EdDSA\"}"),
                URL_SAFE_NO_PAD.encode(document.to_string())
            );
            let compact = format!(
                "{input}.{}",
                URL_SAFE_NO_PAD.encode(key.sign(input.as_bytes()).to_bytes())
            );
            let plan = BoundedPlan::verify(&compact, &config, ARTIFACT).unwrap();
            let input = episodes
                .iter()
                .enumerate()
                .map(|(index, e)| FederationInputRecord {
                    partition: 0,
                    offset: index as i64,
                    record: FederationRecord {
                        key: e.series.series_id.clone(),
                        timestamp: e.t_start / 1000,
                        payload: crate::wire::encode(e).unwrap(),
                        headers: vec![(
                            "tsx-schema".into(),
                            crate::wire::SCHEMA_NAME.as_bytes().to_vec(),
                        )],
                    },
                })
                .collect();
            Self {
                directory,
                config,
                plan,
                driver_key: key,
                input,
            }
        }
    }

    impl Fixture {
        pub(crate) fn sign_document(&self, value: &Value) -> String {
            let input = format!(
                "{}.{}",
                URL_SAFE_NO_PAD.encode(b"{\"alg\":\"EdDSA\"}"),
                URL_SAFE_NO_PAD.encode(value.to_string())
            );
            format!(
                "{input}.{}",
                URL_SAFE_NO_PAD.encode(self.driver_key.sign(input.as_bytes()).to_bytes())
            )
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.directory).unwrap();
        }
    }

    fn episode(sensor: &str, values: Vec<f64>) -> Episode {
        let tags = BTreeMap::from([
            ("park".into(), "p1".into()),
            ("sensor".into(), sensor.into()),
        ]);
        let count = values.len() as i32;
        Episode {
            episode_id: format!("episode-{sensor}"),
            series: crate::model::SeriesKey {
                metric: "learning.value".into(),
                series_id: series_id("learning.value", &tags).unwrap(),
                tags,
                unit: Some("kW".into()),
            },
            t_start: 610_000_000,
            t_end: 610_000_000 + i64::from(count) * 1_000_000,
            count,
            time_encoding: crate::model::TimeEncoding::Regular,
            interval_micros: Some(1_000_000),
            time_deltas: None,
            summary: crate::model::summary(&values),
            values,
            quality: None,
            segmentation: None,
            labels: BTreeMap::new(),
            bucket_id: None,
            revision: 0,
            provenance: crate::model::Provenance {
                source: "fixture".into(),
                producer: "fixture".into(),
                created_at: 0,
                pipeline: None,
                parent_episode_id: None,
            },
        }
    }

    #[derive(Default)]
    struct Memory {
        input: Vec<FederationInputRecord>,
        site: Vec<FederationRecord>,
        signal: Vec<FederationRecord>,
        reads: usize,
        publishes: usize,
        unreachable: bool,
        fail_site_ack: bool,
    }

    impl FederationIo for Memory {
        fn read(
            &mut self,
            _: &str,
            cut: &Cut,
            limits: &Limits,
        ) -> Result<Vec<FederationInputRecord>, String> {
            self.reads += 1;
            let records = if cut.topic == INPUT {
                self.input.clone()
            } else {
                self.site
                    .iter()
                    .enumerate()
                    .map(|(i, r)| FederationInputRecord {
                        partition: 0,
                        offset: i as i64,
                        record: r.clone(),
                    })
                    .collect()
            };
            Ok(records
                .into_iter()
                .filter(|r| {
                    cut.positions.iter().any(|p| {
                        p.partition == r.partition && p.from <= r.offset && r.offset < p.to
                    })
                })
                .take(limits.records)
                .collect())
        }
        fn watermarks(&mut self, _: &str, _: &str, _: usize) -> Result<Vec<Watermark>, String> {
            Ok(vec![Watermark {
                partition: 0,
                low: 0,
                high: self.site.len() as i64,
            }])
        }
        fn publish(
            &mut self,
            bootstrap: &str,
            _: &str,
            _: usize,
            records: &[FederationRecord],
        ) -> Result<(), String> {
            self.publishes += 1;
            if bootstrap.ends_with("39092") {
                self.site.extend_from_slice(records);
                if self.fail_site_ack {
                    self.fail_site_ack = false;
                    return Err("lost local acknowledgement".into());
                }
            } else {
                if self.unreachable {
                    return Err("signal route unreachable".into());
                }
                self.signal.extend_from_slice(records);
            }
            Ok(())
        }
    }

    #[test]
    fn bounded_statistics_stage_every_input_before_full_cut_evidence_and_completed_replay() {
        let f = Fixture::new();
        BoundedPlan::verify(&f.sign_document(f.plan.document()), &f.config, ARTIFACT).unwrap();
        let mut io = Memory {
            input: f.input.clone(),
            ..Memory::default()
        };
        let outcome = execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, NOW).unwrap();
        assert!(outcome.completed);
        assert_eq!(outcome.input_records, 2);
        assert_eq!(outcome.increments, 2);
        assert_eq!(outcome.output_records, 1);
        assert_eq!(io.site.len(), 1);
        let statistics = StatsRecord::decode_soe(&io.site[0].payload, 1_048_576).unwrap();
        assert_eq!(statistics.valid_count, 3);
        assert_eq!(statistics.nan_count, 1);
        assert_eq!(statistics.mean, 2.0);
        validate_partial(&f.plan, &io.site[0]).unwrap();
        let before = (io.reads, io.publishes);
        assert!(
            execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, i64::MAX)
                .unwrap()
                .completed
        );
        assert_eq!((io.reads, io.publishes), before);
    }

    #[test]
    fn every_signed_empty_scope_gets_one_full_cut_partial() {
        let f = Fixture::new();
        let mut document = f.plan.document().clone();
        let mut empty = document["federation"]["scopes"][0].clone();
        empty["startMicros"] = json!("1200000000");
        empty["endMicros"] = json!("1800000000");
        document["federation"]["scopes"]
            .as_array_mut()
            .unwrap()
            .push(empty);
        let plan = BoundedPlan::verify(&f.sign_document(&document), &f.config, ARTIFACT).unwrap();
        let mut io = Memory {
            input: f.input.clone(),
            ..Memory::default()
        };
        let outcome = execute_at(&plan, &f.config, &StopToken::new(), &mut io, NOW).unwrap();
        assert_eq!(outcome.output_records, 2);
        let empty = io
            .site
            .iter()
            .find(|record| record.key.ends_with("|1200000000"))
            .unwrap();
        validate_partial(&plan, empty).unwrap();
        let statistics = StatsRecord::decode_soe(&empty.payload, 1_048_576).unwrap();
        assert_eq!((statistics.valid_count, statistics.nan_count), (0, 0));
        assert!(statistics.min.is_nan());
        assert!(statistics.max.is_nan());
    }

    #[test]
    fn failed_local_ack_keeps_exact_staged_bytes_and_never_recomputes_the_source() {
        let f = Fixture::new();
        let mut io = Memory {
            input: f.input.clone(),
            fail_site_ack: true,
            ..Memory::default()
        };
        assert!(execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, NOW).is_err());
        let before = io.reads;
        assert!(
            execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, i64::MAX)
                .unwrap()
                .completed
        );
        assert_eq!(io.reads, before);
        assert_eq!(io.site.len(), 2);
        assert_eq!(io.site[0], io.site[1]);
    }

    #[test]
    fn unreachable_signal_and_lost_target_ack_keep_forward_cursors_and_retry_exact_records() {
        let f = Fixture::new();
        let mut io = Memory {
            input: f.input.clone(),
            ..Memory::default()
        };
        execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, NOW).unwrap();
        io.unreachable = true;
        let waiting =
            crate::forwarder::forward_with(&f.plan, &f.config, &mut io, &mut || Ok(())).unwrap();
        assert!(!waiting.completed);
        assert_eq!(waiting.error.as_deref(), Some("signal route unreachable"));
        assert!(io.signal.is_empty());
        let checkpoint = f.config.directory().join("bounded-test-1-forward.json");
        let before = fs::read(&checkpoint).unwrap();
        io.unreachable = false;
        let waiting = crate::forwarder::forward_with(&f.plan, &f.config, &mut io, &mut || {
            Err("lost target acknowledgement".into())
        })
        .unwrap();
        assert!(!waiting.completed);
        assert_eq!(fs::read(&checkpoint).unwrap(), before);
        assert_eq!(io.signal.len(), 1);
        assert!(
            crate::forwarder::forward_with(&f.plan, &f.config, &mut io, &mut || Ok(()))
                .unwrap()
                .completed
        );
        assert_eq!(io.signal.len(), 2);
        assert_eq!(io.signal[0], io.signal[1]);
        let before = (io.reads, io.publishes);
        assert!(
            crate::forwarder::forward_with(&f.plan, &f.config, &mut io, &mut || panic!(
                "must not publish completed work"
            ))
            .unwrap()
            .completed
        );
        assert_eq!((io.reads, io.publishes), before);
    }

    #[test]
    fn changed_partial_payload_and_duplicate_headers_refuse_before_signal_publication() {
        for duplicate in [false, true] {
            let f = Fixture::new();
            let mut io = Memory {
                input: f.input.clone(),
                ..Memory::default()
            };
            execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, NOW).unwrap();
            if duplicate {
                let header = io.site[0].headers[0].clone();
                io.site[0].headers.push(header);
            } else {
                let at = io.site[0].payload.len() - 1;
                io.site[0].payload[at] ^= 1;
            }
            assert!(
                crate::forwarder::forward_with(&f.plan, &f.config, &mut io, &mut || Ok(()))
                    .is_err()
            );
            assert!(io.signal.is_empty());
        }
    }

    #[test]
    fn expired_fresh_plan_and_stopped_work_never_fetch_or_publish() {
        let f = Fixture::new();
        let mut io = Memory {
            input: f.input.clone(),
            ..Memory::default()
        };
        assert!(execute_at(&f.plan, &f.config, &StopToken::new(), &mut io, i64::MAX).is_err());
        assert_eq!((io.reads, io.publishes), (0, 0));
        let stop = StopToken::new();
        stop.request().unwrap();
        let outcome = execute_at(&f.plan, &f.config, &stop, &mut io, NOW).unwrap();
        assert!(!outcome.completed);
        assert_eq!((io.reads, io.publishes), (0, 0));
    }
}
