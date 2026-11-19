use crate::admission::{Admitted, Cut};
use crate::run_loop::{RunBroker, Watermark};
use crate::wire;
use rdkafka::bindings as native;
use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::error::KafkaError;
use rdkafka::message::{Header, OwnedHeaders};
use rdkafka::producer::{BaseProducer, BaseRecord, DeliveryResult, Producer, ProducerContext};
use rdkafka::ClientContext;
use std::cell::Cell;
use std::ffi::{CStr, CString};
use std::marker::PhantomData;
use std::ptr::NonNull;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    mpsc, Arc,
};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct InputRecord {
    pub partition: i32,
    pub offset: i64,
    pub key: Vec<u8>,
    pub payload: Vec<u8>,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OutputRecord {
    pub key: String,
    pub timestamp: i64,
    pub payload: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FederationRecord {
    pub key: String,
    pub timestamp: i64,
    pub payload: Vec<u8>,
    pub headers: Vec<(String, Vec<u8>)>,
}

#[derive(Clone, Debug)]
pub struct FederationInputRecord {
    pub partition: i32,
    pub offset: i64,
    pub record: FederationRecord,
}

pub trait Broker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String>;
    fn publish(&mut self, run: &Admitted, records: &[OutputRecord]) -> Result<(), String>;

    fn fenced(&self) -> bool {
        false
    }

    fn capture_grant(
        &mut self,
        _: &Admitted,
        grant: Option<&crate::publication::PublicationGrant>,
    ) -> Result<Option<crate::publication::PublicationGrant>, String> {
        if grant.is_some() {
            return Err("this broker does not support captured publication grants".into());
        }
        Ok(None)
    }

    fn publish_captured(
        &mut self,
        run: &Admitted,
        records: &[OutputRecord],
        grant: Option<&crate::publication::PublicationGrant>,
    ) -> Result<(), String> {
        if grant.is_some() {
            return Err("this broker does not support captured publication grants".into());
        }
        self.publish(run, records)
    }
}

#[derive(Clone)]
pub struct KafkaBroker;

fn consumer(bootstrap: &str) -> Result<BaseConsumer, String> {
    consumer_config(bootstrap)
        .create()
        .map_err(|e| format!("cannot create Kafka reader: {e}"))
}

fn consumer_config(bootstrap: &str) -> ClientConfig {
    let mut config = ClientConfig::new();
    config
        .set("bootstrap.servers", bootstrap)
        .set("isolation.level", "read_uncommitted")
        .set("enable.auto.commit", "false")
        .set("enable.auto.offset.store", "false")
        .set("auto.offset.reset", "error")
        .set("enable.partition.eof", "true")
        .set("allow.auto.create.topics", "false")
        .set("fetch.message.max.bytes", "1048576")
        .set("fetch.max.bytes", "16777216")
        .set("receive.message.max.bytes", "33554432")
        .set("queued.max.messages.kbytes", "16384")
        .set("queued.min.messages", "1");
    config
}

fn producer_config(bootstrap: &str) -> ClientConfig {
    let mut config = ClientConfig::new();
    config
        .set("bootstrap.servers", bootstrap)
        .set("enable.idempotence", "false")
        .set("acks", "all")
        .set("compression.type", "zstd")
        .set("linger.ms", "50")
        .set("batch.size", "524288")
        .set("message.max.bytes", "4194304")
        .set("delivery.timeout.ms", "180000")
        .set("retry.backoff.ms", "1000")
        .set("retry.backoff.max.ms", "10000")
        .set("max.in.flight.requests.per.connection", "1")
        .set("partitioner", "murmur2_random")
        .set("allow.auto.create.topics", "false")
        .set("queue.buffering.max.kbytes", "32768");
    config
}

pub(crate) fn read_federation_prefix(
    bootstrap: &str,
    cut: &Cut,
    limits: &crate::admission::Limits,
) -> Result<Vec<FederationInputRecord>, String> {
    let reader = consumer(bootstrap)?;
    verify_cut(&reader, cut)?;
    let topic = LegacyTopic::new(&reader, &cut.topic)?;
    let deadline = Instant::now() + Duration::from_secs(600);
    let mut records = Vec::new();
    let mut bytes = 0usize;
    for position in &cut.positions {
        if position.from == position.to {
            continue;
        }
        topic.start(position.partition, position.from)?;
        let result = (|| {
            let mut next = position.from;
            while next < position.to {
                if records.len() >= limits.records {
                    return Ok(true);
                }
                if Instant::now() >= deadline {
                    return Err("federation cut read timed out".into());
                }
                if let Some(event) = reader.poll(Duration::ZERO) {
                    let code = match &event {
                        Err(error) => error.rdkafka_error_code().map(|code| code as i32),
                        Ok(_) => None,
                    };
                    if code.is_some_and(retryable_fetch) {
                        reset_refused(
                            next,
                            || topic.stop(position.partition),
                            |n| topic.start(position.partition, n),
                        )?;
                        continue;
                    }
                    return Err(match event {
                        Err(error) => format!("federation client failed: {error}"),
                        Ok(_) => {
                            "federation reader received an unexpected consumer-queue message".into()
                        }
                    });
                }
                let Some(message) = topic.poll(position.partition)? else {
                    continue;
                };
                let data = message.data();
                if data.err != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR {
                    if retryable_fetch(data.err as i32) {
                        drop(message);
                        reset_refused(
                            next,
                            || topic.stop(position.partition),
                            |n| topic.start(position.partition, n),
                        )?;
                        continue;
                    }
                    return Err(format!(
                        "federation fetch failed: {}",
                        KafkaError::MessageConsumption(data.err.into())
                    ));
                }
                if data.partition != position.partition || data.offset != next {
                    return Err("federation cut contains missing or reordered offsets".into());
                }
                let record = message.federation_record(limits.payload_bytes)?;
                let next_bytes = bytes
                    .checked_add(record.payload.len())
                    .ok_or("federation input byte overflow")?;
                if next_bytes > limits.input_bytes {
                    if records.is_empty() {
                        return Err(
                            "federation record exceeds the admitted input memory limit".into()
                        );
                    }
                    return Ok(true);
                }
                bytes = next_bytes;
                records.push(FederationInputRecord {
                    partition: data.partition,
                    offset: data.offset,
                    record,
                });
                next = next.checked_add(1).ok_or("federation offset overflow")?;
            }
            Ok(false)
        })();
        let stopped = topic.stop(position.partition);
        match (result, stopped) {
            (Err(error), Err(stop)) => return Err(format!("{error}; {stop}")),
            (Err(error), _) | (_, Err(error)) => return Err(error),
            (Ok(true), Ok(())) => break,
            (Ok(false), Ok(())) => {}
        }
    }
    verify_cut(&reader, cut)?;
    Ok(records)
}

pub(crate) fn publish_federation_records(
    bootstrap: &str,
    topic: &str,
    partitions: usize,
    records: &[FederationRecord],
) -> Result<(), String> {
    let reader = consumer(bootstrap)?;
    inventory(&reader, topic, partitions)?;
    let (sender, receiver) = mpsc::channel();
    let failed = Arc::new(AtomicBool::new(false));
    let producer: BaseProducer<DeliveryContext> = producer_config(bootstrap)
        .create_with_context(DeliveryContext {
            sender,
            callback_failed: failed.clone(),
        })
        .map_err(|e| format!("cannot create federation writer: {e}"))?;
    for record in records {
        let mut headers = OwnedHeaders::new_with_capacity(record.headers.len());
        for (name, value) in &record.headers {
            headers = headers.insert(Header {
                key: name,
                value: Some(value.as_slice()),
            });
        }
        producer
            .send(
                BaseRecord::to(topic)
                    .key(record.key.as_str())
                    .payload(record.payload.as_slice())
                    .timestamp(record.timestamp)
                    .headers(headers),
            )
            .map_err(|(e, _)| format!("federation enqueue failed before checkpoint: {e}"))?;
    }
    producer
        .flush(Duration::from_secs(180))
        .map_err(|e| format!("federation delivery failed: {e}"))?;
    if failed.load(Ordering::SeqCst) {
        return Err("federation delivery callback channel failed".into());
    }
    for _ in records {
        receiver
            .try_recv()
            .map_err(|_| "a federation acknowledgement is missing")??;
    }
    inventory(&reader, topic, partitions)?;
    Ok(())
}

fn inventory(consumer: &BaseConsumer, topic: &str, count: usize) -> Result<(), String> {
    let metadata = consumer
        .fetch_metadata(Some(topic), Duration::from_secs(30))
        .map_err(|e| format!("cannot read topic inventory: {e}"))?;
    let topics: Vec<_> = metadata
        .topics()
        .iter()
        .filter(|t| t.name() == topic)
        .collect();
    if topics.len() != 1 || topics[0].error().is_some() || topics[0].partitions().len() != count {
        return Err(format!(
            "topic inventory does not match the complete declared topic: {topic}"
        ));
    }
    let mut partitions: Vec<_> = topics[0]
        .partitions()
        .iter()
        .map(|p| (p.id(), p.error()))
        .collect();
    partitions.sort_by_key(|p| p.0);
    if partitions
        .iter()
        .enumerate()
        .any(|(n, p)| p.0 != n as i32 || p.1.is_some())
    {
        return Err(format!("topic partition inventory is invalid: {topic}"));
    }
    Ok(())
}

fn verify_cut(consumer: &BaseConsumer, cut: &Cut) -> Result<(), String> {
    inventory(consumer, &cut.topic, cut.positions.len())?;
    for position in &cut.positions {
        let (low, high) = consumer
            .fetch_watermarks(&cut.topic, position.partition, Duration::from_secs(30))
            .map_err(|e| format!("cannot read input watermarks: {e}"))?;
        if position.from < low || position.to > high {
            return Err(
                "the bounded input cut is unavailable; offsets are never reset or clipped".into(),
            );
        }
    }
    Ok(())
}

impl Broker for KafkaBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
        let consumer = consumer(&run.bootstrap)?;
        verify_cut(&consumer, &run.cut)?;
        inventory(
            &consumer,
            &run.request.sink.topic,
            run.output_partitions as usize,
        )?;
        let topic = LegacyTopic::new(&consumer, &run.cut.topic)?;
        let deadline = Instant::now() + Duration::from_secs(600);
        let mut records = Vec::new();
        let mut bytes = 0usize;
        for position in &run.cut.positions {
            if position.from == position.to {
                continue;
            }
            topic.start(position.partition, position.from)?;
            let result = (|| {
                let mut next = position.from;
                while next < position.to {
                    if Instant::now() >= deadline {
                        return Err("input cut read timed out".into());
                    }
                    if let Some(event) = consumer.poll(Duration::ZERO) {
                        let code = match &event {
                            Err(error) => error.rdkafka_error_code().map(|code| code as i32),
                            Ok(_) => None,
                        };
                        if code.is_some_and(retryable_fetch) {
                            reset_refused(
                                next,
                                || topic.stop(position.partition),
                                |offset| topic.start(position.partition, offset),
                            )?;
                            continue;
                        }
                        return Err(match event {
                            Err(error) => format!("input client failed: {error}"),
                            Ok(_) => {
                                "legacy reader received an unexpected consumer-queue message".into()
                            }
                        });
                    }
                    let Some(message) = topic.poll(position.partition)? else {
                        continue;
                    };
                    let data = message.data();
                    if data.err == native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR__PARTITION_EOF {
                        return Err("input ended before every admitted offset was read".into());
                    }
                    if data.err != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR {
                        if retryable_fetch(data.err as i32) {
                            drop(message);
                            reset_refused(
                                next,
                                || topic.stop(position.partition),
                                |offset| topic.start(position.partition, offset),
                            )?;
                            continue;
                        }
                        return Err(format!(
                            "input fetch failed: {}",
                            KafkaError::MessageConsumption(data.err.into())
                        ));
                    }
                    if data.partition != position.partition || data.offset != next {
                        return Err("input cut contains missing or reordered offsets".into());
                    }
                    bytes = bytes
                        .checked_add(data.len)
                        .ok_or("input byte count overflow")?;
                    if bytes > run.request.limits.input_bytes
                        || records.len() >= run.request.limits.records
                    {
                        return Err("whole topic cut exceeds the input memory limits".into());
                    }
                    records.push(message.record(run)?);
                    next = next.checked_add(1).ok_or("input offset overflow")?;
                }
                Ok(())
            })();
            let stopped = topic.stop(position.partition);
            match (result, stopped) {
                (Err(error), Err(stop)) => return Err(format!("{error}; {stop}")),
                (Err(error), _) | (_, Err(error)) => return Err(error),
                _ => {}
            }
        }
        verify_cut(&consumer, &run.cut)?;
        records.sort_by_key(|r| (r.partition, r.offset));
        Ok(records)
    }

    fn publish(&mut self, run: &Admitted, records: &[OutputRecord]) -> Result<(), String> {
        let reader = consumer(&run.bootstrap)?;
        inventory(
            &reader,
            &run.request.sink.topic,
            run.output_partitions as usize,
        )?;
        let (sender, receiver) = mpsc::channel();
        let callback_failed = Arc::new(AtomicBool::new(false));
        let producer: BaseProducer<DeliveryContext> = producer_config(&run.bootstrap)
            .create_with_context(DeliveryContext {
                sender,
                callback_failed: callback_failed.clone(),
            })
            .map_err(|e| format!("cannot create Kafka writer: {e}"))?;
        for record in records {
            let headers = OwnedHeaders::new_with_capacity(1).insert(Header {
                key: "tsx-schema",
                value: Some(run.pipeline.schema_name()),
            });
            producer
                .send(
                    BaseRecord::to(&run.request.sink.topic)
                        .key(record.key.as_str())
                        .payload(record.payload.as_slice())
                        .timestamp(record.timestamp)
                        .headers(headers),
                )
                .map_err(|(e, _)| format!("output enqueue failed before checkpoint: {e}"))?;
        }
        producer
            .flush(Duration::from_secs(180))
            .map_err(|e| format!("output delivery failed: {e}"))?;
        if callback_failed.load(Ordering::SeqCst) {
            return Err("delivery callback channel failed".into());
        }
        for _ in records {
            receiver
                .try_recv()
                .map_err(|_| "an output delivery acknowledgement is missing")??;
        }
        inventory(
            &reader,
            &run.request.sink.topic,
            run.output_partitions as usize,
        )?;
        Ok(())
    }
}

