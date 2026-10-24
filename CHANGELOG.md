# Changelog

All notable changes to OpenTSx are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Releases are tagged `v<version>`;
entries before 3.0.0 are grouped by date. The detailed progress log (German) is in
[`EVOLUTION/TASK-011-datamodel-refresher-2026.md`](EVOLUTION/TASK-011-datamodel-refresher-2026.md).

## [Unreleased]

### Fixed

- The exercise runner uses the Maven classpath, propagates failed assertions and rejects invalid modes.
  Seeded exercise checks recalculate means and test the actual scaling and daily-window behavior.
- Unavailable desktop analysis and modern Java/Scala export buttons are disabled with explanatory tooltips.
  The modern tracker's JSON display action is labelled `Show PFD`.
- Classic bucket workflow entry points `loadViaContext` and `processBucket` throw `UnsupportedOperationException`
  instead of reporting success with empty results. `MacroRunner` propagates the unsupported-load error.
- Classic FFT conversion preserves the input coordinates and the frequency coordinates of FFT results.
  `TimeSeriesObjectFFT` headers, diagnostics and error messages use English; averaged series use the `Mean_` prefix.
- Desktop FFT derives sampling rates from uniformly spaced coordinates and requests an explicit rate for integer
  index axes. Invalid data and rates produce an error; cancelling the rate dialog leaves the flow unchanged.
- A Maven download that failed with HTTP 502 failed the build, because Maven retries only 429 and 503 by default.
  `.mvn/maven.config` now retries a download up to five times, 3 seconds apart, on 429, 500, 502, 503 and 504.

## [3.0.1] - 2026-10-16

### Fixed
- `bin/opentsx market load --series <path>` refused an archived series that is not in the catalog, such as
  `dwd-cdc/<station>/<parameter>`. It now takes a path from the archive when it is archived there.
- The `opentsx-tools` image staged with `--with-s3` lacked `dev.failsafe:failsafe`, a runtime dependency of
  `iceberg-aws`, so reading an `s3://` table failed with `NoClassDefFoundError: dev/failsafe/FailsafeException`.
  `stage.sh` adds it, and the image workflow checks for it.

## [3.0.0] - 2026-10-16

### Added
- `bin/opentsx market publish --from-day D --to-day D` publishes only the episodes that begin in that range of
  UTC days (`--to-day` exclusive). A daily job publishes the last complete days instead of the whole year again.
- `bin/opentsx market load` takes the same `--from-day` and `--to-day`.
- Workflow `.github/workflows/image.yml` builds `opentsx-tools` for `linux/amd64` and `linux/arm64` with SBOM and
  provenance. On a tag `v<version>` it pushes `ghcr.io/kamir/opentsx-tools:<version>`; the tag must match the
  project version and be a stable version. On a pull request that changes `deploy/docker/` it only builds.

### Fixed
- `bin/opentsx market load` wrote every episode again on each run, because each run gave it a new random id. It now
  uses the identity of `publish` (series, start, value count), so the Iceberg store skips what it already holds.
- Web workbench in `opentsx-saas-frontend` (route `/workbench`): the Swing bucket analyser in the browser. Load a
  bucket (sample, CSV/TSV, episode JSON, retrieval server), apply the time series operations, inspect every result
  on an oscilloscope view (time base, vertical scale, cursors, trigger, measurements), build the processing flow as
  a graph and export it as `opentsx.dag.v1` Smart Topic definition, Processing Flow Descriptor or Java code. See
  [`EVOLUTION/TASK-013-web-workbench.md`](EVOLUTION/TASK-013-web-workbench.md) for the limits: no runtime executes
  the DAG yet.
- TS inventory: the retrieval server reads the episodes topic of a domain from KafScale (`--kafscale <host:port>
  --domain <d>`) and lists every series with extent, value range, episode labels and the ground truth of the
  scenario as time ranges (`GET /v1/inventory`, `opentsx.ts-inventory.v1`). The workbench has a "TS Inventory"
  dialog: filter by tag, channel, scenario event or text, choose window and resolution, load the selection as the
  bucket. `deploy/k3s`: `RETRIEVAL_SOURCE=kafscale`.
