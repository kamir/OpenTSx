# opentsx-connectors: Kafka tools for the 2.x model (legacy)

* `kafka.TSOProducer`, `TSOEventAnalysisProducer`: produce 2.x `TimeSeriesObject`s and events.
* `kafka.topicmanager.TopicsUP` / `TopicsDOWN` / `TopicsCHECK`: manage the 2.x topics listed in
  `config/topiclist.def`.
* `kping`, `klatency`: latency probes.

These tools use the 2.x topics and Confluent serializers (client profiles `config/cpl*.props`). For new pipelines, and
for KafScale, use `opentsx-kafka-v2` ([user manual, chapter 4](../docs/user-manual/04-kafscale-storage.md)).
