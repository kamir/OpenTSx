package org.opentsx.twin.live;

import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The commands of the live event topic. They read their keys in the registry {@code opentsx.config.v1} through
 * {@link OpenTsxConf#load}; {@code bin/opentsx twin-live <command> --help} lists them. Every value and the scenario are
 * checked before the bridge or a client opens. Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error.
 *
 * <pre>
 * twin-live publish   writes the events of the reference run(s) to their event topics; with a duration a run is played
 *                     in that time, with --loop again and again
 * twin-live bridge    serves event topics to browsers as server-sent events
 * twin-live demo      both in one process on topics kept in memory: no broker, nothing is written to KafScale
 * </pre>
 */
public final class TwinLive {

    static final String COMMAND = "twin-live";
    static final String PUBLISH = "twin-live publish";
    static final String BRIDGE = "twin-live bridge";
    static final String BOOTSTRAP = "opentsx.kafscale.bootstrap";
    static final String SCENARIO = "opentsx.twin.scenario";
    static final String DURATION = "opentsx.twinLive.durationSeconds";
    static final String LOOP = "opentsx.twinLive.loop";
    static final String PORT = "opentsx.twinLive.port";
    static final String ALLOW_ORIGIN = "opentsx.twinLive.allowOrigin";
    static final String MAX_STREAMS = "opentsx.twinLive.maxStreams";
    static final long DEMO_SECONDS = 120;
    /** The longest duration whose milliseconds fit a long. */
    static final long MAX_SECONDS = Long.MAX_VALUE / 1000;
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /** A checked command: no bootstrap for demo, no duration when publish writes a run at once. */
    record Plan(String command, String bootstrap, List<StudioScenario> scenarios, Duration duration, boolean loop, int port,
                String allowOrigin, int maxStreams) {
    }

    private TwinLive() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err, KafkaTopics::new));
    }

    /** {@code args} without the command word, as bin/opentsx passes them; {@code brokers} opens the topics of a broker. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   Function<String, Topics> brokers) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        OpenTsxConf.Loaded loaded;
        Plan plan;
        try {
            loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                Registry registry = Registry.shipped();
                out.print(loaded.command() == null ? registry.help() : registry.help(loaded.command()));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            plan = check(loaded.command(), loaded.conf());
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            return failed(e, err);
        }
        Logger log = LoggerFactory.getLogger(TwinLive.class);
        log.info(loaded.startLine());
        try {
            switch (plan.command()) {
                case PUBLISH -> {
                    try (Topics topics = brokers.apply(plan.bootstrap())) {
                        publish(topics, plan, out);
                    }
                }
                case BRIDGE -> {
                    EventBridge bridge = new EventBridge(brokers.apply(plan.bootstrap()), plan.port(), plan.allowOrigin(),
                            plan.maxStreams());
                    out.println("event bridge on http://localhost:" + bridge.port() + "/v1/topics/<topic>/events, broker "
                            + plan.bootstrap());
                    Thread.currentThread().join();
                }
                default -> {
                    Topics topics = new MemoryTopics();
                    try (EventBridge bridge = new EventBridge(topics, plan.port(), plan.allowOrigin(), plan.maxStreams())) {
                        out.println("event bridge on http://localhost:" + bridge.port()
                                + " with topics in memory (no broker, nothing reaches KafScale)");
                        publish(topics, plan, out);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(e, err);
        } catch (IOException | RuntimeException e) {
            return failed(e, err);
        }
        return written(out, err);
    }

    /** Checks every value the command reads and selects the scenarios; opens nothing. */
    static Plan check(String command, OpenTsxConf conf) {
        boolean demo = !command.equals(PUBLISH) && !command.equals(BRIDGE);
        String bootstrap = demo ? null : conf.get(BOOTSTRAP);
        List<StudioScenario> scenarios = List.of();
        Duration duration = null;
        boolean loop = demo;
        if (!command.equals(BRIDGE)) {
            List<StudioScenario> all = Scenarios.all();
            String only = conf.getOption(SCENARIO).orElse(null);
            scenarios = only == null ? all : all.stream().filter(s -> s.id().equals(only)).toList();
            if (scenarios.isEmpty()) {
                throw new OpenTsxConfException(SCENARIO, "\"" + only + "\" is not a scenario",
                        "expected one of " + String.join(", ", all.stream().map(StudioScenario::id).toList()));
            }
            boolean given = conf.getOption(DURATION).isPresent();
            if (given || demo) {
                long seconds = given ? conf.getLong(DURATION) : DEMO_SECONDS;
                if (seconds < 1 || seconds > MAX_SECONDS) {
                    throw new OpenTsxConfException(DURATION, "\"" + seconds + "\" is not allowed",
                            "expected 1 to " + MAX_SECONDS + " seconds");
                }
                duration = Duration.ofSeconds(seconds);
            }
            loop = demo || conf.getBoolean(LOOP);
        }
        int port = 0;
        String allowOrigin = null;
        int maxStreams = 0;
        if (!command.equals(PUBLISH)) {
            port = conf.getInt(PORT);
            if (port < 0 || port > 65535) {
                throw new OpenTsxConfException(PORT, "\"" + port + "\" is not allowed",
                        "expected 0 to 65535, 0 for any free port");
            }
            allowOrigin = conf.get(ALLOW_ORIGIN);
            maxStreams = conf.getInt(MAX_STREAMS);
            if (maxStreams < 1) {
                throw new OpenTsxConfException(MAX_STREAMS, "\"" + maxStreams + "\" is not allowed", "expected 1 or more");
            }
        }
        return new Plan(command, bootstrap, scenarios, duration, loop, port, allowOrigin, maxStreams);
    }

    private static void publish(Topics topics, Plan plan, PrintStream out) throws InterruptedException {
        Map<StudioScenario, RunResult> runs = new LinkedHashMap<>();
        for (StudioScenario s : plan.scenarios()) {
            RunResult run = EventPublisher.referenceRun(s);
            runs.put(s, run);
            out.println(s.id() + ": " + run.events().size() + " events -> "
                    + TopicLayout.twinEvents(s.plan().twin(), run.runId()));
        }
        do {
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            runs.forEach((s, run) -> {
                Thread t = new Thread(() -> {
                    try {
                        new EventPublisher(topics).publish(s.plan(), run, plan.duration());
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
            if (plan.loop() && plan.duration() == null) {
                Thread.sleep(1000);
            }
        } while (plan.loop());
    }

    private static int failed(Exception e, PrintStream err) {
        LoggerFactory.getLogger(TwinLive.class).debug("stack trace of the failed run", e);
        err.print("opentsx: " + causes(e) + "\n");
        err.flush();
        return 1;
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /** The message and its causes on one line; a checked exception with its class, its message is often bare. */
    static String causes(Throwable t) {
        boolean bare = !(t instanceof RuntimeException) || t.getMessage() == null;
        StringBuilder s = new StringBuilder(bare ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString().replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }
}
