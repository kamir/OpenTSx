use rdkafka::bindings as native;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::error::KafkaError;
use std::ffi::CString;
use std::ptr::NonNull;
use std::time::{Duration, Instant};

#[derive(Debug)]
pub struct Record {
    pub partition: i32,
    pub offset: i64,
    pub key: Vec<u8>,
    pub payload: Vec<u8>,
}

struct Topic(NonNull<native::rd_kafka_topic_t>);

impl Drop for Topic {
    fn drop(&mut self) {
        unsafe { native::rd_kafka_topic_destroy(self.0.as_ptr()) };
    }
}

struct Message(NonNull<native::rd_kafka_message_t>);

impl Drop for Message {
    fn drop(&mut self) {
        unsafe { native::rd_kafka_message_destroy(self.0.as_ptr()) };
    }
}

pub fn read(
    consumer: &BaseConsumer,
    topic: &str,
    partition: i32,
    offset: i64,
    count: usize,
) -> Result<Vec<Record>, String> {
    if partition < 0 || offset < 0 || count > 10_000 {
        return Err("invalid conformance read bounds".into());
    }
    let name = CString::new(topic).map_err(|_| "the test topic contains a null byte")?;
    let pointer = unsafe {
        native::rd_kafka_topic_new(
            consumer.client().native_ptr(),
            name.as_ptr(),
            std::ptr::null_mut(),
        )
    };
    let topic =
        Topic(NonNull::new(pointer).ok_or_else(|| native_error("cannot create the test reader"))?);
    if unsafe { native::rd_kafka_consume_start(topic.0.as_ptr(), partition, offset) } != 0 {
        return Err(native_error("cannot start the explicit test read"));
    }
    let result = (|| {
        let mut records = Vec::with_capacity(count);
        let deadline = Instant::now() + Duration::from_secs(30);
        while records.len() < count {
            if Instant::now() >= deadline {
                return Err("the explicit conformance read exceeded 30 seconds".into());
            }
            if let Some(event) = consumer.poll(Duration::ZERO) {
                return Err(match event {
                    Err(error) => format!("the test consumer failed: {error}"),
                    Ok(_) => {
                        "the explicit reader received an unexpected consumer-queue record".into()
                    }
                });
            }
            let message = unsafe { native::rd_kafka_consume(topic.0.as_ptr(), partition, 100) };
            let Some(message) = NonNull::new(message) else {
                if unsafe { native::rd_kafka_last_error() }
                    != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR__TIMED_OUT
                {
                    return Err(native_error("the test read failed"));
                }
                continue;
            };
            let message = Message(message);
            let data = unsafe { message.0.as_ref() };
            if data.err != native::rd_kafka_resp_err_t::RD_KAFKA_RESP_ERR_NO_ERROR {
                return Err(format!(
                    "the test fetch failed: {}",
                    KafkaError::MessageConsumption(data.err.into())
                ));
            }
            if data.partition != partition
                || data.offset != offset + records.len() as i64
                || data.key.is_null()
                || data.payload.is_null()
                || data.key_len > 4096
                || data.len > 2_097_152
            {
                return Err(
                    "the fetched test record has invalid positions, sizes or null fields".into(),
                );
            }
            records.push(Record {
                partition: data.partition,
                offset: data.offset,
                key: unsafe { std::slice::from_raw_parts(data.key.cast::<u8>(), data.key_len) }
                    .to_vec(),
                payload: unsafe { std::slice::from_raw_parts(data.payload.cast::<u8>(), data.len) }
                    .to_vec(),
            });
        }
        Ok(records)
    })();
    let stopped = unsafe { native::rd_kafka_consume_stop(topic.0.as_ptr(), partition) };
    match (result, stopped) {
        (Ok(records), 0) => Ok(records),
        (Err(error), 0) => Err(error),
        (Ok(_), _) => Err(native_error("cannot stop the explicit test read")),
        (Err(error), _) => Err(format!(
            "{error}; {}",
            native_error("cannot stop the explicit test read")
        )),
    }
}

fn native_error(context: &str) -> String {
    format!(
        "{context}: {}",
        KafkaError::MessageConsumption(unsafe { native::rd_kafka_last_error() }.into())
    )
}
