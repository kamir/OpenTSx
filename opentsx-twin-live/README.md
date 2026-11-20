# opentsx-twin-live

The events of a twin run as records of a topic, and a bridge that hands the topic to a browser. The Smart Topics
Studio (`docs/prototypes/smart-topics-studio.html`, the event topic tab `#view-topic`) follows the topic through the
bridge.

```text
twin run --> EventPublisher --> twin.<id>.<run>.events (KafScale) --> EventBridge (SSE) --> Studio
```

## Topic contract

Name: `twin.<id>.<run>.events`, next to `.msgs`, `.barrier` and `.interventions` of the Avro contract in
`opentsx-twin`. `TopicLayout.twinEvents` in `opentsx-kafka-v2` builds the name, and `TopicLayout.provisionTwinEvents`
creates the topic when it is missing: one partition, so the log keeps the order of the run, `compression.type=zstd`
and `cleanup.policy=delete` like every OpenTSx topic. A create that fails is tried again before the next record. Key:
the atom. Value: JSON, schema `twin.event.v1` (header `tsx-schema`):

```json
{"run_id":"h2-chain-32d17d33b237bf6b","k":147,"atom":"line","type":"pressure_limit",
 "t":"2026-10-01T02:27:10Z","attrs":{"p_max_bar":"40.0"}}
```

`t` is the time of the event in the run and may lie inside a time step; `k` is the step that detected it. The record
timestamp is the time of publishing. The value is JSON, not Avro, because the reader is a browser.

## Commands

`<host:port>` is the bootstrap address of the KafScale proxy.

```bash
bin/opentsx twin-live publish --kafscale <host:port> --scenario h2-chain --duration 120 --loop
bin/opentsx twin-live bridge --kafscale <host:port> --port 8090
bin/opentsx twin-live demo --port 8090
```

- `publish` computes the reference run of each Studio scenario (`h2-chain`, `haus`, `boot`), or of the one
  `--scenario` names, and writes its events. With `--duration` the run is played in that many seconds, so a reader
  sees it unfold; without it all events are written at once. `--loop` or `--loop true` publishes the runs again and
  again; without it, or with `--loop false`, they are published once. Publishing the same run again appends the same
  events again.
- `bridge` serves `GET /v1/topics/{topic}/events?from=latest|earliest|<offset>` as `text/event-stream`,
  `GET /v1/health` (liveness: `{"status":"ok"}` while the process answers) and `GET /v1/ready` (readiness:
  `{"status":"ready"}` while the broker answers a lookup of a topic within 2 s, 503 when it does not). A message has the
  offset as its id and `{"topic","offset","timestamp","key","value"}` as data.
  A reconnecting browser sends `Last-Event-ID` and continues behind that offset. While KafScale refuses reads, a
  stream waits 5 s and tries again, and logs
  `WARN org.opentsx.kafka.Patience - the broker refuses reads; waiting pause=PT5S limit=PT10M cause=<causes>` once and
  `INFO org.opentsx.kafka.Patience - the broker answers reads again refusedFor=<duration>` when reads work again;
  after 10 minutes of refusals the stream is closed.
- `demo` runs both in one process on topics kept in memory, plays every run in 120 seconds unless `--duration` says
  otherwise, and loops. No broker is involved and nothing reaches KafScale.

## Configure

The commands read the keys of the registry `opentsx.config.v1`. Print them with their flags, defaults and variables:

```bash
bin/opentsx twin-live publish --help
```

| Key | Flag | Variables | Default | Commands |
|-----|------|-----------|---------|----------|
| `opentsx.kafscale.bootstrap` | `--kafscale`, `--bootstrap` | `OPENTSX_KAFSCALE_BOOTSTRAP`, `KAFSCALE_BOOTSTRAP` | none, required | `publish`, `bridge` |
| `opentsx.twin.scenario` | `--scenario` | `OPENTSX_TWIN_SCENARIO` | every scenario | `publish`, `demo` |
| `opentsx.twinLive.durationSeconds` | `--duration` | `OPENTSX_TWIN_LIVE_DURATION_SECONDS` | `publish`: at once, `demo`: 120 | `publish`, `demo` |
| `opentsx.twinLive.loop` | `--loop` | `OPENTSX_TWIN_LIVE_LOOP` | `false` | `publish` |
| `opentsx.twinLive.port` | `--port` | `OPENTSX_TWIN_LIVE_PORT` | `8090` | `bridge`, `demo` |
| `opentsx.twinLive.allowOrigin` | `--allow-origin` | `OPENTSX_TWIN_LIVE_ALLOW_ORIGIN`, `EVENT_BRIDGE_ALLOW_ORIGIN` | `*` | `bridge`, `demo` |
| `opentsx.twinLive.maxStreams` | `--max-streams` | `OPENTSX_TWIN_LIVE_MAX_STREAMS` | `16` | `bridge`, `demo` |
| `opentsx.log.level` | none | `OPENTSX_LOG_LEVEL` | `info` | all three |

`--bootstrap`, `KAFSCALE_BOOTSTRAP` and `EVENT_BRIDGE_ALLOW_ORIGIN` are legacy names; when both variables of a key are
set, the `OPENTSX_` variable wins. Every command also takes `--conf <key>=<value>` and `--properties-file <path>`.
`--duration` takes 1 to 9223372036854775 seconds, `--port` 0 to 65535 (0 for any free port), `--max-streams` 1 or
more. At start the command logs one line at INFO on stderr with every value it reads and where it came from:
`starting twin-live demo: opentsx.log.level=info (default), opentsx.twin.scenario=h2-chain (commandLine), ...`.

