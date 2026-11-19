package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * OpenMetadata REST client (JDK HTTP client + Jackson; no generated SDK, so it runs on Java 17, while the official
 * Java client 2.0.5 is compiled for Java 21). Authenticates with a bot JWT. Retries the statuses 429, 502, 503 and 504
 * and I/O errors, waiting 1, 2, 4, 8 s and so on between the attempts, or as long as a {@code Retry-After} header
 * says (seconds or an HTTP date), at most 30 s each time. A POST is repeated only when the connection failed.
 */
public final class HttpOmApi implements OmApi {

    /** Waits between two attempts; {@link Thread#sleep(long)} outside of tests. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final long MAX_WAIT_MS = 30_000L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SECONDS = Pattern.compile("^[0-9]{1,9}$");

    private final URI base;
    private final String token;
    private final HttpClient http;
    private final int maxAttempts;
    private final Sleeper sleeper;

    /** @param base server API root, e.g. {@code http://localhost:8585/api}; @param token bot JWT or null */
    public HttpOmApi(URI base, String token) {
        this(base, token, 5);
    }

    public HttpOmApi(URI base, String token, int maxAttempts) {
        this(base, token, maxAttempts, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Thread::sleep);
    }

    HttpOmApi(URI base, String token, int maxAttempts, HttpClient http, Sleeper sleeper) {
        String b = base.toString();
        this.base = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
        this.token = token;
        this.maxAttempts = maxAttempts;
        this.http = http;
        this.sleeper = sleeper;
    }

    @Override
    public JsonNode put(String path, JsonNode body) {
        return send("PUT", path, body).orElseThrow(() -> new OmException("PUT", path, 404, "not found"));
    }

    @Override
    public JsonNode post(String path, JsonNode body) {
        return send("POST", path, body).orElseThrow(() -> new OmException("POST", path, 404, "not found"));
    }

    @Override
    public Optional<JsonNode> get(String path) {
        return send("GET", path, null);
    }

    private Optional<JsonNode> send(String method, String path, JsonNode body) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            rb.header("Authorization", "Bearer " + token);
        }
        try {
            if (body == null) {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                rb.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            }
            HttpRequest request = rb.build();
            for (int attempt = 1; ; attempt++) {
                HttpResponse<String> r;
                try {
                    r = http.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    boolean connected = !(e instanceof ConnectException || e instanceof HttpConnectTimeoutException);
                    if ("POST".equals(method) && connected) {
                        throw new UncheckedIOException(method + " " + path + ": no answer after the request was sent, so "
                                + "it is not repeated (the server may have stored it); check the connection to "
                                + "OpenMetadata, then run the command again", e);
                    }
                    if (attempt >= maxAttempts) {
                        throw new UncheckedIOException(method + " " + path + ": no answer after " + attempt + " attempts", e);
                    }
                    sleeper.sleep(backoff(attempt));
                    continue;
                }
                int status = r.statusCode();
                if (status >= 200 && status < 300) {
                    String text = r.body();
                    return Optional.of(text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
                }
                if (status == 404 && "GET".equals(method)) {
                    return Optional.empty();
                }
                boolean retry = !"POST".equals(method)
                        && (status == 429 || status == 502 || status == 503 || status == 504);
                if (!retry || attempt >= maxAttempts) {
                    throw new OmException(method, path, status, r.body() == null ? "" : r.body());
                }
                sleeper.sleep(wait(r, attempt));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /**
     * The wait a {@code Retry-After} header asks for, in seconds or as an HTTP date (RFC 9110 section 10.2.3), else the
     * backoff; at most 30 s. A date counts from the {@code Date} header of the answer, so the clocks of client and
     * server need not agree, and from the local clock when the answer has none.
     */
    private static long wait(HttpResponse<?> r, int attempt) {
        String after = r.headers().firstValue("Retry-After").orElse("");
        if (SECONDS.matcher(after).matches()) {
            return Math.min(MAX_WAIT_MS, Long.parseLong(after) * 1000);
        }
        Optional<Instant> until = httpDate(after);
        if (until.isEmpty()) {
            return backoff(attempt);
        }
        Instant now = r.headers().firstValue("Date").flatMap(HttpOmApi::httpDate).orElseGet(Instant::now);
        return Math.max(0, Math.min(MAX_WAIT_MS, Duration.between(now, until.get()).toMillis()));
    }

    /** An IMF-fixdate such as {@code Thu, 19 Nov 2026 12:00:00 GMT}. */
    private static Optional<Instant> httpDate(String text) {
        try {
            return Optional.of(Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(text)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static long backoff(int attempt) {
        return Math.min(MAX_WAIT_MS, 500L << Math.min(attempt, 16));
    }
}
