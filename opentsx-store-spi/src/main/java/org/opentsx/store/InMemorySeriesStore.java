package org.opentsx.store;

import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reference implementation of the contract; also handy for notebooks and tests. */
public final class InMemorySeriesStore implements SeriesStore {

    private final Map<String, Episode> episodes = new LinkedHashMap<>();
    private final Map<String, SeriesKey> series = new LinkedHashMap<>();

    @Override
    public StoreCapabilities capabilities() {
        return StoreCapabilities.of(Capability.EPISODES, Capability.MICROSECOND_PRECISION, Capability.NAN_VALUES);
    }

    @Override
    public synchronized WriteResult write(Collection<Episode> batch) {
        int written = 0;
        int skipped = 0;
        for (Episode e : batch) {
            if (episodes.putIfAbsent(e.getEpisodeId(), e) == null) {
                series.putIfAbsent(e.getSeries().getSeriesId(), e.getSeries());
                written++;
            } else {
                skipped++;
            }
        }
        return new WriteResult(written, skipped, 0);
    }

    @Override
    public synchronized List<Episode> read(EpisodeQuery q) {
        List<Episode> out = new ArrayList<>();
        for (Episode e : episodes.values()) {
            if (q.selector().matches(e.getSeries()) && EpisodeOps.overlaps(e, q.fromMicros(), q.toMicros())) {
                Episode r = q.clip() ? EpisodeOps.clip(e, q.fromMicros(), q.toMicros()) : e;
                if (r != null) {
                    out.add(r);
                }
            }
        }
        out.sort(EpisodeOps.READ_ORDER);
        return out.size() > q.limit() ? new ArrayList<>(out.subList(0, q.limit())) : out;
    }

    @Override
    public synchronized Optional<Episode> episode(String episodeId) {
        return Optional.ofNullable(episodes.get(episodeId));
    }

    @Override
    public synchronized List<SeriesKey> listSeries(SeriesSelector selector) {
        return series.values().stream().filter(selector::matches).toList();
    }
}
