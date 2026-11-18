package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.federation.AggregateRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class FederatedStatsIntegrationTest {
    @TempDir(factory = FederationLogDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test void everyActualW3WindowMatchesCentralizedAndAllSixMergeOrders() throws Exception {
        try (var fixture = new FederationFixture(directory)) {
            var result = fixture.routeS();
            assertEquals(3600, fixture.data().episodes().size());
            assertEquals(2_160_000, fixture.data().episodes().stream().mapToLong(org.opentsx.model.v2.Episode::getCount).sum());
            assertEquals(100, fixture.data().rawGroups().size());
            assertEquals(420, result.windows().size());
            assertEquals(3, result.reports().size());
            for (var report : result.reports()) {
                assertEquals(10, report.path("checks").size());
                for (var check : report.path("checks")) assertEquals("pass", check.path("result").asText(), check.path("id").asText());
            }
            Map<FederatedWindparkData.Window, List<TsxStatsMerge.Partial>> partials = new TreeMap<>();
            for (var record : result.partials()) {
                SeriesStats statistics = record.statistics();
                var window = new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()));
                partials.computeIfAbsent(window, ignored -> new ArrayList<>()).add(new TsxStatsMerge.Partial(record.header("tsx-site"), statistics));
            }
            int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            Map<FederatedWindparkData.Window, byte[]> published = new TreeMap<>();
            for (var record : result.merged()) {
                SeriesStats statistics = record.statistics();
                assertNull(published.put(new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd())), record.value()));
            }
            int compared = 0;
            var avro = new OpenTsxAvro();
            for (var entry : partials.entrySet()) {
                var window = entry.getKey();
                assertEquals(3, entry.getValue().size());
                byte[] baseline = null;
                for (int[] order : orders) {
                    byte[] merged = avro.encode(TsxStatsMerge.merge(window.groupId(), window.windowType(), window.startMicros(),
                            window.endMicros(), 0.01, Arrays.stream(order).mapToObj(entry.getValue()::get).toList()));
                    assertArrayEquals(published.get(window), merged, window.toString());
                    if (baseline == null) baseline = merged;
                    else assertArrayEquals(baseline, merged, window.toString());
                    compared++;
                }
            }
            assertEquals(2520, compared);
            List<AggregateRecord> sorted = result.merged().stream().sorted(Comparator.comparing(AggregateRecord::key)).toList();
            List<SeriesStats> driver = result.driverOutput().stream().sorted(Comparator.comparing(s ->
                    "w3|" + s.getSeriesId() + "|" + s.getWindowType() + "|" + Micros.of(s.getWindowStart()))).toList();
            for (int index = 0; index < sorted.size(); index++) assertArrayEquals(sorted.get(index).value(), avro.encode(driver.get(index)));
        }
    }
}
