package org.opentsx.market;

import org.opentsx.market.source.EnergyChartsSource;
import org.opentsx.market.source.EntsoeSource;
import org.opentsx.market.source.Fetcher;
import org.opentsx.market.source.Http;
import org.opentsx.market.source.MissingCredentials;
import org.opentsx.market.source.NetztransparenzSource;
import org.opentsx.market.source.Rebap;
import org.opentsx.market.source.SmardSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches any catalog series ({@link MarketCatalog}) for a time range from its source. Credentials come from the
 * environment ({@code ENTSOE_TOKEN}, {@code NTP_CLIENT_ID}, {@code NTP_CLIENT_SECRET}); a source without them
 * raises {@link MissingCredentials} with a message that says what to set.
 */
public final class Collector {

    /** A fetched series and the queries that produced it (secrets redacted), for the manifest. */
    public record Fetched(MarketSeries series, List<String> queries) {
    }

    private final SmardSource smard;
    private final EnergyChartsSource energyCharts;
    private final EntsoeSource entsoe;
    private final NetztransparenzSource ntp;
    private String cacheKey;
    private Object cache;

    public Collector(Fetcher smard, Fetcher energyCharts, Fetcher entsoe, Fetcher ntp, Map<String, String> env) {
        this.smard = new SmardSource(smard);
        this.energyCharts = new EnergyChartsSource(energyCharts);
        this.entsoe = new EntsoeSource(entsoe, env.get("ENTSOE_TOKEN"));
        this.ntp = new NetztransparenzSource(ntp, env.get("NTP_CLIENT_ID"), env.get("NTP_CLIENT_SECRET"));
    }

    /** Online collector; {@code delayMillis} is the minimum pause between two requests to the same host. */
    public static Collector online(long delayMillis) {
        return new Collector(new Http(delayMillis, 4), new Http(delayMillis, 4), new Http(Math.max(delayMillis, 1000), 4),
                new Http(Math.max(delayMillis, 600), 4), System.getenv());
    }

    /** Whether the source of {@code def} can be used with the configured credentials; otherwise the reason. */
    public static String unavailable(SeriesDef def, Map<String, String> env) {
        return switch (def.source()) {
            case "entsoe" -> blank(env.get("ENTSOE_TOKEN")) ? "ENTSOE_TOKEN not set" : null;
            case "netztransparenz" -> blank(env.get("NTP_CLIENT_ID")) || blank(env.get("NTP_CLIENT_SECRET"))
                    ? "NTP_CLIENT_ID / NTP_CLIENT_SECRET not set" : null;
            default -> null;
        };
    }

    public Fetched fetch(SeriesDef def, long from, long to) {
        List<String> q = new ArrayList<>();
        List<Point> points = switch (def.source()) {
            case "smard" -> smard.fetch(MarketCatalog.smard(def.name()), from, to, q);
            case "energy-charts" -> energyCharts.prices("DE-LU", from, to, q);
            case "entsoe" -> entsoe.fetch(def.name(), from, to, q);
            case "netztransparenz" -> netztransparenz(def.name(), from, to, q);
            default -> throw new IllegalArgumentException("no online source for " + def.path());
        };
        return new Fetched(new MarketSeries(def, points), q);
    }

    @SuppressWarnings("unchecked")
    private List<Point> netztransparenz(String name, long from, long to, List<String> q) {
        boolean rebap = name.startsWith("rebap_");
        String key = (rebap ? "rebap" : "marktwert") + "/" + from + "/" + to;
        if (!key.equals(cacheKey)) {   // both sides / all technologies come in one response
            List<String> queries = new ArrayList<>();
            cache = rebap ? ntp.rebap(from, to, queries) : ntp.marktwerte(from, to, queries);
            cacheKey = key;
            q.addAll(queries);
        } else {
            q.add("(same response as the previous series)");
        }
        if (rebap) {
            Rebap.Result r = (Rebap.Result) cache;
            return name.equals("rebap_short") ? r.shortSide() : r.longSide();
        }
        return ((Map<String, List<Point>>) cache).getOrDefault(name.substring("marktwert_".length()), List.of());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
