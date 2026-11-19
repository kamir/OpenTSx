package org.opentsx.windpark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.Producer;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.BucketWriter;
import org.opentsx.kafka.EpisodeIndex;
import org.opentsx.kafka.FileEpisodeIndex;
import org.opentsx.kafka.InMemoryEpisodeIndex;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.iceberg.IcebergSeriesStore;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.SimulationReport;
import org.opentsx.windpark.sim.WindparkSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs a wind-farm scenario and writes the episodes to KafScale (as one sealed bucket), to Iceberg, to both or to no
 * sink. {@code bin/opentsx windpark-sim --help} lists the keys; {@code opentsx.windpark.sinks} chooses the sinks.
 * The keys, the ground-truth path, the scenario and the catalog are checked before the first client opens. Exit codes:
 * 0 success, 1 runtime failure, 2 usage or configuration error.
 */
public final class WindparkSim {

    static final String COMMAND = "windpark-sim";
    static final String SCENARIO = "opentsx.windpark.scenario";
    static final String CATALOG = "opentsx.windpark.catalog";
    static final String GROUND_TRUTH = "opentsx.windpark.groundTruth";
    static final String INDEX = "opentsx.windpark.index";
    static final String SINKS = "opentsx.windpark.sinks";
    static final String BOOTSTRAP = "opentsx.kafscale.bootstrap";
    static final String PARTITIONS = "opentsx.kafscale.partitions";
    static final String DOMAIN = "opentsx.domain";
    static final String ICEBERG = "opentsx.iceberg.properties";
    static final String NAMESPACE = "opentsx.iceberg.namespace";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /** A checked run: a null bootstrap means no KafScale sink, null iceberg properties no Iceberg sink. */
    record Run(WindparkSimulator simulator, Scenario scenario, ChannelCatalog catalog, String bootstrap, TopicLayout layout,
               int partitions, Path index, Map<String, String> iceberg, String namespace, Path groundTruth) {
    }

