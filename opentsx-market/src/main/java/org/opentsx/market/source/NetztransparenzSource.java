package org.opentsx.market.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * netztransparenz.de WebAPI client (the four German TSOs; documentation "WebAPI Netztransparenz" v1.14). OAuth 2.0
 * client credentials: register as API user on netztransparenz.de, create a client in the OAuth manager of the
 * extranet and set {@code NTP_CLIENT_ID} and {@code NTP_CLIENT_SECRET}. Tokens are valid for one hour. The API allows
 * at most 2 requests per second and IP (repeated violations block the IP for two hours), so the {@link Http}
 * used here must pace at 600 ms or more.
 * <p>
 * Without credentials use the CSV download of the web pages and the offline import ({@code --offline}).
 */
public final class NetztransparenzSource {

    public static final String BASE = "https://ds.netztransparenz.de/api/v1/data";
    public static final String TOKEN_URL = "https://identity.netztransparenz.de/users/connect/token";
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Fetcher fetcher;
    private final String clientId;
    private final String clientSecret;
    private final String base;
    private final String tokenUrl;
    private String token;
    private long tokenExpiresMillis;

    public NetztransparenzSource(Fetcher fetcher, String clientId, String clientSecret) {
        this(fetcher, clientId, clientSecret, BASE, TOKEN_URL);
    }

    public NetztransparenzSource(Fetcher fetcher, String clientId, String clientSecret, String base, String tokenUrl) {
        this.fetcher = fetcher;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.base = base;
        this.tokenUrl = tokenUrl;
    }

    /** reBAP (quality-assured) in {@code [from, to)}, one request per month. */
    public Rebap.Result rebap(long from, long to, List<String> queries) {
        List<Point> s = new ArrayList<>();
        List<Point> l = new ArrayList<>();
        long t = from;
        while (t < to) {
            long next = Math.min(to, Micro.of(Micro.instant(t).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
                    .plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            String url = base + "/NrvSaldo/reBAP/Qualitaetsgesichert/" + T.format(Micro.instant(t)) + "/"
                    + T.format(Micro.instant(next));
            queries.add(url);
            Rebap.Result r = Rebap.parse(get(url).lines().toList());
            r.shortSide().stream().filter(p -> p.tMicros() >= from && p.tMicros() < to).forEach(s::add);
            r.longSide().stream().filter(p -> p.tMicros() >= from && p.tMicros() < to).forEach(l::add);
            t = next;
        }
        return new Rebap.Result(s, l);
    }

    /** Monthly market values for the months of {@code [from, to)} (local months). */
    public Map<String, List<Point>> marktwerte(long from, long to, List<String> queries) {
        LocalDate a = Micro.instant(from).atZone(Micro.BERLIN).toLocalDate();
        LocalDate b = Micro.instant(to - 1).atZone(Micro.BERLIN).toLocalDate();
        String url = base + "/marktpraemie/" + a.getMonthValue() + "/" + a.getYear() + "/" + b.getMonthValue() + "/"
                + b.getYear();
        queries.add(url);
        return Marktwerte.parse(get(url).lines().toList());
    }

    private String get(String url) {
        return fetcher.get(url, Map.of("Authorization", "Bearer " + token()));
    }

    private String token() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new MissingCredentials("netztransparenz.de needs an OAuth client: set NTP_CLIENT_ID and "
                    + "NTP_CLIENT_SECRET (OAuth manager in the netztransparenz.de extranet), or import the CSV "
                    + "download with --offline");
        }
        if (token == null || System.currentTimeMillis() > tokenExpiresMillis - 60_000) {
            String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
            try {
                JsonNode r = JSON.readTree(fetcher.postForm(tokenUrl, form, Map.of()));
                token = r.path("access_token").asText(null);
                tokenExpiresMillis = System.currentTimeMillis() + r.path("expires_in").asLong(3600) * 1000;
            } catch (IOException e) {
                throw new UncheckedIOException("token response is not JSON", e);
            } catch (Http.StatusException e) {
                throw new MissingCredentials("netztransparenz.de rejected the client credentials (HTTP " + e.status() + ")");
            }
            if (token == null) {
                throw new MissingCredentials("netztransparenz.de token response without access_token");
            }
        }
        return token;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