## Read the output

stdout carries the results: one line per scenario with its number of events and its topic
(`h2-chain: 9 events -> twin.h2-chain.<run>.events`), and the address of the bridge
(`event bridge on http://localhost:<port>/v1/topics/<topic>/events, broker <host:port>`, or
`event bridge on http://localhost:<port> with topics in memory (no broker, nothing reaches KafScale)` for `demo`).
stderr carries the log lines of slf4j-simple, which `bin/opentsx` and the image add with
`conf/simplelogger.properties`; the module itself brings no SLF4J binding. At INFO:

```text
starting twin-live demo: opentsx.log.level=info (default), opentsx.twin.scenario=h2-chain (commandLine), ...
publisher started scenarios=h2-chain loop=true duration=PT2M
publisher stopped runs=<number of runs published completely>
event bridge stopped port=<port>
```

`duration=none` means all events at once. `publish` and `demo` log `publisher stopped` when the runs are written,
or at a stop; `bridge` and `demo` log `event bridge stopped` when the bridge closes.

## Stop

On SIGTERM a shutdown hook stops the publisher at its next wait, closes the bridge and the topics, and waits up to
20 s for that. The bridge stops accepting requests and waits up to 5 s for open streams to close their readers. Two
WARN lines say what did not finish in time:

```text
event bridge stopped with streams still open port=<port> open=<n> wait=PT5S
twin-live did not stop in time wait=PT20S
```

## Open the Studio

In the Studio: the event topic tab (`#view-topic`), the live source button (`#tp-src-live`), the address of the
bridge (`#tp-url`) and the connect button (`#tp-connect`). The address can be passed as `?bridge=http://host:8090`.

## Errors

The options and the scenario are checked before the bridge starts and before a client connects to the broker. A usage
or configuration error prints one line on stderr and exits with code 2; a runtime failure prints the message and its
causes on one line and exits with code 1. Examples:

| Exit | stderr |
|------|--------|
| 2 | `opentsx: --prot: unknown option of twin-live demo; did you mean --port?` |
| 2 | `opentsx: opentsx.twin.scenario: "nosuch" is not a scenario; expected one of h2-chain, haus, boot` |
| 2 | `opentsx: opentsx.twinLive.durationSeconds: "0" is not allowed; expected 1 to 9223372036854775 seconds` |
| 2 | `opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP` |
| 1 | `opentsx: publishing failed; caused by java.lang.IllegalStateException: could not create twin.<id>.<run>.events; caused by ...` |

An error answer of the bridge is `{"error": "<text>", "type": "<type>"}`, with the types of
[Appendix B](../docs/user-manual/appendix-b-rest.md).

| Status | `type` | `error` |
|--------|--------|---------|
| 400 | `bad_request` | `not an event topic of a twin: <topic>` or `from: "<value>" is not allowed; expected latest, earliest or an offset of 0 or more` |
| 404 | `not_found` | `no such topic: <topic>`, or `no such endpoint` for any other path, whatever the method |
| 405 | `method_not_allowed` | `<method>: method not allowed; expected GET` on `/v1/health`, `/v1/ready` and `/v1/topics/{topic}/events`, with the header `Allow: GET` |
| 500 | `internal` | `internal error` |
| 502 | `unavailable` | `cannot reach the broker` |
| 503 | `unavailable` | `too many open streams` (see `--max-streams`); on `/v1/ready` `broker: cannot be reached; check the address of the broker and its log` |

A 500 or a 502 writes one line at ERROR to stderr:
`request failed method=<method> path=<path> status=<status> cause=<causes>`. The causes name the broker address; the
answer does not. The stack trace is logged at DEBUG. A stream that fails after it started has answered 200 already;
it is closed and logged at ERROR with the bytes it wrote, and the browser then reconnects:
`stream failed topic=<topic> bytes=<bytes written> cause=<causes>`. A `/v1/ready` that cannot reach the broker logs
`not ready, the broker cannot be reached cause=<causes>` at WARN once per outage, and
`ready, the broker answers again` at INFO when it answers again.

## Limits

- The twin computes a run as a whole; "live" means the publisher plays that run into the topic.
- The bridge is read-only and serves only topics that match `twin.<id>.<run>.events`. It has no authentication and
  answers every origin unless `--allow-origin` narrows that. Run it inside the trusted network.
- Every open stream holds one reader on the log (no consumer group, see `KafScaleClients`); `--max-streams` limits
  their number.
- A message nests at most 1000 levels. A record whose value is JSON nested 1000 levels deep would nest deeper, so the
  stream skips it, goes on with the next record and logs one line at WARN:
  `record skipped, it cannot be written as JSON topic=<topic> offset=<offset> cause=<causes>`.
- On k3s the bridge is the component `event-bridge` (`deploy/k3s/20-configure.sh EVENT_BRIDGE=true`, NodePort 30312).

## Tests

`EventTopicLiveTest`, `KafkaTopicsTest` and `TwinLiveTest` run without a broker. `TwinLiveTest` also stops a demo
with SIGTERM and with its shutdown hook. `KafkaTopicsBrokerTest` needs one:
`KAFSCALE_BOOTSTRAP=host:9092 mvn -pl opentsx-twin-live test` (KafScale in CI; Apache Kafka works too).
