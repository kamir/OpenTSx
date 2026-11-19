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
The tools print the path of the profile, not its properties.
`OpenTSxClusterLink.getPropsAsString_NO_SECURITY_PROPS()` lists the profile without `security.protocol`, the
serializers and deserializers, `schema.registry.url`, `basic.auth.credentials.source` and every key that contains
`sasl.`, `ssl.`, `password`, `secret`, `token` or `user.info`.

## Create and delete the topics

`TopicsUP` creates each listed topic that does not exist and keeps the ones that exist. `TopicsDOWN` deletes each
listed topic and skips the ones that do not exist. Both stop at the first topic the cluster refuses or does not
answer for, with `<topic>: could not create the topic; <cause>` or `<topic>: could not delete the topic; <cause>`.
`TopicsUP` and `TopicsCHECK` stop with `<profile>: could not list the topics; <cause>` when the topics cannot be listed.
