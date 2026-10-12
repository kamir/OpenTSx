package org.opentsx.retrieval;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.retrieval.pattern.Distance;
import org.opentsx.retrieval.pattern.EpisodeSimilarity;
import org.opentsx.retrieval.pattern.PatternSearch;
import org.opentsx.retrieval.pattern.Run;
import org.opentsx.retrieval.segment.Segmentations;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.store.Capability;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The simple retrieval API over one or more {@link SeriesStore}s.
 *
 * <pre>
 * EpisodeService svc = new EpisodeService(icebergStore);
 * List&lt;Episode&gt; eps = svc.episodes().metric("wind.met.wind_speed").tag("turbine", "T07")
 *         .between(from, to).resample(Duration.ofMinutes(1), Resampling.Agg.MEAN).fetch();
 * Optional&lt;Episode&gt; one = svc.episode("01JA...");
 * List&lt;PatternMatch&gt; hits = svc.similarTo(pattern).metric("wind.met.wind_speed").between(from, to)
 *         .distance(Distance.dtw(0.1)).topK(20).fetch();
 * List&lt;PatternMatch&gt; like = svc.similarEpisodes("01JA...").metric("wind.turbine.power_active").topK(10).fetch();
 * List&lt;Episode&gt; gusts = svc.segments(Segmenter.extremum(...)).metric("wind.met.wind_speed").between(from, to).fetch();
 * </pre>
 * Reads go to the first store that keeps episodes ({@link Capability#EPISODES}), otherwise the first store.
 */
public final class EpisodeService {

    private final List<SeriesStore> stores;

    public EpisodeService(SeriesStore... stores) {
        if (stores.length == 0) {
            throw new IllegalArgumentException("at least one store");
        }
        this.stores = List.of(stores);
    }

    /** The store reads go to. */
    public SeriesStore primary() {
        return stores.stream().filter(s -> s.capabilities().has(Capability.EPISODES)).findFirst().orElse(stores.get(0));
    }

    public EpisodesQuery episodes() {
        return new EpisodesQuery();
    }

    public Optional<Episode> episode(String episodeId) {
        for (SeriesStore s : stores) {
            Optional<Episode> e = s.episode(episodeId);
            if (e.isPresent()) {
                return e;
            }
        }
        return Optional.empty();
    }

    /** Subsequence search for a pattern given as values. */
    public SubsequenceQuery similarTo(double[] pattern) {
        return new SubsequenceQuery(pattern.clone());
    }

    /** Subsequence search for a piece of a stored episode. */
    public SubsequenceQuery similarTo(String episodeId, int offset, int length) {
        Episode e = episode(episodeId).orElseThrow(() -> new IllegalArgumentException("unknown episode " + episodeId));
        double[] v = Episodes.values(e);
        if (offset < 0 || length < 4 || offset + length > v.length) {
            throw new IllegalArgumentException("pattern outside the episode");
        }
        return new SubsequenceQuery(Arrays.copyOfRange(v, offset, offset + length));
    }

    /** Whole episodes that look like the given one. */
    public SimilarEpisodesQuery similarEpisodes(String episodeId) {
        return new SimilarEpisodesQuery(episode(episodeId).orElseThrow(() -> new IllegalArgumentException("unknown episode " + episodeId)));
    }

    public SimilarEpisodesQuery similarEpisodes(Episode episode) {
        return new SimilarEpisodesQuery(episode);
    }

    /** Segments (derived episodes) of the selected series. */
    public SegmentQuery segments(Segmenter segmenter) {
        return new SegmentQuery(segmenter);
    }

    /** Common selection: series and time range. */
    @SuppressWarnings("unchecked")
    public abstract class Selection<T extends Selection<T>> {
        String metric;
        final Map<String, String> tags = new LinkedHashMap<>();
        Set<String> ids;
        long from = Long.MIN_VALUE / 2;
        long to = Long.MAX_VALUE / 2;

        public T metric(String metric) {
            this.metric = metric;
            return (T) this;
        }

        public T tag(String key, String value) {
            tags.put(key, value);
            return (T) this;
        }

        public T series(String... seriesIds) {
            ids = new HashSet<>(Arrays.asList(seriesIds));
            return (T) this;
        }

        public T in(SeriesSelector selector) {
            metric = selector.metric();
            tags.putAll(selector.tags());
            ids = selector.seriesIds();
            return (T) this;
        }

        public T between(Instant from, Instant to) {
            this.from = Micros.of(from);
            this.to = Micros.of(to);
            return (T) this;
        }

        SeriesSelector selector() {
            return new SeriesSelector(metric, tags, ids);
        }

        List<Episode> read(boolean clip, int limit) {
            EpisodeQuery q = new EpisodeQuery(selector(), from, to, clip, limit);
            return primary().read(q);
        }
    }

    public final class EpisodesQuery extends Selection<EpisodesQuery> {
        private boolean clip;
        private int limit = Integer.MAX_VALUE;
        private Duration every;
        private Resampling.Agg agg;

        /** Cut episodes to the time range. */
        public EpisodesQuery clip() {
            this.clip = true;
            return this;
        }

        public EpisodesQuery limit(int n) {
            this.limit = n;
            return this;
        }

        public EpisodesQuery resample(Duration every, Resampling.Agg agg) {
            this.every = every;
            this.agg = agg;
            return this;
        }

        public List<Episode> fetch() {
            List<Episode> eps = read(clip, limit);
            if (every == null) {
                return eps;
            }
            List<Episode> out = new ArrayList<>(eps.size());
            for (Episode e : eps) {
                out.add(Resampling.resample(e, every, agg));
            }
            return out;
        }
    }

    public final class SubsequenceQuery extends Selection<SubsequenceQuery> {
        private final double[] pattern;
        private Distance distance = Distance.zNormalizedEuclidean();
        private int k = 10;
        private PatternSearch.Stats stats;

        SubsequenceQuery(double[] pattern) {
            this.pattern = pattern;
        }

        public SubsequenceQuery distance(Distance d) {
            this.distance = d;
            return this;
        }

        public SubsequenceQuery topK(int k) {
            this.k = k;
            return this;
        }

        public List<PatternMatch> fetch() {
            PatternSearch s = new PatternSearch(pattern, distance, k);
            List<PatternMatch> hits = s.search(Run.of(read(false, Integer.MAX_VALUE)));
            stats = s.stats();
            return hits;
        }

        public PatternSearch.Stats stats() {
            return stats;
        }
    }

    public final class SimilarEpisodesQuery extends Selection<SimilarEpisodesQuery> {
        private final Episode query;
        private Distance distance = Distance.zNormalizedEuclidean();
        private int k = 10;
        private EpisodeSimilarity similarity = EpisodeSimilarity.defaults();

        SimilarEpisodesQuery(Episode query) {
            this.query = query;
            this.metric = query.getSeries().getMetric();
        }

        public SimilarEpisodesQuery distance(Distance d) {
            this.distance = d;
            return this;
        }

        public SimilarEpisodesQuery topK(int k) {
            this.k = k;
            return this;
        }

        /** Representation length, SAX word length and alphabet (default 64 / 16 / 8). */
        public SimilarEpisodesQuery representation(int points, int saxLength, int alphabet) {
            this.similarity = new EpisodeSimilarity(points, saxLength, alphabet);
            return this;
        }

        public List<PatternMatch> fetch() {
            return similarity.search(query, read(false, Integer.MAX_VALUE), distance, k);
        }
    }

    public final class SegmentQuery extends Selection<SegmentQuery> {
        private final Segmenter segmenter;
        private SeriesStore target;

        SegmentQuery(Segmenter segmenter) {
            this.segmenter = segmenter;
        }

        /** Also write the segments to this store (idempotent: segment ids are deterministic). */
        public SegmentQuery writeTo(SeriesStore store) {
            this.target = store;
            return this;
        }

        public List<Episode> fetch() {
            List<Episode> segments = Segmentations.segment(read(false, Integer.MAX_VALUE), segmenter);
            if (target != null && !segments.isEmpty()) {
                target.write(segments);
            }
            return segments;
        }
    }
}
