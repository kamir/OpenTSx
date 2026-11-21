# System document, signal states and clean series

This page specifies the system document `opentsx.system.v1`, how the retrieval server computes the state of a
signal, and which topics and series generators and flows write for a signal. Verified against commit `a513aa98`.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

Terms:

| Term | Meaning |
|---|---|
| system | a plant: the atoms of a twin plan, their ports and the wires between them |
| signal | a quantity the system expects, whether data exists for it or not |
| grade | `raw` (as delivered or generated), `clean` (prepared by a flow) or `features` (model inputs a flow computes from `clean`) |
| source | where the data of one grade of a signal comes from: `topic`, `generator`, `flow` or `manual` |
| origin | how data came to be: `MEASURED`, `REPORTED`, `FORECAST`, `ESTIMATED`, `SYNTHETIC`, best first |
| domain | the topic namespace of a system: `tsx.<domain>.*` |
| working copy | the sources one user set in the web application, laid over the system document |
| DAG document | the `opentsx.dag.v1` or `opentsx.dag.v2` document the web workbench writes and the vector processor runs |

Code cited on this page:

| File | Directory |
|---|---|
| `SystemDoc.java`, `Systems.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/system/` |
| `SeriesJob.java`, `DagPlan.java`, `VectorProcessor.java`, `VectorProcessorApp.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/` |
| `SeriesStream.java`, `CleanChain.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/clean/` |
| `Generators.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/generate/` |
| `RetrievalServer.java`, `KafScaleFeed.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/` |
| `SystemDocTest.java`, `SystemsTest.java`, `OperatorLearningAvailabilityTest.java` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/system/` |
| `GeneratorsTest.java` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/generate/` |
| `DagRunEndpointTest.java` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/server/` |
| `TopicLayout.java` | `opentsx-kafka-v2/src/main/java/org/opentsx/kafka/` |
| `Origin.java` | `opentsx-twin/src/main/java/org/opentsx/twin/` |
| `system.py` | `opentsx-saas-backend/app/models/` |
| `systems.py`, `dags.py`, `wayang.py` | `opentsx-saas-backend/app/api/` |
| `main.py` | `opentsx-saas-backend/app/` |
| `test_systems.py`, `test_wayang_rust.py`, `test_dags.py` | `opentsx-saas-backend/tests/` |
| `signals.ts`, `SignalsPlace.tsx`, `SystemPlace.tsx` | `opentsx-saas-frontend/src/systems/` |
| `flow.ts`, `capabilities.ts`, `deployments.ts`, `types.ts`, `PublishClean.tsx`, `FlowComposer.tsx`, `DeviceTools.tsx`, `presets.ts`, `inventory.ts`, `parameters.ts` | `opentsx-saas-frontend/src/workbench/` |
| `Systems.tsx`, `Workbench.tsx` | `opentsx-saas-frontend/src/pages/` |
| `nginx.conf` | `opentsx-saas-frontend/` |

<a id="s1-signals"></a>

## 1. Signals

<a id="s11-signal-id"></a>

### 1.1 Signal id

A signal id is unique within its system. It has one of two forms:

| Form | Meaning | Example |
|---|---|---|
| `<atom>.<port>` | a port of the twin plan; the id is the port | `park.p_gen_kw` |
| `<atom>/<asset>/<channel>` | a sensor channel below an atom; `<asset>` MAY have more than one path segment | `park/T02/WTUR.W`, `park/DWD-01759/wind_speed` |

* A port id MUST match `[a-z0-9][a-z0-9_-]*\.[a-z0-9_]+`.
* A sensor channel id MUST match `[a-z0-9][a-z0-9_-]*(/[A-Za-z0-9_-]+)+/[A-Za-z0-9_.-]+`.
* An id that contains `/` is a sensor channel. Every other id is a port.
* The atom is the part of the id before the first `.` or `/`.
* `<system>/` followed by the id without its last segment is the asset id in the inventory. For `park/T02/WTUR.W` in
  the system `h2-chain` it is `h2-chain/park/T02`.
* One grade of a signal is written `<signal id>@<grade>`, for example `park/T02/WTUR.W@raw`.

Implemented in: `SystemDoc.java:38-39,74-81,377-379,544`.

<a id="s12-expectation"></a>

### 1.2 Expectation

The expectation says what the data of a signal should look like. A field that is not stated is null.

| Field | Type | Allowed values | Taken from the catalog channel |
|---|---|---|---|
| `unit` | string | any | `unit` |
| `min` | number | not above `max` | `min` |
| `max` | number | not below `min` | `max` |
| `interval` | string | ISO-8601 duration, for example `PT1M` | `sampling` |
| `kind` | string | `ANALOG`, `STATUS`, `COUNTER` | `kind` |
| `origin` | string | an origin | never |
| `operating.min` | number | not above `operating.max` | never |
| `operating.max` | number | not below `operating.min` | never |

* What the two ranges mean and how the server reports a value outside them:
  [DECISION-CARD-SPEC.md, section 4.3](DECISION-CARD-SPEC.md#43-plausible-range-and-operating-range).
* A signal with `channel` takes `unit`, `min`, `max`, `interval` and `kind` from the catalog channel. A field stated in
  `expect` overrides the catalog field.
* `interval` MUST match
  `P(\d+D(T(\d+H)?(\d+M)?(\d+(\.\d+)?S)?)?|T(\d+H(\d+M)?(\d+(\.\d+)?S)?|\d+M(\d+(\.\d+)?S)?|\d+(\.\d+)?S))`.

Implemented in: `SystemDoc.java:40-41,45-52,399-415,438-440`.

<a id="s13-grades-and-sources"></a>

### 1.3 Grades and sources

A signal has up to three grades. Each grade has at most one source.

| Grade | Allowed source kinds | Rule |
|---|---|---|
| `raw` | `topic`, `generator`, `manual` | a flow MUST NOT be the source of `raw` |
| `clean` | `topic`, `flow`, `manual` | a generator MUST NOT be the source of `clean` |
| `features` | `flow` | the source MUST be a flow, and the grade MUST name at least one feature |

| Source kind | Data | Origin of the source |
|---|---|---|
| `topic` | series in a topic, chosen by `select` | MUST be stated; `SYNTHETIC` for the output of a simulator |
| `generator` | a run of a demo generator, written to the synthetic topic ([section 4.2](#s42-generated-series)) | always `SYNTHETIC` |
| `flow` | the output of a flow listed in `flows` | none; the signal origin is derived ([section 1.4](#s14-origin-of-a-signal)) |
| `manual` | a constant `value` or a `table` reference; no series | as stated; `REPORTED` when not stated |

Implemented in: `SystemDoc.java:43,417-430,461-499`.

<a id="s14-origin-of-a-signal"></a>

### 1.4 Origin of a signal

The server computes the origin of a signal from its sources and, for a signal that a flow computes, from
`expect.origin`. The first rule that applies gives it:

1. The raw source has an origin: that origin.
2. The clean source is a flow: the worst of `expect.origin` and the origins of the signals the flow reads, the
   signal itself left out, each computed by these rules. A missing origin does not count; when none is present, the
   signal has no origin. A signal already on the path of the computation gives no origin.
3. Otherwise the signal has no origin, for example a signal without a source.

The order from best to worst is `MEASURED`, `REPORTED`, `FORECAST`, `ESTIMATED`, `SYNTHETIC`.

* `expect.origin` can make a computed signal worse than its inputs, never better. In `h2-chain`,
  `park.p_potential_kw` expects `ESTIMATED`, and a power curve computes it from the `MEASURED` wind of a station: it
  is `ESTIMATED`. `park.p_gen_kw` expects `MEASURED` and sums the active power of the turbines, which the document
  binds to the output of a simulator (`SYNTHETIC`): it is `SYNTHETIC`.
* A computed signal without `expect.origin` has the worst origin of the signals its flow reads.
* A `features` source gives no origin.

Implemented in: `Systems.java:399-420`, `Origin.java:7-28`; tests `SystemsTest.java:104-112,130-134,327-344`.

<a id="s2-system-document"></a>

## 2. System document

<a id="s21-load-the-documents"></a>

### 2.1 Load the documents

* A system document is a YAML file named `<name>.system.yaml`. The repository keeps them in `systems/`.
* The retrieval server reads every `*.system.yaml` in the directory given by `--systems`. Each `ref` in a document is
  relative to `--systems-root`, default `.`.
* A `ref` MUST resolve to a regular file below that root.
* A mistake in a document does not stop loading. Mistakes are collected as `problems`. Facts a person should know
  that do not make the document wrong are collected as `notes`.
* The build validates every `systems/*.system.yaml` against
  [`schemas/opentsx.system.v1.schema.json`](schemas/opentsx.system.v1.schema.json) and loads
  `systems/h2-chain.system.yaml` with no problems.

Implemented in: `Systems.java:57-70`, `SystemDoc.java:188-258,356-371`, `RetrievalServer.java:639-646`,
`SystemDocTest.java:58-68,345-347`.

<a id="s22-top-level-fields"></a>

### 2.2 Top-level fields

| Field | Type | Required | Rule |
|---|---|---|---|
| `schema` | string | yes | `opentsx.system.v1` |
| `system` | string | yes | `^[a-z0-9][a-z0-9-]*$`; the name of its twin plan where one exists |
| `version` | integer | yes | 1 or more |
| `domain` | string | yes | `^[a-z0-9][a-z0-9_-]*$`; the topics of the system are `tsx.<domain>.*` |
| `title` | string | no | default: the value of `system` |
| `description` | string | no | not read by the server |
| `example` | boolean | no | true when the values are invented; default false |
| `assumptions` | array of strings | no | what the document assumes about the plant, one sentence each |
| `plan` | `{ref}` | no | the twin plan with atoms, ports and wires |
| `inventory` | `{ref, system}` | no | the asset inventory; `system` defaults to the `system` of the document |
| `catalogs` | array of `{id, ref, version}` | no | channel catalogs; `version`, when stated, MUST equal the `version` of the catalog file |
| `signals` | array | yes | [section 2.3](#s23-signal-fields) |
| `flows` | array of `{id, ref}` | no | flow documents ([section 3](#s3-flow-documents)); `id` matches `^[a-z0-9][a-z0-9-]*$` |
| `models` | array of `{id, ref}` | no | accepted by the schema, not read by the server |
| `decisions` | array of `{id, ref}` | no | decision cards (`opentsx.decision.v1`); the `system` of each card MUST equal the `system` of the document |

Implemented in: `SystemDoc.java:121-128,221-242,262-333`.

<a id="s23-signal-fields"></a>

### 2.3 Signal fields

| Field | Type | Required | Rule |
|---|---|---|---|
| `id` | string | yes | [section 1.1](#s11-signal-id); unique in the document |
| `title` | string | no | default: the value of `id` |
| `channel` | string | `channel` or `expect` | `<catalog id>/<channel id>`; the channel MUST be in that catalog, and `id` MUST end with `/<channel id>` |
| `expect` | object | `channel` or `expect` | [section 1.2](#s12-expectation); at least one field |
| `role` | string | no | `observation`, `estimate`, `command`, `limit` or `exogenous` |
| `meaning` | string | no | one sentence that says what the quantity is |
| `raw` | `{source}` | no | [section 2.4](#s24-source-fields) |
| `clean` | `{source}` | no | [section 2.4](#s24-source-fields) |
| `features` | `{source, names}` | no | `source` is `{kind: flow, flow, output}`, `output` optional; `names` lists one or more unique feature names matching `^[a-z][a-z0-9_]*$`, each a node the flow writes |
| `note` | string | no | free text shown with the signal |

A signal with neither a `raw` nor a `clean` source is open. Open signals are allowed.

Implemented in: `SystemDoc.java:375-443`.

<a id="s24-source-fields"></a>

### 2.4 Source fields

| `kind` | Fields | Rule |
|---|---|---|
| `topic` | `topic`, `select`, `origin` | all three required; `topic` matches `^[A-Za-z0-9._-]+$`; `select` has one or more of `metric` (string), `tags` (map of strings), `series` (array of series ids) |
| `generator` | `processor`, `seed`, `params` | `processor` required: a generator of [section 4.2](#s42-generated-series); `seed` integer, default 0; `params` object with the parameters of that generator; `select` MUST NOT be present |
| `flow` | `flow`, `output` | `flow` MUST be an `id` in `flows`; `output` names the node whose result is this grade |
| `manual` | `value`, `table`, `origin` | exactly one of `value` (number) and `table` (reference to a table of time and value) |

Implemented in: `SystemDoc.java:445-505`, `Systems.java:139-140,161-164`.

<a id="s25-checks"></a>

### 2.5 Checks

The server checks what the schema cannot. Each failed check adds one problem, unless it says note.

1. `schema` is `opentsx.system.v1`. `system` and `domain` are not blank. `version` is 1 or more.
2. Every `ref` exists below the root. The plan can be read.
3. A pinned catalog `version` equals the `version` of the catalog file.
4. The inventory has assets for `inventory.system`.
5. The `flow` of each flow document equals its `id` in `flows`. Every `writes[].from` is a node id of that flow.
6. The `system` of each decision card equals the `system` of the document.
7. Every signal id has a form of [section 1.1](#s11-signal-id) and is unique.
8. A signal states `channel` or `expect`. The channel exists in a catalog of the document, and the signal id ends with
   its channel id.
9. `role`, `expect.kind`, `expect.interval` and every origin have allowed values. `min` is not above `max`.
   `operating.min` is not above `operating.max`.
10. Sources follow [section 1.3](#s13-grades-and-sources) and [section 2.4](#s24-source-fields). A generator source
    names a generator of [section 4.2](#s42-generated-series), its `params` meet the parameter rules of that generator,
    and it has no `select`. A signal whose raw source is a generator states `expect.interval` within the limits of
    [section 4.2](#s42-generated-series).
11. With a plan, the atom of every signal exists in the plan. A port signal names an input or output of its atom.
    When its expectation states a unit, that unit MUST equal the unit of the port.
12. A sensor channel without `channel` MUST state `unit` and `kind` in `expect`.
13. A sensor channel with `channel` MUST have its asset in the inventory. When the asset states an `atom`, it MUST
    equal the atom of the signal.
14. The system document is where the binding of a signal to its series lives. When the inventory lists `series` for
    an asset, a raw topic source SHOULD match one of them by `metric` and `tags`. A mismatch is a note.
15. A flow that is the source of a grade MUST write `<signal id>@<grade>`. For `clean` it MUST write it from `output`
    when `output` is stated. For `features` it MUST write it from every name in `names`.
16. Every grade that such a flow reads MUST be `raw`, `clean` or `features` of a signal of the document with a
    declared source for that grade.
17. A flow that is the source of `features` MUST read `clean` grades only. A flow that is the source of `clean` MUST NOT
    read the `raw` grade of another signal. Such a flow MUST NOT read the grade it writes.

The server does not check that `select` matches exactly the series of the signal or that several flows together form
no cycle. It checks a generator source with the rules of the generator it runs, not against a processor description.

Implemented in: `SystemDoc.java:200-258,262-371,375-601`, `Generators.java:57-63,79-85,105-127,137-159`; tests
`SystemDocTest.java:393-411,484-518`.

<a id="s26-hash"></a>

### 2.6 Hash

* The hash of a system is the lower-case hex xxHash64, seed 0, 16 characters, of a UTF-8 text. The text is the text of
  the document, followed for every referenced file by a newline, the `ref`, a newline and the text of the file. The
  files come in this order: plan, catalogs, inventory, flows, decisions.
* A change to the document or to a referenced file changes the hash.
* A working copy ([section 7](#s7-working-copy)) does not change the hash.
* Generated episodes carry the hash as label `systemHash` ([section 4.2](#s42-generated-series)).

Implemented in: `SystemDoc.java:183-186,220-233,369`; test `SystemDocTest.java:474`.

<a id="s27-example"></a>

### 2.7 Example

Four signals of `systems/h2-chain.system.yaml`: a sensor channel with raw data in a topic and a clean flow, a port
bound to a demo generator, a port set by hand, and an open port.

```yaml
schema: opentsx.system.v1
system: h2-chain
version: 5
example: true
domain: wind
plan: { ref: opentsx-twin/src/main/resources/twin/h2-chain.yaml }
inventory: { ref: opentsx-twin/src/main/resources/twin/inventar-beispiel.yaml, system: h2-chain }
catalogs:
- { id: wind, ref: opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml, version: 1.0.0 }
signals:
- id: park/T02/WTUR.W
  role: observation
  channel: wind/WTUR.W
  expect: { operating: { min: 0, max: 3300 } }
  raw:
    source:
      kind: topic
      topic: tsx.wind.episodes.v2
      origin: SYNTHETIC
      select: { metric: wind.turbine.power_active, tags: { turbine: T02 } }
  clean:
    source: { kind: flow, flow: turbine-power-clean, output: clean }
- id: offtake.demand_kgph
  role: exogenous
  expect: { unit: kg/h, min: 0, max: 120, interval: PT1M, kind: ANALOG, origin: REPORTED }
  raw:
    source: { kind: generator, processor: gen.sine, seed: 1, params: { mean: 60, amplitude: 30, period: PT6H } }
- id: ely.setpoint_kw
  role: command
  expect: { unit: kW, min: 0, max: 5000, interval: PT1M, kind: ANALOG, origin: REPORTED }
  raw:
    source: { kind: manual, value: 5000, origin: REPORTED }
- id: grid.p_grid_meas_kw
  role: observation
  expect: { unit: kW, min: -6000, max: 12000, interval: PT1M, kind: ANALOG, origin: MEASURED }
flows:
- { id: turbine-power-clean, ref: systems/flows/turbine-power-clean.flow.yaml }
```

<a id="s3-flow-documents"></a>

## 3. Flow documents

A flow document (`opentsx.flow.v1`) is the form of a flow a person writes. It names signal grades, not series ids or
topics. The system document lists flow documents in `flows`.

| Field | Type | Rule |
|---|---|---|
| `schema` | string | `opentsx.flow.v1` |
| `flow` | string | MUST equal the `id` under which the system document lists the flow |
| `reads` | array of strings | the grades the flow reads, `<signal id>@<grade>` |
| `nodes` | array of `{id, op, in, params}` | the steps; `source` in `in` stands for what the flow reads |
| `writes` | array of `{from, signal}` | `from` MUST be a node id; `signal` is `<signal id>@<grade>` |

* The server reads `flow`, `reads`, `nodes[].id` and `writes`. It uses them for the checks of
  [section 2.5](#s25-checks) and for `usedBy` ([section 6.2](#s62-status-document)). It does not read `op` or
  `params`.
* `opentsx.flow.v1` has no JSON schema. No code translates a flow document into a DAG document. The vector processor
  runs a flow only as the DAG document the web workbench writes ([section 5](#s5-series-sink-in-a-dag),
  [section 8.4](#s84-publish-as-clean-signal)).

Implemented in: `SystemDoc.java:103-105,335-354,568-601`, `Systems.java:286-291`.

<a id="s4-topics-and-series"></a>

## 4. Topics and series of a signal

<a id="s41-topics"></a>

### 4.1 Topics

| Topic | Holds | Written by |
|---|---|---|
| `tsx.<domain>.episodes.v2` | raw series | producers of measured or simulated data |
| `tsx.<domain>.synthetic.v2` | raw series of demo generators | the retrieval server, on `POST /v1/systems/<id>/generate` |
| `tsx.<domain>.clean.v2` | clean series | the vector processor, series sinks with grade `clean` |
| `tsx.<domain>.features.v2` | feature series, one series per feature | the vector processor, series sinks with grade `features` |

* All four topics hold v2 episodes (`opentsx.episode.v2`). A domain has one topic of each kind, not one per signal.
* A topic source names its topic itself. A manual source has no topic; its value stands in the document.
* A generator MUST NOT write to any topic other than the synthetic topic of its domain.
* Topic names come from `TopicLayout`. A missing synthetic, clean or features topic is created when it is first
  written: one partition, `compression.type=zstd`, `cleanup.policy=delete`.
* With `--kafscale`, the retrieval server follows the episodes topic, the clean topic, the features topic and the
  synthetic topic of its domain.

Implemented in: `TopicLayout.java:42-44,165-181,189-206`, `KafScaleFeed.java:128-134,172-175,400-412`,
`VectorProcessorApp.java:85-89`.

<a id="s42-generated-series"></a>

### 4.2 Generated series

A run of a demo generator writes one series:

* Metric: `<system>.<signal id>` with every character outside `[A-Za-z0-9._-]` replaced by `_`. A generator source
  has no `select` ([section 2.4](#s24-source-fields)), so the metric cannot be chosen.
* Tags: `system`, `signal`, `grade` with the value `raw`, and `run`.
* `run`: `gen-` followed by the first 12 hex characters of the xxHash64 of
  `<signal id>|<processor>|<seed>|<params as JSON>|<from>|<to>`, times in epoch microseconds.
* Unit: `expect.unit`.
* Episodes: regular at `expect.interval`, cut on a grid of 600 samples.
* Episode id: `g` followed by the hex xxHash64 of `<seriesId>|<episode start>|<episode end>`.
* Provenance: source `opentsx-generator`, pipeline the processor id.
* Labels: `grade` (`raw`), `origin` (`SYNTHETIC`), `generator`, `seed`, `fidelity` (`demo`), `systemHash`,
  `generatedAt`.

Rules:

* The same signal, generator, seed, parameters and period give the same series and the same episode ids. A repeated
  run replaces itself for a reader. Another period or other parameters give another series.
* Values are clamped to `expect.min` and `expect.max`.
* The signal MUST state `expect.interval`, at least one microsecond and at most `PT2562047H47M16.854775807S`
  (about 292 years). The period MUST end after it begins. A run MUST NOT exceed 2,000,000 samples.
* The available generators are `gen.ou` and `gen.sine`. Their parameters follow the table below. A required parameter
  MUST be a number. A broken rule is a problem of the document ([section 2.5](#s25-checks)).

| Generator | Required parameters | Optional parameters | Rule |
|---|---|---|---|
| `gen.ou` | `mean`, `sigma` | `reversion`, default 0.1 | `sigma` not below 0; `reversion` above 0 and at most 1 |
| `gen.sine` | `mean`, `amplitude` | `period` (ISO-8601 duration), default `P1D`; `phase` (share of the period), default 0; `sigma`, default 0 | `period` above zero and at most `PT2562047H47M16.854775807S` (about 292 years); `sigma` not below 0 |

Implemented in: `Systems.java:119-153,161-164`, `Generators.java:36-42,57-71,79-159,176-216`; tests
`SystemsTest.java:461-477`, `SystemDocTest.java:508-518`, `GeneratorsTest.java:117-118`.

<a id="s43-clean-and-feature-series"></a>

### 4.3 Clean and feature series

A series sink ([section 5](#s5-series-sink-in-a-dag)) writes one output series for each raw series it reads.

* Metric: `signal.metric` of the sink when it has one, else the metric of the raw series.
* Tags: every tag of the raw series when the sink has no `signal.metric`. Then `system`, `signal`, `grade` (`clean` or
  `features`) and `flow`, the name of the DAG document. A feature series also has `feature` with the feature name;
  no other output series has a `feature` tag.
* Two flows that write the same signal therefore write two series.
* Unit: the unit of the last step in the chain that sets one, else the unit of the raw series. A `convert.unit` step
  with a non-empty `unit` sets that unit; a `model.power-curve` step sets `kW`.
* Episode id: `c` followed by the hex xxHash64 of `<seriesId>|<from>|<to>|<specHash>`. The same piece of the same
  series computed by the same DAG document has the same id, so a reader that sees it twice keeps one.
* Provenance: source `opentsx-processor`, pipeline the DAG name.
* Labels: `grade`, `flow`, `specHash` (lower-case hex SHA-256 of the UTF-8 text of the DAG document), `origin` when the
  sink states one, `madeOf` when the sink states one.

Implemented in: `SeriesStream.java:42-54,125-164`, `CleanChain.java:213-225`, `VectorProcessor.java:261,797-803`.

<a id="s44-find-the-series-of-a-signal"></a>

### 4.4 Find the series of a signal

The status of a signal ([section 6](#s6-signal-state)) reports for each grade the series the TS inventory holds for it.

* A manual source has no series.
* A topic source matches inventory series that have no `signal` tag. With `select.series`, a series matches when its id
  is listed. Otherwise it matches when its metric equals `select.metric` (if stated) and its tags hold every entry of
  `select.tags`. The inventory MUST have been read from the topic of the source. An inventory without topics, such as
  one read from a store, counts as reading every topic.
* A generator source, a flow source, and a `clean` or `features` grade without a declared source match series whose
  tags `system` and `signal` equal the system and the signal id, whose `grade` tag equals the grade, and that have a
  `feature` tag exactly when the grade is `features`. A series without a `grade` tag matches when its episodes carry
  the grade as label `grade` or carry no `grade` label.
* A grade without a declared source that has series found this way is reported with the source
  `{kind: flow, declared: false, flow}`. A clean signal published from the workbench shows this way without a change
  to the system document.
* Several flows: the server knows the DAGs whose desired state is `deployed`. When the series found include series of
  such a DAG (tag `flow`) and series of another, only the series of deployed DAGs count. The others are listed under
  `others`.
* Several generator runs: when the series found carry more than one `run`, only the run with the newest label
  `generatedAt` counts. The others are listed under `others`.

Implemented in: `Systems.java:231-247,479-606`, `RetrievalServer.java:142-153`.

<a id="s5-series-sink-in-a-dag"></a>

## 5. Series sink in a DAG

<a id="s51-sink-fields"></a>

### 5.1 Sink fields

A series sink writes the clean or the features grade of a signal. The web workbench writes a DAG document with a series
sink as `opentsx.dag.v2` (`schema: opentsx.dag.v2`). The vector processor chooses the kind of each sink by the `schema`
of the sink, not by the `schema` of the document.

```yaml
sinks:
- { from: n2, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2,
    signal: { system: h2-chain, id: park/T02/WTUR.W, grade: clean }, origin: SYNTHETIC }
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `from` | string | yes | the node whose output is written; for grade `features` it is the feature name and MUST match `^[a-z][a-z0-9_]*$` |
| `topic` | string | yes | the clean topic of the domain for grade `clean`, the features topic for grade `features` |
| `schema` | string | yes | `opentsx.episode.v2` |
| `signal.system` | string | yes | the system id |
| `signal.id` | string | yes | the signal id |
| `signal.grade` | string | no | `clean` (default) or `features` |
| `signal.metric` | string | no | the metric of the written series, for a sink that writes another signal than the one it reads |
| `origin` | string | no | the `origin` label of the written episodes |
| `madeOf` | string | no | the `madeOf` label: what the series was computed from, for example `park/T02/WTUR.W@clean (SYNTHETIC)` |

The source of a DAG document with a series sink:

| Field | Rule |
|---|---|
| `source.kind` | `kafscale-broker` as the workbench writes it; the vector processor does not read it |
| `source.topics` | exactly one topic: the episodes, synthetic or clean topic of the domain; for grade `features` the clean topic |
| `source.series` | one or more series ids; only these series are read |
| `source.start` | `earliest` (default): begin at the start of the topic; `latest`: begin with what arrives from now on |

Implemented in: `SeriesJob.java:13-141`, `flow.ts:706-763`.

<a id="s52-validation"></a>

### 5.2 Validation

A DAG document with a series sink first passes the document checks of
[VECTOR-AND-DAG-SPEC.md, section 1.2](VECTOR-AND-DAG-SPEC.md#12-checks-before-a-deployment). The name of a series sink,
which those checks keep unique, comes from its signal id and, for grade `features`, the feature name. The vector
processor then refuses the document, with a message, when:

1. A series sink breaks a rule of [section 5.1](#s51-sink-fields).
2. A node between the decoder and a series sink has more than one input. A clean series is one chain from the source.
3. A series sink reads the decoder directly. There is nothing to clean.
4. A node of the chain is not an operator of [section 5.3](#s53-operators-before-a-series-sink), or a parameter is
   invalid.

Implemented in: `DagPlan.java:17-131`, `SeriesJob.java:58-141`, `CleanChain.java:112-200`.

<a id="s53-operators-before-a-series-sink"></a>

### 5.3 Operators before a series sink

The vector processor runs these operators between the decoder and a series sink. The feature operators produce the
`features` grade. Their semantics are in `CleanChain.java` and in the processor descriptions in `systems/processors/`.

| DAG `op` | Processor description | Parameters |
|---|---|---|
| `dq.checks` | `dq.plausible-range` | `checks`: `PLAUSIBLE_RANGE` only; `min` and `max`, default unbounded, `min` not above `max`; `mode`: `gap` (default) or `clip` |
| `convert.unit` | `convert.unit` | `factor`, default 1; `offset`, default 0; `unit`; the value becomes `value * factor + offset` |
| `resample` | `resample` | `every`: ISO-8601 duration or seconds, above 0; `agg`: `MEAN` (default), `MIN`, `MAX`, `LAST` |
| `model.wind-shear` | `model.wind-shear` | `fromM` and `toM` above 0; `alpha` from 0 to 0.6 |
| `model.power-curve` | `model.power-curve` | `ratedKw` above 0; `cutIn` 0 or more; `ratedWind` above `cutIn`; `cutOut` not below `ratedWind`; `count` above 0, default 1 |
| `feature.lag` | `feature.lag` | `steps` from 1 to 100,000 |
| `feature.rolling` | `feature.rolling` | `window`: ISO-8601 duration or seconds, above 0; `fn`: `MEAN` (default), `STD`, `MIN`, `MAX` |

The web workbench offers the same seven operators for a series sink under their processor description ids.

Implemented in: `CleanChain.java:35-43,84,112-200`, `capabilities.ts:3`.

<a id="s54-processing"></a>

### 5.4 Processing

* Each raw series runs through its own copy of the chain state.
* A series not in `source.series` is ignored. An episode without samples is ignored.
* Series MUST arrive in time order. An episode that begins before the end of the previous episode of its series is
  skipped and counted as `skippedBlocks`.
* Only final output is written. Without a `resample` step, each raw episode gives one output episode that ends where
  the raw episode ends; it is regular when the raw episode is regular. With a `resample` step, the output is regular at
  `every`, one episode for each run of windows without a break. A window is written when the data has passed its end.
* The open windows and the samples a lag or a rolling window still needs are the state of the sink. The vector
  processor saves and restores it with its checkpoints. Episodes written again after a restart have the same episode
  ids ([section 4.3](#s43-clean-and-feature-series)).

Implemented in: `SeriesStream.java:68-123,180-203`, `VectorProcessor.java:418-470`.

<a id="s6-signal-state"></a>

## 6. Signal state

<a id="s61-endpoints"></a>

### 6.1 Endpoints

The retrieval server computes the state on every request from the system document, the TS inventory and the deployed
DAGs. It stores no state.

| Route | Body | Answer |
|---|---|---|
| `GET /v1/systems` | none | `systems`: per system `system`, `title`, `version`, `domain`, `example`, `hash`, `counts`, `states`, and `problems` as a number |
| `GET /v1/systems/<id>/signals` | none | the status document ([section 6.2](#s62-status-document)) |
| `POST /v1/systems/<id>/signals` | `{bindings: [{signal, grade, source}]}` | the status document with a working copy laid over the system document; reads only |
| `GET /v1/systems/<id>/plan` | none | `opentsx.system-plan.v1`: the atoms of the plan and what feeds each input |
| `POST /v1/systems/<id>/plan` | `{bindings}` | the plan with a working copy laid over the system document |
| `POST /v1/systems/<id>/generate` | `{signal, from, to}`, optional `bindings` | [section 7.3](#s73-generate-a-series) |

* Without `--systems` every route answers 404.
* A POST body larger than 262,144 bytes is refused with 413.
* The [REST appendix of the user manual](../user-manual/appendix-b-rest.md) lists every route of the server.

Implemented in: `RetrievalServer.java:178-181,254,421-443,475-515`, `Systems.java:80-93`.

<a id="s62-status-document"></a>

### 6.2 Status document

`opentsx.signal-status.v1`, top level:

| Field | Meaning |
|---|---|
| `schema` | `opentsx.signal-status.v1` |
| `system`, `title`, `version`, `domain`, `example` | from the system document |
| `hash` | [section 2.6](#s26-hash) |
| `generatedAt` | the time of the answer |
| `problems`, `notes` | [section 2.5](#s25-checks) |
| `counts` | `signals`, the number of signals, and the number of signals for each `via` |
| `states` | the number of signals for each `state` |
| `signals` | one entry per signal, in the order of the document |

One entry of `signals`:

| Field | Meaning |
|---|---|
| `id`, `atom` | [section 1.1](#s11-signal-id) |
| `group` | for a sensor channel, the asset path between atom and channel; empty for a port |
| `name` | the channel or the port |
| `port` | true for a port |
| `title`, `channel`, `role`, `meaning`, `note` | from the document |
| `expect` | the expectation after the catalog is applied ([section 1.2](#s12-expectation)) |
| `grades.<grade>.source` | the source; with `workingCopy: true` when it comes from a working copy; with `declared: false` for a grade found by tags only |
| `grades.features.names` | the feature names |
| `grades.<grade>.data` | what the inventory holds ([section 4.4](#s44-find-the-series-of-a-signal)): `series`, `samples`, `missing`, `from`, `to`, `min`, `max`, `intervalMicros`, the labels `flow`, `origin`, `madeOf` and `fidelity` of the first series, and `others` |
| `via` | the kind of the raw source, else of the clean source: `topic`, `simulated` (generator), `derived` (flow), `manual`; `open` without both |
| `origin` | [section 1.4](#s14-origin-of-a-signal) |
| `state` | [section 6.3](#s63-state) |
| `outOfRange` | `{min, max}` as observed, present when the observed range of the clean data, else of the raw data, goes below `expect.min` or above `expect.max` |
| `next` | [section 6.4](#s64-next-steps) |
| `usedBy` | the ids of the flows that read any grade of the signal |

Implemented in: `Systems.java:177-303,386-397,446-450`.

<a id="s63-state"></a>

### 6.3 State

A signal has exactly one state: the first row that applies.

| State | Condition |
|---|---|
| `clean` | the inventory holds data for the clean grade |
| `open` | the signal has no raw and no clean source |
| `clean-not-running` | a clean source is declared, but no clean data is found |
| `constant` | the raw source is manual |
| `raw-only` | the inventory holds data for the raw grade |
| `bound-no-data` | a raw source is declared, but no raw data is found |

* The state describes the way to `clean`. Feature sources and feature data do not change it.
* `clean` does not check that a flow runs or that the data is recent.
* A range violation is not a state. It is reported as `outOfRange` next to the state.

Implemented in: `Systems.java:268-270,430-444`; tests `SystemsTest.java:72-142,211-218`.

<a id="s64-next-steps"></a>

### 6.4 Next steps

| State | `next` |
|---|---|
| `open` | `bind`, `simulate` |
| `bound-no-data` | `generate` when the raw source is a generator, else `check-source` |
| `raw-only` | `prepare` |
| `clean-not-running` | `start` |
| `clean`, `constant` | none |

Implemented in: `Systems.java:277-285`.

<a id="s7-working-copy"></a>

## 7. Working copy

<a id="s71-bindings"></a>

### 7.1 Bindings

The backend keeps, for each user and system, the sources the user set in the web application. A binding is the source
of one grade of one signal. The routes need a signed-in user.

| Route | Effect |
|---|---|
| `GET /api/v1/systems/<system>/bindings` | the bindings of the user |
| `PUT /api/v1/systems/<system>/bindings` | sets one binding; a binding of the same signal and grade is replaced |
| `DELETE /api/v1/systems/<system>/bindings?signal=<id>&grade=<grade>` | removes one binding; 404 when there is none |
| `POST /api/v1/systems/<system>/generate` | [section 7.3](#s73-generate-a-series) |

| Field | Rule |
|---|---|
| system | `^[a-z0-9][a-z0-9-]*$` |
| `signal` | `^[a-z0-9][a-z0-9_-]*(\.[a-z0-9_]+\|(/[A-Za-z0-9_.-]+)+)$` |
| `grade` | `raw` (default) or `clean` |
| `source.kind` | `topic`, `generator` or `manual`; a flow becomes a source only by publishing it ([section 8.4](#s84-publish-as-clean-signal)) |
| topic source | `topic`, `select` with `metric`, `tags` or `series`, and `origin` are required; `meaningConfirmed` MUST be true: the person confirms that the series is the quantity the signal means |
| generator source | `processor` MUST be `gen.ou` or `gen.sine`; `params`, when given, an object; `seed`, when given, an integer; `select` MUST NOT be present |
| manual source | `value` a number; `origin` an origin, default `REPORTED` |
| `baseHash` | the hash of the system document the binding was made against; at most 64 characters; stored, not compared |

A refused binding answers 422 with the reason. The backend does not check the parameters of a generator. The retrieval
server checks them when it lays the working copy over the document, and a broken rule is a problem of the document
([section 2.5](#s25-checks)).

Implemented in: `system.py:10-31`, `systems.py:29-46,132-202`.

<a id="s72-overlay-and-export"></a>

### 7.2 Overlay and export

* The web application sends the bindings of the signed-in user in the body of `POST /v1/systems/<id>/signals`,
  `/plan` and `/decisions`. The retrieval server loads the document again with each binding as
  `signals[].<grade>.source` and marks the source `workingCopy: true`.
* A binding for a signal the document does not have, or for a grade other than `raw` or `clean`, is a problem of the
  answer.
* The web application never changes the system document in the repository. "Copy as YAML" on the page Signals gives
  the lines a person puts into the file.

Implemented in: `SystemDoc.java:196-217`, `Systems.java:95-110`, `Systems.tsx:73-107`, `signals.ts:632-639`.

<a id="s73-generate-a-series"></a>

### 7.3 Generate a series

`POST /api/v1/systems/<system>/generate` with `{signal, from, to}` runs the demo generator a signal is bound to.

* The backend admits administrators, or every signed-in user when the setting `DAG_RUN_ROLE` is `user`; it answers 403
  to any other user, and 503 when `RETRIEVAL_URL` is not set. It forwards the working copy of the user, and the run
  token `DAG_RUN_TOKEN` when it is set, to the retrieval server. The same setting admits DAG deployments and stops and
  Wayang runs: `DAG_RUN_ROLE=user` opens all three to every signed-in user. With `user`, the backend prints a line at
  startup that names the three and says that sign-up is open.
* The retrieval server answers 404 unless it runs with `--kafscale` and `--dag-runs true`. With `--dag-runs true` it
  MUST have a run token (`--dag-run-token` or `OPENTSX_DAG_RUN_TOKEN`); without one, or with a blank one, it does not
  start. It answers 401 when the header `X-OpenTSx-Run-Token` does not carry its run token.
* The frontend image answers 403 to `/retrieval/v1/systems/<system>/generate` and to every path below it, so a caller
  of the frontend image reaches the generator only through the backend.
* The document, with the working copy laid over it, MUST have no problems ([section 2.5](#s25-checks)). A generator
  the server does not run, a missing or invalid parameter, a `select` and a missing `expect.interval` or one outside
  the limits of [section 4.2](#s42-generated-series) are such problems. The signal MUST exist and its raw source MUST
  be a generator. `from` and `to` are instants, for example `2026-10-01T00:00:00Z`.
* The series goes to the synthetic topic of the domain ([section 4.2](#s42-generated-series)). The answer holds
  `system`, `signal`, `topic`, `generator`, `fidelity` (`demo`), `episodes`, `samples`, `from`, `to` and `run`.

Implemented in: `systems.py:205-222`, `dags.py:32-55`, `wayang.py:40-48`, `main.py:58-60`,
`RetrievalServer.java:123-126,247-251,503-512,520-527,589-591`, `Systems.java:112-153`, `nginx.conf:20-25`; tests
`DagRunEndpointTest.java:188-197`, `SystemsTest.java:421-438`, `test_systems.py:165-170`, `test_wayang_rust.py:155-157`,
`test_dags.py:154-189`.

<a id="s8-web-application"></a>

## 8. Web application

<a id="s81-places-and-guide"></a>

### 8.1 Places and guide

`/systems` opens the first system on the place Signals. `/systems/<id>/<place>` opens a place.

| Place | `<place>` | Shows |
|---|---|---|
| System | `system` | the atoms of the plan as a graph and as a table; each input says what feeds it: a wired output, a signal with its state, or nothing |
| Signals | `signals` (default) | [section 8.2](#s82-signals-page) |
| Flows | `flows` | the flows of the account made for a signal of the system, and flows of its domain without a system; needs a signed-in user |
| Models | `models` | the decision cards of the system and what each analysis may claim |
| Simulate | `simulate` | questions to the twin of the system |
| Workbench | `/workbench` | the tool: the scope and the operators ([section 8.3](#s83-open-a-signal-in-the-workbench)) |

* The header shows the system and its number of signals for each `via`.
* The status bar shows "Example data." for an example system, the system id, the version, the first 8 characters of
  the hash and "Times in UTC.".
* "Guide" switches on the guided mode. It shows seven numbered steps, each with its state and a link to its place. It
  marks the first step with something to do as the next one. It enforces no order. The browser keeps the choice under
  the `localStorage` key `opentsx.guide`.
* A step that cannot be done before an earlier step is marked as later.

| Step | Place | Done when |
|---|---|---|
| 1 System | System | the plan is read and the system document has no problems |
| 2 Signals | Signals | the system has signals and every input from outside the plan has a signal |
| 3 Stock | Signals | no signal is `bound-no-data` |
| 4 Bind | Signals | no signal is `open` |
| 5 Prepare | Signals | at least one signal is neither `open` nor `constant`, and every such signal has a clean grade |
| 6 Run | Flows | at least one signal has a clean grade, and at least as many signals are `clean` as have a clean grade |
| 7 Model | Models | the decision cards have at least one analysis, and every analysis is usable |

Implemented in: `Systems.tsx:21-27,168-228`, `signals.ts:510-536`, `SystemPlace.tsx:1-5`, `FlowComposer.tsx:27-28`.

<a id="s82-signals-page"></a>

### 8.2 Signals page

* Rows are grouped by atom, then by asset. The ports of an atom come first.
* A row shows the signal, its expectation, its origin, its state and a button for each next step.
* The origin shows as `SIMULATED` in capitals for a signal bound to a generator, else as the origin in lower case, or
  `n/a`. An origin of `SYNTHETIC` or `ESTIMATED` is underlined with a dashed line, so generated or modelled data is not
  taken for a measurement.
* Bind, Simulate and Generate open a panel. Prepare opens the raw grade on the scope. Start opens the place Flows.
  `check-source` shows as a disabled button.
* Bind suggests inventory series without a `signal` or `vector` tag. The expected unit ranks a series higher than a
  value range inside `min` and `max`. The person confirms the meaning before the binding is saved.
* Simulate presets the generator parameters from the operating range of the expectation, else from `min` and `max`.
* Opening a grade on the scope while the workbench holds an unsaved flow asks first: "Open and discard the flow" or
  "Keep it".
* Filters: text, state, via, and "to do" (a next step or `outOfRange`).

Implemented in: `signals.ts:150-158,181-188,223-252,573-611`, `SignalsPlace.tsx:93-156,229`.

<a id="s83-open-a-signal-in-the-workbench"></a>

### 8.3 Open a signal in the workbench

`/workbench?system=<id>&signal=<id>&grade=<grade>` opens one grade of a signal. `grade` defaults to `raw`.

* The workbench reads the status with the working copy of the user. It refuses a signal that does not exist or has no
  data of that grade.
* It replaces the flow on the workbench with the series of that grade.
* The source topic is the clean topic for grade `clean`, the synthetic topic for a raw grade bound to a generator, else
  the topic the series were loaded from.
* A context line shows a link back to Signals, the system, signal and grade, the expectation, the origin with
  "generated data, not a measurement" for `SYNTHETIC` or `ESTIMATED`, and "unsaved changes" while the flow has steps
  and is not saved.
* The scope draws `expect.min` and `expect.max` while the data shown is still the quantity of the signal: the source is
  selected, or every series has the expected unit.
* `dq.plausible-range` is preset with `expect.min` and `expect.max`.
* `resample` is preset with `every` set to the larger of 60 seconds and `expect.interval`, and to 60 seconds when
  `expect.interval` is not stated. An `expect.interval` that is no duration of
  [section 1.2](#s12-expectation) leaves `every` empty. The inspector and the operation panel then refuse the
  operator with `every requires a finite number.` and add no step until a width is entered.
* The presets apply in every mode of the workbench: Signal Engineer and Data Worker preset a new operator in the
  inspector, Legacy presets the operation panel.
* The workbench MAY be used without a signal. It then has no context line.

Implemented in: `Workbench.tsx:170-173,179-187,233,697-744,828-833,950-974`, `presets.ts:9-32`,
`DeviceTools.tsx:310-315,373-377`, `parameters.ts:20-43`, `inventory.ts:218-225`, `types.ts:139-155`.

<a id="s84-publish-as-clean-signal"></a>

### 8.4 Publish as clean signal

The context line offers "Publish as clean signal" for grade `raw` and "Publish as another signal" for grade `clean`.
The button is enabled once a step other than the source is selected.

* "Publish as another signal" asks which signal of the system the step computes. The written series then has the
  metric `<system>.<signal id>`, characters outside `[A-Za-z0-9._-]` replaced by `_`, when the target is a port. Its
  origin is `ESTIMATED` when the chain holds a `model.*` step, else the origin of the signal read, else `ESTIMATED`.
  Its `madeOf` names the signal and grade it was computed from.
* The dialog lists what the processor needs: a signal context (grade `raw`, or a chosen target signal), series loaded
  from the page Signals or the TS inventory, one chain of at least one step from the source with one input per step,
  and only operators of [section 5.3](#s53-operators-before-a-series-sink). It also says whether the account may start
  flows.
* Publishing makes the selected step the only clean sink of the flow, writing to the clean topic of the domain, and
  saves the flow in the account. "Publish and start" also refuses when another running deployment already writes the
  clean data of the same signal, then deploys the DAG document and marks the flow active. "Save without starting"
  leaves the start to the place Flows.
* After publishing, the workbench opens the page Signals on the signal with a note that the flow was started or
  saved.
* The system document in the repository is not changed. The page Signals finds the clean series by its tags
  ([section 4.4](#s44-find-the-series-of-a-signal)).

Implemented in: `PublishClean.tsx:60-101`, `flow.ts:537-597,706-763`, `deployments.ts:115,134-143`,
`Workbench.tsx:963-985`.

<a id="s9-processor-descriptions"></a>

## 9. Processor descriptions

* A generator source names a processor by its id. The vector processor names an operator by its DAG `op`.
* Processor descriptions (`opentsx.processor.v1`) live in `systems/processors/<id>.yaml`. The build validates each file
  against [`schemas/opentsx.processor.v1.schema.json`](schemas/opentsx.processor.v1.schema.json) and checks that the
  file name equals the id.
* A description whose `dag.op` is an operator of [section 5.3](#s53-operators-before-a-series-sink) MUST state
  `runtimes.processor.status: available`.
* Identity, versions and the frozen catalog follow
  [S1.1 of the operator specification](20-WAYANG-TS-OPERATORS-SPEC.md#s11-identity).

Implemented in: `SystemDocTest.java:70-80`, `OperatorLearningAvailabilityTest.java:17-31`.
