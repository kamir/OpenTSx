package org.opentsx.kstreams.processors;
import java.util.HashMap;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.opentsx.kstreams.cks.CassandraStateStore;
import org.opentsx.kstreams.cks.internals.StateDocument;

public class CountingProcessor implements Processor<String, String> {

  private ProcessorContext context;
  private HashMap<String, Long> wordCount;
  private CassandraStateStore store;

  @Override
  public void init(ProcessorContext processorContext) {

    this.context = processorContext;

    wordCount = new HashMap<>();

    store = (CassandraStateStore) context.getStateStore(CassandraStateStore.STORE_NAME);

  }

  @Override
  public void process(String key, String value) {

    String[] key_parts = key.split("_");

    String countKey = key_parts[1] + "_" + key_parts[2];

    if (wordCount.get(countKey) == null) {
      wordCount.put(countKey, 0L);
    }

    wordCount.put(countKey, wordCount.get(countKey)+1);

    StateDocument<String, Long> doc = new StateDocument<>( wordCount );

    store.write("CountingProcessor", doc);

  }

  @Override
  public void close() {

  }
}