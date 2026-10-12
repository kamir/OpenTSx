package org.opentsx.stats;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsEngineTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p1", "turbine", "T01"), "m/s");

    /** 10-minute 1 Hz episodes of a random walk. */
    private static List<Episode> episodes(int count, long seed) {
        Random r = new Random(seed);
        List<Episode> out = new ArrayList<>();
        double v = 8;
        for (int k = 0; k < count; k++) {
            double[] values = new double[600];
            for (int i = 0; i < values.length; i++) {
                v = Math.max(0, v + r.nextGaussian() * 0.2);
                values[i] = v;
            }
            out.add(Episodes.regular(KEY, T0 + k * 600 * SEC, SEC, values).source("test").build());
        }
        return out;
    }

    private static StatsEngine engine() {
        return new StatsEngine(StatsEngine.Config.defaults());
    }

    @Test
    void tenMinuteWindowsEqualTheEpisodeSummaries() {
        StatsEngine engine = engine();
        List<Episode> eps = episodes(12, 1);   // 2 hours
        List<SeriesStats> out = new ArrayList<>();
        eps.forEach(e -> out.addAll(engine.onEpisode(e)));
        List<SeriesStats> tenMin = out.stream().filter(s -> s.getWindowType().equals("PT10M")).toList();
        assertEquals(12, tenMin.size(), "each episode ends exactly at a window boundary and closes it");
        for (int i = 0; i < 12; i++) {
            var sum = eps.get(i).getSummary();
            SeriesStats s = tenMin.get(i);
            assertEquals(sum.getValidCount(), s.getValidCount());
            assertEquals(sum.getMean(), s.getMean(), 1e-9);
            assertEquals(sum.getM2(), s.getM2(), 1e-6);
            assertEquals(sum.getMin(), s.getMin());
            assertEquals(sum.getMax(), s.getMax());
            assertEquals(0, s.getGapCount());
        }
        List<SeriesStats> hours = out.stream().filter(s -> s.getWindowType().equals("PT1H")).toList();
        assertEquals(2, hours.size());
        assertEquals(3600, hours.get(0).getValidCount());

        // GLOBAL = merge of everything; median from the sketch within 1 %
        SeriesStats global = engine.emitGlobal().get(0);
        assertEquals(StatsEngine.GLOBAL, global.getWindowType());
        assertEquals(7200, global.getValidCount());
        double[] all = eps.stream().flatMapToDouble(e -> java.util.Arrays.stream(Episodes.values(e))).sorted().toArray();
        double median = QuantileSketch.fromBytes(global.getQuantileSketch()).quantile(0.5);
        assertEquals(all[(all.length - 1) / 2], median, 0.01 * median);
        assertTrue(engine.emitGlobal().isEmpty(), "nothing changed since the last emission");
    }

    @Test
    void duplicatesAreIgnoredAndGapsCounted() {
        StatsEngine engine = engine();
        List<Episode> eps = episodes(6, 2);
        List<SeriesStats> out = new ArrayList<>(engine.onEpisode(eps.get(0)));
        out.addAll(engine.onEpisode(eps.get(0)));  // KafScale: no idempotent producer
        out.addAll(engine.onEpisode(eps.get(1)));
        out.addAll(engine.onEpisode(eps.get(3)));  // episode 2 missing -> one gap of 10 minutes
        out.addAll(engine.flush());
        assertEquals(1, engine.metrics().duplicates());
        SeriesStats global = out.stream().filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).findFirst().orElseThrow();
        assertEquals(1800, global.getValidCount());
        assertEquals(1, global.getGapCount());
        SeriesStats w3 = out.stream().filter(s -> s.getWindowType().equals("PT10M")
                && Micros.of(s.getWindowStart()) == T0 + 1800 * SEC).findFirst().orElseThrow();
        assertEquals(1, w3.getGapCount(), "the gap is attributed to the window in which data resumes");
    }

    @Test
    void lateValuesOnlyCountGlobally() {
        StatsEngine engine = engine();
        List<Episode> eps = episodes(3, 3);
        engine.onEpisode(eps.get(0));
        engine.onEpisode(eps.get(2));             // closes windows 0 (and 1, which stays empty)
        engine.onEpisode(eps.get(1));             // late for a closed window
        assertEquals(600, engine.metrics().lateValues());
        assertEquals(1800, engine.global(KEY.getSeriesId()).validCount());
    }

    @Test
    void observationsUseTheirTimestampsAndConfiguredInterval() {
        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withWindows(Duration.ofMinutes(1))
                .withExpectedInterval(id -> OptionalLong.of(SEC)));
        List<SeriesStats> out = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            if (i >= 70 && i < 80) {
                continue;                        // 10 s outage
            }
            out.addAll(engine.onObservation(new Observation(KEY.getSeriesId(), Micros.toInstant(T0 + i * SEC), (double) i, null)));
        }
        assertEquals(2, out.size(), "minutes 0 and 1 closed, minute 2 still open");
        assertEquals(50, out.get(1).getValidCount());
        assertEquals(1, out.get(1).getGapCount());
    }

    @Test
    void checkpointRestoreGivesTheSameResultAsAnUninterruptedRun() throws Exception {
        List<Episode> eps = episodes(9, 4);
        StatsEngine straight = engine();
        List<SeriesStats> expected = new ArrayList<>();
        eps.forEach(e -> expected.addAll(straight.onEpisode(e)));
        expected.addAll(straight.flush());

        StatsEngine first = engine();
        List<SeriesStats> actual = new ArrayList<>();
        eps.subList(0, 4).forEach(e -> actual.addAll(first.onEpisode(e)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        first.writeState(new DataOutputStream(bytes));
        StatsEngine second = engine();
        second.readState(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        second.onEpisode(eps.get(3));             // replayed after the checkpoint -> deduplicated
        eps.subList(4, 9).forEach(e -> actual.addAll(second.onEpisode(e)));
        actual.addAll(second.flush());
        assertEquals(expected, actual);
        assertEquals(1, second.metrics().duplicates());
    }
}
