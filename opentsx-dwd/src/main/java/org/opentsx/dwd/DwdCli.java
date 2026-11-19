package org.opentsx.dwd;

import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.house.HouseScenario;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The commands {@code dwd stations}, {@code dwd mosmix}, {@code dwd obs} and {@code dwd house}: the DWD Open Data
 * collector. They read their keys through {@link OpenTsxConf#load}; {@code bin/opentsx dwd <command> --help} lists them.
 * Without an offline file the data is fetched from opendata.dwd.de through {@link DwdClient}. The keys and the offline
 * files are checked before the first download. Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error.
 */
public final class DwdCli {

    static final String COMMAND = "dwd";
    static final String NEAR = "opentsx.dwd.near";
    static final String NEAREST = "opentsx.dwd.nearestCount";
    static final String CATALOG = "opentsx.dwd.stationCatalog";
    static final String OFFLINE = "opentsx.dwd.offline";
    static final String OBS_OFFLINE = "opentsx.dwd.obs.offline";
    static final String STATION = "opentsx.dwd.station";
    static final String PARAMS = "opentsx.dwd.params";
    static final String HOURLY = "opentsx.dwd.hourly";
    static final String OUT = "opentsx.dwd.out";
    static final String DAILY = "opentsx.dwd.daily";
    static final String BOAT = "opentsx.dwd.boat";
    static final String PROXY = "opentsx.https.proxy";
    static final Pattern STATION_ID = Pattern.compile("[A-Za-z0-9]{1,10}");
    static final List<String> HOURLY_PARAMS = List.of("wind", "air_temperature");
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /** The checked work of a command; it writes its result to {@code out}. */
    interface Run {
        void run(PrintStream out) throws IOException;
    }

    private DwdCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err));
    }

    /** {@code args} without the word {@code dwd}, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        Run run;
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
            run = check(loaded.command(), loaded.conf());
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + oneLine(e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            return failed(e, err);
        }
        try {
            run.run(out);
        } catch (IOException | RuntimeException e) {
            return failed(e, err);
        }
        return written(out, err);
    }

    /** Checks every value and reads the offline files; downloads nothing. */
    static Run check(String command, OpenTsxConf conf) {
        Optional<String> station = conf.getOption(STATION);
        if (!command.equals("dwd stations") && station.isPresent() && !STATION_ID.matcher(station.get()).matches()) {
            throw new OpenTsxConfException(STATION, "\"" + station.get() + "\" is not a station id",
                    "expected letters and digits such as 10578 or 01358");
        }
        return switch (command) {
            case "dwd stations" -> stations(conf);
            case "dwd mosmix" -> {
                Forecast f = forecast(conf);
                Exports exports = exports(conf);
                yield out -> mosmix(f.get(), station, exports, out);
            }
            case "dwd obs" -> obs(conf, station, exports(conf));
            case "dwd house" -> {
                Forecast f = forecast(conf);
                yield out -> house(f.get(), station, out);
            }
            default -> throw new IllegalStateException(command + ": not a command of " + DwdCli.class.getName());
        };
    }

    /** The proxy of the downloads: the JVM proxy settings win; a set {@code opentsx.https.proxy} is checked in any case. */
    static ProxySelector proxy(String jvmProxyHost, Optional<String> configured) {
        ProxySelector own = configured.map(DwdCli::proxyOf).orElse(null);
        return jvmProxyHost != null || own == null ? ProxySelector.getDefault() : own;
    }

    private static ProxySelector proxyOf(String value) {
        URI u;
        try {
            u = new URI(value.contains("://") ? value : "http://" + value);
        } catch (URISyntaxException e) {
            // not kept as the cause: its message holds the value, which is a secret
            u = null;
        }
        if (u == null || u.getHost() == null) {
            throw new OpenTsxConfException(PROXY, "not a proxy address", "expected <host>:<port> or http://<host>:<port>");
        }
        return ProxySelector.of(InetSocketAddress.createUnresolved(u.getHost(), u.getPort() > 0 ? u.getPort() : 80));
    }

    private static DwdClient client(OpenTsxConf conf) {
        return new DwdClient(proxy(System.getProperty("https.proxyHost"), conf.getOption(PROXY)));
    }

    private interface Forecast {
        Mosmix.Forecast get() throws IOException;
    }

    private interface Parser<T> {
        T parse(byte[] bytes) throws IOException;
    }

    /** The bytes of an offline file a key names, parsed; a refusal of its content names the file. */
    private static <T> T offline(String file, String what, Parser<T> parser) {
        Path path = Path.of(file);
        byte[] bytes;
        if (Files.isDirectory(path)) {
            throw new OpenTsxConfException(file, "no such file", "give an existing " + what);
        }
        try {
            bytes = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            throw new OpenTsxConfException(file, "no such file", "give an existing " + what);
        } catch (IOException e) {
            throw new UncheckedIOException(file + ": cannot read the " + what, e);
        }
        try {
            return parser.parse(bytes);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }
    }

    private static Run stations(OpenTsxConf conf) {
        List<String> near = conf.getList(NEAR);
        if (near.size() != 2) {
            throw new OpenTsxConfException(NEAR, "\"" + String.join(",", near) + "\" has " + near.size() + " items",
                    "expected LAT,LON such as 50.6,13.0");
        }
        double lat = Double.parseDouble(near.get(0));
        double lon = Double.parseDouble(near.get(1));
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new OpenTsxConfException(NEAR, "\"" + String.join(",", near) + "\" is not a position",
                    "expected a latitude from -90 to 90 and a longitude from -180 to 180");
        }
        int n = conf.getInt(NEAREST);
        if (n < 1) {
            throw new OpenTsxConfException(NEAREST, n + " is not allowed", "expected 1 or more");
        }
        String url = conf.get(CATALOG).equals("cdc") ? DwdClient.CDC_STATIONS : DwdClient.MOSMIX_STATIONS;
        Optional<String> file = conf.getOption(OFFLINE);
        StationCatalog local = file.map(f -> offline(f, "station list", StationCatalog::parse)).orElse(null);
        DwdClient client = local == null ? client(conf) : null;
        return out -> {
            StationCatalog cat = local != null ? local : StationCatalog.parse(client.get(url));
            out.printf(Locale.ROOT, "%d stations in catalog; nearest to %.4f, %.4f:%n", cat.stations().size(), lat, lon);
            out.printf(Locale.ROOT, "%-6s %-36s %9s %9s %6s %8s%n", "id", "name", "lat", "lon", "elev", "km");
            for (StationCatalog.Nearby s : cat.nearest(lat, lon, n)) {
                Station st = s.station();
                out.printf(Locale.ROOT, "%-6s %-36s %9.4f %9.4f %6.0f %8.1f%n", st.id(), st.name(), st.latitude(),
                        st.longitude(), st.elevation(), s.distanceKm());
            }
        };
    }

    /** The MOSMIX forecast of an offline file, checked now, or of the station, downloaded when it is run. */
    private static Forecast forecast(OpenTsxConf conf) {
        Optional<String> station = conf.getOption(STATION);
        Optional<String> file = conf.getOption(OFFLINE);
        if (file.isPresent()) {
            Mosmix.Forecast f = offline(file.get(), "MOSMIX file",
                    b -> Mosmix.read(new ByteArrayInputStream(b), station.map(Set::of).orElse(null)));
            if (f.placemarks().isEmpty()) {
                throw new IllegalArgumentException(file.get() + ": no station" + station.map(s -> " " + s).orElse("")
                        + " in the forecast");
            }
            return () -> f;
        }
        String id = conf.get(STATION);
        DwdClient client = client(conf);
        return () -> Mosmix.read(new ByteArrayInputStream(client.get(Mosmix.latestUrl(id))), Set.of(id));
    }

    private static void mosmix(Mosmix.Forecast f, Optional<String> station, Exports exports, PrintStream out) throws IOException {
        Mosmix.Placemark p = station.isPresent() ? f.placemark(station.get()) : f.placemarks().get(0);
        out.printf(Locale.ROOT, "MOSMIX %s station %s %s (%.3f, %.3f, %.0f m), issued %s, %d time steps %s .. %s%n",
                f.product(), p.id(), p.name(), p.latitude(), p.longitude(), p.elevation(), f.issueTime(),
                f.timeSteps().length, Micros.toInstant(f.timeSteps()[0]),
                Micros.toInstant(f.timeSteps()[f.timeSteps().length - 1]));
        out.println("models: " + String.join(", ", f.models()));
        List<WeatherSeries> series = f.series(p.id());
        report(series, out);
        exports.write(series, out);
    }

    private static Run obs(OpenTsxConf conf, Optional<String> station, Exports exports) {
        Optional<List<String>> files = conf.getOption(OBS_OFFLINE).map(v -> conf.getList(OBS_OFFLINE));
        if (files.isPresent()) {
            List<WeatherSeries> series = new ArrayList<>();
            for (String file : files.get()) {
                series.addAll(offline(file, "CDC observation file", CdcObservations::read));
            }
            if (series.isEmpty()) {
                throw new IllegalArgumentException(String.join(",", files.get()) + ": no observations found");
            }
            return out -> {
                report(series, out);
                exports.write(series, out);
            };
        }
        boolean hourly = conf.getBoolean(HOURLY);
        List<String> params = conf.getOption(PARAMS).map(v -> conf.getList(PARAMS))
                .orElse(hourly ? HOURLY_PARAMS : List.of("wind", "air_temperature", "solar"));
        for (String p : params) {
            if (hourly && !HOURLY_PARAMS.contains(p)) {
                throw new OpenTsxConfException(PARAMS, "\"" + p + "\" has no hourly recent product",
                        "expected wind or air_temperature with hourly");
            }
        }
        String id = conf.get(STATION);
        DwdClient client = client(conf);
        return out -> {
            List<WeatherSeries> series = new ArrayList<>();
            for (String p : params) {
                String url = hourly ? CdcObservations.recentHourlyUrl(p, id) : CdcObservations.nowUrl(p, id);
                out.println("fetch " + url);
                series.addAll(CdcObservations.read(client.get(url)));
            }
            if (series.isEmpty()) {
                throw new IllegalStateException("station " + id + ": no observations found");
            }
            report(series, out);
            exports.write(series, out);
        };
    }

    private static void house(Mosmix.Forecast f, Optional<String> station, PrintStream out) {
        String id = station.orElseGet(() -> f.placemarks().get(0).id());
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
    }

    private static void report(List<WeatherSeries> series, PrintStream out) {
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

    private static Exports exports(OpenTsxConf conf) {
        return new Exports(conf.getOption(OUT).orElse(null), conf.getOption(DAILY).orElse(null), conf.getOption(BOAT).orElse(null));
    }

    /** The CSV files of {@code --out}, {@code --daily} and {@code --boat}; null for a file not asked for. */
    private record Exports(String out, String daily, String boat) {

        void write(List<WeatherSeries> series, PrintStream stdout) throws IOException {
            if (out != null) {
                try (Writer w = Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8)) {
                    WeatherCsv.writeWide(series, w);
                }
                stdout.println("wrote " + out);
            }
            if (daily != null) {
                try (Writer w = Files.newBufferedWriter(Path.of(daily), StandardCharsets.UTF_8)) {
                    int n = WeatherCsv.writeDailyTemperatures(series, HouseScenario.ZONE, w);
                    stdout.println("wrote " + daily + " (" + n + " complete days)");
                }
            }
            if (boat != null) {
                try (Writer w = Files.newBufferedWriter(Path.of(boat), StandardCharsets.UTF_8)) {
                    int n = WeatherCsv.writeBoatIrradiance(series, w);
                    stdout.println("wrote " + boat + " (" + n + " rows)");
                }
            }
        }
    }

    private static int failed(Exception e, PrintStream err) {
        LoggerFactory.getLogger(DwdCli.class).debug("stack trace of the failed run", e);
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
