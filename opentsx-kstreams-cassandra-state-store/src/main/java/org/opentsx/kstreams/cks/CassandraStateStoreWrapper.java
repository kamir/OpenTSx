package org.opentsx.kstreams.cks;

import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.internals.StateStoreProvider;
import org.opentsx.kstreams.cks.internals.CassandraReadableStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CassandraStateStoreWrapper<K,V> implements CassandraReadableStore<K,V> {


  private final StateStoreProvider provider;
  private final String storeName;
  private final QueryableStoreType<CassandraReadableStore<K, V>> elasticsearchStoreType;

  public CassandraStateStoreWrapper(final StateStoreProvider provider,
                                    final String storeName,
                                    final QueryableStoreType<CassandraReadableStore<K,V>> elasticsearchStoreType) {

    this.provider = provider;
    this.storeName = storeName;
    this.elasticsearchStoreType = elasticsearchStoreType;
  }
  @Override
  public V read(K key) {

    List<CassandraReadableStore<K,V>> stores = provider.stores(storeName, elasticsearchStoreType);
    Optional<CassandraReadableStore<K,V>> value = stores
        .stream()
        .filter(store -> store.read(key) != null)
        .findFirst();

    return value.map(store -> store.read(key)).orElse(null);
  }

  @Override
  public List<V> search(String words, String ... fields) {

    List<CassandraReadableStore<K,V>> stores = provider.stores(storeName, elasticsearchStoreType);
    Optional<CassandraReadableStore<K,V>> value = stores
        .stream()
        .filter(store -> store.search(words, fields) != null)
        .findFirst();

    return value.map(store -> store.search(words, fields)).orElse(new ArrayList<>());

  }
}
