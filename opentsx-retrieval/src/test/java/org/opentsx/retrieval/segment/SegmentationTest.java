package org.opentsx.retrieval.segment;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentationTest {

    static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    static final long SEC = 1_000_000L;
    static final SeriesKey KEY = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p1", "turbine", "T01"), "m/s");

    /** 1 Hz series of {@code seconds} samples, cut into episodes of {@code chunk} seconds. */
    static List<Episode> series(int seconds, int chunk, IntToDoubleFunction f) {
        List<Episode> out = new ArrayList<>();
        for (int s = 0; s < seconds; s += chunk) {
            int n = Math.min(chunk, seconds - s);
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = f.applyAsDouble(s + i);
            }
            out.add(Episodes.regular(KEY, T0 + s * SEC, SEC, v).source("test").label("scenario", "s1")
                    .label("gt.operation", "x").build());
        }
        return out;
    }

    static double[] values(List<Episode> eps) {
        return eps.stream().flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
    }

    @Test
    void fixedDurationRecutsAndIsDeterministic() {
        List<Episode> eps = series(7200, 600, i -> i);
        List<Episode> hours = Segmentations.segment(eps, Segmenter.fixedDuration(Duration.ofHours(1)));
        assertEquals(2, hours.size());
        Episode h = hours.get(1);
        assertEquals(3600, h.getCount());
        assertEquals(TimeEncoding.REGULAR, h.getTimeEncoding());
        assertEquals(3600.0, Episodes.values(h)[0]);
        assertEquals(SegmentationStrategy.FIXED_DURATION, h.getSegmentation().getStrategy());
        assertEquals("PT1H", h.getSegmentation().getParams().get("duration"));
        assertEquals(eps.get(6).getEpisodeId(), h.getProvenance().getParentEpisodeId());
        assertEquals("s1", h.getLabels().get("scenario"));
        assertTrue(!h.getLabels().containsKey("gt.operation"), "ground truth does not propagate");
        assertEquals(hours, Segmentations.segment(eps, Segmenter.fixedDuration(Duration.ofHours(1))), "deterministic");
        Episodes.validate(h);
    }

    @Test
    void fixedCountWithOverlap() {
        List<Episode> eps = series(1000, 300, i -> i);
        List<Episode> blocks = Segmentations.segment(eps, Segmenter.fixedCount(100, 50));
        assertEquals(19, blocks.size());
        assertEquals(50.0, Episodes.values(blocks.get(1))[0]);
        assertEquals(100, blocks.get(18).getCount());
    }

    @Test
    void thresholdWithHysteresisPrePostAndDurations() {
        // three phases above 20: 120 s (kept), 20 s (too short), 900 s (capped at 600 s); noise around the threshold
        Random r = new Random(1);
        IntToDoubleFunction f = i -> {
            double base = (i >= 1000 && i < 1120) || (i >= 2000 && i < 2020) || (i >= 3000 && i < 3900) ? 22 : 15;
            return base + r.nextGaussian() * 0.8;
        };
        double[] v = new double[5000];
        for (int i = 0; i < v.length; i++) {
            v[i] = f.applyAsDouble(i);
        }
        List<Episode> eps = series(5000, 600, i -> v[i]);
        List<Episode> segs = Segmentations.segment(eps, Segmenter.threshold(Segmenter.Direction.ABOVE, 20, 18,
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMinutes(10)));
        assertEquals(2, segs.size(), "hysteresis prevents chatter, short phase dropped");
        Episode a = segs.get(0);
        long anchor = Micros.of(a.getSegmentation().getAnchorTs());
        assertEquals(T0 + 1000 * SEC, anchor);
        assertEquals(T0 + 970 * SEC, Micros.of(a.getTStart()), "30 s before");
        assertEquals(1120 + 60 - 970, a.getCount(), "until exit + 60 s");
        Episode b = segs.get(1);
        assertEquals(30 + 600 + 60, b.getCount(), "capped at maxDuration");
    }

    @Test
    void extremaFindPlantedGustsAndIgnoreNoise() {
        Random r = new Random(2);
        int[] peaks = {500, 1700, 1760, 3100};          // 1760 is a smaller peak within distance of 1700
        double[] heights = {6, 5, 3, 7};
        double[] v = new double[4000];
        for (int i = 0; i < v.length; i++) {
            v[i] = 8 + r.nextGaussian() * 0.3;
            for (int p = 0; p < peaks.length; p++) {
                v[i] += heights[p] * Math.exp(-0.5 * Math.pow((i - peaks[p]) / 8.0, 2));
            }
        }
        List<Episode> segs = Segmentations.segment(series(4000, 600, i -> v[i]), Segmenter.extremum(
                Segmenter.Feature.MAX, 2.0, Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofSeconds(60),
                Duration.ofSeconds(90)));
        assertEquals(3, segs.size(), segs.stream().map(e -> e.getSegmentation().getAnchorTs()).toList().toString());
        long[] anchors = segs.stream().mapToLong(e -> (Micros.of(e.getSegmentation().getAnchorTs()) - T0) / SEC).toArray();
        for (int k = 0; k < 3; k++) {
            int expected = new int[]{500, 1700, 3100}[k];
            assertTrue(Math.abs(anchors[k] - expected) <= 3, "peak near " + expected + " but " + anchors[k]);
        }
        assertEquals(151, segs.get(0).getCount(), "60 s before .. 90 s after, inclusive");
    }

    @Test
    void trimmingNeverChangesTheResult() {
        Random r = new Random(3);
        double[] v = new double[20_000];
        double x = 10;
        for (int i = 0; i < v.length; i++) {
            x += r.nextGaussian() * 0.3 + (10 - x) * 0.01;
            v[i] = i % 997 == 0 ? Double.NaN : x;
        }
        List<Episode> eps = series(v.length, 600, i -> v[i]);
        List<Segmenter> all = List.of(
                Segmenter.fixedDuration(Duration.ofMinutes(7), Duration.ofSeconds(13)),
                Segmenter.fixedCount(256, 128),
                Segmenter.extremum(Segmenter.Feature.MIN, 1.5, Duration.ofMinutes(4), Duration.ofMinutes(3),
                        Duration.ofSeconds(45), Duration.ofSeconds(80)),
                Segmenter.threshold(Segmenter.Direction.ABOVE, 11, 10.5, Duration.ofSeconds(20), Duration.ofSeconds(40),
                        Duration.ofSeconds(5), Duration.ofMinutes(3)));
        for (Segmenter s : all) {
            StreamingSegmenter trimmed = new StreamingSegmenter(s, true);
            StreamingSegmenter full = new StreamingSegmenter(s, false);
            List<Episode> a = new ArrayList<>();
            List<Episode> b = new ArrayList<>();
            for (Episode e : eps) {
                a.addAll(trimmed.onEpisode(e));
                b.addAll(full.onEpisode(e));
            }
            a.addAll(trimmed.flush());
            b.addAll(full.flush());
            assertTrue(a.size() > 3, s + " found " + a.size());
            assertEquals(b, a, s.toString());
        }
    }

    @Test
    void streamingEmitsAsSoonAsASegmentIsFinal() {
        List<Episode> eps = series(3600, 600, i -> i >= 700 && i < 800 ? 30 : 10);
        StreamingSegmenter s = new StreamingSegmenter(Segmenter.threshold(Segmenter.Direction.ABOVE, 20, 20,
                Duration.ZERO, Duration.ofSeconds(10), Duration.ZERO, Duration.ZERO));
        assertTrue(s.onEpisode(eps.get(0)).isEmpty());
        List<Episode> second = s.onEpisode(eps.get(1));   // 600..1199 contains the whole phase + 10 s
        assertEquals(1, second.size());
        assertArrayEquals(new double[]{30, 30}, Arrays.copyOf(Episodes.values(second.get(0)), 2));
        assertEquals(110, second.get(0).getCount());
        assertNotNull(second.get(0).getSegmentation().getSourceEpisodeId());
        assertTrue(s.flush().isEmpty());
    }
}
