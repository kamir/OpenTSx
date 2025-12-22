# Validation Plan for Process Flow Descriptors (PFD)

This document outlines the validation strategy for the Process Flow Descriptor (PFD) feature (Task 005), covering unit tests, integration tests, and end-to-end validation.

## 1. Unit Tests

### 1.1 JSON Schema Validation
**Goal**: Ensure the generated JSON matches the official Schema.
*   **Test Class**: `org.opentsx.app.bucketanalyser.PFDSchemaTest`
*   **Test Cases**:
    *   `testValidPFD`: Create a standard PFD and validate it against `processing-flow-descriptor-schema.json`.
    *   `testMissingRequiredFields`: Assert validation failure if `input` or `steps` are missing.
    *   `testInvalidEnum`: Assert failure if an invalid operation (e.g., "UNKNOWN_OP") is used.

### 1.2 Serialization / Deserialization
**Goal**: Ensure `PFDRecorder` produces correct JSON and the Flink Job can parse it.
*   **Test Class**: `org.opentsx.app.bucketanalyser.PFDRecorderTest`
*   **Test Cases**:
    *   `testRecorderSingleton`: Verify state persistence across multiple `addStep` calls.
    *   `testParameterSerialization`: Ensure complex parameters (maps, lists) are correctly serialized.

### 1.3 Flink Dynamic Graph Construction
**Goal**: Ensure `DynamicTopologyJob` creates the correct topology.
*   **Test Class**: `org.opentsx.flink.DynamicTopologyTest` (MiniCluster)
*   **Test Cases**:
    *   `testGraphBuilder`: Mock input JSON and verify that the Flink StreamExecutionEnvironment contains the expected nodes (Source -> Filter -> Map -> Sink).
    *   *Note*: Use `env.getExecutionPlan()` to inspect the JSON plan Flink generates.

## 2. Integration Tests ("The Lab")

### 2.1 Swing UI -> JSON Export
**Goal**: Verify the end-user experience in the legacy app.
*   **Step**: Launch `MacroRecorder2`.
*   **Action**: Load a sample dataset -> Apply "Smooth" -> Apply "DFA".
*   **Assertion**:
    *   Switch to "PFD (JSON)" tab.
    *   JSON text must be present.
    *   JSON must contain "SMOOTH" and "DFA" steps in order.

### 2.2 Flink Job Execution
**Goal**: Verify a real Flink job can run the generated plan.
*   **Step**:
    1.  Save the JSON from 2.1 to `flow.json`.
    2.  Start "The Lab" (`docker-compose.onboarding.yml`).
    3.  Submit job: `flink run -c org.opentsx.flink.DynamicTopologyJob opentsx-flink-core.jar --pfd flow.json`
*   **Assertion**: Job runs, consumes from Input Topic, and produces to Output Topic.

## 3. End-to-End Validation (Task 007)

This will be covered by the future **Validation Framework**.
*   **Scenario**: "Anomaly Detection Pipeline"
*   **Input**: Kafka Topic with synthetic sine wave + spikes.
*   **Flow**: Window(5m) -> Z-Score -> Filter(>3.0).
*   **Output**: Kafka Topic "anomalies".
*   **Success**: The Output topic contains only the spike events.

## 4. Test Data Strategy
*   Use `TSGeneratorFINAL` (existing in Core) to generate deterministic JUnit test data.
*   Use `Welcome.ipynb` (Notebook) to verify Kafka contents for Integration tests.
