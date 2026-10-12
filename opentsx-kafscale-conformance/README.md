# opentsx-kafscale-conformance

Checks the Kafka-protocol features that OpenTSx episode storage and replay rely on, against a running
[KafScale](https://kafscale.io) broker (or any Kafka-compatible broker for comparison).

Tests are **skipped** unless `KAFSCALE_BOOTSTRAP` is set, so a normal `mvn test` does not need a broker.

## Run

```bash
docker compose -f docker-compose.kafscale.yml up -d          # etcd + MinIO + KafScale broker
KAFSCALE_BOOTSTRAP=localhost:9092 mvn -pl opentsx-kafscale-conformance test
cat opentsx-kafscale-conformance/target/kafscale-capabilities.properties
```

Without Docker, the broker can be built from source and run with in-memory S3 and metadata:

```bash
git clone https://github.com/KafScale/platform && cd platform
go build -o kafscale-broker ./cmd/broker
KAFSCALE_USE_MEMORY_S3=1 KAFSCALE_BROKER_ADDR=127.0.0.1:39092 \
KAFSCALE_BROKER_HOST=127.0.0.1 KAFSCALE_BROKER_PORT=39092 ./kafscale-broker
```

## What is checked

**Required** (asserted; OpenTSx depends on them):

| Test | Why OpenTSx needs it |
|------|----------------------|
| `createsTopicsViaAdminApi` | topic provisioning (`tsx.<domain>.*.v2`) |
| `producesAndConsumesInOrderPerKey` | key = seriesId, episodes of one series stay ordered |
| `roundTripsEveryCompressionCodec` (none/snappy/lz4/zstd) | zstd is the default for episode topics |
| `roundTripsOneMegabyteEpisode` | large episodes as single records |
| `committedOffsetsAreReadableAfterRestart` | resume a consumer from a committed position |
| `seeksToExplicitOffsetForIndexBasedReplay` | replay via the episode index (`seek(partition, offset)`) |

**Optional** (probed and written to `target/kafscale-capabilities.properties`; OpenTSx must work without them).

## Results (2026-10-09)

Same suite, same client (kafka-clients 3.9.1); the Apache Kafka column validates the probes themselves.

| Capability | KafScale v1.6.0 and main@7aa2a11 (built from source, in-memory S3 + metadata) | Apache Kafka 3.9.1 (KRaft) |
|------------|------------------------------------------|----------------------------|
| Required tests | ✅ all pass | ✅ all pass |
| `record.headers.preserved` | ✅ true | ✅ true |
| `record.createtime.preserved` | ✅ true | ✅ true |
| `listoffsets.timestamp.supported` (`offsetsForTimes`) | ❌ false – returns the log end offset | ✅ true |
| `producer.idempotence.supported` | ❌ false | ✅ true |
| `offsetcommit.standalone.supported` (commit without group membership, as Flink does) | ❌ false – `CommitFailedException` | ✅ true |
| `group.rejoin.assigned` (a restarted group member gets partitions again) | ❌ false – no assignment within 20 s | ✅ true (49 ms) |
| `topic.cleanup.policy.compact.accepted` | accepted, but KafScale does not compact | ✅ true |

### Root causes found in KafScale

Tracked in the fork: [kamir/kafscale#16](https://github.com/kamir/kafscale/issues/16) (timestamp seek),
[kamir/kafscale#17](https://github.com/kamir/kafscale/issues/17) (group rejoin),
[kamir/kafscale#18](https://github.com/kamir/kafscale/issues/18) (standalone commits).

* **Timestamp seek:** `handleListOffsets` (`cmd/broker/main.go`) only handles `-2` (earliest); every other
  timestamp returns the next offset.
* **Group rejoin:** `GroupCoordinator.LeaveGroup` (`pkg/broker/coordinator.go`) reads only `MemberID`, which is
  empty in LeaveGroup v3+ (the member list is in `Members[]`), so a closed consumer stays in the group.
  `JoinGroup` then answers the new member with `REBALANCE_IN_PROGRESS` instead of `MEMBER_ID_REQUIRED`;
  the Java client drops the assigned member id and rejoins in a tight loop (≈ 22 000 JoinGroup requests in 60 s
  in our trace), each creating another member, so the group never stabilises.
* **Standalone commits:** `OffsetCommit` with generation `-1` and no member id is rejected
  (Apache Kafka accepts it for groups without active members).

### Consequences for OpenTSx

* Replay uses **manual assignment + seek** driven by the episode index, never `offsetsForTimes`.
* Replay/consumer positions are stored by OpenTSx (episode index / job state), not via standalone commits.
* Consumers that use group management must use a **fresh group id per run** until the rejoin issue is fixed.
* Producers set `enable.idempotence=false`; duplicates are removed downstream by `episodeId`.
