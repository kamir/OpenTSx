# TASK-018: Analytics, retrieval, storage and data-source extensions

## Task Metadata
- **Task ID**: TASK-018-analytics-storage-extensions
- **Created**: 2026-10-26
- **Status**: Planned
- **Priority**: Medium
- **Epic**: P5/P6 Analytics and retrieval
- **Origin**: split out of [TASK-011](TASK-011-datamodel-refresher-2026.md) on 2026-10-26 (D90, [18-CLOSING-TASK-011](../docs/datamodel-2026/18-CLOSING-TASK-011.md))

## Objective
Close the analytics gaps (resampling/alignment, gap filling, change-point detection, Welch PSD/coherence), add the P5
options Matrix Profile and iSAX index, finalize D6 Iceberg catalog/object store (REST + MinIO) with a smoke test in
CI, measure records/s against KafScale with real S3, and add real manufacturer exports as additional `sources`.

## Phase 6 (moved from TASK-011)

Moved from TASK-011 (commit 551815a3), lines 102 to 107.

### Phase 6: Analytics gaps
- [ ] Resampling/alignment of multiple series (common grid, as-of joins): the basis for Smart Topics.
- [ ] Gap filling (linear, seasonal, model-based) with quality code SUBSTITUTED.
- [ ] Change-point detection (CUSUM/PELT) as a segmenter.
- [ ] Welch PSD/coherence (vibrations), feature extraction per episode.

## Phase 5 options: retrieval and pattern matching (`opentsx-retrieval`)

Moved from TASK-011 (commit 551815a3), lines 99 to 100.

- [ ] 5c (optional): Matrix Profile motif/discord across buckets; write matches back as annotations.
- [ ] iSAX index for hot data (currently: SAX prefilter per episode, MASS per series).

## Open decision D6 (owner): Iceberg catalog and object store

Moved from TASK-011 (commit 551815a3), line 381.

- [ ] Finalize D6 Iceberg catalog/object store (REST + MinIO).

## Errors and quality: Iceberg REST catalog and KafScale with real S3

Moved from TASK-011 (commit 551815a3), line 407 (part) and line 408.

- [ ] Smoke test against Iceberg REST catalog + MinIO in CI.
- [ ] Records/s measurement against KafScale with real S3 (so far only in-memory S3).

## Runtime and integration

Moved from TASK-011 (commit 551815a3), lines 419 to 420.

- [ ] Real manufacturer exports as additional `sources` in the data point catalog.

Line 420 repeats lines 99 to 100 (Matrix Profile (motif/discord) and iSAX index, the P5 options); it is kept once, under
"Phase 5 options" above.

## Wind-farm domain (gap roadmap P3, `opentsx-windpark`)

Moved from TASK-011 (commit 551815a3), line 81 (part).

- [ ] Follow-up: add real manufacturer exports as additional `sources`.
