#[path = "support/b1_control.rs"]
mod control;
#[path = "support/b1_native_read.rs"]
mod native_read;

use rdkafka::admin::{AdminClient, AdminOptions, NewTopic, TopicReplication, TopicResult};
use rdkafka::client::DefaultClientContext;
use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::message::Message;
use rdkafka::producer::{BaseProducer, BaseRecord, DeliveryResult, Producer, ProducerContext};
use rdkafka::ClientContext;
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::future::Future;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    mpsc, Arc,
};
use std::task::{Context, Poll, Wake, Waker};
use std::time::{Duration, Instant};

const TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Serialize)]
struct Case {
    family: &'static str,
    case: String,
    status: &'static str,
    detail: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    schema: &'static str,
    runner_version: &'static str,
    endpoint: String,
    required_families: usize,
    required_cases: usize,
    executed_families: usize,
    executed_cases: usize,
    passed_cases: usize,
    failed_cases: usize,
    cases: Vec<Case>,
}

fn main() {
    match execute() {
        Ok(report) => {
            let failed = report.failed_cases;
            match serde_json::to_string(&report) {
                Ok(json) => println!("{json}"),
                Err(error) => {
                    eprintln!("cannot encode conformance evidence: {error}");
                    std::process::exit(3);
                }
            }
            if failed != 0 {
                std::process::exit(3);
            }
        }
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(3);
        }
    }
}

