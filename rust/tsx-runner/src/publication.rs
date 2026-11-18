use crate::admission::{canonical_i64, Admitted};
use crate::broker::{Broker, InputRecord, KafkaBroker, OutputRecord};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::io::Read;
use std::time::Duration;

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PublicationGrant {
    pub schema: String,
    pub operation: String,
    pub deployment: String,
    pub epoch: String,
    pub node: String,
    pub input_topic: String,
    pub output_topic: String,
    token: String,
}

impl PublicationGrant {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema != "opentsx.publication-grant.v1"
            || !identifier(&self.operation)
            || !identifier(&self.deployment)
            || !identifier(&self.node)
            || !tsx_contracts::valid_topic(&self.input_topic)
            || !tsx_contracts::valid_topic(&self.output_topic)
            || self.input_topic == self.output_topic
            || self.token.len() != 64
            || !self
                .token
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || canonical_i64(&self.epoch, "publication epoch")? <= 0
        {
            return Err("invalid publication grant".into());
        }
        Ok(())
    }

    pub fn validate_run(&self, run: &Admitted) -> Result<(), String> {
        self.validate()?;
        if run.assignment["deployment"].as_str() != Some(&self.deployment)
            || run.assignment["node"].as_str() != Some(&self.node)
            || run.cut.topic != self.input_topic
            || run.request.sink.topic != self.output_topic
        {
            return Err("publication grant does not match the whole-topic assignment".into());
        }
        Ok(())
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PublicationReceipt {
    schema: String,
    publication_epoch: String,
    request_hash: String,
    output_digest: String,
    output_records: String,
}

pub struct HttpFencedBroker {
    origin: String,
    client: ureq::Agent,
    reader: KafkaBroker,
}

impl HttpFencedBroker {
    pub fn new(origin: &str) -> Result<Self, String> {
        let uri: ureq::http::Uri = origin.parse().map_err(|_| "invalid publication origin")?;
        let authority = uri
            .authority()
            .ok_or("publication origin needs a host and port")?;
        if origin.len() > 1024
            || origin.contains(['?', '#'])
            || uri.scheme_str() != Some("http")
            || uri
                .path_and_query()
                .is_some_and(|path| path.as_str() != "/")
            || authority.as_str().contains('@')
            || authority.port_u16().is_none_or(|port| port == 0)
        {
            return Err(
                "publication endpoint must be an internal HTTP origin with an explicit port".into(),
            );
        }
        crate::admission::validate_bootstrap(authority.as_str())?;
        let config = ureq::Agent::config_builder()
            .proxy(None)
            .max_redirects(0)
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(240)))
            .timeout_connect(Some(Duration::from_secs(5)))
            .build();
        Ok(Self {
            origin: origin.trim_end_matches('/').into(),
            client: config.into(),
            reader: KafkaBroker,
        })
    }
}

impl Broker for HttpFencedBroker {
    fn read(&mut self, run: &Admitted) -> Result<Vec<InputRecord>, String> {
        self.reader.read(run)
    }

    fn publish(&mut self, _: &Admitted, _: &[OutputRecord]) -> Result<(), String> {
        Err("fenced publication requires a captured private grant".into())
    }

    fn fenced(&self) -> bool {
        true
    }

    fn capture_grant(
        &mut self,
        run: &Admitted,
        grant: Option<&PublicationGrant>,
    ) -> Result<Option<PublicationGrant>, String> {
        let grant = grant.ok_or("publication authority has not been installed")?;
        grant.validate_run(run)?;
        Ok(Some(grant.clone()))
    }

    fn publish_captured(
        &mut self,
        run: &Admitted,
        records: &[OutputRecord],
        grant: Option<&PublicationGrant>,
    ) -> Result<(), String> {
        let grant = grant.ok_or("fenced publication requires a captured private grant")?;
        grant.validate_run(run)?;
        let bytes = serde_json::to_vec(
            &json!({"epoch":grant.epoch,"requestText":run.request_text,"records":records}),
        )
        .map_err(|_| "cannot encode fenced publication")?;
        let mut response = self
            .client
            .post(format!("{}/v1/publish", self.origin))
            .header("Authorization", format!("Bearer {}", grant.token))
            .header("Content-Type", "application/json")
            .send(bytes.as_slice())
            .map_err(|_| "publication outcome requires reconciliation")?;
        if response.status().as_u16() != 200 {
            return Err(match response.status().as_u16() {
                400 | 401 | 403 | 404 | 409 | 413 | 422 => {
                    "publication authority refused the captured grant or request"
                }
                _ => "publication outcome requires reconciliation",
            }
            .into());
        }
        let mut bytes = Vec::new();
        response
            .body_mut()
            .as_reader()
            .take(65_537)
            .read_to_end(&mut bytes)
            .map_err(|_| "cannot read publication receipt")?;
        verify_receipt(&bytes, &run.request_hash, records, &grant.epoch)
    }
}