- Vector measuring points (draft): operator `align.vector` brings the results of several nodes onto one clock and
  gives one record per tick and key with one value per component (`opentsx.vector.v1`, JSON schema in
  `docs/datamodel-2026/schemas/`, rules in `docs/datamodel-2026/11`, section 5a). The workbench has the operator as
  "Align to vector": nodes can have several inputs, the scope shows the components on one time base, and the DAG
  export writes the node with all inputs and a vector sink. No runtime executes it yet.
- Vector topics are written and read back. The vector processor (`opentsx vectors`, `deploy/k3s`:
  `VECTOR_PROCESSOR=true`) keeps them up to date: it follows the episodes topic, writes a measuring point to
  `tsx.<domain>.smart.<name>.vectors.v1` when every series has passed its tick, checkpoints per sink and reports
  its status. DAGs are deployed and stopped through the control topic `tsx.<domain>.dags.v1`. The retrieval server
  reads the vector topics, so their components show in the TS inventory as series, and it lists the deployments
  (`GET /v1/dags`). The Java operator and the workbench pass the same test vectors
  (`docs/datamodel-2026/testvectors/`).
- Deploying needs an account: the workbench has "Deploy" and "Stop" in the tab "Smart Topic" with the state the
  processor reports, a list of what a deployment needs (ready or missing, step by step) and a button that turns
  the loaded series into a published vector. The SaaS backend checks the account (`POST /api/v1/dags/deploy`, `/stop`; administrators only
  unless `DAG_RUN_ROLE=user`) and forwards with the run token to the retrieval server (`POST /v1/dags/deploy`,
  `/stop`, only with `--kafscale`, `--dag-runs true` and the token `OPENTSX_DAG_RUN_TOKEN`). The frontend image
  does not proxy those write routes. `deploy/k3s`: `RETRIEVAL_DAG_RUNS`, `RETRIEVAL_DAG_RUN_TOKEN` (generated),
  `SAAS_DAG_RUN_ROLE`.
- Workbench: the ground truth of the scenario shows on the scope. Series loaded from the TS inventory bring their
  events (operations, sensor faults) along as bands on the time axis; operators that keep the time axis hand them
  on, and "Segment at threshold" says how many crossings lie inside an event.
- SaaS module, test operation: `TEST_LOGIN=true` (`deploy/k3s`: `SAAS_TEST_LOGIN=true`) lets `admin` with the
  password `admin` sign in as the first administrator; the login page shows it. Off by default.
- Workbench: the "OpenTSx Flow Composer" lists the flows stored in the account in a selection window like the TS
  inventory: filter by text, domain, status or kind, see what a flow reads, computes and publishes and what the
  processor does with it. From there a flow is opened in the editor (several: the first on the workbench, the
  others in tabs of their own), created, renamed, copied, deleted, activated and paused. Activating a flow the
  vector processor can run deploys it, pausing stops it; any other flow is only marked. An opened flow loads the
  series it was built on from the TS inventory, over their whole range.
- Workbench: a deployment belongs to its flow. It is named `<domain>-flow-<id>` (was `<domain>-<track>-smart`),
  so flows of one track no longer replace each other. "Deploy" saves the flow first and marks it active, "Stop"
  marks it paused. A deployment is refused while another running one writes the same topic. Deployments under
  the old name keep running and belong to no flow.
- Workbench: the left side has two tabs. "Input bucket" is the tree of the loaded bucket and the buckets made
  of it; "Smart Topic" (moved there from the inspector) holds publishing, the DAG download and the deployments.
- SaaS frontend: the page after sign-in (`/dashboard`) is the "OpenTSx Flow Composer". It replaces the cards of
  the dashboard by the composer as a whole page: select flows, open them in the editor, create, rename, copy and
  delete them. "Start" and "Stop" (in each row, in the details and for a selection; they replace "Activate" and
  "Pause") run a flow as a Smart Topic on the vector processor. A flow the processor cannot run shows what it
  needs and is no longer marked active.
