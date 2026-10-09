package org.opentsx.kstreams.cks.internals;

public interface CassandraWritableStore<K,V> extends CassandraReadableStore<K,V> {

  void write(K key, V value);

}
