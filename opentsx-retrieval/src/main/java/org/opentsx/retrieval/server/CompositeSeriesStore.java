package org.opentsx.retrieval.server;

import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.store.EpisodeOps;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.StoreCapabilities;
import org.opentsx.store.WriteResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads from a main store and from a second one that is replaced as a whole (the series of the vector topics).
 * Writes go to the main store.
 */
final class CompositeSeriesStore implements SeriesStore {

    private final SeriesStore main;
    private final Supplier<SeriesStore> extra;

    CompositeSeriesStore(SeriesStore main, Supplier<SeriesStore> extra) {
        this.main = main;
        this.extra = extra;
    }

    @Override
    public StoreCapabilities capabilities() {
        return main.capabilities();
    }

    @Override
    public WriteResult write(Collection<Episode> episodes) {
        return main.write(episodes);
    }

    @Override
    public List<Episode> read(EpisodeQuery query) {
        List<Episode> out = new ArrayList<>(main.read(query));
        out.addAll(extra.get().read(query));
        out.sort(EpisodeOps.READ_ORDER);
        return out.size() > query.limit() ? new ArrayList<>(out.subList(0, query.limit())) : out;
    }

    @Override
    public List<SeriesKey> listSeries(SeriesSelector selector) {
        List<SeriesKey> out = new ArrayList<>(main.listSeries(selector));
        out.addAll(extra.get().listSeries(selector));
        return out;
    }

    @Override
    public Optional<Episode> episode(String episodeId) {
        Optional<Episode> e = main.episode(episodeId);
        return e.isPresent() ? e : extra.get().episode(episodeId);
    }
}
