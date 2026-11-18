package org.opentsx.wayang;

import org.apache.avro.specific.SpecificData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class FederationEvaluationTest {
    private static List<Episode> episodes;
    private static final Map<Integer, FederatedWindparkData> DATA = new HashMap<>();

    @BeforeAll static void loadRepositoryScenario() {
        episodes = KafScaleFixture.scenarioW();
        for (int sites : List.of(1, 3, 10)) DATA.put(sites, FederatedWindparkData.fromEpisodes(episodes, sites));
    }

    @Test void layoutsKeepWholeTurbinesAndPreserveTheThreeSiteDefault() {
        for (int count : List.of(1, 3, 10)) {
            Map<String, String> turbineSites = new TreeMap<>();
            Map<String, String> seriesSites = new TreeMap<>();
            for (Episode episode : episodes) {
                String turbine = episode.getSeries().getTags().get("turbine");
                String site = FederatedWindparkData.site(episode, count);
                String previous = turbineSites.putIfAbsent(turbine, site);
                if (previous != null) assertEquals(previous, site);
                previous = seriesSites.putIfAbsent(episode.getSeries().getSeriesId(), site);
                if (previous != null) assertEquals(previous, site);
                if (count == 3) assertEquals(FederatedWindparkData.site(episode), site);
            }
            assertEquals(10, turbineSites.size());
            assertEquals(100, seriesSites.size());
            assertEquals(new HashSet<>(FederatedWindparkData.siteNames(count)), new HashSet<>(turbineSites.values()));
            if (count == 1) assertEquals(Set.of("s1"), new HashSet<>(turbineSites.values()));
            if (count == 3) {
                assertEquals(3, turbineSites.values().stream().filter("s1"::equals).count());
                assertEquals(3, turbineSites.values().stream().filter("s2"::equals).count());
                assertEquals(4, turbineSites.values().stream().filter("s3"::equals).count());
            }
            if (count == 10) for (int turbine = 1; turbine <= 10; turbine++) {
                assertEquals("s" + turbine, turbineSites.get(String.format(java.util.Locale.ROOT, "T%02d", turbine)));
            }
        }
    }

    @Test void fullScenarioFieldsSketchesAndNumericalErrorsMatchAtEverySiteCount() {
        FederatedWindparkData centralized = DATA.get(1);
        for (int count : List.of(1, 3, 10)) {
            FederatedWindparkData data = DATA.get(count);
            assertEquals(3600, data.episodes().size());
            assertEquals(2_160_000, data.episodes().stream().mapToLong(episode -> Episodes.values(episode).length).sum());
            assertEquals(100, data.rawGroups().size());
            assertEquals(420, data.centralized().size());
            assertEquals(420L * count, data.partials().values().stream().mapToLong(List::size).sum());
            double maximumMean = 0, maximumM2 = 0;
            int comparisons = 0;
            for (var entry : data.partials().entrySet()) {
                FederatedWindparkData.Window window = entry.getKey();
                List<TsxStatsMerge.Partial> partials = entry.getValue();
                assertEquals(count, partials.size());
                SeriesStats central = centralized.partials().get(window).get(0).statistics();
                byte[] baseline = null;
                for (int[] order : FederationEvaluation.orders(count)) {
                    SeriesStats merged = TsxStatsMerge.merge(window.groupId(), window.windowType(), window.startMicros(), window.endMicros(),
                            0.01, Arrays.stream(order).mapToObj(partials::get).toList());
                    Map<String, String> row = FederationEvaluation.comparison(window, data.centralized().get(window),
                            data.gaps().get(window), merged, central, data.uncollapsedCentral().get(window));
                    assertEquals("true", row.get("exact_fields_equal"));
                    assertEquals("true", row.get("central_native_exact_fields_equal"));
                    assertEquals("true", row.get("sketch_equal"));
                    for (String quantile : List.of("q01", "q50", "q99")) assertEquals(0, Double.parseDouble(row.get(quantile + "_absolute_error")));
                    maximumMean = Math.max(maximumMean, Double.parseDouble(row.get("mean_relative_error")));
                    maximumM2 = Math.max(maximumM2, Double.parseDouble(row.get("m2_relative_error")));
                    byte[] encoded = new org.opentsx.model.OpenTsxAvro().encode(merged);
                    if (baseline == null) baseline = encoded;
                    else assertArrayEquals(baseline, encoded);
                    comparisons++;
                }
            }
            assertEquals(420 * FederationEvaluation.orders(count).size(), comparisons);
            System.out.println("Evaluation pure numerical merge: sites=" + count + ", windows=420, comparisons=" + comparisons
                    + ", maximum relative mean error=" + maximumMean + ", maximum relative m2 error=" + maximumM2);
        }
    }

    @Test void unsupportedLayoutsAndIncompleteOrMutatedEvidenceAreRejected() {
        for (int count : new int[]{0, 2, 11}) {
            assertEquals("evaluation requires 1, 3 or 10 sites", assertThrows(IllegalArgumentException.class,
                    () -> FederatedWindparkData.siteNames(count)).getMessage());
            assertThrows(IllegalArgumentException.class, () -> FederationEvaluation.orders(count));
        }
        assertThrows(IllegalArgumentException.class, () -> new FederationBrokers(java.nio.file.Path.of("unused"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FederationBrokers(java.nio.file.Path.of("unused"), List.of("s1", "s1")));
        assertThrows(IllegalArgumentException.class, () -> new FederationBrokers(java.nio.file.Path.of("unused"), List.of("../s1")));
        FederatedWindparkData data = DATA.get(1);
        FederatedWindparkData.Window window = data.centralized().keySet().iterator().next();
        SeriesStats reference = data.centralized().get(window), actual = data.partials().get(window).get(0).statistics();
        byte[] sketch = data.uncollapsedCentral().get(window);
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.comparison(window, reference, data.gaps().get(window), actual, null, sketch));
        SeriesStats changed = SpecificData.get().deepCopy(SeriesStats.getClassSchema(), actual);
        changed.setValidCount(changed.getValidCount() + 1);
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.comparison(window, reference, data.gaps().get(window), changed, actual, sketch));
        SeriesStats badSketch = SpecificData.get().deepCopy(SeriesStats.getClassSchema(), actual);
        badSketch.setQuantileSketch(ByteBuffer.wrap(new byte[]{1}));
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.comparison(window, reference, data.gaps().get(window), badSketch, actual, sketch));
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.comparison(window, reference, data.gaps().get(window) + 1, actual, actual, sketch));
    }

    @Test void toleranceRejectsNonFiniteAndExcessiveErrors() {
        assertEquals(0, FederationEvaluation.relative(0, 0));
        assertEquals(2e-12, FederationEvaluation.relative(0, 2e-12));
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.relative(0, 3.1e-12));
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.relative(1, Double.NaN));
        assertThrows(IllegalStateException.class, () -> FederationEvaluation.relative(1, Double.POSITIVE_INFINITY));
        assertEquals(1, FederationEvaluation.orders(1).size());
        assertEquals(6, FederationEvaluation.orders(3).size());
        assertEquals(2, FederationEvaluation.orders(10).size());
    }
}
