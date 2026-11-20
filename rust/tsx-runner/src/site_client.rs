use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::fs::{File, OpenOptions};
use std::io::Read;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;
use ureq::tls::{Certificate, ClientCert, PemItem, PrivateKey, RootCerts, TlsConfig, TlsProvider};

pub const RESPONSE_BYTES: usize = 512 * 1024;
const PEM_BYTES: usize = 1024 * 1024;
const AGGREGATE_BYTES: usize = 16 * 1024 * 1024;

pub struct SiteClient {
    endpoint: String,
    agent: ureq::Agent,
}

pub struct SiteResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

impl std::fmt::Debug for SiteResponse {
    fn fmt(&self, value: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        value
            .debug_struct("SiteResponse")
            .field("status", &self.status)
            .field("body_bytes", &self.body.len())
            .finish()
    }
}

impl SiteClient {
    pub fn new(
        endpoint: &str,
        ca: &Path,
        certificate: &Path,
        private_key: &Path,
    ) -> Result<Self, String> {
        let uri: ureq::http::Uri = endpoint.parse().map_err(|_| "invalid plan endpoint")?;
        let authority = uri.authority().ok_or("plan endpoint needs a host")?;
        let port = authority
            .as_str()
            .strip_prefix(authority.host())
            .ok_or("invalid plan endpoint authority")?;
        let valid_port = port.is_empty()
            || port.strip_prefix(':').is_some_and(|port| {
                crate::admission::canonical_i64(port, "plan endpoint port")
                    .is_ok_and(|port| (1..=65535).contains(&port))
            });
        if endpoint.len() > 1024
            || endpoint.contains(['?', '#', '\\', '%'])
            || uri.scheme_str() != Some("https")
            || authority.as_str().contains('@')
            || authority.host().is_empty()
            || authority.port_u16() == Some(0)
            || !valid_port
            || uri
                .path_and_query()
                .is_some_and(|path| path.as_str() != "/")
        {
            return Err("plan endpoint must be one HTTPS origin".into());
        }
        let roots = certificates(&read_pem(ca, false)?)?;
        let chain = certificates(&read_pem(certificate, false)?)?;
        let chain_der: Vec<_> = chain
            .iter()
            .map(|certificate| CertificateDer::from(certificate.der().to_vec()))
            .collect();
        for certificate in &chain_der {
            rustls::server::ParsedCertificate::try_from(certificate)
                .map_err(|_| "plan client certificate is invalid")?;
        }
        let mut key_bytes = read_pem(private_key, true)?;
        let key = key(&key_bytes);
        key_bytes.fill(0);
        let key = key?;
        let mut provider = rustls::crypto::ring::default_provider();
        provider
            .cipher_suites
            .retain(|suite| suite.version().version == rustls::ProtocolVersion::TLSv1_3);
        if provider.cipher_suites.is_empty() {
            return Err("TLS 1.3 cipher suites are unavailable".into());
        }
        let provider = Arc::new(provider);
        let mut trusted = rustls::RootCertStore::empty();
        for certificate in &roots {
            trusted
                .add(CertificateDer::from(certificate.der().to_vec()))
                .map_err(|_| "plan CA certificate is invalid")?;
        }
        rustls::ClientConfig::builder_with_provider(provider.clone())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|_| "TLS 1.3 configuration is invalid")?
            .with_root_certificates(trusted)
            .with_client_auth_cert(
                chain_der,
                PrivateKeyDer::try_from(key.der().to_vec())
                    .map_err(|_| "plan private key is invalid")?,
            )
            .map_err(|_| "plan client certificate and private key are invalid")?;
        let config = ureq::Agent::config_builder()
            .https_only(true)
            .proxy(None)
            .max_redirects(0)
            .max_idle_connections(0)
            .http_status_as_error(false)
            .timeout_connect(Some(Duration::from_secs(10)))
            .timeout_global(Some(Duration::from_secs(30)))
            .tls_config(
                TlsConfig::builder()
                    .provider(TlsProvider::Rustls)
                    .root_certs(RootCerts::from(roots))
                    .client_cert(Some(ClientCert::new_with_certs(&chain, key)))
                    .unversioned_rustls_crypto_provider(provider)
                    .build(),
            )
            .build();
        Ok(Self {
            endpoint: endpoint.trim_end_matches('/').to_string(),
            agent: config.into(),
        })
    }

    pub fn request(&self, method: &str, path: &str, body: &[u8]) -> Result<SiteResponse, String> {
        let content_type = route(method, path)?;
        let maximum = if path.ends_with("/aggregates") {
            AGGREGATE_BYTES
        } else if content_type == "application/jose" {
            crate::run_plan::MAX_COMPACT_BYTES
        } else {
            crate::run_plan::MAX_PAYLOAD_BYTES
        };
        if body.len() > maximum || method == "GET" && !body.is_empty() {
            return Err("plan request exceeds its byte or method limits".into());
        }
        let endpoint = format!("{}{path}", self.endpoint);
        let mut answer = if method == "GET" {
            self.agent.get(&endpoint).call()
        } else {
            self.agent
                .post(&endpoint)
                .header("Content-Type", content_type)
                .send(body)
        }
        .map_err(|_| "authenticated plan service could not be reached")?;
        let status = answer.status().as_u16();
        if (300..400).contains(&status) {
            return Err("plan service redirects are forbidden".into());
        }
        if status == 204
            && (answer.headers().contains_key("transfer-encoding")
                || answer
                    .headers()
                    .get("content-length")
                    .is_some_and(|length| length.as_bytes() != b"0"))
        {
            return Err("plan acknowledgement response is not empty".into());
        }
        if status != 204
            && answer
                .headers()
                .get("content-type")
                .and_then(|header| header.to_str().ok())
                .and_then(|header| header.split(';').next())
                != Some("application/json")
        {
            return Err("plan response must use application/json".into());
        }
        let mut bytes = Vec::new();
        answer
            .body_mut()
            .as_reader()
            .take(RESPONSE_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "plan response could not be read")?;
        if bytes.len() > RESPONSE_BYTES {
            return Err("plan response exceeds its byte limit".into());
        }
        if status == 204 && !bytes.is_empty() {
            return Err("plan acknowledgement response is not empty".into());
        }
        Ok(SiteResponse {
            status,
            body: bytes,
        })
    }
}

