package org.opentsx.market.source;

import org.opentsx.market.MarketCatalog;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SMARD chart_data client ({@code https://www.smard.de/app/chart_data/{filter}/{region}/...}), no registration.
 * Reads the index of the weekly files once per filter, region and resolution, then the files overlapping the
 * requested range.
 * <p>
 * Native resolution: generation and load are quarter-hourly (MWh per quarter-hour, converted to MW); the day-ahead
 * price is read hourly before the 15-minute MTU (2025-10-01) and quarter-hourly from then on. (The quarter-hour
 * price file repeats each hourly price four times before the switch; reading it would fake a finer resolution.)
 */
public final class SmardSource {

    public static final String BASE = "https://www.smard.de/app/chart_data";

    private final Fetcher fetcher;
    private final String base;
    private final Map<String, long[]> indexCache = new HashMap<>();

    public SmardSource(Fetcher fetcher) {
        this(fetcher, BASE);
    }

    public SmardSource(Fetcher fetcher, String base) {
        this.fetcher = fetcher;
        this.base = base;
    }

    /** Points in {@code [from, to)} in native resolution; {@code queries} receives every URL read. */
    public List<Point> fetch(MarketCatalog.Smard s, long from, long to, List<String> queries) {
        if (!s.energy()) {
            long mtu = Micro.of(Micro.MTU_15_MIN);
            List<Point> out = new ArrayList<>();
            if (from < mtu) {
                out.addAll(fetch(s, "hour", from, Math.min(to, mtu), queries));
            }
            if (to > mtu) {
                out.addAll(fetch(s, "quarterhour", Math.max(from, mtu), to, queries));
            }
            return out;
        }
        return fetch(s, "quarterhour", from, to, queries);
    }

    /** Points in {@code [from, to)} in the given resolution ({@code hour} or {@code quarterhour}). */
    public List<Point> fetch(MarketCatalog.Smard s, String resolution, long from, long to, List<String> queries) {
        long dt = switch (resolution) {
            case "hour" -> Micro.HOUR;
            case "quarterhour" -> Micro.QUARTER;
            default -> throw new IllegalArgumentException("resolution must be hour or quarterhour: " + resolution);
        };
        String dir = base + "/" + s.filter() + "/" + s.region() + "/";
        String indexUrl = dir + "index_" + resolution + ".json";
        long[] files = indexCache.get(indexUrl);
        if (files == null) {
            queries.add(indexUrl);
            files = SmardJson.index(fetcher.get(indexUrl));
            indexCache.put(indexUrl, files);
        }
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            long start = files[i] * 1000;
            long end = i + 1 < files.length ? files[i + 1] * 1000 : Long.MAX_VALUE;
            if (end <= from || start >= to) {
                continue;
            }
            String url = dir + s.filter() + "_" + s.region() + "_" + resolution + "_" + files[i] + ".json";
            queries.add(url);
            for (Point p : SmardJson.series(fetcher.get(url), dt, s.energy())) {
                if (p.tMicros() >= from && p.tMicros() < to) {
                    out.add(p);
                }
            }
        }
        return out;
    }
}
