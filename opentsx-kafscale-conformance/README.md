# Check Kafka protocol support

Run the OpenTSx conformance suite through KafScale Proxy v1.6.0 or an Apache
Kafka broker.

You need JDK 17 or 21, Maven 3.9 or later, Python 3.10 or later, git, Docker
Compose and network access for dependencies. Run the commands from the OpenTSx
repository root. Tests that require a server are skipped unless
`KAFSCALE_BOOTSTRAP` is set. Never point an OpenTSx producer or fixture at
KafScale Broker directly.

## Build the pinned transport bundle

The [builder](../tools/build_kafscale_proxy.py) compiles the broker, Proxy and
isolated cluster launcher from KafScale v1.6.0 commit
`2809a3a20bf40ed8a2b82cd93215cb59396efb0a`. It runs the pinned Go 1.25.2
compiler container with two CPUs and a 3 GiB memory limit. It builds binaries without building a container image.

```bash
python3 tools/build_kafscale_proxy.py
python3 tools/build_kafscale_proxy.py --check
```

The binaries and their SHA256 manifest are stored in
`target/kafscale-proxy-linux/`. `--check` verifies source identities and binary
hashes without Docker or network access. A changed binary, launcher or builder
rejects reuse and requires rebuilding. The broker version is stamped as `v1.6.0`
through a Go build overlay; the pinned source checkout remains unchanged.

## Start the local Proxy

Export nonempty `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` from your private
environment before running Compose. Use private values and keep them out of
source control, terminal output and reports. Compose rejects missing or empty
values. The local stack uses the same values for MinIO and its broker S3 client.

```bash
docker compose -f docker-compose.kafscale.yml up --detach
docker compose -f docker-compose.kafscale.yml ps
```

The stack exposes KafScale Proxy at `localhost:9092`, plus MinIO at ports `9000`
and `9001`. The broker and etcd remain inside the Compose network. The broker
has no published host port. `KAFSCALE_ADVERTISED_HOST` changes the Proxy address
returned in Kafka Metadata responses; its default is `localhost`. Set it to the
host's reachable LAN address when clients run in k3s pods or on another machine.
OpenTSx producers may connect to Apache Kafka Broker or KafScale Proxy only.

## Run the conformance suite

```bash
KAFSCALE_BOOTSTRAP=localhost:9092 mvn -pl opentsx-kafscale-conformance -am test
cat opentsx-kafscale-conformance/target/kafscale-capabilities.properties
```

The suite asserts topic provisioning, ordered Produce/Fetch, compression codecs,
a one-megabyte episode, committed-position reads and explicit-offset replay. It
records optional capabilities in `target/kafscale-capabilities.properties`. Use
that file from the actual target endpoint to inspect headers, record timestamps,
timestamp seeks, producer idempotence, standalone commits, group rejoin and
Admin offsets.

## Run isolated federation fixtures

The federation fixtures require `KAFSCALE_BOOTSTRAP` plus executable paths in
`KAFSCALE_BROKER_BINARY`, `KAFSCALE_PROXY_BINARY` and `KAFSCALE_CLUSTER_BINARY`.
Point them at `kafscale-broker`, `kafscale-proxy` and `kafscale-cluster` from
the transport bundle, respectively. Each fixture owns four separate etcd stores
and starts one loopback-only broker plus one Proxy per cluster. Every fixture
producer connects through its Proxy. Read the [Wayang
instructions](../opentsx-wayang/README.md) for the JVM federation cases.
