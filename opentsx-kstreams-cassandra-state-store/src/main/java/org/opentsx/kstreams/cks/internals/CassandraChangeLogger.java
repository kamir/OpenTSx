package org.opentsx.kstreams.cks.internals;

import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.internals.ProcessorStateManager;
import org.apache.kafka.streams.processor.internals.RecordCollector;
import org.apache.kafka.streams.state.StateSerdes;

/**
 *
 * @param <K>
 * @param <V>
 */
public class CassandraChangeLogger<K, V> {

  private final String topic;
  private final int partition;
  private final ProcessorContext context;
  private final RecordCollector collector;
  private final Serializer<K> keySerializer;
  private final Serializer<V> valueSerializer;

  public CassandraChangeLogger(final String storeName,
                               final ProcessorContext context,
                               final StateSerdes<K, V> serialization) {
    this(storeName, context, context.taskId().partition, serialization);
  }

  private CassandraChangeLogger(final String storeName,
                                final ProcessorContext context,
                                final int partition,
                                final StateSerdes<K, V> serialization) {
    topic = ProcessorStateManager.storeChangelogTopic(context.applicationId(), storeName);
    this.context = context;
    this.partition = partition;
    this.collector = ((RecordCollector.Supplier) context).recordCollector();
    keySerializer = serialization.keySerializer();
    valueSerializer = serialization.valueSerializer();
  }

  public void logChange(final K key,
      final V value) {
    logChange(key, value, context.timestamp());
  }

  public void logChange(final K key,
      final V value,
      final long timestamp) {
    // Sending null headers to changelog topics (KIP-244)
    collector.send(topic, key, value, null, partition, timestamp, keySerializer, valueSerializer);
  }

}
