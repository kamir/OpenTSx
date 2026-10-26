# TASK-015: Multivariate analysis: pair windows and system reconstruction

## Task Metadata
- **Task ID**: TASK-015-multivariate-analysis
- **Created**: 2026-10-26
- **Status**: In progress (pair windows: phases 1a and 1b done)
- **Priority**: High
- **Epic**: P8 System reconstruction, simulation and optimization
- **Origin**: split out of [TASK-011](TASK-011-datamodel-refresher-2026.md) on 2026-10-26 (D90, [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md))
- **Concept**: [06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md](../docs/datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md)

## Objective
P8 reconstructs the dependencies in the local energy system (wind farm, battery, electrolyzer, energy consumers,
marketing) from data, simulates on the reconstructed structure and optimizes on it, along the chain sources, episodes,
network reconstruction, simulation, optimization. It asks what separate operational management loses and what
coordinated action gains, and quantifies that coordination gap against the reference operation under the same
boundary conditions. The result is a verifiable model of the dependencies and the room for action, accepted against
the ground truth of the wind farm simulator.

## Pair windows
A pair method compares two series A and B inside a window and gives one result item per window. The result items
of one pair range form an episode with a time index of its own: the window centres. That episode is stored, plotted
and cut into slices again like any other episode. Designed on 2026-10-26. The framework runs in the workbench
(phase 1b, opentsx-saas-frontend/src/workbench/pairs.ts); the methods follow in 1c to 1f.

**Pairing**

- Input: the episodes of two series A and B, as the workbench loads them from a retrieval server.
- A pair range is a time range `[t0, t0 + L)` that one episode of A and one episode of B cover. Default
  (`pairing: same`): the two episodes have the same start and the same end, which is what a `FIXED_DURATION`
  segmentation (opentsx-model/src/main/avro/Segmentation.avsc:9) gives both series. Option `pairing: intersect`:
  overlapping episodes of A and B are paired on their intersection; an intersection shorter than the window is
  dropped. Two times closer than half a microsecond are the same time.
- A series without episode ranges (a generated or file bucket) counts as one range from its first sample to its last
  plus one sampling interval, as the loader derives a range (opentsx-saas-frontend/src/workbench/loaders.ts:142).
- Pairs: with one input, every pair of its series in bucket order (A before B); with a second input, each series of
  A with each series of B, B on the time axis of A. At most 200 pairs (`MAX_PAIRS`); above that the operator asks
  for a selection first.
- Today the workbench joins all episodes of one series into one series and drops their ranges
  (opentsx-saas-frontend/src/workbench/loaders.ts:123 to 158). Keeping the ranges is the first step (phase 1a below,
  done: `Bucket.episodes`).

**Windows and time index**

Window width `W`, step `S`, length of the pair range `L`. Window `k` covers `[t0 + k*S, t0 + k*S + W)`. Its result
item is stamped at the centre `tc = t0 + k*S + W/2`.

| Window setting | Windows per pair range | Result per pair range |
|---|---|---|
| `window: episode` (`W = L`) | 1 | one point at the centre of the range, `t0 + L/2` |
| `window: W`, `step: S` with `W < L` | `n = floor((L-W)/S) + 1` | one episode with time encoding `REGULAR`, `tStart = t0 + W/2`, `intervalMicros = S`, `n` values |

- With `S < W` the windows overlap and the result is a series per pair range. With `window: episode` each pair range
  gives a single point; the points of many ranges form one series with time encoding `IRREGULAR_DELTA`
  (opentsx-model/src/main/avro/Episode.avsc:12), because the centres of the ranges need not be evenly spaced.
- A window that would end after `t0 + L` is not computed.
- The time values of the result are window centres, not sample times of A or B. The existing window operator stamps
  the window start (opentsx-saas-frontend/src/workbench/ops.ts:579 to 607, `xs.push(start)` at 595 and 602); pair methods do not reuse it.
