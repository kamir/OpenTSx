package org.opentsx.stats;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The command {@code stats}: the statistics processor on KafScale. It reads the keys of {@code stats} in the registry
 * {@code opentsx.config.v1} through {@link OpenTsxConf#load}; {@code bin/opentsx stats --help} lists them. Exit codes:
 * 0 success, 1 runtime failure, 2 usage or configuration error.
 */
public final class StatsProcessorApp {

    static final String COMMAND = "stats";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    private StatsProcessorApp() {
    }

    public static void main(String[] args) {
        Runtime runtime = Runtime.getRuntime();
        System.exit(run(List.of(args), System.getenv(), System.out, System.err, KafScaleClients::consumer, KafScaleClients::producer,
                runtime::addShutdownHook, runtime::removeShutdownHook));
    }

    /** {@code addHook} and {@code removeHook} register and remove the shutdown hook of a continuous run. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   Function<String, Consumer<byte[], byte[]>> consumers, Function<String, Producer<String, byte[]>> producers,
                   java.util.function.Consumer<Thread> addHook, java.util.function.Consumer<Thread> removeHook) {
        List<String> words = new ArrayList<>(args.size() + 1);
        words.add(COMMAND);
        words.addAll(args);
        OpenTsxConf conf;
        String bootstrap;
        TopicLayout layout;
        StatsEngine engine;
        Path checkpoint;
        Logger log;
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(words, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                out.print(Registry.shipped().help(COMMAND));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            conf = loaded.conf();
            bootstrap = conf.get("opentsx.kafscale.bootstrap");
            layout = new TopicLayout(conf.get("opentsx.domain"));
            checkpoint = conf.getOption("opentsx.stats.checkpoint").map(Path::of).orElse(null);
            engine = new StatsEngine(StatsEngine.Config.defaults()
                    .withWindows(conf.getDurationList("opentsx.stats.windows").toArray(Duration[]::new))
                    .withAllowedLateness(conf.getDuration("opentsx.stats.allowedLateness"))
                    .withSketchAlpha(conf.getDouble("opentsx.stats.sketchAlpha")));
            log = LoggerFactory.getLogger(StatsProcessorApp.class);
            log.info(loaded.startLine());
        } catch (IllegalArgumentException e) {
            // OpenTsxConfException, and a domain, path or engine setting that TopicLayout, Path or StatsEngine refuses
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }
        CountDownLatch closed = new CountDownLatch(1);
        Thread hook = null;
        try (Consumer<byte[], byte[]> consumer = consumers.apply(bootstrap);
             Producer<String, byte[]> producer = producers.apply(bootstrap)) {
            StatsProcessor p = new StatsProcessor(consumer, producer, layout, engine, checkpoint,
                    conf.getBoolean("opentsx.stats.observations"));
            if (conf.getBoolean("opentsx.stats.once")) {
                out.println(p.runUntilCaughtUp(Duration.ofHours(1), true));
                return written(out, err);
            }
            AtomicBoolean stop = new AtomicBoolean();
            hook = new Thread(stopHook(stop, closed, Duration.ofSeconds(60), log));
            addHook.accept(hook);
            p.run(Duration.ofSeconds(30), stop::get);
            return 0;
        } catch (OpenTsxConfException e) {
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            log.debug("stats failed", e);
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        } finally {
            closed.countDown();
            if (hook != null) {
                try {
                    removeHook.accept(hook);
                } catch (IllegalStateException shuttingDown) {
                    // the JVM refuses removals while it shuts down, and it is running this hook already
                }
            }
        }
    }

    /**
     * Tells the processor to stop and waits up to {@code wait} for its final checkpoint. The run removes the hook when it
     * ends; a hook that starts after the end does nothing.
     */
    static Runnable stopHook(AtomicBoolean stop, CountDownLatch closed, Duration wait, Logger log) {
        return () -> {
            if (closed.getCount() == 0) {
                return;
            }
            log.info("stopping; waiting for the final checkpoint wait={}", wait);
            stop.set(true);
            try {
                if (!closed.await(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                    log.warn("the processor did not stop in time; the final checkpoint may be missing wait={}", wait);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        // PrintStream never throws; without this check a full disk or a closed stdout would exit 0.
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /** The message and its causes on one line. */
    static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString();
    }
}