impl RunBroker for KafkaBroker {
    fn watermarks(
        &mut self,
        bootstrap: &str,
        topic: &str,
        partitions: usize,
    ) -> Result<Vec<Watermark>, String> {
        let reader = consumer(bootstrap)?;
        inventory(&reader, topic, partitions)?;
        (0..partitions)
            .map(|partition| {
                let (low, high) = reader
                    .fetch_watermarks(topic, partition as i32, Duration::from_secs(30))
                    .map_err(|error| format!("cannot inspect signed-run offsets: {error}"))?;
                Ok(Watermark {
                    partition: partition as i32,
                    low,
                    high,
                })
            })
            .collect()
    }

    fn publish_status(
        &mut self,
        bootstrap: &str,
        topic: &str,
        key: &str,
        payload: &[u8],
    ) -> Result<(), String> {
        let (sender, receiver) = mpsc::channel();
        let failed = Arc::new(AtomicBool::new(false));
        let producer: BaseProducer<DeliveryContext> = producer_config(bootstrap)
            .create_with_context(DeliveryContext {
                sender,
                callback_failed: failed.clone(),
            })
            .map_err(|error| format!("cannot create the status writer: {error}"))?;
        producer
            .send(BaseRecord::to(topic).key(key).payload(payload))
            .map_err(|(error, _)| format!("cannot enqueue run status: {error}"))?;
        producer
            .flush(Duration::from_secs(180))
            .map_err(|error| format!("run status delivery failed: {error}"))?;
        if failed.load(Ordering::SeqCst) {
            return Err("the status delivery callback failed".into());
        }
        receiver
            .try_recv()
            .map_err(|_| "the status acknowledgement is missing")??;
        if receiver.try_recv().is_ok() {
            return Err("unexpected extra status acknowledgement".into());
        }
        Ok(())
    }
}

