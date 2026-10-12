package org.opentsx.dwd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP fetch from {@code opendata.dwd.de} (JDK {@code java.net.http}). Uses the JVM proxy settings
 * ({@code https.proxyHost}/{@code https.proxyPort}); if none are set, {@code HTTPS_PROXY} from the environment.
 */
public final class DwdClient {

    /** MOSMIX station catalog (fixed-width text, coordinates in degrees.minutes). */
    public static final String MOSMIX_STATIONS =
            "https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/mosmix_stationskatalog.cfg?view=nasPublication";

    /** CDC 10-minute wind station list ({@code *_Beschreibung_Stationen.txt}). */
    public static final String CDC_STATIONS =
            "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/10_minutes/wind/now/zehn_now_ff_Beschreibung_Stationen.txt";

    private final HttpClient http;

    public DwdClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        b.proxy(proxy());
        http = b.build();
    }

    public byte[] get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                .header("User-Agent", "opentsx-dwd").GET().build();
        try {
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode() + " for " + url);
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + url, e);
        }
    }

    private static ProxySelector proxy() {
        if (System.getProperty("https.proxyHost") != null) {
            return ProxySelector.getDefault();
        }
        String env = System.getenv("HTTPS_PROXY");
        if (env == null || env.isBlank()) {
            env = System.getenv("https_proxy");
        }
        if (env != null && !env.isBlank()) {
            URI u = URI.create(env.contains("://") ? env : "http://" + env);
            if (u.getHost() != null) {
                return ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80));
            }
        }
        return ProxySelector.getDefault();
    }
}
