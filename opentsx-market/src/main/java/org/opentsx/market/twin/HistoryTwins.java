package org.opentsx.market.twin;

import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.market.MarketCatalog;
import org.opentsx.market.MarketCli;
import org.opentsx.market.Micro;
import org.opentsx.market.SeriesDef;
import org.opentsx.market.archive.Archive;
import org.opentsx.twin.Mode;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.boat.BoatData;
import org.opentsx.twin.demo.BoatDemo;
import org.opentsx.twin.demo.H2ChainDemo;
import org.opentsx.twin.house.HouseKpis;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The digital twins driven by the history archive ({@code bin/opentsx twin|house|boat --history data/history ...}).
 * Without an archive the default demos of {@code opentsx-twin} stay as they are (synthetic). They read their keys
 * through {@link OpenTsxConf#load} as the commands {@code history-twin twin|house|boat}; {@code --help} lists them.
 * Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error, a missing archive or archived series
 * included.
 * <pre>
 * twin  --history data/history --year 2024 [--from 2024-03-01 --days 31] [--wind smard|dwd] [--cf 0.30]
 * house --history data/history --weather-year 2023 | 2022-2025 [--station 03166] [--hdd-ref 4300] [--levers]
 * boat  --history data/history --weather-year 2024 [--start 2024-07-06] [--days 14] [--radiation-station 06163]
 * common: [--sites file.yaml] [--station id] (temperature) [--radiation-station id]
 * </pre>
 */
public final class HistoryTwins {

    static final String COMMAND = "history-twin";
    static final Pattern YEARS = Pattern.compile("([0-9]{4})(?:-([0-9]{4}))?");

    private HistoryTwins() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err));
    }

    /** {@code args} start with {@code twin}, {@code house} or {@code boat}, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                Registry registry = Registry.shipped();
                out.print(loaded.command() == null ? registry.help() : registry.help(loaded.command()));
            } else if (loaded.version()) {
                out.print(ConfCli.versionLine());
            } else {
                run(loaded.command(), loaded.conf(), out);
            }
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + oneLine(e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(HistoryTwins.class).debug("stack trace of the failed run", e);
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }
        out.flush();
        if (out.checkError()) {
            err.print("opentsx: stdout: cannot write the output; check that standard output is open and not full\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /** A missing archive or archived series is a usage error: the message says how to fill it. */
    static void run(String command, OpenTsxConf conf, PrintStream out) {
        Archive archive = new Archive(Path.of(conf.get("opentsx.archive.dir")));
        if (!Files.isDirectory(archive.root())) {
            throw new IllegalArgumentException(archive.root() + ": no history archive; run bin/opentsx market backfill "
                    + "--archive " + archive.root() + ", or drop --history for the synthetic demo");
        }
        HistoryInputs h = new HistoryInputs(archive);
        Path sites = conf.getOption("opentsx.history.sites").map(Path::of).orElse(null);
        if (sites != null && !Files.isRegularFile(sites)) {
            throw new OpenTsxConfException(sites.toString(), "no such file", "give an existing file");
        }
        switch (command) {
            case "history-twin twin" -> h2(h, HistorySites.get(sites, HistorySites.PARK), conf, out);
            case "history-twin house" -> house(h, station(HistorySites.get(sites, HistorySites.HOUSE), conf), conf, out);
            case "history-twin boat" -> boat(h, station(HistorySites.get(sites, HistorySites.BOAT), conf), conf, out);
            default -> throw new IllegalStateException(command + ": not a command of " + HistoryTwins.class.getName());
        }
    }

    private static HistorySites.Site station(HistorySites.Site s, OpenTsxConf conf) {
        return new HistorySites.Site(s.id(), s.latitude(), s.longitude(), stationId(conf, "opentsx.history.station",
                s.temperature()), s.wind(), stationId(conf, "opentsx.history.radiationStation", s.radiation()),
                s.anemometerHeightM(), s.hubHeightM(), s.shearExponent());
    }

    /** The station id of {@code key}, 5 digits as in the sites file, or {@code absent}. */
    private static String stationId(OpenTsxConf conf, String key, String absent) {
        Optional<String> v = conf.getOption(key);
        if (v.isPresent() && !v.get().matches("[0-9]{5}")) {
            throw new OpenTsxConfException(MarketCli.named(conf, key), "\"" + v.get() + "\" is not a station id",
                    "expected a station id of 5 digits such as 03166");
        }
        return v.orElse(absent);
    }

    static void h2(HistoryInputs h, HistorySites.Site park, OpenTsxConf conf, PrintStream out) {
        int year = conf.getInt("opentsx.history.twin.year");
        LocalDate first = day(conf, "opentsx.history.twin.from", LocalDate.of(year, 1, 1));
        long t0 = Micro.of(first.atStartOfDay(ZoneOffset.UTC).toInstant());
        int days = conf.getOption("opentsx.history.twin.days").isPresent()
                ? days(conf, "opentsx.history.twin.days", H2ChainDemo.MAX_STEPS)
                : toEndOfYear(conf, year, first);
        String wind = conf.get("opentsx.history.twin.wind");
        double cf = conf.getDouble("opentsx.history.twin.capacityFactor");
        require(h.archive(), "smard/day_ahead_price", t0);
        require(h.archive(), wind.equals("dwd") ? MarketCatalog.dwd(park.wind(), "wind_speed").path() : "smard/wind_onshore", t0);
        TwinPlan plan = H2History.plan(Micro.instant(t0), days * 96);
        H2History s = new H2History(h, park, cf);
        H2History.Run ref = s.inputs(plan, wind, 1.0);
        out.printf(Locale.ROOT, "H2 chain on history: %s + %d days (UTC), step 15 min, wind %s, coverage %s%n", first,
                days, wind.equals("dwd") ? "DWD station " + park.wind() + " via power curve" : "SMARD onshore DE scaled to "
                        + "the park (mean capacity factor " + conf.get("opentsx.history.twin.capacityFactor") + ")",
                pct(ref.coverage()));
        out.println("Origins: price REPORTED, wind ESTIMATED (model on reported/measured data), schedule "
                + (ref.forecastKw() != null ? "FORECAST" : "ESTIMATED (actual wind, no forecast archived)")
                + ", electrolyzer dispatch SYNTHETIC (price-limit rule); ely_cost = electrolyzer energy valued at the "
                + "day-ahead price");
        Map<String, Map<String, Double>> rows = new LinkedHashMap<>();
        RunResult shadow = H2History.run(ref, Mode.SHADOW);
        rows.put("always (reference)", H2History.kpis(ref, shadow));
        for (double q : new double[]{0.6, 0.4, 0.2}) {
            H2History.Run r = s.inputs(plan, wind, q);
            rows.put(String.format(Locale.ROOT, "cheapest %.0f %%", q * 100), H2History.kpis(r, H2History.run(r, Mode.WHAT_IF)));
        }
        out.println("run " + shadow.runId() + "  hash " + shadow.hash());
        table(out, rows, List.of("price_limit_eur_mwh", "h2_kg", "ely_full_load_h", "ely_cost_eur",
                "power_cost_eur_per_kg", "park_mwh", "feed_in_revenue_eur", "feed_in_at_negative_price_mwh",
                "deviation_extra_mwh", "deviation_short_mwh", "trips", "p_max_bar", "buffer_min_bar", "line_min_bar", "line_max_bar",
                "compressor_mwh", "h2_shortfall_kg"));
        out.println("Modelled, not proven: the dispatch rule and the park scaling are assumptions; prices and the wind "
                + "situation are real.");
    }

    static void house(HistoryInputs h, HistorySites.Site site, OpenTsxConf conf, PrintStream out) {
        String spec = conf.get("opentsx.history.house.weatherYear");
        Matcher years = YEARS.matcher(spec);
        if (!years.matches() || years.group(2) != null && Integer.parseInt(years.group(2)) < Integer.parseInt(years.group(1))) {
            throw new OpenTsxConfException(MarketCli.named(conf, "opentsx.history.house.weatherYear"), "\"" + spec
                    + "\" is not a year or a range of years", "expected YYYY or YYYY-YYYY such as 2022-2025");
        }
        int a = Integer.parseInt(years.group(1));
        int b = years.group(2) != null ? Integer.parseInt(years.group(2)) : a;
        double hddRef = conf.getDouble("opentsx.house.hddRef");
        HouseHistory hh = new HouseHistory(h, site);
        out.println("House twin on real weather: station " + site.temperature() + " (air temperature)"
                + (site.radiation() != null ? ", " + site.radiation() + " (global radiation)" : "")
                + "; house parameters are the example's assumptions");
        Map<String, Map<String, Double>> rows = new LinkedHashMap<>();
        for (int y = a; y <= b; y++) {
            require(h.archive(), MarketCatalog.dwd(site.temperature(), "air_temperature").path(), Micro.utcYear(y));
            HouseHistory.WeatherYear wy = hh.year(y);
            RunResult r = HouseHistory.shadow(wy);
            HouseKpis k = HouseKpis.of(wy.plan(), r, hddRef);
            Map<String, Double> row = new LinkedHashMap<>();
            row.put("t_coverage_pct", 100 * wy.temperatureCoverage());
            row.put("ghi_coverage_pct", 100 * wy.radiationCoverage());
            row.put("t_mean_c", HouseHistory.meanTemperature(wy));
            row.put("hdd_kd", k.hddKd());
            row.put("oil_l", k.oilLitres());
            row.put("wood_ster", k.woodSter());
            row.put("electricity_kwh", k.electricityKwh());
            row.put("final_heat_kwh", k.finalHeatKwh());
            row.put("normalized_kwh", k.normalizedFinalHeatKwh());
            row.put("cost_eur", k.costEur());
            row.put("co2_kg", k.co2Kg());
            rows.put(Integer.toString(y), row);
            if (conf.getBoolean("opentsx.history.house.levers")) {
                out.println();
                out.println("What-if " + y + " (alternative minus reference, same weather):");
                HouseHistory.levers(wy, k, hddRef).forEach((name, d) -> out.printf(Locale.ROOT,
                        "   %-60s oil %+7.0f l  wood %+6.2f Ster  el %+6.0f kWh  EUR %+6.0f%n", name, d.get("oil_l"),
                        d.get("wood_ster"), d.get("electricity_kwh"), d.get("cost_eur")));
            }
        }
        out.println();
        table(out, rows, List.of("t_coverage_pct", "ghi_coverage_pct", "t_mean_c", "hdd_kd", "oil_l", "wood_ster",
                "electricity_kwh", "final_heat_kwh", "normalized_kwh", "cost_eur", "co2_kg"));
        out.printf(Locale.ROOT, "normalized_kwh: final heat scaled to the reference G20/15 of %.0f Kd%n", hddRef);
    }

    static void boat(HistoryInputs h, HistorySites.Site site, OpenTsxConf conf, PrintStream out) {
        int year = conf.getInt("opentsx.history.boat.weatherYear");
        LocalDate first = day(conf, "opentsx.history.boat.start", BoatHistory.defaultStart(year));
        int days = days(conf, "opentsx.history.boat.days", BoatData.MAX_STEPS);
        require(h.archive(), MarketCatalog.dwd(site.radiation(), "global_radiation").path(),
                Micro.of(first.atStartOfDay(ZoneOffset.UTC).toInstant()));
        BoatHistory.Trip trip = new BoatHistory(h, site).trip(first, days);
        out.printf(Locale.ROOT, "Boat twin on real irradiance: station %s, trip %s from %s (%d days), irradiance "
                + "coverage %.1f %%%n", site.radiation(), BoatDemo.TRIP, first, days, 100 * trip.radiationCoverage());
        Twin twin = new Twin(trip.plan());
        RunResult real = twin.shadow(trip.inputs(), BoatDemo.reference());
        RunResult synth = twin.shadow(trip.logbookOnly(), BoatDemo.reference());
        Map<String, Map<String, Double>> rows = new LinkedHashMap<>();
        rows.put("measured irradiance", BoatDemo.kpis(real));
        rows.put("synthetic clearness", BoatDemo.kpis(synth));
        for (BoatDemo.Alternative a : BoatDemo.alternatives(trip.plan(), trip.inputs(), null)) {
            rows.put(a.label(), BoatDemo.kpis(BoatDemo.run(trip.plan(), a, List.of())));
        }
        out.println("run " + real.runId() + "  hash " + real.hash());
        table(out, rows, List.of("solar_yield_wh", "solar_used_wh", "shore_wh", "solar_share_pct", "unmet_wh",
                "unmet_h", "soc_min_pct", "curtailed_wh"));
    }

    private static LocalDate day(OpenTsxConf conf, String key, LocalDate absent) {
        Optional<String> v = conf.getOption(key);
        if (v.isEmpty()) {
            return absent;
        }
        try {
            return LocalDate.parse(v.get());
        } catch (DateTimeException e) {
            throw new OpenTsxConfException(MarketCli.named(conf, key), "\"" + v.get() + "\" is not a day",
                    "expected a UTC day YYYY-MM-DD");
        }
    }

    /** The days from {@code first} to the end of {@code year}: 1 to the days of 15-minute steps an H2 chain run holds. */
    private static int toEndOfYear(OpenTsxConf conf, int year, LocalDate first) {
        LocalDate end = LocalDate.of(year + 1, 1, 1);
        long max = H2ChainDemo.MAX_STEPS / 96;
        long days = ChronoUnit.DAYS.between(first, end);
        if (days < 1 || days > max) {
            String from = "opentsx.history.twin.from";
            throw new OpenTsxConfException(MarketCli.named(conf, from), "\"" + conf.get(from) + "\" is not allowed",
                    "expected a UTC day from " + end.minusDays(max) + " to " + end.minusDays(1) + ", so that the run to "
                            + "the end of " + MarketCli.named(conf, "opentsx.history.twin.year") + " " + year + " has 1 to "
                            + max + " days");
        }
        return (int) days;
    }

    /** The days of {@code key}: 1 to the days of 15-minute steps in {@code maxSteps}, the most steps a run holds. */
    private static int days(OpenTsxConf conf, String key, long maxSteps) {
        int days = conf.getInt(key);
        long max = maxSteps / 96;
        if (days < 1 || days > max) {
            throw new OpenTsxConfException(MarketCli.named(conf, key), "\"" + days + "\" is not allowed", "expected 1 to " + max);
        }
        return days;
    }

    /** Fails with a hint if the archive has no data for the series in the year of {@code t}. */
    static void require(Archive a, String path, long t) {
        String[] p = path.split("/", 2);
        SeriesDef def = path.startsWith("dwd-cdc/") ? MarketCatalog.dwd(p[1].split("/")[0], p[1].split("/")[1])
                : MarketCatalog.require(path);
        if (a.read(def, Micro.yearOf(t)).isEmpty()) {
            String hint = def.source().equals("dwd-cdc")
                    ? "weather history comes from the DWD backfill (opentsx-dwd); see opentsx-market/README.md"
                    : "run: bin/opentsx market backfill --archive " + a.root() + " --series " + path;
            throw new IllegalArgumentException(path + ": no archived data in " + Micro.yearOf(t) + "; " + hint);
        }
    }

    /** The message and its causes on one line. */
    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
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

    private static String pct(Map<String, Double> cov) {
        List<String> s = new ArrayList<>();
        cov.forEach((k, v) -> s.add(String.format(Locale.ROOT, "%s %.1f %%", k, 100 * v)));
        return String.join(", ", s);
    }

    static void table(PrintStream out, Map<String, Map<String, Double>> rows, List<String> keys) {
        out.printf("   %-34s", "");
        rows.keySet().forEach(r -> out.printf(" %22s", r.length() > 22 ? r.substring(0, 22) : r));
        out.println();
        for (String k : keys) {
            out.printf("   %-34s", k);
            for (Map<String, Double> r : rows.values()) {
                Double v = r.get(k);
                out.printf(Locale.ROOT, " %22s", v == null ? "-" : v.isInfinite() ? "none" : Math.abs(v) >= 100 || v == Math.rint(v)
                        ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v));
            }
            out.println();
        }
    }
}
