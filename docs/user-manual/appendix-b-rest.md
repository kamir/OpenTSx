# Appendix B: REST API (retrieval server)

Base URL: `http://<host>:<port>`. Times are ISO-8601 instants (UTC).

| Method and path | Parameters / body | Response |
|-----------------|-------------------|----------|
| `GET /v1/health` | none | `{"status":"ok"}` |
| `GET /v1/episodes` | `metric`, `tag.<key>=<value>` (repeatable), `series=id,id`, `from`, `to`, `limit`, `clip=true`, `resample=PT1M`, `agg=MEAN\|MIN\|MAX\|FIRST\|LAST\|SUM\|COUNT` | episodes |
| `GET /v1/episodes/{id}` | none | episodes: an array with the one episode, or 404 |
| `POST /v1/patterns/search` | `pattern` (array of at least 4 finite values) **or** `episodeId`+`offset`+`length`; `metric`, `tags` (object), `series`, `from`, `to`, `distance` (`zED`\|`DTW`), `band` (DTW, default 0.1), `topK` (default 10) | matches |
| `POST /v1/episodes/similar` | `episodeId`; `metric` (default: the metric of the episode), `tags`, `series`, `from`, `to`, `distance`, `band`, `topK` | matches |
| `GET /v1/inventory` | none | `opentsx.ts-inventory.v1`: every series with extent, value range, labels and ground-truth marks (see `opentsx-retrieval/README.md`) |
| `GET /v1/systems` | none | the systems the server knows (`--systems`), each with the number of signals by source and by state |
| `GET /v1/systems/{id}/signals` | none | `opentsx.signal-status.v1`: every signal of the system with its expectation, the source of each grade (raw, clean, features), what the inventory holds for it, its state and the next step; `problems` lists what is wrong in the system document |
| `GET /v1/systems/{id}/plan` | none | `opentsx.system-plan.v1`: the atoms of the plan with their ports and the phase of a time step each computes in (`phases` gives the order); every input says what feeds it (the output it is wired to, `delayed` when the value is that of the step before; the signal declared for it with its state; or nothing). `assumptions` are the sentences the system document states about the plant. `POST` with `{bindings}` lays a working copy over the document |
| `GET /v1/systems/{id}/decisions` | none | `opentsx.decision-status.v1`: the decision cards of the system with the signals each needs (`ok`, `reasons`, what was found) and, per analysis, `status` (`usable`, `restricted`, `blocked`) with `reasons`. Each decision has `asOf`, the newest data time among the signals it needs; age is measured against it. The top-level `asOf` is the newest of these. `POST` with `{bindings}` lays a working copy over the document, as for the signals |
| `GET /v1/systems/{id}/scenario` | none | `opentsx.run-scenario.v1`: what can be asked of the twin of the system: its controls, the policies of the electrolyser, the decisions of the reference run, the KPIs and the series it compares. 404 when the system has no scenario |
| `POST /v1/systems/{id}/runs` | JSON `{label?, inputs?: "scenario"\|"signals", bindings?, base?: "reference"\|"empty", interventions?: [{at, atom, control, value\|"release", reason?}], policies?: {atom: policy}, from?: minute}` | computes a run of the twin and keeps it. With `inputs: "signals"` the inputs come from the series of the signals of the system (clean grade if it has data, else raw; a constant of the document as a constant), each with its origin and the share of the run it covers, and the run is compared with the decisions of the scenario on the same inputs; `bindings` lays a working copy over the document. `at` is minutes after the start, `HH:MM` or an instant. With `from` the run is a forecast that continues the reference from that minute. Answer: `opentsx.run.v1` with `run` (the identity), `decisions`, `inputs` with their origin, `inputsFrom` (`scenario` or `signals`), `status`, `events`, `kpis` and the names of the `series`. `status` has `usability`, always `restricted`, and `reasons`: with scenario inputs a reason says that the inputs are invented; with signal inputs a reason names each input that covers less than the whole run, with its share, and one names the inputs that are generated. Every run adds the reason that sizes, pressures and the order on the hydrogen side are assumed values of the plan. Writes to no topic. 400 with a reason for what cannot be asked |
| `GET /v1/systems/{id}/runs` | none | the runs kept, newest first; the server keeps the last hundred in memory and forgets them when it restarts |
| `GET /v1/systems/{id}/runs/{run}` | none | one run, as the POST answers it; 404 when it is not kept any more |
| `GET /v1/systems/{id}/runs/{run}/series` | `keys=atom.port,...` | the series of a run, one value per step, with the origin of each |
| `GET /v1/systems/{id}/runs/{run}/compare` | `base=<run>` (default: the reference) | two runs next to each other: the KPIs of the scenario with difference and verdict, what is left in the stores at the end, and the series the scenario compares over the whole horizon |
| `POST /v1/systems/{id}/signals` | JSON `{bindings: [{signal, grade, source}]}` | as the GET, with the sources of a working copy laid over the system document; such a source is marked `workingCopy`. Reads only |
| `POST /v1/systems/{id}/generate` | JSON `{signal, from, to, bindings?}`; header `X-OpenTSx-Run-Token` when the server has a run token | runs the demo generator the signal is bound to over the period and writes the series to `tsx.<domain>.synthetic.v2`, nowhere else. Needs `--kafscale` and `--dag-runs true` |
| `GET /v1/dags` | none | the DAGs of the domain: what was deployed or stopped (`tsx.<domain>.dags.v1`) and what the vector processor reports per sink (`tsx.<domain>.dag-status.v1`); `writes` says whether this server deploys and stops DAGs. 404 unless the server runs with `--kafscale` |
| `POST /v1/dags/deploy` | body: the DAG as YAML (`opentsx.dag.v1`; `opentsx.dag.v2` when it has series sinks), at most 262144 bytes; optional header `X-OpenTSx-By` | writes the DAG to the control topic `tsx.<domain>.dags.v1` and answers `{dag, state, sinks, skipped}`; 404 unless the server runs with `--kafscale` and `--dag-runs true`; 401 without the run token when the server has one (header `X-OpenTSx-Run-Token`); 400 with the reason when the DAG cannot be run |
| `POST /v1/dags/stop?name=<dag>` | none | `{dag, state}` with `state` `stopped`; 404 and 401 as for deploy |
| `POST /v1/segments` | `strategy` (`FIXED_DURATION`, `FIXED_COUNT`, `FEATURE_EXTREMUM`, `FEATURE_THRESHOLD`), `params` (see below); `metric`, `tags`, `series`, `from`, `to` | episodes |

