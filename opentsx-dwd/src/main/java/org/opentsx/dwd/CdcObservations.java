package org.opentsx.dwd;

import org.opentsx.model.Micros;
import org.opentsx.twin.Origin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CDC station observations ({@code climate_environment/CDC/observations_germany/climate/{10_minutes,hourly}/...}),
 * delivered as a zip with a {@code produkt_*.txt} file plus metadata. The product file is semicolon-separated with
 * padded fields and a trailing {@code eor} column:
 * <pre>
 * STATIONS_ID;MESS_DATUM;  QN;FF_10;DD_10;eor
 *        1358;202610120000;    2;  12.3; 290;eor
 * </pre>
 * <ul>
 *   <li>{@code MESS_DATUM}: {@code yyyyMMddHHmm} (10 minutes) or {@code yyyyMMddHH} (hourly; the solar product
 *   appends {@code :mm}), UTC. 10-minute data before 2000-01-01 are in MEZ (UTC+1) and are shifted to UTC.</li>
 *   <li>{@code -999} marks a missing value ({@code NaN}). Rows missing from the file become {@code NaN} as well, so
 *   every series is on a regular grid.</li>
 *   <li>Means and sums cover the interval <b>before</b> the time stamp; they are moved to the interval start and get
 *   an {@code aggregation} tag. Temperature, humidity and pressure are instantaneous.</li>
 *   <li>Radiation sums in J/cm² are converted to the mean irradiance in W/m².</li>
 * </ul>
 * Columns that are not listed in {@link #COLUMNS} (quality bytes {@code QN*}, sunshine duration, ...) are ignored.
 */
public final class CdcObservations {

    private static final long TEN_MIN = 600_000_000L;
    private static final long HOUR = 3_600_000_000L;
    private static final long Y2000 = Micros.of(Instant.parse("2000-01-01T00:00:00Z"));
    private static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /**
     * A CDC column.
     *
     * @param factor      multiplies the raw value into the canonical unit
     * @param aggregation {@code mean}/{@code sum} (the interval is added from the file resolution), or {@code null}
     */
    public record Column(String name, Variable variable, double factor, String aggregation) {
    }

    /** Known columns of the 10-minute (wind, air_temperature, solar, precipitation) and hourly products. */
    public static final List<Column> COLUMNS = List.of(
            // 10 minutes
            new Column("FF_10", Variable.WIND_SPEED, 1, "mean"),
            new Column("DD_10", Variable.WIND_DIRECTION, 1, "mean"),
            new Column("TT_10", Variable.AIR_TEMPERATURE, 1, null),
            new Column("RF_10", Variable.RELATIVE_HUMIDITY, 1, null),
            new Column("PP_10", Variable.PRESSURE, 1, null),
            new Column("GS_10", Variable.GLOBAL_IRRADIANCE, 10_000.0 / 600, "mean"),   // J/cm² in 10 min -> W/m²
            new Column("DS_10", Variable.DIFFUSE_IRRADIANCE, 10_000.0 / 600, "mean"),
            new Column("RWS_10", Variable.PRECIPITATION, 1, "sum"),
            // hourly
            new Column("F", Variable.WIND_SPEED, 1, "mean"),
            new Column("D", Variable.WIND_DIRECTION, 1, "mean"),
            new Column("TT_TU", Variable.AIR_TEMPERATURE, 1, null),
            new Column("RF_TU", Variable.RELATIVE_HUMIDITY, 1, null),
            new Column("P0", Variable.PRESSURE, 1, null),
            new Column("FG_LBERG", Variable.GLOBAL_IRRADIANCE, 10_000.0 / 3600, "mean"), // J/cm² in 1 h -> W/m²
            new Column("FD_LBERG", Variable.DIFFUSE_IRRADIANCE, 10_000.0 / 3600, "mean"),
            new Column("R1", Variable.PRECIPITATION, 1, "sum"),
            // hourly extreme wind: the highest gust of the hour
            new Column("FX_911", Variable.WIND_GUST, 1, "max"));

    private CdcObservations() {
    }

    /** URL of the "now" (current day) 10-minute zip of a station, e.g. {@code wind}, {@code air_temperature}, {@code solar}. */
    public static String nowUrl(String parameter, String cdcStationId) {
        String code = switch (parameter) {
            case "wind" -> "wind";
            case "air_temperature" -> "TU";
            case "solar" -> "SOLAR";
            case "precipitation" -> "nieder";
            default -> throw new IllegalArgumentException("unknown 10-minute parameter: " + parameter
                    + " (wind, air_temperature, solar, precipitation)");
        };
        return "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/10_minutes/" + parameter
                + "/now/10minutenwerte_" + code + "_" + cdcStationId + "_now.zip";
    }

    /** URL of the "recent" (about 500 days) hourly zip, parameter {@code air_temperature} (TU) or {@code wind} (FF). */
    public static String recentHourlyUrl(String parameter, String cdcStationId) {
        String code = switch (parameter) {
            case "air_temperature" -> "TU";
            case "wind" -> "FF";
            default -> throw new IllegalArgumentException("unknown hourly parameter: " + parameter + " (air_temperature, wind)");
        };
        return "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/hourly/" + parameter
                + "/recent/stundenwerte_" + code + "_" + cdcStationId + "_akt.zip";
    }

    public static List<WeatherSeries> read(Path file) {
        try {
            return read(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A CDC zip (the {@code produkt_*} entry is used) or a plain product text file. */
    public static List<WeatherSeries> read(byte[] bytes) throws IOException {
        if (bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    String n = e.getName();
                    if (n.startsWith("produkt") && n.endsWith(".txt")) {
                        return parse(lines(zip));
                    }
                }
            }
            throw new IOException("no produkt_*.txt in the CDC zip");
        }
        return parse(new String(bytes, StandardCharsets.ISO_8859_1).lines().toList());
    }

    private static List<String> lines(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).lines().toList();
    }

    /** Parses the lines of a product file (header first). */
    public static List<WeatherSeries> parse(List<String> lines) {
        List<String> rows = lines.stream().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("empty CDC file");
        }
        String[] header = split(rows.get(0));
        int idCol = indexOf(header, "STATIONS_ID");
        int timeCol = indexOf(header, "MESS_DATUM");
        if (idCol < 0 || timeCol < 0) {
            throw new IllegalArgumentException("not a CDC product file (STATIONS_ID;MESS_DATUM;... expected): " + rows.get(0));
        }
        List<int[]> used = new ArrayList<>();   // {header index, COLUMNS index}
        for (int i = 0; i < header.length; i++) {
            for (int c = 0; c < COLUMNS.size(); c++) {
                if (COLUMNS.get(c).name().equals(header[i])) {
                    used.add(new int[]{i, c});
                }
            }
        }
        String station = null;
        Long interval = null;
        Map<Long, double[]> byTime = new TreeMap<>();
        for (String row : rows.subList(1, rows.size())) {
            String[] f = split(row);
            if (f.length < header.length - 1) {
                continue;
            }
            String id = String.format("%05d", Integer.parseInt(f[idCol]));
            if (station == null) {
                station = id;
            } else if (!station.equals(id)) {
                throw new IllegalArgumentException("more than one station in one file: " + station + ", " + id);
            }
            String md = f[timeCol];
            int colon = md.indexOf(':');
            String digits = colon >= 0 ? md.substring(0, colon) : md;
            long res = digits.length() == 12 ? TEN_MIN : HOUR;
            if (interval == null) {
                interval = res;
            }
            String stamp = digits.length() == 12 ? digits : digits.substring(0, 10) + "00";
            long t = Micros.of(LocalDateTime.parse(stamp, MIN).toInstant(ZoneOffset.UTC));
            if (res == TEN_MIN && t < Y2000) {
                t -= HOUR;   // MEZ before 2000
            }
            double[] v = new double[used.size()];
            for (int k = 0; k < used.size(); k++) {
                String s = f[used.get(k)[0]];
                double x = s.isEmpty() ? Double.NaN : Double.parseDouble(s);
                v[k] = x <= -999 ? Double.NaN : Mosmix.round(x * COLUMNS.get(used.get(k)[1]).factor());
            }
            byTime.put(t, v);
        }
        List<WeatherSeries> out = new ArrayList<>();
        if (byTime.isEmpty()) {
            return out;
        }
        long step = interval;
        long first = ((TreeMap<Long, double[]>) byTime).firstKey();
        long last = ((TreeMap<Long, double[]>) byTime).lastKey();
        int n = (int) ((last - first) / step) + 1;
        String product = step == TEN_MIN ? "cdc-10min" : "cdc-hourly";
        String period = step == TEN_MIN ? "PT10M" : "PT1H";
        for (int k = 0; k < used.size(); k++) {
            Column c = COLUMNS.get(used.get(k)[1]);
            long shift = c.aggregation() == null ? 0 : step;
            long[] ts = new long[n];
            double[] v = new double[n];
            Arrays.fill(v, Double.NaN);
            for (int i = 0; i < n; i++) {
                ts[i] = first + i * step - shift;
            }
            for (Map.Entry<Long, double[]> e : byTime.entrySet()) {
                long off = e.getKey() - first;
                if (off % step == 0) {
                    v[(int) (off / step)] = e.getValue()[k];
                }
            }
            out.add(new WeatherSeries(station, product, c.variable(),
                    c.aggregation() == null ? null : c.aggregation() + "." + period, Origin.MEASURED, null, c.name(),
                    step, ts, v));
        }
        return out;
    }

    private static String[] split(String row) {
        String[] f = row.split(";", -1);
        for (int i = 0; i < f.length; i++) {
            f[i] = f[i].strip();
        }
        return f;
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
