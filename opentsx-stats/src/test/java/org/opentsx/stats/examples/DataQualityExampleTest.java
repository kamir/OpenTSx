package org.opentsx.stats.examples;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.DataQuality;
import org.opentsx.stats.StatsEngine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Operator example dq.stats (docs/user-manual/17-operators.md): a stuck sensor and an outage fail in their own windows. */
class DataQualityExampleTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;

    @Test
    void stuckSensorAndOutageFailInExactlyTheirWindows() throws IOException {
        String id = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s").getSeriesId();
        Random r = new Random(3);
        List<Observation> observations = new ArrayList<>();
        double[] t = new double[720];
        double[] v = new double[720];
        for (int i = 0; i < 720; i++) {
            t[i] = i / 6.0;
            v[i] = t[i] >= 80 && t[i] < 90 ? 9.3 : 8 + r.nextGaussian();
            if (t[i] >= 102 && t[i] < 108) {
                v[i] = Double.NaN;
                continue;
            }
            observations.add(new Observation(id, Micros.toInstant(T0 + i * 10 * SEC), v[i], null));
        }

        // usage:begin
        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withWindows(Duration.ofMinutes(10))
                .withExpectedInterval(series -> OptionalLong.of(10_000_000L)));
        List<SeriesStats> windows = new ArrayList<>();
        for (Observation o : observations) {
            windows.addAll(engine.onObservation(o));
        }
        windows.addAll(engine.flush());
        DataQuality.Expectation expected = DataQuality.Expectation.of(0.0, 40.0, Duration.ofSeconds(10));
        List<DataQuality.Result> results = new ArrayList<>();
        for (SeriesStats w : windows) {
            if (w.getWindowType().equals("PT10M")) {
                results.addAll(DataQuality.evaluate(w, expected));
            }
        }
        // usage:end

        Set<String> failed = new TreeSet<>();
        double[] start = new double[12];
        double[] flat = new double[12];
        double[] incomplete = new double[12];
        double[] gaps = new double[12];
        for (DataQuality.Result res : results) {
            int k = (int) ((res.windowStartMicros() - T0) / (600 * SEC));
            start[k] = 10 * k + 5;
            if (res.failed()) {
                failed.add(res.check() + " " + 10 * k);
                switch (res.check()) {
                    case FLATLINE -> flat[k] = 1;
                    case COMPLETENESS -> incomplete[k] = 1;
                    case GAPS -> gaps[k] = 1;
                    default -> { }
                }
            }
        }
        assertEquals(12, results.stream().filter(res -> res.check() == DataQuality.Check.COMPLETENESS).count());
        assertEquals(12, results.stream().filter(res -> res.check() == DataQuality.Check.GAPS).count());
        assertEquals(12, results.stream().filter(res -> res.check() == DataQuality.Check.PLAUSIBLE_RANGE).count());
        assertEquals(Set.of("FLATLINE 80", "COMPLETENESS 100", "GAPS 100"), failed);
        DataQuality.Result stuck = results.stream()
                .filter(res -> res.check() == DataQuality.Check.FLATLINE && res.failed()).findFirst().orElseThrow();
        DataQuality.Result outage = results.stream()
                .filter(res -> res.check() == DataQuality.Check.COMPLETENESS && res.failed()).findFirst().orElseThrow();
        DataQuality.Result gap = results.stream()
                .filter(res -> res.check() == DataQuality.Check.GAPS && res.failed()).findFirst().orElseThrow();
        double flatOthers = results.stream().filter(res -> res.check() == DataQuality.Check.FLATLINE && !res.failed())
                .mapToDouble(DataQuality.Result::value).min().orElseThrow();
        assertEquals(0.0, stuck.value());
        assertEquals(0.4, outage.value(), 1e-12);
        assertEquals(1.0, gap.value());

        double[] ts = new double[360];
        double[] vs = new double[360];
        for (int i = 0; i < 360; i++) {
            ts[i] = t[2 * i];
            vs[i] = v[2 * i];
        }
        ExampleChart chart = new ExampleChart("dq.stats", "Data quality: checks fail only in faulty windows");
        chart.panel("Input: wind speed, every 2nd of 720 samples (10 s)", "minute", "m/s", "")
                .add("wind speed", "line", ts, vs);
        chart.panel("Failed checks per 10-minute window", "minute", "failed (1) or passed (0)", "")
                .add("FLATLINE", "bar", start, flat)
                .add("COMPLETENESS", "bar", start, incomplete)
                .add("GAPS", "bar", start, gaps)
                .note("expected: FLATLINE 80 to 90 min")
                .note("COMPLETENESS, GAPS 100 to 110")
                .note(String.format("measured: std %.0f, else %.2f or more", stuck.value(), flatOthers))
                .note(String.format("valid %.0f %%, %.0f gap", 100 * outage.value(), gap.value()));
        chart.write();
    }
}
