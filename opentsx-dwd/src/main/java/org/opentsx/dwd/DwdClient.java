package org.opentsx.dwd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal HTTP fetch from {@code opendata.dwd.de} (JDK {@code java.net.http}) through the given proxy selector. A 429,
 * a 5xx and a timeout are tried again, at most {@value #TRIES} tries in all, after 2, 4 and 8 seconds or the
 * {@code Retry-After} of the answer, at most 60 seconds. Every other failure, such as an unknown host or a TLS error,
 * fails at once.
 */
public final class DwdClient {

    /** MOSMIX station catalog (fixed-width text, coordinates in degrees.minutes). */
    public static final String MOSMIX_STATIONS =
            "https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/mosmix_stationskatalog.cfg?view=nasPublication";

    /** CDC 10-minute wind station list ({@code *_Beschreibung_Stationen.txt}). */
    public static final String CDC_STATIONS =
            "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/10_minutes/wind/now/zehn_now_ff_Beschreibung_Stationen.txt";

    static final int TRIES = 4;
    static final Duration MAX_PAUSE = Duration.ofSeconds(60);
    private static final Logger LOG = LoggerFactory.getLogger(DwdClient.class);

    interface Sleeper {
        void sleep(Duration pause) throws InterruptedException;
    }

    private final HttpClient http;
    private final Sleeper sleeper;

    public DwdClient(ProxySelector proxy) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(proxy)
                .build(), pause -> Thread.sleep(pause.toMillis()));
    }

    DwdClient(HttpClient http, Sleeper sleeper) {
        this.http = http;
        this.sleeper = sleeper;
    }

    public byte[] get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                .header("User-Agent", "opentsx-dwd").GET().build();
        for (int attempt = 1; ; attempt++) {
            Duration pause = Duration.ofSeconds(1L << attempt);
            HttpResponse<byte[]> r;
            try {
                r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpTimeoutException e) {
                if (attempt == TRIES) {
                    throw new IOException("cannot fetch " + url + " after " + attempt + " tries", e);
                }
                pause(url, e.toString(), attempt, pause);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted: " + url, e);
            } catch (IOException e) {
                throw new IOException("cannot fetch " + url, e);
            }
            int status = r.statusCode();
            if (status == 200) {
                return r.body();
            }
            boolean again = status == 429 || (status >= 500 && status <= 599);
            if (!again || attempt == TRIES) {
                throw new IOException("HTTP " + status + " for " + url + (attempt > 1 ? " after " + attempt + " tries" : ""));
            }
            Optional<Duration> asked = retryAfter(r);
            if (asked.isPresent() && asked.get().compareTo(pause) > 0) {
                pause = asked.get().compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : asked.get();
            }
            pause(url, "HTTP " + status, attempt, pause);
        }
    }

    private void pause(String url, String problem, int attempt, Duration pause) throws IOException {
        LOG.warn("GET {}: {}; try {} of {} in {} s", url, problem, attempt + 1, TRIES, pause.toSeconds());
        try {
            sleeper.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + url, e);
        }
    }

    /** The delay in seconds of a {@code Retry-After} header; its HTTP-date form is not used. */
    private static Optional<Duration> retryAfter(HttpResponse<?> r) {
        Optional<String> v = r.headers().firstValue("Retry-After");
        if (v.isEmpty() || !v.get().strip().matches("[0-9]{1,6}")) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(Long.parseLong(v.get().strip())));
    }
}
