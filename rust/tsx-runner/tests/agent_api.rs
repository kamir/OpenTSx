use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use std::fs;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc};
use std::thread::JoinHandle;
use std::time::Duration;
use tsx_runner::site_client::{SiteClient, RESPONSE_BYTES};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Credentials {
    directory: PathBuf,
}

impl Credentials {
    fn new() -> Self {
        let directory = std::env::temp_dir().join(format!(
            "opentsx-agent-api-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&directory).unwrap();
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700)).unwrap();
        let value = Self { directory };
        value.openssl(&[
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-noenc",
            "-keyout",
            "ca.key",
            "-out",
            "ca.crt",
            "-days",
            "2",
            "-subj",
            "/CN=OpenTSx test CA",
            "-addext",
            "basicConstraints=critical,CA:TRUE",
            "-addext",
            "keyUsage=critical,keyCertSign,cRLSign",
        ]);
        for (name, role) in [
            ("server", "serverAuth"),
            ("s1", "clientAuth"),
            ("s2", "clientAuth"),
            ("unknown", "clientAuth"),
        ] {
            value.openssl(&[
                "req",
                "-new",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-noenc",
                "-keyout",
                &format!("{name}.key"),
                "-out",
                &format!("{name}.csr"),
                "-subj",
                &format!("/CN={name}"),
            ]);
            let extra = if name == "server" {
                "subjectAltName=DNS:localhost\n"
            } else {
                ""
            };
            fs::write(value.path(&format!("{name}.ext")), format!(
                "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage={role}\n{extra}"
            )).unwrap();
            value.openssl(&[
                "x509",
                "-req",
                "-in",
                &format!("{name}.csr"),
                "-CA",
                "ca.crt",
                "-CAkey",
                "ca.key",
                "-CAcreateserial",
                "-out",
                &format!("{name}.crt"),
                "-days",
                "2",
                "-sha256",
                "-extfile",
                &format!("{name}.ext"),
            ]);
            fs::set_permissions(
                value.path(&format!("{name}.key")),
                fs::Permissions::from_mode(0o600),
            )
            .unwrap();
        }
        value
    }

    fn path(&self, name: &str) -> PathBuf {
        self.directory.join(name)
    }

    fn openssl(&self, args: &[&str]) {
        let result = Command::new("openssl")
            .args(args)
            .current_dir(&self.directory)
            .output()
            .expect("OpenSSL is required for ephemeral TLS fixtures");
        assert!(
            result.status.success(),
            "ephemeral certificate generation failed"
        );
    }

    fn client(&self, endpoint: &str, site: &str) -> SiteClient {
        SiteClient::new(
            endpoint,
            &self.path("ca.crt"),
            &self.path(&format!("{site}.crt")),
            &self.path(&format!("{site}.key")),
        )
        .unwrap()
    }

    fn server(&self, tls12: bool) -> Server {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let mut roots = rustls::RootCertStore::empty();
        roots
            .add(certificates(&self.path("ca.crt")).remove(0))
            .unwrap();
        let verifier = rustls::server::WebPkiClientVerifier::builder_with_provider(
            Arc::new(roots),
            provider.clone(),
        )
        .build()
        .unwrap();
        let versions = if tls12 {
            vec![&rustls::version::TLS12]
        } else {
            vec![&rustls::version::TLS13]
        };
        let config = rustls::ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&versions)
            .unwrap()
            .with_client_cert_verifier(verifier)
            .with_single_cert(
                certificates(&self.path("server.crt")),
                PrivateKeyDer::from_pem_file(self.path("server.key")).unwrap(),
            )
            .unwrap();
        let s1 = certificates(&self.path("s1.crt"))
            .remove(0)
            .as_ref()
            .to_vec();
        let s2 = certificates(&self.path("s2.crt"))
            .remove(0)
            .as_ref()
            .to_vec();
        Server::new(Arc::new(config), s1, s2)
    }
}

impl Drop for Credentials {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.directory).unwrap();
    }
}

fn certificates(path: &Path) -> Vec<CertificateDer<'static>> {
    CertificateDer::pem_file_iter(path)
        .unwrap()
        .map(Result::unwrap)
        .collect()
}

