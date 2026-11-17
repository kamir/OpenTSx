mod common;

use common::{episode, request, ARTIFACT_DIGEST};
use serde_json::{json, Value};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use tsx_runner::admission::{admit, Admitted};
use tsx_runner::broker::{Broker, InputRecord, OutputRecord};
use tsx_runner::checkpoint::Store;
use tsx_runner::{runner, wire};

static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "opentsx-runner-{}-{}",
            std::process::id(),
            NEXT_DIRECTORY.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn checkpoint(&self) -> PathBuf {
        self.0.join("completed.json")
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[derive(Default)]
struct FakeBroker {
    input: Vec<InputRecord>,
    output: Vec<OutputRecord>,
    reads: usize,
    sends: usize,
    lose_ack: bool,
    fail_checkpoint: Option<PathBuf>,
}

impl FakeBroker {
    fn with_input() -> Self {
        let episodes = [
            episode(vec![1.0, f64::NAN, 3.0], 10_000_000),
            episode(vec![4.0, 5.0], 13_000_000),
        ];
        Self {
            input: episodes
                .iter()
                .enumerate()
                .map(|(offset, episode)| InputRecord {
                    partition: 0,
                    offset: offset as i64,
                    key: episode.series.series_id.as_bytes().to_vec(),
                    payload: wire::encode(episode).unwrap(),
                })
                .collect(),
            ..Self::default()
        }
    }
}

impl Broker for FakeBroker {
    fn read(&mut self, _: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reads += 1;
        Ok(self.input.clone())
    }

    fn publish(&mut self, _: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        self.sends += 1;
        if self.lose_ack {
            self.output.extend(records.iter().take(1).cloned());
            return Err("a delivery acknowledgement was lost".into());
        }
        self.output.extend_from_slice(records);
        if let Some(path) = &self.fail_checkpoint {
            fs::create_dir(path).unwrap();
        }
        Ok(())
    }
}

fn local_request() -> Value {
    let mut request = request();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    assignment["inputs"][0]["partitionCount"] = json!("1");
    assignment["inputs"][0]["positions"] = json!([{ "partition":"0", "from":"0", "to":"2" }]);
    request["assignmentText"] = json!(assignment.to_string());
    request
}

fn admitted(value: &Value) -> Admitted {
    admit(&value.to_string(), ARTIFACT_DIGEST).unwrap()
}

#[test]
fn acknowledged_batch_commits_all_positions_state_and_exact_retry_without_io() {
    let directory = Directory::new();
    let request = local_request();
    let mut broker = FakeBroker::with_input();
    let receipt =
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint()).unwrap();
    assert_eq!((receipt.input_records, receipt.output_records), (2, 2));
    assert_eq!(receipt.positions[0].next, "2");
    assert_eq!((broker.reads, broker.sends), (1, 1));
    let saved = fs::read(directory.checkpoint()).unwrap();
    let mut unavailable = FakeBroker::default();
    let retry = runner::execute(
        admitted(&request),
        &mut unavailable,
        &directory.checkpoint(),
    )
    .unwrap();
    assert_eq!(receipt, retry);
    assert_eq!((unavailable.reads, unavailable.sends), (0, 0));
    assert_eq!(saved, fs::read(directory.checkpoint()).unwrap());
    let completed: Value = serde_json::from_slice(&saved).unwrap();
    assert_eq!(
        completed["payload"]["state"]["schema"],
        "opentsx.rust.clean-state.v1"
    );
}

#[test]
fn partial_acknowledgements_leave_no_checkpoint_and_replay_identical_bytes() {
    let directory = Directory::new();
    let request = local_request();
    let mut failed = FakeBroker {
        lose_ack: true,
        ..FakeBroker::with_input()
    };
    assert!(runner::execute(admitted(&request), &mut failed, &directory.checkpoint()).is_err());
    assert!(!directory.checkpoint().exists());
    assert_eq!(failed.output.len(), 1);
    let mut retry = FakeBroker::with_input();
    runner::execute(admitted(&request), &mut retry, &directory.checkpoint()).unwrap();
    assert_eq!(failed.output[0].payload, retry.output[0].payload);
    assert_eq!(failed.output[0].key, retry.output[0].key);
    assert_eq!(failed.output[0].timestamp, retry.output[0].timestamp);
}

