use serde_json::{json, Value};
use std::collections::BTreeMap;

pub const ARTIFACT_DIGEST: &str = concat!(
    "sha256:",
    "aaaaaaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaa"
);
pub const INPUT_TOPIC: &str = "tsx.learning.episodes.v2";
pub const OUTPUT_TOPIC: &str = "tsx.learning.clean.v2";

pub fn episode(values: Vec<f64>, start: i64) -> tsx_runner::model::Episode {
    use tsx_runner::model::{summary, Episode, Provenance, SeriesKey, TimeEncoding};
    let tags = BTreeMap::from([("sensor".into(), "one".into())]);
    let series = SeriesKey {
        metric: "learning.value".into(),
        series_id: tsx_runner::model::series_id("learning.value", &tags).unwrap(),
        tags,
        unit: Some("kW".into()),
    };
    let count = i32::try_from(values.len()).unwrap();
    Episode {
        episode_id: format!("input-{start}"),
        series,
        t_start: start,
        t_end: start
            .checked_add(i64::from(count).checked_mul(1_000_000).unwrap())
            .unwrap(),
        count,
        time_encoding: TimeEncoding::Regular,
        interval_micros: Some(1_000_000),
        time_deltas: None,
        summary: summary(&values),
        values,
        quality: None,
        segmentation: None,
        labels: BTreeMap::new(),
        bucket_id: None,
        revision: 0,
        provenance: Provenance {
            source: "fixture".into(),
            producer: "fixture".into(),
            created_at: 0,
            pipeline: None,
            parent_episode_id: None,
        },
    }
}

pub fn request() -> Value {
    let tags = BTreeMap::from([("sensor".into(), "one".into())]);
    let series_id = tsx_runner::model::series_id("learning.value", &tags).unwrap();
    let fragment = json!({
        "schema":"opentsx.dag.v2", "dag":"learning-flow",
        "source":{"kind":"kafscale-broker","topics":[INPUT_TOPIC],"series":[series_id]},
        "nodes":[{"id":"decode","op":"decode.avro-soe"},
            {"id":"convert","op":"convert.unit","in":["decode"],"params":{"factor":2,"offset":1,"unit":"MW"}}],
        "sinks":[{"from":"convert","schema":"opentsx.episode.v2","topic":OUTPUT_TOPIC,
            "signal":{"system":"learning","id":"plant/sensor","grade":"clean"},"origin":"SYNTHETIC"}]
    }).to_string();
    let assignment = json!({
        "schema":"opentsx.execution-assignment.v1","deployment":"learning-deployment","generation":"1",
        "assignment":"assignment-1","attempt":"attempt-1","fence":"1","fragment":"fragment-1",
        "site":"local","node":"worker-1","runtime":"rust","catalog":"tsx-catalog/1",
        "artifactDigest":ARTIFACT_DIGEST,"specHash":tsx_contracts::sha256(&fragment),"sourceDagText":fragment,
        "fragmentHash":tsx_contracts::sha256(&fragment),"fragmentText":fragment,"mode":"bounded",
        "inputs":[{"binding":"local","topic":INPUT_TOPIC,"partitionCount":"3","positions":[
            {"partition":"0","from":"0","to":"2"},
            {"partition":"1","from":"0","to":"3"},
            {"partition":"2","from":"2","to":"3"}]}]
    });
    json!({"schema":"opentsx.local-bounded-run.v1","assignmentText":assignment.to_string(),
        "bindings":{"local":{"bootstrap":"localhost:9092","inputs":[INPUT_TOPIC],"outputs":[OUTPUT_TOPIC]}},
        "sink":{"name":"plant-sensor","binding":"local","topic":OUTPUT_TOPIC,"partitionCount":"2"},
        "createdAtMicros":"1700000000000000",
        "limits":{"records":10,"payloadBytes":1024,"inputBytes":65536,"samples":1000,
            "outputBytes":65536,"series":4,"stateBytes":16384}})
}