fn execute() -> Result<Report, String> {
    if std::env::args().len() != 1 {
        return Err(
            "the B1 acceptance fixture takes KAFSCALE_BOOTSTRAP, not command arguments".into(),
        );
    }
    let bootstrap =
        std::env::var("KAFSCALE_BOOTSTRAP").map_err(|_| "KAFSCALE_BOOTSTRAP is required")?;
    validate_bootstrap(&bootstrap)?;
    let admin: AdminClient<DefaultClientContext> = ClientConfig::new()
        .set("bootstrap.servers", &bootstrap)
        .create()
        .map_err(|e| format!("cannot create the test admin client: {e}"))?;
    let reader = consumer(&bootstrap)?;
    let prefix = format!("tsx.b1conformance.{}", std::process::id());
    let mut cases = Vec::new();
    record(
        &mut cases,
        "topics.admin",
        "createsTopicsViaAdminApi",
        || {
            let topic = format!("{prefix}-admin.v2");
            create_topics(&admin, &[(topic.clone(), 3)])?;
            inventory(&reader, &topic, 3)?;
            let all = reader
                .fetch_metadata(None, TIMEOUT)
                .map_err(|e| format!("cannot list the test topics: {e}"))?;
            if !all.topics().iter().any(|item| item.name() == topic) {
                return Err("the created topic is absent from the metadata listing".into());
            }
            Ok("one acknowledged topic has the exact three-partition inventory".into())
        },
    );
    record(
        &mut cases,
        "topics.batch",
        "retainsEveryAcknowledgedTopicAfterBatchCreation",
        || {
            let mut topics = Vec::new();
            for batch in 0..10 {
                for partitions in 1..=3 {
                    topics.push((
                        format!("{prefix}-batch-{batch}-{partitions}.v2"),
                        partitions,
                    ));
                }
            }
            create_topics(&admin, &topics)?;
            for (topic, count) in &topics {
                inventory(&reader, topic, *count)?;
            }
            Ok("all thirty acknowledged topics retain their complete partition inventories".into())
        },
    );
    record(
        &mut cases,
        "records.order",
        "producesAndConsumesInOrderPerKey",
        || {
            let topic = format!("{prefix}-order.v2");
            create_topics(&admin, &[(topic.clone(), 3)])?;
            let input: Vec<_> = (0..200)
                .map(|index| {
                    (
                        format!("series-{}", index % 4),
                        index.to_string().into_bytes(),
                    )
                })
                .collect();
            produce(&bootstrap, &topic, "none", &input)?;
            let mut records = Vec::new();
            for partition in 0..3 {
                let (low, high) = reader
                    .fetch_watermarks(&topic, partition, TIMEOUT)
                    .map_err(|e| format!("cannot inspect ordered output: {e}"))?;
                if low != 0 || !(0..=200).contains(&high) {
                    return Err("the ordered test topic has an invalid range".into());
                }
                records.extend(native_read::read(
                    &reader,
                    &topic,
                    partition,
                    0,
                    high as usize,
                )?);
            }
            if records.len() != 200 {
                return Err("the ordered test did not receive all 200 records".into());
            }
            for key in 0..4 {
                let name = format!("series-{key}");
                let rows: Vec<_> = records
                    .iter()
                    .filter(|row| row.key == name.as_bytes())
                    .collect();
                if rows
                    .iter()
                    .map(|row| row.partition)
                    .collect::<BTreeSet<_>>()
                    .len()
                    != 1
                    || rows.iter().map(|row| &row.payload).ne(input
                        .iter()
                        .skip(key)
                        .step_by(4)
                        .map(|(_, value)| value))
                {
                    return Err("the test changed per-key order or partition placement".into());
                }
            }
            Ok("all 200 values preserve the four exact per-key sequences".into())
        },
    );
    for codec in ["none", "snappy", "lz4", "zstd"] {
        record(
            &mut cases,
            "records.codec",
            &format!("roundTripsEveryCompressionCodec:{codec}"),
            || {
                let topic = format!("{prefix}-codec-{codec}.v2");
                create_topics(&admin, &[(topic.clone(), 1)])?;
                let payload = payload(64 * 1024);
                produce(&bootstrap, &topic, codec, &[("k".into(), payload.clone())])?;
                let records = native_read::read(&reader, &topic, 0, 0, 1)?;
                if records[0].payload != payload || records[0].key != b"k" {
                    return Err("compression changed the exact payload or key".into());
                }
                Ok(format!("{codec} round-trips the exact 65536-byte payload"))
            },
        );
    }
    record(
        &mut cases,
        "records.large",
        "roundTripsOneMegabyteEpisode",
        || {
            let topic = format!("{prefix}-large.v2");
            create_topics(&admin, &[(topic.clone(), 1)])?;
            let payload = payload(1024 * 1024 - 4096);
            produce(&bootstrap, &topic, "zstd", &[("k".into(), payload.clone())])?;
            let records = native_read::read(&reader, &topic, 0, 0, 1)?;
            if records[0].payload != payload || records[0].key != b"k" {
                return Err("the large record did not round-trip exactly".into());
            }
            Ok("the exact 1044480-byte episode-like payload round-trips".into())
        },
    );
    record(
        &mut cases,
        "offsets.committed",
        "committedOffsetsAreReadableAfterRestart",
        || {
            let topic = format!("{prefix}-commit.v2");
            create_topics(&admin, &[(topic.clone(), 1)])?;
            let input: Vec<_> = (0..10)
                .map(|index| ("k".into(), format!("v{index}").into_bytes()))
                .collect();
            produce(&bootstrap, &topic, "zstd", &input)?;
            let committed = control::committed_after_restart(
                &bootstrap,
                &format!("{prefix}-group"),
                &topic,
                || {
                    let first = native_read::read(&reader, &topic, 0, 0, 1)?;
                    if first[0].payload != b"v0" {
                        return Err("the joined consumer did not read the first value".into());
                    }
                    Ok(())
                },
            )?;
            let restarted = consumer(&bootstrap)?;
            let records = native_read::read(&restarted, &topic, 0, committed, 1)?;
            if records[0].offset != 4 || records[0].payload != b"v4" {
                return Err("restart did not seek to the committed value".into());
            }
            Ok("a real member commits 4; a new connection fetches 4 without membership and reads v4".into())
        },
    );
    record(
        &mut cases,
        "offsets.explicit",
        "seeksToExplicitOffsetForIndexBasedReplay",
        || {
            let topic = format!("{prefix}-seek.v2");
            create_topics(&admin, &[(topic.clone(), 1)])?;
            let input: Vec<_> = (0..20)
                .map(|index| ("k".into(), format!("v{index}").into_bytes()))
                .collect();
            produce(&bootstrap, &topic, "zstd", &input)?;
            let (low, high) = reader
                .fetch_watermarks(&topic, 0, TIMEOUT)
                .map_err(|e| format!("cannot inspect seek bounds: {e}"))?;
            if (low, high) != (0, 20) {
                return Err("explicit seek input does not have offsets 0 through 19".into());
            }
            let records = native_read::read(&reader, &topic, 0, 13, 1)?;
            if records[0].offset != 13 || records[0].payload != b"v13" {
                return Err("explicit seek did not start at v13".into());
            }
            Ok("manual offset 13 reads exactly v13 from the verified [0,20) range".into())
        },
    );
    let passed = cases.iter().filter(|row| row.status == "pass").count();
    Ok(Report {
        schema: "opentsx.rust-b1-conformance.v1",
        runner_version: env!("CARGO_PKG_VERSION"),
        endpoint: bootstrap,
        required_families: 7,
        required_cases: 10,
        executed_families: cases
            .iter()
            .map(|row| row.family)
            .collect::<BTreeSet<_>>()
            .len(),
        executed_cases: cases.len(),
        passed_cases: passed,
        failed_cases: cases.len() - passed,
        cases,
    })
}

