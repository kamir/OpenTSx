package org.opentsx.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract every {@link SeriesStore} must pass. Adapters extend this class and implement {@link #newStore()}.
 * Expectations depend on the store's {@link Capability capabilities}: episode stores must round-trip exactly,
 * point stores must return the same points.
 */
public abstract class SeriesStoreContract {

    /** 2026-09-21T00:00:00Z, millisecond aligned so point stores with ms precision can hold every timestamp. */
    protected static final long T0 = 1_790_000_000_000_000L;
    protected static final long SEC = 1_000_000L;

    protected static final SeriesKey WIND_T01 = SeriesKeys.of("wind.turbine.wind_speed", Map.of("park", "ber-01", "turbine", "T01"), "m/s");
    protected static final SeriesKey POWER_T01 = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "ber-01", "turbine", "T01"), "kW");
    protected static final SeriesKey WIND_T02 = SeriesKeys.of("wind.turbine.wind_speed", Map.of("park", "ber-01", "turbine", "T02"), "m/s");

    protected SeriesStore store;

    /** A fresh, empty store. */
    protected abstract SeriesStore newStore();

    @BeforeEach
    void open() {
        store = newStore();
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    /** 3 series x 3 regular 1-minute episodes at 1 Hz, plus an irregular episode and one with NaN. */
    public static List<Episode> fixture() {
        List<Episode> out = new ArrayList<>();
        int n = 0;
        for (SeriesKey key : List.of(WIND_T01, POWER_T01, WIND_T02)) {
            for (int e = 0; e < 3; e++) {
                double[] v = new double[60];
                for (int i = 0; i < v.length; i++) {
                    v[i] = Math.round((7.5 + Math.sin((e * 60 + i) / 9.0) + n) * 100) / 100.0;
                }
                out.add(Episodes.regular(key, T0 + e * 60 * SEC, SEC, v).source("contract").label("n", "" + n).build());
            }
            n++;
        }
        out.add(Episodes.irregular(WIND_T01, new long[]{T0 + 200 * SEC, T0 + 200 * SEC + 250_000, T0 + 203 * SEC},
                new double[]{9.1, 9.4, 8.8}).source("contract").build());
        out.add(Episodes.regular(POWER_T01, T0 + 300 * SEC, SEC, new double[]{1500.0, Double.NaN, 1510.5}).source("contract").build());
        return out;
    }

    @Test
    void roundTripOfAllEpisodes() {
        List<Episode> fixture = fixture();
        WriteResult r = store.write(fixture);
        assertEquals(fixture.size(), r.written());

        List<Episode> read = store.read(EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC));
        if (store.capabilities().has(Capability.EPISODES)) {
            assertEquals(fixture.stream().sorted(EpisodeOps.READ_ORDER).toList(), read);
        } else {
            assertEquals(expectedPoints(fixture, T0, T0 + 3600 * SEC), points(read));
        }
    }

    @Test
    void episodeLookupById() {
        List<Episode> fixture = fixture();
        store.write(fixture);
        Episode wanted = fixture.get(4);
        if (store.capabilities().has(Capability.EPISODES)) {
            assertEquals(java.util.Optional.of(wanted), store.episode(wanted.getEpisodeId()));
        }
        assertEquals(java.util.Optional.empty(), store.episode("01JUNKNOWNUNKNOWNUNKNOWN00"));
    }

    @Test
    void writesAreIdempotentPerEpisodeId() {
        List<Episode> fixture = fixture();
        store.write(fixture);
        WriteResult again = store.write(fixture);
        if (store.capabilities().has(Capability.EPISODES)) {
            assertEquals(0, again.written());
            assertEquals(fixture.size(), again.skipped());
        } // point stores overwrite identical points; the read below must still be unchanged
        assertEquals(expectedPoints(fixture, T0, T0 + 3600 * SEC),
                points(store.read(EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC))));
    }

    @Test
    void selectorsFilterByMetricTagsAndIds() {
        List<Episode> fixture = fixture();
        store.write(fixture);
        long from = T0;
        long to = T0 + 3600 * SEC;

        assertEquals(Set(WIND_T01, WIND_T02), seriesOf(store.read(EpisodeQuery.of(SeriesSelector.metric("wind.turbine.wind_speed"), from, to))));
        assertEquals(Set(WIND_T02), seriesOf(store.read(EpisodeQuery.of(SeriesSelector.metric("wind.turbine.wind_speed").tag("turbine", "T02"), from, to))));
        assertEquals(Set(WIND_T01, POWER_T01), seriesOf(store.read(EpisodeQuery.of(SeriesSelector.all().tag("turbine", "T01"), from, to))));
        assertEquals(Set(POWER_T01), seriesOf(store.read(EpisodeQuery.of(SeriesSelector.ids(POWER_T01.getSeriesId()), from, to))));
        assertTrue(store.read(EpisodeQuery.of(SeriesSelector.metric("nope"), from, to)).isEmpty());
    }

    @Test
    void timeWindowReturnsOverlappingEpisodesAndClipCutsThem() {
        List<Episode> fixture = fixture();
        store.write(fixture);
        long from = T0 + 70 * SEC;
        long to = T0 + 130 * SEC;
        SeriesSelector wind1 = SeriesSelector.ids(WIND_T01.getSeriesId());

        List<Episode> overlapping = store.read(EpisodeQuery.of(wind1, from, to));
        assertTrue(overlapping.stream().allMatch(e -> EpisodeOps.overlaps(e, from, to)));
        if (store.capabilities().has(Capability.EPISODES)) {
            assertEquals(2, overlapping.size()); // episodes [60 s, 120 s) and [120 s, 180 s)
        }

        List<Episode> clipped = store.read(EpisodeQuery.of(wind1, from, to).clipped());
        Map<String, TreeMap<Long, Double>> expected = expectedPoints(
                fixture.stream().filter(e -> e.getSeries().equals(WIND_T01)).toList(), from, to);
        assertEquals(expected, points(clipped));
        assertEquals(60, expected.get(WIND_T01.getSeriesId()).size());
    }

    @Test
    void listSeriesHonoursTheSelector() {
        store.write(fixture());
        assertEquals(Set(WIND_T01, POWER_T01, WIND_T02), Set(store.listSeries(SeriesSelector.all())));
        assertEquals(Set(WIND_T02), Set(store.listSeries(SeriesSelector.all().tag("turbine", "T02"))));
    }

    @Test
    void limitBoundsTheResult() {
        store.write(fixture());
        assertTrue(store.read(EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC).limit(2)).size() <= 2);
    }

    // ------------------------------------------------------------------ helpers

    /** Points of the episodes inside [from, to), as the store can hold them (NaN dropped, ms precision). */
    protected Map<String, TreeMap<Long, Double>> expectedPoints(List<Episode> episodes, long from, long to) {
        Map<String, TreeMap<Long, Double>> out = points(episodes);
        boolean nan = store.capabilities().has(Capability.NAN_VALUES);
        out.values().forEach(m -> {
            m.keySet().removeIf(t -> t < from || t >= to);
            if (!nan) {
                m.values().removeIf(v -> Double.isNaN(v));
            }
        });
        out.values().removeIf(TreeMap::isEmpty);
        return out;
    }

    protected static Map<String, TreeMap<Long, Double>> points(List<Episode> episodes) {
        Map<String, TreeMap<Long, Double>> out = new TreeMap<>();
        for (Episode e : episodes) {
            long[] ts = Episodes.timestampsMicros(e);
            double[] v = Episodes.values(e);
            TreeMap<Long, Double> m = out.computeIfAbsent(e.getSeries().getSeriesId(), k -> new TreeMap<>());
            for (int i = 0; i < ts.length; i++) {
                m.put(ts[i], v[i]);
            }
        }
        return out;
    }

    private static java.util.Set<String> seriesOf(List<Episode> episodes) {
        return episodes.stream().map(e -> e.getSeries().getSeriesId()).collect(Collectors.toSet());
    }

    private static java.util.Set<String> Set(SeriesKey... keys) {
        return java.util.Arrays.stream(keys).map(SeriesKey::getSeriesId).collect(Collectors.toSet());
    }

    private static java.util.Set<String> Set(List<SeriesKey> keys) {
        return keys.stream().map(SeriesKey::getSeriesId).collect(Collectors.toSet());
    }
}
