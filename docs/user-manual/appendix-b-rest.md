# Appendix B: REST API (retrieval server)

Base URL: `http://<host>:<port>`. Times are ISO-8601 instants (UTC).

| Method and path | Parameters / body | Response |
|-----------------|-------------------|----------|
| `GET /v1/health` | none | `{"status":"ok"}` |
| `GET /v1/episodes` | `metric`, `tag.<key>=<value>` (repeatable), `series=id,id`, `from`, `to`, `limit`, `clip=true`, `resample=PT1M`, `agg=MEAN\|MIN\|MAX\|FIRST\|LAST\|SUM\|COUNT` | episodes |
| `GET /v1/episodes/{id}` | none | one episode, or 404 |
| `POST /v1/patterns/search` | `pattern` (array) **or** `episodeId`+`offset`+`length`; `metric`, `tags` (object), `series`, `from`, `to`, `distance` (`zED`\|`DTW`), `band` (DTW, default 0.1), `topK` (default 10) | matches |
| `POST /v1/episodes/similar` | `episodeId`; `metric`, `tags`, `from`, `to`, `distance`, `band`, `topK` | matches |
| `GET /v1/inventory` | none | `opentsx.ts-inventory.v1`: every series with extent, value range, labels and ground-truth marks (see `opentsx-retrieval/README.md`) |
| `GET /v1/systems` | none | the systems the server knows (`--systems`), each with the number of signals by source and by state |
| `GET /v1/systems/{id}/signals` | none | `opentsx.signal-status.v1`: every signal of the system with its expectation, the source of each grade (raw, clean, features), what the inventory holds for it, its state and the next step; `problems` lists what is wrong in the system document |
| `GET /v1/systems/{id}/plan` | none | `opentsx.system-plan.v1`: the atoms of the plan with their ports and the phase of a time step each computes in (`phases` gives the order); every input says what feeds it (the output it is wired to, `delayed` when the value is that of the step before; the signal declared for it with its state; or nothing). `assumptions` are the sentences the system document states about the plant. `POST` with `{bindings}` lays a working copy over the document |
| `GET /v1/systems/{id}/decisions` | none | `opentsx.decision-status.v1`: the decision cards of the system with the signals each needs (`ok`, `reasons`, what was found) and, per analysis, `status` (`usable`, `restricted`, `blocked`) with `reasons`. `asOf` is the newest data time of the system; age is measured against it. `POST` with `{bindings}` lays a working copy over the document, as for the signals |
| `GET /v1/systems/{id}/scenario` | none | `opentsx.run-scenario.v1`: what can be asked of the twin of the system: its controls, the policies of the electrolyser, the decisions of the reference run, the KPIs and the series it compares. 404 when the system has no scenario |
| `POST /v1/systems/{id}/runs` | JSON `{label?, inputs?: "scenario"\|"signals", bindings?, base?: "reference"\|"empty", interventions?: [{at, atom, control, value\|"release", reason?}], policies?: {atom: policy}, from?: minute}` | computes a run of the twin and keeps it. With `inputs: "signals"` the inputs come from the series of the signals of the system (clean grade if it has data, else raw; a constant of the document as a constant), each with its origin and the share of the run it covers, and the run is compared with the decisions of the scenario on the same inputs; `bindings` lays a working copy over the document. `at` is minutes after the start, `HH:MM` or an instant. With `from` the run is a forecast that continues the reference from that minute. Answer: `opentsx.run.v1` with `run` (the identity), `decisions`, `inputs` with their origin, `status` (always `restricted` today: the inputs are those of the scenario), `events`, `kpis` and the names of the `series`. Writes to no topic. 400 with a reason for what cannot be asked |
| `GET /v1/systems/{id}/runs` | none | the runs kept, newest first; the server keeps the last hundred in memory and forgets them when it restarts |
| `GET /v1/systems/{id}/runs/{run}` | none | one run, as the POST answers it; 404 when it is not kept any more |
| `GET /v1/systems/{id}/runs/{run}/series` | `keys=atom.port,...` | the series of a run, one value per step, with the origin of each |
| `GET /v1/systems/{id}/runs/{run}/compare` | `base=<run>` (default: the reference) | two runs next to each other: the KPIs of the scenario with difference and verdict, what is left in the stores at the end, and the series the scenario compares over the whole horizon |
| `POST /v1/systems/{id}/signals` | JSON `{bindings: [{signal, grade, source}]}` | as the GET, with the sources of a working copy laid over the system document; such a source is marked `workingCopy`. Reads only |
| `POST /v1/systems/{id}/generate` | JSON `{signal, from, to, bindings?}`; header `X-OpenTSx-Run-Token` when the server has a run token | runs the demo generator the signal is bound to over the period and writes the series to `tsx.<domain>.synthetic.v2`, nowhere else. Needs `--kafscale` and `--dag-runs true` |
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
