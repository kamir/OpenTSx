package org.opentsx.store;

import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.Collection;
import java.util.List;

/**
 * Storage backend for v2 episodes. Implementations: in-memory (reference), Iceberg, OpenTSDB.
 *
 * <p>Contract (checked by {@code SeriesStoreContract}):
 * <ul>
 *   <li>{@link #write} is idempotent per episodeId.</li>
 *   <li>{@link #read} returns episodes overlapping the query window, ordered by (tStart, seriesId, episodeId).</li>
 *   <li>Stores with {@link Capability#EPISODES} return episodes exactly as written (unless clipped);
 *       point stores return the stored points of each series in the window as reconstructed episodes.</li>
 * </ul>
 */
public interface SeriesStore extends AutoCloseable {

    StoreCapabilities capabilities();

    WriteResult write(Collection<Episode> episodes);

    List<Episode> read(EpisodeQuery query);

    List<SeriesKey> listSeries(SeriesSelector selector);

    @Override
    default void close() {
    }
}
