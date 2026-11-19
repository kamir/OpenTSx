# 14. SaaS stack and personal service layer

> **Status: experimental.** `opentsx-saas-backend` (FastAPI, PostgreSQL, JWT) and `opentsx-saas-frontend`
> (React/Vite) are not part of the Maven build. CI runs their own jobs ("SaaS frontend": types, lint, tests, build;
> "SaaS backend": pytest). Their own docs are in `opentsx-saas-backend/README.md`, `opentsx-saas-backend/DOCKER-SETUP.md`,
> `opentsx-saas-backend/LOCAL-DEVELOPMENT.md` and `opentsx-saas-frontend/README.md`.

## 14.1 Use the SaaS stack

* **Backend:**
  * users with JWT login (`/api/v1/auth/register`, `/login`, `/me`, `/change-password`). Registration creates one
    organization per user. `ENABLE_SIGNUP=false` closes registration: `POST /api/v1/auth/register` answers 403
    `sign-up is closed; ask an administrator of this installation for an account`. The backend has no routes for
    organizations, teams, invitations or billing. It stores accounts with their organization, flows and the working
    copies of systems; DAG deployments and generator runs go to the retrieval server.
  * flows stored per user (`/api/v1/flows`: create, read, update, delete), each a JSON definition with a status.
  * `POST /api/v1/dags/deploy` (body: a DAG as YAML, at most 256 KiB) and `POST /api/v1/dags/stop?name=<dag>` forward
    to the retrieval server in `RETRIEVAL_URL`, with `DAG_RUN_TOKEN` as run token. `DAG_RUN_ROLE` decides who may
    call them: `superuser` (default, administrators only) or `user` (every signed-in account). An account that
    may not call them gets 403 first; without `RETRIEVAL_URL` they answer 503
    `RETRIEVAL_URL: not set; this installation deploys no DAGs` to an account that may.
    `GET /api/v1/dags/capabilities` tells the workbench whether the account may deploy.
  * the working copy of the signal bindings of a system per account (`/api/v1/systems/<system>/bindings`: read,
    replace, delete). `POST /api/v1/systems/<system>/generate` runs the demo generator of a signal through the
    retrieval server, with the same `RETRIEVAL_URL`, run token and `DAG_RUN_ROLE` as a DAG deployment.
  * the backend does not run a flow. A stored flow runs when the workbench or the Flow Composer deploys its DAG
    through `POST /api/v1/dags/deploy`.
  * every error answers `{"error": "<text>", "type": "<type>"}` with the types of the retrieval server
    ([appendix B](appendix-b-rest.md)); `opentsx-saas-backend/README.md` lists the status codes and texts.
  * `TEST_LOGIN=true` lets the name `admin` with the password `admin` sign in as the first administrator (the
    administrator with the lowest id). Use it on a lab network only: everyone who reaches the page is an administrator
    then.
* **Frontend:**
  * **OpenTSx Flow Composer** (`/dashboard`, the page after sign-in): the flows of the account. Select a flow, open
    it in the workbench, create, rename, copy and delete flows, and start and stop the Smart Topic of a flow the
    processor can run. Start, Stop and Delete need the list of deployments of the retrieval server (`GET /v1/dags`).
    While it cannot be read, they stay off, the composer says `Start, Stop and Delete stay off: <reason>.`, where
    `<reason>` is the error of that request, and its header says `how many run is not known`.
  * **Workbench** (`/workbench`): load series from the TS inventory, a file or a sample, apply time series
    operators, inspect every result on an oscilloscope view (time base, cursors, trigger, measurements,
    ground-truth bands), build the processing flow as a graph, publish a node as a Smart Topic and deploy it. The
    inspector shows the flow as Java code ("Code"), as a processing-flow descriptor ("Flow (JSON)") and as a DAG
    ("DAG (YAML)"). "Save flow" stores the flow in the account; `/workbench?flow=<id>` opens a stored flow.
    * The processor runs two kinds of sink. A vector sink runs `align.vector` on source series. A series sink runs a
      single-input chain of `dq.plausible-range` (the DAG node `dq.checks` with the check `PLAUSIBLE_RANGE` only),
      `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`, `feature.lag` and `feature.rolling`.
      Every other operator computes in the workbench only. Every node a flow publishes is the clean signal or an
      `align.vector` node; a flow that publishes any other node is not deployed, and the deploy list names it.
      Publishing a clean signal checks the parameters of every step as the processor does and names the step and the
      parameter it refuses. An emptied or missing `every` of an `align.vector` node, and an emptied `maxAge` with fill
      `as-of`, are refused in the preview and before the deployment.
      [Chapter 17](17-operators.md) lists where each operator runs;
      [Kafka native streaming](../devops/kafka-native-streaming.md) describes the processor.
  * **Systems** (`/systems`): a shell around one system with five places. The data comes from the retrieval server
    (`GET /v1/systems`).
    * "System": the atoms of its plan and what feeds each input. Above the table, the plan as a picture: the atoms
      in the phases of a time step, power, hydrogen and information in three colours, what the document assumes
      about the plant, and a link to the Smart Topics Studio (`/studio/`).
    * "Signals": the signals the system expects, each with its expectation, origin, state and next step. "Prepare"
      opens the raw series of a signal in the workbench with the expected range on the screen. "Bind", "Simulate"
      and "Generate" give an open signal a series or a demo generator, kept as a working copy of the account.
    * "Flows": the Flow Composer for the flows of the system.
    * "Models": the decisions of the system, the signals each needs, and whether each analysis is usable,
      restricted or blocked, with the reasons.
    * "Simulate": ask the twin what happens when a decision changes (a control set at a time, a policy, a forecast
      from a time on). The answer is a run shown next to the reference, with the difference in every KPI, and it
      says how far it may be trusted.
    * The switch "Guide" shows seven steps (System, Signals, Stock, Bind, Prepare, Run, Model) with the state of the
      system in each. They are not the eight steps of [chapter 16](16-engineering-method.md).
  * the visual flow builder (`/flow-builder`). It stores a new flow and cannot open a stored one. The workbench
    cannot open its flows either; the Flow Composer lists them as `flow builder`.
