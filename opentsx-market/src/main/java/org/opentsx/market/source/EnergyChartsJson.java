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
 * Parser for the Energy-Charts API price endpoint ({@code /price?bzn=DE-LU&start=..&end=..}): parallel arrays
 * {@code unix_seconds} (interval start) and {@code price}, plus {@code unit} and {@code license_info}. The
 * resolution is taken from consecutive timestamps (hourly before, quarter-hourly after the MTU switch).
 */
public final class EnergyChartsJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Parsed price document. */
    public record Prices(List<Point> points, String unit, String licence) {
    }

    private EnergyChartsJson() {
    }

    public static Prices prices(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("not an Energy-Charts JSON document", e);
        }
        JsonNode ts = root.path("unix_seconds");
        JsonNode pr = root.path("price");
        if (ts.size() != pr.size()) {
            throw new IllegalArgumentException("unix_seconds and price differ in length");
        }
        String unit = root.path("unit").asText("EUR / MWh").replace(" ", "");
        if (!unit.equals("EUR/MWh")) {
            throw new IllegalArgumentException("unexpected unit " + unit);
        }
        List<Point> out = new ArrayList<>(ts.size());
        for (int i = 0; i < ts.size(); i++) {
            long t = ts.get(i).asLong() * Micro.SECOND;
            long dt;
            if (i + 1 < ts.size()) {
                dt = ts.get(i + 1).asLong() * Micro.SECOND - t;
            } else if (i > 0) {
                dt = t - ts.get(i - 1).asLong() * Micro.SECOND;
            } else {
                dt = t >= Micro.of(Micro.MTU_15_MIN) ? Micro.QUARTER : Micro.HOUR;
            }
            // a gap in the data must not stretch an interval: clamp to the MTU of that time
            long mtu = t >= Micro.of(Micro.MTU_15_MIN) ? Micro.QUARTER : Micro.HOUR;
            dt = Math.min(dt, mtu);
            if (pr.get(i).isNull()) {
                continue;
            }
            out.add(new Point(t, dt, pr.get(i).asDouble()));
        }
        return new Prices(out, "EUR/MWh", root.path("license_info").asText(""));
    }
}
