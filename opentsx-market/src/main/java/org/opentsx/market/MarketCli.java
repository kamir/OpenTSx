package org.opentsx.market;

import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.market.archive.BrokerPublish;
import org.opentsx.market.archive.Manifest;
import org.opentsx.market.archive.MosmixRawArchive;
import org.opentsx.market.archive.QualityReport;
import org.opentsx.market.archive.WeatherBackfill;
import org.opentsx.market.source.EnergyChartsJson;
import org.opentsx.market.source.EntsoeXml;
import org.opentsx.market.source.Http;
import org.opentsx.market.source.Marktwerte;
import org.opentsx.market.source.PartnerCsv;
import org.opentsx.market.source.Rebap;
import org.opentsx.market.source.SmardJson;
import org.opentsx.market.twin.HistorySites;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.iceberg.IcebergSeriesStore;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The commands {@code market <command>}: collect electricity-market data, keep the history archive, load it into a
 * store. They read their keys through {@link OpenTsxConf#load}; {@code bin/opentsx market <command> --help} lists them.
 * Every value, the series and the input files are checked before the first download. Exit codes: 0 success, 1
 * runtime failure (a failed download or year, a rejected credential, a publish the broker did not confirm), 2 usage
 * or configuration error (a credential that is not set included).
 */
public final class MarketCli {

    static final String COMMAND = "market";
    static final String ARCHIVE = "opentsx.archive.dir";
    static final String STORE = "opentsx.market.store";
    static final String NAME = "opentsx.market.name";
    static final String FROM = "opentsx.market.from";
    static final String TO = "opentsx.market.to";
    static final String OUT = "opentsx.market.out";
    static final String OFFLINE = "opentsx.market.offline";
    static final String SERIES = "opentsx.market.series";
    static final String BACKFILL_DELAY = "opentsx.market.backfillDelayMs";
    static final String FORCE = "opentsx.market.force";
    static final String SITES = "opentsx.history.sites";
    static final String WEATHER_STATIONS = "opentsx.market.weather.stations";
    static final String MOSMIX_STATIONS = "opentsx.market.mosmix.stations";
    static final String ICEBERG = "opentsx.iceberg.properties";
    static final String NAMESPACE = "opentsx.iceberg.namespace";
    static final String LOAD_STORE = "opentsx.market.load.store";
    static final String ENTSOE_TOKEN = "opentsx.market.entsoeToken";
    static final String NTP_CLIENT_ID = "opentsx.market.ntpClientId";
    static final String NTP_CLIENT_SECRET = "opentsx.market.ntpClientSecret";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /** Series of the default market backfill ("market"). */
    public static final List<String> MARKET = List.of("smard/day_ahead_price", "smard/load", "smard/residual_load",
            "smard/wind_onshore", "smard/wind_offshore", "smard/solar", "smard/forecast_wind_onshore",
            "smard/forecast_wind_offshore", "smard/forecast_solar", "smard/forecast_load",
            "energy-charts/day_ahead_price", "entsoe/day_ahead_price", "entsoe/load", "entsoe/forecast_wind_onshore",
            "entsoe/forecast_wind_offshore", "entsoe/forecast_solar", "netztransparenz/rebap_short",
            "netztransparenz/rebap_long", "netztransparenz/marktwert_epex", "netztransparenz/marktwert_wind_onshore",
            "netztransparenz/marktwert_wind_offshore", "netztransparenz/marktwert_solar");

    /** The collector of the online sources: the pause between two requests to a host, the credentials, the proxy. */
    interface Collectors {
        Collector open(long delayMillis, Map<String, String> credentials, ProxySelector proxy);
    }

    private MarketCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err, Instant.now(), Collector::online));
    }

    /** {@code args} without the word {@code market}, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err, Instant now,
                   Collectors collectors) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        Consumer<PrintStream> run;
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                Registry registry = Registry.shipped();
                out.print(loaded.command() == null ? registry.help() : registry.help(loaded.command()));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            run = check(loaded.command(), loaded.conf(), now, collectors);
            LoggerFactory.getLogger(MarketCli.class).info(loaded.startLine());
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + oneLine(e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            return failed(e, err);
        }
        try {
            run.accept(out);
        } catch (RuntimeException e) {
            return failed(e, err);
        }
        return written(out, err);
    }

    /** Checks every value, the series and the input files; downloads and writes nothing. */
    static Consumer<PrintStream> check(String command, OpenTsxConf conf, Instant now, Collectors collectors) {
        Archive archive = new Archive(Path.of(conf.get(ARCHIVE)));
        return switch (command) {
            case "market catalog" -> out -> MarketCatalog.all().forEach(d -> out.printf(Locale.ROOT,
                    "%-42s %-34s %-8s %-9s %s%n", d.path(), d.metric(), d.unit(), d.origin(), d.tags()));
            case "market prices" -> fetch(MarketCatalog.require(conf.get("opentsx.market.source") + "/day_ahead_price"),
                    conf, archive, now, collectors);
            case "market series" -> {
                String name = conf.get(NAME);
                SeriesDef def = MarketCatalog.get(name).orElseThrow(() -> new OpenTsxConfException(named(conf, NAME),
                        "\"" + name + "\" is not a series of the catalog", "run opentsx market catalog for the series"));
                yield fetch(def, conf, archive, now, collectors);
            }
            case "market rebap" -> fetch(MarketCatalog.require("netztransparenz/rebap_short"), conf, archive, now,
                    collectors);
            case "market marktwert" -> fetch(MarketCatalog.require("netztransparenz/marktwert_wind_onshore"), conf,
                    archive, now, collectors);
            case "market import" -> importFile(conf, archive, now);
            case "market backfill", "market forward" -> backfill(command, conf, archive, now, collectors);
            case "market report" -> {
                List<SeriesDef> defs = series(conf, SERIES, archive);
                Instant requality = conf.getBoolean("opentsx.market.requality") ? now : null;
                yield out -> report(archive, defs, out, requality);
            }
            case "market weather-backfill", "market weather-forward" -> weather(command, conf, archive, now);
            case "market mosmix-archive" -> {
                List<String> stations = conf.getList(MOSMIX_STATIONS);
                for (String s : stations) {
                    if (!s.matches("[A-Za-z0-9]{1,10}")) {
                        throw new OpenTsxConfException(named(conf, MOSMIX_STATIONS), "item \"" + s
                                + "\" is not a station id", "expected letters and digits such as 10579 or E5411");
                    }
                }
                ProxySelector proxy = proxy(conf);
                yield out -> {
                    for (MosmixRawArchive.Result r : new MosmixRawArchive(archive.root(), new Http(1000, 4, proxy))
                            .collect(stations, now)) {
                        out.printf("mosmix %-6s issue %s  %s%n", r.station(), r.issue(), r.added() ? "added " + r.file()
                                : "already archived");
                    }
                };
            }
            case "market load" -> load(archive, conf);
            case "market publish" -> BrokerPublish.check(archive, conf);
            default -> throw new IllegalStateException(command + ": not a command of " + MarketCli.class.getName());
        };
    }

    /** Fetch (or parse an offline file), summarise, optionally write a CSV and store into the archive. */
    private static Consumer<PrintStream> fetch(SeriesDef def, OpenTsxConf conf, Archive archive, Instant now,
                                               Collectors collectors) {
        long from = time(conf, FROM);
        long to = time(conf, TO);
        if (to <= from) {
            throw new OpenTsxConfException(named(conf, TO), "\"" + conf.get(TO) + "\" is not after " + named(conf, FROM)
                    + " \"" + conf.get(FROM) + "\"", "expected an end after the start");
        }
        Optional<String> csv = conf.getOption(OUT);
        if (csv.isPresent() && csv.get().endsWith(".gz")) {
            throw new OpenTsxConfException(named(conf, OUT), "\"" + csv.get() + "\" names a gzip file",
                    "expected a plain CSV file; the archive keeps the gzip'ed files");
        }
        boolean store = switch (conf.get(STORE)) {
            case "always" -> true;
            case "never" -> false;
            default -> "commandLine".equals(conf.source(ARCHIVE));
        };
        List<MarketSeries> parsed;
        List<String> fileQuery;
        Collector collector;
        if (conf.getOption(OFFLINE).isPresent()) {
            Path f = inputFile(conf, OFFLINE);
            try {
                parsed = offline(def, f);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(f + ": " + e.getMessage(), e);
            }
            fileQuery = List.of("file:" + f.getFileName());
            collector = null;
        } else {
            requireCredentials(def, conf);
            parsed = null;
            fileQuery = null;
            collector = collectors.open(conf.getLong("opentsx.market.fetchDelayMs"), credentials(conf), proxy(conf));
        }
        return out -> {
            List<MarketSeries> result = new ArrayList<>();
            List<String> queries = new ArrayList<>();
            if (parsed != null) {
                result.addAll(parsed);
                queries.addAll(fileQuery);
            } else {
                for (SeriesDef d : siblings(def)) {
                    Collector.Fetched f = collector.fetch(d, from, to);
                    queries.addAll(f.queries());
                    result.add(f.series());
                }
            }
            for (MarketSeries s : result) {
                MarketSeries sl = s.slice(from, to);
                out.println(sl.def().path() + " [" + sl.def().unit() + ", " + sl.def().origin() + "]: "
                        + QualityReport.of(sl, from, to).summary());
                if (csv.isPresent()) {
                    Path p = Path.of(result.size() == 1 ? csv.get() : csv.get().replaceFirst("(\\.csv)", "-"
                            + sl.def().name() + "$1"));
                    writeCsv(p, sl);
                    out.println("  written " + p);
                }
                if (store) {
                    storeYears(archive, sl, queries, now, out);
                }
            }
        };
    }

    /** A credential the source of {@code def} needs and that is not set is a configuration error. */
    private static void requireCredentials(SeriesDef def, OpenTsxConf conf) {
        List<String> keys = switch (def.source()) {
            case "entsoe" -> List.of(ENTSOE_TOKEN);
            case "netztransparenz" -> List.of(NTP_CLIENT_ID, NTP_CLIENT_SECRET);
            default -> List.of();
        };
        for (String key : keys) {
            if (conf.getOption(key).isEmpty()) {
                throw new OpenTsxConfException(key, "not set", "set " + String.join(" or ",
                        conf.registry().entry(key, key).envNames()) + " or put " + key
                        + " in a properties file, or parse a download with --offline");
            }
        }
    }

    /** The proxy of the downloads, by the rule of {@link Http#proxy}. */
    private static ProxySelector proxy(OpenTsxConf conf) {
        return Http.proxy(System.getProperty("https.proxyHost"), conf.getOption(Http.PROXY));
    }

    /** The credentials under the names {@link Collector} and {@link Backfill} read. */
    static Map<String, String> credentials(OpenTsxConf conf) {
        Map<String, String> out = new HashMap<>();
        conf.getOption(ENTSOE_TOKEN).ifPresent(v -> out.put("ENTSOE_TOKEN", v));
        conf.getOption(NTP_CLIENT_ID).ifPresent(v -> out.put("NTP_CLIENT_ID", v));
        conf.getOption(NTP_CLIENT_SECRET).ifPresent(v -> out.put("NTP_CLIENT_SECRET", v));
        return out;
    }

    /** Series that come in the same response (both reBAP sides, all market-value technologies). */
    private static List<SeriesDef> siblings(SeriesDef def) {
        if (def.name().startsWith("rebap_")) {
            return List.of(MarketCatalog.require("netztransparenz/rebap_short"),
                    MarketCatalog.require("netztransparenz/rebap_long"));
        }
        if (def.name().startsWith("marktwert_")) {
            return MarketCatalog.all().stream().filter(d -> d.name().startsWith("marktwert_")).toList();
        }
        return List.of(def);
    }

    static List<MarketSeries> offline(SeriesDef def, Path f) {
        String text = text(f);
        List<MarketSeries> out = new ArrayList<>();
        switch (def.source()) {
            case "smard" -> {
                MarketCatalog.Smard s = MarketCatalog.smard(def.name());
                long dt = f.getFileName().toString().contains("quarterhour") ? Micro.QUARTER : Micro.HOUR;
                out.add(new MarketSeries(def, SmardJson.series(text, dt, s.energy())));
            }
            case "energy-charts" -> out.add(new MarketSeries(def, EnergyChartsJson.prices(text).points()));
            case "entsoe" -> out.add(new MarketSeries(def, EntsoeXml.finest(EntsoeXml.parse(text))));
            case "netztransparenz" -> {
                List<String> lines = text.lines().toList();
                if (def.name().startsWith("rebap_")) {
                    Rebap.Result r = Rebap.parse(lines);
                    out.add(new MarketSeries(MarketCatalog.require("netztransparenz/rebap_short"), r.shortSide()));
                    out.add(new MarketSeries(MarketCatalog.require("netztransparenz/rebap_long"), r.longSide()));
                } else {
                    Marktwerte.parse(lines).forEach((tech, pts) -> out.add(new MarketSeries(
                            MarketCatalog.require("netztransparenz/marktwert_" + tech), pts)));
                }
            }
            default -> throw new IllegalArgumentException("no offline parser for " + def.source());
        }
        return out;
    }

    private static Consumer<PrintStream> importFile(OpenTsxConf conf, Archive archive, Instant now) {
        Path file = inputFile(conf, "opentsx.market.import.file");
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("partner", conf.get("opentsx.market.import.partner"));
        SeriesDef def = MarketCatalog.partner(conf.get(NAME), conf.get("opentsx.market.import.metric"),
                conf.get("opentsx.market.import.unit"), conf.get("opentsx.market.import.origin"), tags);
        try {
            archive.dir(def);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(named(conf, NAME) + ": " + e.getMessage(), e);
        }
        Duration resolution = conf.getOption("opentsx.market.import.resolution").isPresent()
                ? conf.getDuration("opentsx.market.import.resolution") : null;
        ZoneId zone = conf.getOption("opentsx.market.import.zone").map(z -> zone(conf, z)).orElse(null);
        List<Point> pts;
        try {
            pts = PartnerCsv.parse(lines(file), conf.getOption("opentsx.market.import.valueColumn").orElse(null),
                    resolution, zone);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        MarketSeries s = new MarketSeries(def, pts);
        List<String> queries = List.of("file:" + conf.get("opentsx.market.import.file"));
        return out -> {
            out.println(def.path() + ": " + s.size() + " points");
            storeYears(archive, s, queries, now, out);
        };
    }

    private static ZoneId zone(OpenTsxConf conf, String zone) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new OpenTsxConfException(named(conf, "opentsx.market.import.zone"), "\"" + zone + "\" is not a time zone",
                    "give a region id such as Europe/Berlin or an offset such as +02:00");
        }
    }

    private static Consumer<PrintStream> backfill(String command, OpenTsxConf conf, Archive archive, Instant now,
                                                  Collectors collectors) {
        int[] years = years(command, conf, now);
        List<SeriesDef> defs = series(conf, "opentsx.market.backfill.series", null);
        boolean force = conf.getBoolean(FORCE);
        Map<String, String> credentials = credentials(conf);
        Collector c = collectors.open(conf.getLong(BACKFILL_DELAY), credentials, proxy(conf));
        return out -> {
            List<Backfill.Outcome> r = new Backfill(archive, c, credentials, out).run(defs, years[0], years[1], force, now);
            long failed = r.stream().filter(x -> x.status() == Backfill.Status.FAILED).count();
            out.printf("backfill done: %d series-years, %d fetched, %d skipped (complete), %d failed, archive %s%n",
                    r.size(), r.stream().filter(x -> x.status() == Backfill.Status.FETCHED).count(),
                    r.stream().filter(x -> x.status() == Backfill.Status.SKIPPED_COMPLETE).count(), failed,
                    archive.root().toAbsolutePath());
            failedYears(command, failed, r.size());
        };
    }

    private static Consumer<PrintStream> weather(String command, OpenTsxConf conf, Archive archive, Instant now) {
        int[] years = years(command, conf, now);
        List<String[]> jobs = weatherJobs(conf);
        boolean force = conf.getBoolean(FORCE);
        long delay = conf.getLong(BACKFILL_DELAY);
        ProxySelector proxy = proxy(conf);
        return out -> {
            WeatherBackfill w = new WeatherBackfill(archive, new Http(delay, 4, proxy), WeatherBackfill.CDC, out);
            long failed = 0;
            long total = 0;
            for (String[] sp : jobs) {
                for (Backfill.Outcome r : w.run(sp[0], sp[1], years[0], years[1], force, now)) {
                    out.printf("%-40s %d  %-16s %8d rows  %s%n", r.def().path(), r.year(), r.status(), r.rows(),
                            r.message());
                    failed += r.status() == Backfill.Status.FAILED ? 1 : 0;
                    total++;
                }
            }
            failedYears(command, failed, total);
        };
    }

    private static void failedYears(String command, long failed, long total) {
        if (failed > 0) {
            throw new IllegalStateException(command + ": " + failed + " of " + total
                    + " series-years failed; the lines marked FAILED on stdout say why");
        }
    }

    private static void storeYears(Archive archive, MarketSeries s, List<String> queries, Instant now, PrintStream out) {
        if (s.isEmpty()) {
            return;
        }
        Backfill b = new Backfill(archive, null, Map.of(), out);
        int y0 = Micro.yearOf(s.points().get(0).tMicros());
        int y1 = Micro.yearOf(s.points().get(s.size() - 1).tMicros());
        for (int y = y0; y <= y1; y++) {
            // merge with what is archived already, so an import of one month does not drop the rest of the year
            MarketSeries merged = new MarketSeries(s.def(), archive.read(s.def(), y)).merge(s.points());
            long last = merged.points().get(merged.size() - 1).endMicros();
            Backfill.Outcome r = b.store(s.def(), y, merged, queries, now, Math.min(last, Micro.of(now)));
            out.printf("  archived %s %d: %s%n", s.def().path(), y, r.message());
        }
    }

    private static void report(Archive archive, List<SeriesDef> defs, PrintStream out, Instant requality) {
        for (SeriesDef d : defs) {
            if (!Files.isRegularFile(archive.dir(d).resolve(Manifest.FILE))) {
                continue;
            }
            Manifest m = archive.manifest(d);
            if (requality != null) {
                m.years.replaceAll((y, e) -> {
                    int year = Integer.parseInt(y);
                    long from = Micro.utcYear(year);
                    long to = Math.min(Micro.utcYear(year + 1), Math.max(from + 1, Micro.of(Instant.parse(e.retrievedAt()))));
                    QualityReport q = QualityReport.of(new MarketSeries(d, archive.read(d, year)), from, to);
                    return new Manifest.Entry(e.file(), e.rows(), e.sha256(), e.bytes(), e.first(), e.last(),
                            e.retrievedAt(), e.complete(), e.queryCount(), e.queries(), q, e.note());
                });
                m.save(archive.dir(d));
            }
            out.println(d.path() + "  [" + m.unit + ", " + m.origin + "]  " + m.licence);
            m.years.forEach((y, e) -> {
                boolean ok = archive.exists(d, Integer.parseInt(y)) && Archive.sha256(archive.file(d, Integer.parseInt(y)))
                        .equals(e.sha256());
                out.printf(Locale.ROOT, "  %s  %-5s %8d rows %9d B  %s  sha256 %s%s%n", y, e.complete() ? "final" : "open",
                        e.rows(), e.bytes(), e.quality() == null ? "" : e.quality().summary(),
                        e.sha256().substring(0, 12), ok ? "" : "  FILE CHANGED OR MISSING");
            });
        }
    }

    private static Consumer<PrintStream> load(Archive archive, OpenTsxConf conf) {
        int y0 = conf.getInt("opentsx.market.fromYear");
        int y1 = conf.getInt("opentsx.market.toYear");
        Instant fromDay = BrokerPublish.day(conf, "opentsx.market.fromDay", Instant.MIN);
        Instant toDay = BrokerPublish.day(conf, "opentsx.market.toDay", Instant.MAX);
        List<SeriesDef> defs = series(conf, SERIES, archive);
        Map<String, String> iceberg = iceberg(conf);
        String namespace = conf.get(NAMESPACE);
        return out -> {
            SeriesStore store = iceberg != null ? IcebergSeriesStore.open(iceberg, namespace) : new InMemorySeriesStore();
            long episodes = 0;
            long values = 0;
            try (store) {
                for (SeriesDef d : defs) {
                    Manifest m = archive.manifest(d);
                    for (String y : m.years.keySet()) {
                        int year = Integer.parseInt(y);
                        if (year < y0 || year > y1) {
                            continue;
                        }
                        List<Episode> eps = EpisodeMapper.episodes(new MarketSeries(d, archive.read(d, year)),
                                        Episodes.producer("opentsx-market")).stream()
                                .filter(e -> !e.getTStart().isBefore(fromDay) && e.getTStart().isBefore(toDay))
                                .map(BrokerPublish::identified).toList();
                        store.write(eps);
                        episodes += eps.size();
                        values += eps.stream().mapToLong(Episode::getCount).sum();
                        out.printf("%-40s %s  %6d episodes%n", d.path(), y, eps.size());
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("load failed: " + e.getMessage(), e);
            }
            out.printf("loaded %d episodes, %d values into %s%n", episodes, values,
                    iceberg != null ? "Iceberg namespace " + namespace : "an in-memory store");
        };
    }

    /**
     * The Iceberg catalog properties of {@code market load}, or null for the in-memory store: {@code iceberg} takes
     * them from any source, {@code auto} only from the command line, {@code memory} never.
     */
    private static Map<String, String> iceberg(OpenTsxConf conf) {
        String store = conf.get(LOAD_STORE);
        if (store.equals("memory") || store.equals("auto") && !"commandLine".equals(conf.source(ICEBERG))) {
            return null;
        }
        String list = conf.get(ICEBERG);
        try {
            return props(list);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(named(conf, ICEBERG) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Years of a backfill run, {@code [from, to]}: the daily jobs ({@code forward}, {@code weather-forward}) take the
     * previous and the running UTC year, so a year that settles after New Year is fetched once more; the backfills
     * take {@code --from-year} (default 2019) to {@code --to-year} (default the running year).
     */
    static int[] years(String command, OpenTsxConf conf, Instant now) {
        int current = now.atZone(ZoneOffset.UTC).getYear();
        int from = command.endsWith("forward") ? current - 1 : conf.getInt("opentsx.market.backfill.fromYear");
        int to = conf.getOption("opentsx.market.backfill.toYear").isPresent()
                ? conf.getInt("opentsx.market.backfill.toYear") : current;
        return new int[]{from, to};
    }

    /** Station/parameter pairs of the weather backfill: {@code --stations id:param,...} or every station of the sites. */
    static List<String[]> weatherJobs(OpenTsxConf conf) {
        LinkedHashSet<String> jobs = new LinkedHashSet<>();
        Optional<String> sites = conf.getOption(SITES);
        if (conf.getOption(WEATHER_STATIONS).isPresent()) {
            jobs.addAll(conf.getList(WEATHER_STATIONS));
        } else {
            for (HistorySites.Site s : HistorySites.load(sites.isPresent() ? inputFile(conf, SITES) : null).values()) {
                if (s.temperature() != null) {
                    jobs.add(s.temperature() + ":air_temperature");
                }
                if (s.wind() != null) {
                    jobs.add(s.wind() + ":wind_speed");
                }
                if (s.radiation() != null) {
                    jobs.add(s.radiation() + ":global_radiation");
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        for (String j : jobs) {
            String[] p = j.split(":");
            if (p.length != 2 || !p[0].matches("[0-9]{5}") || !WeatherBackfill.parameters().contains(p[1])) {
                throw new OpenTsxConfException(named(conf, WEATHER_STATIONS), "item \"" + j + "\" is not allowed",
                        "expected <id>:<parameter> with a station id of 5 digits and parameter in "
                                + WeatherBackfill.parameters());
            }
            out.add(p);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(sites.orElse("classpath:" + HistorySites.RESOURCE) + ": sites: no site "
                    + "names a temperature, wind or radiation station; name one, or pass --stations <id>:<parameter>,...");
        }
        return out;
    }

    /** The series a key selects ({@link #select}); an unknown one is refused with the name of the key. */
    private static List<SeriesDef> series(OpenTsxConf conf, String key, Archive archive) {
        try {
            return select(conf.get(key), archive);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(named(conf, key) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Series selection: {@code market}, {@code all} (catalog), {@code all-archived} (every series with a manifest in
     * the archive, including weather and partner series), or a comma list of paths, each taken from the archive
     * when it is archived there and from the catalog otherwise.
     */
    static List<SeriesDef> select(String spec, Archive archive) {
        return switch (spec) {
            case "market" -> MARKET.stream().map(MarketCatalog::require).toList();
            case "all" -> MarketCatalog.all();
            case "all-archived" -> archive == null ? MarketCatalog.all() : archive.archivedSeries();
            default -> {
                Map<String, SeriesDef> archived = new HashMap<>();
                if (archive != null) {
                    archive.archivedSeries().forEach(d -> archived.put(d.path(), d));
                }
                yield java.util.Arrays.stream(spec.split(",")).map(String::trim)
                        .map(p -> archived.containsKey(p) ? archived.get(p) : MarketCatalog.require(p)).toList();
            }
        };
    }

    /** The value of a key as a local date (Europe/Berlin, start of day) or an ISO instant, in microseconds. */
    static long time(OpenTsxConf conf, String key) {
        String s = conf.get(key);
        try {
            return s.length() == 10 ? Micro.berlinDay(LocalDate.parse(s)) : Micro.of(Instant.parse(s));
        } catch (DateTimeException | ArithmeticException e) {
            throw new OpenTsxConfException(named(conf, key), "\"" + s + "\" is not a date",
                    "expected a local date YYYY-MM-DD (Europe/Berlin) or an ISO instant such as 2024-01-01T00:00:00Z");
        }
    }

    /** The name of a key as the user most likely gave it: its flag when the command line set it, else the key. */
    public static String named(OpenTsxConf conf, String key) {
        String flag = conf.registry().entry(key, key).flag();
        return flag != null && "commandLine".equals(conf.source(key)) ? "--" + flag : key;
    }

    /** The file a key names; a missing file is a usage error. */
    static Path inputFile(OpenTsxConf conf, String key) {
        String name = conf.get(key);
        Path p = Path.of(name);
        if (!Files.isRegularFile(p)) {
            throw new OpenTsxConfException(name, "no such file", "give an existing file");
        }
        return p;
    }

    static void writeCsv(Path p, MarketSeries s) {
        StringBuilder sb = new StringBuilder(Archive.HEADER).append('\n');
        for (Point pt : s.points()) {
            sb.append(Micro.instant(pt.tMicros())).append(',').append(pt.dtMicros() / Micro.SECOND).append(',')
                    .append(pt.value()).append('\n');
        }
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, sb, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(p + ": cannot write the CSV file", e);
        }
    }

    static Map<String, String> props(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : csv.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("item \"" + kv + "\" is not allowed; expected key=value");
            }
            out.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return out;
    }

    static String text(Path f) {
        try {
            byte[] b = Files.readAllBytes(f);
            String s = new String(b, StandardCharsets.UTF_8);
            if (s.indexOf('�') >= 0) {   // German CSV downloads are often ISO-8859-1
                s = new String(b, StandardCharsets.ISO_8859_1);
            }
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (IOException e) {
            throw new UncheckedIOException(f + ": cannot read the file", e);
        }
    }

    static List<String> lines(Path f) {
        return text(f).lines().toList();
    }

    private static int failed(Exception e, PrintStream err) {
        LoggerFactory.getLogger(MarketCli.class).debug("stack trace of the failed run", e);
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

    /** The message and its causes on one line; a checked exception with its class, its message is often a bare path. */
    static String causes(Throwable t) {
        boolean bare = !(t instanceof RuntimeException) || t.getMessage() == null;
        StringBuilder s = new StringBuilder(bare ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return oneLine(s.toString());
    }

    private static String oneLine(String s) {
        return String.valueOf(s).replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }
}
