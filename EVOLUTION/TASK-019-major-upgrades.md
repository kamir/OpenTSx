# TASK-019: Planned major upgrades

## Task Metadata
- **Task ID**: TASK-019-major-upgrades
- **Created**: 2026-10-26
- **Status**: Planned
- **Priority**: Medium
- **Epic**: Dependencies
- **Origin**: split out of [TASK-011](TASK-011-datamodel-refresher-2026.md) on 2026-10-26 (D90, [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md))

## Objective
Carry out the major upgrades deliberately as migrations: Avro 1.12.2+ with `SERIALIZABLE_PACKAGES` and class
allowlisting, Iceberg 1.11/1.12, JFreeChart 1.5, Confluent 8, JUnit 6, networknt json-schema-validator 3 and the
frontend (React 19, TypeScript 7, ESLint 10, Tailwind 4, Vite 8, React Router 7, Plotly 4).

## Major upgrades

Moved from TASK-011 (commit 551815a3), lines 410 to 415.

**Major upgrades (deliberately as a migration; Dependabot ignores majors and the held-back dependencies named below)**
- [ ] Avro 1.12.2+: class allowlisting (`ClassSecurityValidator`) for the v2 model **and** the legacy 2.x classes (`org.opentsx.data.model`, already needed when building records via the builder); legacy modules do not depend on `opentsx-model` → allowlisting in the generator template or via a system property in the start scripts. Finding of 2026-10-12: v2 runs with `ClassSecurityValidator.setGlobal(composite(...))`, the legacy bridge test fails without legacy allowlisting.
- [ ] Iceberg 1.11/1.12: `GenericAppenderFactory` removed → move `IcebergIO` to the new writer API.
- [ ] JFreeChart 1.5 (legacy charts in `opentsx-core`): `org.jfree.ui` removed → migrate the charts or archive the legacy UI.
- [ ] Confluent 8 (Kafka 4 clients, check against KafScale), JUnit 6, networknt json-schema-validator 3 (API changes).
- [ ] Frontend: React 19, TypeScript 7, ESLint 10 (Flat Config), Tailwind 4, Vite 8, React Router 7, Plotly 4: together as one migration (as of 2026-10-12: `npm ci` fails on peer conflicts, `tsc` on outdated options). Open security findings that only go away with the migration (`npm audit`, as of 2026-10-12 after `npm audit fix`: 18): XSS in `maplibre-gl` via Plotly ≤ 4.1 (Plotly 4.1.1+ or Plotly basic bundle without map types), typescript-eslint 8, `postcss-selector-parser`/`micromatch`/`braces` via Tailwind 4, React Router 7.

## Errors and quality: Avro

Moved from TASK-011 (commit 551815a3), line 406.

- [ ] Avro 1.12.2+ with `SERIALIZABLE_PACKAGES`.

## Wind-farm domain (gap roadmap P3, `opentsx-windpark`)

Moved from TASK-011 (commit 551815a3), line 81 (part).

- [ ] Follow-up: Avro 1.12.2+ with `SERIALIZABLE_PACKAGES`.

Lines 81, 406 and 411 of TASK-011 name the same Avro 1.12.2+ work.

## Flink 2.x

Moved from TASK-011 (commit 551815a3), line 418 (part).

- [ ] Flink 2.x as a separate decision. The upgrade to 1.20.5 is done with #147.
