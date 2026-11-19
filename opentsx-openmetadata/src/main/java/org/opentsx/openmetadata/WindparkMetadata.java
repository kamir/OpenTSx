package org.opentsx.openmetadata;

import org.apache.kafka.clients.consumer.Consumer;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.SeriesStatsLog;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Publishes the wind-farm metadata pool to OpenMetadata, or with {@code opentsx.openmetadata.dryRunDir} writes every
 * request to a directory. {@code bin/opentsx metadata --help} lists the keys. {@code opentsx.openmetadata.profiles}
 * decides whether the latest statistics are read from {@code tsx.<domain>.series-stats.v2} and published as table
 * profiles and data-quality results. Every value is checked, and the statistics are read, before the first request.
 * Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error.
 */
public final class WindparkMetadata {

    static final String COMMAND = "metadata";
    static final String SCENARIO = "opentsx.windpark.scenario";
    static final String CATALOG = "opentsx.windpark.catalog";
    static final String DOMAIN = "opentsx.domain";
    static final String NAMESPACE = "opentsx.iceberg.namespace";
    static final String BOOTSTRAP = "opentsx.kafscale.bootstrap";
    static final String PARTITIONS = "opentsx.kafscale.partitions";
    static final String URL = "opentsx.openmetadata.url";
    static final String TOKEN = "opentsx.openmetadata.token";
    static final String DRY_RUN = "opentsx.openmetadata.dryRunDir";
    static final String PROFILES = "opentsx.openmetadata.profiles";
    static final String PROFILE_WINDOW = "opentsx.openmetadata.profileWindow";
    static final String DQ_WINDOW = "opentsx.openmetadata.dqWindow";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /** A checked run: a null url means a dry run into {@code dryRun}. */
    record Run(ChannelCatalog catalog, Park park, TopicLayout layout, URI url, String token, Path dryRun, String namespace,
               int partitions, String bootstrap, boolean profiles, Duration profileWindow, Duration dqWindow) {
    }

    private WindparkMetadata() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err, KafScaleClients::consumer));
    }

    /** {@code args} without the command word, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   Function<String, Consumer<byte[], byte[]>> consumers) {
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
            err.print("opentsx: " + COMMAND + ": " + causes(e) + "\n");
            return 1;
        }
        Logger log = LoggerFactory.getLogger(WindparkMetadata.class);
        log.info(loaded.startLine());
        try {
            out.print(publish(run, consumers) + "\n");
        } catch (RuntimeException e) {
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
     * Checks every value and loads the scenario and the catalog; sends and writes nothing. With profiles {@code auto},
     * profiles are published when the bootstrap is set on the command line, so a value from the environment or a file
     * does not switch them on.
     */
    static Run check(OpenTsxConf conf) {
        Scenario scenario = Scenario.load(Path.of(conf.get(SCENARIO)));
        ChannelCatalog catalog = conf.getOption(CATALOG).map(p -> ChannelCatalog.load(Path.of(p)))
                .orElseGet(ChannelCatalog::standard);
        Park park = new WindparkSimulator(scenario, catalog).park();
        String domain = conf.get(DOMAIN);
        TopicLayout layout;
        try {
            layout = new TopicLayout(domain);
        } catch (IllegalArgumentException e) {
            OpenTsxConfException bad = new OpenTsxConfException(DOMAIN, "\"" + domain + "\" is not allowed",
                    "expected lower-case letters, digits, _ and -, starting with a letter or a digit");
            bad.initCause(e);
            throw bad;
        }
        int partitions = conf.getInt(PARTITIONS);
        if (partitions < 1) {
            throw new OpenTsxConfException(PARTITIONS, "\"" + partitions + "\" is not allowed", "expected 1 or more");
        }
        Path dryRun = conf.getOption(DRY_RUN).map(Path::of).orElse(null);
        URI url = null;
        if (dryRun != null && conf.getOption(URL).isPresent()) {
            throw new OpenTsxConfException(DRY_RUN, "set together with " + URL,
                    "set only one of them: the directory for a dry run or the URL to publish to");
        }
        if (dryRun == null) {
            url = url(conf.getOption(URL).orElseThrow(() -> new OpenTsxConfException(URL, "not set",
                    "set it with --om, --conf " + URL + "=<value> or OPENTSX_OPENMETADATA_URL, or write the requests "
                            + "to a directory with --dry-run")));
        }
        boolean profiles = switch (conf.get(PROFILES)) {
            case "always" -> true;
            case "never" -> false;
            default -> "commandLine".equals(conf.source(BOOTSTRAP));
        };
        String bootstrap = profiles ? conf.get(BOOTSTRAP) : conf.getOption(BOOTSTRAP).orElse(null);
        Duration profileWindow = window(conf, PROFILE_WINDOW);
        Duration dqWindow = window(conf, DQ_WINDOW);
        return new Run(catalog, park, layout, url, conf.getOption(TOKEN).orElse(null), dryRun, conf.get(NAMESPACE),
                partitions, bootstrap, profiles, profileWindow, dqWindow);
    }

    private static URI url(String text) {
        OpenTsxConfException bad = new OpenTsxConfException(URL, "\"" + text + "\" is not an http or https URL",
                "expected the API root such as http://localhost:8585/api");
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            bad.initCause(e);
            throw bad;
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw bad;
        }
        return uri;
    }

    private static Duration window(OpenTsxConf conf, String key) {
        Duration d = conf.getDuration(key);
        if (d.isZero()) {
            throw new OpenTsxConfException(key, "\"" + conf.get(key) + "\" is not allowed",
                    "expected a window longer than zero such as PT10M");
        }
        return d;
    }

    /**
     * Publishes the pool and, with profiles on, the profiles and data-quality results; returns the summary line. The
     * statistics are read before the first request, so a failing bootstrap leaves nothing behind.
     */
    static String publish(Run run, Function<String, Consumer<byte[], byte[]>> consumers) {
        List<SeriesStats> stats = null;
        if (run.profiles()) {
            Duration timeout = Duration.ofMinutes(5);
            LoggerFactory.getLogger(WindparkMetadata.class).info(
                    "reading statistics before the first request topic={} timeout={}", run.layout().seriesStats(), timeout);
            try (Consumer<byte[], byte[]> consumer = consumers.apply(run.bootstrap())) {
                stats = SeriesStatsLog.readLatest(consumer, run.layout(), timeout);
            }
        }
        OmApi api = run.url() == null ? new DryRunOmApi(run.dryRun()) : new HttpOmApi(run.url(), run.token());
        WindparkMetadataPublisher.Settings d = WindparkMetadataPublisher.Settings.defaults();
        WindparkMetadataPublisher p = new WindparkMetadataPublisher(api, run.catalog(), run.park(), run.layout(),
                new WindparkMetadataPublisher.Settings(d.messagingService(), d.databaseService(), d.database(),
                        run.namespace(), d.pipelineService(), run.bootstrap(), run.partitions()));
        p.publishAll();
        StringBuilder out = new StringBuilder("published " + p.entities().size() + " entities for wind farm " + run.park().id());
        if (stats != null) {
            int profiles = p.publishProfiles(stats, run.profileWindow());
            int results = p.publishDataQuality(stats, run.dqWindow(), WindparkExpectations.of(run.catalog()));
            out.append(", ").append(profiles).append(" profiles, ").append(results).append(" data-quality results");
        }
        return out.toString();
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
}