#[test]
fn crash_after_ack_before_checkpoint_replays_the_whole_fixed_cut() {
    let directory = Directory::new();
    let request = local_request();
    let pending = directory.0.join(".completed.json.pending");
    let mut failed = FakeBroker {
        fail_checkpoint: Some(pending.clone()),
        ..FakeBroker::with_input()
    };
    assert!(runner::execute(admitted(&request), &mut failed, &directory.checkpoint()).is_err());
    assert_eq!(failed.output.len(), 2);
    assert!(!directory.checkpoint().exists());
    fs::remove_dir(pending).unwrap();
    let mut retry = FakeBroker::with_input();
    runner::execute(admitted(&request), &mut retry, &directory.checkpoint()).unwrap();
    assert_eq!(
        failed.output.iter().map(|r| &r.payload).collect::<Vec<_>>(),
        retry.output.iter().map(|r| &r.payload).collect::<Vec<_>>()
    );
}

#[test]
fn incomplete_pending_file_is_uncommitted_and_replaced_after_successful_replay() {
    let directory = Directory::new();
    let pending = directory.0.join(".completed.json.pending");
    fs::write(&pending, b"{partial").unwrap();
    runner::execute(
        admitted(&local_request()),
        &mut FakeBroker::with_input(),
        &directory.checkpoint(),
    )
    .unwrap();
    assert!(directory.checkpoint().is_file());
    assert!(!pending.exists());
}

#[test]
fn corrupt_checkpoint_or_changed_clock_is_refused_without_broker_io() {
    let directory = Directory::new();
    let request = local_request();
    runner::execute(
        admitted(&request),
        &mut FakeBroker::with_input(),
        &directory.checkpoint(),
    )
    .unwrap();
    let original = fs::read(directory.checkpoint()).unwrap();
    let mut changed = request.clone();
    changed["createdAtMicros"] = json!("1700000000000001");
    let mut broker = FakeBroker::default();
    assert!(
        runner::execute(admitted(&changed), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("conflicts")
    );
    let mut corrupt: Value = serde_json::from_slice(&original).unwrap();
    corrupt["payload"]["receipt"]["outputRecords"] = json!(999);
    fs::write(directory.checkpoint(), corrupt.to_string()).unwrap();
    assert!(
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("checksum")
    );
    assert_eq!((broker.reads, broker.sends), (0, 0));
}

#[test]
fn output_caps_and_missing_offsets_fail_before_any_send() {
    let directory = Directory::new();
    let mut request = local_request();
    request["limits"]["outputBytes"] = json!(1);
    let mut broker = FakeBroker::with_input();
    assert!(
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("output byte limit")
    );
    assert_eq!(broker.sends, 0);
    assert!(!directory.checkpoint().exists());
    let request = local_request();
    broker.input.remove(1);
    assert!(
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("omit")
    );
    assert_eq!(broker.sends, 0);
}

#[test]
fn wrong_keys_and_unknown_fingerprints_fail_before_any_send() {
    let directory = Directory::new();
    let request = local_request();
    let mut broker = FakeBroker::with_input();
    broker.input[0].key = b"0000000000000000".to_vec();
    assert!(
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("Kafka key")
    );
    broker = FakeBroker::with_input();
    broker.input[0].payload[2] ^= 1;
    assert!(runner::execute(admitted(&request), &mut broker, &directory.checkpoint()).is_err());
    assert_eq!(broker.sends, 0);
}

#[test]
fn checkpoint_has_one_local_owner_and_refuses_missing_parent() {
    let directory = Directory::new();
    let request = local_request();
    let owner = Store::open(&directory.checkpoint(), 65_536).unwrap();
    let mut broker = FakeBroker::with_input();
    assert!(
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("locked")
    );
    assert_eq!(broker.reads, 0);
    drop(owner);
    let absent = directory.0.join("absent/completed.json");
    assert!(runner::execute(admitted(&request), &mut broker, &absent)
        .unwrap_err()
        .contains("parent"));
    assert_eq!(broker.reads, 0);
}

#[cfg(unix)]
#[test]
fn checkpoint_symlinks_cannot_overwrite_another_file() {
    let directory = Directory::new();
    let other = directory.0.join("other.json");
    fs::write(&other, b"preserve").unwrap();
    std::os::unix::fs::symlink(&other, directory.checkpoint()).unwrap();
    let mut broker = FakeBroker::with_input();
    assert!(runner::execute(
        admitted(&local_request()),
        &mut broker,
        &directory.checkpoint()
    )
    .is_err());
    assert_eq!(fs::read(other).unwrap(), b"preserve");
    assert_eq!(broker.reads, 0);
}

#[test]
fn completed_empty_episode_retries_use_record_counts_not_sample_counts() {
    let directory = Directory::new();
    let mut request = local_request();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut fragment: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    fragment["nodes"] = json!([{ "id":"decode", "op":"decode.avro-soe" }]);
    fragment["sinks"][0]["from"] = json!("decode");
    let text = fragment.to_string();
    assignment["fragmentText"] = json!(text);
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&text));
    request["assignmentText"] = json!(assignment.to_string());
    request["sink"]["name"] = json!("decode");
    request["limits"]["samples"] = json!(1);
    let mut broker = FakeBroker::default();
    for offset in 0..2 {
        let mut value = episode(Vec::new(), 10_000_000 + offset * 1_000_000);
        value.t_end += 1_000_000;
        broker.input.push(InputRecord {
            partition: 0,
            offset,
            key: value.series.series_id.as_bytes().to_vec(),
            payload: wire::encode(&value).unwrap(),
        });
    }
    let receipt =
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint()).unwrap();
    assert_eq!(receipt.output_records, 2);
    let retry = runner::execute(
        admitted(&request),
        &mut FakeBroker::default(),
        &directory.checkpoint(),
    )
    .unwrap();
    assert_eq!(receipt, retry);
}

