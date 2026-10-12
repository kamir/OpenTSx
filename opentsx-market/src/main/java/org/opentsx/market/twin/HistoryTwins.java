package org.opentsx.market.twin;

import org.opentsx.market.MarketCatalog;
import org.opentsx.market.Micro;
import org.opentsx.market.SeriesDef;
import org.opentsx.market.archive.Archive;
import org.opentsx.twin.Mode;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.demo.BoatDemo;
import org.opentsx.twin.demo.HouseDemo;
import org.opentsx.twin.house.HouseKpis;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The digital twins driven by the history archive ({@code bin/opentsx twin|house|boat --history data/history ...}).
 * Without an archive the default demos of {@code opentsx-twin} stay as they are (synthetic).
 * <pre>
 * twin  --history data/history --year 2024 [--from 2024-03-01 --days 31] [--wind smard|dwd] [--cf 0.30]
 * house --history data/history --weather-year 2023 | 2022-2025 [--station 03166] [--hdd-ref 4300] [--levers]
 * boat  --history data/history --weather-year 2024 [--start 2024-07-06] [--days 14] [--station 02961]
 * common: [--sites file.yaml]
 * </pre>
 */
public final class HistoryTwins {

    private HistoryTwins() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: HistoryTwins twin|house|boat --history <dir> ...");
            System.exit(1);
        }
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String k = args[i].replaceFirst("^--", "");
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            o.put(k, flag ? "true" : args[++i]);
        }
        try {
            System.exit(run(args[0], o, System.out));
        } catch (IllegalArgumentException e) {
            System.err.println(args[0] + " --history: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String cmd, Map<String, String> o, PrintStream out) {
        Archive archive = new Archive(Path.of(o.getOrDefault("history", "data/history")));
        if (!Files.isDirectory(archive.root())) {
            throw new IllegalArgumentException("no history archive at " + archive.root() + " - run: bin/opentsx market "
                    + "backfill --archive " + archive.root() + " (or drop --history for the synthetic demo)");
        }
        HistoryInputs h = new HistoryInputs(archive);
        Path sites = o.containsKey("sites") ? Path.of(o.get("sites")) : null;
        return switch (cmd) {
            case "twin" -> h2(h, HistorySites.get(sites, HistorySites.PARK), o, out);
            case "house" -> house(h, station(HistorySites.get(sites, HistorySites.HOUSE), o), o, out);
            case "boat" -> boat(h, station(HistorySites.get(sites, HistorySites.BOAT), o), o, out);
            default -> throw new IllegalArgumentException("unknown twin " + cmd);
        };
    }

    private static HistorySites.Site station(HistorySites.Site s, Map<String, String> o) {
        return new HistorySites.Site(s.id(), s.latitude(), s.longitude(), o.getOrDefault("station", s.temperature()),
                s.wind(), o.getOrDefault("radiation-station", s.radiation()), s.anemometerHeightM(), s.hubHeightM(),
                s.shearExponent());
    }

    static int h2(HistoryInputs h, HistorySites.Site park, Map<String, String> o, PrintStream out) {
        int year = Integer.parseInt(o.getOrDefault("year", "2024"));
        LocalDate first = LocalDate.parse(o.getOrDefault("from", year + "-01-01"));
        long t0 = Micro.of(first.atStartOfDay(ZoneOffset.UTC).toInstant());
        int days = Integer.parseInt(o.getOrDefault("days",
                Long.toString((Micro.utcYear(year + 1) - t0) / Micro.DAY)));
        String wind = o.getOrDefault("wind", "smard");
        require(h.archive(), "smard/day_ahead_price", t0);
        require(h.archive(), wind.equals("dwd") ? MarketCatalog.dwd(park.wind(), "wind_speed").path() : "smard/wind_onshore", t0);
        TwinPlan plan = H2History.plan(Micro.instant(t0), days * 96);
        H2History s = new H2History(h, park, Double.parseDouble(o.getOrDefault("cf", "0.30")));
        H2History.Run ref = s.inputs(plan, wind, 1.0);
        out.printf(Locale.ROOT, "H2 chain on history: %s + %d days (UTC), step 15 min, wind %s, coverage %s%n", first,
                days, wind.equals("dwd") ? "DWD station " + park.wind() + " via power curve" : "SMARD onshore DE scaled to "
                        + "the park (mean capacity factor " + o.getOrDefault("cf", "0.30") + ")", pct(ref.coverage()));
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
                "deviation_extra_mwh", "deviation_short_mwh", "trips"));
        out.println("Modelled, not proven: the dispatch rule and the park scaling are assumptions; prices and the wind "
                + "situation are real.");
        return 0;
    }

    static int house(HistoryInputs h, HistorySites.Site site, Map<String, String> o, PrintStream out) {
        String spec = o.getOrDefault("weather-year", "2023");
        int a = Integer.parseInt(spec.split("-")[0]);
        int b = spec.contains("-") ? Integer.parseInt(spec.split("-")[1]) : a;
        double hddRef = Double.parseDouble(o.getOrDefault("hdd-ref", Double.toString(HouseDemo.HDD_REF)));
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
            if (o.containsKey("levers")) {
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
        return 0;
    }

    static int boat(HistoryInputs h, HistorySites.Site site, Map<String, String> o, PrintStream out) {
        int year = Integer.parseInt(o.getOrDefault("weather-year", "2024"));
        LocalDate first = o.containsKey("start") ? LocalDate.parse(o.get("start")) : BoatHistory.defaultStart(year);
        int days = Integer.parseInt(o.getOrDefault("days", "14"));
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
        return 0;
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
            throw new IllegalArgumentException("no archived data for " + path + " in " + Micro.yearOf(t) + " - " + hint);
        }
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