`series` is a comma-separated list of series ids in every endpoint that takes it.

**Run token:** `POST /v1/dags/deploy`, `POST /v1/dags/stop` and `POST /v1/systems/{id}/generate` write to KafScale.
Set a run token with `--dag-run-token <token>` or the environment variable `OPENTSX_DAG_RUN_TOKEN`; the option wins.
Callers then send the token in the header `X-OpenTSx-Run-Token`. Without a token, anyone who reaches the port can
deploy and stop DAGs and write generated series. A server with `--kafscale`, `--dag-runs true` and no token prints at
start:

```text
dag deployments: OPEN, anyone who reaches this port can deploy and stop DAGs (set OPENTSX_DAG_RUN_TOKEN)
```

With `--kafscale` and `--dag-runs true`, `GET /v1/inventory` shows `source.dagRunAuth`: `none` or `token`.

**Operators of a DAG:** the vector processor runs only these operators on the path from the source to a sink.

* The only decoder is `decode.avro-soe`.
* A vector sink (schema `opentsx.vector.v1`, topic `tsx.<domain>.smart.<name>.vectors.v1`) reads `align.vector`, whose
  inputs are decoders.
* A series sink (schema `opentsx.episode.v2`) reads one chain of single-input operators from the decoder:
  `dq.checks` (check `PLAUSIBLE_RANGE` only), `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`,
  `feature.lag` and `feature.rolling`. It writes to `tsx.<domain>.clean.v2` (grade `clean`) or
  `tsx.<domain>.features.v2` (grade `features`).
* Any other operator on that path is a 400 that names the operator.

The operators are described in [chapter 17](17-operators.md).

**Segment parameters** (durations in ISO-8601):

| Strategy | Parameters |
|----------|-----------|
| `FIXED_DURATION` | `duration`, `offset` |
| `FIXED_COUNT` | `n`, `overlap` |
| `FEATURE_EXTREMUM` | `feature` (MAX/MIN), `prominence`, `window`, `distance`, `pre`, `post` |
| `FEATURE_THRESHOLD` | `direction` (ABOVE/BELOW), `enter`, `exit`, `pre`, `post`, `minDuration`, `maxDuration` |

**Response formats:**

* **Episodes:** JSON by default: an array of objects with `episodeId`, `seriesId`, `metric`, `tags`, `unit`,
  `tStart`, `tEnd`, `count`, `timeEncoding`, `intervalMicros`, `timestampsMicros` (microseconds since the epoch),
  `values` (NaN as `null`), `quality`, `summary` (`validCount`, `nanCount`, `min`, `max`, `mean`, `m2`), `labels`,
  `segmentation` and `parentEpisodeId`. `unit`, `intervalMicros`, `quality`, `segmentation` and `parentEpisodeId`
  appear only when the episode has them. With `Accept: application/x-opentsx-avro-stream`, episodes come as
  `[uint32 big-endian length][Avro single-object Episode]*`.
* **Matches:** a JSON array of `{queryId, episodeId, seriesId, offset, length, distance, metric}`. `metric` names the
  distance, not the series metric: `zED` or `DTW(w=<band>)`, with `@paa64` appended for whole episodes. For whole
  episodes `queryId` is the SAX word of the query, `offset` is 0 and `length` is the number of values of the episode.

**Errors:** the body is `{"error": "..."}`.

| Status | When |
|--------|------|
| `400` | invalid input, an unknown episode in a POST body, a DAG that cannot be run or is over 262144 bytes |
| `401` | the server has a run token and the request lacks it |
| `404` | an unknown episode in `GET /v1/episodes/{id}`; an unknown route, system or run; a system without a scenario on the run routes; a route the server does not offer without `--systems`, `--kafscale` or `--dag-runs true` |
| `413` | a `POST /v1/systems/{id}/...` request with a body over 262144 bytes |
| `500` | a server error |

A POST body that is not valid JSON gets no answer: the server closes the connection, and `curl` reports
`Empty reply from server`. This does not apply to `POST /v1/dags/deploy`: a body that is not valid YAML is a 400.
