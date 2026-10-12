package org.opentsx.dwd;

import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.house.HouseScenario;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code bin/opentsx dwd <command>}: DWD Open Data collector.
 * <pre>
 * stations --near LAT,LON [--n 5] [--catalog mosmix|cdc] [--offline FILE]
 * mosmix   --station ID [--offline FILE.kmz] [--out FILE.csv] [--daily FILE.csv] [--boat FILE.csv]
 * obs      --station CDC_ID [--param wind,air_temperature,solar] [--hourly] | --offline FILE[,FILE...]
 *          [--out FILE.csv] [--daily FILE.csv] [--boat FILE.csv]
 * house    --station ID [--offline FILE.kmz]     house twin over the MOSMIX horizon, weather bound from DWD
 * </pre>
 * Without {@code --offline} the files are fetched from opendata.dwd.de.
 */
public final class DwdCli {

    private static final String USAGE = """
            usage: bin/opentsx dwd <command> [options]
              stations --near LAT,LON [--n 5] [--catalog mosmix|cdc] [--offline FILE]
                       nearest stations (haversine); mosmix = forecast ids, cdc = observation ids
              mosmix   --station ID [--offline FILE.kmz] [--out FILE.csv] [--daily FILE.csv] [--boat FILE.csv]
                       MOSMIX_L forecast (origin FORECAST): FF, DD, TTT, PPPP, N, Rad1h, RR1c
              obs      --station CDC_ID [--param wind,air_temperature,solar] [--hourly]
                       | --offline FILE[,FILE...]   [--out FILE.csv] [--daily FILE.csv] [--boat FILE.csv]
                       CDC station observations (origin MEASURED), 10-minute 'now' or hourly 'recent'
              house    --station ID [--offline FILE.kmz]
                       house twin (haus-erzgebirge) over the forecast horizon with DWD weather bound
            --out: wide CSV; --daily: datum,t_mittel_c (bin/opentsx house --weather); --boat: zeit,einstrahlung_wm2
            examples: stations --near 50.6,13.0      mosmix --station 10578 --out fichtelberg.csv
                      obs --station 01358 --param wind,air_temperature,solar --out obs.csv""";

    private final PrintStream out;
    private final Map<String, String> opts = new LinkedHashMap<>();

    DwdCli(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        int rc = new DwdCli(System.out).run(args);
        if (rc != 0) {
            System.exit(rc);
        }
    }

