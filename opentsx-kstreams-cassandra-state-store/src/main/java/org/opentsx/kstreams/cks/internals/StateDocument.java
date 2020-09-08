package org.opentsx.kstreams.cks.internals;

import java.util.HashMap;
import java.util.Map;

public class StateDocument<K,V> {

  public Map<K,V> content;

  public StateDocument() {
    content = new HashMap<>();
  }

  public StateDocument(Map<K,V> content) {
    this.content = content;
  }

}
