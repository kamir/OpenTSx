# Run Kafka native streaming projections

Use the OpenTSx 3.0.1 Kafka-client path to maintain derived topics on KafScale. The broker acceptance uses KafScale v1.6.0, matching the existing CI broker pin.

## Choose the supported deployment

You need a reachable broker, the OpenTSx Java modules and one processor for each domain. Keep processor checkpoint files on persistent storage. The Kafka bootstrap and advertised broker addresses must be reachable from every client.

The workbench exports a DAG. The SaaS backend forwards an authorized deployment to the retrieval server, which validates it and writes its desired state to the control topic. The processor reads that topic, follows source episodes, writes derived records and publishes status. The retrieval server offers vector components as series in the inventory. See the
[deployment and feed implementation](../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/KafScaleFeed.java#L246).

This integration uses Kafka clients. It does not use a native S3 segment reader. It uses manual partition assignment, explicit offsets and local checkpoints. It does not require timestamp seek, log compaction, transactional writes or idempotent producers. The
[client settings](../../opentsx-kafka-v2/src/main/java/org/opentsx/kafka/KafScaleClients.java#L14) define this boundary.

| Topic | Contents |
| --- | --- |
| `tsx.<domain>.episodes.v2` | Source episodes in the OpenTSx Avro single-object format |
| `tsx.<domain>.synthetic.v2` | Generated input episodes, separate from measurements |
| `tsx.<domain>.clean.v2` | Derived series episodes with signal and flow identity |
| `tsx.<domain>.features.v2` | Features computed from clean series |
| `tsx.<domain>.smart.<name>.vectors.v1` | Aligned vector records as JSON |
| `tsx.<domain>.dags.v1` | Deployment and stop records |
| `tsx.<domain>.dag-status.v1` | Processor state and progress |

Use the [retrieval module instructions](../../opentsx-retrieval/README.md#vector-processor) for the processor launcher and [the SaaS guide](../user-manual/14-saas-service-layer.md) for the account-based front end. The existing processor entry point is `bin/opentsx vectors`; its deployment and stop commands write control records. The retrieval write routes require `--dag-runs true` and use the configured `OPENTSX_DAG_RUN_TOKEN`. An installation without a configured token permits writes from callers that can reach it. The supported boundary is a trusted installation, not a shared tenant-isolation service.

## Publish an executable graph

Every node has a unique, non-empty string ID. Inputs reference existing nodes and the graph has no cycles. The decoder is `decode.avro-soe` and reads the source without node inputs. Sinks form a non-empty array and every sink must be supported. The deployment rejects unsupported sinks, missing inputs, duplicate node IDs, duplicate sink identities and cycles before writing the control record. See
[deployment planning](../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/DagPlan.java#L17).

| Output | Supported execution |
| --- | --- |
| Vector, `opentsx.vector.v1` | `align.vector` directly on source series; preceding transformations are rejected |
| Series, `opentsx.episode.v2` | A single-input chain of `dq.checks`, `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`, `feature.lag` and `feature.rolling` |

A series sink names its signal with `system`, `id` and `grade`. Clean outputs use the domain's clean topic. Feature outputs read clean series and use the features topic. Source series IDs are explicit. Unsupported workbench research operators remain outside this streaming runtime. The supported dispatch is in
[CleanChain](../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/clean/CleanChain.java#L41) and
[VectorJob](../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/VectorJob.java#L53).

The deployment response means that the control record was acknowledged. Confirm actual execution through `GET /v1/dags`: inspect each sink's state, record count, watermark and error. Use `GET /v1/inventory` to discover the source series and the derived vector components. An accepted deployment alone is not proof of output freshness.

## Preserve delivery semantics

There is exactly one processor per domain. Its runners assign all source partitions themselves. Increasing replicas duplicates writers and is not a failover strategy.

Output is at least once. A crash can repeat output after the last checkpoint. Vector readers retain the latest record per key and tick; episode readers deduplicate by episode ID. Checkpoints contain source positions and operator state and are persisted after output acknowledgments. The broker acceptance verifies a graceful processor restart with an open resampling window. It does not certify machine-loss or object-store recovery.

A changed DAG starts again from the source beginning unless it requests `source.start: latest`. Episodes must advance in order within each series; older or overlapping blocks are skipped and counted. Vector finalization depends on expected-series progress and configured lateness. A silent series can hold up output. Checkpoint corruption logs an error and starts over; this requires operational review before production use. See the
[processor contract](../../opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/VectorProcessor.java#L46).

A stop record is applied asynchronously. Once the processor consumes it, its runners are removed. Inspect desired state and sink status rather than treating request acknowledgment as a synchronous processing barrier.

## Verify the components offline

You need JDK 21 and Maven 3.9.8 for the verified local toolchain. This command runs the retrieval reactor and its required modules without a broker when `KAFSCALE_BOOTSTRAP` is unset. Broker-dependent cases are skipped.

```bash
mvn -B -ntp -pl opentsx-retrieval -am verify
```

## Verify the broker integration

You need a dedicated disposable KafScale broker listening on `127.0.0.1:39092`. The acceptance creates random test domains and starts temporary local HTTP endpoints. It does not remove broker topics; use a disposable broker rather than a customer cluster. The CI job starts KafScale v1.6.0 with in-memory storage.

```bash
KAFSCALE_BOOTSTRAP=127.0.0.1:39092 mvn -B -ntp -pl opentsx-retrieval -am -Dtest=KafkaStreamingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Read `opentsx-retrieval/target/surefire-reports/TEST-org.opentsx.retrieval.server.KafkaStreamingIntegrationTest.xml`. The suite contains three cases: continued vector output with inventory/status and stop, rejection before control publication, and series resampling across a checkpoint restart. Require three executed cases with zero failures, errors and skips. The default offline build cannot establish broker acceptance. The
[acceptance implementation](../../opentsx-retrieval/src/test/java/org/opentsx/retrieval/server/KafkaStreamingIntegrationTest.java)
is the executable reference for these cases.
