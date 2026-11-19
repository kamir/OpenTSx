# OpenTSDB store (v2)

`OpenTsdbSeriesStore` maps v2 episodes onto the OpenTSDB 2.x HTTP API. It uses no OpenTSDB or HBase client library.

| v2 | OpenTSDB |
|----|----------|
| `SeriesKey.metric` / `tags` | metric / tags (validated: letters, digits, `- _ . /`; ≥ 1 and ≤ 8 tags incl. unit) |
| `SeriesKey.unit` | reserved tag `_unit` |
| episode values | data points via `POST /api/put?summary` (batches of 50); **ms precision, NaN dropped** (`WriteResult.droppedPoints`) |
| `EpisodeQuery` | `POST /api/query` (aggregator `none`, `msResolution`), one reconstructed episode per series |
| selector without metric | metrics from `/api/suggest?type=metrics&max=10000`; series from `/api/search/lookup` |

Episode ids, labels, segmentation and provenance are not stored (capability `RAW_POINTS`); use Iceberg for that.
A reconstructed episode takes its id from its series and its first and last timestamp, and its `createdAt` is its last
timestamp, so two reads of the same points return equal episodes. Its producer is `opentsx-store-opentsdb-v2/<version>`.

A 400 whose body says `No such name` is an unknown metric and reads as no data. Every other failure throws:

| Answer | Error |
|--------|-------|
| an answer that is not 2xx | `IllegalStateException`: `OpenTSDB <method> <url> returned <status>: <body>` |
| no answer (connection, timeout) or a body that is not JSON | `UncheckedIOException`: `OpenTSDB <method> <url> failed` |
| a request body that cannot be encoded as JSON | `UncheckedIOException`: `OpenTSDB POST <url>: cannot encode the request body as JSON` |
| 10000 metrics from `/api/suggest`, its limit | `IllegalStateException`: `metrics: /api/suggest returned 10000 metrics, its limit, so the list may be incomplete; select a metric in the SeriesSelector` |

Tests run the store contract against an embedded OpenTSDB emulator; set `OPENTSDB_URL` to a fresh OpenTSDB
instance that you run yourself to run them against a real server; `docker-compose.local.yml` has no OpenTSDB service. The legacy
`opentsx-store-opentsdb` connector is not part of the build.
