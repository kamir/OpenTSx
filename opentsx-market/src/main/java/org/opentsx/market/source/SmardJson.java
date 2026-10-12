package org.opentsx.market.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the SMARD chart_data API (Bundesnetzagentur). An index file lists the start times (UTC ms) of the
 * data files, one per week (Monday 00:00 local time); a data file holds {@code "series": [[ms, value|null], ...]}
 * with the interval start in UTC ms.
 * <p>
 * Generation and consumption are published as <b>energy per interval in MWh</b> (a quarter-hour value is about a
 * quarter of the hourly one); with {@code energy = true} they are converted to the average power in MW. Prices are
 * EUR/MWh and kept as they are. {@code null} values (not yet published) are skipped.
 */
public final class SmardJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SmardJson() {
    }

    /** Start times of the data files, UTC ms. */
    public static long[] index(String json) {
        JsonNode ts = read(json).path("timestamps");
        long[] out = new long[ts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ts.get(i).asLong();
        }
        return out;
    }

    /** Points of one data file; {@code dtMicros} is the resolution (hour or quarter-hour). */
    public static List<Point> series(String json, long dtMicros, boolean energy) {
        JsonNode s = read(json).path("series");
        List<Point> out = new ArrayList<>(s.size());
        double hours = dtMicros / (double) Micro.HOUR;
        for (JsonNode p : s) {
            if (p.size() < 2 || p.get(1).isNull()) {
                continue;
            }
            double v = p.get(1).asDouble();
            out.add(new Point(p.get(0).asLong() * 1000, dtMicros, energy ? v / hours : v));
        }
        return out;
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("not a SMARD JSON document", e);
        }
    }
}
