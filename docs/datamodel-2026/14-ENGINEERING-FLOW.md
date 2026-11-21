# OpenTSx: Engineering flow from the system to the model (concept and draft)

> As of 2026-10-13, third version. **Approved on 2026-10-13** with two changes from the proposal
> (D43: three grades instead of two; D50: a guided mode in addition). Delivery stages L1 and L2 are implemented
> (section 10): system document with checks, state of the signals, the shell with its four places and the guided
> mode, jump to the scope, plus L3 with limits. **Several definitions of this document have been changed by
> [15](15-ENTSCHEIDUNGSKREIS.md)** (state of the signal, origin, meaning of `clean`); what applies is stated there.
> L4 and L5 are built as planned here, followed by the stages E1 to E3 from 15. Work package:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md); the findings of the two reviews are there too.

## 1. Summary

Today OpenTSx has two ends of a workflow and no middle. The **Smart Topics Studio** describes a system from above:
building blocks, ports, connections, events. The **workbench** works on series from below: load them, apply
operators, check them on the oscilloscope, deploy them as a Smart Topic. Missing in between is what makes up
engineering work: recording which measured quantities a system **expects**, which of them **exist**, which must be
**simulated**, and how raw data becomes **clean series** for models.

The proposal:

1. **Four places and one tool** instead of separate pages: System, Signals, Flows, Models; plus the Workbench
   (section 3).
2. The **signal** as the shared key: an expected measured quantity with raw data, clean data and features for
   models (section 4).
3. Two new declarative documents: `opentsx.system.v1` describes the whole system, `opentsx.processor.v1` a time
   series processor, whether generator, transformer or model (sections 5 and 7).
4. A **fixed rule for where the data of a signal lives**, and a translation step from the flow as a person writes it
   to the DAG as the processor reads it (section 6).
5. A **shared shell** in the web application (section 8).

Existing drafts stay valid and are included: the wiring plan of the twin (`twin/*.yaml`), the channel catalog, the
inventory (`opentsx.inventory.v1`, [12](12-EXPLORATIVES-UI-INVENTAR.md)), the DAG (`opentsx.dag.v1`,
[11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md)), the Smart Topic specification ([05](05-SMART-TOPICS.md)). Where they
must change, this is stated explicitly (sections 6.3 and 9).

## 2. Terms

One word per thing. The user interface and the documents stick to it.

| Term | Meaning | Not to be confused with |
|---|---|---|
| **System** | the plant: building blocks, ports, connections | **Scenario**: one case or run of the system (period, interventions) |
| **Signal** | a quantity the system expects, whether data exists or not | **Series**: the data of a signal; **Channel**: an entry in the channel catalog |
| **Grade** | `raw` (as delivered or generated), `clean` (cleaned, on the clock, in the unit of the system) or `features` (features for models, computed from `clean`) | view of the user interface, delivery stage of the implementation |
| **Origin** | `MEASURED`, `REPORTED`, `FORECAST`, `ESTIMATED`, `SYNTHETIC`, as `Origin` in the twin | kind of source (topic, generator, flow, by hand) |
| **Flow** | the recipe: processors and their connection | **running flow**: a started flow; **Smart Topic**: its published result |
| **Processor** | a building block of a flow, or a generator | vector processor: the runtime that executes flows |

## 3. Current state and target design

### 3.1 Building blocks and breaks

| Step of the engineering work | Exists | Break |
|---|---|---|
| Define the system | Studio, view "System diagram", from `twin/*.yaml` | static HTML page with example data, separate from the web application |
| Record the expected quantities | channel catalog (unit, value range, sampling, IEC reference); ports in the wiring plan | ports and channels are not related to each other |
| Know the existing data | TS inventory (`GET /v1/inventory`): series, period, value range, gaps, ground truth | no comparison "expected against existing" |
| Bind or simulate a measurement | the twin binds series per run to input ports (`SeriesInputs`); wind farm simulator; `opentsx-market`; sine mix of the workbench | nowhere is it declared which port comes from where; no generator writes continuously into a topic |
| Clean raw data | workbench: 23 operators, interactive | the processor only executes `align.vector` and only writes vectors; there is no topic for clean series |
| Model, train, optimize | twin (what-if, forecast); Smart Topic specification as a draft | no loop on clean series |
| Result back into the system | Studio "Series" and "Compare" | fed by the twin run, not by Smart Topics |

