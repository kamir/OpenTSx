package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.TreeMap;

/**
 * Operator state of one plan, by DAG node id: read from the checkpoint before an increment and written into the next
 * checkpoint after it (S6.3). Wayang copies operators while it plans, so every copy shares one instance of this.
 */
public final class TsxState {

    private final Map<String, JsonNode> byNode = new TreeMap<>();

    TsxState(Map<String, JsonNode> saved) {
        byNode.putAll(saved);
    }

    public synchronized JsonNode get(String node) {
        return byNode.get(node);
    }

    public synchronized void put(String node, JsonNode state) {
        byNode.put(node, state);
    }

    synchronized Map<String, JsonNode> snapshot() {
        return new TreeMap<>(byNode);
    }
}
