package org.opentsx.market;

import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
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
import org.opentsx.model.v2.Episode;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.iceberg.IcebergSeriesStore;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code bin/opentsx market <command>}: collect electricity-market data, keep the history archive, load it into a
 * store. Run without arguments for the usage.
 */
public final class MarketCli {

    static final String USAGE = """
            usage: bin/opentsx market <command> [options]

              prices    --source smard|energy-charts|entsoe --from D --to D [--out f.csv[.gz]] [--offline file]
              series    --name source/series --from D --to D [--out f.csv[.gz]] [--offline file]
              rebap     --from D --to D [--out f.csv] [--offline file.csv] [--archive dir]
              marktwert --from D --to D [--out f.csv] [--offline file.csv] [--archive dir]
              import    --file f.csv --name partner-series --metric m --unit u [--origin REPORTED|FORECAST]
                        [--value-column value] [--resolution PT15M] [--zone Europe/Berlin] [--archive dir]
              backfill  [--archive data/history] [--series market|all|source/series,...] [--from-year 2019]
                        [--to-year YYYY] [--delay-ms 500] [--force]
              forward   [--archive data/history] [--series market|...] [--delay-ms 500]   (daily job: running year)
              report    [--archive data/history] [--series ...] [--requality]   (--requality: recompute the
                        quality reports from the archived files, e.g. after a rule change; no download)
              weather-backfill [--archive data/history] [--from-year 2019] [--to-year Y]
                        [--stations 03166:air_temperature,01358:global_radiation,...] [--sites file.yaml]
                        (DWD CDC observations of the twin sites; default: all stations of the sites file)
              weather-forward [--archive data/history] [--stations ...]   (daily job: running and previous year)
              mosmix-archive [--archive data/history] [--stations 10579,10097,10046,E5411]
                        (forward archive of raw MOSMIX_L forecasts; run every 6 h)
              load      [--archive data/history] [--series ...] [--from-year Y --to-year Y] [--from-day D --to-day D]
                        [--iceberg k=v,... --namespace tsx]
              publish   --series source/name,... --kafscale host:port --domain wind [--archive data/history]
                        [--from-year Y --to-year Y] [--from-day D --to-day D] [--batch 100] [--pause-ms 3000] [--dry-run]
                        (archived series as raw episodes into tsx.<domain>.episodes.v2; stops at the first
                        refusal of the broker; an episode keeps its identity, so reading tools can tell a repeat)
              catalog

              D = local date (Europe/Berlin, start of day) or an ISO instant; --to is exclusive.
              Credentials (never in files under git): ENTSOE_TOKEN, NTP_CLIENT_ID, NTP_CLIENT_SECRET.
            """;

    /** Series of the default market backfill ("market"). */
    public static final List<String> MARKET = List.of("smard/day_ahead_price", "smard/load", "smard/residual_load",
            "smard/wind_onshore", "smard/wind_offshore", "smard/solar", "smard/forecast_wind_onshore",
            "smard/forecast_wind_offshore", "smard/forecast_solar", "smard/forecast_load",
            "energy-charts/day_ahead_price", "entsoe/day_ahead_price", "entsoe/load", "entsoe/forecast_wind_onshore",
            "entsoe/forecast_wind_offshore", "entsoe/forecast_solar", "netztransparenz/rebap_short",
            "netztransparenz/rebap_long", "netztransparenz/marktwert_epex", "netztransparenz/marktwert_wind_onshore",
            "netztransparenz/marktwert_wind_offshore", "netztransparenz/marktwert_solar");