#[derive(Debug)]
enum Event {
    Request {
        method: String,
        path: String,
        content_type: String,
        body: Vec<u8>,
    },
    Rejected,
}

struct Server {
    address: SocketAddr,
    stopped: Arc<AtomicBool>,
    events: mpsc::Receiver<Event>,
    thread: Option<JoinHandle<()>>,
}

impl Server {
    fn new(config: Arc<rustls::ServerConfig>, s1: Vec<u8>, s2: Vec<u8>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let stopped = Arc::new(AtomicBool::new(false));
        let stop = stopped.clone();
        let (send, events) = mpsc::channel();
        let thread = std::thread::spawn(move || loop {
            let (socket, _) = listener.accept().unwrap();
            if stop.load(Ordering::SeqCst) {
                break;
            }
            socket
                .set_read_timeout(Some(Duration::from_secs(10)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(10)))
                .unwrap();
            let connection = rustls::ServerConnection::new(config.clone()).unwrap();
            let mut stream = rustls::StreamOwned::new(connection, socket);
            let request = read_request(&mut stream);
            let Ok((method, path, content_type, body)) = request else {
                send.send(Event::Rejected).unwrap();
                continue;
            };
            let peer = stream
                .conn
                .peer_certificates()
                .and_then(|certs| certs.first())
                .map(|cert| cert.as_ref());
            let site = if peer == Some(s1.as_slice()) {
                "s1"
            } else if peer == Some(s2.as_slice()) {
                "s2"
            } else {
                "unknown"
            };
            let (status, response_type, answer, extra) = if site == "unknown" {
                (
                    401,
                    "application/json",
                    b"{\"message\":\"unknown client\"}".to_vec(),
                    "",
                )
            } else if !path.starts_with(&format!("/v1/sites/{site}/")) {
                (
                    403,
                    "application/json",
                    b"{\"message\":\"wrong site\"}".to_vec(),
                    "",
                )
            } else if path.contains("job=absent") {
                (
                    404,
                    "application/json",
                    b"{\"message\":\"job is unknown\"}".to_vec(),
                    "",
                )
            } else if path.ends_with("/status") && body == b"{\"planVersion\":0}" {
                (
                    409,
                    "application/json",
                    b"{\"message\":\"stale version\"}".to_vec(),
                    "",
                )
            } else if path.contains("job=redirect") {
                (
                    302,
                    "application/json",
                    Vec::new(),
                    "Location: https://untrusted.invalid/\r\n",
                )
            } else if path.contains("job=oversized") {
                (200, "application/json", vec![b' '; RESPONSE_BYTES + 1], "")
            } else if path.contains("job=wrongtype") {
                (200, "text/plain", b"{}".to_vec(), "")
            } else if path == "/v1/sites/s1/deliveries/2/ack" {
                (204, "application/json", b"x".to_vec(), "")
            } else if method == "GET" || path.ends_with("/conformance") {
                (
                    200,
                    "application/json",
                    b"{\"result\":\"accepted\"}".to_vec(),
                    "",
                )
            } else {
                (204, "application/json", Vec::new(), "")
            };
            let headers = format!("HTTP/1.1 {status} response\r\nContent-Type: {response_type}\r\nContent-Length: {}\r\nConnection: close\r\n{extra}\r\n", answer.len());
            let written = stream
                .write_all(headers.as_bytes())
                .and_then(|_| stream.write_all(&answer))
                .and_then(|_| stream.flush());
            if written.is_err() {
                send.send(Event::Rejected).unwrap();
                continue;
            }
            send.send(Event::Request {
                method,
                path,
                content_type,
                body,
            })
            .unwrap();
        });
        Self {
            address,
            stopped,
            events,
            thread: Some(thread),
        }
    }

    fn endpoint(&self) -> String {
        format!("https://localhost:{}", self.address.port())
    }
    fn event(&self) -> Event {
        self.events
            .recv_timeout(Duration::from_secs(30))
            .expect("the TLS fixture did not finish the request")
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::SeqCst);
        TcpStream::connect(self.address).expect("cannot wake the TLS fixture");
        self.thread
            .take()
            .unwrap()
            .join()
            .expect("TLS fixture failed");
    }
}

