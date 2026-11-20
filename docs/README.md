# OpenTSx documentation

| Folder | Content |
|--------|---------|
| [`user-manual/`](user-manual/README.md) | **User manual** (English) for the current version. Start here. |
| [`devops/`](devops/README.md) | **DevOps guide** (English, German summary): OpenTSx on k3s against an existing KafScale cluster — setup plan, sizing plan, demos, scripts in [`deploy/k3s/`](../deploy/k3s/README.md) |
| [`roadmap/infographic-briefing.html`](roadmap/infographic-briefing.html) | Briefing (German) for the roadmap infographic |
| [`research/01-RELATED-WORK-AND-POSITIONING.md`](research/01-RELATED-WORK-AND-POSITIONING.md) | Related work and positioning for the IEEE paper: 188 candidates in nine clusters (time-series systems, distributed frameworks, libraries, federation, standards, collectors, commercial historians, cloud services, research architectures), comparison matrix, claim check, required evaluation |
| [`research/02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md`](research/02-WAYANG-FPGA-OPERATORS-FOR-SPACE.md) | Study on placing OpenTSx operators on ground, onboard-computer and FPGA tiers through Apache Wayang: Python-to-FPGA toolchains, space-grade FPGAs and onboard computers, in-orbit platform services and storage, onboard logs and space-ground transport, pre-flight test environments, evidence-gated roadmap |
| [`research/03-EARTH-ORBIT-DATA-PRODUCT-ARCHITECTURE.md`](research/03-EARTH-ORBIT-DATA-PRODUCT-ARCHITECTURE.md) | Study on whether earth-plus-orbit data products reuse ground IT service APIs or build orbit-native frameworks: flight evidence, space standards, data-product patterns, analog domains, a two-sided debate with fact checks, six architecture options, decision guide |
| [`business/`](business/README.md) | Market research on digital twins, customers and benefits, marketing campaign (German); campaign briefing as a page in [`business/kampagnen-briefing.html`](business/kampagnen-briefing.html) |
| [`datamodel-2026/`](datamodel-2026/) | Design documents (German): analysis, target model, gap analysis, normative data-model specification, Smart Topics, system reconstruction & optimization (P8), digital twin (P9), Smart Topics Studio & LabVIEW bridge |
| Normative specifications (English) | [data model v2](datamodel-2026/04-MODELL-V2-SPEC.md), [Wayang time-series operators](datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md), [vector and DAG rules](datamodel-2026/VECTOR-AND-DAG-SPEC.md), [system document, signal states and clean series](datamodel-2026/SYSTEM-DOCUMENT-SPEC.md), [decision cards and records](datamodel-2026/DECISION-CARD-SPEC.md), [twin coupling and execution](datamodel-2026/TWIN-COUPLING-SPEC.md), [pair windows and pair methods](datamodel-2026/PAIR-WINDOWS-SPEC.md) |
| [`devguide/`](devguide/README.md) | Developer notes: PDF build, processing-flow descriptor schema and validation plan, local infrastructure |
| [`DOCUMENTATION-INVENTORY.md`](DOCUMENTATION-INVENTORY.md) | What was kept, updated, archived or added in the 2026 documentation refresh |

The 2025 documentation (the old GitBook manual, onboarding tracks, devguide guides and the learning-path MANUALS) is in
[`archive/docs/`](../archive/docs/README.md).

Build the PDF with `mvn -Ppdf-manual` (see [devguide/PDF-GENERATION.md](devguide/PDF-GENERATION.md)).