fn retryable_fetch(error: i32) -> bool {
    use native::rd_kafka_resp_err_t::*;
    [
        RD_KAFKA_RESP_ERR_LEADER_NOT_AVAILABLE,
        RD_KAFKA_RESP_ERR_NOT_LEADER_FOR_PARTITION,
        RD_KAFKA_RESP_ERR_REQUEST_TIMED_OUT,
        RD_KAFKA_RESP_ERR_BROKER_NOT_AVAILABLE,
        RD_KAFKA_RESP_ERR_REPLICA_NOT_AVAILABLE,
        RD_KAFKA_RESP_ERR_NETWORK_EXCEPTION,
        RD_KAFKA_RESP_ERR_KAFKA_STORAGE_ERROR,
    ]
    .iter()
    .any(|code| *code as i32 == error)
}

fn reset_refused(
    offset: i64,
    stop: impl FnOnce() -> Result<(), String>,
    start: impl FnOnce(i64) -> Result<(), String>,
) -> Result<(), String> {
    stop()?;
    start(offset)
}

// FindCoordinator supports v0..2 here; KafScale 1.6.0 advertises v3 only.
// https://github.com/confluentinc/librdkafka/blob/v2.12.1/src/rdkafka_request.c#L603
struct LegacyTopic<'a> {
    pointer: NonNull<native::rd_kafka_topic_t>,
    active_partition: Cell<Option<i32>>,
    _consumer: &'a BaseConsumer,
}