- Concept and draft for the engineering workflow from system to model
  ([`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](docs/datamodel-2026/14-ENGINEERING-FLOW.md), German): the
  signal as the key between Studio, workbench and Flow Composer, and two draft schemas, `opentsx.system.v1` (a
  whole system: which signal comes from where) and `opentsx.processor.v1` (generators, transformers, models),
  with examples for the system `h2-chain`. Signed off on 2026-10-13 with three grades of a signal (raw, clean,
  features) and an optional guided mode; the implementation follows in delivery stages.
- Signals of a system (TASK-014, stage L1). System documents (`opentsx.system.v1`) live in `systems/`; the
  retrieval server reads them (`--systems`), checks every reference against the twin plan, the channel catalog and
  the inventory, and hands out the state of each signal computed from the TS inventory (`GET /v1/systems`,
  `/v1/systems/<id>/signals`). The web application has the page "Signals" (`/systems`): expectation, origin, state
  and next step per signal. "Prepare" opens the raw series of a signal in the workbench, with a context line and
  the expected range on the scope. `deploy/k3s`: the retrieval server on KafScale serves the documents of the image.
- Shell around a system (TASK-014, stage L2): `/systems/<id>/<place>` with a rail for the four places System,
  Signals, Flows and Models and the workbench as the tool. "System" shows the atoms of the plan with what feeds each
  input (`GET /v1/systems/<id>/plan`); "Flows" is the Flow Composer for the flows of the system; "Models" previews
  which signals are ready for a model. The optional guide shows the seven steps of the engineering method with the
  state of the system in each. A flow built from a signal remembers its system and signal.
- Clean signals (TASK-014, stage L3). The vector processor also runs series sinks (`opentsx.dag.v2`): a chain of
  plausible range, unit conversion and resampling from a raw series to the clean grade of a signal, written as v2
  episodes to `tsx.<domain>.clean.v2` with the tags `system` and `signal`. The workbench has "Publish as clean
  signal" (check, save and start in one step) when it was opened for a signal; the page "Signals" then shows the
  signal as clean. Shared test vectors for the cleaning operators in Java and TypeScript
  (`docs/datamodel-2026/testvectors/clean-ops.v1.json`).
- Plan revised after an external review ([`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md),
  German, decided on 2026-10-13: stages L4 and L5 are built first, then E1 to E3): the decision as the central unit, with draft schemas `opentsx.decision.v1` (decision
  card) and `opentsx.decision-record.v1` (recommendation, approval, action, outcome) and an example; a streaming
  contract with what holds today and what is the aim (11, section 7a); release of a model for a purpose (05, section
  5a). Signals carry a role, a meaning and an operating range next to the plausible range. Clean episodes carry an
  identity, so a restart is not counted twice. The user manual's chapter 16 is rewritten as eight steps from a
  decision to its effect.
- Binding and simulating signals (TASK-014, stage L4). On the page "Signals" a signal is bound to a series of the TS
  inventory after its meaning was confirmed, or to a demo generator preset from its expectation (`gen.ou`,
  `gen.sine`), which an administrator runs over a period. Generated series go to `tsx.<domain>.synthetic.v2` and
  carry `origin: SYNTHETIC` and `fidelity: demo`. What is set there is a working copy per account
  (`/api/v1/systems/<id>/bindings`) laid over the system document; the page gives the lines for the file.
- Models (TASK-014, stage L5). The series chain of the vector processor has a first model and features:
  `model.power-curve` (wind speed to power by a curve of points), `feature.lag` and `feature.rolling`. A sink may
  read the clean series of one signal and write another signal, with `origin` and `madeOf` in its labels, or
  features to `tsx.<domain>.features.v2`; the workbench has "Publish as another signal" for that. The retrieval
  server reads the decision cards a system document lists and says per analysis whether it is usable, restricted
  or blocked today, with the reasons (`GET /v1/systems/<id>/decisions`): grade, age against the newest data,
  accepted origin, the range the analysis was checked in, and its evidence. The place "Models" shows the cards; the
  place "System" shows the plan as a graph above the table. Comparing alternatives over a period is not built.