    private WindparkSim() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err, KafScaleClients::admin, KafScaleClients::producer));
    }

    /** {@code args} without the command word, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   Function<String, Admin> admins, Function<String, Producer<String, byte[]>> producers) {
        List<String> argv = new ArrayList<>();
        argv.add(COMMAND);
        argv.addAll(args);
        OpenTsxConf.Loaded loaded;
        Run run;
        try {
            loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            if (loaded.help()) {
                out.print(Registry.shipped().help(COMMAND));
                return written(out, err);
            }
            run = check(loaded.conf());
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + e.getMessage() + "\n");
            return 2;
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(WindparkSim.class).debug("stack trace of the failed run", e);
            err.print("opentsx: " + COMMAND + ": " + causes(e) + "\n");
            return 1;
        }
        Logger log = LoggerFactory.getLogger(WindparkSim.class);
        log.info(loaded.startLine());
        try {
            out.print(summary(simulate(run, admins, producers, out)));
        } catch (Exception e) {
            log.debug("stack trace of the failed run", e);
            err.print("opentsx: " + COMMAND + ": " + causes(e) + "\n");
            return 1;
        }
        return written(out, err);
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            return 1;
        }
        return 0;
    }

    /**
     * Checks every value and loads the scenario and the catalog; opens nothing. With {@code auto}, a sink is on when its
     * key is set on the command line, so a value from the environment or a file does not add a sink.
     */
    static Run check(OpenTsxConf conf) {
        Scenario scenario = Scenario.load(Path.of(conf.get(SCENARIO)));
        ChannelCatalog catalog = conf.getOption(CATALOG).map(p -> ChannelCatalog.load(Path.of(p)))
                .orElseGet(ChannelCatalog::standard);
        WindparkSimulator simulator = new WindparkSimulator(scenario, catalog);
        List<String> sinks = conf.getList(SINKS);
        String shown = "\"" + String.join(",", sinks) + "\"";
        boolean auto = sinks.equals(List.of("auto"));
        if (!auto && sinks.contains("auto")) {
            throw new OpenTsxConfException(SINKS, shown + " combines auto with other sinks",
                    "expected auto alone or a list of iceberg, kafscale");
        }
        if (Set.copyOf(sinks).size() != sinks.size()) {
            throw new OpenTsxConfException(SINKS, shown + " names a sink twice", "name each sink once");
        }
        boolean kafscale = auto ? "commandLine".equals(conf.source(BOOTSTRAP)) : sinks.contains("kafscale");
        boolean iceberg = auto ? "commandLine".equals(conf.source(ICEBERG)) : sinks.contains("iceberg");

        String bootstrap = null;
        TopicLayout layout = null;
        int partitions = 0;
        Path index = null;
        if (kafscale) {
            bootstrap = conf.get(BOOTSTRAP);
            String domain = conf.get(DOMAIN);
            try {
                layout = new TopicLayout(domain);
            } catch (IllegalArgumentException e) {
                throw new OpenTsxConfException(DOMAIN, "\"" + domain + "\" is not allowed",
                        "expected lower-case letters, digits, _ and -, starting with a letter or a digit");
            }
            partitions = conf.getInt(PARTITIONS);
            if (partitions < 1) {
                throw new OpenTsxConfException(PARTITIONS, "\"" + partitions + "\" is not allowed", "expected 1 or more");
            }
            index = conf.getOption(INDEX).map(Path::of).orElse(null);
        }
        Map<String, String> props = iceberg ? icebergProperties(conf.getList(ICEBERG)) : null;
        Path groundTruth = conf.getOption(GROUND_TRUTH).map(Path::of).orElse(null);
        if (groundTruth != null) {
            String allowed = "give a file in an existing directory, such as ground-truth.json";
            if (Files.isDirectory(groundTruth)) {
                throw new OpenTsxConfException(GROUND_TRUTH, "\"" + groundTruth + "\" is a directory", allowed);
            }
            Path parent = groundTruth.toAbsolutePath().getParent();
            if (!Files.isDirectory(parent)) {
                throw new OpenTsxConfException(GROUND_TRUTH, "\"" + groundTruth + "\": " + parent
                        + " is not an existing directory", allowed);
            }
        }
        return new Run(simulator, scenario, catalog, bootstrap, layout, partitions, index, props, conf.get(NAMESPACE),
                groundTruth);
    }

    /** Every item is key=value; {@code warehouse} and exactly one of {@code type} and {@code catalog-impl} are required. */
    static Map<String, String> icebergProperties(List<String> items) {
        String shown = "\"" + String.join(",", items) + "\"";
        Map<String, String> out = new LinkedHashMap<>();
        for (String item : items) {
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw new OpenTsxConfException(ICEBERG, "item \"" + item + "\" is not key=value",
                        "expected catalog properties such as type=rest,uri=http://localhost:8181,warehouse=s3://lake/");
            }
            String key = item.substring(0, eq).trim();
            if (out.put(key, item.substring(eq + 1).trim()) != null) {
                throw new OpenTsxConfException(ICEBERG, shown + " sets " + key + " twice", "set each property once");
            }
        }
        if (out.containsKey("type") == out.containsKey("catalog-impl")) {
            String which = out.containsKey("type") ? "both type and" : "neither type nor";
            throw new OpenTsxConfException(ICEBERG, shown + " sets " + which + " catalog-impl",
                    "set exactly one of them, such as type=rest");
        }
        if (!out.containsKey("warehouse")) {
            throw new OpenTsxConfException(ICEBERG, shown + " sets no warehouse",
                    "add warehouse=<location>, such as warehouse=s3://lake/");
        }
        return out;
    }

    /** Opens the sinks of a checked run, runs the scenario and closes every client, also when a close fails. */
    static SimulationReport simulate(Run run, Function<String, Admin> admins,
                                     Function<String, Producer<String, byte[]>> producers, PrintStream out) throws Exception {
        List<AutoCloseable> closeables = new ArrayList<>();
        List<Consumer<Episode>> sinks = new ArrayList<>();
        BucketWriter bucket = null;
        Throwable failure = null;
        try {
            if (run.bootstrap() != null) {
                EpisodeIndex index = run.index() != null ? new FileEpisodeIndex(run.index()) : new InMemoryEpisodeIndex();
                if (index instanceof AutoCloseable c) {
                    closeables.add(c);
                }
                try (Admin admin = admins.apply(run.bootstrap())) {
                    run.layout().provision(admin, run.partitions(), (short) 1);
                }
                Producer<String, byte[]> producer = producers.apply(run.bootstrap());
                closeables.add(0, producer);
                Map<String, String> descriptor = new LinkedHashMap<>();
                descriptor.put("scenario", run.scenario().name());
                descriptor.put("seed", Long.toString(run.scenario().seed()));
                descriptor.put("park", run.scenario().park().id());
                descriptor.put("catalog", run.catalog().name() + "@" + run.catalog().version());
                descriptor.put("start", run.scenario().start().toString());
                descriptor.put("duration", run.scenario().duration().toString());
                bucket = BucketWriter.open(producer, run.layout(), index, run.scenario().name() + "/" + run.scenario().start(),
                        descriptor);
                sinks.add(bucket::append);
            }
            if (run.iceberg() != null) {
                SeriesStore store = IcebergSeriesStore.open(run.iceberg(), run.namespace());
                closeables.add(store);
                List<Episode> batch = new ArrayList<>();
                sinks.add(e -> {
                    batch.add(e);
                    if (batch.size() >= 2_000) {
                        store.write(batch);
                        batch.clear();
                    }
                });
                closeables.add(0, () -> {
                    if (!batch.isEmpty()) {
                        store.write(batch);
                    }
                });
            }
            SimulationReport report = run.simulator().run(e -> sinks.forEach(s -> s.accept(e)));
            if (bucket != null) {
                BucketManifest m = bucket.seal();
                out.println("bucket " + m.getBucketId() + " sealed: " + m.getEpisodeCount() + " episodes, checksum " + m.getChecksum());
            }
            if (run.groundTruth() != null) {
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValue(Files.newOutputStream(run.groundTruth()), report.groundTruth());
            }
            return report;
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            closeAll(closeables, failure);
        }
    }

    static void closeAll(List<AutoCloseable> closeables, Throwable primary) throws Exception {
        Exception first = null;
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception e) {
                if (primary != null) {
                    primary.addSuppressed(e);
                } else if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** The failure, its causes and the failures suppressed by it, on one line. */
    static String causes(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            sb.append(sb.length() == 0 ? "" : "; caused by ").append(c);
        }
        for (Throwable s : t.getSuppressed()) {
            sb.append("; suppressed ").append(s);
        }
        return sb.toString().replaceAll("\\p{Cntrl}", " ");
    }

    static String summary(SimulationReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("scenario %s: %,d episodes, %,d values, %d ground-truth events%n",
                r.scenario(), r.episodes(), r.values(), r.groundTruth().size()));
        r.energyKwh().forEach((t, e) -> sb.append(String.format("  %s  %8.1f kWh  mean wind %5.2f m/s  states %s%n",
                t, e, r.meanWindMs().get(t), r.stateSeconds().get(t))));
        return sb.toString();
    }
}
