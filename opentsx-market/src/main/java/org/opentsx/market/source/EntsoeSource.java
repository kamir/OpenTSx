package org.opentsx.market.source;

import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ENTSO-E Transparency Platform REST client ({@code https://web-api.tp.entsoe.eu/api}). Needs a personal security
 * token (free registration, then "Web API Security Token" in the account settings), read from {@code ENTSOE_TOKEN};
 * without it every call raises {@link MissingCredentials}. Requests one month at a time.
 * <p>
 * Queries: day-ahead prices {@code documentType=A44} (in/out domain DE-LU {@code 10Y1001A1001A82H}); actual total
 * load {@code A65/processType=A16}; day-ahead generation forecast wind and solar {@code A69/processType=A01} with
 * {@code psrType} B19 (wind onshore), B18 (wind offshore), B16 (solar).
 */
public final class EntsoeSource {

    public static final String BASE = "https://web-api.tp.entsoe.eu/api";
    public static final String DE_LU = "10Y1001A1001A82H";
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    /** The queries the collector knows, by archive series name. */
    public static final Map<String, String> QUERIES = Map.of(
            "day_ahead_price", "documentType=A44&in_Domain=" + DE_LU + "&out_Domain=" + DE_LU,
            "load", "documentType=A65&processType=A16&outBiddingZone_Domain=" + DE_LU,
            "forecast_wind_onshore", "documentType=A69&processType=A01&in_Domain=" + DE_LU + "&psrType=B19",
            "forecast_wind_offshore", "documentType=A69&processType=A01&in_Domain=" + DE_LU + "&psrType=B18",
            "forecast_solar", "documentType=A69&processType=A01&in_Domain=" + DE_LU + "&psrType=B16");

    private final Fetcher fetcher;
    private final String token;
    private final String base;

    public EntsoeSource(Fetcher fetcher, String token) {
        this(fetcher, token, BASE);
    }

    public EntsoeSource(Fetcher fetcher, String token, String base) {
        this.fetcher = fetcher;
        this.token = token;
        this.base = base;
    }

    /** Points of a known query in {@code [from, to)}, finest resolution per period. */
    public List<Point> fetch(String series, long from, long to, List<String> queries) {
        String q = QUERIES.get(series);
        if (q == null) {
            throw new IllegalArgumentException("unknown ENTSO-E series " + series + "; known: " + QUERIES.keySet());
        }
        if (token == null || token.isBlank()) {
            throw new MissingCredentials("opentsx.market.entsoeToken: not set; ENTSO-E needs a security token (register at "
                    + "https://transparency.entsoe.eu, then request Web API access) in OPENTSX_MARKET_ENTSOE_TOKEN, "
                    + "ENTSOE_TOKEN or a properties file");
        }
        List<Point> out = new ArrayList<>();
        long t = from;
        while (t < to) {
            long next = Math.min(to, Micro.of(Micro.instant(t).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
                    .plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            String period = "&periodStart=" + PERIOD.format(Micro.instant(t)) + "&periodEnd=" + PERIOD.format(Micro.instant(next));
            String shown = base + "?" + q + period + "&securityToken=***";
            queries.add(shown);
            try {
                for (Point p : EntsoeXml.finest(EntsoeXml.parse(fetcher.get(base + "?" + q + period + "&securityToken=" + token)))) {
                    if (p.tMicros() >= from && p.tMicros() < to) {
                        out.add(p);
                    }
                }
            } catch (EntsoeXml.NoData e) {
                if (!e.getMessage().contains("No matching data found")) {
                    throw new IllegalStateException("HTTP 200 for " + shown + ": " + e.getMessage(), e);
                }
                // no data for this month (e.g. not yet published); the quality report shows the gap
            } catch (Http.StatusException e) {
                if (e.status() == 401) {
                    throw new MissingCredentials("opentsx.market.entsoeToken: rejected by ENTSO-E (HTTP 401); check the "
                            + "token in OPENTSX_MARKET_ENTSOE_TOKEN, ENTSOE_TOKEN or the properties file");
                }
                if (e.status() != 400 || !e.body().contains("Acknowledgement_MarketDocument")) {
                    throw e;
                }
                String reason = reason(e);
                // a month without data is answered with 400 and the acknowledgement "No matching data found ..."
                if (!reason.contains("No matching data found")) {
                    Http.StatusException refused = new Http.StatusException(400, shown, reason);
                    refused.initCause(e);
                    throw refused;
                }
            }
            t = next;
        }
        return out;
    }

    /** The reasons of the acknowledgement in the response of {@code e}; throws {@code e} when it holds none. */
    private static String reason(Http.StatusException e) {
        try {
            EntsoeXml.parse(e.body());
        } catch (EntsoeXml.NoData ack) {
            return ack.getMessage();
        } catch (IllegalArgumentException notXml) {
            e.addSuppressed(notXml);
        }
        throw e;
    }
}
