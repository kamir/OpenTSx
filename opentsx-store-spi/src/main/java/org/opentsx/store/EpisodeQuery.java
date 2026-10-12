package org.opentsx.store;

/**
 * Episodes of the selected series overlapping [fromMicros, toMicros).
 *
 * @param clip  if true, episodes are cut to the window (values outside are removed); point stores always clip
 * @param limit maximum number of episodes, {@code Integer.MAX_VALUE} for no limit
 */
public record EpisodeQuery(SeriesSelector selector, long fromMicros, long toMicros, boolean clip, int limit) {

    public EpisodeQuery {
        if (fromMicros >= toMicros) {
            throw new IllegalArgumentException("fromMicros must be before toMicros");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
    }

    public static EpisodeQuery of(SeriesSelector selector, long fromMicros, long toMicros) {
        return new EpisodeQuery(selector, fromMicros, toMicros, false, Integer.MAX_VALUE);
    }

    public EpisodeQuery clipped() {
        return new EpisodeQuery(selector, fromMicros, toMicros, true, limit);
    }

    public EpisodeQuery limit(int n) {
        return new EpisodeQuery(selector, fromMicros, toMicros, clip, n);
    }
}
