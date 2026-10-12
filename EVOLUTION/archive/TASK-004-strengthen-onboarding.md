# TASK-004: Strengthen Onboarding Tracks

## Task Metadata
- **Task ID**: TASK-004-strengthen-onboarding
- **Created**: 2025-12-21
- **Status**: Completed
- **Priority**: High
- **Assigned To**: OpenTSx Core Team
- **Epic**: Onboarding & Adoption

## Objective
Review, validate, and strengthen the three existing onboarding tracks (Flink, SWE, Domain Expert) to ensure they are bug-free, executable, and bridge the gap between the "Core" technology and the new SaaS platform.

## Current State Review

### 1. Flink Track (`docs/onboarding/ONBOARDING-PATH-Flink.md`)
- **Strengths**: Comprehensive covering of the Flink+OpenTSx architecture. Good progression from theory to "production".
- **Weaknesses**:
    - Assumes the `opentsx-flink-core` jar is easily built.
    - Code examples need to be verified against the actual `opentsx-flink-core` module API.
    - "Episode 6" (Kafka Integration) requires a complex local setup (Schema Registry, Kafka, Flink) which is error-prone manually.

### 2. SWE Track (`docs/onboarding/ONBOARDING-PATH-SWE.md`)
- **Strengths**: Good breadth (Spark, KStreams, Storage).
- **Weaknesses**:
    - Relies on shell scripts (`bin/010_build.sh`, `bin/120_run_demo.sh`) which need to be verified for robustness across OS versions (specifically macOS vs Linux).
    - Lacks a "Verification" step for the environment before starting.

### 3. Domain Expert Track (`docs/onboarding/ONBOARDING-PATH-TSx.md`)
- **Strengths**: Excellent "Rosetta Stone" approach mapping R/Python to OpenTSx.
- **Weaknesses**:
    - The "Hands-On" sections involve writing significant Java code, which might be a barrier for pure R/Python users.
    - Needs "Fill in the blank" style exercises or a pre-configured Jupyter/Notebook environment (w/ BeakerX or similar) to lower the barrier.

## Requirements for Improvement

### 1. Unified Environment ("The Lab")
- Create a single `docker-compose.onboarding.yml` that supports all three tracks.
- **Components**: Kafka, Zookeeper, Schema Registry, Kudu (optional), OpenTSDB, Flink JobManager/TaskManager, Jupyter Lab (for TSx track).

### 2. Content Hardening
- **Validation**: Manually execute every command in the tracks on a clean machine.
- **Correction**: Fix any broken paths, deprecated API calls, or missing dependencies.
- **Bridge**: Add a section to each track on how this connects to the **SaaS Platform**. (e.g., "How to view your Flink job results in the SaaS Dashboard").

### 3. Interactive Notebooks (for TSx Track)
- Convert the "Domain Expert" track exercises into **Polyglot Notebooks** (VS Code) or Jupyter Notebooks.
- Allow users to run the Java/Scala code chunks immediately without setting up a full IDE.

## Sub-Tasks

- [x] **004.1**: Create `docker-compose.onboarding.yml` and verify it runs on standard hardware (16GB RAM). (Completed)
- [x] **004.2**: Audit `ONBOARDING-PATH-Flink.md` code snippets against `opentsx-flink-core` source. (Completed)
- [x] **004.3**: Audit `ONBOARDING-PATH-SWE.md` scripts (`bin/*.sh`). (Completed)
- [x] **004.4**: Create a "Welcome to OpenTSx" Polyglot Notebook for the TSx track. (Completed)
- [x] **004.5**: Update documentation to reference "The Lab" environment instead of manual installation. (Completed)

## Success Criteria
- A user can complete the "Foundation Phase" of any track in < 2 hours without hitting environment errors.
- All code snippets in documentation are valid and compile.

## Timeline
- **Week 1**: Environment setup (Docker).
- **Week 2**: Script & Code validation.
- **Week 3**: Documentation update & Notebook creation.