impl<'a> LegacyTopic<'a> {
    fn new(consumer: &'a BaseConsumer, topic: &str) -> Result<Self, String> {
        let topic = CString::new(topic).map_err(|_| "input topic contains a null byte")?;
        let pointer = unsafe {
            native::rd_kafka_topic_new(
                consumer.client().native_ptr(),
                topic.as_ptr(),
                std::ptr::null_mut(),
            )
        };
        Ok(Self {
            pointer: NonNull::new(pointer)
                .ok_or_else(|| native_error("cannot create input topic reader"))?,
            active_partition: Cell::new(None),
            _consumer: consumer,
        })
    }

    fn start(&self, partition: i32, offset: i64) -> Result<(), String> {
        if self.active_partition.get().is_some() {
            return Err("input topic reader already has an active partition".into());
        }
        if unsafe { native::rd_kafka_consume_start(self.pointer.as_ptr(), partition, offset) } != 0
        {
            return Err(native_error("cannot start explicit input partition"));
        }
        self.active_partition.set(Some(partition));
        Ok(())
    }

    fn stop(&self, partition: i32) -> Result<(), String> {
        if unsafe { native::rd_kafka_consume_stop(self.pointer.as_ptr(), partition) } != 0 {
            return Err(native_error("cannot stop explicit input partition"));
        }
        self.active_partition.set(None);
        Ok(())
    }

