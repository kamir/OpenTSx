package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.EpisodeSummary;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeAxisAndSummaryTest {

    @Test
    void deltaEncodingMatchesSharedVectorsAndRoundTrips() {
        for (JsonNode v : TestVectors.load("time-deltas.json")) {
            long[] ts = TestVectors.longs(v.get("timestampsUs"));
            byte[] encoded = TimeAxis.encodeDeltas(ts);
            assertEquals(v.get("deltasHex").asText(), TestVectors.hex(encoded));
            assertArrayEquals(ts, TimeAxis.decodeDeltas(ts[0], ts.length, ByteBuffer.wrap(encoded)));
        }
    }

    @Test
    void regularSecondsCostOneBytePerTimestamp() {
        long[] ts = new long[10_000];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = 1_700_000_000_000_000L + i * 1_000_000L + (i % 3 == 0 ? 7 : 0); // jitter
        }
        assertTrue(TimeAxis.encodeDeltas(ts).length < 2 * ts.length);
    }

    @Test
    void rejectsNonIncreasingTimestampsAndCorruptDeltas() {
        assertThrows(IllegalArgumentException.class, () -> TimeAxis.encodeDeltas(new long[]{5, 5}));
        assertThrows(IllegalArgumentException.class, () -> TimeAxis.encodeDeltas(new long[]{5, 4}));
        assertThrows(IllegalArgumentException.class,
                () -> TimeAxis.decodeDeltas(0, 3, ByteBuffer.wrap(new byte[]{2})));
        assertThrows(IllegalArgumentException.class,
                () -> TimeAxis.decodeDeltas(0, 2, ByteBuffer.wrap(new byte[]{2, 2})));
    }

    @Test
    void summaryMatchesTwoPassStatisticsAndCountsNaN() {
        double[] v = {1, 2, 3, Double.NaN, 4, 10};
        EpisodeSummary s = Summaries.of(v);
        double[] finite = Arrays.stream(v).filter(x -> !Double.isNaN(x)).toArray();
        double mean = Arrays.stream(finite).average().orElseThrow();
        double var = Arrays.stream(finite).map(x -> (x - mean) * (x - mean)).sum() / (finite.length - 1);
        assertEquals(5, s.getValidCount());
        assertEquals(1, s.getNanCount());
        assertEquals(1.0, s.getMin());
        assertEquals(10.0, s.getMax());
        assertEquals(mean, s.getMean(), 1e-12);
        assertEquals(var, Summaries.variance(s), 1e-12);
    }

    @Test
    void mergedSummaryEqualsSummaryOfConcatenation() {
        Random r = new Random(3);
        double[] a = r.doubles(1000, -5, 5).toArray();
        double[] b = r.doubles(37, 100, 200).toArray();
        double[] all = new double[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        EpisodeSummary merged = Summaries.merge(Summaries.of(a), Summaries.of(b));
        EpisodeSummary direct = Summaries.of(all);
        assertEquals(direct.getValidCount(), merged.getValidCount());
        assertEquals(direct.getMean(), merged.getMean(), 1e-9);
        assertEquals(direct.getM2(), merged.getM2(), 1e-6);
        assertEquals(direct.getMin(), merged.getMin());
        assertEquals(direct.getMax(), merged.getMax());
    }

    @Test
    void emptyAndAllNaNSummaries() {
        EpisodeSummary empty = Summaries.of(new double[0]);
        assertEquals(0, empty.getValidCount());
        assertTrue(Double.isNaN(empty.getMean()));
        EpisodeSummary nan = Summaries.of(new double[]{Double.NaN, Double.NaN});
        assertEquals(2, nan.getNanCount());
        EpisodeSummary merged = Summaries.merge(nan, Summaries.of(new double[]{4, 6}));
        assertEquals(2, merged.getValidCount());
        assertEquals(5.0, merged.getMean());
        assertEquals(2, merged.getNanCount());
    }
}
