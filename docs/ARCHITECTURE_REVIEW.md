# OpenTSx Architecture Review & Strategic Assessment
**Date:** 2025-12-21
**Version:** 1.0.0
**Status:** Review Draft

---

## 1. Executive Summary

OpenTSx maintains a dual-layer architecture:
1.  **The "Core" Data Plane**: A mature, high-throughput, distributed time series processing engine based on the Java/Scala ecosystem (Kafka, Flink, Cassandra, OpenTSDB).
2.  **The "SaaS" Control Plane**: A modern, user-centric web platform based on the Python/React ecosystem (FastAPI, React Flow, PostgreSQL).

The architecture is robust and follows best practices for both domains. However, the **integration surface** between the Control Plane (SaaS) and the Data Plane (Core) represents the most critical architectural risk and opportunity. The "Flow Builder" in the SaaS layer implies a capability to orchestrate the heavy lifting in the Core, but the mechanism for this bridging is the primary area requiring definition.

---

## 2. Component Analysis

### 2.1 The Data Plane (OpenTSx Core)
*   **Strengths**:
    *   **Event-Driven**: The use of Kafka as the central nervous system enables true decoupling and replayability.
    *   **Polyglot Persistence**: effectively uses the "right tool for the job" (Cassandra for raw writes, OpenTSDB for metrics, HDFS/S3 for batch).
    *   **Extensive Algorithmic Library**: The `opentsx-core` module provides specialized TS algorithms (DFA, MFDFA) not commonly found in generic tools.
*   **Observations**:
    *   **Complexity**: The stack is heavy. Requires Zookeeper, Kafka, Schema Registry, Cassandra, etc. Onboarding without a pre-baked environment is daunting.
    *   **Technology**: Uses standard Big Data tech (Java 8+, Scala). Solid, but less "agile" than the SaaS layer.

### 2.2 The Control Plane (SaaS Platform)
*   **Strengths**:
    *   **Modern UX**: React Flow and Tailwind CSS provide the "visual excellence" required for adoption.
    *   **Agile Backend**: FastAPI + AsyncPG is a high-performance, modern choice for the API layer.
    *   **Multi-Tenancy**: Built-in from the start (Organizations, Teams).
*   **Observations**:
    *   **State Separation**: It maintains its own state (PostgreSQL) for user metadata, distinct from the time series data.

---

## 3. Integration Gap Analysis (The "Hidden" Architecture)

The documentation describes two distinct worlds. The critical path for the "SaaS Service" involves bridging them:

1.  **Orchestration Bridge**: How does a "Flow" constructed in React translate to a running Flink Job or KStreams Topology?
    *   *Risk*: If the SaaS backend merely stores the flow definition, the Data Plane cannot execute it.
    *   *Recommendation*: Implement a "Topology Compiler" or "Job Submitter" service that translates JSON flow definitions into deployable artifacts (or dynamic topologies).

2.  **Data Query Bridge**: How does the React Frontend visualize data stored in Cassandra/OpenTSDB?
    *   *Risk*: Direct connection from Browser to Cassandra is impossible/insecure. Python Backend must act as a gateway.
    *   *Recommendation*: Ensure the FastAPI backend has high-performance connectors to the Core storage layers to serve the "Visual Flow Builder" with live sample data.

---

## 4. Sharp Review & Recommendations

### 4.1 Onboarding & Documentation
*   **Critique**: The `docs/` folder is rich but fragmented. The "Onboarding Tracks" are excellent concepts but assume a perfectly working local "Big Data" environment.
*   **Action**: Consolidate the "Getting Started" experience. A single `docker-compose` profile that brings up the *minimum* Core + SaaS stack is essential.

### 4.2 Codebase Structure
*   **Critique**: The repository combines the heavy Java modules and the lightweight Python/JS modules. This is a "Monorepo" without Monorepo tooling (like Nx or Bazel).
*   **Action**: Formalize the boundary. Ensure CI/CD pipelines treat the Core and SaaS as loosely coupled deployables.

### 4.3 SaaS Evolution
*   **Critique**: The SaaS platform is currently a "shell" around user management and flow editing. It needs to become a "command center".
*   **Action**: Prioritize the implementation of the **Execution Engine** interface in the SaaS backend to actually drive the Core.

---

## 5. Conclusion

OpenTSx is well-positioned as a "System of Systems". The Core provides the muscle, and the SaaS provides the brain/interface. The immediate architectural priority is defining the **nervous system** that connects them—specifically, the API protocols for job submission and data retrieval between the FastAPI backend and the Kafka/Flink clusters.
