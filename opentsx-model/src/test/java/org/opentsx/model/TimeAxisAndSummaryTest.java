package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.EpisodeSummary;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theLargestStepIsEncodedAndDecoded() {
        long[] ts = {Long.MIN_VALUE, -1, Long.MAX_VALUE - 1};
        byte[] encoded = TimeAxis.encodeDeltas(ts);
        assertEquals("feffffffffffffffff0100", TestVectors.hex(encoded));
        assertArrayEquals(ts, TimeAxis.decodeDeltas(ts[0], ts.length, ByteBuffer.wrap(encoded)));
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
        assertEquals("timeDeltas: input ends inside a varint; expected complete varints",
                assertThrows(IllegalArgumentException.class,
                        () -> TimeAxis.decodeDeltas(0, 3, ByteBuffer.wrap(new byte[]{2}))).getMessage());
        assertEquals("timeDeltas: input ends inside a varint; expected complete varints",
                assertThrows(IllegalArgumentException.class,
                        () -> TimeAxis.decodeDeltas(0, 2, ByteBuffer.wrap(new byte[]{(byte) 0x82}))).getMessage());
        assertEquals("timeDeltas: 1 trailing bytes; expected exactly 1 varints for 2 timestamps",
                assertThrows(IllegalArgumentException.class,
                        () -> TimeAxis.decodeDeltas(0, 2, ByteBuffer.wrap(new byte[]{2, 2}))).getMessage());
        byte[] eleven = new byte[11];
        Arrays.fill(eleven, (byte) 0x80);
        assertEquals("timeDeltas: varint longer than 10 bytes; expected at most 10 bytes per varint",
                assertThrows(IllegalArgumentException.class,
                        () -> TimeAxis.decodeDeltas(0, 2, ByteBuffer.wrap(eleven))).getMessage());
    }

    @Test
    void theVarintHelperRefusesTruncatedInputLikeTheTimeAxis() {
        byte[] truncated = {2, (byte) 0x83};
        IllegalArgumentException helper = assertThrows(IllegalArgumentException.class, () -> {
            ByteBuffer in = ByteBuffer.wrap(truncated);
            Varints.readZigZag(in, "timeDeltas");
            Varints.readZigZag(in, "timeDeltas");
        });
        IllegalArgumentException axis = assertThrows(IllegalArgumentException.class,
                () -> TimeAxis.decodeDeltas(0, 3, ByteBuffer.wrap(truncated)));
        assertEquals("timeDeltas: input ends inside a varint; expected complete varints", helper.getMessage());
        assertEquals(helper.getMessage(), axis.getMessage());
        assertEquals("sketch bytes: input ends inside a varint; expected complete varints",
                assertThrows(IllegalArgumentException.class,
                        () -> Varints.readUnsigned(ByteBuffer.wrap(new byte[]{(byte) 0x80}), "sketch bytes")).getMessage());
    }

    @Test
    void varintsRoundTripTheWholeLongRange() {
        long[] signed = {0, 1, -1, 63, -64, 64, 300, Long.MAX_VALUE, Long.MIN_VALUE};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long v : signed) {
            Varints.writeZigZag(out, v);
        }
        Varints.writeUnsigned(out, 0);
        Varints.writeUnsigned(out, 300);
        Varints.writeUnsigned(out, Long.MAX_VALUE);
        ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
        for (long v : signed) {
            assertEquals(v, Varints.readZigZag(in, "test"));
        }
        assertEquals(0, Varints.readUnsigned(in, "test"));
        assertEquals(300, Varints.readUnsigned(in, "test"));
        assertEquals(Long.MAX_VALUE, Varints.readUnsigned(in, "test"));
        assertFalse(in.hasRemaining());

        ByteArrayOutputStream zero = new ByteArrayOutputStream();
        Varints.writeZigZag(zero, 0);
        Varints.writeZigZag(zero, -1);
        Varints.writeZigZag(zero, 1);
        assertArrayEquals(new byte[]{0, 1, 2}, zero.toByteArray());
        assertEquals("varint: \"-1\" is not allowed; expected a whole number from 0 to 9223372036854775807",
                assertThrows(IllegalArgumentException.class, () -> Varints.writeUnsigned(zero, -1)).getMessage());
        ByteArrayOutputStream top = new ByteArrayOutputStream();
        Varints.writeZigZag(top, Long.MIN_VALUE);
        assertEquals("test: varint exceeds 9223372036854775807; expected a whole number from 0 to 9223372036854775807",
                assertThrows(IllegalArgumentException.class,
                        () -> Varints.readUnsigned(ByteBuffer.wrap(top.toByteArray()), "test")).getMessage());
    }

    @Test
    void varintsRefuseATenthByteAbove64Bits() {
        byte[] wide = new byte[10];
        Arrays.fill(wide, (byte) 0xFF);
        wide[9] = 0x02;
        assertEquals("test: varint exceeds 64 bits; expected at most 64 bits per varint",
                assertThrows(IllegalArgumentException.class,
                        () -> Varints.readUnsigned(ByteBuffer.wrap(wide), "test")).getMessage());
        assertEquals("timeDeltas: varint exceeds 64 bits; expected at most 64 bits per varint",
                assertThrows(IllegalArgumentException.class,
                        () -> TimeAxis.decodeDeltas(0, 2, ByteBuffer.wrap(wide))).getMessage());
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
    void infiniteValuesCountAsNaN() {
        EpisodeSummary s = Summaries.of(new double[]{1, Double.POSITIVE_INFINITY, 3, Double.NEGATIVE_INFINITY, Double.NaN});
        assertEquals(2, s.getValidCount());
        assertEquals(3, s.getNanCount());
        assertEquals(1.0, s.getMin());
        assertEquals(3.0, s.getMax());
        assertEquals(2.0, s.getMean());
        assertEquals(2.0, Summaries.variance(s));
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
