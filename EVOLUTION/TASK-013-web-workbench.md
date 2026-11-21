# TASK-013: Web workbench (the Swing bucket analyzer in the browser)

## Task Metadata
- **Task ID**: TASK-013-web-workbench
- **Created**: 2026-10-12
- **Status**: In Progress
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery branch**: `opentsx/2026-task013-start-stop` (013.21, Start and Stop), worktree
  `../OpenTSx-worktrees/composer-page`
- **Earlier delivery branches** (merged): `opentsx/2026-task013-web-workbench` (013.1 to 013.4, PR #161),
  `opentsx/2026-task013-ts-inventory` (013.8, PR #165), `opentsx/2026-task013-vector-sink` (013.10, PR #166),
  `opentsx/2026-task013-vector-runtime` (013.11, PR #168), `opentsx/2026-task013-secure-dag-runs` (013.12, PR #172),
  `opentsx/2026-task013-ground-truth` (013.13, PR #173), `opentsx/2026-task013-broker-patience` (013.14, PR #175),
  `opentsx/2026-task013-vector-ops` (013.15 and 013.16, PR #177),
  `opentsx/2026-task013-processor-resilience` (013.17 to 013.19, PR #178),
  `opentsx/2026-task013-side-tabs` (013.20, PR #179),
  `opentsx/2026-task013-composer-page` (013.21, PR #180).
  `opentsx/2026-task013-vector-processor` (013.15, PR #176) was merged into its base branch instead of into
  `master`; its content comes with PR #177.

## Objective
The Swing workbench (`org.opentsx.app.bucketanalyser`: `MacroTrackerFrame`, `TSOperationControlerPanel`,
`MultiChart`, `PFDRecorder`) gets a web variant in the React frontend (`opentsx-saas-frontend`, route
`/workbench`). You load a bucket, apply operations, see every result immediately on an oscilloscope screen and
build the processing graph along the way. The graph is handed over as a Smart Topic definition (`opentsx.dag.v1`, see
[11](../docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md)).
This continues TASK-005 (Processing Flow Descriptor) in the browser.

## Analysis of the Swing workbench

| Swing | Task | Web |
|-------|---------|-----|
| `MacroTrackerFrame` (JTree, tabs Properties / Code / Preview / PFD) | Track of the transformations, code export | Track tree on the left, inspector on the right (Properties, Code, Flow (JSON), DAG (YAML), Smart Topic) |
| `Track`, `SourceTSB`, `Transform`, `TSBucketTransformation` | Nodes: source and derived buckets, name = prefix + source name | `workbench/flow.ts` (`FlowNode`, same naming rule) |
| `TSOperationControlerPanel` (tabs with buttons, pastel colors per tab) | Operation on all series of the bucket, opens a new chart, reports the transformation to the track | Operation bar under the screen, same tabs, colors and button texts |
| `MultiChart` (JFreeChart dialog per result) | Display | One oscilloscope screen for the selected node |
| `PFDRecorder` | Linear list of steps as JSON | Tab Flow (JSON) for the path up to the selected node |
| `MacroRecorder` (sine mix, track EXP2) | Example data | Button "Load sample bucket" (same parameters, noise with a fixed seed) |

Observations on the Swing code:
* Every operation creates a new window. How the results relate is visible only in the tree.
* The track is a tree: every derived bucket has exactly one source.
* The buttons in the tab "TS-Model" (AR, MA, ARMA, ARIMA, SARIMA, CARIMA) have no handler; the tab
  "Multi-variate Analysis" is empty.
* The Java export always writes `parameter` as `null`; the parameters are only in the operation name.

## Implemented (as of 2026-10-12)

* **Load a bucket:** sample bucket, file (CSV/TSV wide: time column plus series; long: `series_id,x,y`;
  episode JSON in the form of `GET /v1/episodes`), episodes from the retrieval server through the dev proxy
  `/retrieval`. A file also loads by dropping it on the screen.
* **Operations** (port of the Java methods, with tests): Shuffle, NORM max=1, NORM std=1 avg=0, Log, Returns,
  Diffs, Profile, FFT, Histogram per series, Histogram cross-cut, CDF, ENTROPY, Auto-Correlation, DFA, Sigma
  Band. In addition, filter and conversion for the stream: Select series, Plausible range, Convert unit, Resample,
  Moving average, Window statistics, Segment at threshold.
* **Oscilloscope:** grid 10 x 8, time base and vertical scale in 1-2-5 steps, position, channels
  on/off, one scale for all or per channel, cursors (dX, 1/dX, dY, dY/dX), trigger with level and edge,
  jump to the next event, measurements in the visible window (Pk-Pk, Max, Min, Mean, RMS, Freq), Run/Stop
  as a pass through the recording, Auto set. Mouse wheel, dragging, double-click and keyboard.
* **The trigger becomes a segmenter:** "Segment at this trigger" creates a node `segment.threshold` with the
  level and edge of the trigger.
* **Graph:** React Flow, nodes in the tab colors. Dragging a connection onto a node changes its
  input (cycles are refused). The parameters of a node can be changed afterwards; everything after it
  is computed again.
* **Smart Topic:** per node "Publish as Smart Topic" with a topic name
  (`tsx.<domain>.smart.<name>.metrics.v2`). Export as `opentsx.dag.v1` (only the published branches),
  check (no sink, duplicate topic, operator not in the stream catalog), plus PFD JSON and Java code.

## Limits (deliberate, please do not skip)

* **No deployment from the user interface.** The repository has no runtime for `opentsx.dag.v1`: the
  Go processor from document 11 is a proposal. The workbench therefore hands over a file or saves
  the flow in the account and does not claim a rollout. `POST /flows/{id}/execute` passes the definition on
  to Flink (`DynamicTopologyJob`) and answers with 502 when there is no Flink cluster; a workbench flow is not a PFD.
* **Operation only in a trusted network.** The NodePort speaks HTTP without TLS; credentials and tokens cross the
  network in plain text. There is no token refresh in the frontend: sign in again after 30 minutes. The
  workbench stays open and does not lose the flow.
* **Backend leftovers:** The routes for organizations, teams, invitations and billing in `app/main.py` are
  still placeholders. The plan limit `max_flows` is not checked. There are no backend tests in
  CI; checks run with `opentsx-saas-backend/smoke-flows.sh` against a running instance.
* **Catalog:** Only `resample`, `dq.checks` (Plausible range), `stats.welford-ddsketch` (Window statistics)
  and `segment.threshold` are in the stream catalog (11, section 6). All analysis operations (FFT, DFA,
  ...) run only in the workbench; the check reports this at the sink.
* **One input per node.** Nodes with several inputs (`score.onnx` with `in: [ep, stats]`) are missing.
* **Not ported:** MF-DFA, test for normality (Shapiro-Wilk), `TSBucket` SequenceFiles
  (`.tsb.vec.seq`) as input.
* **Differences from the Java code:** Shuffle draws from Mulberry32 with a seed; the Java code
  (`TimeSeriesObject.shuffleYValues`, `shuffleXValues`) draws from `RNGWrapper`, so the order for the same seed
  differs; entropy with its own binning (16 bins over the value range); DFA exponent as the slope over all scales
  instead of over `fitMIN`/`fitMAX`; autocorrelation up to at most 1000 lags.
* The route `/workbench` is reachable without signing in, because everything computes in the browser. Saving
  needs an account.

## Sub-Tasks

- [x] 013.1 Analysis of the Swing workbench
- [x] 013.2 Operator library, loader, flow model, exports, with tests (`src/workbench/workbench.test.ts`)
- [x] 013.3 Oscilloscope, track tree, operation bar, graph, inspector
- [x] 013.3a Delivery: images `saas-frontend` and `saas-backend` (`deploy/docker/`), k3s component `saas`
      (`SAAS=true`: frontend on NodePort 30311, backend, PostgreSQL with PVC, secret `opentsx-saas-secrets`)
- [x] 013.4 Backend: flows per user in the database (`app/api/flows.py`: create, read, update, delete,
      executions); the workbench saves (`Save flow`) and opens (`/workbench?flow=<id>`, dashboard) the
      definition `opentsx.workbench.v1` (node list plus rendered `opentsx.dag.v1`)
- [x] 013.8 TS inventory: select series of the Smart Topics by name (as of 2026-10-13)
      - The retrieval server reads `tsx.<domain>.episodes.v2` from KafScale (`--kafscale`, class `EpisodeLogTail`
        in `opentsx-kafka-v2`, `KafScaleFeed` in `opentsx-retrieval`) and follows the topic
      - `GET /v1/inventory` (`opentsx.ts-inventory.v1`): per series size, value range, labels of the episodes
        (scenario, seed, catalog, IEC reference) and ground truth (`gt.operation`, `gt.sensor_fault`) as time
        ranges; plus the scenario file and the channel catalog when the server gets them (`--scenario`,
        `--catalog`)
      - Workbench: dialog "TS Inventory" (`src/workbench/InventoryDialog.tsx`, logic `inventory.ts`): filter by
        tag, channel, scenario event and text; choose time window and resolution; the selection becomes the
        bucket, and the domain and source topic of the flow follow the inventory
      - k3s: `RETRIEVAL_SOURCE=kafscale` (component `retrieval-kafscale`)
- [x] 013.10 Vector measuring points (as of 2026-10-13): operator `align.vector` with several inputs and the
      message `opentsx.vector.v1` (draft in 11, section 5a, JSON schema under `docs/datamodel-2026/schemas/`)
      - Flow model: a node can have further inputs (`FlowNode.inputs`); evaluation, cycle check,
        saving and exports follow all inputs
      - Workbench: "Align to vector" in the tab "Filter and conversion"; inputs in the tab Properties or by a
        connection in the graph (dashed); preview of the first measuring points as JSON; sink topic
        `tsx.<domain>.smart.<name>.vectors.v1`
- [x] 013.11 Write vector topics and read them back (as of 2026-10-13)
      - Java operator `VectorAligner` (`opentsx-retrieval`), the same test vectors as the workbench
        (`docs/datamodel-2026/testvectors/align-vector.v1.json`)
      - Retrieval server: `POST /v1/dags/run` (only with `--kafscale` and `--dag-runs true`) computes the vector
        sinks of a DAG once over the held episodes and writes `tsx.<domain>.smart.<name>.vectors.v1`
      - The server reads the vector topics of the domain; their components appear as series
        `smart.<name>.<component>` with the tag `vector` in the TS inventory
      - Workbench: the DAG names the loaded series (`source.series`); the tab "Smart Topic" has "Run once and
        write to KafScale" with the result or the reason for refusal
      - k3s: `RETRIEVAL_DAG_RUNS=true`
- [x] 013.12 Secure the write path and put tests into CI (as of 2026-10-13)
      - Retrieval server: runs only with a run token (`OPENTSX_DAG_RUN_TOKEN`, header `X-OpenTSx-Run-Token`)
      - SaaS backend: `POST /api/v1/dags/run` checks the account and passes the DAG on with the token;
        `DAG_RUN_ROLE` sets who may write (default: administrators only)
      - Frontend image: `/retrieval/v1/dags/` is blocked; the workbench calls the backend
      - A run fails when the broker does not acknowledge every measuring point (before, this went unnoticed); a
        vector topic that cannot be read no longer holds up the other topics
      - CI: jobs "SaaS frontend" (types, lint, tests, build) and "SaaS backend" (pytest)
- [x] 013.14 Patience in the broker window (as of 2026-10-13): after a slow write, KafScale refuses every read and
      every write for about a minute. Readers now wait this out (`org.opentsx.kafka.Patience`, in
      `LogReader`, `StatsProcessor`, `SeriesStatsLog`), and all writers retry for up to three minutes
      (`KafScaleClients.producerProps`). Before, the stats processor exited because of this (15 restarts in 17 hours on
      the lab cluster).
- [x] 013.13 Ground truth on the oscilloscope (as of 2026-10-13)
      - The scenario events of the loaded series travel as marks with the bucket (`Bucket.marks`); operators
        that keep the time axis pass them on, spectra and histograms have none
      - The screen draws them as bands (operating event yellow, sensor fault red) with names at the bottom
        edge; the field "Ground truth" switches them on and off and jumps to an event
      - "Segment at threshold" counts how many threshold crossings fall into an event
- [x] 013.15 Vector processor: continuous operation (as of 2026-10-13)
      - `VectorStream`: measuring points as soon as they are final; the same result as the one-off run; state for
        the restart
      - `VectorProcessor`: deployments from the control topic `tsx.<domain>.dags.v1`, one runner per sink with a
        checkpoint, status to `tsx.<domain>.dag-status.v1`; command `opentsx vectors` (run, deploy, stop)
      - Rules in 11, section 5b, decision D39
- [x] 013.16 Deliver and operate the vector processor (as of 2026-10-13)
      - k3s component `vector-processor` (`VECTOR_PROCESSOR=true`): one pod `opentsx-vectors`, state on a
        volume
      - Retrieval server: `POST /v1/dags/deploy` and `/stop` write into the control topic; `GET /v1/dags` shows
        what is requested and what the processor reports. The one-off run (`/v1/dags/run`) was dropped
      - Backend: `POST /api/v1/dags/deploy`, `/stop` (administrators only)
      - Workbench: "Keep running on KafScale" in the tab "Smart Topic" with Deploy, Stop and the state per sink;
        parameter `lateness` on the vector node
- [x] 013.17 Link between workbench and processor, made reliable and visible (as of 2026-10-13)
      - Processor: one producer per runner and one for the status; waiting for the acknowledgement does not
        block the loop; a producer without an answer is discarded and replaced. Before, the first write into
        a new sink topic stopped the whole processor (fault in the KafScale proxy, KOI-2026-10-13-02)
      - Workbench: list "what a deployment needs" with ready or missing per step, and a button that makes a
        published vector from the loaded series
- [x] 013.18 Test mode for sign-in (as of 2026-10-13): `SAAS_TEST_LOGIN=true` lets `admin` with the password
      `admin` in as the first administrator; the login page shows this. The stored password stays
      unchanged. Off by default; when it is on, everyone who reaches the page is an administrator
- [x] 013.19 "OpenTSx Flow Composer": select and manage saved flows (as of 2026-10-13)
      - Selection window on the pattern of the TS inventory (`FlowComposer.tsx`, `flows.ts`): filter by text,
        domain, status and kind, preview with source, buckets, Smart Topics and state in the processor
      - Open in the editor (the workbench), several too: the first flow here, the others in their own tabs. An
        opened flow loads the series it was built on over their whole time range; the time window of the
        original selection is not saved
      - Create, rename (name, description), copy, delete (with a confirmation in the window)
      - Activate and pause: sets the status of the flow. If the vector processor can execute the flow,
        activating also deploys it and pausing also stops it (administrators only); otherwise it is only marked,
        and the window says why
      - Deployment bound to the flow id: it is called `<domain>-flow-<id>` instead of `<domain>-<track>-smart`.
        Two flows of the same track no longer share a deployment, and a copy has its own. "Deploy"
        in the editor therefore saves the flow first and sets its status to active, "Stop" to paused. A
        deployed flow cannot be deleted. If another running deployment already writes into the same topic,
        the deployment is refused. The export of an unsaved flow still carries the name of the track;
        deployments with the old name keep running and belong to no flow
- [x] 013.20 Left side with two tabs (as of 2026-10-13): "Input bucket" (the previous tree) and "Smart Topic"
      (moved there from the inspector; the column gets wider for it). Older entries that name the tab in the
      inspector mean this one
- [x] 013.21 The start page is the "OpenTSx Flow Composer" (as of 2026-10-13): the page `/dashboard` shows,
      instead of the tiles, the composer from 013.19 as a whole page, with selection, open in the editor,
      create, rename, copy, delete, activate and pause. The name was meant for this page; 013.19 had only given
      it to the window in the workbench, which stays as a shortcut
      - "Start" and "Stop" for the Smart Topic of a flow, in every row, in the details and for the selection;
        they replace "Activate" and "Pause". A flow that the processor cannot execute shows the list
        of what is missing, and is no longer only marked as active
- [ ] 013.5 Runtime for `opentsx.dag.v1` beyond vectors (11, D25 to D27): operators before the vector
      (`resample`, `dq.checks`), further sink kinds
- [ ] 013.6 Context topics (inputs from other topics), `score.onnx`, `train.trigger`; nodes with several
      inputs have existed since 013.10
- [ ] 013.7 Port MF-DFA and the normality test; parity vectors Java against TypeScript
- [ ] 013.9 Write the result of a single node as episodes into a
      Smart Topic (`tsx.<domain>.smart.<name>.metrics.v2`); bind the asset register `opentsx.inventory.v1` (12)
      to the TS inventory through `series`. For vectors the loop has been closed since 013.11.

## Limits of the TS inventory

* **Read only.** The workbench fetches series from the topic. It writes nothing to KafScale; "apply as a Smart
  Topic" stays export and saving until 013.5 and 013.9 are implemented.
* **The inventory is derived.** The topic `tsx.<domain>.series.v2` is planned in the layout, but no
  writer fills it. The inventory is therefore built from the episodes themselves.
* **In memory.** The server holds the episodes in the heap, at most `--max-episodes` (default 20000). With more,
  the inventory reports `truncated`. The lake profile (Iceberg) is meant for larger data volumes.
* **Ground truth in episode steps.** The marks come from the labels of the episodes, so they sit on the grid of
  the episode length (10 minutes in the demo scenario). An event of 15 minutes shows as 20 minutes.
* **No access protection.** `/retrieval/` is reachable without signing in, as before.

## Limits of the vector measuring points

* **One processor, one restart per change.** The vector processor keeps a sink up to date continuously.
  A changed DAG starts from the beginning and appends everything again; on reading, the later measuring point
  counts. Only one processor may run per domain, and nothing prevents a second one.
* **A series that went silent stops its vector.** A tick is written only when every named series has
  reached it. The state shows what it is waiting for; there is no idle timeout.
* **Only vectors directly on the source.** If another operator comes before the vector (normalization, smoothing,
  resampling), the DAG is refused and the node is named. Such flows are still computed only by the workbench.
* **Deploying only with an account, reading without.** Since 013.12 writing goes through the backend and is
  limited to administrators (`SAAS_DAG_RUN_ROLE=superuser`). With `user` every account may write; because
  anyone can register, that is open in practice. Reading through `/retrieval/` still needs no account.
  The run token protects only as long as the retrieval server is not made reachable without a token in another
  way (in the cluster its port is open to all pods; there is no NetworkPolicy).
* **No all or nothing.** If the broker acknowledges only part of the measuring points, the run fails, and the
  acknowledged part stays in the topic. A new run writes everything again.
* **One source bucket.** All inputs come from the one loaded bucket. Vectors across several topics are
  not covered.
* **Only time axes.** FFT, histogram, DFA and similar nodes are not components; the operator refuses them.
* **The format is JSON.** An Avro version and a quality byte per component are open (11, section 11).

## Check

```bash
cd opentsx-saas-frontend
npm ci
npx tsc --noEmit && npx vitest run && npm run build
npm run dev          # http://localhost:3000/workbench
```

TS inventory against a broker (reads only, without a consumer group):

```bash
mvn -q -pl opentsx-kafka-v2,opentsx-retrieval -am test
bin/opentsx retrieval-server --port 8080 --kafscale <host:port> --domain wind \
    --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
    --catalog opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml
curl -s localhost:8080/v1/inventory | head -c 400
```

## Notes
* 2026-10-12: First version. Checked in the browser (headless Chrome): loading a bucket, operations, FFT peaks
  of the sample bucket at 2, 6, 12 and 24 Hz, trigger, cursors, DAG export, no console errors.
* 2026-10-12: Rolled out on the lab cluster (namespace `opentsx`, deployment and NodePort service
  `opentsx-saas-frontend`, image `opentsx/saas-frontend:3.0.0-task013`, built from the delivery worktree before
  the merge). Only the frontend; the SaaS backend does not run there. Checked: the page loads, "Load episodes"
  fetches episodes through `/retrieval/` from the retrieval server in the cluster, DFA computes on them, no console
  errors. Teardown: `kubectl -n opentsx delete deploy,svc opentsx-saas-frontend`. After the merge, take the
  regular path (`20-configure.sh SAAS_FRONTEND=true`, tag `3.0.0-local`).
* 2026-10-12, backend: The first real run against PostgreSQL found three old bugs, which are fixed.
  (1) Registration aborted with `MissingGreenlet` (`user.organizations.append` in the async context).
  (2) Every call with a token gave 401, because the token subject was a number and python-jose requires a
  string on reading. (3) A refresh token was accepted as an access token. In addition, the organization slug
  is now unique (two addresses with the same part before the @ collided).
* 2026-10-12, rollout on the lab cluster with backend and database (images `3.0.0-task013-3`, from the
  delivery worktree, state of this commit). Checked: `smoke-flows.sh http://<lab-host>:30311` (26 checks
  green, among them access to someone else's flows refused) and a browser session: register, build a flow,
  mark a Smart Topic, save, open in the dashboard, restore after a reload, compute on a new bucket;
  no console errors. The check left test accounts behind (`smoke-...@example.com`,
  `e2e-...@example.com`). Teardown (also deletes the database):
  `kubectl -n opentsx delete deploy,svc opentsx-saas-frontend opentsx-saas-backend opentsx-saas-db`, then
  `kubectl -n opentsx delete pvc opentsx-saas-db` and `kubectl -n opentsx delete secret opentsx-saas-secrets`.
  This supersedes the earlier entry on the rollout of the frontend only.
* 2026-10-13: TS inventory (013.8). Checked against KafScale on the lab cluster: 100 series, 3600 episodes,
  2,160,000 measured values from `tsx.wind.episodes.v2`; the marks match `ostsee-demo.yaml` (for example STUCK
  at the anemometer of T05, 01:30 to 01:50). In the browser: choose the event, load the series, the stuck
  measurement shows as a flat line at 4.16 m/s on the screen; ten power series loaded with PT1M; no console
  errors. Rolled out on the lab cluster: `opentsx-retrieval` now reads the topic (image
  `opentsx/tools:3.0.0-task013-4`, before that the demo store), frontend `3.0.0-task013-4`. The state before the
  change is on the lab host under `~/opentsx-builds/task013/retrieval-before-task013-4.yaml`.
* 2026-10-13: Vector measuring points (013.10). Checked in the browser with the data of the lab cluster: wind
  speed and power of T05 loaded, standardized, aligned with `key: turbine` and a 60 s clock into a vector of
  four components: 360 measuring points, no missing values, preview and DAG export are correct, no console errors.
* 2026-10-13: Loop for vectors closed (013.11). On the lab cluster in the browser: four channels of all ten
  turbines loaded from the inventory, aligned with `key: turbine` and a 60 s clock, published as
  `tsx.wind.smart.turbine-state.vectors.v1` and written: 3600 measuring points, 10 keys, 15
  missing values (the gap of T01). The topic appears in the inventory, and the four components of T05 can be
  loaded. The end offset of the topic is 7200, because the run ran twice (the second time after the fix of the
  component order). Retrieval server `opentsx/tools:3.0.0-task013-8` with `--dag-runs true`, frontend
  `3.0.0-task013-9`.
* 2026-10-13: Safeguards (013.12) rolled out on the lab cluster and checked: anonymously and with a normal account
  the run is refused, and the direct path through `/retrieval/v1/dags/` answers with 403. On the first run as an
  administrator (08:46 UTC) the broker refused writes and reads for a good minute with REQUEST_TIMED_OUT: after a
  single slow write, KafScale rates its object store as "degraded" for 60 seconds and then refuses everything
  (finding KOI-2026-10-13-01 in the KafScale ops insights). Of 3600 measuring points, 280 arrived; the
  partition is intact. As a result, the run now retries writes for up to three minutes with pauses and
  fails when not everything is acknowledged.
* 2026-10-13: Ground truth on the screen (013.13). In the browser with the data of the lab cluster: the anemometers
  of T05 and T07 loaded over six hours, the bands CURTAILMENT (01:00 to 01:40) and STUCK T05 (01:30 to 01:50)
  are in the right place; after the jump to STUCK, the flat stretch of T05 lies in the red band while T07 keeps
  running. A derived bucket (Diffs) keeps the marks, the FFT has none. No console errors.
  The marks are as fine as the episodes are long (10 minutes), so they are approximations of the event.
* 2026-10-13: Vector processor (013.15) run locally against the lab broker: DAG `wind-exp2-smart` deployed through
  the control topic; the runner wrote 3600 measuring points to `tsx.wind.smart.turbine-state.vectors.v1`
  (end offset 11080 to 14680), stored its checkpoint at the end of all six partitions and reported its status.
  Then the processor was ended and the DAG stopped through the control topic: no processor runs on the broker
  until 013.16 delivers it. No new episodes arrived during the run; following new data is shown by the tests
  (`VectorProcessorTest`), not by this run.
* 2026-10-13: Vector processor delivered on the lab cluster (013.16): pod `opentsx-vectors` with a volume, images
  `3.0.0-task013-17`. Deployed as an administrator through the backend: the entry went into the control topic,
  the processor picked it up after a few seconds and wrote 3600 measuring points (end offset 14680 to 18280), and
  `GET /v1/dags` shows "running, 3600, data up to 06:00". After a restart of the pod the end offset stayed at
  18280: the processor resumes at the checkpoint. A normal account sees the state; Deploy and Stop are blocked
  for it. The DAG stays deployed. No new episodes arrived; following new data is still shown only by tests.
* 2026-10-13: Report by the user: "The link between the workbench and the DAG flow runner does not work." Two
  findings. First, the Deploy button stayed disabled for obvious flows (sample bucket, published operator instead
  of a vector), and the page named only the first obstacle. Second, a real bug: the first deployment into a new
  sink topic through the user interface stopped the processor. It wrote 20 measuring points, got an answer from
  the KafScale proxy that the client cannot read, and sent nothing after that, not even a status; a restart of
  the pod solved it. Cause measured and reproduced (KOI-2026-10-13-02): a mixed produce request with topics that
  have an owner and topics that do not. After the fix (013.17, images `3.0.0-task013-19`), checked through the
  user interface as an administrator: load series, "Create a vector of the loaded series", Deploy into a new
  topic; after 15 s "running", 360 measuring points in the topic, no error in the processor log.