    fn poll(&self, partition: i32) -> Result<Option<LegacyMessage<'_>>, String> {
        let pointer = unsafe { native::rd_kafka_consume(self.pointer.as_ptr(), partition, 100) };
        match NonNull::new(pointer) {
            Some(pointer) => Ok(Some(LegacyMessage {
                pointer,
                _topic: PhantomData,
            })),
            None if unsafe { native::rd_kafka_last_error() }
                == native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR__TIMED_OUT =>
            {
                Ok(None)
            }
            None => Err(native_error("cannot poll explicit input partition")),
        }
    }
}

impl Drop for LegacyTopic<'_> {
    fn drop(&mut self) {
        if let Some(partition) = self.active_partition.take() {
            if unsafe { native::rd_kafka_consume_stop(self.pointer.as_ptr(), partition) } != 0 {
                eprintln!("{}", native_error("input topic reader cleanup failed"));
            }
        }
        unsafe { native::rd_kafka_topic_destroy(self.pointer.as_ptr()) };
    }
}

struct LegacyMessage<'a> {
    pointer: NonNull<native::rd_kafka_message_t>,
    _topic: PhantomData<&'a ()>,
}

impl LegacyMessage<'_> {
    fn data(&self) -> &native::rd_kafka_message_t {
        unsafe { self.pointer.as_ref() }
    }

    fn federation_record(&self, limit: usize) -> Result<FederationRecord, String> {
        let data = self.data();
        if data.payload.is_null()
            || data.key.is_null()
            || data.len > limit
            || data.len > isize::MAX as usize
            || data.key_len == 0
            || data.key_len > 4096
        {
            return Err("federation record key or payload is missing or exceeds its bound".into());
        }
        let key = std::str::from_utf8(unsafe {
            std::slice::from_raw_parts(data.key.cast::<u8>(), data.key_len)
        })
        .map_err(|_| "federation record key is not UTF-8")?
        .to_owned();
        let timestamp = unsafe {
            native::rd_kafka_message_timestamp(self.pointer.as_ptr(), std::ptr::null_mut())
        };
        if timestamp < 0 {
            return Err("federation record has no timestamp".into());
        }
        let mut headers = std::ptr::null_mut();
        if unsafe { native::rd_kafka_message_headers(self.pointer.as_ptr(), &mut headers) }
            != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR
        {
            return Err("federation record has no headers".into());
        }
        Ok(FederationRecord {
            key,
            timestamp,
            payload: unsafe { std::slice::from_raw_parts(data.payload.cast::<u8>(), data.len) }
                .to_vec(),
            headers: copy_headers(headers, limit)?,
        })
    }

    fn record(&self, run: &Admitted) -> Result<InputRecord, String> {
        let data = self.data();
        if data.payload.is_null() {
            return Err(format!(
                "record {}/{}@{} is not Avro single-object encoded",
                run.cut.topic, data.partition, data.offset
            ));
        }
        if data.key.is_null() {
            return Err("episode record has no series key".into());
        }
        if data.len > run.request.limits.payload_bytes
            || data.len > isize::MAX as usize
            || data.key_len != 16
        {
            return Err("input payload or key exceeds the local record contract".into());
        }
        let record = InputRecord {
            partition: data.partition,
            offset: data.offset,
            key: unsafe { std::slice::from_raw_parts(data.key.cast::<u8>(), data.key_len) }
                .to_vec(),
            payload: unsafe { std::slice::from_raw_parts(data.payload.cast::<u8>(), data.len) }
                .to_vec(),
        };
        crate::runner::validate_record_schema(run, &record)?;
        let mut headers = std::ptr::null_mut();
        if unsafe { native::rd_kafka_message_headers(self.pointer.as_ptr(), &mut headers) }
            != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR
        {
            return Err("episode record has no schema header".into());
        }
        validate_schema_headers(headers)?;
        Ok(record)
    }
}

