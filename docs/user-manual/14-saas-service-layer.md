# 14. SaaS stack and personal service layer

> **Status: experimental.** `opentsx-saas-backend` (FastAPI, PostgreSQL, JWT) and `opentsx-saas-frontend`
> (React/Vite) are not part of the Maven build. CI runs their own jobs ("SaaS frontend": types, lint, tests, build;
> "SaaS backend": pytest). Their own docs are in `opentsx-saas-backend/README.md`, `opentsx-saas-backend/docs/` and
> `opentsx-saas-frontend/README.md`.

## 14.1 What exists today

* **Backend:**
  * users, organizations and teams with JWT login,
  * flows stored per user (`/api/v1/flows`: create, read, update, delete), each a JSON definition with a status,
  * `POST /api/v1/dags/deploy` and `/stop`: hand a flow to the vector processor or stop it,
  * `POST /api/v1/systems/<system>/generate`: run the demo generator of a signal,
  * `/api/v1/dags/wayang-rust`: capability, bounded/continuous deployment, status and continuous
    stop and explicit fenced replacement endpoints. PostgreSQL
    stores the exact DAG, accepted clock, source cuts and whole-topic ownership; a standalone coordinator reconciles
    the saved plan through a custom Wayang Rust platform. Bounded completion saves its verified result and
    publication epoch before retirement. A lost retirement reply retries that operation without executing again,
  * **Continuous Wayang on Rust** retains complete-topic ownership between increments, saves confirmed positions
    and cumulative counts, and restores worker state after service restart. Transient preparation failures retain
    private candidates for identical retry. **Stop deployment** reconciles dispatched
    work and revokes publication authority before releasing ownership. Explicit resume preserves state; changed-DAG
    restart replays the same whole topics into a fresh output. Stop retains the worker chain; request replacement
    while the deployment is active. Unknown broker outcomes block the switch. See the [continuous guide](../devguide/integration/wayang-rust-continuous.md),
  * a `FlinkService` that submits processing-flow descriptors as Flink jobs.
  * One setting decides who may use the DAG, generator and Wayang routes above: `DAG_RUN_ROLE`. With `superuser`,
    the default, only administrators may. With `user`, every signed-in account may use all three, and sign-up is
    open. The backend says so in a line that starts with `DAG_RUN_ROLE=user:` when it starts.
* **Frontend:**
  * **OpenTSx Flow Composer** (`/dashboard`, the page after sign-in): the flows of the account. Select a flow, open
    it in the workbench, create, rename, copy and delete flows, and start and stop the Smart Topic of a flow the
    processor can run.
  * **Workbench** (`/workbench`): load series from the TS inventory, a file or a sample, apply time series
    operators, inspect every result on an oscilloscope view (time base, cursors, trigger, measurements,
    ground-truth bands), build the processing flow as a graph, publish a node as a Smart Topic and deploy it. The
    series processor supports `dq.checks`, `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`,
    `feature.lag` and `feature.rolling`; vector sinks support `align.vector`.
    Workbench execution and deployment availability are separate. Use the
    [operator lessons](17-learn-operators.md) and the actual runtime contract when choosing an execution surface.
    Select **Legacy** in the Workbench header, then open **Smart Topic** for the explicit Wayang deployment targets.
    **Run bounded with Wayang** is an explicit separate action for plausible-range checks, unit conversion and an
    optional trailing resample on one to three complete topics. HTTP workers prepare privately before activation and
    checkpoint acknowledged output. Exact retry reuses the frozen plan and receipts; partial activation can expose
    at-least-once output. See the [local rollout guide](../devguide/integration/wayang-rust-compose.md).
  * **Systems** (`/systems`): a shell around one system with five places. "System": the atoms of its plan and what
    feeds each input. "Signals": the signals the system expects, each with its expectation, origin, state and next
    step; "Prepare" opens the raw series of a signal in the workbench with the expected range on the screen;
    "Bind", "Simulate" and "Generate" give an open signal a series or a demo generator, kept as a working copy of
    the account.
    "Flows": the Flow Composer for the flows of the system. "Models": the decisions of the system, the signals each
    needs, and whether each analysis is usable, restricted or blocked today, with the reasons. "System" shows the
    plan as a picture above the table: the atoms in the phases of a time step, power, hydrogen and information in
    three colours, what the document assumes about the plant, and a link to the Smart Topics Studio (`/studio/`).
    "Simulate": ask the twin what happens when a decision changes (a control set at a time, a policy, a forecast
    from a time on); the answer is a run shown next to the reference, with the difference in every KPI, and it
    says how far it may be trusted. When an input of either run is generated, no difference is marked better or
    worse, and the reason stands above the table. The
    switch "Guide" shows the seven steps of the method of [chapter 16](16-engineering-method.md) with the state of
    the system in each. The data comes from the retrieval server (`GET /v1/systems`).
  * the earlier visual flow builder (`/flow-builder`); its flows cannot be opened in the workbench.
* How the pieces are meant to be used together is described as a method in
  [chapter 16](16-engineering-method.md).
* **Run it:** `docker compose -f docker-compose.local.yml --profile full up`, or see `opentsx-saas-backend/LOCAL-DEVELOPMENT.md`.

## 14.2 Planned role: personal service layer

The stack is intended to become the **control plane for a user's own smart series**. It would hold no time-series
data itself; the data plane stays OpenTSx (KafScale, Iceberg, retrieval REST, MLflow). The parts of the stack would
take on these roles:

| Today | Planned role |
|-------|--------------|
| `Flow` (JSON definition, owner, status) | **smart series specification**: the versioned YAML of a Smart Topic (`opentsx.dag.v1`, [VECTOR-AND-DAG-SPEC, section 1](../datamodel-2026/VECTOR-AND-DAG-SPEC.md#1-dag-document)), owned by a user or team |
| `FlowExecution` | training runs and deployments (MLflow run ids, champion version, processor status) |
| `FlinkService` | a general **runner**: statistics processor, Smart Topic scoring processor, Python training job |
| dashboard | "my series": smart metrics, model quality and drift, data-quality results, prediction with or without context |
| users / teams | ownership of topic prefixes (`tsx.<domain>`), Iceberg namespaces and OpenMetadata domains |

A personal instance is also the natural participant in **federated training**: it trains locally and contributes only
model updates to the global model.

The same control plane would steer **system reconstruction, simulation and optimization** (phase P8). It would
connect sources, trigger reconstruction runs, compare simulation scenarios, and submit optimization instances to
classical solvers or, as an experiment, to quantum backends.

It would also host the **digital twin** of an energy system (phase P9): operators review optimizer proposals and
play their own decisions through the twin before releasing them. The concept is in
[TWIN-COUPLING-SPEC §9](../datamodel-2026/TWIN-COUPLING-SPEC.md#9-operating-modes).

This is a proposal; it is tracked in the roadmap ([chapter 15](15-roadmap.md)).
