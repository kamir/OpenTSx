package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The commands {@code vectors} (same as {@code vectors run}), {@code vectors deploy} and {@code vectors stop}. They read
 * their keys through {@link OpenTsxConf#load}; {@code bin/opentsx vectors <command> --help} lists them.
 *
 * <pre>
 * opentsx vectors --kafscale host:9092 --domain wind --state /state              run (exactly one per domain)
 * opentsx vectors deploy --kafscale host:9092 --domain wind --dag flow.dag.yaml  deploy a DAG, or replace it
 * opentsx vectors stop   --kafscale host:9092 --domain wind --name wind-exp2-smart
 * </pre>
 * {@code deploy} and {@code stop} only write a record to the control topic; the running processor takes it up. The
 * options and the DAG file are checked before the first Admin call. {@code deploy} then reads the control topic and
 * refuses a DAG whose vector topic or signal another DAG that the processor runs writes. Exit codes: 0 success, 1
 * runtime failure or a refused second writer, 2 usage or configuration error.
 */
public final class VectorProcessorApp {

    static final String COMMAND = "vectors";
    static final String BOOTSTRAP = "opentsx.kafscale.bootstrap";
    static final String DOMAIN = "opentsx.domain";
    static final String DAG = "opentsx.vectors.dag";
    static final String NAME = "opentsx.vectors.name";
    static final String BY = "opentsx.vectors.by";
    static final String STATE = "opentsx.vectors.state";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    private VectorProcessorApp() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(List.of(args), System.getenv(), out, err, KafScaleClients::admin, KafScaleClients::producer,
                KafScaleClients::consumer);
        // no exit on success: the run ends with 0 only when the shutdown hook stopped it
        if (code != 0) {
            System.exit(code);
        }
    }

    /** {@code args} without the word {@code vectors}, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   Function<String, Admin> admins, Function<String, Producer<String, byte[]>> producers,
                   Function<String, Consumer<byte[], byte[]>> consumers) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        OpenTsxConf.Loaded loaded;
        String bootstrap;
        TopicLayout layout;
        String name = null;
        String spec = null;
        Path state = null;
        try {
            loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                out.print(Registry.shipped().help(loaded.command()));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            OpenTsxConf conf = loaded.conf();
            bootstrap = conf.get(BOOTSTRAP);
            layout = new TopicLayout(conf.get(DOMAIN));
            switch (loaded.command()) {
                case "vectors deploy" -> {
                    String file = conf.get(DAG);
                    spec = readDag(file);
                    name = checkDag(file, spec, layout);
                }
                case "vectors stop" -> {
                    name = conf.get(NAME);
                    if (!VectorProcessor.DAG_NAME.matcher(name).matches()) {
                        throw new OpenTsxConfException(NAME, "\"" + name + "\" is not allowed",
                                "expected letters, digits, dots, hyphens and underscores, starting with a letter or a digit");
                    }
                }
                default -> state = Path.of(conf.get(STATE));
            }
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(VectorProcessorApp.class).debug("stack trace of the failed run", e);
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }
        Logger log = LoggerFactory.getLogger(VectorProcessorApp.class);
        log.info(loaded.startLine());
        String by = loaded.conf().get(BY);
        try {
            switch (loaded.command()) {
                case "vectors deploy" -> {
                    refuseSecondWriter(consumers, bootstrap, layout, name, spec);
                    send(admins, producers, bootstrap, layout, name, VectorProcessor.deployRecord(name, spec, by));
                    out.println("deployed " + name + " (control topic " + layout.dags() + ")");
                }
                case "vectors stop" -> {
                    send(admins, producers, bootstrap, layout, name, VectorProcessor.stopRecord(name, by));
                    out.println("stopped " + name);
                }
                default -> {
                    process(admins, producers, consumers, bootstrap, layout, state, log);
                    return 0;
                }
            }
        } catch (Exception e) {
            log.debug("stack trace of the failed run", e);
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }
        return written(out, err);
    }

    private static String readDag(String file) {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) {
            throw new OpenTsxConfException(file, "no such file", "give an existing file");
        }
        try {
            return Files.readString(p);
        } catch (CharacterCodingException e) {
            throw new OpenTsxConfException(file, "not valid UTF-8", "save the file as UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException(file + ": cannot read the file", e);
        }
    }

    /** Refuses here what the processor would refuse, so the caller sees the reason at once; returns the DAG name. */
    private static String checkDag(String file, String spec, TopicLayout layout) {
        JsonNode dag;
        try {
            dag = new ObjectMapper(new YAMLFactory()).readTree(spec);
        } catch (JsonProcessingException e) {
            throw new OpenTsxConfException(file, "not valid YAML", String.valueOf(e.getOriginalMessage()).lines().findFirst().orElse(""));
        }
        try {
            String name = VectorProcessor.dagName(spec);
            DagPlan.of(dag, layout, VectorProcessor.sha256(spec));
            return name;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Refuses a DAG whose vector topic or signal another DAG that the processor runs writes
     * ({@link VectorProcessor#deployedWrites}).
     */
    private static void refuseSecondWriter(Function<String, Consumer<byte[], byte[]>> consumers, String bootstrap,
                                           TopicLayout layout, String name, String spec) throws IOException {
        Map<String, Set<String>> written;
        try (Consumer<byte[], byte[]> consumer = consumers.apply(bootstrap)) {
            written = VectorProcessor.deployedWrites(consumer, layout);
        }
        Optional<String> conflict = VectorProcessor.sinkConflict(name, new ObjectMapper(new YAMLFactory()).readTree(spec), written);
        if (conflict.isPresent()) {
            throw new IllegalStateException(conflict.get());
        }
    }

    private static void send(Function<String, Admin> admins, Function<String, Producer<String, byte[]>> producers,
                             String bootstrap, TopicLayout layout, String key, byte[] value) throws Exception {
        try (Admin admin = admins.apply(bootstrap)) {
            layout.provisionDagTopics(admin, (short) 1);
        }
        try (Producer<String, byte[]> producer = producers.apply(bootstrap)) {
            producer.send(new ProducerRecord<>(layout.dags(), key, value))
                    .get(KafScaleClients.DELIVERY_TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
        }
    }

    private static void process(Function<String, Admin> admins, Function<String, Producer<String, byte[]>> producers,
                                Function<String, Consumer<byte[], byte[]>> consumers, String bootstrap, TopicLayout layout,
                                Path state, Logger log) {
        try (Admin admin = admins.apply(bootstrap)) {
            // sinks are created as their DAGs arrive; the processor needs the right to create topics
            layout.provisionDagTopics(admin, (short) 1);
        }
        VectorProcessor p = new VectorProcessor(() -> consumers.apply(bootstrap), () -> producers.apply(bootstrap),
                layout, state, VectorProcessor.Timing.standard());
        p.sinkProvisioner(topic -> {
            try (Admin admin = admins.apply(bootstrap)) {
                layout.provisionTopic(admin, topic, (short) 1);
            }
        });
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ended = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopping(() -> stop.set(true), ended, HOOK_WAIT, log)));
        Path progress = state.resolve(PROGRESS_FILE);
        log.info("vector processor started domain={} state={} progress={}", layout.domain(), state.toAbsolutePath(),
                progress.toAbsolutePath());
        try {
            loop(p, stop::get, progress, Clock.systemUTC());
        } finally {
            ended.countDown();
        }
    }

    /** The file in the state directory that holds the time the last round ended, in seconds since the epoch. */
    static final String PROGRESS_FILE = "progress";
    /** How long the progress file may lag behind the rounds; less often than every round spares the disk. */
    static final Duration PROGRESS_EVERY = Duration.ofSeconds(5);

    /**
     * Runs rounds until {@code stop} says so, then writes the checkpoints. It first removes the progress file of an
     * earlier run, so that a probe sees this run only. After a round it writes the time to {@code progress}, at most
     * once per {@link #PROGRESS_EVERY}, so that a probe can tell a loop that goes on from one that hangs.
     *
     * @throws UncheckedIOException when the progress file cannot be removed or written
     */
    static void loop(VectorProcessor p, BooleanSupplier stop, Path progress, Clock clock) {
        try {
            Files.deleteIfExists(progress);
        } catch (IOException e) {
            throw new UncheckedIOException(progress + ": cannot remove the progress file of an earlier run; check that the"
                    + " state directory (opentsx.vectors.state) is writable", e);
        }
        Instant written = null;
        while (!stop.getAsBoolean()) {
            p.step();
            Instant now = clock.instant();
            if (written == null || Duration.between(written, now).compareTo(PROGRESS_EVERY) >= 0) {
                writeProgress(progress, now);
                written = now;
            }
        }
        p.close();
    }

    private static void writeProgress(Path progress, Instant at) {
        Path tmp = progress.resolveSibling(progress.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, at.getEpochSecond() + "\n");
            Files.move(tmp, progress, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(progress + ": cannot write the progress file; check that the state directory"
                    + " (opentsx.vectors.state) exists, is writable and has space", e);
        }
    }

    /** How long the shutdown hook waits for the final checkpoints. */
    static final Duration HOOK_WAIT = Duration.ofSeconds(60);

    /**
     * The shutdown hook: asks the processor to stop and waits at most {@code wait} for it to end. The processor logs
     * the final checkpoint of every sink; the hook logs {@code stopped}, or a WARN when the wait ran out. After a run
     * that already ended, on a failure that exits, there is nothing to stop and it logs nothing.
     */
    static Runnable stopping(Runnable stop, CountDownLatch ended, Duration wait, Logger log) {
        return () -> {
            if (ended.getCount() == 0) {
                return;
            }
            log.info("stopping; waiting for the final checkpoints wait={}", wait);
            stop.run();
            try {
                // wait for the final checkpoints, not for the main thread: after a failed run its System.exit waits for this hook
                if (ended.await(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.info("stopped");
                } else {
                    log.warn("the processor did not stop in time; the final checkpoints may be missing wait={}", wait);
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
        return s.toString().replaceAll("\\p{Cntrl}", " ");
    }
}
