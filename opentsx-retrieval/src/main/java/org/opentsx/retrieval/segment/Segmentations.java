package org.opentsx.retrieval.segment;

import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeOps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Batch segmentation: all episodes at once (any number of series). */
public final class Segmentations {

    private Segmentations() {
    }

    public static List<Episode> segment(Collection<Episode> episodes, Segmenter segmenter) {
        List<Episode> sorted = new ArrayList<>(episodes);
        sorted.sort(EpisodeOps.READ_ORDER);
        StreamingSegmenter s = new StreamingSegmenter(segmenter);
        List<Episode> out = new ArrayList<>();
        for (Episode e : sorted) {
            out.addAll(s.onEpisode(e));
        }
        out.addAll(s.flush());
        out.sort(EpisodeOps.READ_ORDER);
        return out;
    }
}
