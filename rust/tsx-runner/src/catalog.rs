use crate::broker::OutputRecord;
use crate::model::{xxh64_hex, Episode};
use crate::operators::{Limits, Pipeline as CleanPipeline, SUPPORTED_OPS};
use crate::pairs::{
    input_name_clash, PairParams, PairStream, VectorParams, VectorPoint, VectorStream,
};
use crate::retrieval::{sax_breakpoints, sax_word, Segmenter};
use crate::stats::{self, Expectation, StatsEngine, StatsRecord};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

pub const CATALOG_OPS: &[&str] = &[
    "stats.welford-ddsketch",
    "align.vector",
    "segment.threshold",
    "segment.extremum",
    "sax.word",
    "pair.xcorr",
    "dq.result",
];
const STATE_ABI: &str = "opentsx.rust.catalog-state.v1";

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Context {
    #[serde(default)]
    pub series_by_node: BTreeMap<String, Vec<String>>,
    #[serde(default)]
    pub clean_targets: BTreeMap<String, Value>,
    pub xcorr_product_limit: Option<usize>,
}

#[derive(Clone, Debug)]
pub enum Pipeline {
    Clean(Box<CleanPipeline>),
    Catalog(Box<Catalog>),
}

impl Pipeline {
    pub fn compile(
        fragment: &Value,
        spec_hash: &str,
        created_at: i64,
        limits: Limits,
        context: Option<&Context>,
        site: &str,
    ) -> Result<Self, String> {
        let catalog_profile = context.is_some();
        let context = context.cloned().unwrap_or_default();
        if context.xcorr_product_limit == Some(0) {
            return Err("xcorrProductLimit must be positive".into());
        }
        let nodes = fragment["nodes"]
            .as_array()
            .ok_or("nodes must be an array")?;
        for node in nodes {
            let op = text(&node["op"], "node operator")?;
            if op != "decode.avro-soe" && !SUPPORTED_OPS.contains(&op) && !CATALOG_OPS.contains(&op)
            {
                return Err(format!("unsupported operator {op}"));
            }
        }
        if !catalog_profile
            && nodes.iter().any(|n| {
                !matches!(
                    n["op"].as_str(),
                    Some("decode.avro-soe" | "dq.checks" | "convert.unit" | "resample")
                )
            })
        {
            return Err("B2 operators require an explicit local context".into());
        }
        if !catalog_profile {
            for node in nodes.iter().filter(|n| n["op"] == "resample") {
                let id = text(&node["id"], "resample node")?;
                if nodes.iter().any(|n| {
                    n["in"]
                        .as_array()
                        .is_some_and(|a| a.iter().any(|v| v.as_str() == Some(id)))
                }) {
                    return Err("resample is supported only as the trailing operator".into());
                }
            }
        }
        if context.series_by_node.is_empty()
            && context.clean_targets.is_empty()
            && nodes.iter().all(|n| {
                n["op"] == "decode.avro-soe"
                    || n["op"]
                        .as_str()
                        .is_some_and(|op| SUPPORTED_OPS.contains(&op))
            })
        {
            return if catalog_profile {
                CleanPipeline::compile_catalog(fragment, spec_hash, created_at, limits)
            } else {
                CleanPipeline::compile(fragment, spec_hash, created_at, limits)
            }
            .map(|p| Self::Clean(Box::new(p)));
        }
        Catalog::compile(fragment, spec_hash, created_at, limits, &context, site)
            .map(|p| Self::Catalog(Box::new(p)))
    }

