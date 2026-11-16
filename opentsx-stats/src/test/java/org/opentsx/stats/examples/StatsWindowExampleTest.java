package org.opentsx.stats.examples;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsEngine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator example stats.welford-ddsketch (docs/user-manual/17-operators.md): hourly windows and sketch quantiles. */
class StatsWindowExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s");

    @Test
    void hourlyWindowsFollowTheInputAndSketchQuantilesStayWithinOnePercent() throws IOException {
        Random r = new Random(11);
        double[] values = new double[2160];
        for (int i = 0; i < values.length; i++) {
            values[i] = i < 1440 ? 8 + r.nextGaussian() : 12 + 3 * r.nextGaussian();
        }
        List<Episode> episodes = new ArrayList<>();
        for (int k = 0; k < 36; k++) {
            double[] block = Arrays.copyOfRange(values, 60 * k, 60 * k + 60);
            episodes.add(Episodes.regular(KEY, T0 + 600 * SEC * k, 10 * SEC, block).source("example").build());
        }

        // usage:begin
        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withWindows(Duration.ofHours(1)));
        List<SeriesStats> hours = new ArrayList<>();
        for (Episode e : episodes) {
            hours.addAll(engine.onEpisode(e));
        }
        SeriesStats global = engine.emitGlobal().get(0);
        QuantileSketch sketch = QuantileSketch.fromBytes(global.getQuantileSketch());
        double median = sketch.quantile(0.5);
        // usage:end

        assertEquals(6, hours.size());
        double[] mean = new double[6];
        double[] std = new double[6];
        double meanOff = 0;
        double stdOff = 0;
        for (int h = 0; h < 6; h++) {
            SeriesStats s = hours.get(h);
            assertEquals(360, s.getValidCount());
            mean[h] = s.getMean();
            std[h] = Math.sqrt(s.getM2() / (s.getValidCount() - 1));
            double mu = h < 4 ? 8 : 12;
            double sigma = h < 4 ? 1 : 3;
            assertEquals(mu, mean[h], 0.15 * sigma, "mean of hour " + h);
            assertEquals(sigma, std[h], 0.15 * sigma, "std of hour " + h);
            meanOff = Math.max(meanOff, Math.abs(mean[h] - mu));
            stdOff = Math.max(stdOff, Math.abs(std[h] - sigma));
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        assertEquals(sorted[(sorted.length - 1) / 2], median, 0.01 * median);
        double[] q = new double[19];
        double[] fromSketch = new double[19];
        double worst = 0;
        for (int k = 0; k < 19; k++) {
            q[k] = 0.05 * (k + 1);
            double exact = sorted[(int) Math.floor(q[k] * (sorted.length - 1))];
            fromSketch[k] = sketch.quantile(q[k]);
            worst = Math.max(worst, 100 * Math.abs(fromSketch[k] - exact) / Math.abs(exact));
        }
        assertTrue(worst <= 1.0, "largest relative error " + worst + " %");

        double[] t = new double[540];
        double[] shown = new double[540];
        for (int i = 0; i < 540; i++) {
            t[i] = 4 * i * 10 / 3600.0;
            shown[i] = values[4 * i];
        }
        double[] rank = new double[201];
        double[] ordered = new double[201];
        for (int i = 0; i <= 200; i++) {
            rank[i] = i / 200.0;
            ordered[i] = sorted[(int) Math.floor(rank[i] * (sorted.length - 1))];
        }
        double[] hour = {0, 1, 2, 3, 4, 5, 6};
        ExampleChart chart = new ExampleChart("stats.welford-ddsketch",
                "Window statistics: hourly mean and std follow the input, sketch quantiles are within 1 %");
        chart.panel("Input, every 4th of 2160 samples (10 s)", "hour", "value", "")
                .add("value", "line", t, shown);
        chart.panel("Hourly windows of StatsEngine", "hour", "value", "")
                .add("window mean", "step", hour, closed(mean))
                .add("window std", "step", hour, closed(std))
                .note("expected: mean 8, 12, std 1, 3")
                .note(String.format("measured: mean off by %.2f", meanOff))
                .note(String.format("measured: std off by %.2f", stdOff));
        chart.panel("Quantiles of all values", "probability q", "value", "")
                .add("sketch quantile", "bar", q, fromSketch)
                .add("value", "line", rank, ordered)
                .note("expected: at most 1 % off")
                .note(String.format("measured: at most %.2f %% off", worst));
        chart.write();
    }

    private static double[] closed(double[] v) {
        double[] out = Arrays.copyOf(v, v.length + 1);
        out[v.length] = v[v.length - 1];
        return out;
    }
}
