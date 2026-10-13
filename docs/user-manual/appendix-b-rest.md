# Appendix B: REST API (retrieval server)

Base URL: `http://<host>:<port>`. Times are ISO-8601 instants (UTC).

| Method and path | Parameters / body | Response |
|-----------------|-------------------|----------|
| `GET /v1/health` | — | `{"status":"ok"}` |
| `GET /v1/episodes` | `metric`, `tag.<key>=<value>` (repeatable), `series=id,id`, `from`, `to`, `limit`, `clip=true`, `resample=PT1M`, `agg=MEAN\|MIN\|MAX\|FIRST\|LAST\|SUM\|COUNT` | episodes |
| `GET /v1/episodes/{id}` | — | one episode, or 404 |
| `POST /v1/patterns/search` | `pattern` (array) **or** `episodeId`+`offset`+`length`; `metric`, `tags` (object), `series`, `from`, `to`, `distance` (`zED`\|`DTW`), `band` (DTW, default 0.1), `topK` (default 10) | matches |
| `POST /v1/episodes/similar` | `episodeId`; `metric`, `tags`, `from`, `to`, `distance`, `band`, `topK` | matches |
| `GET /v1/inventory` | none | `opentsx.ts-inventory.v1`: every series with extent, value range, labels and ground-truth marks (see `opentsx-retrieval/README.md`) |
| `GET /v1/systems` | none | the systems the server knows (`--systems`), each with the number of signals by source and by state |
| `GET /v1/systems/{id}/signals` | none | `opentsx.signal-status.v1`: every signal of the system with its expectation, the source of each grade (raw, clean, features), what the inventory holds for it, its state and the next step; `problems` lists what is wrong in the system document |
| `GET /v1/dags` | none | the DAGs of the domain: what was deployed or stopped and what the vector processor reports per sink; 404 unless the server runs with `--kafscale` |
| `POST /v1/dags/deploy` | body: `opentsx.dag.v1` as YAML; optional header `X-OpenTSx-By` | the DAG and its sinks as handed to the vector processor; 404 unless the server runs with `--kafscale` and `--dag-runs true`; 401 without the run token when the server has one (header `X-OpenTSx-Run-Token`); 400 with the reason when the DAG cannot be run |
| `POST /v1/dags/stop?name=<dag>` | none | the stopped DAG; 404 and 401 as for deploy |
| `POST /v1/segments` | `strategy` (`FIXED_DURATION`, `FIXED_COUNT`, `FEATURE_EXTREMUM`, `FEATURE_THRESHOLD`), `params` (see below); `metric`, `tags`, `from`, `to` | episodes |

**Segment parameters** (durations in ISO-8601):

| Strategy | Parameters |
|----------|-----------|
| `FIXED_DURATION` | `duration`, `offset` |
| `FIXED_COUNT` | `n`, `overlap` |
| `FEATURE_EXTREMUM` | `feature` (MAX/MIN), `prominence`, `window`, `distance`, `pre`, `post` |
| `FEATURE_THRESHOLD` | `direction` (ABOVE/BELOW), `enter`, `exit`, `pre`, `post`, `minDuration`, `maxDuration` |

**Response formats:**

* **Episodes:** JSON by default (values, timestamps, summary, labels, segmentation). With
  `Accept: application/x-opentsx-avro-stream`, episodes come as `[uint32 big-endian length][Avro single-object
  Episode]*`.
* **Matches:** a JSON array of `{queryId, episodeId, seriesId, offset, length, distance, metric}`.

**Errors:** `400` for invalid input, `404` for an unknown episode, `500` for a server error. The body is
`{"error": "..."}`.
