package org.opentsx.twin.boat;

import org.opentsx.model.Micros;
import org.opentsx.twin.Origin;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Real operating data of a boat from a CSV file (power-station app export, Starlink statistics, USB-C meter, logbook),
 * resampled to the plan step and bound to the input ports of a boat plan.
 * <p>
 * Columns (header row, any order, all but {@code time} optional; empty cell = no value; lines starting with
 * {@code #} are comments; separator {@code ,} or {@code ;}, with {@code ;} a decimal comma is allowed). The German
 * column names ({@code zeit}, {@code modus}, {@code landstrom}, {@code soc_prozent}, {@code last_w},
 * {@code einstrahlung_wm2}, {@code <device>_an}, {@code notiz}) and modes ({@code hafen}, {@code segeln},
 * {@code anker}, {@code unbemannt}) are accepted as aliases; a column named twice is refused:
 * <table>
 *   <tr><th>column</th><th>meaning</th><th>bound to</th><th>origin</th></tr>
 *   <tr><td>{@code time}</td><td>ISO time, with offset/Z or local ({@code zone})</td><td>-</td><td>-</td></tr>
 *   <tr><td>{@code mode}</td><td>harbour | sailing | anchor | away (logbook)</td><td>{@code <boat-mode>.mode_obs}</td><td>REPORTED</td></tr>
 *   <tr><td>{@code shore}</td><td>shore power plugged in, 0/1 (logbook)</td><td>{@code <shore-power>.available}</td><td>REPORTED</td></tr>
 *   <tr><td>{@code soc_percent}</td><td>power station state of charge, %</td><td>{@code <power-station>.soc_meas}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code solar_w}</td><td>solar input power at the station, W</td><td>{@code <solar>.p_meas_w}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code load_w}</td><td>total output power of the station, W</td><td>{@code <power-station>.p_load_meas_w}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code ghi_wm2}</td><td>global horizontal irradiance, W/m² (e.g. nearby DWD station)</td><td>{@code <solar>.ghi_wm2}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code <device>_on}</td><td>device switched on that day, 0/1 (logbook, e.g. {@code starlink_on})</td><td>{@code <device>.on}</td><td>REPORTED</td></tr>
 *   <tr><td>{@code <device>_w}</td><td>power of one device at the device, W (e.g. {@code starlink_w})</td><td>{@code <device>.p_meas_w}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code note}</td><td>free text, ignored</td><td>-</td><td>-</td></tr>
 * </table>
 * Rows inside one plan step are averaged (mode: last value, shore power: maximum). A step without rows holds the
 * previous value for up to one hour, after that the value is missing and the model fills in (origin SYNTHETIC). The
 * rows span at most {@link #MAX_SPAN_DAYS} days and at most {@link #MAX_STEPS} steps of the plan.
 *
 * @param plan     the template plan moved to the data period (start floored to the step, steps to cover all rows)
 * @param inputs   bindings for the twin
 * @param socMeas  measured SoC per plan step (0..1, NaN where missing), the calibration target
 * @param coverage per bound port, the number of steps with a value
 * @param ignored  columns that were not understood
 * @param rows     data rows read
 * @param series   per bound port the resampled values (NaN = missing)
 * @param origins  per bound port its origin
 */
public record BoatData(TwinPlan plan, SeriesInputs inputs, double[] socMeas, Map<String, Integer> coverage,
                       List<String> ignored, int rows, Map<String, double[]> series, Map<String, Origin> origins) {

    /** The longest span of the rows: 10 years, leap days included. */
    public static final int MAX_SPAN_DAYS = 3653;

    /**
     * The most plan steps the rows may span: 366 days of the 15-minute steps of {@code twin/boot-etap22.yaml}. A
     * calibrated boat run over 35040 steps needs about 2.4 GB of Java heap.
     */
    public static final long MAX_STEPS = 366 * 96;

    private static final Map<String, String> GERMAN_COLUMNS = Map.of("zeit", "time", "modus", "mode",
            "landstrom", "shore", "soc_prozent", "soc_percent", "last_w", "load_w", "einstrahlung_wm2", "ghi_wm2",
            "notiz", "note");

    /** The bindings without the given ports, e.g. without measured solar power to calibrate the solar model. */
    public SeriesInputs inputs(java.util.Set<String> exclude) {
        SeriesInputs out = new SeriesInputs();
        series.forEach((port, v) -> {
            if (!exclude.contains(port)) {
                out.bind(port, plan.startMicros(), plan.baseStepMicros(), v, origins.get(port));
            }
        });
        return out;
    }

    /** Resampled values of a bound port (NaN = missing), or all NaN if the port is not bound. */
    public double[] measured(String port) {
        double[] v = series.get(port);
        if (v != null) {
            return v.clone();
        }
        double[] none = new double[plan.steps()];
        Arrays.fill(none, Double.NaN);
        return none;
    }

    private enum Agg { MEAN, LAST, MAX }

    private record Column(int index, String port, Origin origin, double factor, Agg agg) {
    }

    public static BoatData parse(List<String> lines, TwinPlan template, ZoneId zone) {
        List<Integer> numbers = IntStream.range(0, lines.size()).filter(i -> {
            String l = lines.get(i).strip();
            return !l.isEmpty() && !l.startsWith("#");
        }).mapToObj(i -> i + 1).toList();
        List<String> content = numbers.stream().map(n -> lines.get(n - 1).strip()).toList();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("no header row");
        }
        String headerLine = content.get(0).replace("﻿", "");
        char sep = headerLine.indexOf(';') >= 0 ? ';' : ',';
        String[] header = headerLine.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);

        String boat = atomOfType(template, "boat-mode");
        String shore = atomOfType(template, "shore-power");
        String station = atomOfType(template, "power-station");
        String solar = atomOfType(template, "solar");
        List<String> devices = template.atoms().stream().filter(a -> a.type().equals("device"))
                .map(TwinPlan.AtomSpec::id).toList();

        int timeCol = -1;
        List<Column> cols = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        java.util.Set<String> named = new java.util.HashSet<>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim().toLowerCase(Locale.ROOT);
            String name = h.endsWith("_an") ? h.substring(0, h.length() - 3) + "_on"
                    : h.equals("timestamp") ? "time" : GERMAN_COLUMNS.getOrDefault(h, h);
            Column c = null;
            switch (name) {
                case "time" -> timeCol = i;
                case "mode" -> c = col(i, boat, "mode_obs", Origin.REPORTED, 1, Agg.LAST);
                case "shore" -> c = col(i, shore, "available", Origin.REPORTED, 1, Agg.MAX);
                case "soc_percent" -> c = col(i, station, "soc_meas", Origin.MEASURED, 0.01, Agg.MEAN);
                case "solar_w" -> c = col(i, solar, "p_meas_w", Origin.MEASURED, 1, Agg.MEAN);
                case "load_w" -> c = col(i, station, "p_load_meas_w", Origin.MEASURED, 1, Agg.MEAN);
                case "ghi_wm2" -> c = col(i, solar, "ghi_wm2", Origin.MEASURED, 1, Agg.MEAN);
                case "note", "" -> {
                }
                default -> {
                    if (name.endsWith("_w") && devices.contains(name.substring(0, name.length() - 2))) {
                        c = col(i, name.substring(0, name.length() - 2), "p_meas_w", Origin.MEASURED, 1, Agg.MEAN);
                    } else if (name.endsWith("_on") && devices.contains(name.substring(0, name.length() - 3))) {
                        c = col(i, name.substring(0, name.length() - 3), "on", Origin.REPORTED, 1, Agg.LAST);
                    }
                }
            }
            boolean known = c != null || timeCol == i || name.equals("note");
            if (known && !named.add(name)) {
                throw new IllegalArgumentException(header[i].trim() + ": names the column " + name
                        + " a second time; expected each column once");
            }
            if (c != null) {
                cols.add(c);
            } else if (!known && !h.isEmpty()) {
                ignored.add(header[i].trim());
            }
        }
        if (timeCol < 0) {
            throw new IllegalArgumentException("missing column time; expected a header with time and any of mode, "
                    + "shore, soc_percent, solar_w, load_w, ghi_wm2, <device>_on, <device>_w, note");
        }

        List<long[]> times = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<Integer> rowLines = new ArrayList<>();
        for (int r = 1; r < content.size(); r++) {
            String[] f = content.get(r).split(java.util.regex.Pattern.quote(String.valueOf(sep)), header.length);
            if (f.length <= timeCol || f[timeCol].isBlank()) {
                continue;
            }
            String t = f[timeCol].trim();
            try {
                times.add(new long[]{time(t, zone)});
            } catch (DateTimeException | ArithmeticException e) {
                throw new IllegalArgumentException("line " + numbers.get(r) + ": " + header[timeCol].trim() + " \"" + t
                        + "\" is not a time; expected 2026-07-04T10:00+02:00, 2026-07-04T08:00:00Z or 2026-07-04 10:00",
                        e);
            }
            rows.add(f);
            rowLines.add(numbers.get(r));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }
        int earliest = 0;
        for (int r = 1; r < times.size(); r++) {
            if (times.get(r)[0] < times.get(earliest)[0]) {
                earliest = r;
            }
        }
        long first = times.get(earliest)[0];
        long limit = first + MAX_SPAN_DAYS * 86_400_000_000L;
        for (int r = 0; r < times.size(); r++) {
            if (times.get(r)[0] > limit) {
                throw new IllegalArgumentException("line " + rowLines.get(r) + ": " + header[timeCol].trim() + " \""
                        + rows.get(r)[timeCol].trim() + "\" lies more than " + MAX_SPAN_DAYS + " days after the "
                        + "earliest row (line " + rowLines.get(earliest) + "); expected rows that span at most "
                        + MAX_SPAN_DAYS + " days (10 years)");
            }
        }
        int latest = 0;
        for (int r = 1; r < times.size(); r++) {
            if (times.get(r)[0] > times.get(latest)[0]) {
                latest = r;
            }
        }
        long step = template.baseStepMicros();
        long last = times.get(latest)[0];
        long start = Math.floorDiv(first, step) * step;
        long steps = (last - start) / step + 1;
        if (steps > MAX_STEPS) {
            throw new IllegalArgumentException("line " + rowLines.get(latest) + ": " + header[timeCol].trim() + " \""
                    + rows.get(latest)[timeCol].trim() + "\" needs " + steps + " plan steps of " + template.baseStep()
                    + " from the earliest row (line " + rowLines.get(earliest) + "); expected at most " + MAX_STEPS
                    + " plan steps, from a longer baseStep or rows that span less time");
        }
        int n = (int) steps;
        int maxHold = (int) Math.max(1, BoatClock.HOUR / step);

        SeriesInputs inputs = new SeriesInputs();
        Map<String, Integer> coverage = new LinkedHashMap<>();
        Map<String, double[]> series = new LinkedHashMap<>();
        Map<String, Origin> origins = new LinkedHashMap<>();
        double[] soc = new double[n];
        Arrays.fill(soc, Double.NaN);
        for (Column c : cols) {
            double[] sum = new double[n];
            int[] cnt = new int[n];
            double[] lastV = new double[n];
            for (int r = 0; r < rows.size(); r++) {
                String[] f = rows.get(r);
                double v;
                try {
                    v = c.index() < f.length ? number(f[c.index()], sep, c.port()) : Double.NaN;
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("line " + rowLines.get(r) + ": " + e.getMessage(), e);
                }
                if (Double.isNaN(v)) {
                    continue;
                }
                int k = (int) ((times.get(r)[0] - start) / step);
                v *= c.factor();
                sum[k] = c.agg() == Agg.MAX ? (cnt[k] == 0 ? v : Math.max(sum[k], v)) : sum[k] + v;
                lastV[k] = v;
                cnt[k]++;
            }
            double[] values = new double[n];
            int held = 0;
            int covered = 0;
            double prev = Double.NaN;
            for (int k = 0; k < n; k++) {
                if (cnt[k] > 0) {
                    values[k] = switch (c.agg()) {
                        case MEAN -> sum[k] / cnt[k];
                        case MAX -> sum[k];
                        case LAST -> lastV[k];
                    };
                    held = 0;
                } else if (!Double.isNaN(prev) && held < maxHold) {
                    values[k] = prev;
                    held++;
                } else {
                    values[k] = Double.NaN;
                }
                prev = values[k];
                if (!Double.isNaN(values[k])) {
                    covered++;
                }
            }
            inputs.bind(c.port(), start, step, values, c.origin());
            coverage.put(c.port(), covered);
            series.put(c.port(), values);
            origins.put(c.port(), c.origin());
            if (c.port().equals(station + ".soc_meas")) {
                soc = values;
            }
        }
        TwinPlan plan = new TwinPlan(template.twin(), Micros.toInstant(start), template.baseStep(), n,
                template.atoms(), template.wiring());
        return new BoatData(plan, inputs, soc, coverage, List.copyOf(ignored), rows.size(),
                java.util.Collections.unmodifiableMap(series), java.util.Collections.unmodifiableMap(origins));
    }

    private static Column col(int index, String atom, String port, Origin origin, double factor, Agg agg) {
        return atom == null ? null : new Column(index, atom + "." + port, origin, factor, agg);
    }

    private static String atomOfType(TwinPlan plan, String type) {
        return plan.atoms().stream().filter(a -> a.type().equals(type)).map(TwinPlan.AtomSpec::id).findFirst()
                .orElse(null);
    }

    private static long time(String s, ZoneId zone) {
        String v = s.replace(' ', 'T');
        try {
            return Micros.of(OffsetDateTime.parse(v).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Micros.of(Instant.parse(v));
            } catch (DateTimeParseException e2) {
                return Micros.of(LocalDateTime.parse(v).atZone(zone).toInstant());
            }
        }
    }

    private static double number(String s, char sep, String port) {
        String v = s.trim();
        if (v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        if (port.endsWith(".mode_obs")) {
            int m = BoatClock.mode(v);
            if (m < 0) {
                throw new IllegalArgumentException("mode \"" + v + "\" is not a mode; expected harbour, sailing, "
                        + "anchor or away");
            }
            return m;
        }
        if (sep == ';') {
            v = v.replace(',', '.');
        }
        return Double.parseDouble(v);
    }
}
