# Appendix B: REST API (retrieval server)

Base URL: `http://<host>:<port>`. Times are ISO-8601 instants (UTC).

| Method and path | Parameters / body | Response |
|-----------------|-------------------|----------|
| `GET /v1/health` | none | `{"status":"ok"}` |
| `GET /v1/episodes` | `metric`, `tag.<key>=<value>` (repeatable), `series=id,id`, `from`, `to`, `limit` (1 to 20000), `clip=true\|false` (default `false`), `resample=PT1M`, `agg=MEAN\|MIN\|MAX\|FIRST\|LAST\|SUM\|COUNT` (default `MEAN`) | episodes |
| `GET /v1/episodes/{id}` | none | episodes: an array with the one episode; 404 for an unknown id; 400 for an id that holds `/` |
| `POST /v1/patterns/search` | `pattern` (array of 4 to 100000 finite values) **or** `episodeId`+`offset`+`length`; `metric`, `tags` (object), `series`, `from`, `to`, `distance` (`zED`\|`DTW`, in any case), `band` (DTW, default 0.1), `topK` (1 to 1000, default 10) | matches; 404 for an unknown `episodeId` |
| `POST /v1/episodes/similar` | `episodeId`; `metric` (default: the metric of the episode), `tags`, `series`, `from`, `to`, `distance`, `band`, `topK` | matches; 404 for an unknown `episodeId` |
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

**Limits:**

| Limit | Value | Constant | Answer when exceeded |
|-------|-------|----------|----------------------|
| JSON body of `POST /v1/patterns/search`, `/v1/episodes/similar`, `/v1/segments` and `/v1/systems/{id}/...` | 262144 bytes | `RetrievalServer.MAX_BODY_BYTES` | 413 `request body: larger than 262144 bytes; send at most 262144 bytes` |
| DAG document of `POST /v1/dags/deploy` | 262144 bytes | `RetrievalServer.MAX_DAG_BYTES` | 400 `the DAG is larger than 262144 bytes` |
| points of one episode after `resample` | 100000 | `Resampling.MAX_POINTS` | 400, for example `resample: "PT0.005S" gives 120000 points for one episode; expected a longer step that gives at most 100000` |
| `topK` of `POST /v1/patterns/search` and `/v1/episodes/similar` | 1 to 1000 | `EpisodeService.MAX_TOP_K` | 400 `topK: 1000000 is out of range; expected a whole number from 1 to 1000` |
| values of `pattern` | 4 to 100000 | `EpisodeService.MAX_PATTERN_LENGTH` | 400 `pattern: 100001 values; expected 4 to 100000 values` |
| `limit` of `GET /v1/episodes` | 1 to 20000 | `EpisodeService.MAX_EPISODES` | 400 `limit: 20001 is out of range; expected a whole number from 1 to 20000` |
| episodes one request selects: `GET /v1/episodes` without `limit`, `POST /v1/patterns/search`, `/v1/episodes/similar` and `/v1/segments` | 20000 | `EpisodeService.MAX_EPISODES` | 400 `selection: more than 20000 episodes match; narrow it with metric, tags, series, from or to`; `GET /v1/episodes` adds `, or set limit` |

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

`prominence` and `enter` are required. Without `exit` the phase ends at `enter`.

**Numbers in a body:** `offset`, `length`, `topK`, `band`, the values of `pattern` and the numeric segment parameters
are JSON numbers. Absent or `null` gives the default; `prominence`, `enter` and each value of `pattern` have none, and
an absent or `null` one is a 400 `<field>: missing; ...`. A string, also a number in quotes such as `"10"`, a boolean,
or a fraction where a whole number belongs is a 400 that names the field:

```text
topK: "ten" is not a whole number; expected a whole number from 1 to 1000 without quotes
pattern[1]: "a" is not a number; expected a number without quotes, such as 0.01 or 1e-3
params.prominence: missing; expected a number without quotes, such as 0.01 or 1e-3
```

`POST /v1/patterns/search` with neither `pattern` nor `episodeId` is a 400
`pattern: missing; expected an array of 4 to 100000 numbers, or episodeId with offset and length`.

**Other parameters:** `from` and `to` are ISO-8601 instants, and `from` lies before `to`. With `to` alone, `from` is
`1970-01-01T00:00:00Z`; with `from` alone, `to` is `9999-01-01T00:00:00Z`. `resample` and the duration
parameters of a segmentation are ISO-8601 durations. `agg`, `clip`, `distance`, `strategy`, `params.feature` and
`params.direction` take only the values listed on this page. Any other value is a 400 that names the parameter and what it
takes:

```text
limit: "abc" is not an int; expected a whole number from 1 to 20000
agg: "mean" is not allowed; expected one of MEAN, MIN, MAX, FIRST, LAST, SUM, COUNT
from: "yesterday" is not an instant; expected an ISO-8601 instant such as 2026-01-01T00:00:00Z
to: "1960-01-01T00:00:00Z" is not after from, which defaults to 1970-01-01T00:00:00Z; expected a later to or an earlier from
params.duration: "1h" is not a duration; expected an ISO-8601 duration such as PT1M
```

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

**Errors:** every error answer is a JSON object with two fields, `error` and `type`:

```json
{"error":"limit: \"abc\" is not an int; expected a whole number from 1 to 20000","type":"bad_request"}
```

`error` is the reason in words. `type` is one of `bad_request`, `not_found`, `method_not_allowed`, `too_large`,
`unauthorized`, `forbidden`, `unavailable` and `internal`. Branch on `type`, not on the text. The retrieval server
answers the six types in the table below, never `forbidden` or `unavailable`. The event bridge of `opentsx-twin-live`
uses the same body and also answers `unavailable`.

| Status | `type` | When |
|--------|--------|------|
| `400` | `bad_request` | a parameter or a body field that is missing, malformed or outside its limit; a POST body that is not valid JSON or not a JSON object; an episode id that holds `/`; a DAG that cannot be run, is not valid YAML or is over 262144 bytes; a `resample` step that gives more than 100000 points for one episode |
| `401` | `unauthorized` | the server has a run token and the request lacks it |
| `404` | `not_found` | an unknown episode in `GET /v1/episodes/{id}`, `POST /v1/episodes/similar` or `POST /v1/patterns/search`; a path the server does not know, also outside `/v1/`; an unknown system or run; a system without a scenario on the run routes; a route the server does not offer without `--systems`, `--kafscale` or `--dag-runs true` |
| `405` | `method_not_allowed` | a known path with a method it does not take; the `Allow` header lists the methods it takes |
| `413` | `too_large` | a `POST /v1/patterns/search`, `/v1/episodes/similar`, `/v1/segments` or `/v1/systems/{id}/...` request with a body over 262144 bytes |
| `500` | `internal` | a fault in the server; the body is always `{"error":"internal error","type":"internal"}` |

A POST body that is not valid JSON is a 400 with the parser message, for example the body `{"pattern":[1,2`:

```text
the body is not valid JSON: Unexpected end-of-input: expected close marker for Array (start marker at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 12])
```

A wrong method names the methods the path takes:

```bash
curl -s -i -X DELETE http://<host>:<port>/v1/episodes
```

```text
HTTP/1.1 405 Method Not Allowed
Allow: GET
{"error":"DELETE /v1/episodes: method not allowed; expected GET","type":"method_not_allowed"}
```

A 500 writes one line at ERROR to the log of the server (stderr), with the method, the path and the exception with
its causes. The stack trace is logged at DEBUG only:

```text
request failed method=<method> path=<path> status=500 cause=<exception>; caused by <exception>
```
