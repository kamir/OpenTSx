# TASK-005: Processing Flow Descriptor (Macro Recorder Evolution)

## Task Metadata
- **Task ID**: TASK-005-processing-flow-descriptor
- **Created**: 2025-12-21
- **Status**: Planned
- **Priority**: High
- **Epic**: SaaS Evolution & Core Integration

## Objective
Evolve the existing "Macro Recorder" concept from a static logging tool into a dynamic **Flow Builder**. This will allow users to define time series processing pipelines interactively (initially in Java Swing, eventually in the SaaS React UI) and export them as platform-agnostic **Processing Flow Descriptors**. These descriptors will then be executed by a generic Flink engine.

## The Concept

We are moving from "Code-First" pipelines to "Design-First" flows.

1.  **Input Definition**:
    -   Connect to a Kafka Topic.
    -   Define a Time Range (creating a bounded "Episode").
2.  **Interactive Design**:
    -   Apply functions (Smooth, Decompose, Filter, Anomaly Detect).
    -   The UI shows immediate feedback on the sample Episode.
3.  **Artifact Generation**:
    -   Instead of just running locally, the tool generates a **Processing Flow Descriptor (PFD)**.
    -   Format: JSON/YAML.
    -   Content: A DAG of operations + Parameters.
4.  **Execution**:
    -   The SaaS Backend submits this PFD to the Flink Cluster.
    -   A generic `DynamicTopologyJob` runs the plan at scale.

## Architecture

### 1. The Processing Flow Descriptor (PFD)
A standardized schema representing the pipeline.

**Example Structure:**
```json
{
  "name": "Sensor Anomaly Detection",
  "input": {
    "type": "KAFKA",
    "topic": "raw-sensor-data",
    "schema": "Observation"
  },
  "steps": [
    {
      "id": "step-1",
      "operation": "WINDOW_AGGREGATE",
      "params": { "type": "TUMBLING", "size": "5m" }
    },
    {
      "id": "step-2",
      "operation": "NORMALIZE",
      "params": { "method": "Z_SCORE" }
    },
    {
      "id": "step-3",
      "operation": "DETECT_ANOMALY",
      "params": { "algorithm": "ESD", "threshold": 3.0 }
    }
  ],
  "output": {
    "type": "KAFKA",
    "topic": "anomalies",
    "schema": "Alert"
  }
}
```

### 2. The Generic Flink Engine (`DynamicTopologyJob`)
A single Flink Job JAR that accepts a PFD as input and constructs the DataStream graph at runtime.

### 3. The UI Evolution
-   **Current**: `MacroTrackerFrame` logs textual transformations.
-   **Future**: A "Flow Designer" that builds the JSON structure as actions are performed.

## Sub-Tasks

- [x] **005.1: Define PFD Schema**: Create the formal JSON Schema specification. (Completed)
- [x] **005.2: Java UI Prototype**: Extend `MacroRecorder` to export the PFD JSON. (Completed)
- [ ] **005.3: Flink Dynamic Job**: Implement the Flink job that parses PFD and executes the pipeline.
- [ ] **005.4: SaaS Integration Plan**: Define how the Python backend stores and submits these descriptors.

## Success Criteria
-   A user can "record" a sequence of operations in the UI.
-   The UI exports a valid JSON descriptor.
-   The Flink cluster runs a job based *solely* on that JSON and produces correct results.