    pub fn source_topic(&self) -> &str {
        match self {
            Self::Clean(p) => p.source_topic(),
            Self::Catalog(p) => &p.topic,
        }
    }
    pub fn sink_name(&self) -> &str {
        match self {
            Self::Clean(p) => p.sink_name(),
            Self::Catalog(p) => &p.sink_name,
        }
    }
    pub fn sink_topic(&self) -> Option<&str> {
        match self {
            Self::Clean(p) => p.sink_topic(),
            Self::Catalog(p) => Some(&p.sink_topic),
        }
    }
    pub fn expected_series(&self) -> &BTreeSet<String> {
        match self {
            Self::Clean(p) => p.expected_series(),
            Self::Catalog(p) => &p.expected,
        }
    }
    pub fn is_passthrough(&self) -> bool {
        matches!(self, Self::Clean(p) if p.is_passthrough())
    }
    pub fn schema_name(&self) -> &str {
        match self {
            Self::Clean(_) => crate::wire::SCHEMA_NAME,
            Self::Catalog(p) => match p.output_type {
                RecordType::Series => crate::wire::SCHEMA_NAME,
                RecordType::Stats => "org.opentsx.model.v2.SeriesStats",
                RecordType::Vector => "opentsx.vector.v1",
                RecordType::Signal => "opentsx.signal.v1",
            },
        }
    }
    pub fn process_records(
        &mut self,
        episodes: &[Episode],
        complete: bool,
    ) -> Result<Vec<OutputRecord>, String> {
        match self {
            Self::Clean(p) => p.process(episodes)?.iter().map(episode_record).collect(),
            Self::Catalog(p) => p.process(episodes, complete),
        }
    }
    pub fn process(&mut self, episodes: &[Episode]) -> Result<Vec<Episode>, String> {
        match self {
            Self::Clean(p) => p.process(episodes),
            Self::Catalog(_) => Err("catalog plans require typed record execution".into()),
        }
    }
    pub fn snapshot(&self) -> Result<Value, String> {
        match self {
            Self::Clean(p) => p.snapshot(),
            Self::Catalog(p) => p.snapshot(),
        }
    }
    pub fn restore(&mut self, state: &Value) -> Result<(), String> {
        match self {
            Self::Clean(p) => p.restore(state),
            Self::Catalog(p) => p.restore(state),
        }
    }
    pub fn validate_output(
        &self,
        record: &OutputRecord,
        max_bytes: usize,
        max_samples: usize,
    ) -> Result<String, String> {
        let kind = match self {
            Self::Clean(_) => RecordType::Series,
            Self::Catalog(p) => p.output_type,
        };
        let identity = validate_record(record, kind, max_bytes, max_samples)?;
        if let Self::Catalog(p) = self {
            if matches!(kind, RecordType::Signal | RecordType::Vector) {
                let value: Value =
                    serde_json::from_slice(&record.payload).map_err(|_| "invalid prepared JSON")?;
                let job = text(&p.fragment["dag"], "DAG identity")?;
                if kind == RecordType::Signal
                    && (value["job"] != job
                        || value["site"] != p.site
                        || value["specHash"] != p.spec_hash)
                {
                    return Err("prepared signal context differs from its assignment".into());
                }
                if kind == RecordType::Vector
                    && (value["vector"] != p.sink_from
                        || value["provenance"]["dag"] != job
                        || value["provenance"]["specHash"] != p.spec_hash)
                {
                    return Err("prepared vector context differs from its assignment".into());
                }
            }
        }
        Ok(identity)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RecordType {
    Series,
    Stats,
    Vector,
    Signal,
}

#[derive(Clone, Debug)]
enum Data {
    Series(Vec<Episode>),
    Stats(Vec<StatsRecord>),
    Vectors(Vec<VectorPoint>),
    Signals(Vec<Value>),
}
impl Data {
    fn series(&self) -> Result<&[Episode], String> {
        if let Self::Series(v) = self {
            Ok(v)
        } else {
            Err("operator requires Episode input".into())
        }
    }
}

#[derive(Clone, Debug)]
enum Engine {
    Decode,
    Clean(CleanPipeline),
    Stats(StatsEngine),
    Segment(Segmenter),
    Sax {
        w: usize,
        a: usize,
    },
    Vector(VectorStream),
    Pair(PairStream),
    Dq {
        expectation: Expectation,
        checks: BTreeSet<String>,
    },
}

#[derive(Clone, Debug)]
struct Stage {
    id: String,
    inputs: Vec<String>,
    engine: Engine,
}

#[derive(Clone, Debug)]
pub struct Catalog {
    fragment: Value,
    context: Context,
    spec_hash: String,
    created_at: i64,
    site: String,
    limits: Limits,
    topic: String,
    sink_topic: String,
    sink_name: String,
    sink_from: String,
    expected: BTreeSet<String>,
    stages: Vec<Stage>,
    output_type: RecordType,
    output_owners: BTreeMap<String, bool>,
    input_series: BTreeMap<String, bool>,
}

impl Catalog {
    fn compile(
        fragment: &Value,
        spec_hash: &str,
        created_at: i64,
        limits: Limits,
        context: &Context,
        site: &str,
    ) -> Result<Self, String> {
        if spec_hash.len() != 64
            || !spec_hash
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err("invalid pipeline specHash".into());
        }
        if [
            limits.max_samples,
            limits.max_series,
            limits.max_state_bytes,
            limits.max_output_samples,
        ]
        .contains(&0)
        {
            return Err("operator limits must be positive".into());
        }
        if context.xcorr_product_limit == Some(0) {
            return Err("xcorrProductLimit must be positive".into());
        }
        fields(
            fragment,
            &[
                "schema",
                "dag",
                "version",
                "source",
                "nodes",
                "sinks",
                "checkpoint",
            ],
        )?;
        if !matches!(
            fragment["schema"].as_str(),
            Some("opentsx.dag.v1" | "opentsx.dag.v2")
        ) {
            return Err("unsupported DAG schema".into());
        }
        fields(&fragment["source"], &["kind", "topics", "series", "start"])?;
        if fragment["source"]["kind"] != "kafscale-broker" {
            return Err("unsupported source kind".into());
        }
        let topics = fragment["source"]["topics"]
            .as_array()
            .filter(|v| v.len() == 1)
            .ok_or("a pipeline reads exactly one whole topic")?;
        let topic = text(&topics[0], "source topic")?.to_string();
        let expected = ids(fragment["source"].get("series"), limits.max_series)?;
        let sinks = fragment["sinks"]
            .as_array()
            .filter(|v| v.len() == 1)
            .ok_or("a pipeline needs exactly one sink")?;
        let sink = &sinks[0];
        fields(
            sink,
            &[
                "from", "schema", "topic", "signal", "origin", "madeOf", "name",
            ],
        )?;
        let sink_topic = text(&sink["topic"], "sink topic")?.to_string();
        let sink_from = text(&sink["from"], "sink input")?.to_string();
        let sink_name = sink
            .get("name")
            .map(|v| text(v, "sink name"))
            .transpose()?
            .unwrap_or(&sink_from)
            .to_string();
        let values = fragment["nodes"]
            .as_array()
            .filter(|v| !v.is_empty() && v.len() <= 128)
            .ok_or("nodes must contain 1 to 128 entries")?;
        let mut nodes = BTreeMap::new();
        for node in values {
            fields(node, &["id", "op", "in", "params"])?;
            let id = text(&node["id"], "node id")?.to_string();
            let op = text(&node["op"], "node operator")?;
            if op != "decode.avro-soe" && !SUPPORTED_OPS.contains(&op) && !CATALOG_OPS.contains(&op)
            {
                return Err(format!("unsupported operator {op}"));
            }
            if nodes.insert(id, node.clone()).is_some() {
                return Err("duplicate node id".into());
            }
        }
        for (id, selected) in &context.series_by_node {
            if !nodes.contains_key(id) {
                return Err("seriesByNode names an unknown node".into());
            }
            ids(Some(&json!(selected)), limits.max_series)?;
        }
        if context
            .clean_targets
            .keys()
            .any(|id| !nodes.contains_key(id))
        {
            return Err("cleanTargets names an unknown node".into());
        }
        for (id, target) in &context.clean_targets {
            fields(target, &["system", "id", "grade", "metric"])?;
            if nodes[id]["op"]
                .as_str()
                .is_none_or(|op| !SUPPORTED_OPS.contains(&op))
            {
                return Err("cleanTargets must name a cleaning node".into());
            }
        }
        let mut order = Vec::new();
        visit(
            &sink_from,
            &nodes,
            &mut BTreeSet::new(),
            &mut BTreeSet::new(),
            &mut order,
        )?;
        if order.len() != nodes.len() {
            return Err("pipeline graph contains unused nodes".into());
        }
        let mut consumers: BTreeMap<String, usize> = BTreeMap::new();
        for node in nodes.values() {
            for input in inputs(node)? {
                *consumers.entry(input).or_default() += 1;
            }
        }
        *consumers.entry(sink_from.clone()).or_default() += 1;
        let mut stages: Vec<Stage> = Vec::new();
        let mut types = BTreeMap::new();
        for id in order {
            let node = &nodes[&id];
            let op = node["op"].as_str().ok_or("operator is missing")?;
            if SUPPORTED_OPS.contains(&op)
                && nodes.values().any(|n| {
                    n["op"].as_str().is_some_and(|x| SUPPORTED_OPS.contains(&x))
                        && inputs(n).is_ok_and(|a| a == [id.clone()])
                })
                && consumers[&id] == 1
            {
                continue;
            }
            let mut from = inputs(node)?;
            let params = parameters(op, node.get("params"))?;
            let (engine, kind) = if op == "decode.avro-soe" {
                fields(&params, &[])?;
                if !from.is_empty() {
                    return Err("decoder must read the source directly".into());
                }
                (Engine::Decode, RecordType::Series)
            } else if SUPPORTED_OPS.contains(&op) {
                let mut chain = vec![node.clone()];
                while from.len() == 1
                    && nodes.get(&from[0]).is_some_and(|n| {
                        n["op"].as_str().is_some_and(|p| SUPPORTED_OPS.contains(&p))
                    })
                    && consumers[&from[0]] == 1
                {
                    chain.push(nodes[&from[0]].clone());
                    from = inputs(&nodes[&from[0]])?;
                }
                chain.reverse();
                require_inputs(&from, &types, RecordType::Series, 1, 1)?;
                let mut clean = fragment.clone();
                let source_id = "__b2_source";
                chain[0]["in"] = json!([source_id]);
                chain.insert(0, json!({"id":source_id,"op":"decode.avro-soe"}));
                clean["nodes"] = json!(chain);
                clean["source"]["series"] = json!(inventory(&from[0], &nodes, context, &expected)?);
                let mut target = sink.clone();
                target["from"] = json!(id);
                target["schema"] = json!("opentsx.episode.v2");
                if let Some(bound) = context.clean_targets.get(&id) {
                    target["signal"] = bound.clone();
                }
                clean["sinks"] = json!([target]);
                (
                    Engine::Clean(CleanPipeline::compile_catalog(
                        &clean, spec_hash, created_at, limits,
                    )?),
                    RecordType::Series,
                )
            } else {
                match op {
                    "stats.welford-ddsketch" => {
                        require_inputs(&from, &types, RecordType::Series, 1, 1)?;
                        fields(&params, &["windows", "alpha"])?;
                        let windows = params["windows"]
                            .as_array()
                            .filter(|v| !v.is_empty())
                            .ok_or("statistics windows must be nonempty")?
                            .iter()
                            .map(|v| {
                                let raw = text(v, "statistics window")?;
                                let (width, canonical) = crate::retrieval::duration_micros(raw)?;
                                Ok((canonical, width))
                            })
                            .collect::<Result<Vec<_>, String>>()?;
                        let alpha = params["alpha"]
                            .as_f64()
                            .filter(|a| a.is_finite() && *a > 0.0 && *a < 1.0)
                            .ok_or("alpha must be in (0, 1)")?;
                        (
                            Engine::Stats(StatsEngine::new(windows, alpha, None, 0)?),
                            RecordType::Stats,
                        )
                    }
                    "segment.threshold" | "segment.extremum" => {
                        require_inputs(&from, &types, RecordType::Series, 1, 1)?;
                        (
                            Engine::Segment(Segmenter::new(
                                op,
                                &params,
                                limits.max_series,
                                limits.max_samples,
                                limits.max_state_bytes,
                            )?),
                            RecordType::Series,
                        )
                    }
                    "sax.word" => {
                        require_inputs(&from, &types, RecordType::Series, 1, 1)?;
                        fields(&params, &["w", "a"])?;
                        let w = params["w"]
                            .as_u64()
                            .filter(|n| *n > 0 && *n <= limits.max_output_samples as u64)
                            .ok_or("SAX w exceeds the sample capacity")?
                            as usize;
                        let a = params["a"]
                            .as_u64()
                            .ok_or("SAX alphabet must be an integer")?
                            as usize;
                        sax_breakpoints(a)?;
                        (Engine::Sax { w, a }, RecordType::Series)
                    }
                    "align.vector" => {
                        require_inputs(&from, &types, RecordType::Series, 1, 128)?;
                        let names = params
                            .get("names")
                            .map(|v| {
                                v.as_array()
                                    .ok_or_else(|| "vector names must be an array".to_string())
                                    .and_then(|a| {
                                        a.iter()
                                            .map(|v| {
                                                text(v, "vector input name").map(str::to_string)
                                            })
                                            .collect::<Result<Vec<_>, _>>()
                                    })
                            })
                            .transpose()?
                            .unwrap_or_else(|| {
                                (1..=from.len()).map(|i| format!("in{i}")).collect()
                            });
                        if names.len() != from.len() {
                            return Err("vector names must uniquely name every input".into());
                        }
                        if let Some(clash) = input_name_clash(&names) {
                            return Err(format!("node {id}: {clash}"));
                        }
                        let declared = names
                            .into_iter()
                            .zip(from.iter())
                            .map(|(name, input)| {
                                Ok((name, inventory(input, &nodes, context, &expected)?))
                            })
                            .collect::<Result<Vec<_>, String>>()?;
                        (
                            Engine::Vector(VectorStream::new(
                                declared,
                                VectorParams::parse(&params)?,
                                limits.max_samples,
                            )?),
                            RecordType::Vector,
                        )
                    }
                    "pair.xcorr" => {
                        require_inputs(&from, &types, RecordType::Series, 1, 2)?;
                        let within = from.len() == 1;
                        let a = inventory(&from[0], &nodes, context, &expected)?;
                        let b = inventory(
                            &from[if within { 0 } else { 1 }],
                            &nodes,
                            context,
                            &expected,
                        )?;
                        let product_limit = context
                            .xcorr_product_limit
                            .ok_or("pair.xcorr requires an explicit xcorrProductLimit")?;
                        let mut stream = PairStream::new(
                            id.clone(),
                            PairParams::parse(&params)?,
                            a,
                            b,
                            within,
                            spec_hash.into(),
                            created_at,
                            product_limit,
                            limits.max_samples,
                        )?;
                        if within {
                            let order = if let Some(order) = context.series_by_node.get(&from[0]) {
                                order.clone()
                            } else {
                                fragment["source"]["series"]
                                    .as_array()
                                    .ok_or("pair source inventory is missing")?
                                    .iter()
                                    .map(|v| text(v, "pair source id").map(str::to_string))
                                    .collect::<Result<Vec<_>, String>>()?
                            };
                            stream.set_within_order(&order)?;
                        }
                        (Engine::Pair(stream), RecordType::Series)
                    }
                    "dq.result" => {
                        require_inputs(&from, &types, RecordType::Stats, 1, 1)?;
                        fields(&params, &["checks", "min", "max", "expectedInterval"])?;
                        let checks = params["checks"]
                            .as_array()
                            .ok_or("DQ checks must be an array")?
                            .iter()
                            .map(|v| {
                                let check = text(v, "DQ check")?;
                                if ![
                                    "COMPLETENESS",
                                    "PLAUSIBLE_RANGE",
                                    "FLATLINE",
                                    "GAPS",
                                    "FRESHNESS",
                                ]
                                .contains(&check)
                                {
                                    return Err("unknown DQ check".to_string());
                                }
                                Ok(check.to_string())
                            })
                            .collect::<Result<BTreeSet<_>, _>>()?;
                        let mut expectation = params.clone();
                        expectation
                            .as_object_mut()
                            .ok_or("DQ params must be an object")?
                            .remove("checks");
                        if let Some(interval) = expectation
                            .as_object_mut()
                            .ok_or("DQ params must be an object")?
                            .remove("expectedInterval")
                        {
                            expectation["expectedIntervalMicros"] = json!(
                                crate::retrieval::duration_micros(text(
                                    &interval,
                                    "expected interval"
                                )?)?
                                .0
                            );
                        }
                        (
                            Engine::Dq {
                                expectation: Expectation::from_json(&expectation)?,
                                checks,
                            },
                            RecordType::Signal,
                        )
                    }
                    _ => return Err(format!("unsupported operator {op}")),
                }
            };
            types.insert(id.clone(), kind);
            stages.push(Stage {
                id,
                inputs: from,
                engine,
            });
        }
        if context
            .series_by_node
            .keys()
            .any(|id| types.get(id) != Some(&RecordType::Series))
        {
            return Err("seriesByNode must name an executed series-output node".into());
        }
        if context.clean_targets.keys().any(|id| {
            !stages
                .iter()
                .any(|s| s.id == *id && matches!(s.engine, Engine::Clean(_)))
        }) {
            return Err("cleanTargets must name an executed clean stage".into());
        }
        let output_type = *types.get(&sink_from).ok_or("missing sink producer")?;
        let expected_schema = match output_type {
            RecordType::Series => "opentsx.episode.v2",
            RecordType::Stats => "org.opentsx.model.v2.SeriesStats",
            RecordType::Vector => "opentsx.vector.v1",
            RecordType::Signal => "opentsx.signal.v1",
        };
        if sink["schema"] != expected_schema {
            return Err("sink schema does not match its input type".into());
        }
        let result = Self {
            fragment: fragment.clone(),
            context: context.clone(),
            spec_hash: spec_hash.into(),
            created_at,
            site: site.into(),
            limits,
            topic,
            sink_topic,
            sink_name,
            sink_from,
            expected,
            stages,
            output_type,
            output_owners: BTreeMap::new(),
            input_series: BTreeMap::new(),
        };
        result.snapshot()?;
        Ok(result)
    }

    fn process(
        &mut self,
        episodes: &[Episode],
        complete: bool,
    ) -> Result<Vec<OutputRecord>, String> {
        let mut next = self.clone();
        let records = next.evaluate(episodes, complete)?;
        next.snapshot()?;
        *self = next;
        Ok(records)
    }

    fn evaluate(
        &mut self,
        episodes: &[Episode],
        complete: bool,
    ) -> Result<Vec<OutputRecord>, String> {
        let mut count = 0usize;
        let mut selected = Vec::new();
        for episode in episodes {
            episode.validate(self.limits.max_samples)?;
            self.input_series
                .insert(episode.series.series_id.clone(), true);
            if self.input_series.len() > self.limits.max_series {
                return Err("input series limit exceeded".into());
            }
            count = count
                .checked_add(episode.values.len())
                .ok_or("input sample count overflow")?;
            if count > self.limits.max_samples {
                return Err("input sample limit exceeded".into());
            }
            if self.expected.is_empty() || self.expected.contains(&episode.series.series_id) {
                selected.push(episode.clone());
            }
        }
        let mut outputs: BTreeMap<String, Data> = BTreeMap::new();
        let job = text(&self.fragment["dag"], "DAG identity")?;
        for stage in &mut self.stages {
            let input = stage
                .inputs
                .iter()
                .map(|id| outputs.get(id).ok_or("node input was not evaluated"))
                .collect::<Result<Vec<_>, _>>()?;
            let data = match &mut stage.engine {
                Engine::Decode => Data::Series(selected.clone()),
                Engine::Clean(pipeline) => Data::Series(pipeline.process(input[0].series()?)?),
                Engine::Segment(segmenter) => {
                    Data::Series(segmenter.process(input[0].series()?, complete)?)
                }
                Engine::Sax { w, a } => Data::Series(
                    input[0]
                        .series()?
                        .iter()
                        .map(|e| sax_word(e, *w, *a, true, &self.spec_hash))
                        .collect::<Result<Vec<_>, _>>()?,
                ),
                Engine::Stats(engine) => {
                    let mut records = Vec::new();
                    for episode in input[0].series()? {
                        records.extend(engine.on_episode(episode)?);
                    }
                    if complete {
                        records.extend(engine.flush()?);
                    }
                    Data::Stats(records)
                }
                Engine::Vector(stream) => {
                    let node = self.fragment["nodes"]
                        .as_array()
                        .ok_or("nodes missing")?
                        .iter()
                        .find(|n| n["id"] == stage.id)
                        .ok_or("vector node missing")?;
                    let names = node["params"]["names"]
                        .as_array()
                        .map(|a| {
                            a.iter()
                                .map(|v| text(v, "input name").map(str::to_string))
                                .collect::<Result<Vec<_>, _>>()
                        })
                        .transpose()?
                        .unwrap_or_else(|| (1..=input.len()).map(|i| format!("in{i}")).collect());
                    let mut points = Vec::new();
                    for (name, data) in names.iter().zip(input) {
                        for episode in data.series()? {
                            points.extend(stream.add(
                                name,
                                &episode.series.series_id,
                                &episode.series.metric,
                                &episode.series.tags,
                                &episode.timestamps()?,
                                &episode.values,
                                episode.t_end,
                            )?);
                        }
                    }
                    if complete {
                        points.extend(stream.flush()?);
                    }
                    Data::Vectors(points)
                }
                Engine::Pair(stream) => {
                    let mut records = Vec::new();
                    for (i, data) in input.iter().enumerate() {
                        for episode in data.series()? {
                            records.extend(stream.add(if i == 0 { "a" } else { "b" }, episode)?);
                        }
                    }
                    Data::Series(records)
                }
                Engine::Dq {
                    expectation,
                    checks,
                } => {
                    let Data::Stats(records) = input[0] else {
                        return Err("DQ requires statistics input".into());
                    };
                    let mut signals = Vec::new();
                    for record in records {
                        for result in stats::evaluate(record, expectation, None)? {
                            if checks.contains(&result.check) {
                                signals.push(result.signal(job, &self.site, &self.spec_hash)?);
                            }
                        }
                    }
                    Data::Signals(signals)
                }
            };
            let data = if let (Data::Series(records), Some(ids)) =
                (&data, self.context.series_by_node.get(&stage.id))
            {
                Data::Series(
                    records
                        .iter()
                        .filter(|e| ids.contains(&e.series.series_id))
                        .cloned()
                        .collect(),
                )
            } else {
                data
            };
            check_data(&data, self.limits)?;
            outputs.insert(stage.id.clone(), data);
        }
        let data = outputs
            .remove(&self.sink_from)
            .ok_or("sink input missing")?;
        let records = match data {
            Data::Series(records) => records
                .iter()
                .map(episode_record)
                .collect::<Result<Vec<_>, String>>()?,
            Data::Stats(records) => records
                .iter()
                .map(|s| {
                    Ok(OutputRecord {
                        key: s.series_id.clone(),
                        timestamp: positive_millis(s.window_end_micros)?,
                        payload: s.encode_soe()?,
                    })
                })
                .collect::<Result<Vec<_>, String>>()?,
            Data::Signals(signals) => signals
                .iter()
                .map(|s| {
                    Ok(OutputRecord {
                        key: text(&s["seriesId"], "signal series")?.into(),
                        timestamp: positive_millis(parse_instant(text(
                            &s["at"],
                            "signal timestamp",
                        )?)?)?,
                        payload: serde_json::to_vec(s)
                            .map_err(|e| format!("cannot encode signal: {e}"))?,
                    })
                })
                .collect::<Result<Vec<_>, String>>()?,
            Data::Vectors(points) => points
                .iter()
                .map(|p| {
                    let key = crate::pairs::vector_key(p);
                    let record =
                        crate::pairs::vector_json(p, &self.sink_from, job, &self.spec_hash)?;
                    Ok(OutputRecord {
                        key,
                        timestamp: positive_millis(p.to_micros)?,
                        payload: serde_json::to_vec(&record)
                            .map_err(|e| format!("cannot encode vector: {e}"))?,
                    })
                })
                .collect::<Result<Vec<_>, String>>()?,
        };
        for record in &records {
            let identity = validate_record(
                record,
                self.output_type,
                usize::MAX,
                self.limits.max_output_samples,
            )?;
            self.output_owners.insert(identity, true);
        }
        if self.output_owners.len() > self.limits.max_series {
            return Err("output series limit exceeded".into());
        }
        Ok(records)
    }

    fn snapshot(&self) -> Result<Value, String> {
        let mut states = BTreeMap::new();
        for stage in &self.stages {
            let state = match &stage.engine {
                Engine::Clean(p) => p.snapshot()?,
                Engine::Stats(p) => p.snapshot()?,
                Engine::Segment(p) => p.snapshot()?,
                Engine::Vector(p) => p.snapshot()?,
                Engine::Pair(p) => p.snapshot()?,
                _ => continue,
            };
            states.insert(stage.id.clone(), state);
        }
        self.validate_retained_series(&states)?;
        let state = json!({"schema":STATE_ABI,"specHash":self.spec_hash,"createdAt":self.created_at.to_string(),"site":self.site,
            "fragment":self.fragment,"context":self.context,"nodes":states,"outputOwners":self.output_owners,"series":self.input_series});
        if serde_json::to_vec(&state)
            .map_err(|e| format!("cannot encode catalog state: {e}"))?
            .len()
            > self.limits.max_state_bytes
        {
            return Err("catalog retained state exceeds stateBytes".into());
        }
        Ok(state)
    }

    fn validate_retained_series(&self, states: &BTreeMap<String, Value>) -> Result<(), String> {
        fn require_subset(
            actual: &BTreeSet<String>,
            allowed: &BTreeSet<String>,
            node: &str,
        ) -> Result<(), String> {
            if !actual.is_subset(allowed) {
                return Err(format!(
                    "node {node}: retained series is outside its actual upstream inventory"
                ));
            }
            Ok(())
        }
        fn keys(state: &Value, field: &str) -> Result<BTreeSet<String>, String> {
            state[field]
                .as_object()
                .map(|entries| entries.keys().cloned().collect())
                .ok_or_else(|| format!("retained {field} inventory must be an object"))
        }
        let raw: BTreeSet<_> = self
            .input_series
            .keys()
            .filter(|id| self.expected.is_empty() || self.expected.contains(*id))
            .cloned()
            .collect();
        let mut outputs: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for stage in &self.stages {
            let inputs = stage
                .inputs
                .iter()
                .map(|id| {
                    outputs
                        .get(id)
                        .ok_or("retained node input was not evaluated")
                })
                .collect::<Result<Vec<_>, _>>()?;
            let mut output = match &stage.engine {
                Engine::Decode => raw.clone(),
                Engine::Clean(pipeline) => {
                    let input = inputs.first().ok_or("cleaning retained input is missing")?;
                    let state = states
                        .get(&stage.id)
                        .ok_or("cleaning retained state is missing")?;
                    require_subset(&keys(state, "series")?, input, &stage.id)?;
                    if pipeline.is_passthrough() {
                        (*input).clone()
                    } else {
                        keys(state, "outputOwners")?
                    }
                }
                Engine::Segment(_) => {
                    let input = inputs
                        .first()
                        .ok_or("segmentation retained input is missing")?;
                    let state = states
                        .get(&stage.id)
                        .ok_or("segmentation retained state is missing")?;
                    let series = keys(state, "series")?;
                    require_subset(&series, input, &stage.id)?;
                    (*input).clone()
                }
                Engine::Sax { .. } => inputs
                    .first()
                    .ok_or("SAX retained input is missing")?
                    .iter()
                    .cloned()
                    .collect(),
                Engine::Stats(engine) => {
                    let input = inputs
                        .first()
                        .ok_or("statistics retained input is missing")?;
                    let series = engine.series_ids();
                    require_subset(&series, input, &stage.id)?;
                    series
                }
                Engine::Pair(engine) => {
                    let retained = engine.retained_series_by_input()?;
                    for (name, series) in retained {
                        let input = match name.as_str() {
                            "a" => inputs.first(),
                            "b" => inputs.get(1),
                            _ => return Err("pair retained input name is invalid".into()),
                        }
                        .ok_or("pair retained input is missing")?;
                        require_subset(&series, input, &stage.id)?;
                    }
                    let first = inputs.first().ok_or("pair retained input is missing")?;
                    let second = inputs.get(1).unwrap_or(first);
                    engine.possible_output_series(first, second, self.limits.max_series)?
                }
                Engine::Vector(engine) => {
                    engine.validate_series_identity()?;
                    let state = states
                        .get(&stage.id)
                        .ok_or("vector retained state is missing")?;
                    let declarations = state["inputs"]
                        .as_array()
                        .ok_or("vector retained declarations are missing")?;
                    if declarations.len() != inputs.len() {
                        return Err("vector retained input count differs from its plan".into());
                    }
                    let retained = engine.retained_series_by_input()?;
                    for (index, declaration) in declarations.iter().enumerate() {
                        let name = declaration[0]
                            .as_str()
                            .ok_or("vector retained input name is missing")?;
                        if let Some(series) = retained.get(name) {
                            require_subset(series, inputs[index], &stage.id)?;
                        }
                    }
                    if retained.keys().any(|name| {
                        !declarations
                            .iter()
                            .any(|declaration| declaration[0] == *name)
                    }) {
                        return Err("vector retained input name differs from its plan".into());
                    }
                    engine.possible_output_series()?
                }
                Engine::Dq { .. } => inputs
                    .first()
                    .ok_or("DQ retained input is missing")?
                    .iter()
                    .cloned()
                    .collect(),
            };
            if matches!(
                stage.engine,
                Engine::Decode
                    | Engine::Clean(_)
                    | Engine::Segment(_)
                    | Engine::Sax { .. }
                    | Engine::Pair(_)
            ) {
                if let Some(selected) = self.context.series_by_node.get(&stage.id) {
                    output.retain(|id| selected.contains(id));
                }
            }
            if output.len() > self.limits.max_series {
                return Err("retained node output inventory exceeds series capacity".into());
            }
            outputs.insert(stage.id.clone(), output);
        }
        let output = outputs
            .get(&self.sink_from)
            .ok_or("retained sink input is missing")?;
        require_subset(
            &self.output_owners.keys().cloned().collect(),
            output,
            &self.sink_from,
        )
    }

    fn restore(&mut self, state: &Value) -> Result<(), String> {
        let mut next = self.clone();
        let expected = next.snapshot()?;
        fields(
            state,
            &[
                "schema",
                "specHash",
                "createdAt",
                "site",
                "fragment",
                "context",
                "nodes",
                "outputOwners",
                "series",
            ],
        )?;
        for key in [
            "schema",
            "specHash",
            "createdAt",
            "site",
            "fragment",
            "context",
        ] {
            if state[key] != expected[key] {
                return Err("catalog checkpoint identity or ABI mismatch".into());
            }
        }
        if state["nodes"]
            .as_object()
            .map(|o| o.keys().collect::<Vec<_>>())
            != expected["nodes"]
                .as_object()
                .map(|o| o.keys().collect::<Vec<_>>())
        {
            return Err("catalog checkpoint node inventory mismatch".into());
        }
        for stage in &mut next.stages {
            match &mut stage.engine {
                Engine::Clean(p) => p.restore(&state["nodes"][&stage.id])?,
                Engine::Stats(p) => p.restore(&state["nodes"][&stage.id])?,
                Engine::Segment(p) => p.restore(&state["nodes"][&stage.id])?,
                Engine::Vector(p) => p.restore(&state["nodes"][&stage.id])?,
                Engine::Pair(p) => p.restore(&state["nodes"][&stage.id])?,
                _ => {}
            }
        }
        next.output_owners = serde_json::from_value(state["outputOwners"].clone())
            .map_err(|_| "invalid catalog output ownership")?;
        next.input_series = serde_json::from_value(state["series"].clone())
            .map_err(|_| "invalid catalog input ownership")?;
        if next
            .input_series
            .iter()
            .any(|(id, value)| !valid_id(id) || !value)
            || next.input_series.len() > next.limits.max_series
        {
            return Err("invalid catalog input ownership".into());
        }
        if next
            .output_owners
            .iter()
            .any(|(id, value)| !valid_id(id) || !value)
            || next.output_owners.len() > next.limits.max_series
        {
            return Err("invalid catalog output ownership".into());
        }
        next.snapshot()?;
        *self = next;
        Ok(())
    }
}

fn check_data(data: &Data, limits: Limits) -> Result<(), String> {
    let samples = match data {
        Data::Series(v) => v.iter().try_fold(0usize, |n, e| {
            n.checked_add(e.values.len())
                .ok_or("output sample count overflow")
        })?,
        Data::Stats(v) => v.len(),
        Data::Vectors(v) => v.iter().try_fold(0usize, |n, p| {
            n.checked_add(p.values.len())
                .ok_or("output sample count overflow")
        })?,
        Data::Signals(v) => v.len(),
    };
    if samples > limits.max_output_samples {
        return Err("output sample limit exceeded".into());
    }
    Ok(())
}

fn episode_record(e: &Episode) -> Result<OutputRecord, String> {
    Ok(OutputRecord {
        key: e.series.series_id.clone(),
        timestamp: positive_millis(e.t_start)?,
        payload: crate::wire::encode(e)?,
    })
}
fn positive_millis(t: i64) -> Result<i64, String> {
    let m = t.div_euclid(1000);
    if m <= 0 {
        Err("local Kafka replay requires positive millisecond timestamps".into())
    } else {
        Ok(m)
    }
}
fn parse_instant(s: &str) -> Result<i64, String> {
    let t = time::OffsetDateTime::parse(s, &time::format_description::well_known::Rfc3339)
        .map_err(|_| "invalid record timestamp")?;
    i64::try_from(t.unix_timestamp_nanos() / 1000).map_err(|_| "record timestamp overflow".into())
}
fn valid_id(id: &str) -> bool {
    id.len() == 16
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn text<'a>(v: &'a Value, name: &str) -> Result<&'a str, String> {
    v.as_str()
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| format!("{name} must be a nonempty string"))
}
fn fields(v: &Value, allowed: &[&str]) -> Result<(), String> {
    if v.as_object()
        .ok_or("expected an object")?
        .keys()
        .any(|k| !allowed.contains(&k.as_str()))
    {
        Err("unsupported catalog field".into())
    } else {
        Ok(())
    }
}
fn inputs(node: &Value) -> Result<Vec<String>, String> {
    match node.get("in") {
        None => Ok(Vec::new()),
        Some(v) => v
            .as_array()
            .ok_or_else(|| "node inputs must be an array".into())
            .and_then(|a| {
                a.iter()
                    .map(|v| text(v, "node input").map(str::to_string))
                    .collect()
            }),
    }
}
fn ids(value: Option<&Value>, cap: usize) -> Result<BTreeSet<String>, String> {
    let Some(value) = value else {
        return Ok(BTreeSet::new());
    };
    let a = value
        .as_array()
        .ok_or("series inventory must be an array")?;
    let mut set = BTreeSet::new();
    for v in a {
        let id = text(v, "series id")?;
        if !valid_id(id) || !set.insert(id.to_string()) {
            return Err("series inventory needs unique canonical IDs".into());
        }
    }
    if set.len() > cap {
        return Err("series inventory exceeds capacity".into());
    }
    Ok(set)
}
fn inventory(
    id: &str,
    nodes: &BTreeMap<String, Value>,
    context: &Context,
    expected: &BTreeSet<String>,
) -> Result<BTreeSet<String>, String> {
    let ids = if let Some(ids) = context.series_by_node.get(id) {
        ids.iter().cloned().collect()
    } else if nodes[id]["op"] == "decode.avro-soe" {
        expected.clone()
    } else {
        BTreeSet::new()
    };
    if ids.is_empty() {
        Err(format!(
            "node {id}: bind the output series inventory before building pair or alignment"
        ))
    } else {
        Ok(ids)
    }
}
fn require_inputs(
    inputs: &[String],
    types: &BTreeMap<String, RecordType>,
    kind: RecordType,
    min: usize,
    max: usize,
) -> Result<(), String> {
    if inputs.len() < min
        || inputs.len() > max
        || inputs.iter().any(|i| types.get(i) != Some(&kind))
    {
        Err("operator input count or type mismatch".into())
    } else {
        Ok(())
    }
}
fn visit(
    id: &str,
    nodes: &BTreeMap<String, Value>,
    active: &mut BTreeSet<String>,
    seen: &mut BTreeSet<String>,
    out: &mut Vec<String>,
) -> Result<(), String> {
    if seen.contains(id) {
        return Ok(());
    }
    if !active.insert(id.into()) {
        return Err("pipeline graph contains a cycle".into());
    }
    let node = nodes
        .get(id)
        .ok_or("pipeline graph references a missing node")?;
    for input in inputs(node)? {
        visit(&input, nodes, active, seen, out)?;
    }
    active.remove(id);
    seen.insert(id.into());
    out.push(id.into());
    Ok(())
}

