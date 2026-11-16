package org.opentsx.retrieval.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.generate.Generators;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Operator example gen.sine (docs/user-manual/17-operators.md): the output has the configured mean, amplitude and period. */
class SineGeneratorExampleTest {

    private static final long MINUTE = 60_000_000L;
    private static final long DAY = 1440 * MINUTE;

    @Test
    void sevenDaysHaveTheConfiguredMeanAmplitudeAndPeriod() throws IOException {
        long from = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));

        // usage:begin
        JsonNode params = new ObjectMapper().readTree(
                "{\"mean\": 60, \"amplitude\": 30, \"period\": \"P1D\", \"phase\": 0.5, \"sigma\": 3}");
        Generators.Target target = new Generators.Target("h2-chain", "offtake.demand_kgph",
                "h2-chain.offtake.demand_kgph", "kg/h", "gen.sine", 1, 0.0, 120.0, "example");
        List<Episode> episodes = Generators.episodes(target, params, 15 * MINUTE, from, from + 7 * DAY);
        double[] values = episodes.stream().flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
        // usage:end

        assertEquals(672, values.length);
        double mean = Arrays.stream(values).average().orElseThrow();
        double s = 0;
        double c = 0;
        for (int i = 0; i < values.length; i++) {
            s += (values[i] - mean) * Math.sin(2 * Math.PI * i / 96.0);
            c += (values[i] - mean) * Math.cos(2 * Math.PI * i / 96.0);
        }
        double amplitude = 2.0 / values.length * Math.hypot(s, c);
        int period = 0;
        double best = -1;
        for (int lag = 48; lag <= 144; lag++) {
            double r = autocorrelation(values, lag);
            if (r > best) {
                best = r;
                period = lag;
            }
        }
        assertEquals(60, mean, 0.5);
        assertEquals(30, amplitude, 0.5);
        assertEquals(96, period, "one day in steps of 15 minutes");

        ExampleChart chart = new ExampleChart("gen.sine", "gen.sine: mean 60, amplitude 30, period 24 h");
        double[] hour = new double[288];
        double[] cycle = new double[288];
        for (int i = 0; i < 288; i++) {
            hour[i] = i / 4.0;
            cycle[i] = 60 - 30 * Math.sin(2 * Math.PI * i / 96.0);
        }
        chart.panel("Output, first 3 days", "hour", "kg/h", "")
                .add("generated", "line", hour, Arrays.copyOf(values, 288))
                .add("configured cycle", "line", hour, cycle);
        double[] ofDay = new double[384];
        for (int i = 0; i < 384; i++) {
            ofDay[i] = (i % 96) / 4.0;
        }
        chart.panel("First 4 days on one day", "hour of the day", "kg/h", "")
                .add("generated", "point", ofDay, Arrays.copyOf(values, 384))
                .add("configured cycle", "line", Arrays.copyOf(hour, 97), Arrays.copyOf(cycle, 97));
        chart.note("expected: 60, 30, 24 h")
                .note(String.format("measured: %.1f, %.1f, %.1f h", mean, amplitude, period / 4.0));
        chart.write();
    }

    private static double autocorrelation(double[] v, int lag) {
        double mean = Arrays.stream(v).average().orElseThrow();
        double num = 0;
        double den = 0;
        for (int i = 0; i < v.length; i++) {
            den += (v[i] - mean) * (v[i] - mean);
            if (i + lag < v.length) {
                num += (v[i] - mean) * (v[i + lag] - mean);
            }
        }
        return num / den;
    }
}
