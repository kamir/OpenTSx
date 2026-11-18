package org.opentsx.wayang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class W3StatsMergeTest {

    private static FederatedWindparkData data;

    @BeforeAll
    static void loadCompleteScenario() { data = FederatedWindparkData.load(); }

    private static byte[] sketch(SeriesStats statistics) {
        ByteBuffer encoded = statistics.getQuantileSketch().duplicate();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    @Test
    void completeScenarioIsSplitIntoDisjointTurbineSites() {
        assertEquals(3600, data.episodes().size());
        assertEquals(2_160_000, data.episodes().stream().mapToLong(Episode::getCount).sum());
        assertEquals(Set.of("s1", "s2", "s3"), data.sites().keySet());
        assertEquals(1080, data.sites().get("s1").size());
        assertEquals(1080, data.sites().get("s2").size());
        assertEquals(1440, data.sites().get("s3").size());
        Set<String> seen = new HashSet<>();
        for (var site : data.sites().entrySet()) {
            Set<String> series = new HashSet<>();
            site.getValue().forEach(e -> {
                assertEquals(site.getKey(), FederatedWindparkData.site(e));
                series.add(e.getSeries().getSeriesId());
            });
            for (String id : series) assertTrue(seen.add(id), "raw series belongs to more than one site");
        }
        assertEquals(100, seen.size());
        assertEquals(420, data.partials().size());
        assertEquals(data.centralized().keySet(), data.partials().keySet());
        assertEquals(data.centralized().keySet(), data.gaps().keySet());
        assertEquals(1260, data.partials().values().stream().mapToLong(java.util.List::size).sum());
    }

    @Test
    void everyWindowMatchesCentralizedAndAllSixOrdersAreBitIdentical() {
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        double largestMeanError = 0;
        double largestM2Error = 0;
        String largestMeanWindow = "";
        String largestM2Window = "";
        int comparisons = 0;
        OpenTsxAvro avro = new OpenTsxAvro();
        for (var entry : data.partials().entrySet()) {
            var window = entry.getKey();
            var partials = entry.getValue();
            SeriesStats expected = data.centralized().get(window);
            assertEquals(3, partials.size());
            assertArrayEquals(data.uncollapsedCentral().get(window), sketch(expected), "central sketch did not collapse");
            for (var partial : partials) assertArrayEquals(data.uncollapsedSites().get(partial.site()).get(window),
                    sketch(partial.statistics()), "site sketch did not collapse");
            byte[] baseline = null;
            for (int[] order : orders) {
                SeriesStats actual = TsxStatsMerge.merge(window.groupId(), window.windowType(), window.startMicros(),
                        window.endMicros(), FederatedWindparkData.ALPHA, Arrays.stream(order).mapToObj(partials::get).toList());
                assertEquals(expected.getValidCount(), actual.getValidCount(), window.toString());
                assertEquals(expected.getNanCount(), actual.getNanCount(), window.toString());
                assertEquals(data.gaps().get(window).longValue(), actual.getGapCount(), window.toString());
                assertEquals(expected.getMin(), actual.getMin(), window.toString());
                assertEquals(expected.getMax(), actual.getMax(), window.toString());
                assertEquals(expected.getLastSeen(), actual.getLastSeen(), window.toString());
                double meanError = expected.getMean() == 0 ? Math.abs(actual.getMean())
                        : Math.abs((actual.getMean() - expected.getMean()) / expected.getMean());
                double m2Error = expected.getM2() == 0 ? Math.abs(actual.getM2())
                        : Math.abs((actual.getM2() - expected.getM2()) / expected.getM2());
                if (meanError > largestMeanError) {
                    largestMeanError = meanError;
                    largestMeanWindow = data.metric(window.groupId()) + " " + window;
                }
                if (m2Error > largestM2Error) {
                    largestM2Error = m2Error;
                    largestM2Window = data.metric(window.groupId()) + " " + window;
                }
                assertArrayEquals(sketch(expected), sketch(actual), window.toString());
                for (double q : new double[]{0.01, 0.5, 0.99}) assertEquals(
                        QuantileSketch.fromBytes(expected.getQuantileSketch()).quantile(q),
                        QuantileSketch.fromBytes(actual.getQuantileSketch()).quantile(q), window + " q=" + q);
                byte[] encoded = avro.encode(actual);
                if (baseline == null) baseline = encoded;
                else assertArrayEquals(baseline, encoded, window.toString());
                comparisons++;
            }
        }
        assertEquals(2520, comparisons);
        System.out.println("W3 pure numerical merge: windows=" + data.partials().size() + ", orders=" + comparisons
                + ", maximum relative mean error=" + largestMeanError + " at " + largestMeanWindow
                + ", maximum relative m2 error=" + largestM2Error + " at " + largestM2Window);
        assertTrue(largestMeanError <= 3e-12, "D-T2 relative mean error: " + largestMeanError);
        assertTrue(largestM2Error <= 3e-12, "D-T2 relative m2 error: " + largestM2Error);
    }
}
