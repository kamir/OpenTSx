package org.opentsx.wayang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxPairEsVectorsTest {

    static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "pair-es.v1.json");

    static JsonNode vectors() throws IOException {
        JsonNode root = new ObjectMapper().readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.pair-es.v1", root.path("schema").asText());
        assertEquals(8, root.path("cases").size());
        return root;
    }

    static double[] doubles(JsonNode array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).asDouble();
        return values;
    }

    static void close(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < 5e-13, () -> "expected " + expected + ", actual " + actual);
    }

    @TestFactory
    List<DynamicTest> sharedVectors() throws IOException {
        JsonNode root = vectors();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                double[] a = doubles(c.path("eventsA"));
                double[] b = doubles(c.path("eventsB"));
                if (c.has("a")) {
                    assertArrayEquals(a, TsxPairEs.detectEvents(doubles(c.path("a")), c.path("sigma").asDouble()));
                    assertArrayEquals(b, TsxPairEs.detectEvents(doubles(c.path("b")), c.path("sigma").asDouble()));
                }
                Double tauMax = c.path("tauMax").isNull() ? null : c.path("tauMax").asDouble();
                TsxPairEs.Sync sync = TsxPairEs.eventSync(a, b, tauMax);
                JsonNode expected = c.path("expect");
                close(expected.path("q_total").asDouble(), sync.qTotal());
                close(expected.path("q_ab").asDouble(), sync.qAb());
                close(expected.path("q_ba").asDouble(), sync.qBa());
                close(expected.path("q_rand").asDouble(), TsxPairEs.surrogateSync(a, b,
                        root.path("shuffles").asInt(), root.path("seed").asLong(), tauMax));
            }));
        }
        return tests;
    }

    @Test
    void mulberry32MatchesEverySharedValueExactly() throws IOException {
        var seeds = vectors().path("mulberry32").fields();
        int count = 0;
        while (seeds.hasNext()) {
            var entry = seeds.next();
            Mulberry32 rand = new Mulberry32(Long.parseLong(entry.getKey()));
            for (JsonNode expected : entry.getValue()) {
                assertEquals(expected.asDouble(), rand.nextDouble());
                count++;
            }
        }
        assertEquals(10, count);
    }

    @Test
    void localWindowsStayOpenAtTheBoundary() {
        assertEquals(new TsxPairEs.Sync(0, 0, 0),
                TsxPairEs.eventSync(new double[]{0, 10}, new double[]{5, 15}, null));
        assertEquals(new TsxPairEs.Sync(0, 0, 0),
                TsxPairEs.eventSync(new double[]{0, 100}, new double[]{10, 110}, 10.0));
        assertEquals(new TsxPairEs.Sync(1, 1, 0),
                TsxPairEs.eventSync(new double[]{0, 100}, new double[]{10, 110}, 11.0));
    }

    @Test
    void eventsAreSortedAndDeduplicatedWithoutChangingTheInputs() {
        double[] a = {20, 0, 10, 10};
        double[] b = {10, 20, 0, 0};
        assertEquals(new TsxPairEs.Sync(1, 0.5, 0.5), TsxPairEs.eventSync(a, b, null));
        assertArrayEquals(new double[]{20, 0, 10, 10}, a);
        assertArrayEquals(new double[]{10, 20, 0, 0}, b);
    }

    @Test
    void emptyEventsGiveZeroAndSingleEventsHaveNoLocalLimit() {
        assertEquals(new TsxPairEs.Sync(0, 0, 0), TsxPairEs.eventSync(new double[0], new double[]{1}, null));
        assertEquals(new TsxPairEs.Sync(1, 1, 0), TsxPairEs.eventSync(new double[]{1}, new double[]{9}, null));
        assertEquals(new TsxPairEs.Sync(1, 0.5, 0.5), TsxPairEs.eventSync(new double[]{1}, new double[]{1}, 0.0));
        assertEquals(0, TsxPairEs.surrogateSync(new double[]{3}, new double[0], 5, 1, null));
    }

    @Test
    void gapsAndBoundaryTicksDoNotBecomeDetectedEvents() {
        assertArrayEquals(new double[]{2}, TsxPairEs.detectEvents(
                new double[]{0, 0, 9, 0, 0, 0, 9, Double.NaN, 0, 0, 0, 0}, 1));
        assertArrayEquals(new double[0], TsxPairEs.detectEvents(new double[0], 2));
        assertArrayEquals(new double[0], TsxPairEs.detectEvents(new double[]{Double.NaN, Double.NaN}, 2));
        assertArrayEquals(new double[0], TsxPairEs.detectEvents(new double[]{9, 0, 9}, 0));
        assertArrayEquals(new double[0], TsxPairEs.detectEvents(new double[]{0, 9, 9, 0}, 0));
    }

    @Test
    void intervalShuffleKeepsTheEndpointsAndGapMultiset() {
        double[] events = {2, 9, 15, 31, 47, 52, 70};
        double[] shuffled = TsxPairEs.isiShuffle(events, new Mulberry32(7));
        assertEquals(events[0], shuffled[0]);
        assertEquals(events[events.length - 1], shuffled[shuffled.length - 1]);
        assertNotEquals(Arrays.toString(events), Arrays.toString(shuffled));
        assertArrayEquals(gaps(events), gaps(shuffled));
        assertArrayEquals(new double[]{2, 9, 15, 31, 47, 52, 70}, events);
        assertArrayEquals(new double[]{4, 9}, TsxPairEs.isiShuffle(new double[]{4, 9}, new Mulberry32(1)));
    }

    static double[] gaps(double[] events) {
        double[] gaps = new double[events.length - 1];
        for (int i = 0; i < gaps.length; i++) gaps[i] = events[i + 1] - events[i];
        Arrays.sort(gaps);
        return gaps;
    }

    @Test
    void rejectsInvalidParametersAndEventTimes() {
        double[] events = {1, 2};
        assertThrows(IllegalArgumentException.class, () -> TsxPairEs.detectEvents(events, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TsxPairEs.eventSync(events, events, -1.0));
        assertThrows(IllegalArgumentException.class, () -> TsxPairEs.eventSync(events, events, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TsxPairEs.eventSync(events, new double[]{Double.NaN}, null));
        assertThrows(IllegalArgumentException.class, () -> TsxPairEs.isiShuffle(new double[]{Double.POSITIVE_INFINITY}, new Mulberry32(1)));
        assertEquals("shuffles must be at least 1", assertThrows(IllegalArgumentException.class,
                () -> TsxPairEs.surrogateSync(events, events, 0, 1, null)).getMessage());
    }
}
