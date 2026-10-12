package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * OpenMetadata REST client (JDK HTTP client + Jackson; no generated SDK, so it runs on Java 17 - the official
 * Java client 2.0.5 is compiled for Java 21). Authenticates with a bot JWT; retries 429 and 5xx with backoff.
 */
public final class HttpOmApi implements OmApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final String token;
    private final HttpClient http;
    private final int maxAttempts;

    /** @param base server API root, e.g. {@code http://localhost:8585/api}; @param token bot JWT or null */
    public HttpOmApi(URI base, String token) {
        this(base, token, 5);
    }

    public HttpOmApi(URI base, String token, int maxAttempts) {
        String b = base.toString();
        this.base = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
        this.token = token;
        this.maxAttempts = maxAttempts;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = r.statusCode();
                if (status >= 200 && status < 300) {
                    String text = r.body();
                    return Optional.of(text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
                }
                if (status == 404 && "GET".equals(method)) {
                    return Optional.empty();
                }
                boolean retry = status == 429 || status == 502 || status == 503 || status == 504;
                if (!retry || attempt >= maxAttempts) {
                    throw new OmException(method, path, status, r.body() == null ? "" : r.body());
                }
                Thread.sleep(Math.min(30_000L, 500L << attempt));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