fn validate_record(
    record: &OutputRecord,
    kind: RecordType,
    max_bytes: usize,
    max_samples: usize,
) -> Result<String, String> {
    if record.payload.len() > max_bytes || record.timestamp <= 0 {
        return Err("prepared output size or timestamp mismatch".into());
    }
    let (key, timestamp, identity) = match kind {
        RecordType::Series => {
            let e = crate::wire::decode(&record.payload, max_bytes, max_samples)?;
            (
                e.series.series_id.clone(),
                positive_millis(e.t_start)?,
                e.series.series_id,
            )
        }
        RecordType::Stats => {
            let s = StatsRecord::decode_soe(&record.payload, max_bytes)?;
            if !valid_id(&s.series_id) {
                return Err("statistics needs a canonical series identity".into());
            }
            (
                s.series_id.clone(),
                positive_millis(s.window_end_micros)?,
                s.series_id,
            )
        }
        RecordType::Signal => {
            let s = tsx_contracts::parse_json(
                std::str::from_utf8(&record.payload).map_err(|_| "invalid signal UTF-8")?,
                max_bytes,
            )
            .map_err(str::to_string)?;
            fields(
                &s,
                &[
                    "schema",
                    "id",
                    "job",
                    "site",
                    "seriesId",
                    "kind",
                    "at",
                    "window",
                    "check",
                    "status",
                    "value",
                    "threshold",
                    "message",
                    "specHash",
                ],
            )?;
            let id = text(&s["seriesId"], "signal series")?;
            if s["schema"] != "opentsx.signal.v1" || s["kind"] != "dq" || !valid_id(id) {
                return Err("invalid DQ signal record".into());
            }
            fields(&s["window"], &["from", "to"])?;
            if ![
                "COMPLETENESS",
                "PLAUSIBLE_RANGE",
                "FLATLINE",
                "GAPS",
                "FRESHNESS",
            ]
            .contains(&text(&s["check"], "signal check")?)
                || !["SUCCESS", "FAILED", "ABORTED"].contains(&text(&s["status"], "signal status")?)
                || !(s["value"].is_null() || s["value"].as_f64().is_some_and(f64::is_finite))
                || !s["threshold"].is_string()
                || !s["message"].is_string()
                || !valid_hash(text(&s["specHash"], "signal specHash")?)
            {
                return Err("invalid DQ signal fields".into());
            }
            let from = text(&s["window"]["from"], "signal window start")?;
            let to = text(&s["window"]["to"], "signal window end")?;
            if parse_instant(from)? >= parse_instant(to)? || s["at"] != to {
                return Err("invalid DQ signal extent".into());
            }
            let expected = xxh64_hex(
                format!(
                    "{}|{}|{id}|dq|{to}|{}|{from}|{to}",
                    text(&s["job"], "signal job")?,
                    text(&s["site"], "signal site")?,
                    text(&s["check"], "signal check")?
                )
                .as_bytes(),
            );
            if s["id"] != expected {
                return Err("invalid DQ signal identity".into());
            }
            (id.into(), positive_millis(parse_instant(to)?)?, id.into())
        }
        RecordType::Vector => {
            let v = tsx_contracts::parse_json(
                std::str::from_utf8(&record.payload).map_err(|_| "invalid vector UTF-8")?,
                max_bytes,
            )
            .map_err(str::to_string)?;
            fields(
                &v,
                &[
                    "schema",
                    "vector",
                    "key",
                    "ts",
                    "window",
                    "clock",
                    "components",
                    "values",
                    "missing",
                    "provenance",
                ],
            )?;
            let key: BTreeMap<String, String> =
                serde_json::from_value(v["key"].clone()).map_err(|_| "invalid vector key")?;
            let key = key
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join(",");
            let values = v["values"].as_array().ok_or("invalid vector values")?;
            let components = v["components"]
                .as_array()
                .ok_or("invalid vector components")?;
            if v["schema"] != "opentsx.vector.v1"
                || values.is_empty()
                || values.len() != components.len()
                || values.len() > max_samples
                || values.iter().any(|v| !v.is_null() && v.as_f64().is_none())
                || v["missing"].as_u64()
                    != Some(values.iter().filter(|v| v.is_null()).count() as u64)
            {
                return Err("invalid vector record".into());
            }
            if v["ts"] != v["window"]["to"]
                || parse_instant(text(&v["window"]["from"], "vector from")?)?
                    >= parse_instant(text(&v["window"]["to"], "vector to")?)?
            {
                return Err("invalid vector extent".into());
            }
            fields(&v["window"], &["from", "to"])?;
            fields(&v["provenance"], &["dag", "specHash"])?;
            text(&v["vector"], "vector name")?;
            text(&v["provenance"]["dag"], "vector DAG")?;
            if !valid_hash(text(&v["provenance"]["specHash"], "vector specHash")?)
                || components.iter().any(|c| {
                    c.as_str().is_none_or(|s| {
                        s.is_empty()
                            || s.split('.').count() > 2
                            || s.split('.').any(|p| {
                                p.is_empty()
                                    || !p.bytes().all(|b| {
                                        b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_'
                                    })
                            })
                    })
                })
                || components
                    .iter()
                    .map(Value::as_str)
                    .collect::<BTreeSet<_>>()
                    .len()
                    != components.len()
            {
                return Err("invalid vector components or provenance".into());
            }
            let clock = text(&v["clock"], "vector clock")?;
            let width = parse_instant(text(&v["window"]["to"], "vector to")?)?
                .checked_sub(parse_instant(text(&v["window"]["from"], "vector from")?)?)
                .ok_or("vector width overflow")?;
            if !clock.starts_with("PT")
                || !clock.ends_with('S')
                || stats::parse_duration(clock)? != width
            {
                return Err("vector clock differs from its extent".into());
            }
            let identity = xxh64_hex(key.as_bytes());
            (
                key,
                positive_millis(parse_instant(text(&v["ts"], "vector timestamp")?)?)?,
                identity,
            )
        }
    };
    if record.key != key || record.timestamp != timestamp {
        return Err("prepared output identity or timestamp mismatch".into());
    }
    Ok(identity)
}

