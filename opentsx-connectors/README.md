# opentsx-connectors: Kafka tools for the 2.x model (legacy)

* `kafka.TSOProducer`: produces 2.x `TimeSeriesObject`s and events.
* `kafka.topicmanager.TopicsUP` / `TopicsDOWN` / `TopicsCHECK`: manage the 2.x topics listed in
  `config/topiclist.def`.
* `kping`, `klatency`: latency probes.

These tools use the 2.x topics and Confluent serializers. They read the Kafka client profile
`config/opentsx-local.props` (git-ignored). Without it, they read the localhost template
[`config/opentsx-local.props.example`](../config/opentsx-local.props.example). For new pipelines, and for KafScale, use
`opentsx-kafka-v2` ([user manual, chapter 4](../docs/user-manual/04-kafscale-storage.md)).

## Choose the profile and the topic list

Set `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` to read another client profile. The tools use the file, or
`<file>.example` when the file cannot be read. When neither can be read, the tool stops with:

```text
OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME: "<file>" cannot be read (<absolute path>); give a readable Kafka client profile or its .example template
```

Set `OPENTSX_TOPIC_MAP_FILE_NAME` to read another topic list. When it cannot be read, the tool stops with
`OPENTSX_TOPIC_MAP_FILE_NAME: "<file>" cannot be read (<absolute path>); give a readable topic list`.

A profile that cannot be loaded stops each tool listed above with
`<absolute path>: cannot read the Kafka client profile (<cause>); set OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME to a readable file`.
The tools log the path of the profile at DEBUG, not its properties.
Every producer and consumer of this module, `TSOConsumer` and `TSOEventProducer` included, takes `bootstrap.servers`
and the other client settings from this profile. Each sets its own serializers or deserializers, so the
`key.serializer`, `value.serializer`, `key.deserializer` and `value.deserializer` of the profile do not apply.
`OpenTSxClusterLink.getPropsAsString_NO_SECURITY_PROPS()` lists the profile without `security.protocol`, the
serializers and deserializers, `schema.registry.url`, `basic.auth.credentials.source` and every key that contains
`sasl.`, `ssl.`, `password`, `secret`, `token` or `user.info`.

## Create and delete the topics

`TopicsUP` creates each listed topic that does not exist and keeps the ones that exist. `TopicsDOWN` deletes each
listed topic and skips the ones that do not exist. Both stop at the first topic the cluster refuses or does not
answer for, with `<topic>: could not create the topic; <cause>` or `<topic>: could not delete the topic; <cause>`.
`TopicsUP` and `TopicsCHECK` stop with `<profile>: could not list the topics; <cause>` when the topics cannot be listed.
`TopicsManagerTool.initTopicDefinitions(<file>)` sets the topic list and returns the names of its topics; it throws
`IOException` when the file cannot be read.

## Read a topic into a bucket

`TSOReader.loadBucket_Kafka(<topic>, <z>)` reads the topic with one `TSOConsumer` until at least `<z>` records have
arrived, keeping every record of the last poll, and `runConsumer` closes the consumer. It stops early after 10 empty
polls of one second in a row and then logs at WARN:
`stopped reading after empty polls topic=<topic> read=<n> expected=<z> emptyPolls=10`.

`KafkaConnector.close()` and `TSOConsumer.close()` close the consumer when `processTopic` or `runConsumer` has not
closed it yet, so a `KafkaConnector` that connected but never read leaks no consumer. A second `close()` does nothing.

## Read the probe topics

`kping.TSOEventAnalysisConsumer.createConsumer(<topic>)` returns a `Consumer<String, GenericRecord>` subscribed to
`<topic>`, for the events that `TSOProducer.pushTSDataAsEventsToKafka_String_Avro` writes to `OpenTSx_Events`: the
series label as the key and the Avro `Event` as the value. It sets `key.deserializer` to
`org.apache.kafka.common.serialization.StringDeserializer` and `value.deserializer` to
`io.confluent.kafka.serializers.KafkaAvroDeserializer`, whatever the profile says. `KafkaAvroDeserializer` reads
`schema.registry.url` from the profile. The consumer uses the fixed group id `KPING-Consumer`.

`klatency.LatencyTestEventConsumer.createConsumer(<topic>)` returns a `Consumer<String, String>` subscribed to
`<topic>`, with the fixed group id `LatencyTestEventConsumer`. It sets `key.deserializer` and `value.deserializer` to
`org.apache.kafka.common.serialization.StringDeserializer`, whatever the profile says.

Both consumers continue at the offsets their group committed. They start at the latest offset
(`auto.offset.reset=latest`) only while the group has no committed offset or its committed offset no longer exists on
the cluster. The Kafka client commits the offsets while it polls, because `enable.auto.commit` is `true` unless the
profile sets it to `false`, and `EventFlowAnalysisConsumer` and `RequestResponseAnalysisConsumer` in `opentsx-lg` call
`commitAsync()` when they stop after their run of empty polls. A rerun therefore reads the records written while no
consumer of the group ran. To start at the latest offset again, stop every consumer of the group and reset the
offsets of the group to the latest offset with the tools of your Kafka platform.

`kping.TSOEventAnalysisConsumer.TOPIC` is deprecated. It names `refds_events_topic_ccloud_demo_09_2020`
(`ReferenceDataset.event_topicname`), the topic of `TSOEventProducer`, which holds String JSON records that
`KafkaAvroDeserializer` cannot read. Pass `TSOProducer.TOPIC_for_events` (`OpenTSx_Events`) to `createConsumer`.

## Handle errors

The helpers stop at the first failure and throw.

* A record that cannot be sent, because the producer refuses it or the cluster does not acknowledge it, stops
  `TSOProducer.pushTSDataAsEpisodesToKafka_String_Avro`,
  `TSOProducer.pushTSDataAsEventsToKafka_String_Avro`, `TSOEventProducer.pushTSOItemsToKafka`,
  `EventFlowStateProducer.pushFlowState` and `LatencyTestEventProducer.pushTestEventToCluster` with
  `<topic>: could not send the record; <cause>`. The `TSOProducer` and `TSOEventProducer` methods close their producer;
  `EventFlowStateProducer` and `LatencyTestEventProducer` keep theirs until `close()`.
* `TSOWriter.persistBucket_SequenceFile` closes the SequenceFile when a series cannot be written and passes the
  failure on.
* `TSOWriter.persistEvents_Kafka` sends to the topic it is given.
* `TSOWriter.persistBucket_Kafka` stores nothing and throws
  `<topic>: persistBucket_Kafka stores nothing; use org.opentsx.bridge.LegacyBridge and org.opentsx.kafka.BucketWriter`.
* `TopicsCHECK` prints its verdict on stdout and exits with status 0 when every listed topic exists and 1 when a topic
  is missing or the topics cannot be listed. The verdict names the topic list and the missing topics:
  `<topic list>: topics missing on the cluster [<topic>, ...]; create them with TopicsUP`.

The helpers log through SLF4J under the logger names `org.opentsx.connectors.kafka.*`, one line per event at DEBUG.
`mvn exec:java` in this module puts slf4j-simple and
[`conf/simplelogger.properties`](../conf/simplelogger.properties) on the classpath, so INFO and higher lines go to stderr.
`mvn -q` sets the JVM system property `org.slf4j.simpleLogger.defaultLogLevel` to `error`, which wins over that file
and hides the INFO and WARN lines; run `mvn exec:java` without `-q` to see them.
`TopicsUP`, `TopicsDOWN` and `TopicsCHECK` print their results on stdout.