fn copy_headers(
    headers: *mut native::rd_kafka_headers_t,
    limit: usize,
) -> Result<Vec<(String, Vec<u8>)>, String> {
    let headers = NonNull::new(headers).ok_or("federation record has no headers")?;
    let count = unsafe { native::rd_kafka_header_cnt(headers.as_ptr()) };
    if count == 0 || count > 64 {
        return Err("federation header count exceeds its bound".into());
    }
    let mut output = Vec::with_capacity(count);
    let mut bytes = 0usize;
    for index in 0..count {
        let mut name = std::ptr::null();
        let mut value = std::ptr::null();
        let mut length = 0;
        if unsafe {
            native::rd_kafka_header_get_all(
                headers.as_ptr(),
                index,
                &mut name,
                &mut value,
                &mut length,
            )
        } != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR
            || name.is_null()
            || value.is_null()
        {
            return Err("federation headers must have nonnull names and values".into());
        }
        let name = unsafe { CStr::from_ptr(name) }
            .to_str()
            .map_err(|_| "federation header name is not UTF-8")?;
        bytes = bytes
            .checked_add(name.len())
            .and_then(|n| n.checked_add(length))
            .ok_or("federation header byte overflow")?;
        if bytes > limit || length > isize::MAX as usize {
            return Err("federation headers exceed their byte limit".into());
        }
        output.push((
            name.to_owned(),
            unsafe { std::slice::from_raw_parts(value.cast::<u8>(), length) }.to_vec(),
        ));
    }
    Ok(output)
}

