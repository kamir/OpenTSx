package org.opentsx.wayang.board;

import org.opentsx.kafka.TopicLayout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workload driver of the B4.2 board measurement (TASK-020, D-T15). It writes JSON lines to standard output; see
 * {@link BoardEvents}.
 *
 * <pre>
 * feed    --bootstrap B --domain D --series N --seed S --blocks K [--partitions P]
 * digest  --domain D --series N --seed S --blocks K
 * track-a --bootstrap B --domain D --chain C --series N --seed S --catalog DIR --checkpoint FILE
 *         --interval-seconds I --duration-seconds T [--lag-file FILE]
 * track-b --bootstrap B --domain D --chain C --series N --seed S --runner FILE --listen HOST:PORT --state DIR
 *         --log FILE --interval-seconds I --duration-seconds T [--profile measurement] [--lag-file FILE]
 * </pre>
 */
public final class BoardWorkload {

    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "feed", Set.of("bootstrap", "domain", "series", "seed", "blocks"),
            "digest", Set.of("domain", "series", "seed", "blocks"),
            "track-a", Set.of("bootstrap", "domain", "chain", "series", "seed", "catalog", "checkpoint",
                    "interval-seconds", "duration-seconds"),
            "track-b", Set.of("bootstrap", "domain", "chain", "series", "seed", "runner", "listen", "state", "log",
                    "interval-seconds", "duration-seconds"));
    private static final Map<String, Set<String>> OPTIONAL = Map.of(
            "feed", Set.of("partitions"), "digest", Set.of(), "track-a", Set.of("lag-file"),
            "track-b", Set.of("profile", "lag-file"));

    private BoardWorkload() {
    }

    static Map<String, String> options(String command, List<String> args) {
        Set<String> required = REQUIRED.get(command);
        if (required == null) throw new IllegalArgumentException("unknown command " + command + "; expected " + REQUIRED.keySet());
        if (args.size() % 2 != 0) throw new IllegalArgumentException("options come as --name value pairs");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.size(); i += 2) {
            String name = args.get(i);
            if (!name.startsWith("--")) throw new IllegalArgumentException("expected an option, got " + name);
            name = name.substring(2);
            if (!required.contains(name) && !OPTIONAL.get(command).contains(name)) {
                throw new IllegalArgumentException("unknown option --" + name + " for " + command);
            }
            if (options.put(name, args.get(i + 1)) != null) throw new IllegalArgumentException("duplicate option --" + name);
        }
        for (String name : required) {
            if (!options.containsKey(name)) throw new IllegalArgumentException("missing option --" + name + " for " + command);
        }
        if (options.containsKey("chain") && !BoardChains.CHAINS.contains(options.get("chain"))) {
            throw new IllegalArgumentException("unknown chain " + options.get("chain") + "; expected one of " + BoardChains.CHAINS);
        }
        return options;
    }

    public static void main(String[] argv) throws Exception {
        long origin = System.nanoTime();
        if (argv.length == 0) throw new IllegalArgumentException("expected a command: " + REQUIRED.keySet());
        String command = argv[0];
        Map<String, String> o = options(command, List.of(argv).subList(1, argv.length));
        BoardEvents events = new BoardEvents(System.out, origin);
        TopicLayout layout = new TopicLayout(o.get("domain"));
        int series = Integer.parseInt(o.get("series"));
        long seed = Long.parseLong(o.get("seed"));
        switch (command) {
            case "digest" -> events.emit("input", Map.of("topic", layout.episodes(), "series", series, "blocks",
                    Long.parseLong(o.get("blocks")), "sha256", new BoardFeed(series, seed).digest(layout.episodes(),
                            Long.parseLong(o.get("blocks")))));
            case "feed" -> {
                BoardTrackA.provision(o.get("bootstrap"), layout, Integer.parseInt(o.getOrDefault("partitions", "1")));
                long blocks = Long.parseLong(o.get("blocks"));
                BoardFeed feed = new BoardFeed(series, seed);
                events.emit("feedStarted", Map.of("topic", layout.episodes(), "series", series, "blocks", blocks,
                        "sha256", feed.digest(layout.episodes(), blocks)));
                feed.run(o.get("bootstrap"), layout.episodes(), blocks, events);
                events.emit("feedFinished", Map.of());
            }
            case "track-a" -> {
                events.emit("started", Map.of("pid", ProcessHandle.current().pid(), "track", "A",
                        "chain", o.get("chain"), "series", series, "java", Runtime.version().toString(),
                        "maxHeapBytes", Runtime.getRuntime().maxMemory()));
                BoardTrackA.run(o.get("bootstrap"), layout, o.get("chain"), series, seed, Path.of(o.get("catalog")),
                        Path.of(o.get("checkpoint")), seconds(o, "interval-seconds"), seconds(o, "duration-seconds"),
                        path(o, "lag-file"), events);
            }
            case "track-b" -> BoardTrackB.run(Path.of(o.get("runner")), o.get("listen"), Path.of(o.get("state")),
                    o.getOrDefault("profile", "measurement"), o.get("bootstrap"), layout, o.get("chain"), series, seed,
                    seconds(o, "interval-seconds"), seconds(o, "duration-seconds"), Path.of(o.get("log")),
                    path(o, "lag-file"), events);
            default -> throw new IllegalStateException(command);
        }
    }

    private static Path path(Map<String, String> o, String name) {
        return o.containsKey(name) ? Path.of(o.get(name)) : null;
    }

    private static Duration seconds(Map<String, String> o, String name) {
        long value = Long.parseLong(o.get(name));
        if (value < 1) throw new IllegalArgumentException("--" + name + " must be positive");
        return Duration.ofSeconds(value);
    }
}
