# Vector and DAG rules

This page specifies the DAG document `opentsx.dag.v1`, the operator `align.vector`, the record `opentsx.vector.v1`
and the vector processor that runs them; section 4 was verified against the code with the per-episode pair clock
(fix of 2026-11-20), the others against commit `a513aa98`.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

"Implemented in" lines cite `file:line`. Short paths stand for these folders:

| Short path | Folder |
|---|---|
| `vector/` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/` |
| `server/` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/` |
| `clean/` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/clean/` |
| `vector-tests/` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/vector/` |
| `kafka/` | `opentsx-kafka-v2/src/main/java/org/opentsx/kafka/` |
| `workbench/` | `opentsx-saas-frontend/src/workbench/` |

## 1. DAG document

An `opentsx.dag.v1` document is YAML. It names one source, a list of nodes and a list of sinks. The web workbench
writes it. The vector processor, the retrieval server and the command `opentsx vectors` read it. A document with a
series sink (a sink with `schema: opentsx.episode.v2`) is an `opentsx.dag.v2` document. This page does not specify
series sinks.

Implemented in: `workbench/flow.ts:706-763` (write), `vector/DagPlan.java:17-132` and `vector/VectorJob.java:53-138`
(read), `vector/SeriesJob.java:56-140` (series sinks).

### 1.1 Fields

