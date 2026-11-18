package org.opentsx.stats;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataQualityTest {

    private static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));
    private static final long SEC = 1_000_000L;

    private static DataQuality.Result freshness(List<DataQuality.Result> results) {
        List<DataQuality.Result> f = results.stream().filter(r -> r.check() == DataQuality.Check.FRESHNESS).toList();
        assertEquals(1, f.size(), results::toString);
        return f.get(0);
    }

    @Test
    void freshnessComparesLastSeenWithTheGivenClock() {
        StatsAccumulator a = new StatsAccumulator(0.01);
        for (int i = 0; i < 600; i++) {
            a.add(T0 + i * SEC, 8 + i % 3);
        }
        SeriesStats stats = a.toSeriesStats("s1", "PT10M", T0, T0 + 600 * SEC);
        long lastSeen = T0 + 599 * SEC;
        DataQuality.Expectation expect = DataQuality.Expectation.of(null, null, null).withMaxAge(Duration.ofMinutes(1));

        DataQuality.Result fresh = freshness(DataQuality.evaluate(stats, expect, OptionalLong.of(lastSeen + 60 * SEC)));
        assertEquals(DataQuality.Status.SUCCESS, fresh.status());
        assertEquals(60.0, fresh.value());
        assertEquals("<= PT1M", fresh.threshold());
        assertEquals("last value 60 s ago", fresh.message());

        DataQuality.Result old = freshness(DataQuality.evaluate(stats, expect, OptionalLong.of(lastSeen + 61 * SEC)));
        assertEquals(DataQuality.Status.FAILED, old.status());
        assertEquals(61.0, old.value());
        assertEquals("last value 61 s ago", old.message());

        SeriesStats unseen = SeriesStats.newBuilder(stats).setLastSeen(null).build();
        DataQuality.Result never = freshness(DataQuality.evaluate(unseen, expect, OptionalLong.of(lastSeen)));
        assertEquals(DataQuality.Status.ABORTED, never.status());
        assertTrue(Double.isNaN(never.value()));
        assertEquals("never seen", never.message());

        assertTrue(DataQuality.evaluate(stats, expect).stream().noneMatch(r -> r.check() == DataQuality.Check.FRESHNESS),
                "without a clock FRESHNESS does not run");
        assertTrue(DataQuality.evaluate(stats, DataQuality.Expectation.of(null, null, null), OptionalLong.of(lastSeen + 61 * SEC))
                .stream().noneMatch(r -> r.check() == DataQuality.Check.FRESHNESS), "without a maximum age FRESHNESS does not run");
    }
}
