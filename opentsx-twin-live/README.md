# opentsx-twin-live

The events of a twin run as records of a topic, and a bridge that hands the topic to a browser. The Smart Topics
Studio (`docs/prototypes/smart-topics-studio.html`, view `topic`, labelled "Event topic") follows the topic through
the bridge.

```text
twin run --> EventPublisher --> tsx.<domain>.twin.<twin>.<run>.events.v1 (KafScale) --> EventBridge (SSE) --> Studio
```

## Topic contract

Name: `tsx.<domain>.twin.<twin>.<run>.events.v1`, from `TopicLayout.twinEvents(twin, run)` in
[`opentsx-kafka-v2`](../opentsx-kafka-v2/README.md). `<domain>` is the `--domain` of the command (default `wind`),
`<twin>` the twin id of the plan and `<run>` the run id (`<twin>-<16 hex digits>`, deterministic). Twin and run ids
must match `[a-z0-9][a-z0-9-]*`. Two deployments with different domains on one broker write different topics; two
deployments with the same domain write the same topic.

It is the only topic a twin run writes. One partition, so the log keeps the order of the run. Key: the atom. Value:
JSON, schema `twin.event.v1` (header `tsx-schema`):

```json
{"run_id":"h2-chain-6734f91571b28b83","k":148,"atom":"buffer","type":"pressure_limit",
 "t":"2025-10-01T02:28:54.367877Z","attrs":{"p_max_bar":"200.000"}}
```

`t` is the time of the event in the run and may lie inside a time step; `k` is the step that detected it. The record
timestamp is the time of publishing. The value is JSON, not Avro, because the reader is a browser. `twin.event.v1`
has no Avro schema.

Port messages, barriers and the `Intervention` record of the Avro message contract in `opentsx-twin` have no topic.
An applied intervention appears on the event topic only as an event of type `intervention` with the attributes
`control`, `value`, `author` and `reason`.

## Commands

```text
bin/opentsx twin-live publish --bootstrap host:9092 [--domain wind] [--scenario id] [--duration 120] [--loop]
bin/opentsx twin-live bridge  --bootstrap host:9092 [--domain wind] [--port 8090] [--allow-origin *] [--max-streams 16]
bin/opentsx twin-live demo    [--domain wind] [--port 8090] [--scenario id] [--duration 120]
```

`--domain` names the domain of the event topics, as `--domain` of the other tools does (default `wind`). Give the
publisher and the bridge the same domain.

- `publish` computes the reference run of each Studio scenario and writes its events. With `--duration` the run is
  played in that many seconds, so a reader sees it unfold; without it all events are written at once. Publishing
  the same run again appends the same events again.
- `bridge` serves `GET /v1/topics/{topic}/events?from=latest|earliest|<offset>` as `text/event-stream` and
  `GET /v1/health`. `{topic}` must be an event topic of its domain; any other name gets 400. A message has the
  offset as its id and `{"topic","offset","timestamp","key","value"}` as data. A reconnecting browser sends
  `Last-Event-ID` and continues behind that offset.
- `demo` runs both in one process on topics kept in memory. No broker is involved and nothing reaches KafScale.

In the Studio, open the `topic` view (button `dt-topic`, "Event topic"), select the live source (`tp-src-live`,
"Live from the topic"), enter the address of the bridge (`tp-url`, "Bridge address") and the domain of the bridge
(`tp-domain`, "Domain", default `wind`), and connect (`tp-connect`, "Connect"). The Studio builds the topic name from
the domain, the twin id and the run id of the reference run, as `TopicLayout.twinEvents` does. The address can be
passed as `?bridge=http://host:8090` and the domain as `?domain=<domain>`; with `bridge` the Studio selects the live
source.

## Move consumers to the domain topic

`publish` and `demo` write `tsx.<domain>.twin.<twin>.<run>.events.v1`. They do not write `twin.<twin>.<run>.events`,
and the bridge answers 400 for that name. Topics with the old name stay on the broker with the records they hold;
delete them only on a broker where you created them.

You need the domain the publisher runs with: its `--domain`, `wind` without it, `OPENTSX_DOMAIN` on k3s.

The twin id and the run id stay the same: `twin.<twin>.<run>.events` becomes
`tsx.<domain>.twin.<twin>.<run>.events.v1`. `demo` prints the new names. It needs no broker and prints the topic of
every scenario before it plays the runs; stop it with Ctrl-C. `publish` prints the same lines before it writes.

```bash
bin/opentsx twin-live demo --domain wind
```

```text
event bridge on http://localhost:8090, domain wind, with topics in memory (no broker, nothing reaches KafScale)
h2-chain: 9 events -> tsx.wind.twin.h2-chain.h2-chain-6734f91571b28b83.events.v1
haus: 7 events -> tsx.wind.twin.haus-erzgebirge.haus-erzgebirge-e0b53b1a33881fa8.events.v1
boot: 26 events -> tsx.wind.twin.boot-etap22.boot-etap22-e9c037dcea05c21a.events.v1
```

- Start the bridge with the same `--domain` as the publisher. On k3s the component `event-bridge` passes
  `OPENTSX_DOMAIN`.
- Point every consumer at the new name. Open the Studio with `?domain=<domain>` or enter the domain in the field
  "Domain" of the view Event topic.

## Limits

- The twin itself still computes a run as a whole; "live" means the publisher plays that run into the topic. A twin
  that publishes while it steps is a later stage (`MessageSink`).
- The bridge is read-only and serves only the event topics of twin runs in its domain
  (`TopicLayout.isTwinEvents`). It has no authentication and answers every origin unless `--allow-origin` narrows
  that. Run it inside the trusted network.
- Every open stream holds one reader on the log (no consumer group, see `KafScaleClients`); `--max-streams` limits
  their number.
- On k3s the bridge is the component `event-bridge` (`deploy/k3s/20-configure.sh EVENT_BRIDGE=true`, NodePort 30312).

## Tests

`EventTopicLiveTest` runs without a broker. It also checks that the Studio builds the name of
`TopicLayout.twinEvents`. `KafkaTopicsBrokerTest` needs one:
`KAFSCALE_BOOTSTRAP=host:9092 mvn -pl opentsx-twin-live test` (KafScale in CI; Apache Kafka works too).
`tools/test_twin_topic_names.py` fails when code other than `TopicLayout` and the Studio builds a twin event topic
name.
