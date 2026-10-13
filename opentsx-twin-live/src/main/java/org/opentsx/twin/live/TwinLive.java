package org.opentsx.twin.live;

import org.opentsx.twin.RunResult;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line of the live event topic.
 *
 * <pre>
 * twin-live publish --bootstrap host:9092 [--scenario id] [--duration 120] [--loop]
 *     writes the events of the reference run(s) to their event topics; with --duration (seconds) a run is played in
 *     that time, with --loop again and again
 * twin-live bridge  --bootstrap host:9092 [--port 8090] [--allow-origin *] [--max-streams 16]
 *     serves event topics to browsers as server-sent events
 * twin-live demo    [--port 8090] [--scenario id] [--duration 120]
 *     both in one process on topics kept in memory: no broker, nothing is written to KafScale
 * </pre>
 */
public final class TwinLive {

    private TwinLive() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        }
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        Map<String, String> o = options(args);
        switch (args[0]) {
            case "publish" -> {
                try (Topics topics = new KafkaTopics(required(o, "bootstrap"))) {
                    publish(topics, o);
                }
            }
            case "bridge" -> {
                Topics topics = new KafkaTopics(required(o, "bootstrap"));
                EventBridge bridge = bridge(topics, o);
                System.out.println("event bridge on http://localhost:" + bridge.port() + "/v1/topics/<topic>/events, broker "
                        + o.get("bootstrap"));
                Thread.currentThread().join();
            }
            case "demo" -> {
                o.putIfAbsent("duration", "120");
                o.put("loop", "true");
                Topics topics = new MemoryTopics();
                EventBridge bridge = bridge(topics, o);
                System.out.println("event bridge on http://localhost:" + bridge.port()
                        + " with topics in memory (no broker, nothing reaches KafScale)");
                publish(topics, o);
            }
            default -> usage();
        }
    }

    private static EventBridge bridge(Topics topics, Map<String, String> o) throws java.io.IOException {
        return new EventBridge(topics, Integer.parseInt(o.getOrDefault("port", "8090")),
                o.getOrDefault("allow-origin", "*"), Integer.parseInt(o.getOrDefault("max-streams", "16")));
    }

    private static void publish(Topics topics, Map<String, String> o) throws InterruptedException {
        List<StudioScenario> scenarios = new ArrayList<>(Scenarios.all());
        if (o.containsKey("scenario")) {
            scenarios.removeIf(s -> !s.id().equals(o.get("scenario")));
            if (scenarios.isEmpty()) {
                throw new IllegalArgumentException("unknown scenario " + o.get("scenario") + ", known: "
                        + Scenarios.all().stream().map(StudioScenario::id).toList());
            }
        }
        Duration duration = o.containsKey("duration") ? Duration.ofSeconds(Long.parseLong(o.get("duration"))) : null;
        Map<StudioScenario, RunResult> runs = new LinkedHashMap<>();
        for (StudioScenario s : scenarios) {
            RunResult run = EventPublisher.referenceRun(s);
            runs.put(s, run);
            System.out.println(s.id() + ": " + run.events().size() + " events -> "
                    + EventTopic.name(s.plan().twin(), run.runId()));
        }
        boolean loop = o.containsKey("loop");
        do {
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
            runs.forEach((s, run) -> {
                Thread t = new Thread(() -> {
                    try {
                        new EventPublisher(topics).publish(s.plan(), run, duration);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        failures.add(e);
                    }
                }, "publish-" + s.id());
                threads.add(t);
                t.start();
            });
            for (Thread t : threads) {
                t.join();
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("publishing failed", failures.get(0));
            }
            if (loop && duration == null) {
                Thread.sleep(1000);
            }
        } while (loop);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                usage();
            }
            String name = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                o.put(name, args[++i]);
            } else {
                o.put(name, "true");
            }
        }
        return o;
    }

    private static String required(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null || v.equals("true")) {
            System.err.println("missing --" + name);
            usage();
        }
        return v;
    }

    private static void usage() {
        System.err.println("""
                usage: twin-live publish --bootstrap host:9092 [--scenario id] [--duration seconds] [--loop]
                       twin-live bridge  --bootstrap host:9092 [--port 8090] [--allow-origin *] [--max-streams 16]
                       twin-live demo    [--port 8090] [--scenario id] [--duration seconds]""");
        System.exit(1);
    }
}
