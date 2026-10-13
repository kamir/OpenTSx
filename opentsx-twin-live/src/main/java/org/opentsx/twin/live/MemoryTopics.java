package org.opentsx.twin.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Topics kept in the process: for tests and for the demo mode that needs no broker. */
public final class MemoryTopics implements Topics {

    private final Map<String, List<Rec>> logs = new ConcurrentHashMap<>();

    @Override
    public void create(String topic) {
        logs.computeIfAbsent(topic, t -> new ArrayList<>());
    }

    @Override
    public void append(String topic, String key, byte[] value, String schema) {
        List<Rec> log = logs.computeIfAbsent(topic, t -> new ArrayList<>());
        synchronized (log) {
            log.add(new Rec(log.size(), System.currentTimeMillis(), key, value));
            log.notifyAll();
        }
    }

    public int size(String topic) {
        List<Rec> log = logs.get(topic);
        if (log == null) {
            return 0;
        }
        synchronized (log) {
            return log.size();
        }
    }

    @Override
    public Cursor open(String topic, long offset) throws NoSuchTopicException {
        List<Rec> log = logs.get(topic);
        if (log == null) {
            throw new NoSuchTopicException(topic);
        }
        long start;
        synchronized (log) {
            start = offset == LATEST ? log.size() : Math.max(0, offset);
        }
        return new Cursor() {
            private int next = (int) start;

            @Override
            public List<Rec> poll(Duration timeout) {
                synchronized (log) {
                    if (next >= log.size()) {
                        try {
                            log.wait(Math.max(1, timeout.toMillis()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                    }
                    List<Rec> out = new ArrayList<>(log.subList(Math.min(next, log.size()), log.size()));
                    next += out.size();
                    return out;
                }
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void close() {
    }
}
