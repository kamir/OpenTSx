package org.opentsx.twin.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.twin.TwinEvent;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The topic that carries the events of one twin run: {@code twin.<id>.<run>.events}, the only topic a twin run writes.
 * One partition, so the log keeps the order of the run. The key of a record is the atom; the value is JSON
 * ({@link #SCHEMA}):
 * <pre>
 * {"run_id":"h2-chain-6734f91571b28b83","k":148,"atom":"buffer","type":"pressure_limit",
 *  "t":"2025-10-01T02:28:54.367877Z","attrs":{"p_max_bar":"200.000"}}
 * </pre>
 * {@code t} is the time of the event in the run, which may lie inside a time step; {@code k} is the step that
 * detected it. The record timestamp is the time of publishing.
 */
public final class EventTopic {

    public static final String SCHEMA = "twin.event.v1";

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("twin\\.[a-z0-9][a-z0-9_-]*\\.[A-Za-z0-9][A-Za-z0-9_-]*\\.events");

    private EventTopic() {
    }

    public static String name(String twin, String runId) {
        String name = "twin." + twin + "." + runId + ".events";
        if (!isEventTopic(name)) {
            throw new IllegalArgumentException("not a valid event topic: " + name);
        }
        return name;
    }

    /** Whether {@code name} is an event topic of a twin; the bridge serves nothing else. */
    public static boolean isEventTopic(String name) {
        return name != null && name.length() <= 249 && NAME.matcher(name).matches();
    }

    public static byte[] value(String runId, TwinEvent e) {
        ObjectNode n = JSON.createObjectNode();
        n.put("run_id", runId);
        n.put("k", e.k());
        n.put("atom", e.atom());
        n.put("type", e.type());
        n.put("t", Instant.ofEpochSecond(Math.floorDiv(e.tMicros(), 1_000_000L),
                Math.floorMod(e.tMicros(), 1_000_000L) * 1000L).toString());
        ObjectNode attrs = n.putObject("attrs");
        new TreeMap<>(e.attrs()).forEach(attrs::put);
        try {
            return JSON.writeValueAsBytes(n);
        } catch (java.io.IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
