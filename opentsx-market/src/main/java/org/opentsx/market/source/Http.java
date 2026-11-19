package org.opentsx.market.source;

import org.opentsx.conf.OpenTsxConfException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polite HTTP GET/POST for the collectors (JDK HTTP client): a minimum interval between requests per instance,
 * retries with exponential backoff on network errors, 429 and 5xx, honouring {@code Retry-After}. An unknown host and
 * a TLS error fail at once. The proxy comes from {@link #proxy}.
 */
public class Http implements Fetcher {

    public static final String USER_AGENT = "OpenTSx-market/3.0 (+https://github.com/kamir/OpenTSx)";
    public static final String PROXY = "opentsx.https.proxy";
    private static final Pattern SECRET = Pattern.compile("[?&][^=&#]*(?i:token|secret|key)[^=&#]*=([^&#]+)");

    /** A non-retryable HTTP status (4xx other than 429). */
    public static final class StatusException extends RuntimeException {
        private final int status;
        private final String body;

        public StatusException(int status, String uri, String body) {
            super("HTTP " + status + " for " + redact(uri, uri)
                    + (body.isBlank() ? "" : ": " + abbreviate(redact(uri, body))));
            this.status = status;
            this.body = redact(uri, body);
        }

        public int status() {
            return status;
        }

        /** The whole response, secrets of the URL masked. */
        public String body() {
            return body;
        }
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final HttpClient client;
    private final long minIntervalMillis;
    private final int retries;
    private final Sleeper sleeper;
    private long last;

    public Http(long minIntervalMillis, int retries, ProxySelector proxy) {
        this(HttpClient.newBuilder()
                .proxy(proxy)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), minIntervalMillis, retries, Thread::sleep);
    }

    Http(HttpClient client, long minIntervalMillis, int retries, Sleeper sleeper) {
        this.client = client;
        this.minIntervalMillis = minIntervalMillis;
        this.retries = retries;
        this.sleeper = sleeper;
    }

    /**
     * The proxy of the downloads: the JVM proxy settings win when {@code https.proxyHost} is set; otherwise the key
     * {@value #PROXY} ({@code <host>:<port>} or {@code http://<host>:<port>}), which is checked in any case.
     */
    public static ProxySelector proxy(String jvmProxyHost, Optional<String> configured) {
        ProxySelector own = configured.map(Http::proxyOf).orElse(null);
        return jvmProxyHost != null || own == null ? ProxySelector.getDefault() : own;
    }

    private static ProxySelector proxyOf(String value) {
        URI u;
        try {
            u = new URI(value.contains("://") ? value : "http://" + value);
        } catch (URISyntaxException e) {
            // not kept as the cause: its message holds the value, which is a secret
            u = null;
        }
        if (u == null || u.getHost() == null) {
            throw new OpenTsxConfException(PROXY, "not a proxy address", "expected <host>:<port> or http://<host>:<port>");
        }
        return ProxySelector.of(InetSocketAddress.createUnresolved(u.getHost(), u.getPort() > 0 ? u.getPort() : 80));
    }

    @Override
    public String get(String uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(uri)).GET();
        headers.forEach(b::header);
        return send(b, uri);
    }

    @Override
    public byte[] getBytes(String uri) {
        return send(HttpRequest.newBuilder(uri(uri)).GET(), uri, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** POST of a form ({@code application/x-www-form-urlencoded}). */
    @Override
    public String postForm(String uri, String form, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(uri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        headers.forEach(b::header);
        return send(b, uri);
    }

    private String send(HttpRequest.Builder b, String uri) {
        return send(b, uri, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private <T> T send(HttpRequest.Builder b, String uri, HttpResponse.BodyHandler<T> handler) {
        HttpRequest req = b.header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).build();
        long backoff = 2_000;
        for (int attempt = 0; ; attempt++) {
            pace();
            try {
                HttpResponse<T> r = client.send(req, handler);
                int s = r.statusCode();
                if (s / 100 == 2) {
                    return r.body();
                }
                if (s != 429 && s / 100 != 5 || attempt >= retries) {
                    throw new StatusException(s, uri, r.body() instanceof String t ? t : "");
                }
                backoff = Math.max(backoff, r.headers().firstValue("Retry-After").map(Http::seconds).orElse(0L) * 1000);
            } catch (IOException e) {
                if (attempt >= retries || !retryable(e)) {
                    throw new UncheckedIOException(req.method() + " " + redact(uri, uri) + " failed after " + (attempt + 1)
                            + (attempt == 0 ? " attempt" : " attempts"), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 120_000);
        }
    }

    /** An unknown host or a TLS error does not go away by asking again. */
    private static boolean retryable(IOException e) {
        if (e instanceof SSLException) {
            return false;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return false;
            }
        }
        return true;
    }

    private synchronized void pace() {
        long wait = last + minIntervalMillis - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        last = System.currentTimeMillis();
    }

    private static long seconds(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sleep(long ms) {
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static URI uri(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            // no cause: its message holds the URI with the secret
            throw new IllegalArgumentException(redact(uri, e.getMessage()));
        }
    }

    /** {@code text} with the value of every query parameter of {@code uri} named like a token, secret or key masked. */
    static String redact(String uri, String text) {
        String out = text;
        Matcher m = SECRET.matcher(uri);
        while (m.find()) {
            out = out.replace(m.group(1), "***");
        }
        return out;
    }

    static String abbreviate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
