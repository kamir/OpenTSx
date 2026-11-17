package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public interface RustWorkerClient {
    JsonNode get(RustRollout.Worker worker, String path);

    JsonNode post(RustRollout.Worker worker, String path, String body);

    final class Rejected extends RuntimeException {
        private final int status;

        public Rejected(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    static RustWorkerClient http() {
        return new RustWorkerClient() {
            private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5)).build();

            @Override
            public JsonNode get(RustRollout.Worker worker, String path) {
                return send(worker, path, null);
            }

            @Override
            public JsonNode post(RustRollout.Worker worker, String path, String body) {
                return send(worker, path, body);
            }

            private JsonNode send(RustRollout.Worker worker, String path, String body) {
                URI url = URI.create(worker.endpoint() + path);
                HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(900));
                if (body == null) builder.GET();
                else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                try {
                    HttpResponse<java.io.InputStream> answer = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                    byte[] bytes;
                    try (var input = answer.body()) { bytes = input.readNBytes(262_145); }
                    if (bytes.length > 262_144) throw new IllegalStateException("worker response exceeds its byte limit");
                    JsonNode value = RustRollout.json(new String(bytes, StandardCharsets.UTF_8));
                    if (answer.statusCode() != 200) {
                        throw new Rejected(answer.statusCode(), worker.node() + ": " + value.path("error").asText("worker refused request"));
                    }
                    return value;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("worker request interrupted", error);
                } catch (IOException error) {
                    throw new IllegalStateException("worker could not be reached: " + worker.node(), error);
                }
            }
        };
    }
}
