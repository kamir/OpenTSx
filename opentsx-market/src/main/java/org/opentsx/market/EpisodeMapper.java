package org.opentsx.market;

import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a collected series to v2 episodes: REGULAR episodes, one per UTC day (monthly values: one per month), split
 * where the resolution changes (MTU switch) or values are missing, so a gap is never filled. Labels carry the
 * origin ({@code REPORTED}, {@code MEASURED}, {@code FORECAST}), the resolution, the licence and the archive path.
 */
public final class EpisodeMapper {

    private EpisodeMapper() {
    }

    public static List<Episode> episodes(MarketSeries s, String producer) {
        SeriesKey key = s.def().key();
        List<Episode> out = new ArrayList<>();
        List<Point> pts = s.points();
        int i = 0;
        while (i < pts.size()) {
            Point first = pts.get(i);
            long dt = first.dtMicros();
            long dayEnd = dt >= Micro.DAY ? Long.MAX_VALUE : Math.floorDiv(first.tMicros(), Micro.DAY) * Micro.DAY + Micro.DAY;
            int j = i + 1;
            while (j < pts.size() && pts.get(j).dtMicros() == dt && dt < Micro.DAY
                    && pts.get(j).tMicros() == pts.get(j - 1).tMicros() + dt && pts.get(j).tMicros() < dayEnd) {
                j++;
            }
            double[] v = new double[j - i];
            for (int k = i; k < j; k++) {
                v[k - i] = pts.get(k).value();
            }
            out.add(Episodes.regular(key, first.tMicros(), dt, v)
                    .source(s.def().source())
                    .producer(producer)
                    .label("origin", s.def().origin())
                    .label("resolution", Duration.ofNanos(dt * 1000).toString())
                    .label("licence", s.def().licence())
                    .label("archive", s.def().path())
                    .build());
            i = j;
        }
        return out;
    }
}
