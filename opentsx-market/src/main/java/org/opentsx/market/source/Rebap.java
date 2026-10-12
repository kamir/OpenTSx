package org.opentsx.market.source;

import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for the imbalance price reBAP of netztransparenz.de, WebAPI
 * {@code api/v1/data/NrvSaldo/reBAP/Qualitaetsgesichert/{from}/{to}} (CSV "Format 9") and the CSV download:
 * <pre>
 * Datum;Zeitzone;von;bis;Datenkategorie;Datentyp;Einheit;reBAP unterdeckt;reBAP ueberdeckt
 * 10.06.2023;UTC;13:00;13:15;reBAP;Qualitätsgesichert;EUR/MWh;103,97;103,97
 * </pre>
 * Columns are found by name, so the download variant ({@code Datum;von;Zeitzone von;bis;Zeitzone bis;...}) and a
 * single {@code reBAP} column (both sides equal) work as well. Times are UTC; a zone column with CET/CEST is
 * honoured per column ({@code Zeitzone von}/{@code Zeitzone bis}; fixed offsets, so the doubled hour at the end
 * of DST is unambiguous). {@code short} is the price for
 * an under-supplied balancing group ("unterdeckt"), {@code long} for an over-supplied one ("überdeckt").
 */
public final class Rebap {

    /** Parsed reBAP: price for short and long balancing groups. */
    public record Result(List<Point> shortSide, List<Point> longSide) {
    }

    private Rebap() {
    }

    public static Result parse(List<String> lines) {
        int h = 0;
        while (h < lines.size() && !lines.get(h).toLowerCase(Locale.ROOT).startsWith("datum")) {
            h++;
        }
        if (h == lines.size()) {
            throw new IllegalArgumentException("no header line starting with 'Datum'");
        }
        String[] header = lines.get(h).split(";", -1);
        int date = -1, zone = -1, zoneTo = -1, from = -1, to = -1, under = -1, over = -1, single = -1;
        for (int i = 0; i < header.length; i++) {
            String n = header[i].trim().toLowerCase(Locale.ROOT);
            if (n.startsWith("datum") && date < 0) {
                date = i;
            } else if (n.startsWith("zeitzone bis")) {
                zoneTo = i;
            } else if (n.startsWith("zeitzone") && zone < 0) {
                zone = i;
            } else if (n.equals("von") || n.startsWith("(uhrzeit) von")) {
                from = i;
            } else if (n.equals("bis") || n.startsWith("(uhrzeit) bis")) {
                to = i;
            } else if (n.contains("unterdeckt")) {
                under = i;
            } else if (n.contains("ueberdeckt") || n.contains("überdeckt")) {
                over = i;
            } else if (n.startsWith("rebap")) {
                single = i;
            }
        }
        if (date < 0 || from < 0 || (under < 0 && single < 0)) {
            throw new IllegalArgumentException("reBAP header not understood: " + lines.get(h));
        }
        if (under < 0) {
            under = single;
            over = single;
        }
        DateTimeFormatter dmy = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        List<Point> s = new ArrayList<>();
        List<Point> l = new ArrayList<>();
        for (String line : lines.subList(h + 1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(";", -1);
            String d = c[date].trim();
            LocalDate day = d.contains(".") ? LocalDate.parse(d, dmy) : LocalDate.parse(d.substring(0, 10));
            LocalTime t0 = LocalTime.parse(c[from].trim());
            LocalTime t1 = to >= 0 ? LocalTime.parse(c[to].trim()) : t0.plusMinutes(15);
            ZoneOffset off = offset(zone >= 0 ? c[zone] : "UTC");
            ZoneOffset offTo = zoneTo >= 0 ? offset(c[zoneTo]) : off;
            long t = Micro.of(day.atTime(t0).toInstant(off));
            long end = Micro.of(day.atTime(t1).toInstant(offTo));
            if (end <= t) {
                end += Micro.DAY;   // interval ends at or after midnight
            }
            long dt = end - t;
            double vu = Numbers.german(c[under]);
            double vo = Numbers.german(c[over]);
            if (!Double.isNaN(vu)) {
                s.add(new Point(t, dt, vu));
            }
            if (!Double.isNaN(vo)) {
                l.add(new Point(t, dt, vo));
            }
        }
        return new Result(s, l);
    }

    private static ZoneOffset offset(String zone) {
        return switch (zone.trim().toUpperCase(Locale.ROOT)) {
            case "", "UTC", "GMT", "Z" -> ZoneOffset.UTC;
            case "CET", "MEZ" -> ZoneOffset.ofHours(1);
            case "CEST", "MESZ" -> ZoneOffset.ofHours(2);
            default -> throw new IllegalArgumentException("unknown time zone " + zone);
        };
    }
}