- Slices: the result series is cut into new episodes with the existing segmenters. The new episode names the result
  episode in `Segmentation.sourceEpisodeId` (opentsx-model/src/main/avro/Segmentation.avsc:16).

**Alignment inside a window**

- A and B are put on one clock by the rules of `align.vector` (opentsx-saas-frontend/src/workbench/vector.ts:4 to
  14): ticks of `every` seconds since the epoch, one aggregate per tick, fill as-of up to `maxAge`. The default clock
  is the larger of the two sampling intervals.
- Coverage of a window: the share of ticks in which both A and B have a value. Below `minCoverage` (default 0.8)
  the result item is kept with the value NaN and the quality byte 1 (Episode.avsc:16, one byte per value,
  0 = ok), so the time index stays regular.
- Only the ticks that lie entirely inside a window count, and the fill does not reach back across the start of the
  pair range: a pair range is computed from its own samples. The tick and fill code is the one of `align.vector`
  (`addSamples`, `fillColumn` in vector.ts).
- In the workbench the quality bytes are `Series.quality` (types.ts:27). They belong to the x values they were
  computed for: an operator that builds a new x array (resample, FFT, histogram) drops them (`keepQuality`,
  flow.ts:407).

**Result series**

- A method gives one or more fields per window. Each field is a series of its own with the SeriesKey metric
  `pair.<method>.<field>` and the tags `a` (series id of A), `b` (series id of B), `window` and `step`
  (opentsx-model/src/main/avro/SeriesKey.avsc:7 to 10). Its series id follows from metric and tags as for any other
  series; the workbench computes it with xxHash64 in the browser (seriesKeys.ts), checked against the shared vectors
  opentsx-model/src/test/resources/testvectors/series-id.json. `window` and `step` are ISO-8601 durations in seconds
  (`PT120S`), or `episode`.
- Each pair range gives a result episode of its own in both window settings: with sliding windows
  `[t0 + W/2, t0 + W/2 + n*S)`, with `window: episode` the single point, one microsecond long. So `pair.a` and
  `pair.b` always name one episode each. Whether the export writes the points of `window: episode` as one
  `IRREGULAR_DELTA` episode per series instead is decided in phase 2.
- Lag sign: a lag `tau > 0` means B follows A by `tau`.
- Lineage: `Provenance.parentEpisodeId` holds one id (opentsx-model/src/main/avro/Provenance.avsc:11). It names the
  episode of A; the labels `pair.a`, `pair.b`, `pair.method`, `pair.window` and `pair.step` (Episode.avsc:19) name
  both input episodes and the settings.
- All pairs: for a set of series the same method runs on every pair and writes one row per pair and pair range
  into the edge table of P8.0 (source `a`, target `b`, method, lag, strength, window).
- The link strengths of P8.1 (`l_adj`, `l_norm`, `l_trans`) and the significance fields come as further fields of
  `pair.xcorr` with P8.1.

| Method | Fields | Origin |
|---|---|---|
| `pair.xcorr` | `max`, `lag`, `strength` | port of `KreuzKorrelation.calcKR_SLIDINGWINDOW`, which already stamps the window centre (`experiments/crosscorrelation/KreuzKorrelation.java:455` and `:490`), and of `CCFunction.calcStrength_VERSION_A`, `strength = (max-mean)/std` (`experiments/linkstrength/CCFunction.java:76` to `:88`), from kamir/WikiExplorer.NG (Apache License 2.0) |
| `pair.es` | `q_total`, `q_ab`, `q_ba`, `q_rand` | event synchronisation after Quiroga, Kreuz and Grassberger (python-package/opentsx/algorithms/event_sync.py:83 to 137); `q_rand` is `q_total` of shuffled event times, the baseline against chance |
| `pair.scatter` | `slope`, `r` | least squares line and Pearson correlation of the aligned values |
| `pair.granger` | `p` | p-value of the F test, `GrangerCausality.grangerCausalityTest` (opentsx-core/src/main/java/org/opentsx/algorithms/statistics/GrangerCausality.java:19 to 53) |

**Browser first, processor later**

