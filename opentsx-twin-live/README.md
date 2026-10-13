# opentsx-twin-live

The events of a twin run as records of a topic, and a bridge that hands the topic to a browser. The Smart Topics
Studio (`docs/prototypes/smart-topics-studio.html`, tab "Ereignis-Topic") follows the topic through the bridge.

```
twin run --> EventPublisher --> twin.<id>.<run>.events (KafScale) --> EventBridge (SSE) --> Studio
```

## Topic contract

Name: `twin.<id>.<run>.events`, next to `.msgs`, `.barrier` and `.interventions` of the Avro contract in
`opentsx-twin`. One partition, so the log keeps the order of the run. Key: the atom. Value: JSON, schema
`twin.event.v1` (header `tsx-schema`):

```json
{"run_id":"h2-chain-32d17d33b237bf6b","k":147,"atom":"line","type":"pressure_limit",
 "t":"2026-10-01T02:27:10Z","attrs":{"p_max_bar":"40.0"}}
```

`t` is the time of the event in the run and may lie inside a time step; `k` is the step that detected it. The record
timestamp is the time of publishing. The value is JSON, not Avro, because the reader is a browser; an Avro variant
in the style of the other twin topics is open.

## Commands

```
bin/opentsx twin-live publish --bootstrap host:9092 [--scenario id] [--duration 120] [--loop]
bin/opentsx twin-live bridge  --bootstrap host:9092 [--port 8090] [--allow-origin *] [--max-streams 16]
bin/opentsx twin-live demo    [--port 8090] [--scenario id] [--duration 120]
```

- `publish` computes the reference run of each Studio scenario and writes its events. With `--duration` the run is
  played in that many seconds, so a reader sees it unfold; without it all events are written at once. Publishing
  the same run again appends the same events again.
- `bridge` serves `GET /v1/topics/{topic}/events?from=latest|earliest|<offset>` as `text/event-stream` and
  `GET /v1/health`. A message has the offset as its id and `{"topic","offset","timestamp","key","value"}` as data.
  A reconnecting browser sends `Last-Event-ID` and continues behind that offset.
- `demo` runs both in one process on topics kept in memory. No broker is involved and nothing reaches KafScale.

In the Studio: tab "Ereignis-Topic", source "Live aus dem Topic", address of the bridge, "Verbinden". The address
can be passed as `?bridge=http://host:8090`.

## Limits

- The twin itself still computes a run as a whole; "live" means the publisher plays that run into the topic. A twin
  that publishes while it steps is a later stage (`MessageSink`).
- The bridge is read-only and serves only topics that match `twin.<id>.<run>.events`. It has no authentication and
  answers every origin unless `--allow-origin` narrows that. Run it inside the trusted network.
- Every open stream holds one reader on the log (no consumer group, see `KafScaleClients`); `--max-streams` limits
  their number.
- On k3s the bridge is the component `event-bridge` (`deploy/k3s/20-configure.sh EVENT_BRIDGE=true`, NodePort 30312).

## Tests

`EventTopicLiveTest` runs without a broker. `KafkaTopicsBrokerTest` needs one:
`KAFSCALE_BOOTSTRAP=host:9092 mvn -pl opentsx-twin-live test` (KafScale in CI; Apache Kafka works too).
