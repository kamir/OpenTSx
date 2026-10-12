package org.opentsx.market.source;

import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for the monthly market values (Monatsmarktwerte, § 23a EEG) of netztransparenz.de, WebAPI
 * {@code api/v1/data/marktpraemie/...} (CSV "Format 12") or the CSV download of the Marktwertübersicht:
 * <pre>
 * Monat;MW-EPEX in ct/kWh;MW Wind Onshore in ct/kWh;PM Wind Onshore fernsteuerbar in ct/kWh;MW Wind Offshore ...
 * 1/2024;7,657;6,502;;7,138;;7,535;;7,657;;Ja;Ja;Ja;Ja
 * </pre>
 * Only the market values ("MW ...") are read; they are converted from ct/kWh to EUR/MWh (factor 10). A month is the
 * interval from local midnight of its first day to local midnight of the next month (Europe/Berlin), in UTC.
 */
public final class Marktwerte {

    /** Header prefix (lower case, without "in ct/kWh") to series suffix. */
    public static final Map<String, String> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("mw-epex", "epex");
        COLUMNS.put("mw wind onshore", "wind_onshore");
        COLUMNS.put("mw wind an land", "wind_onshore");
        COLUMNS.put("mw wind offshore", "wind_offshore");
        COLUMNS.put("mw wind auf see", "wind_offshore");
        COLUMNS.put("mw solar", "solar");
        COLUMNS.put("mw steuerbar", "controllable");
    }

    private Marktwerte() {
    }

    /** Points per technology suffix ({@code epex}, {@code wind_onshore}, ...), monthly, EUR/MWh. */
    public static Map<String, List<Point>> parse(List<String> lines) {
        int h = 0;
        while (h < lines.size() && !lines.get(h).toLowerCase(Locale.ROOT).startsWith("monat")) {
            h++;
        }
        if (h == lines.size()) {
            throw new IllegalArgumentException("no header line starting with 'Monat'");
        }
        String[] header = lines.get(h).split(";", -1);
        Map<Integer, String> cols = new LinkedHashMap<>();
        for (int i = 1; i < header.length; i++) {
            String name = header[i].toLowerCase(Locale.ROOT).replace("in ct/kwh", "").trim();
            String suffix = COLUMNS.get(name);
            if (suffix != null && !cols.containsValue(suffix)) {
                cols.put(i, suffix);
            }
        }
        Map<String, List<Point>> out = new LinkedHashMap<>();
        cols.values().forEach(s -> out.put(s, new ArrayList<>()));
        for (String line : lines.subList(h + 1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(";", -1);
            String[] my = c[0].trim().split("/");
            LocalDate first = LocalDate.of(Integer.parseInt(my[1].trim()), Integer.parseInt(my[0].trim()), 1);
            long t = Micro.berlinDay(first);
            long dt = Micro.berlinDay(first.plusMonths(1)) - t;
            for (Map.Entry<Integer, String> e : cols.entrySet()) {
                if (e.getKey() < c.length) {
                    double v = Numbers.german(c[e.getKey()]);
                    if (!Double.isNaN(v)) {
                        out.get(e.getValue()).add(new Point(t, dt, v * 10));
                    }
                }
            }
        }
        return out;
    }
}