fn validate_schema_headers(headers: *mut native::rd_kafka_headers_t) -> Result<(), String> {
    let headers = NonNull::new(headers).ok_or("episode record has no schema header")?;
    let mut matched = 0;
    for index in 0..unsafe { native::rd_kafka_header_cnt(headers.as_ptr()) } {
        let mut name = std::ptr::null();
        let mut value = std::ptr::null();
        let mut length = 0;
        if unsafe {
            native::rd_kafka_header_get_all(
                headers.as_ptr(),
                index,
                &mut name,
                &mut value,
                &mut length,
            )
        } != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR
            || name.is_null()
        {
            return Err("cannot read episode headers".into());
        }
        let name = unsafe { CStr::from_ptr(name) }
            .to_str()
            .map_err(|_| "episode header name is not UTF-8")?;
        if name == "tsx-schema" {
            matched += 1;
            if value.is_null()
                || length != wire::SCHEMA_NAME.len()
                || unsafe { std::slice::from_raw_parts(value.cast::<u8>(), length) }
                    != wire::SCHEMA_NAME.as_bytes()
            {
                return Err("episode record has an unsupported schema header".into());
            }
        }
    }
    if matched != 1 {
        return Err("episode record has a missing or duplicate schema header".into());
    }
    Ok(())
}

impl Drop for LegacyMessage<'_> {
    fn drop(&mut self) {
        unsafe { native::rd_kafka_message_destroy(self.pointer.as_ptr()) };
    }
}

fn native_error(context: &str) -> String {
    format!(
        "{context}: {}",
        KafkaError::MessageConsumption(unsafe { native::rd_kafka_last_error() }.into())
    )
}

struct DeliveryContext {
    sender: mpsc::Sender<Result<(), String>>,
    callback_failed: Arc<AtomicBool>,
}

impl ClientContext for DeliveryContext {}

impl ProducerContext for DeliveryContext {
    type DeliveryOpaque = ();

