package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class TsxPlanClient {
    private final String base;
    private final String site;
    private final HttpClient client;

    public TsxPlanClient(URI endpoint, String site, SSLContext tls) {
        validate(endpoint, site);
        this.base = endpoint.toString().replaceAll("/$", "");
        this.site = site;
        this.client = client(tls);
    }

    TsxPlanClient(URI endpoint, String site, HttpClient client) {
        validate(endpoint, site);
        this.base = endpoint.toString().replaceAll("/$", "");
        this.site = site;
        this.client = java.util.Objects.requireNonNull(client);
    }

    private static void validate(URI endpoint, String site) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
                || site == null || !site.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("a pinned HTTPS plan endpoint and valid site are required");
        }
    }

    private static HttpClient client(SSLContext tls) {
        SSLParameters parameters = tls.getDefaultSSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().sslContext(tls).sslParameters(parameters)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    private String route(String suffix) { return base + "/v1/sites/" + site + "/" + suffix; }

    private static String job(String job) {
        if (job == null || !job.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid job identity");
        return job;
    }

    public JsonNode challenge(String job) { return send("GET", "conformance-challenge?job=" + job(job), null, null); }
    public JsonNode conformance(String compact) {
        if (compact == null || compact.length() > SignedJws.MAX_COMPACT_BYTES || !StandardCharsets.US_ASCII.newEncoder().canEncode(compact)) {
            throw new IllegalArgumentException("invalid signed conformance size or encoding");
        }
        return send("POST", "conformance", "application/jose", compact.getBytes(StandardCharsets.US_ASCII));
    }
    public JsonNode next(String after) { SiteJson.decimal(after, false); return send("GET", "plans?after=" + after, null, null); }
    public void ack(String sequence, JsonNode value) {
        SiteJson.decimal(sequence, true);
        send("POST", "deliveries/" + sequence + "/ack", "application/json", bounded(value, SiteJson.MAX_BYTES));
    }
    public void status(String job, JsonNode value) {
        send("POST", "jobs/" + job(job) + "/status", "application/json", bounded(value, SiteJson.MAX_BYTES));
    }
    public void aggregates(String job, JsonNode value) {
        send("POST", "jobs/" + job(job) + "/aggregates", "application/json", bounded(value, TsxPlanService.MAX_AGGREGATE_BYTES));
    }
    public void refusal(String job, JsonNode value) {
        send("POST", "jobs/" + job(job) + "/refusal", "application/json", bounded(value, SiteJson.MAX_BYTES));
    }

    private static byte[] bounded(JsonNode value, int maximum) {
        byte[] bytes = SiteJson.bytes(value);
        if (bytes.length > maximum) throw new IllegalArgumentException("request exceeds its byte limit");
        return bytes;
    }

    private JsonNode send(String method, String suffix, String contentType, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(route(suffix))).timeout(Duration.ofSeconds(30));
        if (method.equals("GET")) request.GET();
        else request.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<java.io.InputStream> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var input = answer.body()) { bytes = input.readNBytes(512 * 1024 + 1); }
            if (bytes.length > 512 * 1024) throw new IllegalStateException("plan response exceeds its byte limit");
            if (answer.statusCode() == 204) {
                if (bytes.length != 0) throw new IllegalStateException("acknowledgement response is not empty");
                return null;
            }
            JsonNode value = SiteJson.parse(bytes, 512 * 1024);
            if (answer.statusCode() != 200) throw new TsxPlanService.HttpFailure(answer.statusCode(), value.path("message").asText("plan service refused request"));
            return value;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("plan request interrupted", error);
        } catch (IOException error) { throw new IllegalStateException("authenticated plan service could not be reached", error); }
    }
}