- The pair operators run in the workbench. Their OpDef has `multiInput: true` and `inCatalog: false`
  (opentsx-saas-frontend/src/workbench/types.ts:115 and 119), and `maxInputs: 2`. They sit in the tab
  "Multi-variate Analysis", which the Swing panel has but never filled
  (opentsx-core/src/main/java/org/opentsx/app/bucketanalyser/TSOperationControlerPanel.java:368 to 369).
- The plan export marks them: the DAG writes `# not in the stream operator catalog` next to every operator outside
  the catalog (opentsx-saas-frontend/src/workbench/flow.ts:713), and a pair node gets `experimental: true`.
- The processor reads nodes only through `in`, from a sink back to the source
  (opentsx-retrieval/src/main/java/org/opentsx/retrieval/vector/SeriesJob.java:106 to 122). A pair node that no sink
  reads is never looked at. A sink behind a pair node is refused: a series chain must have one input per node
  (SeriesJob.java:117 to 119), and a vector reads `decode.*` nodes only (VectorJob.java:85 to 91). This fails closed,
  so the processor needs no skip rule. The workbench says so before publishing: `runBlocker`
  (opentsx-saas-frontend/src/workbench/flow.ts:577) gets a reason for pair nodes.
- Stream phase: `align.vector` already joins several inputs on one event-time clock. A pair runtime puts its window
  on top of that join: it collects the aligned ticks of a window, runs the method when the watermark passes the
  window end and writes the result at the window centre. No second join operator is needed.

**Phases**

- [x] 1a: workbench keeps episode ranges when it loads episodes (loaders.ts:123 to 158), with tests. *Done 2026-10-26: `Bucket.episodes` (`EpisodeRange`: series, episode id, [start, end) in seconds from x = 0, segmentation strategy), read from `tStart`/`tEnd` to the microsecond or derived as the v2 model does; time-axis operators hand them on like `marks`, a new axis drops them; `episodes.ts` (`episodesOf`, `episodeSummary`), Properties row "Episodes".*
- [x] 1b: pair framework in the workbench: pairing, windows, centre stamps, alignment, coverage, quality, result
  bucket with its own time index, series keys and labels as above, with tests for both window settings. *Done
  2026-10-26: `pairs.ts` (`pairWindows` runs a `PairMethod` over the pairs of one or two inputs; `pairRanges`,
  `windowsOf`), `seriesKeys.ts` (canonical form and xxHash64), the diagnostic method `pair.coverage` (fields
  `coverage` and `ticks`) as the operator "Pair coverage", `Series.quality`, `EpisodeRange.labels` and `parent`;
  tests in pairs.test.ts.*
- [ ] 1c: `pair.xcorr`, ported from kamir/WikiExplorer.NG, with test vectors from the old implementation.
- [ ] 1d: `pair.es` with `q_rand`, test vectors shared with python-package/tests/test_event_sync.py.
- [ ] 1e: `pair.scatter`.
- [ ] 1f: `pair.granger`.
- [ ] 1g: all pairs and the edge table for P8.0.
- [ ] 2: plan export with `experimental: true`, publish blocked with a reason.
- [ ] 3: stream phase on top of `align.vector` in the processor; parity test against the workbench on the same input.

## Phase 8 (moved from TASK-011)
Moved from TASK-011 (commit 551815a3), lines 132 to 197.

### Phase 8: System reconstruction, simulation and optimization (concept: [`06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md`](../docs/datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md))

Chain: *sources (sensors, content interaction such as ER1/Wikipedia with feedback) → episodes → network reconstruction
(Kämpf 2017 + state of 2026) → simulation on the reconstructed structure → optimization (classical, quantum only as an
object of measurement)*, controlled through the Personal Service Layer (P7e). Each step is accepted against the ground
truth of the wind farm simulator.

