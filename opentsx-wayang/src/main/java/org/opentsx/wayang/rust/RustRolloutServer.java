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
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class RustRolloutServer {
    static final int MAX_BYTES = 1_048_576;
    private final RustRollout rollout;
    private final RustPublicationGateway publication;
    private final byte[] runToken;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private record Job(String hash, CompletableFuture<JsonNode> result) { }

    public RustRolloutServer(RustRollout rollout) { this(rollout, null, null); }

    public RustRolloutServer(RustRollout rollout, RustPublicationGateway publication, String runToken) {
        if ((publication == null) != (runToken == null)) throw new IllegalArgumentException("publication and run token must be configured together");
        if (runToken != null && !runToken.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("invalid rollout control credential");
        this.rollout = rollout;
        this.publication = publication;
        this.runToken = runToken == null ? null : runToken.getBytes(StandardCharsets.US_ASCII);
    }

    public JsonNode request(String method, String path, String body) {
        if (method.equals("GET") && path.equals("/v1/capabilities")) return rollout.capabilities();
        if (!method.equals("POST")) throw new IllegalArgumentException("unsupported rollout route");
        JsonNode input = RustRollout.json(body);
        if (path.equals("/v1/switch")) return rollout.replace(input);
        if (path.equals("/v1/retire")) return rollout.retire(input);
        if (path.equals("/v1/plan")) {
            boolean continuous = input.has("profile");
            if (!input.isObject() || input.size() != (continuous ? 4 : 3) || !input.path("deployment").isTextual()
                    || !input.path("dagText").isTextual() || !input.path("createdAtMicros").isTextual()
                    || (continuous && !input.path("profile").asText().equals("continuous"))) throw new IllegalArgumentException("expected deployment, dagText, createdAtMicros and optional continuous profile");
            if (continuous) return rollout.planContinuous(input.path("deployment").asText(), input.path("dagText").asText(), input.path("createdAtMicros").asText());
            return rollout.plan(input.path("deployment").asText(), input.path("dagText").asText(), input.path("createdAtMicros").asText());
        }
        if (path.equals("/v1/next")) {
            if (input.size() != 2 || !input.path("plan").isObject() || !input.path("result").isObject()) {
                throw new IllegalArgumentException("expected the previous plan and completed result");
            }
            return rollout.next(input.path("plan"), input.path("result"));
        }
        if (!path.equals("/v1/execute")) throw new IllegalArgumentException("unsupported rollout route");
        String deployment = input.path("deployment").asText();
        String hash = input.path("planHash").asText();
        if (!hash.matches("[0-9a-f]{64}") || !deployment.matches("[a-z0-9][a-z0-9-]{0,62}")
                || !hash.equals(RustRollout.hash(input))) throw new IllegalArgumentException("invalid physical plan identity");
        Job candidate = new Job(hash, new CompletableFuture<>());
        boolean continuous = input.path("schema").asText().equals(RustRollout.CONTINUOUS_SCHEMA);
        String key = continuous ? deployment + ":" + input.path("sequence").asText() : deployment;
        Job job;
        synchronized (jobs) {
            job = jobs.get(key);
            if (job == null) {
                if (jobs.size() >= 64) throw new IllegalStateException("local rollout journal capacity reached");
                jobs.put(key, candidate);
                job = candidate;
            }
        }
        if (!job.hash().equals(hash)) throw new IllegalArgumentException("deployment identity conflicts with another physical plan");
        if (job == candidate) {
            try { job.result().complete(rollout.execute(input)); }
            catch (RuntimeException error) {
                job.result().completeExceptionally(error);
                jobs.remove(key, job);
                throw error;
            }
        }
        try { return job.result().join(); }
        catch (CompletionException error) { throw new IllegalStateException("deployment requires reconciliation", error.getCause()); }
        finally { if (continuous) jobs.remove(key, job); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (runToken != null && !authenticated(exchange.getRequestHeaders().getFirst("X-OpenTSx-Run-Token"))) {
                respond(exchange, 403, error("rollout control authorization required"));
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                respond(exchange, 413, error("rollout request exceeds " + MAX_BYTES + " bytes"));
                return;
            }
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("rollout routes do not accept query fields");
            respond(exchange, 200, request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        } catch (RustPublicationGateway.Refused error) {
            respond(exchange, 409, error(error.getMessage()));
        } catch (IllegalArgumentException | CharacterCodingException error) {
            respond(exchange, 400, error(error.getMessage()));
        } catch (RuntimeException error) {
            respond(exchange, 503, error(runToken == null ? "deployment requires reconciliation: " + rootMessage(error) : "deployment requires reconciliation"));
        } finally { exchange.close(); }
    }

    private boolean authenticated(String value) {
        return value != null && value.length() <= 256 && MessageDigest.isEqual(runToken, value.getBytes(StandardCharsets.US_ASCII));
    }

    private void publish(HttpExchange exchange) throws IOException {
        try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || !publication.accepts(authorization.substring(7))) {
                respond(exchange, 403, error("publication authority was revoked"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/v1/publish")
                    || exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("unsupported publication route");
            byte[] bytes = exchange.getRequestBody().readNBytes(RustPublicationGateway.MAX_BYTES + 1);
            if (bytes.length > RustPublicationGateway.MAX_BYTES) {
                respond(exchange, 413, error("publication exceeds its byte limit"));
                return;
            }
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            respond(exchange, 200, publication.publish(authorization.substring(7), RustRollout.json(body)));
        } catch (RustPublicationGateway.Refused error) {
            respond(exchange, 403, error(error.getMessage()));
        } catch (IllegalArgumentException | CharacterCodingException error) {
            respond(exchange, 400, error("invalid publication document"));
        } catch (RuntimeException error) {
            respond(exchange, 503, error("publication outcome requires reconciliation"));
        } finally { exchange.close(); }
    }

    HttpServer listen(InetSocketAddress address, boolean data) throws IOException {
        if (data && publication == null) throw new IllegalArgumentException("publication is not configured");
        HttpServer server = HttpServer.create(address, 16);
        server.createContext("/", data ? this::publish : this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, data ? "publication-http" : "rollout-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
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
            System.out.println("RustRolloutServer --listen host:port --bootstrap host:port --catalog directory --worker node=http://host:port [--worker node=http://host:port] [--publication-listen host:port --publication-state directory --publication-bootstrap host:port --run-token-file file]");
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
            } else if (!java.util.Set.of("--listen", "--bootstrap", "--catalog", "--publication-listen", "--publication-state", "--publication-bootstrap", "--run-token-file").contains(args[index])
                    || options.putIfAbsent(args[index], args[index + 1]) != null) throw new IllegalArgumentException("unknown or duplicate rollout argument");
        }
        if (!options.keySet().containsAll(java.util.Set.of("--listen", "--bootstrap", "--catalog")) || (options.size() != 3 && options.size() != 7)) throw new IllegalArgumentException("missing rollout argument");
        String[] listen = options.get("--listen").split(":", 2);
        if (listen.length != 2) throw new IllegalArgumentException("listen must be host:port");
        String bootstrap = options.get("--bootstrap");
        boolean fenced = options.size() == 7;
        RustPublicationGateway publication = fenced ? new RustPublicationGateway(Path.of(options.get("--publication-state")), options.get("--publication-bootstrap")) : null;
        String token = null;
        if (fenced) {
            Path file = Path.of(options.get("--run-token-file"));
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 257) throw new IllegalArgumentException("invalid rollout credential file");
            token = Files.readString(file, StandardCharsets.US_ASCII).strip();
        }
        RustRollout rollout = new RustRollout(bootstrap, Path.of(options.get("--catalog")), workers, RustWorkerClient.http(), RustRollout.kafka(bootstrap, fenced ? options.get("--publication-bootstrap") : bootstrap), publication);
        RustRolloutServer service = new RustRolloutServer(rollout, publication, token);
        InetSocketAddress address = new InetSocketAddress(listen[0], Integer.parseInt(listen[1]));
        if (fenced && address.getAddress().isAnyLocalAddress()) throw new IllegalArgumentException("fenced control must bind its dedicated interface");
        HttpServer server = service.listen(address, false);
        HttpServer data;
        if (fenced) {
            String[] endpoint = options.get("--publication-listen").split(":", 2);
            if (endpoint.length != 2) throw new IllegalArgumentException("publication listen must be host:port");
            data = service.listen(new InetSocketAddress(endpoint[0], Integer.parseInt(endpoint[1])), true);
        } else data = null;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            if (data != null) data.stop(1);
            if (publication != null) try { publication.close(); } catch (IOException error) { System.err.println("cannot close publication storage"); }
        }));
        System.out.println("Wayang Rust rollout service is listening");
    }
}