| Field | Type | Rule | Read by the processor |
|---|---|---|---|
| `dag` | string | Name of the deployment. MUST match `[A-Za-z0-9][A-Za-z0-9._-]*` to be deployed. | yes |
| `version` | integer | The workbench writes `1`. | no |
| `schema` | string | Absent in an `opentsx.dag.v1` document. The workbench writes `opentsx.dag.v2` when a sink is a series sink. | no |
| `source.kind` | string | The workbench writes `kafscale-broker`. | no |
| `source.topics` | list of strings | Topics the document reads. With a vector sink exactly `[tsx.<domain>.episodes.v2]`. | yes |
| `source.series` | list of strings | Series ids of the source topic. MUST NOT be empty when the document has a vector sink. | yes |
| `source.start` | `earliest` or `latest` | Where a new runner begins in the source topic. Default `earliest`. | yes |
| `checkpoint` | map | The workbench writes `{ backend: file }`. | no |
| `nodes[].id` | string | Non-empty and unique in the document. The workbench names the decoder node `decode`. | yes |
| `nodes[].op` | string | Operator name. The only decoder is `decode.avro-soe`. | yes |
| `nodes[].in` | list of node ids | Inputs in order. `decode.avro-soe` has none. Every other node has at least one. | yes |
| `nodes[].params` | map | Operator parameters. For `align.vector` see [2.1](#21-parameters). | yes |
| `nodes[].experimental` | boolean | The workbench writes `true` for `pair.*` operators. | no |
| `sinks[].from` | node id | The node whose output the sink writes. | yes |
| `sinks[].topic` | string | Topic the sink writes. | yes |
| `sinks[].schema` | string | Record type of the sink. The processor accepts `opentsx.vector.v1` and `opentsx.episode.v2`. | yes |

The workbench also writes sinks that the processor refuses: `schema: org.opentsx.model.v2.SeriesStats` for a
published `stats.window` node, and `schema: opentsx.episode.v2` without a `signal` for other published nodes. A
series sink without a signal gets `a series sink names the signal it writes: signal: { system, id, grade }`.

Implemented in: `vector/VectorProcessor.java:220-230`, `vector/VectorJob.java:57-63,109-112`,
`vector/DagPlan.java:25-29,43-48,79-111`, `vector/SeriesJob.java:73-82`, `workbench/flow.ts:717-739,759-760`.

### 1.2 Checks before a deployment

The retrieval server and `opentsx vectors deploy` check a document before they write it to the control topic. The
processor checks it again when it takes the deployment up. All three run `DagPlan.of`. A refused document gets one of
these messages:

| Check | Message |
|---|---|
| The text is YAML. Server and command only. | `the DAG is no valid YAML: <reason>` |
| `dag` matches `[A-Za-z0-9][A-Za-z0-9._-]*`. Server and command only. | `the DAG needs a name (dag: ...) of letters, digits, dots, hyphens and underscores` |
| The document is a map with a `nodes` list. | `the document is no opentsx.dag DAG (no nodes)` |
| Every node has a unique, non-empty string `id`. | `every node needs a non-empty string id`, `duplicate node id <id>` |
| A decoder is `decode.avro-soe` and reads no node. | `node <id>: unsupported decoder <op>`, `decoder <id> must read the source without node inputs` |
| Every other node has an `op` and at least one input. | `node <id> needs an operator and a non-empty input array` |
| Every input names a node of the document. | `node <id>: missing input <input>` |
| The nodes form no cycle. | `the DAG contains a cycle` |
| `sinks` is a non-empty list. | `sinks must be a non-empty array` |
| Every sink has the schema `opentsx.vector.v1` or `opentsx.episode.v2`. | `unsupported sink <topic>: schema must be opentsx.vector.v1 or opentsx.episode.v2` |
| A vector sink reads an `align.vector` node. | `unsupported sink <topic>: a vector sink must read align.vector` |
| No two sinks have the same identity. A vector sink is identified by the `<name>` of its topic. | `duplicate sink identity <name>` |
| A document with a vector sink names its series. | `a vector that keeps running needs the ids of its series (source.series). Load the series from the TS inventory, then build the vector.` |

The checks of [2.5](#25-what-the-processor-runs) follow for every vector sink. The checks cover structure only. They
do not check that the record types of connected nodes match.

The retrieval server answers a refused document with HTTP 400 and `{"error": "<message>"}`.

Implemented in: `vector/DagPlan.java:17-132`, `vector/VectorProcessor.java:220-230,280-293`,
`server/KafScaleFeed.java:248-251`, `server/RetrievalServer.java:219-220,261-274`,
`vector/VectorProcessorApp.java:54-58`.

### 1.3 Document hash

`specHash` is the lower-case hex SHA-256 of the UTF-8 bytes of the document text as it was deployed. Comments and
whitespace count. The processor writes it into every vector record (`provenance.specHash`), every clean episode
(label `specHash`), every status record and every checkpoint.

A deployment with the hash of the running document changes nothing. Any other text under the same name starts the
DAG over ([3.2](#32-runners)).

Implemented in: `vector/VectorProcessor.java:258-264,618-627,763-766,797-803`, `vector/VectorJson.java:62-64`,
`clean/SeriesStream.java:152-155`.

### 1.4 Example

This is the document `VectorJobTest` reads, with the series ids of `wind.met.wind_speed{park=p,turbine=T01}` and
`wind.turbine.power_active{park=p,turbine=T01}` written out:

```yaml
# opentsx.dag.v1, written by the OpenTSx web workbench
dag: wind-exp2-smart
version: 1
source:
  kind: kafscale-broker
  topics: [tsx.wind.episodes.v2]
  series: [e740b3452ed6b905, fe7df239f4afc445]
checkpoint: { backend: file }
nodes:
  - id: decode
    op: decode.avro-soe
  - id: n1
    op: align.vector
    in: [decode]
    params: { every: PT60S, key: turbine, agg: MEAN, emit: any, names: [state] }
sinks:
  - { from: n1, topic: tsx.wind.smart.turbine-state.vectors.v1, schema: opentsx.vector.v1 }
```

The vector is `turbine-state`. The one input carries two series per turbine, so its components are `power_active`
and `wind_speed`. The Kafka key of turbine `T01` is `turbine=T01`.

Implemented in: `vector-tests/VectorJobTest.java:28-59,66-78,144-165`.

## 2. Vector measuring points

`align.vector` puts the series of several inputs on one clock. For every tick and key it gives one measuring point
with one value per component. The processor writes the points as `opentsx.vector.v1` records to a vector topic.

The web workbench and Java implement the operator. Both pass the shared test vectors in
[testvectors/align-vector.v1.json](testvectors/align-vector.v1.json).

Implemented in: `workbench/vector.ts:138-259`, `vector/VectorAligner.java:100-223`, `vector/VectorStream.java:153-352`.

### 2.1 Parameters

| Parameter | Type | Default | Rule |
|---|---|---|---|
| `every` | ISO-8601 duration | none | Length of a tick. Required. MUST be greater than 0. |
| `key` | string | `series` | `series`, `all` or a tag name. See [2.2](#22-clock-components-and-key). |
| `agg` | enum | `MEAN` | `MEAN`, `LAST`, `FIRST`, `MIN`, `MAX`, `SUM` or `COUNT`. |
| `fill` | enum | `none` | `as-of` fills a missing value with the last one. A missing `fill` is `none`. Any other string is refused, see below. |
| `maxAge` | ISO-8601 duration | the value of `every` | How old a value may be that `fill: as-of` takes. |
| `emit` | enum | `any` | `complete` writes only ticks in which every component has a value. Any other value means `any`. |
| `names` | list of strings | `in1`, `in2` and so on | One component name per input, in the order of `in`. Two inputs MUST NOT have the same name ([2.5](#25-what-the-processor-runs)). |
| `lateness` | ISO-8601 duration | `PT0S` | Processor only: how long a tick stays open after every series has passed its end. A negative value counts as 0. |

`every`, `maxAge` and `lateness` MUST be ISO-8601 durations such as `PT60S`. A number such as `60` is refused with
`node <id>: every and maxAge must be durations like PT60S` or `node <id>: lateness must be a duration like PT2M`.

A `fill` other than `none` or `as-of`, such as `asof`, is refused. The workbench preview and the Rust runner refuse it
with `vector fill must be none or as-of`. For a vector that feeds a published node, the deploy checks of the workbench
and of the Flow Composer list `<label>: vector fill must be none or as-of.`, where `<label>` is the name of the node,
so the workbench neither downloads nor deploys the flow; a vector left out of the DAG does not block it. Its DAG export writes the fill as stored. The processor refuses it with
`node <id>: vector fill must be none or as-of`. Wayang refuses it with
`node <id>: parameter fill must be one of [none, as-of]`.

The workbench edits seconds and writes `PT<seconds>S`. It writes `maxAge` only with `fill: as-of`, and `lateness`
only when it is greater than 0.
It writes a name typed in its comma-separated parameter `names` in lower-case snake case: lower case, every run of
characters other than a-z and 0-9 becomes `_`, and `_` at either end is dropped. A typed name is never renamed, so
two typed names with the same snake-case name are refused. A typed name without a letter or digit counts as left
empty. It derives a name left empty from the bucket name and appends `_2`, `_3` and so on when another input has
that name.

Implemented in: `vector/VectorJob.java:83-108,123-138`, `vector/VectorAligner.java:21,29-38,109`,
`vector/VectorStream.java:114-115`, `workbench/flow.ts:505-512,694-696`, `workbench/vector.ts:341-346`,
`rust/tsx-runner/src/pairs.rs:182-185`, `opentsx-wayang/src/main/java/org/opentsx/wayang/DagToWayang.java:236-245`.

### 2.2 Clock, components and key

| Term | Rule |
|---|---|
| Clock | Tick k covers the half-open interval [k · every, (k + 1) · every) of time since 1970-01-01T00:00:00Z. The first sample does not move the grid, so two runs and two runtimes cut the same ticks. |
| Component | Every input is one component, named by `names`. When an input has more than one series for any key, every series of that input is a component of its own: `<name>.<series>` with several inputs, `<series>` with one input. `<series>` is the last dot-separated part of the metric in lower-case snake case, or `value` when that is empty. The components of one input are in name order, so the order does not depend on the arrival of the series. A name that an earlier input already gave is not added again. |
| Value | The aggregate `agg` of the samples of the component in the tick. NaN and infinite samples do not count. |
| Key | What forms one vector. `series`: one vector per series id; the key holds the tags of the series and `series` with its id. A tag name such as `turbine`: one vector per value of that tag, so the channels of one turbine form one vector; the key holds that tag only, with `""` for a series without it. `all`: one vector; the key is empty. A tag that no series carries is refused with `no series carries the tag "<tag>"; use series, all or a tag of the series`. |
| Missing value | A component without a value in the tick is `null`. With `fill: as-of` it takes the last value of the component when that value lies at most floor(maxAge / every) ticks back. |
| Ticks | Per key the ticks run from the first to the last tick in which any component of the key has a value. With `emit: any` a tick is written when at least one component has a value, filled values included. With `emit: complete` it is written only when every component has one. |
| Time axis | Every input MUST have a time axis. The workbench refuses spectra, histograms and other axes with `<bucket> has a <kind> axis. A vector needs inputs with a time axis.` |
| One series per component | The key MUST tell apart the series that give the same component. The processor, `VectorAligner`, the workbench preview and the Rust runner `tsx-runner` refuse data in which two series give the same component of one vector: `the series <a> and <b> both give the component "<c>" of one vector; choose a key that tells them apart`. `<a>` and `<b>` are the ids of the two series, `<a>` the first in id order. |

Implemented in: `vector/VectorAligner.java:89-98,108-208,210-223`, `workbench/vector.ts:57-94,138-199`,
`vector/VectorStream.java:244-286`, `rust/tsx-runner/src/pairs.rs:461-519`, `workbench/vector.test.ts:191-200`,
`rust/tsx-runner/tests/pair_vector_vectors.rs:461-482`.

### 2.3 Record

A vector topic holds one `opentsx.vector.v1` JSON record per measuring point. JSON Schema:
[schemas/opentsx.vector.v1.schema.json](schemas/opentsx.vector.v1.schema.json).

| Field | Type | Rule |
|---|---|---|
| `schema` | string | `opentsx.vector.v1`. |
| `vector` | string | Name of the vector. The processor writes the `<name>` of the sink topic. The workbench preview writes the label of the node. |
| `key` | map of strings | The key of the vector, see [2.2](#22-clock-components-and-key). |
| `ts` | string | End of the tick: the time from which the vector is complete. Equal to `window.to`. |
| `window.from`, `window.to` | string | Start and end of the tick, the half-open interval [from, to). |
| `clock` | string | Length of a tick as `PT<seconds>S`, for example `PT60S`. |
| `components` | list of strings | Component names in vector order. Each MUST match `^[a-z0-9_]+(\.[a-z0-9_]+)?$`. No name occurs twice. |
| `values` | list of number or null | One value per component, in the order of `components`. `null` is a missing value. |
| `missing` | integer | Number of `null` entries in `values`. |
| `provenance` | map | Written by the processor: `dag` (the DAG name) and `specHash` ([1.3](#13-document-hash)). The workbench preview leaves it out. |

* Times are ISO-8601 in UTC. The processor writes the shortest form of the instant, for example
  `2026-10-01T00:01:00Z`. The workbench preview writes milliseconds, for example `2026-10-01T00:01:00.000Z`, and a
  number of seconds for data without absolute time. A reader MUST parse times as instants and MUST NOT compare their
  text.
* `values` is a list in the order of `components`, not a map from name to value. A model reads the vector without
  resolving names. Every record still carries `components`, so a reader needs no DAG.
* The Kafka key is the text form of `key`: the pairs `<tag>=<value>` in key order, joined by `,`, without escaping.
  For example `turbine=T01`. It is empty for `key: all`.
* The topic is `tsx.<domain>.smart.<name>.vectors.v1`. `<name>` MUST match `[a-z0-9][a-z0-9-]*`. The processor
  creates a missing topic with one partition, `compression.type=zstd` and `cleanup.policy=delete`, so a reader gets
  the measuring points in the order they were written.
* The processor does not check component names against the pattern. `params.names` of a document MUST follow it.

Implemented in: `vector/VectorJson.java:28-66`, `workbench/vector.ts:295-339`,
`kafka/TopicLayout.java:27,107-116,183-200`, `vector/VectorProcessor.java:385-391`,
`vector/VectorProcessorApp.java:85-89`, `vector-tests/VectorJobTest.java:157-165`.

### 2.4 Read vectors back as series

The retrieval server started with `--kafscale` reads every vector topic of its domain and offers each component as a
series.

| Point | Rule |
|---|---|
| Series | One series per key and component. Metric `smart.<vector>.<component>`, tags: the key plus `vector=<vector>`. Labels `smartTopic`, `schema` and `component`; source `smart-topic`. |
| Time axis | Regular, one value per tick of the clock. A tick without a record and a `null` value are both gaps (NaN). |
| Repeated ticks | Per key and tick start the record read last counts. A rerun appends to the topic, which is not compacted. |
| Layout | The record with the latest tick of a key sets clock and components. Records of that key with another clock, other components or a tick start off that grid are left out. |
| Invalid records | A record is ignored when it does not parse, has another `schema`, has `values` and `components` of different or zero length, or has a `window.to` that is not after `window.from`. |

Implemented in: `server/KafScaleFeed.java:200-240`, `vector/VectorStoreBridge.java:26-60`,
`vector/VectorJson.java:76-102`.

### 2.5 What the processor runs

For a vector sink the processor runs `align.vector` directly on the series of the source topic. These checks apply in
addition to [1.2](#12-checks-before-a-deployment):

| Point | Rule | Message |
|---|---|---|
| Inputs | Every input of the `align.vector` node MUST be the decoder node. Another operator before the vector is refused and named. | `node <id> (<op>) is not available in this runtime: it executes align.vector on the series of the source topic only. Build the vector from the source bucket.` |
| Names | Two inputs of the `align.vector` node MUST NOT have the same name. Without `names`, the input at position `<i>` from 1 is named `in<i>`. The vector processor also names an input `in<i>` when `names` has no entry or a blank entry for it. Wayang refuses a `names` list whose length differs from `in`, or that holds a blank name, with `node <id>: names must uniquely name every input`. The Rust runner refuses such a length with `vector names must uniquely name every input` and a blank name with `vector input name must be a nonempty string`. Wayang and the Rust runner refuse two inputs with one name with the same message as the processor. `VectorAligner.align`, `new VectorStream(inputNames, ...)`, `VectorStream::new` of the Rust runner and the workbench preview refuse it with the text after `node <id>: `. For a vector node that feeds a published node, the deploy checks of the workbench list `<label>: two inputs of the vector are both named "<name>"; give every input its own name.`, where `<label>` is the name of the node, so the workbench neither downloads nor deploys the flow. A vector node that feeds no published node is left out of the DAG and is not checked. | `node <id>: two inputs of the vector are both named "<name>"; give every input its own name` |
| Source topic | `source.topics` MUST be `[tsx.<domain>.episodes.v2]` of the domain of the processor. A vector over several topics is not supported. | `the DAG reads <topics>; this server holds <topic>` |
| Series | Every input reads every series named in `source.series`. The workbench writes the series chosen in the TS inventory. | see [1.2](#12-checks-before-a-deployment) |
| Sink topic | MUST be `tsx.<domain>.smart.<name>.vectors.v1` of the domain of the processor. | `sink topic <topic> must be tsx.<domain>.smart.<name>.vectors.v1 (lower-case letters, digits and hyphens in <name>)` |
| Derived episodes | Episodes cut out of other episodes, with a parent episode id or a source episode id, are ignored. | none |

Implemented in: `vector/VectorJob.java:53-121`, `vector/VectorProcessor.java:378-391,602-606`,
`workbench/flow.ts:686-689,724`, `vector/VectorAligner.java:104-107,225-234`, `vector/VectorStream.java:106-109`,
`workbench/vector.ts:100-130,151-152`, `opentsx-wayang/src/main/java/org/opentsx/wayang/DagToWayangBuild.java:61-78`,
`opentsx-wayang/src/main/java/org/opentsx/wayang/DagToWayang.java:74`, `rust/tsx-runner/src/pairs.rs:333-340,1789-1794`,
`rust/tsx-runner/src/catalog.rs:519-541,1206-1210`.

## 3. Vector processor

The vector processor keeps the sinks of deployed DAGs up to date. It follows the source topic of each sink and
appends records as they become final. It runs as `opentsx vectors` and, on k3s, as the component `vector-processor`
(Deployment `opentsx-vectors`, enabled with `VECTOR_PROCESSOR=true`).

Implemented in: `vector/VectorProcessor.java:46-150`, `vector/VectorProcessorApp.java:16-102`, `bin/opentsx:56`,
`deploy/k3s/components/vector-processor/vector-processor.yaml:1-14`, `deploy/k3s/20-configure.sh:233`.

### 3.1 Control and status topics

Deploying and stopping are records in the control topic. The processor reports per sink to the status topic. Both
topics have one partition. The retrieval server and `opentsx vectors` create them when they are missing.

| Topic | Record | Kafka key | Rule |
|---|---|---|---|
| `tsx.<domain>.dags.v1` | `opentsx.dag-control.v1`: `schema`, `dag`, `state` (`deployed` or `stopped`), `spec` (the document text, with `deployed` only), `by`, `at` | DAG name | Per DAG name the latest record counts, so a deployment survives every restart of the processor. The processor reads the topic every 5 s. |
| `tsx.<domain>.dag-status.v1` | `opentsx.dag-status.v1`: `schema`, `dag`, `vector`, `kind` (`vector` or `series`), `topic`, `state`, `specHash`, `records`, `skippedBlocks`, `watermark`, `waitingFor` (at most 10 ids), `waitingForCount`, `undecodable` (when not 0), `error` (when set), `at` | `<dag>/<sink>` | The latest record per key counts. Written on every change of `state`, when a checkpoint with new records is saved, and at least every 30 s. |
| `tsx.<domain>.dag-status.v1` | Refusal: `schema`, `dag`, `state` (`failed`), `specHash`, `error`, `at` | `<dag>` | Written when the processor cannot run a deployed document. |

`<sink>` is the `<name>` of a vector topic, or the signal id of a series sink. `state` of a sink is `waiting` (a named series has not been seen yet),
`running`, `failed` or `stopped`. `watermark` is the instant up to which every named series has data. `records` is
the number of records the runner has sent.

Implemented in: `kafka/TopicLayout.java:130-160`, `vector/VectorProcessor.java:67-81,137-150,203-256,296-301,763-788`,
`vector/VectorStream.java:222-233`, `server/KafScaleFeed.java:272-277`, `vector/VectorProcessorApp.java:48-50`.

### 3.2 Runners

| Point | Rule |
|---|---|
| Runner | One runner per sink of a deployed DAG, named `<dag>/<sink>`, with its own consumer, read positions, state and producer. |
| Start | A new or changed DAG begins at the start of its source topic (`source.start: earliest`) or at its end (`latest`). The runners of other DAGs are not touched. |
| Same document | A deployment with the hash of the running document changes nothing. |
| Stop and change | A stop record, or another document under the same name, ends the runners of that name and deletes their checkpoints. The old output stays in the topic under the old hash. |
| Series | Until every series named in `source.series` has been seen once, the runner writes nothing and reports `waiting`. Only then is it known which series form a vector and what the components are called. |
| Final tick | The watermark is the smallest end (`tEnd` of the latest episode) over all named series. Tick k is final when (k + 1) · every ≤ watermark − lateness. Only final ticks are written. With episodes of 10 minutes and a clock of 60 s, ten measuring points per key appear at once. |
| Order per series | The first sample of an episode MUST NOT lie before the end of the previous episode of the same series. An episode that does is skipped and counted in `skippedBlocks`: a duplicate after a retried write, or data out of order. `lateness` covers the offset between series, not late episodes of one series. |
| Checkpoint | One file per runner in the state directory: `<dag>--<sink>.json`, with every character outside `[A-Za-z0-9._-]` replaced by `_`. It holds the read positions, the stream state (open ticks, the last value per series for `as-of`, progress), `records` and `specHash`. A snapshot is taken every 10 s, or earlier at 5000 unconfirmed sends. The file is written, by atomic rename, only after the broker has confirmed every record sent before the snapshot. A checkpoint with another `specHash` is ignored. |
| After a crash | The runner repeats at most what it sent after its last checkpoint. The values are the same, and readers take the later record per key and tick. |
| Refused reads | A fetch the broker refuses is tried again every 5 s. The consumer goes back to its positions before that fetch, so no record is skipped. After 10 minutes of refusals the runner reports `failed`. |
| Missing confirmations | A send retries up to the delivery timeout of 3 minutes. When a send fails, or a confirmation is still missing 3 minutes 30 s after the snapshot, the runner reports `failed`. It starts again 60 s later from its last checkpoint, with a new producer and a new consumer. |
| One producer per runner | Every runner writes only to its sink topic, with its own producer. Status records use one more producer, replaced after a failed send. Waiting for confirmations does not stop the loop. A sink whose writes the broker does not confirm holds up neither the other sinks nor the status. |
| Equality with the one-shot operator | Fed with all data in which no two series give the same component of one vector, `VectorStream` gives the measuring points that `VectorAligner` gives, whatever the order in which the series arrive. On data in which two series give one component, `VectorStream` refuses ([2.2](#22-clock-components-and-key)) and `VectorAligner` aggregates them into one value. |
| Shutdown | On an orderly stop every runner writes a last checkpoint when the broker confirms within 30 s. `opentsx vectors` waits up to 60 s for this. |

Implemented in: `vector/VectorProcessor.java:73-81,83-87,102-105,176-199,258-294,492-761`,
`vector/VectorStream.java:182-215,222-233,288-352,364-442`, `kafka/Patience.java:40-42,69-110`,
`kafka/KafScaleClients.java:31,46`, `vector/VectorProcessorApp.java:90-101`, `vector-tests/VectorStreamTest.java:95-114`,
`vector-tests/VectorProcessorTest.java:334`.

### 3.3 Deploy and stop

Deploying and stopping write a record to the control topic. There are three ways to do it:

| Way | Who | How |
|---|---|---|
| Workbench, tab "Smart Topic", buttons Deploy and Stop | Signed-in administrators. Every signed-in user when the SaaS backend has `DAG_RUN_ROLE=user`. | The SaaS backend checks the account at `POST /api/v1/dags/deploy` (body: the document as YAML) and `POST /api/v1/dags/stop?name=<dag>`. It forwards to the retrieval server with the run token of the installation and the e-mail address of the account in `X-OpenTSx-By`. |
| Retrieval server: `POST /v1/dags/deploy` (body: the document as YAML, at most 262144 bytes) and `POST /v1/dags/stop?name=<dag>` | Callers with the run token in the header `X-OpenTSx-Run-Token` when the server has a token (`--dag-run-token` or `OPENTSX_DAG_RUN_TOKEN`). Anyone who reaches the port when it has none. | The routes exist only with `--kafscale` and `--dag-runs true`; otherwise the answer is 404. A missing or wrong token gets 401. The server checks the document ([1.2](#12-checks-before-a-deployment)) and writes the record. |
| `opentsx vectors deploy --kafscale <proxy> --domain <domain> --dag <file> [--by <who>]` and `opentsx vectors stop --kafscale <proxy> --domain <domain> --name <dag> [--by <who>]` | Whoever reaches the KafScale proxy. | The command checks the document and writes the record. |

`<proxy>` is the bootstrap address of the KafScale proxy. `<domain>` defaults to `wind`. `<file>` is the DAG
document. The processor itself runs as `opentsx vectors --kafscale <proxy> --domain <domain> --state <dir>`, and the
state directory `<dir>` defaults to `vector-state`. Exactly one processor runs per domain ([3.5](#35-limits)).

`by` in the control record is the value of `X-OpenTSx-By` (default `unknown`) or of `--by` (default `cli`). It names
the caller. It does not prove who the caller is.

Implemented in: `opentsx-saas-backend/app/api/dags.py:32-113`, `opentsx-saas-backend/app/core/config.py:59`,
`server/RetrievalServer.java:100-102,186-198,242-274,580-629`, `server/KafScaleFeed.java:248-291`,
`vector/VectorProcessorApp.java:31-76`, `workbench/DeploymentsPanel.tsx:140-170`.

### 3.4 Read the state

`GET /v1/dags` of the retrieval server needs no token. It answers:

| Field | Content |
|---|---|
| `now` | Server time, to judge how old a report is. |
| `writes` | Whether the write routes of [3.3](#33-deploy-and-stop) exist. |
| `dags[]` | Per DAG name: `dag`, `desired` (`deployed` or `stopped`), `by`, `at`, `sinks[]` (the latest status record per sink) and `refused` (the error of a refusal). |
| `jobs[]` | The latest `opentsx.dag-status.v1` record per key among the records that carry `job`: the status of Track A and Track B jobs, as written, with or without a control record of that name. The vector processor never writes `job`; its records appear under `dags[]`. |

Under a stopped DAG every sink report is shown with `state` `stopped`. Under a deployed DAG a report older than the
latest control record is left out until the processor reports for the new deployment. The workbench shows
"No vector processor has reported for two minutes. Is one running for this domain?" when neither the deployment nor
any sink report is younger than 120 s.

Implemented in: `server/RetrievalServer.java:178-185`, `server/KafScaleFeed.java:294-313,321-323,339-378`,
`workbench/deployments.ts:55-80`, `workbench/DeploymentsPanel.tsx:177`.

### 3.5 Limits

* Exactly one processor per domain. Every runner assigns all partitions of its source topic itself. There is no lease
  and no consumer group. Two processors write every record twice. The k3s component runs one replica with the
  strategy `Recreate`.
* A series that stops sending holds up its vectors. There is no idle timeout. The `watermark` in the status stops
  moving.
* Vectors read the series of the source topic directly ([2.5](#25-what-the-processor-runs)).
* The state of a runner lives in memory and in its checkpoint. It grows with the number of open ticks: many series
  with a large offset between them hold many open ticks.

Implemented in: `vector/VectorProcessor.java:63,565-587`,
`deploy/k3s/components/vector-processor/vector-processor.yaml:1-14`, `vector/VectorStream.java:227-233,295-346`.

## 4. Stream operator catalog

The stream operator catalog is catalog 1,
[`systems/catalogs/tsx-catalog-1.yaml`](../../systems/catalogs/tsx-catalog-1.yaml), specified in S1.7 of
[20-WAYANG-TS-OPERATORS-SPEC.md](20-WAYANG-TS-OPERATORS-SPEC.md#s17-catalog-1). The web workbench holds its operator
ids in the list `CATALOG_1`, in the order of the file. A workbench operator is a catalog operator when catalog 1
lists its id or the `op` it writes into the DAG. These workbench operators are catalog operators:

| Workbench operator | `op` in the DAG | Parameters in the DAG |
|---|---|---|
| `dq.plausible-range` | `dq.checks` | `{ checks: [PLAUSIBLE_RANGE], min, max, mode }` |
| `convert.unit` | `convert.unit` | the parameters of the node |
| `resample` | `resample` | `{ every: PT<seconds>S, agg }` |
| `model.wind-shear` | `model.wind-shear` | the parameters of the node |
| `model.power-curve` | `model.power-curve` | the parameters of the node |
| `feature.lag` | `feature.lag` | the parameters of the node |
| `feature.rolling` | `feature.rolling` | `{ window: PT<seconds>S, fn }` when the node feeds a series sink with a `signal`, otherwise the parameters of the node |
| `stats.window` | `stats.welford-ddsketch` | `{ windows: [PT<seconds>S] }` |
| `align.vector` | `align.vector` | see [2.1](#21-parameters) |
| `segment.threshold` | `segment.threshold` | the parameters of the node |
| `pair.xcorr` | `pair.xcorr` | the parameters of the node; the node also gets `experimental: true` |
| `pair.es` | `pair.es` | the parameters of the node; the node also gets `experimental: true` |

The decoder `decode.avro-soe` stands for the source bucket in every DAG. No workbench operator writes the catalog
operators `segment.extremum`, `sax.word` and `dq.result`.

Every other workbench operator is written with the comment `# not in the stream operator catalog` after its `op`:
`shuffle`, `norm.max`, `norm.zscore`, `log`, `logreturn`, `diff`, `profile`, `fft`, `histogram`,
`histogram.crosscut`, `cdf`, `entropy.window`, `acf`, `dfa`, `pair.coverage`, `sigma.band`, `select` and
`smooth.movavg`. When a published node is or reads from such an operator, the workbench warns:
`<label> uses <operators>: not in the stream operator catalog, a runtime must provide them.`

A workbench test reads the catalog file and fails when `CATALOG_1` differs from its operator ids, or when the DAG
export or the warning marks an operator that catalog 1 lists or leaves out one that it does not list.

Catalog membership does not say which runtime runs an operator. The vector processor runs `align.vector` for vector
sinks ([2.5](#25-what-the-processor-runs)) and the operators of `CleanChain` for series sinks. Each operator declares
the runtimes that offer it under `runtimes` in `systems/processors/<id>.yaml`, for example
[align.vector](../../systems/processors/align.vector.yaml).

The shared test vectors of `align.vector` run in TypeScript, in Java as a whole and as a stream, on Wayang and in
Rust.

Implemented in: `workbench/ops.ts:834,838,857,864,871,877,884,893,896,905,911,919,935-946`,
`workbench/flow.ts:493-513,678-684,727-735`, `workbench/workbench.test.ts:386-400`,
`workbench/vector.test.ts:212-239`, `vector-tests/VectorAlignerTest.java:23`,
`vector-tests/VectorStreamTest.java:202`,
`opentsx-wayang/src/test/java/org/opentsx/wayang/TsxVectorVectorsTest.java:46`,
`rust/tsx-runner/tests/pair_vector_vectors.rs:40`.

## 5. Streaming contract

This is what the vector processor holds for its two kinds of sinks: vectors (`opentsx.vector.v1`) and clean series
(`opentsx.episode.v2` in `tsx.<domain>.clean.v2`).

| Point | Rule |
|---|---|
| Event time | Every episode carries the time it covers (`tStart`, `tEnd`), every measuring point its tick (`ts`, `window`). Ticks and resampling windows lie on a grid of multiples of their length since the epoch, not at the first value. |
| Arrival time | No record says when a raw value arrived. An episode carries `createdAt`, the time it was built. |
| Finality | A vector tick is final as in [3.2](#32-runners). A resampling window of a clean series is final when the data of its series has passed its end. Only final results are written. |
| Late data | An episode whose first sample lies before the end of the previous episode of its series is skipped and counted (`skippedBlocks`). It enters no result. No revision is written. |
| Identity | A clean episode has the id `c` followed by the hex xxHash64 of `<seriesId>\|<from>\|<to>\|<specHash>`. The same piece computed again has the same id, so a reader that keeps one episode per id counts it once. A vector record has no id; readers take the later record per key and tick. |
| Duplicates in the source | The same raw episode twice counts as late data and is skipped. Of two different episodes over the same period, a correction at the source, the second is skipped. |
| Restart | As in [3.2](#32-runners): what was sent after the last checkpoint is sent again and stands twice in the topic. |
| Refused fetch | When the broker refuses part of a fetch, the consumer goes back to its positions before the fetch ([3.2](#32-runners)). |
| Ownership | In `tsx.<domain>.clean.v2` one deployment writes a signal. One deployment writes a vector topic. The workbench checks this before it deploys. The retrieval server and the processor do not check it. |
| Replay | A new or changed DAG begins at the start of its source topic, or at its end with `source.start: latest`. Every output names the hash of its document. |
| New version | A changed document replaces the running one at once and starts over. The old output stays in the topic under the old hash. |
| Equality of preview and operation | The workbench (TypeScript) and Java pass the same shared test vectors for `align.vector` (`align-vector.v1.json`) and for `dq.checks` with `PLAUSIBLE_RANGE`, `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`, `feature.lag` and `feature.rolling` (`clean-ops.v1.json`). Java also checks that a stream cut into any pieces gives the same result. An operator MUST pass the shared test vectors in TypeScript and Java before the processor runs it. |
| Quality per value | The processor does not read or pass on the quality byte of an input episode. A plausible range turns a value outside [min, max] into a gap (`mode: gap`) or into the bound (`mode: clip`). |
| Kind of quantity | The processor aggregates every series as it gets it. It does not check whether a signal is analog, a status or a counter. |

Implemented in: `vector/VectorStream.java:189-198,295-312`, `clean/SeriesStream.java:31-35,81-120,146-156`,
`clean/CleanChain.java:24-25,375`, `vector/VectorJson.java:40-66`, `vector/VectorStoreBridge.java:26-34`,
`kafka/Patience.java:69-96`, `workbench/deployments.ts:129-158`, `vector/VectorProcessor.java:258-294`,
`workbench/vector.test.ts:212-239`, `workbench/clean.test.ts:22-57`,
`opentsx-retrieval/src/test/java/org/opentsx/retrieval/clean/CleanChainTest.java:91-126`,
`vector-tests/VectorStreamTest.java:202-258`, `opentsx-model/src/main/java/org/opentsx/model/Episodes.java:204`.