**Short title: Interplay and coordination potential.** Guiding question: *"What do we lose through separate
operational management, and what do we gain through coordinated action?"* This means the joint optimum of the local
energy system (wind farm, battery, electrolyzer, energy consumers, marketing). To be included: weather and generation
forecasts, electricity prices and marketing schedules, H2 demand and offtake obligations, storage levels, capacities
and technical limits, grid restrictions, field work, bat protection shutdowns and other operating rules.
- **Result:** a verifiable model of the dependencies and the room for action, plus a quantified coordination gap.
  Deliverables: dependency model, constraints, reference operation, comparison scenarios, potential report with
  assumptions, uncertainties and sensitivities.
- **Evaluation principle:** coordination potential = result of the coordinated alternative − result of the
  reference operation, under the same boundary conditions and with the information available at decision time; a
  comparison with perfect knowledge of the future only as a labeled theoretical upper bound. Result measures:
  contribution margin, avoidable curtailment, H2 demand coverage, storage stress, benefit and additional cost per
  participant. Modeled potential and proven additional revenue stay separate.
- **Principle:** A statistical dependency is at first a hypothesis, not proof of causality. The methods in
  P8.0 to P8.5 are **method proposals** and become binding only after technical agreement.
- **Cooperation:** transparency about who provides which data, who decides on changes and is accountable for them,
  who benefits, and who carries effort, costs and risks.
- **Bridge to P9:** P8 identifies dependencies and quantifies the coordination potential; P9 turns this into an
  executable twin in which decisions have a causal effect and countermeasures are tested.
- [ ] Capture operating rules (grid restrictions, field work, bat protection shutdowns) as constraints: in the twin as the "Operating rules" building block (calendar or rule source for curtailment).

**P8.0: Foundation (contract)**
- [ ] *(low priority, noted for later)* Ground truth export: the directed wake graph per wind direction sector **that the simulator used to generate the data**, from `opentsx-windpark` (Jensen, `WakeModel`), labeled as a simulation reference (`gt.source=simulator`, `gt.kind=wake`). Definitions per [07 §8](../docs/datamodel-2026/07-DIGITAL-TWIN-BSP.md): meteorological convention, 12 × 30° sectors, edge `A → B` = B in the wake cone of A for at least one sector direction, weights (mean/maximum deficit, coverage share, distance in D) at reference Ct (8 m/s) plus a Ct table for 4/8/12 m/s; in addition the **dynamic edge log** of the run (active pairs per 10 s step), including injected faults/regimes. Document the limits (no stability/meandering).
- [ ] Iceberg schema of the **edge table** (multilayer, versioned): source/target series, layer/method, lag, strength, significance fields (l_adj, l_norm, l_trans, p-value, surrogate type, confidence from bagging), window, specification hash, provenance (episode hash, topic/offsets).
- [ ] OpenLineage facet "replay provenance" in `opentsx-openmetadata`; edge table, simulation runs and optimization instances as lineage nodes in OpenMetadata.

**P8.1: Classical reconstruction (Kämpf methodology as an Apache-licensed Java library, clean room)**
- [ ] Lagged cross-correlation with adjusted (l_adj), normalized (l_norm) and peak quality (l_trans) link strength.
- [ ] Event Synchronization with Q_rand calibration **and** Event Coincidence Analysis (ECA) as a more stable alternative. The Q>1 bug itself is already fixed with #110.
- [ ] Surrogates: shuffling, phase randomization, Fourier filter + rank reordering (preserve long-term correlations); KS test, adaptive threshold per window, k-means in link measure planes, FDR correction; MST/PMFG filter.
- [ ] Univariate preliminary stage: DFA, RIS, weekly cycle normalization (168 h) for content data.
- [ ] Context measures for content networks: Context Cohesive Force (CCF), relevance indices (local/global TRRI), Structure Induced Stress (SIS) as a structure vs. function measure.
- [ ] Streaming screening: running window sums (CC/ECA) in `opentsx-stats`, Flink variant with the Flink upgrade; precision/recall against ground truth per sector, always report the CC baseline as well.

