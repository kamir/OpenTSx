package org.opentsx.stats;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Varints;
import org.opentsx.model.v2.SeriesStats;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantileSketchTest {

    private static double exact(double[] sorted, double q) {
        return sorted[(int) Math.floor(q * (sorted.length - 1))];
    }

    @Test
    void quantilesHaveBoundedRelativeError() {
        Random r = new Random(1);
        double[] values = new double[100_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = switch (i % 3) {
                case 0 -> Math.exp(r.nextGaussian() * 2);        // log-normal, wide dynamic range
                case 1 -> -50 + 100 * r.nextDouble();            // symmetric around zero
                default -> 8 + 2 * r.nextGaussian();             // wind-speed like
            };
        }
        QuantileSketch s = new QuantileSketch(0.01);
        for (double v : values) {
            s.add(v);
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        for (double q : new double[]{0, 0.001, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 0.999, 1}) {
            double want = exact(sorted, q);
            double got = s.quantile(q);
            assertTrue(Math.abs(got - want) <= 0.01 * Math.abs(want) + 1e-9, q + ": " + got + " vs " + want);
        }
        assertEquals(values.length, s.count());
    }

    @Test
    void mergeEqualsSingleSketchAndBytesAreCanonical() {
        Random r = new Random(2);
        QuantileSketch all = new QuantileSketch();
        QuantileSketch a = new QuantileSketch();
        QuantileSketch b = new QuantileSketch();
        for (int i = 0; i < 10_000; i++) {
            double v = r.nextGaussian() * 10;
            all.add(v);
            (i % 2 == 0 ? a : b).add(v);
        }
        a.merge(b);
        assertEquals(all, a);
        assertArrayEquals(all.toBytes(), a.toBytes());
        QuantileSketch back = QuantileSketch.fromBytes(a.toBytes());
        assertEquals(a, back);
        assertEquals(a.count(), back.count());
        assertEquals(a.quantile(0.5), back.quantile(0.5));
    }

    @Test
    void fromBytesRefusesTrailingAndTruncatedBytes() {
        QuantileSketch s = new QuantileSketch(0.01);
        for (double v : new double[]{1.0, 2.5, -3.75, 0.0}) {
            s.add(v);
        }
        byte[] bytes = s.toBytes();
        IllegalArgumentException trailing = assertThrows(IllegalArgumentException.class,
                () -> QuantileSketch.fromBytes(Arrays.copyOf(bytes, bytes.length + 2)));
        assertEquals("sketch bytes: 2 trailing bytes; expected exactly " + bytes.length, trailing.getMessage());
        IllegalArgumentException cut = assertThrows(IllegalArgumentException.class,
                () -> QuantileSketch.fromBytes(Arrays.copyOf(bytes, bytes.length - 1)));
        assertEquals("sketch bytes: input ends inside a varint; expected complete varints", cut.getMessage());
        IllegalArgumentException header = assertThrows(IllegalArgumentException.class,
                () -> QuantileSketch.fromBytes(Arrays.copyOf(bytes, 5)));
        assertEquals("sketch bytes: truncated at 1 of 5", header.getMessage());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, () -> QuantileSketch.fromBytes(new byte[0]));
        assertEquals("sketch bytes: truncated at 0 of 0", empty.getMessage());
        assertEquals(s, QuantileSketch.fromBytes(bytes));
    }

    private static byte[] sketchBytes(int... body) {
        byte[] header = Arrays.copyOf(new QuantileSketch(0.01).toBytes(), 9);
        byte[] out = Arrays.copyOf(header, header.length + body.length);
        for (int i = 0; i < body.length; i++) {
            out[header.length + i] = (byte) body[i];
        }
        return out;
    }

    private static String refusal(byte[] bytes) {
        return assertThrows(IllegalArgumentException.class, () -> QuantileSketch.fromBytes(bytes)).getMessage();
    }

    @Test
    void fromBytesRefusesTheVarintsTheModelRefuses() {
        assertEquals("sketch bytes: input ends inside a varint; expected complete varints", refusal(sketchBytes(0x80)));
        assertEquals("sketch bytes: varint exceeds 9223372036854775807; expected a whole number from 0 to"
                        + " 9223372036854775807",
                refusal(sketchBytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0, 0)));
        assertEquals("sketch bytes: varint exceeds 64 bits; expected at most 64 bits per varint",
                refusal(sketchBytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x02, 0, 0)));
        assertEquals("sketch bytes: varint longer than 10 bytes; expected at most 10 bytes per varint",
                refusal(sketchBytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x81, 0x00, 0, 0)));
    }

    private static byte[] oneBinAt(long... deltas) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Arrays.copyOf(new QuantileSketch(0.01).toBytes(), 9));
        Varints.writeUnsigned(out, 0);
        Varints.writeUnsigned(out, deltas.length);
        for (long d : deltas) {
            Varints.writeZigZag(out, d);
            Varints.writeUnsigned(out, 1);
        }
        Varints.writeUnsigned(out, 0);
        return out.toByteArray();
    }

    @Test
    void fromBytesRefusesABinIndexOutsideTheIntRange() {
        assertEquals("sketch bytes: bin index 1099511627776 out of range; expected an int",
                refusal(oneBinAt(1L << 40)));
        assertEquals("sketch bytes: bin index 2147483648 out of range; expected an int",
                refusal(oneBinAt(Integer.MAX_VALUE, 1)));
        assertEquals("sketch bytes: bin index -2147483649 out of range; expected an int",
                refusal(oneBinAt(Integer.MIN_VALUE, -1)));
        assertEquals("sketch bytes: bin index 9223372036854775808 out of range; expected an int",
                refusal(oneBinAt(1, Long.MAX_VALUE)));
        byte[] extremes = oneBinAt(Integer.MIN_VALUE, (long) Integer.MAX_VALUE - Integer.MIN_VALUE);
        assertEquals(2, QuantileSketch.fromBytes(extremes).count());
    }

    @Test
    void theReadersOfStatisticsRefuseACorruptSketchAsBadInput() throws IOException {
        byte[] corrupt = oneBinAt(1L << 40);
        String text = "sketch bytes: bin index 1099511627776 out of range; expected an int";
        StatsAccumulator a = new StatsAccumulator(0.01);
        a.add(0, 1.0);
        SeriesStats stats = SeriesStats.newBuilder(a.toSeriesStats("s1", "PT10M", 0, 600_000_000L))
                .setQuantileSketch(ByteBuffer.wrap(corrupt)).build();
        assertEquals(text, assertThrows(IllegalArgumentException.class, () -> StatsAccumulator.of(stats)).getMessage());
        assertEquals(text, assertThrows(IllegalArgumentException.class,
                () -> DataQuality.evaluate(stats, DataQuality.Expectation.of(0.0, 10.0, null))).getMessage());

        ByteArrayOutputStream checkpoint = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(checkpoint);
        for (int i = 0; i < 9; i++) {
            out.writeLong(0);
        }
        out.writeInt(corrupt.length);
        out.write(corrupt);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(checkpoint.toByteArray()));
        assertEquals(text,
                assertThrows(IllegalArgumentException.class, () -> StatsAccumulator.readFrom(in)).getMessage());
    }

    @Test
    void zerosNaNsAndEmpty() {
        QuantileSketch s = new QuantileSketch();
        assertTrue(Double.isNaN(s.quantile(0.5)));
        s.add(Double.NaN);
        s.add(Double.POSITIVE_INFINITY);
        assertEquals(0, s.count());
        s.add(0.0);
        s.add(0.0);
        s.add(5.0);
        assertEquals(0.0, s.quantile(0.5));
        assertEquals(5.0, s.quantile(1.0), 0.05);
        assertThrows(IllegalArgumentException.class, () -> s.merge(new QuantileSketch(0.02)));
    }

    @Test
    void shareOutsideABand() {
        QuantileSketch s = new QuantileSketch();
        for (int i = 0; i < 1000; i++) {
            s.add(i < 990 ? 10 + (i % 20) : 100);    // 1 % far above 60
        }
        s.add(-5);                                     // one below 0
        assertEquals(1.0 / 1001, s.fractionBelow(0), 1e-12);
        assertEquals(10.0 / 1001, s.fractionAbove(60), 1e-12);
        assertEquals(0.0, s.fractionAbove(150), 1e-12);
    }

    @Test
    void collapsingBoundsMemory() {
        QuantileSketch s = new QuantileSketch(0.01, 64);
        for (int i = -300; i <= 300; i++) {
            s.add(Math.pow(10, i / 10.0));
        }
        assertTrue(s.toBytes().length < 64 * 2 * 4 + 32);
        assertEquals(601, s.count());
        double top = s.quantile(1.0);
        assertTrue(Math.abs(top - 1e30) <= 0.01 * 1e30);    // large values keep their accuracy
    }

    /**
     * Fixed test vector: the Python implementation (opentsx.stats.sketch) must produce the same bytes.
     * If this changes, the binary format changed - bump the format version instead.
     */
    @Test
    void testVector() {
        QuantileSketch s = new QuantileSketch(0.01);
        for (double v : new double[]{0.0, 1.0, 2.5, 2.5, -3.75, 8.2, 12.0, 1e-12, 1000.0, -0.5}) {
            s.add(v);
        }
        assertEquals("017b14ae47e17a843f020500015c0278012601ba0301024301ca0101",
                HexFormat.of().formatHex(s.toBytes()));
    }
}