fn record(
    cases: &mut Vec<Case>,
    family: &'static str,
    name: &str,
    action: impl FnOnce() -> Result<String, String>,
) {
    let (status, detail) = match action() {
        Ok(detail) => ("pass", detail),
        Err(error) => ("fail", error),
    };
    cases.push(Case {
        family,
        case: name.into(),
        status,
        detail,
    });
}

fn validate_bootstrap(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 4096 || value.split(',').count() > 16 {
        return Err("invalid KAFSCALE_BOOTSTRAP address list".into());
    }
    for address in value.split(',') {
        let (host, port) = address
            .rsplit_once(':')
            .ok_or("the test endpoint requires a port")?;
        let port_value: u16 = port
            .parse()
            .map_err(|_| "the test endpoint port is invalid")?;
        let valid_host = if let Some(address) = host
            .strip_prefix('[')
            .and_then(|host| host.strip_suffix(']'))
        {
            address.parse::<std::net::Ipv6Addr>().is_ok()
        } else {
            host.len() <= 253
                && host
                    .bytes()
                    .next()
                    .is_some_and(|byte| byte.is_ascii_alphanumeric())
                && host
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
        };
        if port_value == 0 || port_value.to_string() != port || !valid_host {
            return Err(
                "the test endpoint must be a canonical host:port address without credentials"
                    .into(),
            );
        }
    }
    Ok(())
}

fn consumer(bootstrap: &str) -> Result<BaseConsumer, String> {
    ClientConfig::new()
        .set("bootstrap.servers", bootstrap)
        .set("enable.auto.commit", "false")
        .set("enable.auto.offset.store", "false")
        .set("isolation.level", "read_uncommitted")
        .set("allow.auto.create.topics", "false")
        .set("enable.partition.eof", "true")
        .set("fetch.message.max.bytes", "4194304")
        .set("fetch.max.bytes", "16777216")
        .create()
        .map_err(|e| format!("cannot create the explicit test reader: {e}"))
}

fn inventory(reader: &BaseConsumer, topic: &str, count: i32) -> Result<(), String> {
    let metadata = reader
        .fetch_metadata(Some(topic), TIMEOUT)
        .map_err(|e| format!("cannot inspect the test topic: {e}"))?;
    let topics: Vec<_> = metadata
        .topics()
        .iter()
        .filter(|item| item.name() == topic)
        .collect();
    if topics.len() != 1
        || topics[0].error().is_some()
        || topics[0].partitions().len() != count as usize
        || topics[0]
            .partitions()
            .iter()
            .enumerate()
            .any(|(index, part)| part.id() != index as i32 || part.error().is_some())
    {
        return Err(format!(
            "the acknowledged topic has an incomplete partition inventory: {topic}"
        ));
    }
    Ok(())
}

struct Waiter(std::thread::Thread);
impl Wake for Waiter {
    fn wake(self: Arc<Self>) {
        self.0.unpark();
    }
}

fn create_topics(
    admin: &AdminClient<DefaultClientContext>,
    topics: &[(String, i32)],
) -> Result<(), String> {
    let topics: Vec<_> = topics
        .iter()
        .map(|(name, count)| NewTopic::new(name, *count, TopicReplication::Fixed(1)))
        .collect();
    let options = AdminOptions::new()
        .request_timeout(Some(TIMEOUT))
        .operation_timeout(Some(TIMEOUT));
    let mut future = std::pin::pin!(admin.create_topics(&topics, &options));
    let waker = Waker::from(Arc::new(Waiter(std::thread::current())));
    let mut context = Context::from_waker(&waker);
    let deadline = Instant::now() + TIMEOUT;
    let results: Vec<TopicResult> = loop {
        match future.as_mut().poll(&mut context) {
            Poll::Ready(value) => {
                break value.map_err(|e| format!("topic creation failed: {e}"))?
            }
            Poll::Pending => {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err("topic creation exceeded 30 seconds".into());
                }
                std::thread::park_timeout(remaining);
            }
        }
    };
    let expected: BTreeSet<_> = topics.iter().map(|topic| topic.name).collect();
    let mut acknowledged = BTreeSet::new();
    for result in results {
        let name = result
            .map_err(|(topic, error)| format!("topic creation was refused for {topic}: {error}"))?;
        if !acknowledged.insert(name) {
            return Err("topic creation acknowledged a duplicate topic".into());
        }
    }
    if acknowledged
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>()
        != expected
    {
        return Err("topic creation did not acknowledge the exact batch".into());
    }
    Ok(())
}