    int run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
            out.println(USAGE);
            return args.length == 0 ? 1 : 0;
        }
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                return usage("unexpected argument: " + args[i]);
            }
            String k = args[i].substring(2);
            boolean flag = k.equals("hourly");
            if (!flag && i + 1 >= args.length) {
                return usage("missing value for --" + k);
            }
            opts.put(k, flag ? "true" : args[++i]);
        }
        try {
            return switch (args[0]) {
                case "stations" -> stations();
                case "mosmix" -> mosmix();
                case "obs" -> obs();
                case "house" -> house();
                default -> usage("unknown command: " + args[0]);
            };
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
    }

    private int usage(String msg) {
        System.err.println(msg);
        System.err.println(USAGE);
        return 1;
    }

    private String need(String k) {
        String v = opts.get(k);
        if (v == null) {
            throw new IllegalArgumentException("--" + k + " is required");
        }
        return v;
    }

    // ----------------------------------------------------------------------------------------------- commands

    private int stations() throws IOException {
        String[] ll = need("near").split(",");
        if (ll.length != 2) {
            throw new IllegalArgumentException("--near expects LAT,LON");
        }
        double lat = Double.parseDouble(ll[0].strip());
        double lon = Double.parseDouble(ll[1].strip());
        int n = Integer.parseInt(opts.getOrDefault("n", "5"));
        String which = opts.getOrDefault("catalog", "mosmix");
        StationCatalog cat = opts.containsKey("offline") ? StationCatalog.read(Path.of(opts.get("offline")))
                : StationCatalog.parse(new DwdClient().get(which.equals("cdc") ? DwdClient.CDC_STATIONS : DwdClient.MOSMIX_STATIONS));
        out.printf(Locale.ROOT, "%d stations in catalog; nearest to %.4f, %.4f:%n", cat.stations().size(), lat, lon);
        out.printf(Locale.ROOT, "%-6s %-36s %9s %9s %6s %8s%n", "id", "name", "lat", "lon", "elev", "km");
        for (StationCatalog.Nearby s : cat.nearest(lat, lon, n)) {
            Station st = s.station();
            out.printf(Locale.ROOT, "%-6s %-36s %9.4f %9.4f %6.0f %8.1f%n", st.id(), st.name(), st.latitude(),
                    st.longitude(), st.elevation(), s.distanceKm());
        }
        return 0;
    }

    private Mosmix.Forecast forecast() throws IOException {
        String station = opts.get("station");
        if (opts.containsKey("offline")) {
            try (InputStream in = Files.newInputStream(Path.of(opts.get("offline")))) {
                return Mosmix.read(in, station == null ? null : Set.of(station));
            }
        }
        String id = need("station");
        return Mosmix.read(new ByteArrayInputStream(new DwdClient().get(Mosmix.latestUrl(id))), Set.of(id));
    }

    private int mosmix() throws IOException {
        Mosmix.Forecast f = forecast();
        Mosmix.Placemark p = opts.containsKey("station") ? f.placemark(opts.get("station")) : f.placemarks().get(0);
        out.printf(Locale.ROOT, "MOSMIX %s station %s %s (%.3f, %.3f, %.0f m), issued %s, %d time steps %s .. %s%n",
                f.product(), p.id(), p.name(), p.latitude(), p.longitude(), p.elevation(), f.issueTime(),
                f.timeSteps().length, Micros.toInstant(f.timeSteps()[0]),
                Micros.toInstant(f.timeSteps()[f.timeSteps().length - 1]));
        out.println("models: " + String.join(", ", f.models()));
        List<WeatherSeries> series = f.series(p.id());
        report(series);
        export(series);
        return 0;
    }

    private int obs() throws IOException {
        List<WeatherSeries> series = new ArrayList<>();
        if (opts.containsKey("offline")) {
            for (String file : opts.get("offline").split(",")) {
                series.addAll(CdcObservations.read(Path.of(file.strip())));
            }
        } else {
            String id = need("station");
            DwdClient c = new DwdClient();
            boolean hourly = opts.containsKey("hourly");
            for (String p : opts.getOrDefault("param", hourly ? "wind,air_temperature" : "wind,air_temperature,solar").split(",")) {
                String url = hourly ? CdcObservations.recentHourlyUrl(p.strip(), id) : CdcObservations.nowUrl(p.strip(), id);
                out.println("fetch " + url);
                series.addAll(CdcObservations.read(c.get(url)));
            }
        }
        if (series.isEmpty()) {
            throw new IllegalArgumentException("no observations found");
        }
        report(series);
        export(series);
        return 0;
    }

    private int house() throws IOException {
        Mosmix.Forecast f = forecast();
        String id = opts.containsKey("station") ? opts.get("station") : f.placemarks().get(0).id();
        List<WeatherSeries> series = f.series(id);
        TwinPlan base = HouseScenario.plan();
        long step = base.baseStep().toNanos() / 1000;
        long t0 = Math.floorDiv(f.timeSteps()[0] + step - 1, step) * step;
        int steps = (int) ((f.timeSteps()[f.timeSteps().length - 1] - t0) / step);
        TwinPlan plan = HouseScenario.withParams(base, Map.of(), Micros.toInstant(t0), steps);
        String weather = HouseScenario.id(plan, "weather");
        SeriesInputs inputs = TwinWeather.bind(plan, series, TwinWeather.housePorts(weather));
        RunResult r = new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.SHADOW, inputs, List.of(), null, 0, null, null));
        out.printf(Locale.ROOT, "house twin %s, %d x %s from %s, weather from MOSMIX %s station %s (issued %s)%n",
                plan.twin(), steps, plan.baseStep(), plan.start(), f.product(), id, f.issueTime());
        double[] t = r.series(weather + ".t_out_c");
        double[] g = r.series(weather + ".ghi_w_m2");
        double[] q = r.series("building.q_req_kw");
        Origin[] o = r.origins(weather + ".t_out_c");
        TreeMap<LocalDate, double[]> days = new TreeMap<>();   // n, t, ghi kWh, heat kWh, forecast steps
        double h = plan.baseStep().toSeconds() / 3600.0;
        for (int i = 0; i < t.length; i++) {
            LocalDate d = Micros.toInstant(r.timeMicros(i)).atZone(HouseScenario.ZONE).toLocalDate();
            double[] a = days.computeIfAbsent(d, k -> new double[5]);
            a[0]++;
            a[1] += t[i];
            a[2] += g[i] / 1000 * h;
            a[3] += q[i] * h;
            a[4] += o[i] == Origin.FORECAST ? 1 : 0;
        }
        out.printf(Locale.ROOT, "%-10s %6s %9s %11s %12s%n", "day", "steps", "t_out C", "GHI kWh/m2", "heat req kWh");
        days.forEach((d, a) -> out.printf(Locale.ROOT, "%-10s %6.0f %9.1f %11.2f %12.1f%s%n", d, a[0], a[1] / a[0], a[2],
                a[3], a[4] < a[0] ? "  (" + (int) (a[0] - a[4]) + " steps synthetic)" : ""));
        out.println("origin of weather.t_out_c: " + r.origin(weather + ".t_out_c")
                + " (FORECAST = bound from DWD; SYNTHETIC = model fallback)");
        return 0;
    }

    // ----------------------------------------------------------------------------------------------- output

    private void report(List<WeatherSeries> series) {
        out.printf(Locale.ROOT, "%-28s %-12s %-6s %-6s %-10s %5s %5s  %-17s %s%n", "metric", "aggregation", "unit",
                "source", "origin", "n", "valid", "seriesId", "first value");
        int episodes = 0;
        for (WeatherSeries s : series) {
            List<Episode> eps = s.toEpisodes(Duration.ofDays(1));
            episodes += eps.size();
            double firstValid = Arrays.stream(s.values()).filter(v -> !Double.isNaN(v)).findFirst().orElse(Double.NaN);
            out.printf(Locale.ROOT, "%-28s %-12s %-6s %-6s %-10s %5d %5d  %-17s %s%n", s.variable().metric(),
                    s.aggregation() == null ? "-" : s.aggregation(), s.variable().unit(), s.sourceElement(), s.origin(),
                    s.size(), s.valid(), s.seriesKey().getSeriesId(), WeatherCsv.num(firstValid));
        }
        out.println(episodes + " v2 episodes (forecast: one per run; observations: per UTC day)");
    }

    private void export(List<WeatherSeries> series) throws IOException {
        if (opts.containsKey("out")) {
            try (Writer w = Files.newBufferedWriter(Path.of(opts.get("out")), StandardCharsets.UTF_8)) {
                WeatherCsv.writeWide(series, w);
            }
            out.println("wrote " + opts.get("out"));
        }
        if (opts.containsKey("daily")) {
            try (Writer w = Files.newBufferedWriter(Path.of(opts.get("daily")), StandardCharsets.UTF_8)) {
                int n = WeatherCsv.writeDailyTemperatures(series, HouseScenario.ZONE, w);
                out.println("wrote " + opts.get("daily") + " (" + n + " complete days)");
            }
        }
        if (opts.containsKey("boat")) {
            try (Writer w = Files.newBufferedWriter(Path.of(opts.get("boat")), StandardCharsets.UTF_8)) {
                int n = WeatherCsv.writeBoatIrradiance(series, w);
                out.println("wrote " + opts.get("boat") + " (" + n + " rows)");
            }
        }
    }
}
