package org.opentsx.market.archive;

import org.opentsx.dwd.CdcObservations;
import org.opentsx.dwd.Variable;
import org.opentsx.dwd.WeatherSeries;
import org.opentsx.market.MarketCatalog;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;
import org.opentsx.market.SeriesDef;
import org.opentsx.market.source.Fetcher;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backfill of DWD CDC station observations into the history archive ({@code dwd-cdc/<station>/<parameter>/}),
 * parsed with {@link CdcObservations} of {@code opentsx-dwd}:
 * <ul>
 *   <li>{@code air_temperature}: hourly {@code TT_TU} (°C), {@code hourly/air_temperature/{historical,recent}};</li>
 *   <li>{@code wind_speed}: hourly mean {@code F} (m/s), {@code hourly/wind/{historical,recent}};</li>
 *   <li>{@code global_radiation}: 10-minute {@code GS_10} as mean irradiance (W/m²),
 *       {@code 10_minutes/solar/{historical,recent}}.</li>
 * </ul>
 * The zips of a directory are found in its listing ({@code historical} file names carry their date range, e.g.
 * {@code stundenwerte_TU_03166_19820101_20251231_hist.zip}); only zips overlapping the requested years are read, and
 * nothing is downloaded when every requested year is already complete. "recent" (about the last 500 days, not yet
 * fully quality-controlled) overrides "historical" where both have a value. Instantaneous values (temperature) are
 * stored as the interval starting at the measurement time; means are stamped at their interval start by the parser.
 */
public final class WeatherBackfill {

    public static final String CDC = "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/";
    private static final Pattern RANGE = Pattern.compile("_(\\d{8})_(\\d{8})_hist\\.zip$");

    /** Where a parameter comes from. */
    record Product(String dir, String prefix, Variable variable) {
    }

    static final Map<String, Product> PRODUCTS = Map.of(
            "air_temperature", new Product("hourly/air_temperature/", "stundenwerte_TU_", Variable.AIR_TEMPERATURE),
            "wind_speed", new Product("hourly/wind/", "stundenwerte_FF_", Variable.WIND_SPEED),
            "global_radiation", new Product("10_minutes/solar/", "10minutenwerte_SOLAR_", Variable.GLOBAL_IRRADIANCE));

    private final Archive archive;
    private final Fetcher fetcher;
    private final String base;
    private final PrintStream log;
    private final Map<String, String> listings = new HashMap<>();

    public WeatherBackfill(Archive archive, Fetcher fetcher, String base, PrintStream log) {
        this.archive = archive;
        this.fetcher = fetcher;
        this.base = base;
        this.log = log;
    }

    /** One station parameter for the years {@code fromYear..toYear}. */
    public List<Backfill.Outcome> run(String station, String parameter, int fromYear, int toYear, boolean force,
                                      Instant now) {
        SeriesDef def = MarketCatalog.dwd(station, parameter);
        Product p = PRODUCTS.get(parameter);
        List<Integer> todo = new ArrayList<>();
        List<Backfill.Outcome> out = new ArrayList<>();
        Manifest m = archive.manifest(def);
        for (int y = fromYear; y <= toYear; y++) {
            Manifest.Entry e = m.years.get(Integer.toString(y));
            if (!force && e != null && e.complete() && archive.exists(def, y)
                    && Archive.sha256(archive.file(def, y)).equals(e.sha256())) {
                out.add(new Backfill.Outcome(def, y, Backfill.Status.SKIPPED_COMPLETE, e.rows(), "already archived"));
            } else {
                todo.add(y);
            }
        }
        if (todo.isEmpty()) {
            return out;
        }
        List<String> queries = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        try {
            for (String kind : List.of("historical/", "recent/")) {
                String dir = base + p.dir() + kind;
                for (String zip : zips(dir, p.prefix() + station + "_")) {
                    Matcher r = RANGE.matcher(zip);
                    if (r.find() && (Integer.parseInt(r.group(2).substring(0, 4)) < todo.get(0)
                            || Integer.parseInt(r.group(1).substring(0, 4)) > todo.get(todo.size() - 1))) {
                        continue;
                    }
                    queries.add(dir + zip);
                    points.addAll(points(CdcObservations.read(fetcher.getBytes(dir + zip)), p.variable()));
                }
            }
        } catch (IOException | RuntimeException e) {
            for (int y : todo) {
                out.add(new Backfill.Outcome(def, y, Backfill.Status.FAILED, 0, e.getClass().getSimpleName() + ": "
                        + e.getMessage()));
            }
            return out;
        }
        MarketSeries s = new MarketSeries(def, points);   // later points (recent) replace earlier ones (historical)
        Backfill b = new Backfill(archive, null, Map.of(), log);
        for (int y : todo) {
            MarketSeries ys = s.slice(Micro.utcYear(y), Micro.utcYear(y + 1));
            if (ys.isEmpty()) {
                out.add(new Backfill.Outcome(def, y, Backfill.Status.EMPTY, 0, "no data in the CDC files"));
                continue;
            }
            out.add(b.store(def, y, ys, queries, now, Math.min(Micro.utcYear(y + 1), Micro.of(now))));
        }
        return out;
    }

    /** Zip files of a CDC directory whose name starts with {@code prefix}. */
    List<String> zips(String dir, String prefix) {
        String html = listings.computeIfAbsent(dir, fetcher::get);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("href=\"(" + Pattern.quote(prefix) + "[^\"]*\\.zip)\"").matcher(html);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    static List<Point> points(List<WeatherSeries> series, Variable v) {
        List<Point> out = new ArrayList<>();
        for (WeatherSeries w : series) {
            if (w.variable() != v) {
                continue;
            }
            long[] ts = w.timestamps();
            double[] vals = w.values();
            for (int i = 0; i < ts.length; i++) {
                if (!Double.isNaN(vals[i])) {
                    out.add(new Point(ts[i], w.intervalMicros(), vals[i]));
                }
            }
        }
        return out;
    }

    /** Parameters of the backfill, in CLI order. */
    public static List<String> parameters() {
        return List.of("air_temperature", "wind_speed", "global_radiation");
    }
}