### 3.2 Four places and one tool

The first version had six numbered stages. The review showed: "Bind or simulate" is a column of the signal list,
"Operation" is the Flow Composer with a filter, and numbers claim an order that does not exist. Four places remain,
without numbers, each with a count that says what there is to do.

| Place | Question | Content | Builds on |
|---|---|---|---|
| **System** | What does the plant consist of, what depends on what? | wiring plan; a click on a port leads to its signal | Studio "System diagram" |
| **Signals** | Which measured quantities does the system expect, what is there, what is to be done next? | per signal: expectation, origin, state, next step; Bind, Simulate and Change expectation are actions here | channel catalog, TS inventory |
| **Flows** | What prepares the data, what is running? | the Flow Composer, filtered to the system; per flow the signal it serves | Flow Composer |
| **Models** | What does the system predict, what if? | models on clean signals; if one is missing, that is shown | Smart Topic specification, twin, Studio "Compare" |
| **Workbench** (tool) | How does this raw series become a usable one? | oscilloscope and operators, opened for a signal | Workbench |

The smooth path:

1. Choose the **System**. The wiring plan provides the ports.
2. **Signals** shows all signals with their state. The row says the next step.
3. An open signal: "Bind" proposes series from the TS inventory (same metric, matching unit). If there are none,
   "Simulate" prefills a generator from the expectation (unit, value range, sampling).
4. A signal with raw data: "Prepare" opens the workbench with these series. The scope shows the expected value
   range. "Publish as clean signal" makes the flow the source of the clean data.
5. **Flows** starts and stops what should run.
6. **Models** accepts only clean signals.

## 4. The signal

* **Id**, unique within the system:
  * `<block>.<port>` for a port of the wiring plan, for example `park.p_avail_kw`. The id **is** the port; there is
    no second field for it.
  * `<block>/<asset>/<channel>` for a sensor channel under a building block, for example `park/T02/WTUR.W`.
    `<system>/<block>/<asset>` is the asset id of the inventory, `<channel>` the channel id of the catalog.
  * In external references (addresses, tags) the system comes first: `h2-chain/park.p_avail_kw`.
* **Expectation:** unit, value range, sampling interval, kind (`ANALOG`, `STATUS`, `COUNTER` as in the catalog),
  expected origin. For a sensor channel it comes from the catalog (where the interval is called `sampling`) and can
  be overridden.
* **Grade:** `raw`, `clean` and `features`. A derived signal has no `raw`. `features` are features for models:
  lagged values, rolling statistics, derived quantities. They are always computed by a flow from `clean`, carry a
  name per feature and can be used by several models.
* **Source per grade:**

  | `kind` | Meaning | Origin |
  |---|---|---|
  | `topic` | series in a topic, chosen by metric and tags | stated at the source; any origin is possible, including `SYNTHETIC` (data of a simulator) |
  | `generator` | a generator processor with parameters and seed | always `SYNTHETIC` |
  | `flow` | output of a flow | the worst origin of the inputs |
  | `manual` | constant or table | stated at the source; default `REPORTED` (a schedule is not a simulation) |

* **State**, computed from the declaration, the TS inventory and the list of running flows, exactly one:

  | State | Meaning | Next step |
  |---|---|---|
  | `open` | no source | Bind, Simulate |
  | `bound, no data` | source there, no series found | check the source |
  | `raw only` | raw data there, nothing clean | Prepare |
  | `clean, not running` | flow declared, not running | Start |
  | `clean, running` | flow running, data current | n/a |
  | `stale` | last value older than expected | check the flow or the source |
  | `out of range` | values outside the expectation above a threshold | Prepare or check the expectation |
  | `constant` | set by hand | n/a |

  The state describes the path up to `clean`. Beside it there are two separate items: whether features are
  declared and running, and who uses the signal (flows, models, twin). Neither is progress of the signal; a signal
  without features is complete.

