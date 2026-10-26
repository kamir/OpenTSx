# EVOLUTION - Task Tracking Folder

## Overview

This folder contains task tracking documents for the evolution and improvement of the OpenTSx project. Each task is documented in detail with requirements, progress tracking, and outcomes.

## Task Naming Convention

Tasks follow the naming pattern: `TASK-XXX-description.md`

Where:
- `XXX` is a three-digit sequential number (001, 002, 003, etc.)
- `description` is a short kebab-case description of the task

## Current Tasks

| Task ID | Title | Status | Created |
|---------|-------|--------|---------|
| TASK-019 | [Planned major upgrades](TASK-019-major-upgrades.md): Avro 1.12.2+, Iceberg 1.11/1.12, JFreeChart 1.5, Confluent 8, JUnit 6, frontend, Flink 2.x | planned | 2026-10-26 |
| TASK-018 | [Analytics, retrieval, storage and data-source extensions](TASK-018-analytics-storage-extensions.md): analytics gaps (P6), Matrix Profile, iSAX, D6, records/s on real S3, vendor exports | planned | 2026-10-26 |
| TASK-017 | [Digital twin of the energy system](TASK-017-digital-twin.md): atoms, wiring plan, layered BSP, three modes, house and boat twins, plant explorer (P9) | in progress | 2026-10-26 |
| TASK-016 | [Smart Topics](TASK-016-smart-topics.md): derived topics, training outside the stream, processor and DevOps (P7) | in progress | 2026-10-26 |
| TASK-015 | [Multivariate analysis](TASK-015-multivariate-analysis.md): pair windows, system reconstruction, simulation and optimization (P8) | planned | 2026-10-26 |
| TASK-013 | [Web workbench](TASK-013-web-workbench.md): Swing bucket analyser in the browser, oscilloscope, flow graph, export as `opentsx.dag.v1` | in progress | 2026-10-12 |
| TASK-012 | [Briefing for the local agent](TASK-012-lokaler-agent.md) (German): repository hygiene, local test installation (k3s, KafScale, images), market history with credentials, private data, Modbus | open | 2026-10-12 |
| TASK-011 | [Datamodel refresher 2026](TASK-011-datamodel-refresher-2026.md): data model v2, KafScale, stores, wind farm, statistics, OpenMetadata, retrieval, Smart Topics | Phases 0 to 3 and 5 done; three items of phase 4 open (WP1 to WP3 of [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md)); phases 6 to 9 and the backlog moved to TASK-015 to TASK-019 and TASK-010 | 2026-10-09 |
| TASK-010 | [Open-source readiness](TASK-010.md): LICENSE, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, CHANGELOG, link checker | partly done (CI, README, link check); repository items moved in from TASK-011 | 2025-12 |
| TASK-005 | [Processing flow descriptor](TASK-005-processing-flow-descriptor.md) (Flink `DynamicTopologyJob`) | concept valid, not continued | 2025-12-21 |

## Archived Tasks

`archive/` holds the completed or superseded tasks: TASK-001 to TASK-004, TASK-005-PROGRESS, TASK-006, TASK-007,
`T-B-DECIDED.md`, and the `DECISIONS/` folder. TASK-002/003/006/007 were not continued. The 2026 plan (TASK-011)
supersedes them. The Flink upgrade to 1.20.5 is done (#147); Flink 2.x is part of TASK-019.

## Task Lifecycle

Tasks go through the following states:

1. **Planned** - Identified but not yet started
2. **In Progress** - Actively being worked on
3. **Blocked** - Waiting on dependencies or external factors
4. **Review** - Completed and under review
5. **Completed** - Finished and verified
6. **Archived** - Historical reference

## Task Template

When creating a new task, use this structure:

```markdown
# TASK-XXX: Task Title

## Task Metadata
- **Task ID**: TASK-XXX-description
- **Created**: YYYY-MM-DD
- **Status**: Status
- **Priority**: Priority
- **Assigned To**: Name/Team
- **Epic**: Epic Name

## Objective
[Clear description of what this task aims to achieve]

## Requirements
[Detailed list of requirements]

## Sub-Tasks
[Breakdown of work items]

## Dependencies
[What this task depends on]

## Success Criteria
[How to measure completion]

## Timeline
[Estimated timeline]

## Notes
[Additional context]
```

## Related Documentation

- [Main README](../README.md)
- [User manual](../docs/user-manual/README.md)
- [Design documents 2026](../docs/datamodel-2026/) (German)
- Archived 2025 material: [onboarding tracks, guides, decisions](../archive/docs/README.md), [archived tasks](archive/)

## Contributing

When updating tasks:
1. Update the task file directly
2. Update the status in this README
3. Add notes with date stamps
4. Link related tasks

## Metrics

We track:
- Task completion rate
- Time to completion
- Blockers and dependencies
- Resource allocation
- Success criteria achievement

---

## Recent Achievements (2025-12-21)

### TASK-PYTHON: Python Feature Parity ✅

**Completed**: 2025-12-21

**Major Deliverables:**
1. **Complete MFDFA Implementation** (396 lines)
   - Full q-order fluctuation functions
   - Generalized Hurst exponent h(q)
   - Mass exponent τ(q)
   - Singularity spectrum f(α)
   - 4-panel visualization

2. **Complete Event Synchronization** (362 lines)
   - Directional synchronization (Q, q_xy, q_yx)
   - Adaptive τ_max calculation
   - Lead-lag relationship analysis
   - Event visualization

3. **Complete RIS Implementation** (389 lines)
   - Return interval statistics
   - Risk parameter R = σ/μ
   - Stretched exponential fitting
   - Survival function calculation
   - 4-panel risk visualization

4. **Python Onboarding Path** (comprehensive 8-episode curriculum)
   - Installation & setup
   - TimeSeriesObject fundamentals
   - DFA, MFDFA, Event Sync, RIS tutorials
   - Java-Python interoperability
   - Production deployment patterns

5. **Comprehensive Documentation**
   - Feature Comparison (700 lines)
   - Interoperability Guide (945 lines)
   - Implementation Summary
   - Test suite (all passing ✅)

**Feature Parity Status**: 86% complete (5/7 major features)

**Impact**: Python developers can now perform advanced time series analysis with OpenTSx, achieving near-parity with the Java implementation.

---

**Last Updated**: 2025-12-21
**Maintained By**: OpenTSx Core Team