* [Chapter 16](16-engineering-method.md) describes the method that uses these pieces together.

## 14.2 Run the SaaS stack

The full stack runs on k3s: `deploy/k3s/20-configure.sh SAAS=true` adds the frontend on NodePort 30311, the backend
and its PostgreSQL. See [SaaS module](../../deploy/k3s/README.md#saas-module-web-workbench-accounts-stored-flows) in
the k3s guide. The component `saas` gives the backend `RETRIEVAL_URL` (`http://opentsx-retrieval:8080`),
`DAG_RUN_TOKEN`, `DAG_RUN_ROLE` (from `SAAS_DAG_RUN_ROLE`) and `TEST_LOGIN` (from `SAAS_TEST_LOGIN`).

To run the backend alone, follow `opentsx-saas-backend/LOCAL-DEVELOPMENT.md`.

`docker compose -f docker-compose.local.yml --profile full up` starts the backend on 127.0.0.1:8000. It needs
`POSTGRES_PASSWORD`, `SECRET_KEY` (at least 32 characters) and `FIRST_SUPERUSER_PASSWORD` (at least 12 characters)
in the environment or in `.env` next to the file. It does not give a working full stack:

* Its frontend container runs `npm run dev` without `--host 0.0.0.0`. Vite then listens on `localhost` inside the
  container, so port 3000 does not answer from the host.
* The stack has no retrieval server and sets no `RETRIEVAL_URL`, so Systems, the TS inventory and DAG deploy and
  stop do not work in it.

## 14.3 Check the plan for the personal service layer

The personal service layer is a proposal (phase P7e in [chapter 15](15-roadmap.md)). In it, the stack is the
control plane for a user's own smart series. It holds no time-series data; the data plane stays OpenTSx (KafScale,
Iceberg, retrieval REST, MLflow).

| Part of the stack | Proposed role | Status |
|-------------------|---------------|--------|
| `Flow` (JSON definition, owner, status) | **smart series specification**: the versioned YAML of a Smart Topic ([design](../datamodel-2026/05-SMART-TOPICS.md), German), owned by a user or team | proposal (P7e) |
| `FlowExecution` | training runs and deployments (MLflow run ids, champion version, processor status) | proposal (P7e) |
| dashboard | "my series": smart metrics, model quality and drift, data-quality results, prediction with or without context | proposal (P7e) |
| users / teams | ownership of topic prefixes (`tsx.<domain>`), Iceberg namespaces and OpenMetadata domains | proposal (P7e) |

| Role of a personal instance | Status | Design |
|-----------------------------|--------|--------|
| **federated training**: train locally and contribute only model updates to the global model (the Wayang approach) | planned (P7) | [05-SMART-TOPICS](../datamodel-2026/05-SMART-TOPICS.md) (German) |
| steer **system reconstruction, simulation and optimization**: connect sources, trigger reconstruction runs, compare simulation scenarios, submit optimization instances to classical solvers or, as an experiment, to quantum backends | concept (P8) | [06-SYSTEMREKONSTRUKTION-OPTIMIERUNG](../datamodel-2026/06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md) (German) |
| host the **digital twin** of an energy system: operators review optimizer proposals and play their own decisions through the twin before releasing them | proposal (P9); the twin itself is a proof of concept in `opentsx-twin` ([chapter 15](15-roadmap.md)) | [07-DIGITAL-TWIN-BSP](../datamodel-2026/07-DIGITAL-TWIN-BSP.md) (German) |