Simulated and measured data must never look the same. The rule of the Studio applies, with its origin marks as the
only vocabulary: in the list and on the scope, `SYNTHETIC` carries a mark of its own with generator and seed, plus a
dashed curve; the mark is inherited by every derived signal and every model.

## 5. Declarative representation of the system: `opentsx.system.v1`

One document per system. Schema: [`schemas/opentsx.system.v1.schema.json`](schemas/opentsx.system.v1.schema.json),
example: [`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml).

```yaml
schema: opentsx.system.v1
system: h2-chain
version: 1
title: Wasserstoffkette am Windpark
domain: wind                               # namespace of the topics: tsx.<domain>...
plan: { ref: opentsx-twin/src/main/resources/twin/h2-chain.yaml }
inventory: { ref: opentsx-twin/src/main/resources/twin/inventar-beispiel.yaml, system: h2-chain }
catalogs:
  - { id: wind, ref: opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml, version: 1.0.0 }
signals:
  - id: park/T02/WTUR.W                    # sensor channel under the building block "park"
    channel: wind/WTUR.W                   # expectation from the catalog
    raw:
      source: { kind: topic, topic: tsx.wind.episodes.v2, origin: SYNTHETIC,
                select: { metric: wind.turbine.power_active, tags: { turbine: T02 } } }
    clean:
      source: { kind: flow, flow: turbine-power-clean, output: clean }
  - id: park.p_avail_kw                    # port of the wiring plan, derived
    expect: { unit: kW, min: 0, max: 12000, interval: PT1M, kind: ANALOG }
    clean:
      source: { kind: flow, flow: park-power, output: sum }
  - id: market.price_eur_mwh               # no data bound: simulated
    expect: { unit: EUR/MWh, min: -500, max: 3000, interval: PT15M, kind: ANALOG }
    raw:
      source: { kind: generator, processor: gen.ou, seed: 7,
                params: { mean: 62.8, reversion: 0.08, sigma: 9.0 } }
  - id: ely.setpoint_kw                    # by hand
    expect: { unit: kW, min: 0, max: 5000, interval: PT1M, kind: ANALOG }
    raw:
      source: { kind: manual, value: 5000, origin: REPORTED }
flows:
  - { id: turbine-power-clean, ref: systems/flows/turbine-power-clean.flow.yaml }
  - { id: park-power, ref: systems/flows/park-power.flow.yaml }
```

Rules:

* **One truth per question.** Building blocks and ports are in the wiring plan, the meaning of a channel is in the
  catalog, the device is in the inventory. The system document says **which signal comes from where**. Today the
  inventory also names series (`series`) and ports per asset; until it is read by code, the tool checks that both
  statements match. After that, `series` in the inventory is dropped in favor of the system document (proposal,
  D47).
* **Generators appear only as the source of a signal,** never as a node of a flow.
* **Check.** The id is unique; the building block and, for ports, the port are in the wiring plan; `channel` ends
  in the same channel id as the signal id and is in the catalog; the asset is in the inventory; a selection `select`
  matches exactly the series of the signal; every `flow` is in the list, every `processor` has a description; a
  flow as the source of `clean` reads only `raw` of the same signal or `clean` of other signals, a flow as the
  source of `features` reads only `clean`; cycles are an error. What a JSON schema cannot check (uniqueness,
  references), the tool checks.
* **Hash.** The hash of a system is computed by the tool: over the document and the content of all files it refers
  to, like the plan hash of the twin. It goes into the provenance of the generated series.
* **Open signals are allowed.** The user interface counts them.

## 6. Where the data lives, and how a flow gets to the processor

This was the biggest gap of the first version: it did not say in which topic simulated and clean series are
stored, and today's processor cannot read its example flows.

### 6.1 Rule for topic and series

| Source of the grade | Topic | Format |
|---|---|---|
| `topic` | as given at the source | as there |
| `generator` | `tsx.<domain>.synthetic.v2` | v2 episodes |
| `flow`, grade `clean` | `tsx.<domain>.clean.v2` | v2 episodes |
| `flow`, grade `features` | `tsx.<domain>.features.v2` | v2 episodes, one series per feature |
| `manual` | none; the value is in the document | n/a |

So per domain there is **one** topic for simulated series, **one** for clean series and **one** for features, not
one per signal.
The series in it is determined by:

* **Metric:** the metric of the channel if the signal refers to one, otherwise `<system>.<block>.<port>`.
* **Tags:** `system`, `signal` (the id) and `grade` (`raw` for generated data, `clean`, `features`); for sensor
  channels also the tags of the raw series; for features also `feature` with the name of the feature. `grade` was
  added with L4, because a generated raw series and the clean series of the same signal would otherwise be the same
  series. `flow` (the name of the deployment) was added on 2026-10-14 (D84, 15, section 13.5): if two flows write
  the same signal one after the other, two series result. The view of a signal shows the series of the flow that is
  running, and names the others without counting them. In the same way a generated raw series carries the run of
  its generator (`run`, D85); the run generated last is the one counted.
* **Series id:** as everywhere, from metric and tags (`SeriesKeys`).
* **Labels of the episodes:** `origin`, `grade`, hash of the system.

With this the TS inventory finds the series of a signal without any further information, and the state can be
computed. Prerequisite: `TopicLayout` knows the three topics, and the retrieval server reads them like the episode
topic.

### 6.2 The flow as a person writes it, and the DAG as the processor reads it

A flow in the system document names **signals**, not series ids and not topics:

```yaml
schema: opentsx.flow.v1                    # written form; translated into opentsx.dag
flow: turbine-power-clean
reads: ["park/T02/WTUR.W@raw"]
nodes:
  - { id: range, op: dq.plausible-range, in: [source], params: { mode: gap } }   # min, max from the expectation
  - { id: clean, op: resample,           in: [range],  params: { every: PT1M, agg: MEAN } }
writes: [ { from: clean, signal: "park/T02/WTUR.W@clean" } ]
```

A **translation step** turns this into the DAG for the processor: it resolves signals to topic and series id
(rule 6.1), fills parameters from the expectation, replaces processor ids with the operators of the DAG
(section 7) and puts the `decode` node in front. The processor never sees a signal name. The workbench produces the
same written form when it was opened with a signal.

### 6.3 What must change in the DAG and in the processor

`opentsx.dag.v1`, as `VectorJob` reads it today, is not enough. What is needed is a version `opentsx.dag.v2` with
three extensions:

1. **Sink for series:** a sink that writes v2 episodes into the topic of the clean series. Today only vector topics
   are allowed.
2. **Source from more than one topic of the same format:** the episode topic, `synthetic` and `clean` are all v2
   episodes. A combination of topics of different formats stays excluded
   ([11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), section 5a).
3. **Operators before the sink:** the processor executes transformers, not only `align.vector` (TASK-013,
   013.5).

Until then, flows with transformers stay interactive, and the user interface says so.

## 7. Declarative description of the processors: `opentsx.processor.v1`

Today the meaning of an operator is stated in three places: in `ops.ts`, in the Java code and in the table in
[11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), section 6. Schema:
[`schemas/opentsx.processor.v1.schema.json`](schemas/opentsx.processor.v1.schema.json), examples:
[`gen.ou`](../../systems/processors/gen.ou.yaml), [`dq.plausible-range`](../../systems/processors/dq.plausible-range.yaml),
[`align.vector`](../../systems/processors/align.vector.yaml).

| `kind` | Inputs | Examples |
|---|---|---|
| `generator` | none | Ornstein-Uhlenbeck (prices, demand), sine mix, playback of a recording |
| `transformer` | one or more series | plausibility check, resampling, smoothing, FFT, `align.vector` |
| `model` | series and a trained artifact | forecast, anomaly score, step of the twin |

```yaml
schema: opentsx.processor.v1
id: dq.plausible-range
version: 1
kind: transformer
title: Plausible range
description: Values outside the band become gaps or are clipped.
inputs:  [ { name: in,  type: series, cardinality: one } ]
outputs: [ { name: out, type: series } ]
params:
  - { name: min,  type: number, required: true, from: expect.min }
  - { name: max,  type: number, required: true, from: expect.max }
  - { name: mode, type: enum, values: [gap, clip], default: gap }
semantics: { needs: point, deterministic: true, keeps: [time, unit, series], origin: INHERIT }
dag: { op: dq.checks, fixed: { checks: [PLAUSIBLE_RANGE] } }
runtimes:
  workbench: { status: available }
  processor: { status: planned }
ui: { group: stream, label: Plausible range }
```

What the description carries:

* **`from: expect.<field>`** fills a parameter from the expectation of the signal whose data the processor
  produces. This is how "simulated according to the expected properties" comes about.
* **`dag`** names the operator of the DAG when its name differs from that of the processor (`dq.plausible-range` is
  `dq.checks` with the check `PLAUSIBLE_RANGE`).
* **`semantics.needs`** says how much input one output value needs: `point`, `window` or `series` (the whole
  series). A processor with `series`, such as normalization to the maximum, cannot run continuously.
* **Output `xy`** stands for results over an axis other than time (spectrum, histogram, distribution). They can be
  viewed, but not published as a signal.
* **`runtimes`** says where a processor runs. From this the workbench derives whether a flow can be started; the
  list "what is missing" is no longer maintained by hand.
* **`ui`** names the group and the label in the operator bar. Durations are written as ISO durations;
  `editAs: seconds` says that the workbench lets the user enter them in seconds.

The descriptions are not all written at once. Each delivery stage describes the processors it needs; a test checks
that a described processor matches `ops.ts` (id, parameters, defaults).

## 8. User interface

### 8.1 The shell

The frame is the web application, in English. It takes over the layout of the Studio (rail on the left, path at the
top, status bar at the bottom) in React, with a shared file for colors and spacing that the workbench draws from
too. The workbench keeps its density.

```
+------------------------------------------------------------------------------------------+
| OpenTSx   h2-chain v    8 signals: 2 from topics, 1 derived, 1 simulated, 2 manual,      |
|                         2 open                                                           |
+------------+-----------------------------------------------------------------------------+
| System     |                                                                             |
|   8 ports  |                                                                             |
| Signals    |   (content of the place)                                                    |
|   2 open   |                                                                             |
| Flows      |                                                                             |
|   2, 0 run |                                                                             |
| Models     |                                                                             |
|   1 blocked|                                                                             |
| ---------- |                                                                             |
| Workbench  |                                                                             |
+------------+-----------------------------------------------------------------------------+
| example data | system hash 3f2a | times in UTC                                           |
+------------------------------------------------------------------------------------------+
```

Addresses: `/systems`, `/systems/<id>/system|signals|flows|models`. The numbers in the sketch match the example.

**Guided mode (optional).** The four places are the basis. Anyone who is new or builds a system from scratch
switches on "Guide". Then the sequence of the seven steps of the method
([user manual, chapter 16](../user-manual/16-engineering-method.md)) appears above the content, numbered, with the
state per step and a jump to the place where the step is done. The mode changes nothing about the places and forces
no order; it shows the next sensible step.

```
 Guide:  1 System ok | 2 Signals ok | 3 Stock ok | 4 Bind 2 open | 5 Prepare 1 of 3 | 6 Run 0 | 7 Model blocked
```

### 8.2 Signals

A row says what the signal is, where it comes from, how it is doing and what is to be done next. Rows are grouped
by building block and asset; a group can be collapsed so that a park with many turbines stays readable.

```
 park
   Signal              expected             origin      state                    next
   p_avail_kw          kW 0..12000 1 min    synthetic   clean, not running       [Start]
   T02 / WTUR.W        kW -100..10000 1 s   synthetic   raw only                 [Prepare]
   T02 / WMET.HorWdSpd m/s 0..60 1 s        synthetic   raw only                 [Prepare]
 market
   price_eur_mwh       EUR/MWh 15 min       SIMULATED   raw only (gen.ou, seed 7) [Prepare]
 ely
   setpoint_kw         kW 0..5000           reported    constant 5000            n/a
 offtake
   demand_kgph         kg/h 0..120 1 min    n/a         open                     [Bind] [Simulate]
```

A click on a row opens on the right: the expectation (editable), the source per grade, the period, a small trend
plot with the expected value range, who uses the signal, and the actions Bind, Simulate, Replace with measurement,
Prepare, Open on scope.

### 8.3 Handover to the workbench and back

Today the workbench holds exactly one flow; a second one replaces it. Therefore:

* **Before the handover** the shell asks for confirmation when the workbench holds unsaved work.
* **In the workbench** there is a context line:

  ```
  < Signals | h2-chain > park/T02/WTUR.W > raw -> clean          [Publish as clean signal]
  expected kW -100..10000, 1 s | synthetic | unsaved changes
  ```

  The scope shows the expected value range as lines; parameters with `from: expect.*` are prefilled.
* **"Publish as clean signal"** replaces today's three manual steps (check box "Publish as Smart Topic", "Save
  flow", "Deploy") with a dialog that says: which signal, which output of the flow, that the change is in the
  user's own working copy and not yet in the repository (D42), that the flow is not running yet and where it is
  started, and which steps the processor cannot execute.
* **After that** the workbench leads back to the row of the signal, which is highlighted.
* Without a system the workbench stays usable as it is today.

### 8.4 Existing labels that change

| Today | In future | Reason |
|---|---|---|
| link "Editor" (composer page), link "Composer page" (workbench) | "Workbench", "Flows" | two names for the same places |
| column "buckets" in the composer | "steps" | it counts steps |
| tab "Input bucket" | stays | the name is an explicit wish of the owner; the proposal "Steps" from the review is dropped |
| "Create a vector of the loaded series" | "Align series to a common clock" | says what happens |
| two state columns in the composer ("status", "Smart Topic") | one | the same fact |
| tab "TS-Model" in the workbench | stays | it mirrors the Swing workbench and says itself that its buttons do nothing there either |

### 8.5 The Studio views

"System diagram" becomes the place System, "Series" merges into Signals, "Overview" and "Compare" become Models.

**State at L2, differing from the draft:** The place System does not embed the prototype. It shows the wiring plan
as a table: per building block the inputs with their unit and what feeds them (the output of another building
block, a signal with its state, or nothing), and the outputs. The reason: the prototype is a generated HTML file
outside the web application, and no path leads from its ports to a signal. The table provides exactly this
connection and shows along the way which inputs from outside have no signal yet. The picture with the connections
stays the task of L5 (system diagram in React).

## 9. What the draft does not solve

* **No generator writes continuously into a topic today.** A runtime for this would be new. It writes to a shared
  broker; it may only write into the topic `synthetic` of its domain, never into an episode topic, and needs a right
  of its own (today one token applies to everything).
* **The processor only executes `align.vector` and only writes vectors** (section 6.3).
* **The Smart Topic specification ([05](05-SMART-TOPICS.md)) chooses its target by metric and tags** and cannot name
  a signal. For Models it needs `target.signal`; its own `resample` then becomes unnecessary. Its section `features`
  overlaps with the grade `features` (D43): features that several models need belong to the signal, those of a
  single model stay in that model's specification. The boundary is to be set with L5.
* **There are no processors for features.** The example names `feature.lags` and `feature.rolling`; both have to be
  built.
* **The twin computes a run as a whole.** Models shows computed runs at first.
* **Many assets of the same kind.** The example names one turbine out of six. A template "for every asset of this
  kind" is missing in the schema; without it a document for a large park gets long.
* **Concurrent changes.** The working copy per account (D42) needs a rule: every change carries the hash of the
  version it started from; if that hash no longer matches, the change is refused instead of overwritten.
* **Only the Java code knows the port list of the wiring plan** (`AtomCatalog`). The check tool needs Java or the
  export of the Studio.

## 10. Delivery stages (after approval)

The first version began with contracts for all operators, without a visible result. The order is now built up from
the smallest state that shows the idea from start to end.

| Stage | Content | Visible |
|---|---|---|
| L1 (**implemented** 2026-10-13) | system document with signals bound to topics and open signals; check in the build; `GET /v1/systems` and `/v1/systems/<id>/signals` with computed state; place Signals; "Open on scope" with context line and expected value range | catalog "expected against existing" for `h2-chain`, with a jump to the scope |
| L2 (**implemented** 2026-10-13) | shell: rail, status bar, shared colors; guided mode; labels as in 8.4; place Flows (composer with filter); place System as a table of the building blocks and ports (instead of the embedded prototype, see 8.5); place Models as a preview | one frame instead of three pages |
| L3 (**implemented** 2026-10-13, with limits) | topic `clean`, sink for series (`opentsx.dag.v2`), three operators in the processor (plausible range, unit, resampling), "Publish as clean signal". Not implemented: the written form `opentsx.flow.v1` with a translation step (the workbench writes the DAG directly), merging several series, writing back into the system document | raw data of a series continuously becomes a clean series |
| L4 (**implemented** 2026-10-13, with limits) | topic `synthetic`; two demo generators (`gen.ou`, `gen.sine`) as a one-off run over a period, only for administrators and only into this topic (D61); actions Bind, Simulate and Generate on the Signals page; binding only after the meaning is confirmed (D53); working copy per account on top of the system document, with export of the lines for the file (D42). Not implemented: a generator that writes continuously; writing back into the file | open signals are bound or simulated |
| L5 (**implemented** 2026-10-13, with limits) | Models: topic `features`, operators for features (`feature.lag`, `feature.rolling`) and a first model (`model.power-curve`), sink into another signal with `origin` and `madeOf`; approval per purpose from the decision card with range of validity and evidence (D59, `GET /v1/systems/<id>/decisions`); place Models; system diagram as a graph in React. Not implemented: the comparison of alternatives (follows with E3), `target.signal` in the vector specification | forecast and what-if in the same frame |

Processor descriptions come with the stage that needs them: L3 the transformers for cleaning, L4 the generators.

## 11. Decisions

Decided by the owner on 2026-10-13.

| No. | Topic | Decision | State |
|---|---|---|---|
| D40 | Frame | the web application is the shell, in the layout of the Studio; its views are embedded and rebuilt later | as proposed |
| D41 | Key | the signal connects everything; id `<block>.<port>` or `<block>/<asset>/<channel>`, unique in the system | as proposed |
| D42 | Storage | system documents and processor descriptions live as files in the repository; the retrieval server serves them; the user interface writes into a working copy per account, with the hash of the starting version, and produces the file from it | as proposed |
| D43 | Grade | **three:** `raw`, `clean` and `features`. Features are a grade of the signal and can be shared between models | **changed** (the proposal was two) |
| D44 | Processors | one description per processor, of the kind generator, transformer or model; they are written stage by stage and checked against `ops.ts` | as proposed |
| D45 | Language | English; texts of the scenarios stay in their language | as proposed |
| D46 | Example system | `h2-chain`: it has a system diagram, and its wind farm is based on the data in `tsx.wind.episodes.v2` | as proposed |
| D47 | Place of the binding | which series belong to a signal is stated in the system document; `series` in the inventory is dropped when the inventory is read by code | as proposed |
| D48 | Topics | per domain one topic `tsx.<domain>.synthetic.v2` and one `tsx.<domain>.clean.v2`, both v2 episodes; the series follows the rule in 6.1 | as proposed |
| D49 | Flow and DAG | flows name signals (`opentsx.flow.v1`); a translation step produces the DAG; the processor gets `opentsx.dag.v2` with a sink for series | as proposed |
| D50 | Places | four places without numbers (System, Signals, Flows, Models) and the Workbench as a tool as the basis; **plus an optional guided mode** with the numbered steps of the method | **extended** |
| D51 | Topic for features | `tsx.<domain>.features.v2`, v2 episodes, one series per feature with the tag `feature` | **consequence of D43 and D48, not decided on its own**; change before L5 if anyone objects |
