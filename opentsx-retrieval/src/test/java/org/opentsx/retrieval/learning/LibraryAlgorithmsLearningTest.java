package org.opentsx.retrieval.learning;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.pattern.Distance;
import org.opentsx.retrieval.pattern.Dtw;
import org.opentsx.retrieval.pattern.Mass;
import org.opentsx.retrieval.pattern.Paa;
import org.opentsx.retrieval.pattern.PatternSearch;
import org.opentsx.retrieval.pattern.Run;
import org.opentsx.retrieval.pattern.Sax;
import org.opentsx.retrieval.pattern.ZNorm;
import org.opentsx.retrieval.segment.Segmentations;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.retrieval.segment.StreamingSegmenter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryAlgorithmsLearningTest {

    private static final long SECOND = 1_000_000L;
    private static final SeriesKey KEY = SeriesKeys.of("learning.value", Map.of("sensor", "one"), "1");

    @Test
    void normalizationUsesPopulationScaleAndNeutralizesGaps() {
        assertArrayEquals(new double[]{-Math.sqrt(1.5), 0, Math.sqrt(1.5), 0},
                ZNorm.of(new double[]{1, 2, 3, Double.NaN}), 1e-12);
        assertArrayEquals(new double[]{0, 0}, ZNorm.of(new double[]{7, 7}));
    }

    @Test
    void paaWeightsFractionalBoundaries() {
        assertArrayEquals(new double[]{1.6, 6.4}, Paa.of(new double[]{0, 2, 4, 6, 8}, 2), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> Paa.of(new double[0], 2));
    }

    @Test
    void saxWordsRequireNormalizedInputAndCompatibleAlphabets() {
        String word = Sax.word(ZNorm.of(new double[]{1, 2, 3, 4}), 2, 4);
        assertEquals("2x4:ad", word);
        assertEquals(word, Sax.word(ZNorm.of(new double[]{12, 14, 16, 18}), 2, 4));
        assertEquals(0, Sax.mindist(word, word, 4));
        assertThrows(IllegalArgumentException.class, () -> Sax.breakpoints(1));
    }

    @Test
    void massFindsTheShapeAndRejectsWindowsContainingGaps() {
        double[] profile = Mass.distanceProfile(new double[]{1, 2, 1, 0},
                new double[]{1, 2, 1, 0, Double.NaN, 1, 2, 1, 0});
        assertEquals(6, profile.length);
        assertEquals(0, profile[0], 1e-6);
        assertEquals(0, profile[5], 1e-6);
        for (int i = 1; i < 5; i++) {
            assertEquals(Double.POSITIVE_INFINITY, profile[i]);
        }
    }

    @Test
    void dtwBandControlsWhetherTimeStretchingIsAllowed() {
        double[] first = {0, 1, 1, 0};
        double[] second = {0, 0, 1, 0};
        assertEquals(1, Dtw.distance(first, second, 0, Double.POSITIVE_INFINITY));
        assertEquals(0, Dtw.distance(first, second, 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Distance.dtw(1.1));
    }

    @Test
    void patternSearchMapsMatchesAcrossEpisodeBoundaries() {
        Episode first = episode("first", 0, new double[]{9, 1, 2});
        Episode second = episode("second", 3, new double[]{1, 0, 9});
        PatternSearch search = new PatternSearch(new double[]{1, 2, 1, 0},
                Distance.zNormalizedEuclidean(), 1);
        var matches = search.search(Run.of(List.of(first, second)));
        assertEquals(1, matches.size());
        assertEquals("first", matches.get(0).getEpisodeId());
        assertEquals(1, matches.get(0).getOffset());
        assertEquals(4, matches.get(0).getLength());
        assertEquals(0, matches.get(0).getDistance(), 1e-6);
    }

    @Test
    void fixedDurationUsesTheEpochGridAndFlushesTheTail() {
        var segments = Segmentations.segment(List.of(episode("duration", 0,
                new double[]{0, 1, 2, 3, 4, 5, 6})), Segmenter.fixedDuration(Duration.ofSeconds(4)));
        assertEquals(2, segments.size());
        assertArrayEquals(new double[]{0, 1, 2, 3}, Episodes.values(segments.get(0)));
        assertArrayEquals(new double[]{4, 5, 6}, Episodes.values(segments.get(1)));
        assertEquals(4 * SECOND, Micros.of(segments.get(1).getTStart()));
    }

    @Test
    void fixedCountOverlapsCompleteWindowsAndDropsTheTail() {
        var segments = Segmentations.segment(List.of(episode("count", 0,
                new double[]{0, 1, 2, 3, 4, 5, 6})), Segmenter.fixedCount(4, 2));
        assertEquals(2, segments.size());
        assertArrayEquals(new double[]{0, 1, 2, 3}, Episodes.values(segments.get(0)));
        assertArrayEquals(new double[]{2, 3, 4, 5}, Episodes.values(segments.get(1)));
        assertThrows(IllegalArgumentException.class, () -> Segmenter.fixedCount(4, 4));
    }

    @Test
    void extremaRetainThePeakAndItsInclusiveNeighbourhood() {
        var segments = Segmentations.segment(List.of(episode("peak", 0,
                new double[]{0, 0, 1, 4, 1, 0, 0, 0})), Segmenter.extremum(Segmenter.Feature.MAX,
                2, Duration.ofSeconds(4), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertEquals(1, segments.size());
        assertArrayEquals(new double[]{1, 4, 1}, Episodes.values(segments.get(0)));
        assertEquals(3 * SECOND, Micros.of(segments.get(0).getSegmentation().getAnchorTs()));
    }

    @Test
    void thresholdStateCrossesEpisodesWithoutChangingTheResult() {
        Segmenter strategy = Segmenter.threshold(Segmenter.Direction.ABOVE, 4, 3,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO);
        Episode first = episode("threshold-a", 0, new double[]{0, 0, 5});
        Episode second = episode("threshold-b", 3, new double[]{5, 2, 0, 0, 0});
        StreamingSegmenter stream = new StreamingSegmenter(strategy);
        List<Episode> incremental = new ArrayList<>(stream.onEpisode(first));
        incremental.addAll(stream.onEpisode(second));
        incremental.addAll(stream.flush());
        assertEquals(Segmentations.segment(List.of(first, second), strategy), incremental);
        assertEquals(1, incremental.size());
        assertArrayEquals(new double[]{0, 5, 5, 2}, Episodes.values(incremental.get(0)));
        assertEquals(2 * SECOND, Micros.of(incremental.get(0).getSegmentation().getAnchorTs()));
    }

    private static Episode episode(String id, long startSeconds, double[] values) {
        return Episodes.regular(KEY, startSeconds * SECOND, SECOND, values)
                .episodeId(id).source("learning").createdAt(0).build();
    }
}