fn route(method: &str, path: &str) -> Result<&'static str, String> {
    if path.len() > 1024 || !path.is_ascii() || path.contains(['%', '#', '\\', '\r', '\n']) {
        return Err("invalid plan service route".into());
    }
    let (path, query) = path
        .split_once('?')
        .map_or((path, None), |(path, query)| (path, Some(query)));
    let parts: Vec<_> = path.split('/').collect();
    if parts.len() < 5 || parts[..3] != ["", "v1", "sites"] || !identifier(parts[3]) {
        return Err("invalid plan service route".into());
    }
    if method == "GET" && parts.len() == 5 {
        if parts[4] == "conformance-challenge"
            && query
                .and_then(|query| query.strip_prefix("job="))
                .is_some_and(identifier)
        {
            return Ok("");
        }
        if parts[4] == "plans"
            && query
                .and_then(|query| query.strip_prefix("after="))
                .is_some_and(|value| canonical_sequence(value, false))
        {
            return Ok("");
        }
    }
    if method != "POST" || query.is_some() {
        return Err("invalid plan service method or query".into());
    }
    if parts.len() == 5 && parts[4] == "conformance" {
        return Ok("application/jose");
    }
    if parts.len() == 7
        && ((parts[4] == "deliveries" && canonical_sequence(parts[5], true) && parts[6] == "ack")
            || (parts[4] == "jobs"
                && identifier(parts[5])
                && matches!(parts[6], "status" | "aggregates" | "refusal")))
    {
        return Ok("application/json");
    }
    Err("unknown plan service route".into())
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && value.as_bytes()[0] != b'-'
}

fn canonical_sequence(value: &str, positive: bool) -> bool {
    crate::admission::canonical_i64(value, "delivery sequence")
        .is_ok_and(|sequence| sequence >= i64::from(positive))
}

fn read_pem(path: &Path, private: bool) -> Result<Vec<u8>, String> {
    let file: File = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
        .map_err(|_| "cannot open plan TLS material")?;
    let metadata = file
        .metadata()
        .map_err(|_| "cannot inspect plan TLS material")?;
    if !metadata.is_file()
        || metadata.len() > PEM_BYTES as u64
        || private && (metadata.mode() & 0o077 != 0 || metadata.uid() != unsafe { libc::geteuid() })
    {
        return Err("plan TLS material has invalid type, size or private permissions".into());
    }
    let mut bytes = Vec::new();
    file.take(PEM_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "cannot read plan TLS material")?;
    if bytes.is_empty() || bytes.len() > PEM_BYTES {
        return Err("plan TLS material is empty or exceeds its byte limit".into());
    }
    Ok(bytes)
}

fn pem_blocks(bytes: &[u8], certificate: bool) -> Result<Vec<&[u8]>, String> {
    let mut text = std::str::from_utf8(bytes)
        .map_err(|_| "TLS PEM is not UTF-8")?
        .trim();
    if !text.is_ascii() {
        return Err("TLS PEM must contain ASCII data".into());
    }
    let mut blocks = Vec::new();
    while !text.is_empty() {
        let label = if certificate {
            "CERTIFICATE"
        } else if text.starts_with("-----BEGIN PRIVATE KEY-----") {
            "PRIVATE KEY"
        } else if text.starts_with("-----BEGIN RSA PRIVATE KEY-----") {
            "RSA PRIVATE KEY"
        } else if text.starts_with("-----BEGIN EC PRIVATE KEY-----") {
            "EC PRIVATE KEY"
        } else {
            return Err("TLS PEM contains an unexpected block".into());
        };
        let begin = format!("-----BEGIN {label}-----");
        let end = format!("-----END {label}-----");
        let contents = text
            .strip_prefix(&begin)
            .ok_or("TLS PEM contains unexpected data")?;
        let length = contents.find(&end).ok_or("TLS PEM block is incomplete")?;
        if !contents[..length].bytes().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(byte, b'+' | b'/' | b'=' | b' ' | b'\t' | b'\r' | b'\n')
        }) {
            return Err("TLS PEM block contains invalid data".into());
        }
        let end = begin.len() + length + end.len();
        blocks.push(&text.as_bytes()[..end]);
        text = text[end..].trim();
    }
    if blocks.is_empty() || !certificate && blocks.len() != 1 {
        return Err("TLS PEM block inventory is invalid".into());
    }
    Ok(blocks)
}

fn certificates(bytes: &[u8]) -> Result<Vec<Certificate<'static>>, String> {
    pem_blocks(bytes, true)?
        .into_iter()
        .map(|block| {
            Certificate::from_pem(block).map_err(|_| "TLS certificate PEM is invalid".into())
        })
        .collect()
}

fn key(bytes: &[u8]) -> Result<PrivateKey<'static>, String> {
    let blocks = pem_blocks(bytes, false)?;
    match ureq::tls::parse_pem(blocks[0]).next() {
        Some(Ok(PemItem::PrivateKey(key))) => Ok(key),
        _ => Err("TLS private key PEM is invalid".into()),
    }
}
