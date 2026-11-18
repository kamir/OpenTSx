package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsAccumulator;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeOrderTest {

    private static final String GROUP = "0123456789abcdef";
    private static final long END = 600_000_000;
    private static final double ALPHA = 0.01;
    private static final int[][] ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    private static SeriesStats statistics(double... values) {
        StatsAccumulator accumulator = new StatsAccumulator(ALPHA);
        for (int i = 0; i < values.length; i++) accumulator.add(i, values[i]);
        return accumulator.toSeriesStats(GROUP, "PT10M", 0, END);
    }

    private static List<TsxStatsMerge.Partial> partials(double[] values) {
        List<TsxStatsMerge.Partial> result = new ArrayList<>();
        for (int site = 0; site < 3; site++) {
            int from = site * values.length / 3;
            int to = (site + 1) * values.length / 3;
            StatsAccumulator accumulator = new StatsAccumulator(ALPHA);
            for (int i = from; i < to; i++) accumulator.add(i, values[i]);
            result.add(new TsxStatsMerge.Partial("s" + (site + 1), accumulator.toSeriesStats(GROUP, "PT10M", 0, END)));
        }
        return result;
    }

    private static SeriesStats merge(List<TsxStatsMerge.Partial> partials) {
        return TsxStatsMerge.merge(GROUP, "PT10M", 0, END, ALPHA, partials);
    }

    private static byte[] bytes(SeriesStats statistics) { return new OpenTsxAvro().encode(statistics); }

    private static byte[] sketch(SeriesStats statistics) {
        ByteBuffer buffer = statistics.getQuantileSketch().duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    @Test
    void everyOrderOfTheThreeSharedSketchPartialsIsBitIdentical() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Path.of("..", "docs", "datamodel-2026", "testvectors",
                "stats-window.v1.json").toFile()).path("cases");
        JsonNode vector = null;
        for (JsonNode c : cases) if (c.path("name").textValue().equals("canonical sketch values")) vector = c;
        assertTrue(vector != null);
        double[] values = new double[vector.path("samples").size()];
        for (int i = 0; i < values.length; i++) values[i] = vector.path("samples").get(i).path("value").doubleValue();
        List<TsxStatsMerge.Partial> partials = partials(values);
        List<byte[]> before = partials.stream().map(p -> bytes(p.statistics())).toList();
        SeriesStats baseline = merge(partials);
        SeriesStats centralized = statistics(values);
        assertEquals(centralized.getValidCount(), baseline.getValidCount());
        assertEquals(centralized.getNanCount(), baseline.getNanCount());
        assertEquals(centralized.getGapCount(), baseline.getGapCount());
        assertEquals(centralized.getMin(), baseline.getMin());
        assertEquals(centralized.getMax(), baseline.getMax());
        assertEquals(centralized.getLastSeen(), baseline.getLastSeen());
        assertEquals(centralized.getMean(), baseline.getMean(), 1e-12 * Math.abs(centralized.getMean()));
        assertEquals(centralized.getM2(), baseline.getM2(), 1e-12 * Math.abs(centralized.getM2()));
        assertEquals(vector.path("expect").get(0).path("sketchHex").textValue(), HexFormat.of().formatHex(sketch(baseline)));
        assertArrayEquals(sketch(centralized), sketch(baseline));
        for (int[] order : ORDERS) assertArrayEquals(bytes(baseline), bytes(merge(Arrays.stream(order).mapToObj(partials::get).toList())));
        for (int i = 0; i < partials.size(); i++) assertArrayEquals(before.get(i), bytes(partials.get(i).statistics()));
    }

    @Test
    void theEmptyIdentityKeepsTheExistingWireNaNs() {
        SeriesStats empty = merge(List.of());
        assertEquals(0, empty.getValidCount());
        assertEquals(0, empty.getNanCount());
        assertEquals(0, empty.getGapCount());
        assertTrue(Double.isNaN(empty.getMin()));
        assertTrue(Double.isNaN(empty.getMax()));
        assertTrue(Double.isNaN(empty.getMean()));
        assertTrue(Double.isNaN(empty.getM2()));
        assertNull(empty.getLastSeen());
        assertArrayEquals(bytes(statistics()), bytes(empty));
        List<TsxStatsMerge.Partial> input = List.of(new TsxStatsMerge.Partial("s1", empty),
                new TsxStatsMerge.Partial("s2", statistics(1, 2, 3)), new TsxStatsMerge.Partial("s3", statistics()));
        assertArrayEquals(bytes(statistics(1, 2, 3)), bytes(merge(input)));
    }

    @Test
    void nanOnlyPartialsKeepTheirCountsGapsAndLatestObservation() {
        SeriesStats nan = statistics(Double.NaN, Double.POSITIVE_INFINITY);
        nan.setGapCount(2L);
        SeriesStats merged = merge(List.of(new TsxStatsMerge.Partial("s2", nan),
                new TsxStatsMerge.Partial("s1", statistics())));
        assertArrayEquals(bytes(nan), bytes(merged));
    }

    @Test
    void invalidWindowScopesAndSiteIdentitiesAreRefused() {
        for (String window : new String[]{"GLOBAL", "PT0S", "PT-1S", "PT0.000000001S", "not-a-duration"}) {
            assertThrows(IllegalArgumentException.class, () -> TsxStatsMerge.merge(GROUP, window, 0, END, ALPHA, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> TsxStatsMerge.merge(GROUP, "PT10M", 1, END + 1, ALPHA, List.of()));
        assertThrows(IllegalArgumentException.class, () -> TsxStatsMerge.merge(GROUP, "PT10M", 0, END + 1, ALPHA, List.of()));
        assertThrows(IllegalArgumentException.class, () -> TsxStatsMerge.merge("bad", "PT10M", 0, END, ALPHA, List.of()));
        for (double alpha : new double[]{0, 1, Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> TsxStatsMerge.merge(GROUP, "PT10M", 0, END, alpha, List.of()));
        }
        for (String site : new String[]{"", "Wrong", "s|1"}) {
            assertThrows(IllegalArgumentException.class, () -> merge(List.of(new TsxStatsMerge.Partial(site, statistics(1)))));
        }
        assertThrows(IllegalArgumentException.class, () -> merge(List.of(new TsxStatsMerge.Partial("s1", statistics(1)),
                new TsxStatsMerge.Partial("s1", statistics(2)))));
    }

    @Test
    void mismatchedFieldsAndInvalidMomentsAreRefusedWithoutChangingInputs() {
        for (String field : new String[]{"series", "type", "start", "end", "valid", "nan", "gap", "min", "max", "mean", "m2", "last"}) {
            SeriesStats changed = statistics(1, 2);
            switch (field) {
                case "series" -> changed.setSeriesId("fedcba9876543210");
                case "type" -> changed.setWindowType("PT1H");
                case "start" -> changed.setWindowStart(Micros.toInstant(1));
                case "end" -> changed.setWindowEnd(Micros.toInstant(END + 1));
                case "valid" -> changed.setValidCount(-1L);
                case "nan" -> changed.setNanCount(-1L);
                case "gap" -> changed.setGapCount(-1L);
                case "min" -> changed.setMin(Double.NaN);
                case "max" -> changed.setMax(0.0);
                case "mean" -> changed.setMean(Double.POSITIVE_INFINITY);
                case "m2" -> changed.setM2(-1.0);
                case "last" -> changed.setLastSeen(Micros.toInstant(END));
                default -> throw new AssertionError(field);
            }
            byte[] before = bytes(changed);
            assertThrows(IllegalArgumentException.class, () -> merge(List.of(new TsxStatsMerge.Partial("s1", changed))), field);
            assertArrayEquals(before, bytes(changed));
        }
    }

    @Test
    void incompatibleMissingAndMalformedSketchesAreRefused() {
        List<ByteBuffer> sketches = new ArrayList<>();
        sketches.add(null);
        QuantileSketch otherAlpha = new QuantileSketch(0.02);
        otherAlpha.add(1);
        sketches.add(ByteBuffer.wrap(otherAlpha.toBytes()));
        sketches.add(ByteBuffer.wrap(new QuantileSketch(ALPHA).toBytes()));
        sketches.add(ByteBuffer.wrap(new byte[]{1}));
        byte[] trailing = Arrays.copyOf(sketch(statistics(1)), sketch(statistics(1)).length + 1);
        sketches.add(ByteBuffer.wrap(trailing));
        for (ByteBuffer sketch : sketches) {
            SeriesStats changed = statistics(1);
            changed.setQuantileSketch(sketch);
            assertThrows(IllegalArgumentException.class, () -> merge(List.of(new TsxStatsMerge.Partial("s1", changed))));
        }
    }

    @Test
    void countOverflowIsRefusedBeforeMerging() {
        for (String field : new String[]{"valid", "nan", "gap"}) {
            SeriesStats large = statistics(1);
            SeriesStats one = statistics(1);
            if (field.equals("valid")) {
                large.setValidCount(Long.MAX_VALUE);
                QuantileSketch sketch = new QuantileSketch(ALPHA);
                sketch.add(1, Long.MAX_VALUE);
                large.setQuantileSketch(ByteBuffer.wrap(sketch.toBytes()));
            } else if (field.equals("nan")) {
                large.setNanCount(Long.MAX_VALUE);
                one.setNanCount(1L);
            } else {
                large.setGapCount(Long.MAX_VALUE);
                one.setGapCount(1L);
            }
            assertThrows(IllegalArgumentException.class, () -> merge(List.of(new TsxStatsMerge.Partial("s1", large),
                    new TsxStatsMerge.Partial("s2", one))), field);
        }
    }

    @Test
    void zeroBucketAndCollapsedTailAccuracyAreExplicitlyQualified() {
        SeriesStats zero = merge(partials(new double[]{-5e-10, 0, 1e-12}));
        assertEquals(0, QuantileSketch.fromBytes(zero.getQuantileSketch()).quantile(1));
        double[] values = new double[5001];
        for (int i = 0; i < values.length; i++) values[i] = Math.exp(i * 0.03);
        List<TsxStatsMerge.Partial> partials = partials(values);
        SeriesStats baseline = merge(partials);
        QuantileSketch sketch = QuantileSketch.fromBytes(baseline.getQuantileSketch());
        assertEquals(values.length, sketch.count());
        assertTrue(Math.abs(sketch.quantile(1) - values[values.length - 1]) <= ALPHA * values[values.length - 1]);
        assertTrue(Math.abs(sketch.quantile(0) - values[0]) > ALPHA * values[0]);
        for (int[] order : ORDERS) assertArrayEquals(bytes(baseline), bytes(merge(Arrays.stream(order).mapToObj(partials::get).toList())));
        QuantileSketch tooManyBins = new QuantileSketch(ALPHA, 8192);
        for (double value : values) tooManyBins.add(value);
        SeriesStats invalid = statistics(1);
        invalid.setValidCount((long) values.length);
        invalid.setQuantileSketch(ByteBuffer.wrap(tooManyBins.toBytes()));
        assertThrows(IllegalArgumentException.class, () -> merge(List.of(new TsxStatsMerge.Partial("s1", invalid))));
    }
}
