# Run a local k3s trial

Use the maintained English setup and operations guides for OpenTSx with KafScale
Proxy v1.6.0.

You need a Linux host, an existing k3s cluster and the tools listed in the
[setup plan](setup-plan.md). Follow the [Proxy
setup](../../opentsx-kafscale-conformance/README.md#start-the-local-proxy)
before starting local KafScale. It requires the pinned transport bundle and
private MinIO environment values.

## Prepare the local Proxy

The Compose stack exposes KafScale Proxy at the host's port `9092`. The broker
has no published host port. For clients in k3s pods, configure a reachable LAN
address with `KAFSCALE_ADVERTISED_HOST`. OpenTSx producers connect to KafScale
Proxy or Apache Kafka Broker only.

## Run the probe

Read [Try it locally first](operations.md#try-it-locally-first-the-probe) for
the probe sequence and report. `deploy/k3s/60-probe.sh --local-kafscale` checks
the bundle and private MinIO environment before invoking Compose. `--dry-run`
renders a temporary plan; it does not verify a live k3s deployment or publish
records.

## Check installation and operations

Use the [setup plan](setup-plan.md) for installation, network configuration,
image import and teardown. Use [operations](operations.md) for monitoring,
backups and incident handling, [demos](demos.md) for workloads and
[troubleshooting](troubleshooting.md) for failure checks. A passing syntax or
plan check is not evidence of a completed k3s installation.
