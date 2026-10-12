package org.opentsx.retrieval.pattern;

import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;
import org.opentsx.store.EpisodeOps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contiguous samples of one series, concatenated from consecutive episodes, with the mapping back to
 * (episode, offset). A run breaks where the time axis has a gap or the sampling interval changes.
 */
public final class Run {

    final String seriesId;
    final double[] values;
    final String[] episodeIds;
    final int[] offsets;

    private Run(String seriesId, double[] values, String[] episodeIds, int[] offsets) {
        this.seriesId = seriesId;
        this.values = values;
        this.episodeIds = episodeIds;
        this.offsets = offsets;
    }

    public String seriesId() {
        return seriesId;
    }

    public int length() {
        return values.length;
    }

    /** Builds runs from episodes of any number of series. */
    public static List<Run> of(Collection<Episode> episodes) {
        Map<String, List<Episode>> bySeries = new LinkedHashMap<>();
        List<Episode> sorted = new ArrayList<>(episodes);
        sorted.sort(EpisodeOps.READ_ORDER);
        for (Episode e : sorted) {
            bySeries.computeIfAbsent(e.getSeries().getSeriesId(), k -> new ArrayList<>()).add(e);
        }
        List<Run> runs = new ArrayList<>();
        for (Map.Entry<String, List<Episode>> en : bySeries.entrySet()) {
            List<Episode> cur = new ArrayList<>();
            Episode prev = null;
            for (Episode e : en.getValue()) {
                if (prev != null && !continues(prev, e)) {
                    runs.add(build(en.getKey(), cur));
                    cur = new ArrayList<>();
                }
                cur.add(e);
                prev = e;
            }
            if (!cur.isEmpty()) {
                runs.add(build(en.getKey(), cur));
            }
        }
        return runs;
    }

    private static boolean continues(Episode a, Episode b) {
        if (a.getTimeEncoding() == TimeEncoding.REGULAR && b.getTimeEncoding() == TimeEncoding.REGULAR) {
            return a.getIntervalMicros().equals(b.getIntervalMicros()) && a.getTEnd().equals(b.getTStart());
        }
        return !b.getTStart().isBefore(a.getTEnd()) && !b.getTStart().isAfter(a.getTEnd().plusSeconds(1));
    }

    private static Run build(String seriesId, List<Episode> eps) {
        int n = eps.stream().mapToInt(Episode::getCount).sum();
        double[] v = new double[n];
        String[] ids = new String[n];
        int[] off = new int[n];
        int k = 0;
        for (Episode e : eps) {
            double[] ev = Episodes.values(e);
            for (int i = 0; i < ev.length; i++, k++) {
                v[k] = ev[i];
                ids[k] = e.getEpisodeId();
                off[k] = i;
            }
        }
        return new Run(seriesId, v, ids, off);
    }
}
