# OpenTSx documentation

| Folder | Content |
|--------|---------|
| [`user-manual/`](user-manual/README.md) | **User manual** for the current version. Start here. |
| [`devops/`](devops/README.md) | **DevOps guide**: OpenTSx on k3s against an existing KafScale cluster: setup plan, sizing plan, demos, scripts in [`deploy/k3s/`](../deploy/k3s/README.md) |
| [`datamodel-2026/`](datamodel-2026/) | **Normative specifications**: [data model v2](datamodel-2026/04-MODELL-V2-SPEC.md), [Wayang time-series operators](datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md) with its [concept](datamodel-2026/20-WAYANG-TS-OPERATORS.md), [vector and DAG rules](datamodel-2026/VECTOR-AND-DAG-SPEC.md), [system document, signal states and clean series](datamodel-2026/SYSTEM-DOCUMENT-SPEC.md), [decision cards and records](datamodel-2026/DECISION-CARD-SPEC.md), [twin coupling and execution](datamodel-2026/TWIN-COUPLING-SPEC.md), [pair windows and pair methods](datamodel-2026/PAIR-WINDOWS-SPEC.md); JSON schemas in `schemas/`, shared test vectors in `testvectors/`, measurement reports in [`measurements/`](datamodel-2026/measurements/README.md) |
| [`devguide/`](devguide/README.md) | Developer notes: PDF build, processing-flow descriptor schema and validation plan, local infrastructure |
| [`learning/`](learning/) | Operator learning contracts and lesson metadata |
| [`prototypes/`](prototypes/) | Smart Topics Studio (template and generated page) and the plant explorer |

Build the PDF with `mvn -Ppdf-manual` (see [devguide/PDF-GENERATION.md](devguide/PDF-GENERATION.md)).
