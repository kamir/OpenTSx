package org.opentsx.kafka;

import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Small synthetic wind-farm dataset with realistic sensor resolution (quantized values). */
final class WindData {

    static final long T0 = 1_790_000_000_000_000L; // 2026-09-21T...Z

    private WindData() {
    }

    static SeriesKey key(String channel, String turbine) {
        String unit = switch (channel) {
            case "wind_speed" -> "m/s";
            case "power_active" -> "kW";
            default -> "rpm";
        };
        return SeriesKeys.of("wind.turbine." + channel, Map.of("park", "ber-01", "turbine", turbine), unit);
    }

    /** 1 Hz signal of one channel and turbine, quantized to the sensor resolution. */
    static double[] signal(String channel, int turbine, long startSecond, int n, Random r) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            double t = startSecond + i;
            double wind = 8 + 2.5 * Math.sin(t / 900.0 + turbine) + 0.8 * Math.sin(t / 37.0) + r.nextGaussian() * 0.4;
            v[i] = switch (channel) {
                case "wind_speed" -> Math.round(wind * 100) / 100.0;                                   // 0.01 m/s
                case "power_active" -> Math.round(Math.min(3000, Math.max(0, 6 * Math.pow(wind, 3))) * 10) / 10.0; // 0.1 kW
                default -> Math.round(Math.min(16, wind * 1.4) * 100) / 100.0;                         // 0.01 rpm
            };
        }
        return v;
    }

    /** turbines x channels x episodes, each a REGULAR 1 Hz episode of {@code seconds} values. */
    static List<Episode> episodes(int turbines, List<String> channels, int episodesPerSeries, int seconds, long seed) {
        Random r = new Random(seed);
        List<Episode> out = new ArrayList<>();
        for (int e = 0; e < episodesPerSeries; e++) {
            for (int t = 1; t <= turbines; t++) {
                for (String c : channels) {
                    long startSecond = (long) e * seconds;
                    out.add(Episodes.regular(key(c, String.format("T%02d", t)), T0 + startSecond * 1_000_000L, 1_000_000L,
                                    signal(c, t, startSecond, seconds, r))
                            .source("simulator:test").label("channel", c).build());
                }
            }
        }
        return out;
    }
}
