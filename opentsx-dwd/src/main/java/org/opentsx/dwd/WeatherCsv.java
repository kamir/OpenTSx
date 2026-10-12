package org.opentsx.dwd;

import org.opentsx.model.Micros;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CSV exports of DWD series.
 * <ul>
 *   <li>{@link #writeWide}: one row per station, product, run and time; one column per variable (canonical units).
 *   Aggregated columns (irradiance, precipitation, 10-minute means) are stamped at the interval start.</li>
 *   <li>{@link #writeDailyTemperatures}: {@code datum,t_mittel_c}, the format of
 *   {@code bin/opentsx house --calibrate bills.csv --weather file.csv}.</li>
 *   <li>{@link #writeBoatIrradiance}: {@code zeit,einstrahlung_wm2}, a column set of {@code bin/opentsx boat --data};
 *   the boat import treats it as measured, so use observations there (or keep in mind that a forecast is
 *   relabelled).</li>
 * </ul>
 */
public final class WeatherCsv {

    private WeatherCsv() {
    }

    public static void writeWide(List<WeatherSeries> series, Writer out) throws IOException {
        List<Variable> vars = series.stream().map(WeatherSeries::variable).distinct().sorted().toList();
        out.write("# DWD Open Data (opendata.dwd.de), converted by opentsx-dwd; times UTC; aggregated values at interval start\n");
        StringBuilder h = new StringBuilder("time_utc,station,product,origin,reference_time,lead_h");
        vars.forEach(v -> h.append(',').append(v.column()));
        out.write(h.append('\n').toString());
        Map<String, List<WeatherSeries>> groups = new LinkedHashMap<>();
        for (WeatherSeries s : series) {
            groups.computeIfAbsent(s.station() + "|" + s.product() + "|" + s.referenceTime(), k -> new ArrayList<>()).add(s);
        }
        for (List<WeatherSeries> g : groups.values()) {
            WeatherSeries first = g.get(0);
            TreeMap<Long, Map<Variable, Double>> rows = new TreeMap<>();
            for (WeatherSeries s : g) {
                for (int i = 0; i < s.size(); i++) {
                    rows.computeIfAbsent(s.timestamps()[i], k -> new EnumMap<>(Variable.class))
                            .putIfAbsent(s.variable(), s.values()[i]);
                }
            }
            for (Map.Entry<Long, Map<Variable, Double>> r : rows.entrySet()) {
                StringBuilder b = new StringBuilder();
                b.append(Micros.toInstant(r.getKey())).append(',').append(first.station()).append(',')
                        .append(first.product()).append(',').append(first.origin()).append(',');
                if (first.referenceTime() != null) {
                    b.append(first.referenceTime()).append(',')
                            .append(num((r.getKey() - Micros.of(first.referenceTime())) / 3.6e9));
                } else {
                    b.append(',');
                }
                for (Variable v : vars) {
                    b.append(',').append(num(r.getValue().getOrDefault(v, Double.NaN)));
                }
                out.write(b.append('\n').toString());
            }
        }
    }

    /**
     * Daily mean air temperature per local day of {@code zone} as {@code datum,t_mittel_c}. A day needs values for at
     * least 80 % of its samples, otherwise it is left out (the house twin then uses its synthetic climate).
     */
    public static int writeDailyTemperatures(List<WeatherSeries> series, ZoneId zone, Writer out) throws IOException {
        WeatherSeries t = series.stream().filter(s -> s.variable() == Variable.AIR_TEMPERATURE).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no air temperature series"));
        TreeMap<LocalDate, double[]> days = new TreeMap<>();   // {sum, n}
        for (int i = 0; i < t.size(); i++) {
            if (!Double.isNaN(t.values()[i])) {
                double[] d = days.computeIfAbsent(Micros.toInstant(t.timestamps()[i]).atZone(zone).toLocalDate(),
                        k -> new double[2]);
                d[0] += t.values()[i];
                d[1]++;
            }
        }
        out.write("# daily mean air temperature, DWD station " + t.station() + " (" + t.product() + ", " + t.origin()
                + "), local days " + zone + "\n");
        out.write("datum,t_mittel_c\n");
        int written = 0;
        for (Map.Entry<LocalDate, double[]> d : days.entrySet()) {
            Instant a = d.getKey().atStartOfDay(zone).toInstant();
            Instant b = d.getKey().plusDays(1).atStartOfDay(zone).toInstant();
            double expected = (Micros.of(b) - Micros.of(a)) / (double) t.intervalMicros();
            if (d.getValue()[1] >= 0.8 * expected) {
                out.write(d.getKey() + "," + num(Math.round(d.getValue()[0] / d.getValue()[1] * 100) / 100.0) + "\n");
                written++;
            }
        }
        return written;
    }

    /** Global irradiance as {@code zeit,einstrahlung_wm2} (UTC instants), for {@code bin/opentsx boat --data}. */
    public static int writeBoatIrradiance(List<WeatherSeries> series, Writer out) throws IOException {
        WeatherSeries g = series.stream().filter(s -> s.variable() == Variable.GLOBAL_IRRADIANCE).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no global irradiance series"));
        out.write("# global irradiance, DWD station " + g.station() + " (" + g.product() + ", " + g.origin()
                + "); time = interval start, UTC\n");
        out.write("zeit,einstrahlung_wm2\n");
        int n = 0;
        for (int i = 0; i < g.size(); i++) {
            if (!Double.isNaN(g.values()[i])) {
                out.write(Micros.toInstant(g.timestamps()[i]) + "," + num(g.values()[i]) + "\n");
                n++;
            }
        }
        return n;
    }

    static String num(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        String s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s;
    }
}
