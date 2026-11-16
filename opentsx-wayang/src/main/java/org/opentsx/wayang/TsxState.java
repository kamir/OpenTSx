package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Operator state of one plan, by DAG node id: read from the checkpoint before an increment and written into the next
 * checkpoint after it (S6.3). Wayang copies operators while it plans, so every copy shares one instance of this.
 */
public final class TsxState {

    private final Map<String, JsonNode> byNode = new TreeMap<>();
    private final Map<String, Progress> progress = new TreeMap<>();

    public record Progress(long watermark, List<String> waitingFor, long skippedBlocks) {
        public Progress {
            waitingFor = List.copyOf(waitingFor);
            if (skippedBlocks < 0) throw new IllegalArgumentException("skipped blocks must not be negative");
        }
    }

    TsxState(Map<String, JsonNode> saved) {
        byNode.putAll(saved);
    }

    public synchronized JsonNode get(String node) {
        return byNode.get(node);
    }

    public synchronized void put(String node, JsonNode state) {
        byNode.put(node, state);
    }

    public synchronized void progress(String node, long watermark, List<String> waitingFor, long skippedBlocks) {
        progress.put(node, new Progress(watermark, waitingFor, skippedBlocks));
    }

    public synchronized Progress progress() {
        long watermark = Long.MIN_VALUE;
        long skipped = 0;
        TreeSet<String> waiting = new TreeSet<>();
        for (Progress value : progress.values()) {
            if (value.watermark() != Long.MIN_VALUE
                    && (watermark == Long.MIN_VALUE || value.watermark() < watermark)) watermark = value.watermark();
            skipped = Math.addExact(skipped, value.skippedBlocks());
            waiting.addAll(value.waitingFor());
        }
        return new Progress(watermark, List.copyOf(waiting), skipped);
    }

    void inheritProgress(TsxState previous) {
        Map<String, Progress> saved;
        synchronized (previous) {
            saved = new TreeMap<>(previous.progress);
        }
        synchronized (this) {
            progress.putAll(saved);
        }
    }

    synchronized Map<String, JsonNode> snapshot() {
        return new TreeMap<>(byNode);
    }
}
