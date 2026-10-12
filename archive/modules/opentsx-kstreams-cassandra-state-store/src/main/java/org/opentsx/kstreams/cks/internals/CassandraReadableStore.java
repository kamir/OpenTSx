package org.opentsx.kstreams.cks.internals;

import java.util.List;

public interface CassandraReadableStore<K,V>  {

  V read(K key);

  List<V> search(String words, String... fields);

}
