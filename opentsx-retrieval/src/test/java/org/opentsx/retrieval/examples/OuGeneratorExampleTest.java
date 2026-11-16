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

/** Operator example gen.ou (docs/user-manual/17-operators.md): the lag-1 autocorrelation is 1 − reversion. */
class OuGeneratorExampleTest {

    private static final long MINUTE = 60_000_000L;

    @Test
    void lagOneAutocorrelationIsOneMinusReversion() throws IOException {
        long from = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));

        // usage:begin
        JsonNode params = new ObjectMapper().readTree("{\"mean\": 62.8, \"sigma\": 9.0, \"reversion\": 0.08}");
        Generators.Target target = new Generators.Target("h2-chain", "market.price_eur_mwh",
                "h2-chain.market.price_eur_mwh", "EUR/MWh", "gen.ou", 7, null, null, "example");
        List<Episode> episodes = Generators.episodes(target, params, 15 * MINUTE, from, from + 5000 * 15 * MINUTE);
        double[] values = episodes.stream().flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
        // usage:end

        assertEquals(5000, values.length);
        double mean = Arrays.stream(values).average().orElseThrow();
        double std = Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / values.length);
        double expectedStd = 9.0 / Math.sqrt(1 - 0.92 * 0.92);
        assertEquals(62.8, mean, 3);
        assertEquals(expectedStd, std, 0.1 * expectedStd);

        double[] reversion = {0.02, 0.08, 0.2, 0.35, 0.5};
        double[] measured = new double[reversion.length];
        for (int k = 0; k < reversion.length; k++) {
            JsonNode p = new ObjectMapper().readTree("{\"mean\": 62.8, \"sigma\": 9.0, \"reversion\": " + reversion[k] + "}");
            double[] v = Generators.values("gen.ou", p, 7, 15 * MINUTE, Math.floorDiv(from, 15 * MINUTE), 5000, null, null);
            measured[k] = 1 - lagOne(v);
            assertEquals(reversion[k], measured[k], 0.015, "reversion " + reversion[k]);
        }

        ExampleChart chart = new ExampleChart("gen.ou", "gen.ou: lag-1 autocorrelation is 1 − reversion");
        double[] day = new double[500];
        for (int i = 0; i < 500; i++) {
            day[i] = i / 96.0;
        }
        chart.panel("Output with reversion 0.08, first 500 steps", "day", "EUR/MWh", "")
                .add("generated", "line", day, Arrays.copyOf(values, 500));
        chart.panel("5000 steps per reversion", "configured reversion", "1 − lag-1 autocorrelation", "")
                .add("expected", "line", new double[]{0, 0.5}, new double[]{0, 0.5})
                .add("measured", "point", reversion, measured);
        chart.note("expected: 1 − r1 = reversion")
                .note(String.format("measured at 0.08: %.3f", measured[1]))
                .note(String.format("mean %.1f, std %.1f (expected %.1f)", mean, std, expectedStd));
        chart.write();
    }

    private static double lagOne(double[] v) {
        double mean = Arrays.stream(v).average().orElseThrow();
        double num = 0;
        double den = 0;
        for (int i = 0; i < v.length; i++) {
            den += (v[i] - mean) * (v[i] - mean);
            if (i + 1 < v.length) {
                num += (v[i] - mean) * (v[i + 1] - mean);
            }
        }
        return num / den;
    }
}