    fn delivery(&self, delivery: &DeliveryResult<'_>, _: ()) {
        let result = delivery
            .as_ref()
            .map(|_| ())
            .map_err(|(error, _)| format!("output was not acknowledged: {error}"));
        if self.sender.send(result).is_err() {
            self.callback_failed.store(true, Ordering::SeqCst);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_tombstones_use_the_contextual_fatal_single_object_error() {
        use crate::run_loop::run_profile::{Fixture, ARTIFACT_DIGEST};
        use crate::run_plan::{RunConfig, SignedPlan};
        let fixture = Fixture::new();
        let config = RunConfig::load(&fixture.config_path).unwrap();
        let plan = SignedPlan::verify(
            &fixture.sign(&fixture.plan_document()),
            &config,
            ARTIFACT_DIGEST,
        )
        .unwrap();
        let admitted = plan.admit_cut(&config, &plan.positions).unwrap();
        let mut data: native::rd_kafka_message_t = unsafe { std::mem::zeroed() };
        data.partition = 1;
        data.offset = 7;
        let message = std::mem::ManuallyDrop::new(LegacyMessage {
            pointer: NonNull::from(&mut data),
            _topic: PhantomData,
        });
        assert_eq!(
            message.record(&admitted).unwrap_err(),
            "record tsx.b1.episodes.v2/1@7 is not Avro single-object encoded"
        );
    }

    #[test]
    fn local_reader_has_no_group_and_matches_java_isolation() {
        let config = consumer_config("127.0.0.1:39093");
        assert_eq!(config.get("group.id"), None);
        assert_eq!(config.get("isolation.level"), Some("read_uncommitted"));
        assert_eq!(config.get("enable.auto.commit"), Some("false"));
        assert_eq!(config.get("enable.auto.offset.store"), Some("false"));
        assert_eq!(config.get("auto.offset.reset"), Some("error"));
    }

    #[test]
    fn refused_fetches_restart_only_at_the_current_unfinished_cut_offset() {
        let calls = std::cell::RefCell::new(Vec::new());
        reset_refused(
            13,
            || {
                calls.borrow_mut().push(0);
                Ok(())
            },
            |offset| {
                calls.borrow_mut().push(offset);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(*calls.borrow(), [0, 13]);
        assert_eq!(
            reset_refused(
                13,
                || Err("stop refused".into()),
                |_| panic!("must not restart")
            ),
            Err("stop refused".into())
        );
        assert_eq!(
            reset_refused(13, || Ok(()), |_| Err("restart refused".into())),
            Err("restart refused".into())
        );
    }

    #[test]
    fn only_transient_fetch_refusals_are_retryable() {
        for code in -200..200 {
            assert_eq!(
                retryable_fetch(code),
                [5, 6, 7, 8, 9, 13, 56].contains(&code),
                "{code}"
            );
        }
    }

    #[test]
    fn status_and_episode_publishers_use_the_existing_acknowledged_configuration() {
        let config = producer_config("127.0.0.1:39093");
        for (key, value) in [
            ("acks", "all"),
            ("compression.type", "zstd"),
            ("enable.idempotence", "false"),
            ("partitioner", "murmur2_random"),
            ("allow.auto.create.topics", "false"),
            ("max.in.flight.requests.per.connection", "1"),
        ] {
            assert_eq!(config.get(key), Some(value));
        }
    }

    struct TestHeaders(NonNull<native::rd_kafka_headers_t>);

    impl TestHeaders {
        fn new() -> Self {
            Self(NonNull::new(unsafe { native::rd_kafka_headers_new(2) }).unwrap())
        }

        fn add(&self, name: &[u8], value: Option<&[u8]>) {
            let name = CString::new(name).unwrap();
            let error = unsafe {
                native::rd_kafka_header_add(
                    self.0.as_ptr(),
                    name.as_ptr(),
                    -1,
                    value.map_or(std::ptr::null(), |bytes| bytes.as_ptr().cast()),
                    value.map_or(0, |bytes| bytes.len() as isize),
                )
            };
            assert_eq!(
                error,
                native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR
            );
        }
    }

    impl Drop for TestHeaders {
        fn drop(&mut self) {
            unsafe { native::rd_kafka_headers_destroy(self.0.as_ptr()) };
        }
    }

    #[test]
    fn native_schema_headers_require_exactly_one_supported_nonnull_value() {
        assert!(validate_schema_headers(std::ptr::null_mut()).is_err());
        let headers = TestHeaders::new();
        assert!(validate_schema_headers(headers.0.as_ptr()).is_err());
        headers.add(b"tsx-schema", Some(wire::SCHEMA_NAME.as_bytes()));
        assert!(validate_schema_headers(headers.0.as_ptr()).is_ok());
        headers.add(b"tsx-schema", Some(wire::SCHEMA_NAME.as_bytes()));
        assert!(validate_schema_headers(headers.0.as_ptr()).is_err());
        for value in [None, Some(&b"wrong"[..]), Some(&b""[..])] {
            let headers = TestHeaders::new();
            headers.add(b"tsx-schema", value);
            assert!(validate_schema_headers(headers.0.as_ptr()).is_err());
        }
    }

    #[test]
    fn native_schema_headers_reject_invalid_utf8_names_and_allow_other_headers() {
        let headers = TestHeaders::new();
        headers.add(b"tsx-schema", Some(wire::SCHEMA_NAME.as_bytes()));
        headers.add(b"context", Some(b"synthetic"));
        assert!(validate_schema_headers(headers.0.as_ptr()).is_ok());
        headers.add(&[0xff], Some(b"invalid"));
        assert!(validate_schema_headers(headers.0.as_ptr()).is_err());
    }

    #[test]
    fn native_forward_headers_preserve_exact_values_and_order() {
        let headers = TestHeaders::new();
        headers.add(b"tsx-schema", Some(b"org.opentsx.model.v2.SeriesStats"));
        headers.add(b"tsx-evidence", Some(b"{\"kind\":\"bounded-source-cut\"}"));
        headers.add(b"trace", Some(&[0, 255]));
        assert_eq!(
            copy_headers(headers.0.as_ptr(), 1024).unwrap(),
            vec![
                (
                    "tsx-schema".into(),
                    b"org.opentsx.model.v2.SeriesStats".to_vec()
                ),
                (
                    "tsx-evidence".into(),
                    b"{\"kind\":\"bounded-source-cut\"}".to_vec()
                ),
                ("trace".into(), vec![0, 255])
            ]
        );
    }

    #[test]
    fn native_forward_headers_reject_null_invalid_names_and_excess_bytes() {
        assert!(copy_headers(std::ptr::null_mut(), 1024).is_err());
        let null = TestHeaders::new();
        null.add(b"tsx-schema", None);
        assert!(copy_headers(null.0.as_ptr(), 1024).is_err());
        let names = TestHeaders::new();
        names.add(&[255], Some(b"value"));
        assert!(copy_headers(names.0.as_ptr(), 1024).is_err());
        let large = TestHeaders::new();
        large.add(b"name", Some(b"value"));
        assert!(copy_headers(large.0.as_ptr(), 8).is_err());
    }
}
