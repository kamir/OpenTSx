package org.opentsx.twin;

/**
 * One output value as it travels between atoms. This is the topic contract shared by both execution variants: the
 * tightly coupled runner can emit every message to a {@link MessageSink}; the loosely coupled variant exchanges
 * exactly these messages over port topics. The id is deterministic, so duplicates can be dropped on read (no
 * idempotent producer needed).
 */
public record PortMessage(String runId, int k, Phase phase, String atom, String port, long tMicros, double value,
                          Origin origin) {

    public String id() {
        return runId + "/" + k + "/" + phase + "/" + atom + "/" + port;
    }
}
