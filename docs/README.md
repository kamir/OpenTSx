# OpenTSx documentation

| Folder | Content |
|--------|---------|
| [`user-manual/`](user-manual/README.md) | **User manual** (English) for the current version. Start here. |
| [`devops/`](devops/README.md) | **DevOps guide** (English): OpenTSx on k3s against an existing KafScale cluster, with the setup plan, sizing plan, demos and the scripts in [`deploy/k3s/`](../deploy/k3s/README.md) |
| [`roadmap/infographic-briefing.html`](roadmap/infographic-briefing.html) | Briefing (German) for the roadmap infographic |
| [`business/`](business/README.md) | Market research on digital twins, customers and benefits, marketing campaign; campaign briefing as a page in [`business/kampagnen-briefing.html`](business/kampagnen-briefing.html) |
| [`datamodel-2026/`](datamodel-2026/) | Design documents (German; 01, 03, 08, 09, 10, 11, 16, 18 and 19 are English): analysis, target model, gap analysis, normative data-model specification, Smart Topics, system reconstruction & optimization (P8), digital twin (P9), Smart Topics Studio & LabVIEW bridge |
| [`devguide/`](devguide/README.md) | Developer notes: PDF build, processing-flow descriptor schema and validation plan, local infrastructure |

The 2025 documentation (the old GitBook manual, onboarding tracks, devguide guides and the learning-path MANUALS) is in
[`archive/docs/`](../archive/docs/README.md).

Build the PDF with `mvn -Ppdf-manual` (see [devguide/PDF-GENERATION.md](devguide/PDF-GENERATION.md)).