#[test]
fn completed_filtered_passthrough_retries_without_reading_or_publishing() {
    let directory = Directory::new();
    let mut request = local_request();
    let mut assignment: Value =
        serde_json::from_str(request["assignmentText"].as_str().unwrap()).unwrap();
    let mut fragment: Value =
        serde_json::from_str(assignment["fragmentText"].as_str().unwrap()).unwrap();
    fragment["nodes"] = json!([{ "id":"decode", "op":"decode.avro-soe" }]);
    fragment["sinks"][0]["from"] = json!("decode");
    let text = fragment.to_string();
    assignment["fragmentText"] = json!(text);
    assignment["fragmentHash"] = json!(tsx_contracts::sha256(&text));
    request["assignmentText"] = json!(assignment.to_string());
    request["sink"]["name"] = json!("decode");
    let mut broker = FakeBroker::with_input();
    let mut other = episode(vec![4.0, 5.0], 13_000_000);
    other.series.tags.insert("sensor".into(), "two".into());
    other.series.series_id =
        tsx_runner::model::series_id(&other.series.metric, &other.series.tags).unwrap();
    broker.input[1].key = other.series.series_id.as_bytes().to_vec();
    broker.input[1].payload = wire::encode(&other).unwrap();
    let receipt =
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint()).unwrap();
    assert_eq!((receipt.input_records, receipt.output_records), (2, 1));
    let mut unavailable = FakeBroker::default();
    let retry = runner::execute(
        admitted(&request),
        &mut unavailable,
        &directory.checkpoint(),
    )
    .unwrap();
    assert_eq!(receipt, retry);
    assert_eq!((unavailable.reads, unavailable.sends), (0, 0));
}

#[test]
fn unselected_input_series_still_count_toward_the_input_series_cap() {
    let directory = Directory::new();
    let mut request = local_request();
    request["limits"]["series"] = json!(1);
    let mut broker = FakeBroker::with_input();
    let mut other = episode(vec![4.0, 5.0], 13_000_000);
    other.series.tags.insert("sensor".into(), "two".into());
    other.series.series_id =
        tsx_runner::model::series_id(&other.series.metric, &other.series.tags).unwrap();
    broker.input[1].key = other.series.series_id.as_bytes().to_vec();
    broker.input[1].payload = wire::encode(&other).unwrap();
    assert!(
        runner::execute(admitted(&request), &mut broker, &directory.checkpoint())
            .unwrap_err()
            .contains("input series limit")
    );
    assert_eq!(broker.sends, 0);
    assert!(!directory.checkpoint().exists());
}

#[test]
fn completed_receipt_requires_exact_cut_cardinality() {
    let directory = Directory::new();
    let request = local_request();
    runner::execute(
        admitted(&request),
        &mut FakeBroker::with_input(),
        &directory.checkpoint(),
    )
    .unwrap();
    {
        let store = Store::open(&directory.checkpoint(), 65_536).unwrap();
        let mut completion = store.load().unwrap().unwrap();
        completion.receipt.input_records = 0;
        store.commit(&completion).unwrap();
    }
    let mut broker = FakeBroker::default();
    assert!(runner::execute(admitted(&request), &mut broker, &directory.checkpoint()).is_err());
    assert_eq!(broker.reads, 0);
}

#[test]
fn zero_and_negative_transport_timestamps_are_refused_before_publication() {
    for start in [0, 999, -1_000] {
        let directory = Directory::new();
        let mut broker = FakeBroker::with_input();
        let value = episode(vec![1.0], start);
        broker.input[0].payload = wire::encode(&value).unwrap();
        assert!(runner::execute(
            admitted(&local_request()),
            &mut broker,
            &directory.checkpoint()
        )
        .is_err());
        assert_eq!(broker.sends, 0);
        assert!(!directory.checkpoint().exists());
    }
}
