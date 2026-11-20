package org.opentsx.wayang.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes one JSON object per line: {@code event}, {@code seconds} since the driver started and the event's fields.
 * {@code firstFetch} and {@code firstOutput} are written once each; they give the run's {@code firstFetchSeconds} and
 * {@code firstOutputSeconds}.
 */
public final class BoardEvents {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final long origin;
    private final AtomicBoolean fetched = new AtomicBoolean();
    private final AtomicBoolean output = new AtomicBoolean();

    public BoardEvents(PrintStream out, long originNanos) {
        this.out = out;
        this.origin = originNanos;
    }

    public double seconds() {
        return (System.nanoTime() - origin) / 1e9;
    }

    public synchronized void emit(String event, Map<String, ?> fields) {
        ObjectNode line = JSON.createObjectNode().put("event", event).put("seconds", Math.round(seconds() * 1000) / 1000.0);
        fields.forEach((name, value) -> line.set(name, JSON.valueToTree(value)));
        out.println(line);
        out.flush();
    }

    /** The first increment over a non-empty input range starts. */
    public void firstFetch() {
        if (fetched.compareAndSet(false, true)) emit("firstFetch", Map.of());
    }

    /** The output topic holds its first record. */
    public void firstOutput() {
        if (output.compareAndSet(false, true)) emit("firstOutput", Map.of());
    }

    public boolean hasOutput() {
        return output.get();
    }
}