    private MarketCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.print(USAGE);
            System.exit(args.length == 0 ? 1 : 0);
        }
        try {
            System.exit(run(args[0], options(args), System.out, System.getenv(), Instant.now()));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            System.err.println("market " + args[0] + ": " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String cmd, Map<String, String> o, PrintStream out, Map<String, String> env, Instant now) {
        Archive archive = new Archive(Path.of(o.getOrDefault("archive", "data/history")));
        switch (cmd) {
            case "catalog" -> MarketCatalog.all().forEach(d -> out.printf(Locale.ROOT, "%-42s %-34s %-8s %-9s %s%n",
                    d.path(), d.metric(), d.unit(), d.origin(), d.tags()));
            case "prices" -> {
                String src = o.getOrDefault("source", "smard");
                return fetchCommand(MarketCatalog.require(src + "/day_ahead_price"), o, out, env, archive, now);
            }
            case "series" -> {
                return fetchCommand(MarketCatalog.require(req(o, "name")), o, out, env, archive, now);
            }
            case "rebap" -> {
                return fetchCommand(MarketCatalog.require("netztransparenz/rebap_short"), o, out, env, archive, now);
            }
            case "marktwert" -> {
                return fetchCommand(MarketCatalog.require("netztransparenz/marktwert_wind_onshore"), o, out, env, archive,
                        now);
            }
            case "import" -> {
                Map<String, String> tags = new LinkedHashMap<>();
                tags.put("partner", o.getOrDefault("partner", "default"));
                SeriesDef def = MarketCatalog.partner(req(o, "name"), req(o, "metric"), req(o, "unit"),
                        o.getOrDefault("origin", "REPORTED"), tags);
                List<Point> pts = PartnerCsv.parse(lines(Path.of(req(o, "file"))), o.get("value-column"),
                        o.containsKey("resolution") ? Duration.parse(o.get("resolution")) : null,
                        o.containsKey("zone") ? ZoneId.of(o.get("zone")) : null);
                MarketSeries s = new MarketSeries(def, pts);
                out.println(def.path() + ": " + s.size() + " points");
                storeYears(archive, s, List.of("file:" + o.get("file")), now, env, out);
            }
            case "backfill", "forward" -> {
                int current = now.atZone(ZoneOffset.UTC).getYear();
                int from = cmd.equals("forward") ? current - 1 : Integer.parseInt(o.getOrDefault("from-year", "2019"));
                int to = Integer.parseInt(o.getOrDefault("to-year", Integer.toString(current)));
                Collector c = Collector.online(Long.parseLong(o.getOrDefault("delay-ms", "500")));
                List<Backfill.Outcome> r = new Backfill(archive, c, env, out).run(select(o.getOrDefault("series", "market")),
                        from, to, o.containsKey("force"), now);
                long failed = r.stream().filter(x -> x.status() == Backfill.Status.FAILED).count();
                out.printf("backfill done: %d series-years, %d fetched, %d skipped (complete), %d failed, archive %s%n",
                        r.size(), r.stream().filter(x -> x.status() == Backfill.Status.FETCHED).count(),
                        r.stream().filter(x -> x.status() == Backfill.Status.SKIPPED_COMPLETE).count(), failed,
                        archive.root().toAbsolutePath());
                return failed > 0 ? 3 : 0;
            }
            case "report" -> report(archive, select(o.getOrDefault("series", "all-archived"), archive), out,
                    o.containsKey("requality") ? now : null);
            case "weather-backfill", "weather-forward" -> {
                int current = now.atZone(ZoneOffset.UTC).getYear();
                int from = cmd.equals("weather-forward") ? current - 1 : Integer.parseInt(o.getOrDefault("from-year", "2019"));
                int to = Integer.parseInt(o.getOrDefault("to-year", Integer.toString(current)));
                WeatherBackfill w = new WeatherBackfill(archive, new Http(Long.parseLong(o.getOrDefault("delay-ms", "500")), 4),
                        WeatherBackfill.CDC, out);
                long failed = 0;
                for (String[] sp : weatherJobs(o)) {
                    for (Backfill.Outcome r : w.run(sp[0], sp[1], from, to, o.containsKey("force"), now)) {
                        out.printf("%-40s %d  %-16s %8d rows  %s%n", r.def().path(), r.year(), r.status(), r.rows(),
                                r.message());
                        failed += r.status() == Backfill.Status.FAILED ? 1 : 0;
                    }
                }
                return failed > 0 ? 3 : 0;
            }
            case "mosmix-archive" -> {
                List<String> st = o.containsKey("stations") ? List.of(o.get("stations").split(","))
                        : MosmixRawArchive.DEFAULT_STATIONS;
                for (MosmixRawArchive.Result r : new MosmixRawArchive(archive.root(), new Http(1000, 4)).collect(st, now)) {
                    out.printf("mosmix %-6s issue %s  %s%n", r.station(), r.issue(), r.added() ? "added " + r.file()
                            : "already archived");
                }
            }
            case "load" -> {
                return load(archive, o, out);
            }
            case "publish" -> {
                return org.opentsx.market.archive.BrokerPublish.run(archive, o, out);
            }
            default -> throw new IllegalArgumentException("unknown command " + cmd + "\n" + USAGE);
        }
        return 0;
    }

    /** Fetch (or parse an offline file), summarise, optionally write a CSV and store into the archive. */
    private static int fetchCommand(SeriesDef def, Map<String, String> o, PrintStream out, Map<String, String> env,
                                    Archive archive, Instant now) {
        long from = time(req(o, "from"));
        long to = time(req(o, "to"));
        List<MarketSeries> result = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        if (o.containsKey("offline")) {
            Path f = Path.of(o.get("offline"));
            queries.add("file:" + f.getFileName());
            result.addAll(offline(def, f));
        } else {
            String why = Collector.unavailable(def, env);
            if (why != null) {
                out.println("skipped " + def.source() + ": " + why + " (use --offline <file> to import a download)");
                return 4;
            }
            Collector c = Collector.online(Long.parseLong(o.getOrDefault("delay-ms", "300")));
            for (SeriesDef d : siblings(def)) {
                Collector.Fetched f = c.fetch(d, from, to);
                queries.addAll(f.queries());
                result.add(f.series());
            }
        }
        for (MarketSeries s : result) {
            MarketSeries sl = s.slice(from, to);
            out.println(sl.def().path() + " [" + sl.def().unit() + ", " + sl.def().origin() + "]: "
                    + QualityReport.of(sl, from, to).summary());
            if (o.containsKey("out")) {
                Path p = Path.of(result.size() == 1 ? o.get("out") : o.get("out").replaceFirst("(\\.csv)", "-"
                        + sl.def().name() + "$1"));
                writeCsv(p, sl);
                out.println("  written " + p);
            }
            if (o.containsKey("archive") && !sl.isEmpty()) {
                storeYears(archive, sl, queries, now, env, out);
            }
        }
        return 0;
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

    private static void storeYears(Archive archive, MarketSeries s, List<String> queries, Instant now,
                                   Map<String, String> env, PrintStream out) {
        if (s.isEmpty()) {
            return;
        }
        Backfill b = new Backfill(archive, null, env, out);
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

    private static int load(Archive archive, Map<String, String> o, PrintStream out) {
        int y0 = Integer.parseInt(o.getOrDefault("from-year", "2000"));
        int y1 = Integer.parseInt(o.getOrDefault("to-year", "2100"));
        Instant fromDay = org.opentsx.market.archive.BrokerPublish.day(o, "from-day", Instant.MIN);
        Instant toDay = org.opentsx.market.archive.BrokerPublish.day(o, "to-day", Instant.MAX);
        SeriesStore store = o.containsKey("iceberg")
                ? IcebergSeriesStore.open(props(o.get("iceberg")), o.getOrDefault("namespace", "tsx"))
                : new InMemorySeriesStore();
        long episodes = 0;
        long values = 0;
        try (store) {
            for (SeriesDef d : select(o.getOrDefault("series", "all-archived"), archive)) {
                Manifest m = archive.manifest(d);
                for (String y : m.years.keySet()) {
                    int year = Integer.parseInt(y);
                    if (year < y0 || year > y1) {
                        continue;
                    }
                    List<Episode> eps = EpisodeMapper.episodes(new MarketSeries(d, archive.read(d, year)),
                                    "opentsx-market/3.0.1").stream()
                            .filter(e -> !e.getTStart().isBefore(fromDay) && e.getTStart().isBefore(toDay))
                            .map(org.opentsx.market.archive.BrokerPublish::identified).toList();
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
                o.containsKey("iceberg") ? "Iceberg namespace " + o.getOrDefault("namespace", "tsx") : "an in-memory store");
        return 0;
    }

    /** Station/parameter pairs of the weather backfill: {@code --stations id:param,...} or every station of the sites. */
    static List<String[]> weatherJobs(Map<String, String> o) {
        java.util.LinkedHashSet<String> jobs = new java.util.LinkedHashSet<>();
        if (o.containsKey("stations")) {
            jobs.addAll(List.of(o.get("stations").split(",")));
        } else {
            for (HistorySites.Site s : HistorySites.load(o.containsKey("sites") ? Path.of(o.get("sites")) : null).values()) {
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
            String[] p = j.trim().split(":");
            if (p.length != 2 || !WeatherBackfill.parameters().contains(p[1])) {
                throw new IllegalArgumentException("expected station:parameter with parameter in "
                        + WeatherBackfill.parameters() + ", got " + j);
            }
            out.add(p);
        }
        return out;
    }

    static List<SeriesDef> select(String spec) {
        return select(spec, null);
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
                Map<String, SeriesDef> archived = new java.util.HashMap<>();
                if (archive != null) {
                    archive.archivedSeries().forEach(d -> archived.put(d.path(), d));
                }
                yield java.util.Arrays.stream(spec.split(",")).map(String::trim)
                        .map(p -> archived.containsKey(p) ? archived.get(p) : MarketCatalog.require(p)).toList();
            }
        };
    }

    static long time(String s) {
        if (s.length() == 10) {
            return Micro.berlinDay(LocalDate.parse(s));
        }
        return Micro.of(Instant.parse(s));
    }

    static void writeCsv(Path p, MarketSeries s) {
        if (p.toString().endsWith(".gz")) {
            throw new IllegalArgumentException("--out writes plain CSV; use --archive for the gzip'ed archive");
        }
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
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --option, got " + args[i]);
            }
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(k, args[++i]);
            } else {
                out.put(k, "true");
            }
        }
        return out;
    }

    static Map<String, String> props(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : csv.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("expected key=value in " + csv);
            }
            out.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return out;
    }

    private static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + k);
        }
        return v;
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
            throw new UncheckedIOException(e);
        }
    }

    static List<String> lines(Path f) {
        return text(f).lines().toList();
    }
}