type Delivery = (usize, Result<(i32, i64), String>);
struct Deliveries {
    sender: mpsc::Sender<Delivery>,
    failed: Arc<AtomicBool>,
}
impl ClientContext for Deliveries {}
impl ProducerContext for Deliveries {
    type DeliveryOpaque = Box<usize>;
    fn delivery(&self, result: &DeliveryResult<'_>, index: Box<usize>) {
        let result = result
            .as_ref()
            .map(|message| (message.partition(), message.offset()))
            .map_err(|(error, _)| format!("the test record was not acknowledged: {error}"));
        if self.sender.send((*index, result)).is_err() {
            self.failed.store(true, Ordering::SeqCst);
        }
    }
}

fn produce(
    bootstrap: &str,
    topic: &str,
    codec: &str,
    records: &[(String, Vec<u8>)],
) -> Result<(), String> {
    let (sender, receiver) = mpsc::channel();
    let failed = Arc::new(AtomicBool::new(false));
    let producer: BaseProducer<Deliveries> = ClientConfig::new()
        .set("bootstrap.servers", bootstrap)
        .set("enable.idempotence", "false")
        .set("acks", "all")
        .set("max.in.flight.requests.per.connection", "1")
        .set("partitioner", "murmur2_random")
        .set("compression.type", codec)
        .set("linger.ms", "20")
        .set("message.max.bytes", "4194304")
        .set("delivery.timeout.ms", "180000")
        .set("allow.auto.create.topics", "false")
        .create_with_context(Deliveries {
            sender,
            failed: failed.clone(),
        })
        .map_err(|e| format!("cannot create the {codec} test producer: {e}"))?;
    for (index, (key, payload)) in records.iter().enumerate() {
        producer
            .send(
                BaseRecord::with_opaque_to(topic, Box::new(index))
                    .key(key)
                    .payload(payload),
            )
            .map_err(|(error, _)| format!("cannot enqueue a test record: {error}"))?;
    }
    producer
        .flush(Duration::from_secs(180))
        .map_err(|e| format!("the test records were not delivered: {e}"))?;
    if failed.load(Ordering::SeqCst) {
        return Err("the test delivery channel failed".into());
    }
    let mut acknowledged = BTreeMap::new();
    for _ in records {
        let (index, delivery) = receiver
            .try_recv()
            .map_err(|_| "a test delivery acknowledgement is missing")?;
        let (partition, offset) = delivery?;
        if index >= records.len()
            || partition < 0
            || offset < 0
            || acknowledged.insert(index, (partition, offset)).is_some()
        {
            return Err("the test delivery acknowledgements are invalid or duplicated".into());
        }
    }
    if receiver.try_recv().is_ok() {
        return Err("the test received extra delivery acknowledgements".into());
    }
    Ok(())
}

fn payload(length: usize) -> Vec<u8> {
    (0..length)
        .map(|index| ((index.wrapping_mul(31) ^ (index >> 5)) & 255) as u8)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_or_credential_shaped_endpoints_are_rejected() {
        for value in [
            "",
            "identity@localhost:9092",
            "http://localhost:9092",
            "localhost:0",
            "localhost:09092",
            "localhost:65536",
            "localhost:9092\n",
            "[]:9092",
            "[:]:9092",
            "::1:9092",
        ] {
            assert!(validate_bootstrap(value).is_err(), "{value}");
        }
        for value in ["127.0.0.1:39092", "kafscale-proxy:39093", "[::1]:39092"] {
            validate_bootstrap(value).unwrap();
        }
    }

    #[test]
    fn every_attempt_is_recorded_and_failures_are_not_converted_to_success() {
        let mut rows = Vec::new();
        record(&mut rows, "test", "pass", || Ok("verified".into()));
        record(&mut rows, "test", "fail", || Err("actual failure".into()));
        assert_eq!(rows.len(), 2);
        assert_eq!(
            (rows[0].status, rows[0].detail.as_str()),
            ("pass", "verified")
        );
        assert_eq!(
            (rows[1].status, rows[1].detail.as_str()),
            ("fail", "actual failure")
        );
    }
}
