package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class RustRolloutServer {
    private static final int MAX_BYTES = 262_144;
    private final RustRollout rollout;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private record Job(String hash, CompletableFuture<JsonNode> result) { }

    public RustRolloutServer(RustRollout rollout) { this.rollout = rollout; }

    public JsonNode request(String method, String path, String body) {
        if (method.equals("GET") && path.equals("/v1/capabilities")) return rollout.capabilities();
        if (!method.equals("POST")) throw new IllegalArgumentException("unsupported rollout route");
        JsonNode input = RustRollout.json(body);
        if (path.equals("/v1/plan")) {
            if (!input.isObject() || input.size() != 3 || !input.path("deployment").isTextual()
                    || !input.path("dagText").isTextual() || !input.path("createdAtMicros").isTextual()) throw new IllegalArgumentException("expected deployment, dagText and createdAtMicros strings");
            return rollout.plan(input.path("deployment").asText(), input.path("dagText").asText(), input.path("createdAtMicros").asText());
        }
        if (!path.equals("/v1/execute")) throw new IllegalArgumentException("unsupported rollout route");
        String deployment = input.path("deployment").asText();
        String hash = input.path("planHash").asText();
        if (!hash.matches("[0-9a-f]{64}") || !deployment.matches("[a-z0-9][a-z0-9-]{0,62}")
                || !hash.equals(RustRollout.hash(input))) throw new IllegalArgumentException("invalid physical plan identity");
        Job candidate = new Job(hash, new CompletableFuture<>());
        Job job;
        synchronized (jobs) {
            job = jobs.get(deployment);
            if (job == null) {
                if (jobs.size() >= 64) throw new IllegalStateException("local rollout journal capacity reached");
                jobs.put(deployment, candidate);
                job = candidate;
            }
        }
        if (!job.hash().equals(hash)) throw new IllegalArgumentException("deployment identity conflicts with another physical plan");
        if (job == candidate) {
            try { job.result().complete(rollout.execute(input)); }
            catch (RuntimeException error) {
                job.result().completeExceptionally(error);
                jobs.remove(deployment, job);
                throw error;
            }
        }
        try { return job.result().join(); }
        catch (CompletionException error) { throw new IllegalStateException("deployment requires reconciliation", error.getCause()); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                respond(exchange, 413, error("rollout request exceeds 262144 bytes"));
                return;
            }
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("rollout routes do not accept query fields");
            respond(exchange, 200, request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        } catch (IllegalArgumentException | CharacterCodingException error) {
            respond(exchange, 400, error(error.getMessage()));
        } catch (RuntimeException error) {
            respond(exchange, 503, error("deployment requires reconciliation: " + rootMessage(error)));
        } finally { exchange.close(); }
    }

    private static JsonNode error(String message) {
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.put("error", message);
        return result;
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void respond(HttpExchange exchange, int status, JsonNode value) throws IOException {
        byte[] bytes = RustRollout.stringify(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("RustRolloutServer --listen host:port --bootstrap host:port --catalog directory --worker node=http://host:port [--worker node=http://host:port]");
            return;
        }
        Map<String, String> options = new HashMap<>();
        var workers = new ArrayList<RustRollout.Worker>();
        if (args.length % 2 != 0) throw new IllegalArgumentException("expected named rollout arguments; use --help");
        for (int index = 0; index < args.length; index += 2) {
            if (args[index].equals("--worker")) {
                String[] parts = args[index + 1].split("=", 2);
                if (parts.length != 2) throw new IllegalArgumentException("worker must be node=http://host:port");
                workers.add(new RustRollout.Worker(parts[0], parts[1]));
            } else if (!java.util.Set.of("--listen", "--bootstrap", "--catalog").contains(args[index])
                    || options.putIfAbsent(args[index], args[index + 1]) != null) throw new IllegalArgumentException("unknown or duplicate rollout argument");
        }
        if (options.size() != 3) throw new IllegalArgumentException("missing rollout argument");
        String[] listen = options.get("--listen").split(":", 2);
        if (listen.length != 2) throw new IllegalArgumentException("listen must be host:port");
        String bootstrap = options.get("--bootstrap");
        RustRollout rollout = new RustRollout(bootstrap, Path.of(options.get("--catalog")), workers, RustWorkerClient.http(), RustRollout.kafka(bootstrap));
        RustRolloutServer service = new RustRolloutServer(rollout);
        HttpServer server = HttpServer.create(new InetSocketAddress(listen[0], Integer.parseInt(listen[1])), 16);
        server.createContext("/", service::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        server.start();
        System.out.println("Wayang Rust rollout service is listening");
    }
}