fn verify_receipt(
    bytes: &[u8],
    request_hash: &str,
    records: &[OutputRecord],
    epoch: &str,
) -> Result<(), String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "invalid publication receipt")?;
    let value =
        tsx_contracts::parse_json(text, 65_536).map_err(|_| "invalid publication receipt")?;
    let receipt: PublicationReceipt =
        serde_json::from_value(value).map_err(|_| "invalid publication receipt")?;
    if receipt.schema != "opentsx.publication-receipt.v1"
        || receipt.publication_epoch != epoch
        || receipt.request_hash != request_hash
        || receipt.output_records != records.len().to_string()
        || receipt.output_digest != output_digest(records)?
    {
        return Err("publication receipt does not match the captured request and output".into());
    }
    Ok(())
}

pub fn output_digest(records: &[OutputRecord]) -> Result<String, String> {
    let mut bytes = Vec::new();
    for record in records {
        let key = u64::try_from(record.key.len()).map_err(|_| "output key length overflow")?;
        let payload =
            u64::try_from(record.payload.len()).map_err(|_| "output payload length overflow")?;
        bytes.extend_from_slice(&key.to_be_bytes());
        bytes.extend_from_slice(record.key.as_bytes());
        bytes.extend_from_slice(&record.timestamp.to_be_bytes());
        bytes.extend_from_slice(&payload.to_be_bytes());
        bytes.extend_from_slice(&record.payload);
    }
    Ok(tsx_contracts::sha256_bytes(&bytes))
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value.bytes().enumerate().all(|(index, byte)| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || (index > 0 && byte == b'-')
        })
}

#[cfg(test)]
mod tests {
    use super::{output_digest, verify_receipt};
    use crate::broker::OutputRecord;
    use serde_json::{json, Value};

    #[test]
    fn output_digest_uses_utf8_lengths_signed_milliseconds_and_ordered_payload_bytes() {
        let records = vec![OutputRecord {
            key: "é".into(),
            timestamp: -1,
            payload: vec![0, 255],
        }];
        let mut expected = 2_u64.to_be_bytes().to_vec();
        expected.extend_from_slice("é".as_bytes());
        expected.extend_from_slice(&(-1_i64).to_be_bytes());
        expected.extend_from_slice(&2_u64.to_be_bytes());
        expected.extend_from_slice(&[0, 255]);
        assert_eq!(
            output_digest(&records).unwrap(),
            tsx_contracts::sha256_bytes(&expected)
        );
        assert_eq!(
            output_digest(&[]).unwrap(),
            tsx_contracts::sha256_bytes(&[])
        );
    }

    #[test]
    fn receipt_verification_requires_exact_epoch_hash_digest_and_decimal_count() {
        let hash = tsx_contracts::sha256("request");
        let records = vec![OutputRecord {
            key: "series".into(),
            timestamp: 1,
            payload: vec![1, 2],
        }];
        let valid = json!({"schema":"opentsx.publication-receipt.v1","publicationEpoch":"2",
            "requestHash":hash,"outputDigest":output_digest(&records).unwrap(),"outputRecords":"1"});
        verify_receipt(&serde_json::to_vec(&valid).unwrap(), &hash, &records, "2").unwrap();
        for (field, value) in [
            ("schema", json!("other")),
            ("publicationEpoch", json!("1")),
            ("publicationEpoch", json!("02")),
            ("requestHash", json!(tsx_contracts::sha256("other"))),
            ("outputDigest", json!(tsx_contracts::sha256("other"))),
            ("outputRecords", json!(1)),
            ("outputRecords", json!("01")),
            ("outputRecords", json!("2")),
            ("extra", Value::Null),
        ] {
            let mut invalid = valid.clone();
            invalid[field] = value;
            assert!(
                verify_receipt(&serde_json::to_vec(&invalid).unwrap(), &hash, &records, "2")
                    .is_err()
            );
        }
        for bytes in [
            vec![255],
            vec![b' '; 65_537],
            b"{\"schema\":null,\"schema\":null}".to_vec(),
        ] {
            assert!(verify_receipt(&bytes, &hash, &records, "2").is_err());
        }
    }
}
