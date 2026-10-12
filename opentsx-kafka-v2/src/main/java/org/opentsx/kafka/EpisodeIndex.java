package org.opentsx.kafka;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Lookup from episodes to log positions. Duplicate episode ids keep the first (lowest) position. */
public interface EpisodeIndex {

    void add(IndexEntry entry);

    default void addAll(Collection<IndexEntry> entries) {
        entries.forEach(this::add);
    }

    List<IndexEntry> bucket(String bucketId);

    /** Episodes overlapping [fromMicros, toMicros); {@code seriesIds == null} means all series. */
    List<IndexEntry> find(Set<String> seriesIds, long fromMicros, long toMicros);

    int size();
}