fn read_request(
    stream: &mut rustls::StreamOwned<rustls::ServerConnection, TcpStream>,
) -> std::io::Result<(String, String, String, Vec<u8>)> {
    let mut bytes = Vec::new();
    while !bytes.ends_with(b"\r\n\r\n") {
        let mut byte = [0];
        stream.read_exact(&mut byte)?;
        bytes.push(byte[0]);
        if bytes.len() > 16 * 1024 {
            return Err(std::io::Error::other("fixture header limit"));
        }
    }
    let header = std::str::from_utf8(&bytes).map_err(std::io::Error::other)?;
    let mut lines = header.split("\r\n");
    let mut first = lines.next().unwrap().split(' ');
    let method = first.next().unwrap().to_string();
    let path = first.next().unwrap().to_string();
    let mut count = 0;
    let mut content_type = String::new();
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("content-length") {
                count = value
                    .trim()
                    .parse::<usize>()
                    .map_err(std::io::Error::other)?;
            }
            if name.eq_ignore_ascii_case("content-type") {
                content_type = value.trim().to_string();
            }
        }
    }
    if count > 16 * 1024 * 1024 {
        return Err(std::io::Error::other("fixture request limit"));
    }
    let mut body = vec![0; count];
    stream.read_exact(&mut body)?;
    Ok((method, path, content_type, body))
}

#[test]
fn seven_endpoints_and_four_http_errors_use_mutual_certificate_identity() {
    let credentials = Credentials::new();
    let server = credentials.server(false);
    let client = credentials.client(&server.endpoint(), "s1");
    let endpoints = [
        (
            "GET",
            "/v1/sites/s1/conformance-challenge?job=wind",
            b"".as_slice(),
            200,
            "",
        ),
        (
            "POST",
            "/v1/sites/s1/conformance",
            b"signed.report.bytes",
            200,
            "application/jose",
        ),
        ("GET", "/v1/sites/s1/plans?after=0", b"", 200, ""),
        (
            "POST",
            "/v1/sites/s1/deliveries/1/ack",
            b"{}",
            204,
            "application/json",
        ),
        (
            "POST",
            "/v1/sites/s1/jobs/wind/status",
            b"{}",
            204,
            "application/json",
        ),
        (
            "POST",
            "/v1/sites/s1/jobs/wind/aggregates",
            b"{}",
            204,
            "application/json",
        ),
        (
            "POST",
            "/v1/sites/s1/jobs/wind/refusal",
            b"{}",
            204,
            "application/json",
        ),
    ];
    for (method, path, body, status, expected_type) in endpoints {
        let answer = client.request(method, path, body).unwrap();
        assert_eq!(answer.status, status);
        if status == 204 {
            assert!(answer.body.is_empty());
        }
        match server.event() {
            Event::Request {
                method: actual_method,
                path: actual_path,
                body: actual_body,
                content_type,
            } => {
                assert_eq!(actual_method, method);
                assert_eq!(actual_path, path);
                assert_eq!(actual_body, body);
                assert_eq!(content_type, expected_type);
            }
            Event::Rejected => panic!("a valid TLS request was rejected"),
        }
    }
    for (site, path, body, expected) in [
        ("unknown", "/v1/sites/s1/plans?after=0", b"".as_slice(), 401),
        ("s2", "/v1/sites/s1/plans?after=0", b"", 403),
        (
            "s1",
            "/v1/sites/s1/conformance-challenge?job=absent",
            b"",
            404,
        ),
        (
            "s1",
            "/v1/sites/s1/jobs/wind/status",
            b"{\"planVersion\":0}",
            409,
        ),
    ] {
        let method = if body.is_empty() { "GET" } else { "POST" };
        assert_eq!(
            credentials
                .client(&server.endpoint(), site)
                .request(method, path, body)
                .unwrap()
                .status,
            expected
        );
        assert!(matches!(server.event(), Event::Request { .. }));
    }
}

