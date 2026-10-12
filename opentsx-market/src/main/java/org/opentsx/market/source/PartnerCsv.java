package org.opentsx.market.source;

import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * File import for partner data without a public API: direct-marketer schedules and forecasts, realised intraday
 * prices (EPEX intraday is licensed and is <b>not</b> collected from the exchange), park-controller exports.
 * <p>
 * Format: a header line, then one row per interval. Columns (case-insensitive): {@code start} (required),
 * {@code end} (optional, else {@code resolution}), {@code value} (or the column named in {@code valueColumn}).
 * Separator {@code ;} (then decimal comma) or {@code ,}. Timestamps are ISO-8601 with offset or {@code Z}
 * ({@code 2025-10-26T02:15:00+01:00}); local timestamps without offset are rejected unless a zone is given, and
 * then the ambiguous hour at the end of DST is rejected too, because it cannot be placed.
 */
public final class PartnerCsv {

    private PartnerCsv() {
    }

    public static List<Point> parse(List<String> lines, String valueColumn, Duration resolution, ZoneId localZone) {
        int h = 0;
        while (h < lines.size() && (lines.get(h).isBlank() || lines.get(h).startsWith("#"))) {
            h++;
        }
        if (h == lines.size()) {
            return List.of();
        }
        String head = lines.get(h);
        String sep = head.contains(";") ? ";" : ",";
        boolean german = sep.equals(";");
        String[] cols = head.split(sep, -1);
        int start = -1, end = -1, value = -1;
        String wanted = valueColumn == null ? "value" : valueColumn.toLowerCase(Locale.ROOT);
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().toLowerCase(Locale.ROOT);
            if (c.equals("start") || c.equals("from") || c.equals("von")) {
                start = i;
            } else if (c.equals("end") || c.equals("to") || c.equals("bis")) {
                end = i;
            } else if (c.equals(wanted)) {
                value = i;
            }
        }
        if (start < 0 || value < 0) {
            throw new IllegalArgumentException("partner CSV needs columns 'start' and '" + wanted + "': " + head);
        }
        if (end < 0 && resolution == null) {
            throw new IllegalArgumentException("partner CSV without 'end' column needs a resolution");
        }
        List<Point> out = new ArrayList<>();
        for (String line : lines.subList(h + 1, lines.size())) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split(sep, -1);
            long t = Micro.of(instant(c[start].trim(), localZone));
            long dt = end >= 0 ? Micro.of(instant(c[end].trim(), localZone)) - t : resolution.toNanos() / 1000;
            if (dt <= 0) {
                throw new IllegalArgumentException("end not after start: " + line);
            }
            double v = german ? Numbers.german(c[value]) : Numbers.plain(c[value]);
            if (!Double.isNaN(v)) {
                out.add(new Point(t, dt, v));
            }
        }
        return out;
    }

    static Instant instant(String s, ZoneId zone) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            if (s.endsWith("Z")) {
                return Instant.parse(s);
            }
            if (zone == null) {
                throw new IllegalArgumentException("timestamp without offset (" + s + "); pass a zone", e);
            }
            LocalDateTime l = LocalDateTime.parse(s.replace(' ', 'T'));
            if (zone.getRules().getValidOffsets(l).size() != 1) {
                throw new IllegalArgumentException("ambiguous or skipped local time " + s + " in " + zone, e);
            }
            return l.atZone(zone).toInstant();
        }
    }
}
