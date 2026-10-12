package org.opentsx.retrieval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.retrieval.pattern.Distance;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesSelector;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeServiceTest {

    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    static InMemorySeriesStore store;
    static List<Episode> all;

    @BeforeAll
    static void load() {
        Scenario s = new Scenario("svc", 9, START, Duration.ofHours(2), Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", "svc", 54, 13, "GENERIC-3MW", 1, 3, 5, 7, 0.05),
                new Scenario.WindSpec(2.0, 11.0, 0.12, 240, 15, 0.1, 10), List.of(), List.of());
        all = new ArrayList<>();
        new WindparkSimulator(s, ChannelCatalog.standard()).run(all::add);
        store = new InMemorySeriesStore();
        store.write(all);
    }

    @Test
    void queryLookupAndResample() {
        EpisodeService svc = new EpisodeService(store);
        List<Episode> eps = svc.episodes().metric("wind.met.wind_speed").tag("turbine", "T02")
                .between(START.plusSeconds(1800), START.plusSeconds(3600)).fetch();
        assertEquals(3, eps.size());
        Episode first = eps.get(0);
        assertEquals(first, svc.episode(first.getEpisodeId()).orElseThrow());

        List<Episode> minute = svc.episodes().metric("wind.met.wind_speed").tag("turbine", "T02")
                .between(START.plusSeconds(1800), START.plusSeconds(2400)).resample(Duration.ofMinutes(1), Resampling.Agg.MEAN).fetch();
        assertEquals(1, minute.size());
        double[] m = Episodes.values(minute.get(0));
        assertEquals(10, m.length);
        double[] raw = Episodes.values(first);
        assertEquals(Arrays.stream(raw, 0, 60).average().orElseThrow(), m[0], 1e-9);
        assertEquals(first.getEpisodeId(), minute.get(0).getProvenance().getParentEpisodeId());
        assertEquals("PT1M/MEAN", minute.get(0).getLabels().get("resample"));

        List<Episode> limited = svc.episodes().in(SeriesSelector.metric("wind.turbine.power_active")).limit(4).fetch();
        assertEquals(4, limited.size());
    }

    @Test
    void patternFromAStoredEpisodeFindsItselfFirst() {
        EpisodeService svc = new EpisodeService(store);
        Episode e = svc.episodes().metric("wind.met.wind_speed").tag("turbine", "T01").limit(5).fetch().get(4);
        EpisodeService.SubsequenceQuery q = svc.similarTo(e.getEpisodeId(), 100, 120).metric("wind.met.wind_speed").topK(5);
        List<PatternMatch> hits = q.fetch();
        assertEquals(5, hits.size());
        assertEquals(e.getEpisodeId(), hits.get(0).getEpisodeId());
        assertEquals(100, hits.get(0).getOffset());
        assertEquals(0.0, hits.get(0).getDistance(), 1e-4, "FFT rounding: sqrt amplifies 1 - corr ~ 1e-14");
        assertTrue(q.stats().windows() > 20_000);

        List<PatternMatch> dtw = svc.similarTo(e.getEpisodeId(), 100, 120).metric("wind.met.wind_speed")
                .distance(Distance.dtw(0.1)).topK(3).fetch();
        assertEquals(0.0, dtw.get(0).getDistance(), 1e-6);
    }

    @Test
    void similarEpisodesAndSegmentsWrittenBack() {
        EpisodeService svc = new EpisodeService(store);
        Episode q = svc.episodes().metric("wind.turbine.power_active").limit(1).fetch().get(0);
        List<PatternMatch> like = svc.similarEpisodes(q.getEpisodeId()).topK(3).fetch();
        assertEquals(3, like.size());
        assertTrue(like.stream().noneMatch(m -> m.getEpisodeId().equals(q.getEpisodeId())));

        InMemorySeriesStore derived = new InMemorySeriesStore();
        List<Episode> gusts = svc.segments(Segmenter.extremum(Segmenter.Feature.MAX, 2.0, Duration.ofMinutes(4),
                        Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(60)))
                .metric("wind.met.wind_speed").writeTo(derived).fetch();
        assertTrue(gusts.size() >= 3, "gusts: " + gusts.size());
        assertEquals(gusts.size(), derived.read(org.opentsx.store.EpisodeQuery.of(SeriesSelector.all(),
                Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)).size());
        Episode g = gusts.get(0);
        assertEquals(SegmentationStrategy.FEATURE_EXTREMUM, g.getSegmentation().getStrategy());
        long anchor = Micros.of(g.getSegmentation().getAnchorTs());
        double peak = Episodes.values(g)[(int) ((anchor - Micros.of(g.getTStart())) / 1_000_000L)];
        assertEquals(Arrays.stream(Episodes.values(g)).max().orElseThrow(), peak, 1e-12, "anchor is the maximum");
        // writing the same segments again is a no-op (deterministic ids)
        assertEquals(0, derived.write(svc.segments(Segmenter.extremum(Segmenter.Feature.MAX, 2.0, Duration.ofMinutes(4),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(60))).metric("wind.met.wind_speed").fetch()).written());
    }
}