- System plan of the H2 chain in a second form (TASK-014, stage S1, after a second briefing; decisions D64 to D66 in
  [`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 13). The
  twin has six new atoms: `wind-park` (potential, available and generated power, with the energy not generated
  counted per cause), `h2-compressor`, `h2-buffer` with the regulator that feeds the pipeline, `h2-pipeline` with a
  pressure at each end, `h2-handover` (regulating valve, lowest delivery pressure, shortfall) and `h2-consumer`
  (the demand is information, the delivery is gas). The order compressor, buffer, pipeline is an assumption and
  says so in the plan; every simplification is named in its atom. The system document has a signal for every input
  from outside. The scenario now tells: the handover station closes, the buffer fills, the electrolyzer trips at
  200 bar. The first form of the plan is kept for the tests of the twin, and its atoms stay in the catalog.
- Demo of the H2 chain made showable (TASK-014, after the first walk-through in
  [`docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md`](docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md),
  section 7). The place "System" draws the plan in the phases of a time step, with power, hydrogen and information in
  three colours and late wires dashed, shows what the system document assumes (`assumptions`), follows the working
  copy (`POST /v1/systems/<id>/plan`) and links to the Smart Topics Studio, which the frontend image now serves
  under `/studio/`. The scenario has a rule window (04:00 to 05:00) and a dispatch (05:00 to 05:30), so potential,
  available and generated power differ and the energy not generated shows per cause.
- Runs of the twin on request (TASK-014, run service and run register). The retrieval server computes a run of the
  scenario of a system for the decisions a person asks for (`POST /v1/systems/<id>/runs`: controls set at a time, a
  policy, optionally a forecast that continues the reference), keeps the last hundred runs in memory and hands out
  a run, its series and a comparison of two runs by their identity. Every run names the origin of its inputs and
  its status, which is `restricted` as long as the inputs are those of the scenario. The web application has the
  place "Simulate": a question on the left, the run next to the reference on the right. Also: a signal bound in the
  working copy opens in the workbench, and a flow may read a generated series as the raw grade of its signal.
- `opentsx-market`, weather backfill: six more DWD quantities per station (relative humidity, wind direction,
  10-minute wind speed, the highest gust of the hour, pressure, precipitation) next to temperature, hourly wind
  speed and global radiation. `opentsx-dwd` knows the variable `weather.wind_gust`.
- The year 2025 in the system (TASK-014, stage J1). `bin/opentsx market publish` writes archived series as raw
  episodes into the episodes topic of a domain, in batches with a pause, and stops at the first refusal of the
  broker. The system document of the H2 chain (version 4) binds the day-ahead price to the published series
  (`REPORTED`) and has three signals for the weather of the site from a DWD station (10-minute wind, temperature,
  pressure); a series below an atom may state its own unit and kind without a channel of a catalog. The demand of
  the customer is declared as a demo generator. The night of the scenario is 2025-10-01. A decision measures the
  age of an input against the newest data among the signals it needs, not against every series of the system.
- Draft: notebooks as a further application
  ([`docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md`](docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md), German):
  load the series directly, look at them, test a new series against what the system expects. Not built.
- Notebooks as an OpenTSx application, stage N1 (TASK-014). The Python package reads the inventory, systems,
  signals, decisions and runs of the twin from the retrieval server, and `TopicReader` reads the episodes of a
  topic directly from the broker, once, without a consumer group and with a limit it does not pass silently.
  Notebook 1 shows the time series inventory, a series with its coverage and gaps, and the price of 2025. The image
  `opentsx/notebook-content` carries package and notebooks into the notebook of a stack (`deploy/k3s/notebook/`:
  a patch for the notebook's Deployment and a network rule for the retrieval server).
- The operator `model.wind-shear` (TASK-014, J1): the wind of a measuring height carried to the hub height by the
  power law, in the processor and the workbench, checked by the shared test vectors. The potential of the park in
  the system document of the H2 chain is now made of the measured wind of the DWD station: cleaned, carried from
  10 m to 120 m, then the power curve of four turbines.
- A derived series carries the flow that writes it (tag `flow`, decision D84). Two flows that write the same signal
  one after the other write two series; the state of the signal counts the series of the flow that runs and names
  the others. Before, a stopped flow and its successor wrote into one series and mixed two periods.
- The year run on history (`bin/opentsx twin --history ... --year 2025`) computes the H2 chain in its second form
  (compressor, buffer, pipeline, handover station) at a 15-minute step, with the potential of the park as its wind
  input and the compressor in the schedule. It prints the pressures of buffer and pipeline, the energy of the
  compressor and the hydrogen not delivered.
- A generated series carries the run of its generator (tag `run`, decision D85): the same generator over the same
  period replaces itself, another period gives another series. The state of a signal counts the run generated
  last (label `generatedAt`) and names the others.
- Runs on the signals of a system (TASK-014, J1). A run of the twin may take its inputs from the series of the
  signals instead of the invented inputs of the scenario (`inputs: "signals"`): each input says where its values
  came from, its origin and how much of the run it covers, and the run is compared with the decisions of the
  scenario on the same inputs. The place "Simulate" lets a person choose.
- Notebooks 2 and 4 for the notebook of a stack (TASK-014, N2): "Ein Signal" puts the raw and clean series of a
  signal next to its expectation and operating range, and shows what the decision says about its inputs; "Den
  Zwilling fragen" asks the twin a question on the signals of the system and then the same question over many
  times of day. The Python client asks for runs on the signals and gives the operating range of a signal.
- Notebook 3 "Verproben" (TASK-014, N2): tests a CSV file of an operator against a signal (unit, step, time zone,
  meaning of the time stamp, duplicates, plausible and operating range, gaps), puts it next to the series of the
  signal, gives a verdict, writes a prepared copy in UTC and the unit of the signal, and names the commands and
  binding lines. An example file derived from DWD data ships with it.
- User manual, chapter 16: the engineering method from the system to the model in seven steps (define the system,
  list the expected signals, take stock, bind or simulate, prepare, run, model), with the rules that hold across
  them, a worked example, a checklist, and for each step what OpenTSx supports today and what is planned.
- CI: jobs "SaaS frontend" (types, lint, tests, build) and "SaaS backend" (pytest).
- SaaS backend: flows are stored per user in the database (`app/api/flows.py`: create, read, update, delete,
  executions), replacing the placeholder routes. The workbench saves a flow to the account and reopens it from the
  dashboard. `opentsx-saas-backend/smoke-flows.sh` checks accounts and flows against a running instance.
- `deploy/k3s`: optional component `saas` (`SAAS=true`): frontend on node port 30311, backend, PostgreSQL; images
  `saas-frontend` and `saas-backend` (`deploy/docker/`).
- `LICENSE` (Apache License 2.0, full text) and `NOTICE`.
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), `SECURITY.md` (reporting via GitHub private
  vulnerability reporting), this changelog.
- `config/opentsx-local.props.example`: localhost / KafScale-compatible Kafka client profile for the 2.x tools.
  Without a local `config/opentsx-local.props` (git-ignored), `OpenTSxClusterLink` falls back to this template.

### Fixed
- Example system `h2-chain`: the available power of the wind farm was bound to the sum of the measured turbine power.
  Under curtailment that is the actual generation, not what the wind offers. They are two signals now: an
  observation (`park.p_gen_kw`) and an estimate from the wind speed (`park.p_avail_kw`).
- Readers on KafScale could skip records when the broker refused a fetch: the Kafka client throws the refusal of one
  partition out of the middle of a poll and drops what it had already taken from the others. `Patience` now puts
  the consumer back to where it stood before the refused poll. Seen as a clean series that lacked its first episode.
- Vector processor: the first write to a new sink topic stopped the whole processor. The KafScale proxy answers a
  produce request that mixes a topic nobody has written to yet with another one in a way the Kafka client cannot
  parse, and the producer then sent nothing any more, not even status records. Every runner now has its own
  producer and the status records have another; waiting for the broker's confirmation no longer blocks the loop,
  and a producer that gets no answer is closed and replaced.
- Readers no longer end when KafScale refuses fetches for a minute (`Unexpected error code 7 while fetching`): they
  wait it out (`org.opentsx.kafka.Patience`, used by `LogReader`, the statistics processor and `SeriesStatsLog`).
  Producers retry with pauses for up to three minutes instead of five quick attempts
  (`KafScaleClients.producerProps`). The statistics processor used to restart on every such window.
- Retrieval server: a vector topic the broker does not hand out no longer keeps the feed from reading the other
  topics.
- SaaS frontend: the login page showed the default administrator password of a local backend on every
  installation, also where the deployment generates another one. The hint now appears in the dev server only.
  A wrong password reloaded the page instead of showing the error; the form now reports it.
- SaaS backend: registration failed against a real database (`MissingGreenlet`), every authenticated request
  answered 401 (numeric token subject), and a refresh token was accepted as access token.

### Changed
- Default 2.x client profile is now `./config/opentsx-local.props` (was `./config/cpl.props`); scripts, the
  `opentsx-lg` README and `opentsx-default.properties` updated.
- `opentsx.properties.example` lists only keys the code reads and documents how the v2 modules are configured
  (command-line options and environment variables).

### Removed
- Archived Maven modules (`archive/modules/`, `archive/opentsx-kstreams/`) removed from `master`: not built, but
  their outdated dependencies kept raising security alerts and Dependabot PRs. Last state: commit `315f01d`.
- Four dead submodule links in `archive/demos/` (no URL in `.gitmodules`).
- `config/cpl.props`, `config/cpl_iMac.props`, `config/cpl_local.props`, `config/mdc_c1.props` (client profiles with
  private LAN hosts).

## 2026-10-12

### Added
- Digital twin proof of concept `opentsx-twin` (P9): atom contract with model, views, dynamics and policy; wiring
  plan in YAML; layered bulk-synchronous runner; provenance ladder; shadow, what-if and forecast modes. Scenarios:
  energy system / H2 chain (`bin/opentsx twin`), family house with calibration against bills
  (`bin/opentsx house`, `--simulated`), sailing boat Etap 22 with calibration against logger data
  (`bin/opentsx boat`, `--simulated`).
- Smart Topics Studio prototype (`docs/prototypes/smart-topics-studio.html`) and the LabVIEW / command-loop concept.
- Studio guide (`StudioGuide`, `twin/haus-erzgebirge.guide.yaml`, `twin/h2-chain.guide.yaml`): prose for every atom,
  port, parameter, control and wire of the house twin and the H2 chain, where each value comes from and references to compare against; checked against the plan.
  The node graph explains wires and ports on hover, and each node takes own details (nameplate, serial number, own
  parameter values) with export as inventory (`opentsx.inventory.v1`) and as an own wiring plan.
- Smart Topics Studio redesigned as a product component after reviews from four viewpoints (UI design, UX and
  accessibility, electrical and energy engineering, commercial reader): app bar with scenario switch, scenario summary
  with headline figures and the question behind it, views Überblick, Vergleich, Systembild and Messreihen, inspector
  beside the diagram, connections coloured by energy carrier and routed around the blocks, events in plain language,
  larger units from a threshold on, limit lines in charts, a switch for technical details. Engineering corrections
  in both guides.
- Smart Topics Studio as a tool shell: title bar with menus (Datei, Ansicht, Szenario, Hilfe), icon rail on the left
  that expands and collapses, scenarios as document tabs, detail panel for blocks and connections sliding in from the
  right, status bar. Keyboard: Alt+1 to Alt+4 for the views, Ctrl+B for the rail, Esc closes the detail panel.
  The document tabs belong to the current scenario: the scenario itself, a link to its page in the Anlagen-Explorer,
  and an event topic view.
- Event topic of a twin run (`opentsx-twin-live`): `EventPublisher` writes the events of a run to
  `twin.<id>.<run>.events` (key: atom, value: JSON `twin.event.v1`), at once or played over a given duration;
  `EventBridge` serves such a topic to browsers as server-sent events; `bin/opentsx twin-live publish|bridge|demo`.
  The Studio follows the topic live through the bridge and keeps a replay that needs no broker. The publisher creates
  the topic before the first event, so a reader can connect while the run has not reported anything yet.
- k3s component `event-bridge` (`EVENT_BRIDGE=true`): the event bridge as Deployment and Service on NodePort 30312;
  `twin-live` is part of the tools image and takes the broker from `KAFSCALE_BOOTSTRAP`.
- Retrieval (`opentsx-retrieval`, P5): segmentation, pattern engine (MASS, SAX, DTW), retrieval API, REST server,
  Python client, power-curve analysis.
- DevOps guide for k3s against an existing KafScale cluster (`docs/devops/`, `deploy/k3s/`, `deploy/docker/`).
- Design documents: Smart Topics (05), interplay & coordination potential (06), digital twin (07), house (08),
  Studio & LabVIEW (09), boat (10), Smart Topic processor & DevOps (11).
- New English user manual (`docs/user-manual/`), documentation inventory, launcher `bin/opentsx`, link check in CI.

### Changed
- 2026 refresh merged into `master` (#102); SaaS frontend/backend dependencies bundled (Vite 7, python-multipart,
  python-socketio).
- README repositioned ("from time series to decisions").

### Removed
- Outdated documentation and modules outside the build moved to `archive/`; `opentsx-app-tools` archived.

## 2026-10-11

### Added
- Streaming statistics and data quality `opentsx-stats` (quantile sketch, DQ, KafScale processor with checkpoints)
  and `opentsx-openmetadata` (metadata pool, validated against the OpenMetadata 2.0.5 schemas); Python
  `opentsx.stats` (P4).
- Wind-farm domain `opentsx-windpark`: IEC 61400-25-2 data-point catalog, normalizer, simulator with ground truth,
  CLI to KafScale / Iceberg.
- Store SPI with contract suite, Iceberg store (index, log sync), OpenTSDB adapter, PyIceberg reader with byte
  equality between Java and Python (P3).
- `opentsx-kafka-v2` (topic layout, bucket writer, episode index, replay, catalog) and `LegacyBucketImport` (P2);
  end-to-end tests against KafScale v1.6.0 and Apache Kafka 3.9.1.

### Changed
- Avro 1.12.1 managed centrally (precision bug in 1.12.0 covered by a test).

## 2026-10-09

### Added
- Data model v2: `opentsx-model` (Java) and `opentsx.model` (Python), byte-identical via shared test vectors;
  `timestamp-micros` (D2) and xxHash64 series identity (D3); legacy bridge; schema history with compatibility check
  (P1).
- KafScale conformance suite (P0): headers and CreateTime are preserved; `offsetsForTimes`, the idempotent producer,
  standalone commits and consumer-group rejoin do not work on KafScale v1.6.0.
- Analysis of the existing model, target model, phase plan and gap analysis (`docs/datamodel-2026/`).

## Before 2026

| Years | Phase |
|-------|-------|
| 2013–2018 | Hadoop.TS: Hadoop, HBase/OpenTSDB, Spark; research on Wikipedia and financial time series |
| 2018–2020 | renamed to OpenTSx; Kafka, Kafka Streams |
| 2020–2025 | Confluent Platform/Cloud, ksqlDB, multi-region demos, SaaS prototype |
