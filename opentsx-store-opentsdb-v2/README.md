# OpenTSDB store (v2)

`OpenTsdbSeriesStore` maps v2 episodes onto the OpenTSDB 2.x HTTP API — no OpenTSDB/HBase client libraries.

| v2 | OpenTSDB |
|----|----------|
| `SeriesKey.metric` / `tags` | metric / tags (validated: letters, digits, `- _ . /`; ≥ 1 and ≤ 8 tags incl. unit) |
| `SeriesKey.unit` | reserved tag `_unit` |
| episode values | data points via `POST /api/put?summary` (batches of 50); **ms precision, NaN dropped** (`WriteResult.droppedPoints`) |
| `EpisodeQuery` | `POST /api/query` (aggregator `none`, `msResolution`), one reconstructed episode per series |
| selector without metric | metrics from `/api/suggest`; series from `/api/search/lookup` |

Episode ids, labels, segmentation and provenance are not stored (capability `RAW_POINTS`); use Iceberg for that.
Tests run the store contract against an embedded OpenTSDB emulator; set `OPENTSDB_URL` (fresh instance, e.g. the
`opentsdb` service in `docker-compose.local.yml`) to run them against a real server. The legacy
`opentsx-store-opentsdb` connector is not part of the build.
