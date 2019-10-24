package org.opentsx.kstreams.cks.internals;

import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.internals.StateStoreProvider;
import org.opentsx.kstreams.cks.CassandraStateStore;
import org.opentsx.kstreams.cks.CassandraStateStoreWrapper;

public class CassandraStoreType<K,V> implements QueryableStoreType<CassandraReadableStore<K,V>> {

  @Override
  public boolean accepts(StateStore stateStore) {
    return stateStore instanceof CassandraStateStore;
  }

  @Override
  public CassandraReadableStore<K, V> create(StateStoreProvider stateStoreProvider, String storeName) {
    return new CassandraStateStoreWrapper<K,V>(stateStoreProvider, storeName, this);
  }
}
