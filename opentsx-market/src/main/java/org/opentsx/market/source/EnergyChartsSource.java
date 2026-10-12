package org.opentsx.market.source;

import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Energy-Charts API client (Fraunhofer ISE, {@code https://api.energy-charts.info}), no registration. Used as an
 * alternative and cross-check for the day-ahead price; requests one month at a time ({@code start}/{@code end}
 * are local dates, both inclusive).
 */
public final class EnergyChartsSource {

    public static final String BASE = "https://api.energy-charts.info";

    private final Fetcher fetcher;
    private final String base;

    public EnergyChartsSource(Fetcher fetcher) {
        this(fetcher, BASE);
    }

    public EnergyChartsSource(Fetcher fetcher, String base) {
        this.fetcher = fetcher;
        this.base = base;
    }

    public List<Point> prices(String biddingZone, long from, long to, List<String> queries) {
        LocalDate d = Micro.instant(from).atZone(Micro.BERLIN).toLocalDate().withDayOfMonth(1);
        LocalDate last = Micro.instant(to - 1).atZone(Micro.BERLIN).toLocalDate();
        List<Point> out = new ArrayList<>();
        while (!d.isAfter(last)) {
            LocalDate end = d.plusMonths(1).minusDays(1);
            String url = base + "/price?bzn=" + biddingZone + "&start=" + d + "&end=" + (end.isAfter(last) ? last : end);
            queries.add(url);
            for (Point p : EnergyChartsJson.prices(fetcher.get(url)).points()) {
                if (p.tMicros() >= from && p.tMicros() < to) {
                    out.add(p);
                }
            }
            d = d.plusMonths(1);
        }
        return out;
    }
}
