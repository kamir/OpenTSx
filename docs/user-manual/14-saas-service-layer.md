# 14. SaaS stack and personal service layer

> **Status: experimental.** `opentsx-saas-backend` (FastAPI, PostgreSQL, JWT) and `opentsx-saas-frontend`
> (React/Vite) are not part of the Maven build. CI runs their own jobs ("SaaS frontend": types, lint, tests, build;
> "SaaS backend": pytest). Their own docs are in `opentsx-saas-backend/README.md`, `opentsx-saas-backend/docs/` and
> `opentsx-saas-frontend/README.md`.

## 14.1 What exists today

* **Backend:**
  * users, organizations and teams with JWT login,
  * flows stored per user (`/api/v1/flows`: create, read, update, delete), each a JSON definition with a status,
  * `POST /api/v1/dags/deploy` and `/stop`: hand a flow to the vector processor or stop it (administrators only
    unless configured otherwise),
  * a `FlinkService` that submits processing-flow descriptors as Flink jobs.
* **Frontend:**
  * **OpenTSx Flow Composer** (`/dashboard`, the page after sign-in): the flows of the account. Select a flow, open
    it in the workbench, create, rename, copy and delete flows, and start and stop the Smart Topic of a flow the
    processor can run.
  * **Workbench** (`/workbench`): load series from the TS inventory, a file or a sample, apply time series
    operators, inspect every result on an oscilloscope view (time base, cursors, trigger, measurements,
    ground-truth bands), build the processing flow as a graph, publish a node as a Smart Topic and deploy it. The
    limits are in [`EVOLUTION/TASK-013-web-workbench.md`](../../EVOLUTION/TASK-013-web-workbench.md): of all
    operators, the processor runs only the alignment of series to a common clock.
  * **Systems** (`/systems`): a shell around one system with four places. "System": the atoms of its plan and what
    feeds each input. "Signals": the signals the system expects, each with its expectation, origin, state and next
    step; "Prepare" opens the raw series of a signal in the workbench with the expected range on the screen.
    "Flows": the Flow Composer for the flows of the system. "Models": which signals are ready for a model. The
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
| `Flow` (JSON definition, owner, status) | **smart series specification**: the versioned YAML of a Smart Topic ([design](../datamodel-2026/05-SMART-TOPICS.md)), owned by a user or team |
| `FlowExecution` | training runs and deployments (MLflow run ids, champion version, processor status) |
| `FlinkService` | a general **runner**: statistics processor, Smart Topic scoring processor, Python training job |
| dashboard | "my series": smart metrics, model quality and drift, data-quality results, prediction with or without context |
| users / teams | ownership of topic prefixes (`tsx.<domain>`), Iceberg namespaces and OpenMetadata domains |

A personal instance is also the natural participant in **federated training**: it trains locally and contributes only
model updates to the global model (the Wayang approach in the Smart Topic design).

The same control plane would steer **system reconstruction, simulation and optimization** (phase P8). It would
connect sources, trigger reconstruction runs, compare simulation scenarios, and submit optimization instances to
classical solvers or, as an experiment, to quantum backends. The concept is in
[06-SYSTEMREKONSTRUKTION-OPTIMIERUNG](../datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md) (German).

It would also host the **digital twin** of an energy system (phase P9): operators review optimizer proposals and
play their own decisions through the twin before releasing them. The concept is in
[07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md) (German).

This is a proposal; it is tracked in the roadmap ([chapter 15](15-roadmap.md)).