#[test]
fn tls12_wrong_hostname_and_untrusted_roots_are_rejected() {
    let credentials = Credentials::new();
    let legacy = credentials.server(true);
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(certificates(&credentials.path("ca.crt")).remove(0))
        .unwrap();
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS12])
        .unwrap()
        .with_root_certificates(roots)
        .with_client_auth_cert(
            certificates(&credentials.path("s1.crt")),
            PrivateKeyDer::from_pem_file(credentials.path("s1.key")).unwrap(),
        )
        .unwrap();
    let connection = rustls::ClientConnection::new(
        Arc::new(config),
        "localhost".to_string().try_into().unwrap(),
    )
    .unwrap();
    let socket = TcpStream::connect(legacy.address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(10)))
        .unwrap();
    socket
        .set_write_timeout(Some(Duration::from_secs(10)))
        .unwrap();
    let mut stream = rustls::StreamOwned::new(connection, socket);
    stream.write_all(b"GET /v1/sites/s1/plans?after=0 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").unwrap();
    let mut response = [0; 12];
    stream.read_exact(&mut response).unwrap();
    assert_eq!(&response, b"HTTP/1.1 200");
    assert_eq!(
        stream.conn.protocol_version(),
        Some(rustls::ProtocolVersion::TLSv1_2)
    );
    assert!(matches!(legacy.event(), Event::Request { .. }));
    assert!(credentials
        .client(&legacy.endpoint(), "s1")
        .request("GET", "/v1/sites/s1/plans?after=0", b"")
        .is_err());
    assert!(matches!(legacy.event(), Event::Rejected));
    let server = credentials.server(false);
    let wrong_host = format!("https://127.0.0.1:{}", server.address.port());
    assert!(credentials
        .client(&wrong_host, "s1")
        .request("GET", "/v1/sites/s1/plans?after=0", b"")
        .is_err());
    assert!(matches!(server.event(), Event::Rejected));
    let untrusted = SiteClient::new(
        &server.endpoint(),
        &credentials.path("s2.crt"),
        &credentials.path("s1.crt"),
        &credentials.path("s1.key"),
    )
    .unwrap();
    assert!(untrusted
        .request("GET", "/v1/sites/s1/plans?after=0", b"")
        .is_err());
    assert!(matches!(server.event(), Event::Rejected));
}

#[test]
fn redirects_oversized_bodies_content_types_and_nonempty_acknowledgements_are_rejected() {
    let credentials = Credentials::new();
    let server = credentials.server(false);
    let client = credentials.client(&server.endpoint(), "s1");
    for (method, path, body, message) in [
        (
            "GET",
            "/v1/sites/s1/conformance-challenge?job=redirect",
            b"".as_slice(),
            "redirects",
        ),
        (
            "GET",
            "/v1/sites/s1/conformance-challenge?job=oversized",
            b"",
            "byte limit",
        ),
        (
            "GET",
            "/v1/sites/s1/conformance-challenge?job=wrongtype",
            b"",
            "application/json",
        ),
        ("POST", "/v1/sites/s1/deliveries/2/ack", b"{}", "not empty"),
    ] {
        assert!(client
            .request(method, path, body)
            .unwrap_err()
            .contains(message));
        assert!(matches!(server.event(), Event::Request { .. }));
    }
    assert!(server.events.try_recv().is_err());
}

