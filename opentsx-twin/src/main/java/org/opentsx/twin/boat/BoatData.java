package org.opentsx.twin.boat;

import org.opentsx.model.Micros;
import org.opentsx.twin.Origin;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Real operating data of a boat from a CSV file (power-station app export, Starlink statistics, USB-C meter, logbook),
 * resampled to the plan step and bound to the input ports of a boat plan.
 * <p>
 * Columns (header row, any order, all but {@code zeit} optional; empty cell = no value; lines starting with
 * {@code #} are comments; separator {@code ,} or {@code ;}, with {@code ;} a decimal comma is allowed):
 * <table>
 *   <tr><th>column</th><th>meaning</th><th>bound to</th><th>origin</th></tr>
 *   <tr><td>{@code zeit}</td><td>ISO time, with offset/Z or local ({@code zone})</td><td>-</td><td>-</td></tr>
 *   <tr><td>{@code modus}</td><td>hafen | segeln | anker | unbemannt (logbook)</td><td>{@code <boat-mode>.mode_obs}</td><td>REPORTED</td></tr>
 *   <tr><td>{@code landstrom}</td><td>shore power plugged in, 0/1 (logbook)</td><td>{@code <shore-power>.available}</td><td>REPORTED</td></tr>
 *   <tr><td>{@code soc_prozent}</td><td>power station state of charge, %</td><td>{@code <power-station>.soc_meas}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code solar_w}</td><td>solar input power at the station, W</td><td>{@code <solar>.p_meas_w}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code last_w}</td><td>total output power of the station, W</td><td>{@code <power-station>.p_load_meas_w}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code <device>_w}</td><td>power of one device at the device, W (e.g. {@code starlink_w})</td><td>{@code <device>.p_meas_w}</td><td>MEASURED</td></tr>
 *   <tr><td>{@code notiz}</td><td>free text, ignored</td><td>-</td><td>-</td></tr>
 * </table>
 * Rows inside one plan step are averaged (mode: last value, shore power: maximum). A step without rows holds the
 * previous value for up to one hour, after that the value is missing and the model fills in (origin SYNTHETIC).
 *
 * @param plan     the template plan moved to the data period (start floored to the step, steps to cover all rows)
 * @param inputs   bindings for the twin
 * @param socMeas  measured SoC per plan step (0..1, NaN where missing), the calibration target
 * @param coverage per bound port, the number of steps with a value
 * @param ignored  columns that were not understood
 * @param rows     data rows read
 */
public record BoatData(TwinPlan plan, SeriesInputs inputs, double[] socMeas, Map<String, Integer> coverage,
                       List<String> ignored, int rows) {

    private enum Agg { MEAN, LAST, MAX }

    private record Column(int index, String port, Origin origin, double factor, Agg agg) {
    }

    public static BoatData load(Path csv, TwinPlan template, ZoneId zone) {
        try {
            return parse(Files.readAllLines(csv, StandardCharsets.UTF_8), template, zone);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BoatData parse(List<String> lines, TwinPlan template, ZoneId zone) {
        List<String> content = lines.stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
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
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim().toLowerCase(Locale.ROOT);
            Column c = null;
            switch (h) {
                case "zeit", "time", "timestamp" -> timeCol = i;
                case "modus", "mode" -> c = col(i, boat, "mode_obs", Origin.REPORTED, 1, Agg.LAST);
                case "landstrom", "shore" -> c = col(i, shore, "available", Origin.REPORTED, 1, Agg.MAX);
                case "soc_prozent", "soc_percent" -> c = col(i, station, "soc_meas", Origin.MEASURED, 0.01, Agg.MEAN);
                case "solar_w" -> c = col(i, solar, "p_meas_w", Origin.MEASURED, 1, Agg.MEAN);
                case "last_w", "load_w" -> c = col(i, station, "p_load_meas_w", Origin.MEASURED, 1, Agg.MEAN);
                case "notiz", "note", "" -> {
                }
                default -> {
                    if (h.endsWith("_w") && devices.contains(h.substring(0, h.length() - 2))) {
                        c = col(i, h.substring(0, h.length() - 2), "p_meas_w", Origin.MEASURED, 1, Agg.MEAN);
                    }
                }
            }
            if (c != null) {
                cols.add(c);
            } else if (timeCol != i && !h.isEmpty() && !h.equals("notiz") && !h.equals("note")) {
                ignored.add(header[i].trim());
            }
        }
        if (timeCol < 0) {
            throw new IllegalArgumentException("CSV needs a column 'zeit'");
        }

        List<long[]> times = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (int r = 1; r < content.size(); r++) {
            String[] f = content.get(r).split(java.util.regex.Pattern.quote(String.valueOf(sep)), header.length);
            if (f.length <= timeCol || f[timeCol].isBlank()) {
                continue;
            }
            times.add(new long[]{time(f[timeCol].trim(), zone)});
            rows.add(f);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }
        long step = template.baseStepMicros();
        long first = times.stream().mapToLong(t -> t[0]).min().getAsLong();
        long last = times.stream().mapToLong(t -> t[0]).max().getAsLong();
        long start = Math.floorDiv(first, step) * step;
        int n = (int) ((last - start) / step) + 1;
        int maxHold = (int) Math.max(1, BoatClock.HOUR / step);

        SeriesInputs inputs = new SeriesInputs();
        Map<String, Integer> coverage = new LinkedHashMap<>();
        double[] soc = new double[n];
        Arrays.fill(soc, Double.NaN);
        for (Column c : cols) {
            double[] sum = new double[n];
            int[] cnt = new int[n];
            double[] lastV = new double[n];
            for (int r = 0; r < rows.size(); r++) {
                String[] f = rows.get(r);
                double v = c.index() < f.length ? number(f[c.index()], sep, c.port()) : Double.NaN;
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
            if (c.port().equals(station + ".soc_meas")) {
                soc = values;
            }
        }
        TwinPlan plan = new TwinPlan(template.twin(), Micros.toInstant(start), template.baseStep(), n,
                template.atoms(), template.wiring());
        return new BoatData(plan, inputs, soc, coverage, List.copyOf(ignored), rows.size());
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
                throw new IllegalArgumentException("unknown mode '" + v + "', use hafen, segeln, anker, unbemannt");
            }
            return m;
        }
        if (sep == ';') {
            v = v.replace(',', '.');
        }
        return Double.parseDouble(v);
    }
}
