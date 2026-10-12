# 14. SaaS stack and personal service layer

> **Status: experimental.** `opentsx-saas-backend` (FastAPI, PostgreSQL, JWT) and `opentsx-saas-frontend`
> (React/Vite) are not part of the Maven build and are not covered by CI. Their own docs are in
> `opentsx-saas-backend/README.md`, `opentsx-saas-backend/docs/` and `opentsx-saas-frontend/README.md`.

## 14.1 What exists today

* **Backend:**
  * users, organizations and teams with JWT login,
  * a `Flow` model: a JSON definition with owner, status, tags and an execution history (`FlowExecution`),
  * a `FlinkService` that submits processing-flow descriptors as Flink jobs.
* **Frontend:** login, dashboard and a visual flow builder.
* **API:** only the authentication router is wired today. The flow API is not exposed yet.
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