#[test]
fn local_material_routes_and_request_limits_fail_before_network_io() {
    let credentials = Credentials::new();
    let endpoint = "https://127.0.0.1:1";
    for endpoint in [
        "http://localhost:1",
        "https://user@localhost:1",
        "https://localhost:0",
        "https://localhost:bad",
        "https://localhost:65536",
        "https://localhost:01",
        "https://localhost:",
        "https://localhost:1/path",
        "https://localhost:1?query",
        "https://localhost:1#fragment",
    ] {
        assert!(SiteClient::new(
            endpoint,
            &credentials.path("ca.crt"),
            &credentials.path("s1.crt"),
            &credentials.path("s1.key")
        )
        .is_err());
    }
    for bytes in [
        b"not PEM".to_vec(),
        [
            fs::read(credentials.path("ca.crt")).unwrap(),
            b"garbage".to_vec(),
        ]
        .concat(),
        b"-----BEGIN CERTIFICATE-----\nAAAA\n".to_vec(),
    ] {
        fs::write(credentials.path("bad.crt"), bytes).unwrap();
        assert!(SiteClient::new(
            endpoint,
            &credentials.path("bad.crt"),
            &credentials.path("s1.crt"),
            &credentials.path("s1.key")
        )
        .is_err());
        assert!(SiteClient::new(
            endpoint,
            &credentials.path("ca.crt"),
            &credentials.path("bad.crt"),
            &credentials.path("s1.key")
        )
        .is_err());
    }
    fs::write(
        credentials.path("bad.key"),
        [
            fs::read(credentials.path("s1.key")).unwrap(),
            b"trailing garbage".to_vec(),
        ]
        .concat(),
    )
    .unwrap();
    fs::set_permissions(
        credentials.path("bad.key"),
        fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    assert!(SiteClient::new(
        endpoint,
        &credentials.path("ca.crt"),
        &credentials.path("s1.crt"),
        &credentials.path("bad.key")
    )
    .is_err());
    fs::copy(credentials.path("s1.key"), credentials.path("readable.key")).unwrap();
    fs::set_permissions(
        credentials.path("readable.key"),
        fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    assert!(SiteClient::new(
        endpoint,
        &credentials.path("ca.crt"),
        &credentials.path("s1.crt"),
        &credentials.path("readable.key")
    )
    .is_err());
    std::os::unix::fs::symlink(credentials.path("s1.key"), credentials.path("linked.key")).unwrap();
    assert!(SiteClient::new(
        endpoint,
        &credentials.path("ca.crt"),
        &credentials.path("s1.crt"),
        &credentials.path("linked.key")
    )
    .is_err());
    fs::write(
        credentials.path("mixed.crt"),
        [
            fs::read(credentials.path("s1.crt")).unwrap(),
            b"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n".to_vec(),
        ]
        .concat(),
    )
    .unwrap();
    assert!(SiteClient::new(
        endpoint,
        &credentials.path("ca.crt"),
        &credentials.path("mixed.crt"),
        &credentials.path("s1.key")
    )
    .is_err());
    assert!(SiteClient::new(
        endpoint,
        &credentials.path("ca.crt"),
        &credentials.path("s1.crt"),
        &credentials.path("s2.key")
    )
    .is_err());
    for bytes in [
        b"not a PEM key".to_vec(),
        b"-----BEGIN PRIVATE KEY-----\nAAAA\n".to_vec(),
        [
            fs::read(credentials.path("s1.key")).unwrap(),
            fs::read(credentials.path("s1.key")).unwrap(),
        ]
        .concat(),
    ] {
        fs::write(credentials.path("malformed.key"), bytes).unwrap();
        fs::set_permissions(
            credentials.path("malformed.key"),
            fs::Permissions::from_mode(0o600),
        )
        .unwrap();
        assert!(SiteClient::new(
            endpoint,
            &credentials.path("ca.crt"),
            &credentials.path("s1.crt"),
            &credentials.path("malformed.key")
        )
        .is_err());
    }
    let client = credentials.client(endpoint, "s1");
    for (method, path, body) in [
        ("DELETE", "/v1/sites/s1/plans?after=0", b"".as_slice()),
        ("GET", "/v1/sites/s1/plans?after=01", b""),
        ("GET", "/v1/sites/s1/plans?after=-1", b""),
        ("GET", "//untrusted.invalid", b""),
        ("GET", "/v1/sites/s1/plans?after=0%0a", b""),
        ("GET", "/v1/sites/s1/plans?after=0", b"body"),
    ] {
        let message = client.request(method, path, body).unwrap_err();
        assert!(
            message.contains("route") || message.contains("method") || message.contains("query")
        );
    }
    assert!(client
        .request(
            "POST",
            "/v1/sites/s1/jobs/wind/aggregates",
            &vec![0; 16 * 1024 * 1024 + 1]
        )
        .unwrap_err()
        .contains("byte"));
}
