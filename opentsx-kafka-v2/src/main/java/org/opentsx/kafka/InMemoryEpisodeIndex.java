package org.opentsx.kafka;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Index in memory, for tests, tools and small catalogs. {@link FileEpisodeIndex} keeps the entries in a file,
 * {@code IcebergEpisodeIndex} of opentsx-store-iceberg in an Iceberg table.
 */
public class InMemoryEpisodeIndex implements EpisodeIndex {

    private static final Comparator<IndexEntry> BY_TIME =
            Comparator.comparingLong(IndexEntry::tStartMicros).thenComparing(IndexEntry::episodeId);

    private final Map<String, IndexEntry> byEpisode = new LinkedHashMap<>();

    @Override
    public synchronized void add(IndexEntry entry) {
        byEpisode.merge(entry.episodeId(), entry, (old, neu) -> neu.offset() < old.offset() ? neu : old);
    }

    @Override
    public synchronized List<IndexEntry> bucket(String bucketId) {
        List<IndexEntry> out = new ArrayList<>();
        for (IndexEntry e : byEpisode.values()) {
            if (bucketId.equals(e.bucketId())) {
                out.add(e);
            }
        }
        out.sort(BY_TIME);
        return out;
    }

    @Override
    public synchronized List<IndexEntry> find(Set<String> seriesIds, long fromMicros, long toMicros) {
        List<IndexEntry> out = new ArrayList<>();
        for (IndexEntry e : byEpisode.values()) {
            if ((seriesIds == null || seriesIds.contains(e.seriesId())) && e.overlaps(fromMicros, toMicros)) {
                out.add(e);
            }
        }
        out.sort(BY_TIME);
        return out;
    }

    @Override
    public synchronized int size() {
        return byEpisode.size();
    }

    synchronized List<IndexEntry> entries() {
        return new ArrayList<>(byEpisode.values());
    }
}