**P8.2: Causal confirmation (screen-then-confirm)**
- [ ] Python worker (`python-package/opentsx/algorithms`): reads episodes via PyIceberg, calls tigramite (PCMCI+, RPCMCI, J-PCMCI+) and IDTxl (multivariate TE) **behind a process boundary** (GPL), writes edges back; bootstrap bagging for confidences.
- [ ] Hawkes-Granger for event streams (content interaction: accesses, edits, feedback).
- [ ] Comparison report in CauseMe style on simulator ground truth; confirmation becomes the default only if it beats the CC baseline measurably.

**P8.3: Simulation on the reconstructed structure (twin from data)**
- [ ] Network dynamics models on confirmed edges: VAR, Koopman/DMD; multivariate Hawkes on a graph for content data; physics-based wake path (FLORIS-like) as a reference.
- [ ] Scenario and intervention replay against held-out episodes; SIS/threshold parameters as a structural break detector; acceptance: out-of-sample better than a model without structure.
- [ ] Identifiability: regime diversity or targeted excitation (simulator interventions, feedback as a quasi-intervention).

**P8.4: Optimization, classical**
- [ ] Problem builder from the edge table/simulation: graph-native binary problems (partitioning, sensor/turbine selection, MIS) as QUBO in dimod format; operational problems (yaw/wake steering, maintenance, unit commitment) as MILP.
- [ ] Backends: HiGHS, OR-Tools CP-SAT (Java), simulated annealing/parallel tempering; Gurobi/CPLEX optional; Java exports, Python optimization service behind an interface.
- [ ] Results as Smart Topic events; optimality gap against the MIP bound; a closed loop in the simulator improves the objective.

**P8.5: Quantum and federated experiments (object of measurement, not a promise)**
- [ ] Quantum backends (D-Wave Leap Hybrid, Qiskit Functions, possibly neutral atoms for MIS) only through a Python sidecar; protocol: time to target including embedding and queue, best tuned classical baseline (PT, GPU heuristics), QOBLIB report format; "parity/no advantage" is a valid result.
- [ ] Publication of versioned, simulator-generated QUBO/MILP instances with known optima.
- [ ] Federated structure discovery across operators (Wayang, mergeable sketches, shared IEC catalog); semantic alignment first, because inter-farm learning suffers from heterogeneity.

**P8.x: Content interaction / ER1**
- [ ] Clarify whether an anonymized or synthetic extract of ER1-like data (access, edit, feedback) can be provided as a second test case; otherwise validation with public Wikipedia pageviews/clickstream.
- [ ] Synthetic generator for content interaction with known coupling and feedback (counterpart to the wind farm simulator).

## Open decisions (moved from TASK-011)
Moved from TASK-011 (commit 551815a3), lines 382 to 383.

- [ ] Confirm D14 to D17 (P8 proposals).
- [ ] ER1: provision of anonymized/synthetic content interaction data (P8.x).

## Streaming screening (moved from TASK-011)
Moved from TASK-011 (commit 551815a3), line 418 (part).

- [ ] Flink adapter for the P8.1 streaming screening.

## Research follow-up (moved from TASK-011)
Moved from TASK-011 (commit 551815a3), lines 422 to 428.

**Research follow-up (from 06-SYSTEMREKONSTRUKTION-OPTIMIERUNG, marked as unverified)**
- [ ] Verify the licenses of IDTxl, pyunicorn, netrd, causal-learn, Tetrad from their LICENSE files; Maven availability of JIDT.
- [ ] Obtain CauseMe and QOBLIB leaderboard numbers and CausalTime results.
- [ ] Official AWS status of IoT TwinMaker; primary documentation of InfluxDB/Timescale/QuestDB/Grafana/Prometheus on causal and network functions.
- [ ] Comparison of ES/ECA vs. Hawkes on clickstream-like data; Hawkes model on the Wikipedia page network (no source found → own contribution).
- [ ] Full text of Müller et al. (IEEE TPWRS 2025, quantum unit commitment): instance sizes and run times.
- [ ] Verify the repository handle of the dissertation (MLU, probably 1981185920/8878).