fn valid_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn parameters(op: &str, value: Option<&Value>) -> Result<Value, String> {
    let mut params = value.cloned().unwrap_or_else(|| json!({}));
    let object = params
        .as_object_mut()
        .ok_or("operator params must be an object")?;
    if object.values().any(Value::is_null) {
        return Err("catalog parameter values cannot be null".into());
    }
    if op == "stats.welford-ddsketch" {
        object
            .entry("windows")
            .or_insert_with(|| json!(["PT10M", "PT1H"]));
        object.entry("alpha").or_insert_with(|| json!(0.01));
    }
    if op == "dq.result" {
        object.entry("checks").or_insert_with(|| {
            json!([
                "COMPLETENESS",
                "PLAUSIBLE_RANGE",
                "FLATLINE",
                "GAPS",
                "FRESHNESS"
            ])
        });
    }
    if op == "pair.xcorr" {
        if let Some(lags) = object.get("lags") {
            if lags.as_u64().is_none_or(|v| v > 1_073_741_823) {
                return Err("lags must be a non-negative integer".into());
            }
        }
        if object.get("window").is_some_and(|v| !v.is_string()) {
            return Err("pair window must be catalog text".into());
        }
        for name in ["step", "every", "maxAge", "minCoverage"] {
            if object
                .get(name)
                .is_some_and(|v| v.as_f64().is_none_or(|n| !n.is_finite()))
            {
                return Err(format!("pair {name} must be a finite number"));
            }
        }
        if object
            .get("minCoverage")
            .and_then(Value::as_f64)
            .is_some_and(|n| !(0.0..=1.0).contains(&n))
        {
            return Err("minCoverage must be within 0 and 1".into());
        }
        for (name, allowed) in [
            (
                "agg",
                &["MEAN", "MIN", "MAX", "FIRST", "LAST", "SUM", "COUNT"][..],
            ),
            ("fill", &["none", "as-of"][..]),
            ("pairing", &["same", "intersect"][..]),
        ] {
            if object
                .get(name)
                .is_some_and(|v| v.as_str().is_none_or(|s| !allowed.contains(&s)))
            {
                return Err(format!("invalid pair {name}"));
            }
        }
    }
    Ok(params)
}
