package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal OpenMetadata 2.0.5 stand-in for tests. Every request body is validated against the official JSON schema of
 * the endpoint (from the openmetadata-spec 2.0.5 artifact), and references are checked the way the server does:
 * services, parents, tags, glossary terms, test definitions, entity links, custom properties, lineage ids.
 * Violations are answered with HTTP 400 and recorded.
 */
final class FakeOpenMetadata implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> SCHEMAS = Map.ofEntries(
            Map.entry("/v1/services/databaseServices", "api/services/createDatabaseService.json"),
            Map.entry("/v1/services/messagingServices", "api/services/createMessagingService.json"),
            Map.entry("/v1/services/pipelineServices", "api/services/createPipelineService.json"),
            Map.entry("/v1/databases", "api/data/createDatabase.json"),
            Map.entry("/v1/databaseSchemas", "api/data/createDatabaseSchema.json"),
            Map.entry("/v1/tables", "api/data/createTable.json"),
            Map.entry("/v1/topics", "api/data/createTopic.json"),
            Map.entry("/v1/pipelines", "api/data/createPipeline.json"),
            Map.entry("/v1/classifications", "api/classification/createClassification.json"),
            Map.entry("/v1/tags", "api/classification/createTag.json"),
            Map.entry("/v1/glossaries", "api/data/createGlossary.json"),
            Map.entry("/v1/glossaryTerms", "api/data/createGlossaryTerm.json"),
            Map.entry("/v1/dataQuality/testDefinitions", "api/tests/createTestDefinition.json"),
            Map.entry("/v1/dataQuality/testSuites/basic", "api/tests/createTestSuite.json"),
            Map.entry("/v1/dataQuality/testCases", "api/tests/createTestCase.json"),
            Map.entry("/v1/lineage", "api/lineage/addLineage.json"));
    private static final Pattern TYPE_PUT = Pattern.compile("^/v1/metadata/types/([0-9a-f-]{36})$");
    private static final Pattern PROFILE = Pattern.compile("^/v1/tables/([0-9a-f-]{36})/tableProfile$");
    private static final Pattern RESULT = Pattern.compile("^/v1/dataQuality/testCases/testCaseResults/(.+)$");
    private static final Pattern LINK = Pattern.compile("^<#E::table::(.+)::columns::([^:>]+)>$");

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
            // createTestCaseResult.json declares $id .../schema/tests/ although it lives in api/tests/, so its relative
            // refs leave /schema/; the second mapping resolves them to the same files
            b -> b.schemaMappers(m -> m.mapPrefix("https://open-metadata.org/schema/", "classpath:json/schema/")
                    .mapPrefix("https://open-metadata.org/", "classpath:json/schema/")));
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();
    private final HttpServer server;

    /** Entities by FQN, and by id. */
    final Map<String, ObjectNode> byFqn = new LinkedHashMap<>();
    final Map<String, ObjectNode> byId = new HashMap<>();
    final Map<String, String> typeIds = new LinkedHashMap<>();
    final Map<String, Set<String>> customProperties = new HashMap<>();
    final List<JsonNode> lineage = new ArrayList<>();
    final Map<String, List<JsonNode>> profiles = new LinkedHashMap<>();
    final Map<String, List<JsonNode>> results = new LinkedHashMap<>();
    final List<String> violations = new ArrayList<>();
    int requests;

    FakeOpenMetadata() throws IOException {
        for (String t : List.of("string", "integer", "markdown", "table", "topic", "pipeline")) {
            typeIds.put(t, UUID.nameUUIDFromBytes(("type:" + t).getBytes(StandardCharsets.UTF_8)).toString());
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", this::handle);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        requests++;
        String path = ex.getRequestURI().getRawPath().substring("/api".length());
        String method = ex.getRequestMethod();
        try {
            if (!"Bearer test-token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                reply(ex, 401, "{\"message\":\"unauthorized\"}");
                return;
            }
            if (method.equals("GET")) {
                get(ex, path);
                return;
            }
            JsonNode body = JSON.readTree(ex.getRequestBody());
            JsonNode response = switch (method) {
                case "PUT" -> put(path, body);
                case "POST" -> post(path, body);
                default -> throw new Violation("method " + method);
            };
            reply(ex, 200, JSON.writeValueAsString(response));
        } catch (Violation v) {
            violations.add(method + " " + path + ": " + v.getMessage());
            reply(ex, 400, JSON.writeValueAsString(Map.of("message", v.getMessage())));
        } catch (RuntimeException e) {
            violations.add(method + " " + path + ": server error " + e);
            reply(ex, 500, JSON.writeValueAsString(Map.of("message", String.valueOf(e))));
        }
    }

    private void get(HttpExchange ex, String path) throws IOException {
        if (path.startsWith("/v1/metadata/types/name/")) {
            String name = path.substring("/v1/metadata/types/name/".length());
            String id = typeIds.get(name);
            if (id != null) {
                reply(ex, 200, JSON.writeValueAsString(Map.of("id", id, "name", name, "fullyQualifiedName", name)));
                return;
            }
        }
        reply(ex, 404, "{\"message\":\"not found\"}");
    }

    private JsonNode put(String path, JsonNode body) {
        Matcher type = TYPE_PUT.matcher(path);
        if (type.matches()) {
            validate("type/customProperty.json", body);
            String entity = typeIds.entrySet().stream().filter(e -> e.getValue().equals(type.group(1)))
                    .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new Violation("unknown type id"));
            String propertyType = body.path("propertyType").path("id").asText();
            if (!typeIds.containsValue(propertyType) || !"type".equals(body.path("propertyType").path("type").asText())) {
                throw new Violation("unknown property type " + body.path("propertyType"));
            }
            customProperties.computeIfAbsent(entity, k -> new HashSet<>()).add(body.path("name").asText());
            return JSON.createObjectNode().put("id", type.group(1)).put("name", entity);
        }
        Matcher profile = PROFILE.matcher(path);
        if (profile.matches()) {
            validate("api/data/createTableProfile.json", body);
            ObjectNode table = byId.get(profile.group(1));
            if (table == null) {
                throw new Violation("unknown table id " + profile.group(1));
            }
            Set<String> cols = new HashSet<>();
            table.path("columns").forEach(c -> cols.add(c.path("name").asText()));
            body.path("columnProfile").forEach(c -> {
                if (!cols.contains(c.path("name").asText())) {
                    throw new Violation("profile for unknown column " + c.path("name").asText());
                }
            });
            profiles.computeIfAbsent(table.path("fullyQualifiedName").asText(), k -> new ArrayList<>()).add(body);
            return table;
        }
        String schema = SCHEMAS.get(path);
        if (schema == null) {
            throw new Violation("unsupported endpoint");
        }
        validate(schema, body);
        if (path.equals("/v1/lineage")) {
            for (String side : List.of("fromEntity", "toEntity")) {
                requireId(body.path("edge").path(side));
            }
            JsonNode pipeline = body.path("edge").path("lineageDetails").path("pipeline");
            if (!pipeline.isMissingNode()) {
                requireId(pipeline);
            }
            lineage.add(body);
            return JSON.createObjectNode();
        }
        checkReferences(path, body);
        String fqn = EntityPaths.fqnOf(path, body).orElseThrow();
        String entityType = EntityPaths.typeOf(path).orElseThrow();
        ObjectNode entity = byFqn.get(fqn);
        if (entity == null) {
            entity = ((ObjectNode) body).deepCopy();
            entity.put("id", UUID.randomUUID().toString());
        } else {
            String id = entity.path("id").asText();
            entity = ((ObjectNode) body).deepCopy();
            entity.put("id", id);
        }
        entity.put("fullyQualifiedName", fqn);
        entity.put("entityType", entityType);
        byFqn.put(fqn, entity);
        byId.put(entity.path("id").asText(), entity);
        return entity;
    }

    private JsonNode post(String path, JsonNode body) {
        Matcher m = RESULT.matcher(path);
        if (!m.matches()) {
            throw new Violation("unsupported endpoint");
        }
        validate("api/tests/createTestCaseResult.json", body);
        String fqn = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        ObjectNode tc = byFqn.get(fqn);
        if (tc == null || !"testCase".equals(tc.path("entityType").asText())) {
            throw new Violation("unknown test case " + fqn);
        }
        results.computeIfAbsent(fqn, k -> new ArrayList<>()).add(body);
        return JSON.createObjectNode();
    }

    private void checkReferences(String path, JsonNode b) {
        switch (path) {
            case "/v1/databases" -> requireFqn(b.path("service").asText(), "databaseService");
            case "/v1/topics" -> requireFqn(b.path("service").asText(), "messagingService");
            case "/v1/pipelines" -> requireFqn(b.path("service").asText(), "pipelineService");
            case "/v1/databaseSchemas" -> requireFqn(b.path("database").asText(), "database");
            case "/v1/tables" -> requireFqn(b.path("databaseSchema").asText(), "databaseSchema");
            case "/v1/tags" -> requireFqn(b.path("classification").asText(), "classification");
            case "/v1/glossaryTerms" -> requireFqn(b.path("glossary").asText(), "glossary");
            case "/v1/dataQuality/testSuites/basic" -> requireFqn(b.path("basicEntityReference").asText(), "table");
            case "/v1/dataQuality/testCases" -> {
                requireFqn(b.path("testDefinition").asText(), "testDefinition");
                Matcher l = LINK.matcher(b.path("entityLink").asText());
                if (!l.matches()) {
                    throw new Violation("entity link is not a column link: " + b.path("entityLink").asText());
                }
                ObjectNode table = requireFqn(l.group(1), "table");
                boolean found = false;
                for (JsonNode c : table.path("columns")) {
                    found |= c.path("name").asText().equals(l.group(2));
                }
                if (!found) {
                    throw new Violation("unknown column " + l.group(2));
                }
                if (!byFqn.containsKey(l.group(1) + ".testSuite")) {
                    throw new Violation("table has no basic test suite yet");
                }
                ObjectNode def = byFqn.get(b.path("testDefinition").asText());
                Set<String> params = new HashSet<>();
                def.path("parameterDefinition").forEach(p -> params.add(p.path("name").asText()));
                b.path("parameterValues").forEach(p -> {
                    if (!params.contains(p.path("name").asText())) {
                        throw new Violation("parameter " + p.path("name").asText() + " not defined by " + b.path("testDefinition").asText());
                    }
                });
            }
            default -> {
            }
        }
        // tags and glossary terms must exist
        List<JsonNode> labels = new ArrayList<>();
        b.path("tags").forEach(labels::add);
        b.path("columns").forEach(c -> c.path("tags").forEach(labels::add));
        for (JsonNode t : labels) {
            String expected = t.path("source").asText().equals("Glossary") ? "glossaryTerm" : "tag";
            requireFqn(t.path("tagFQN").asText(), expected);
        }
        // custom properties must be registered for the entity type
        String entityType = EntityPaths.typeOf(path).orElse("");
        b.path("extension").fieldNames().forEachRemaining(name -> {
            if (!customProperties.getOrDefault(entityType, Set.of()).contains(name)) {
                throw new Violation("custom property " + name + " is not defined for " + entityType);
            }
        });
    }

    private ObjectNode requireFqn(String fqn, String entityType) {
        ObjectNode e = byFqn.get(fqn);
        if (e == null || !entityType.equals(e.path("entityType").asText())) {
            throw new Violation(entityType + " " + fqn + " does not exist");
        }
        return e;
    }

    private void requireId(JsonNode ref) {
        ObjectNode e = byId.get(ref.path("id").asText());
        if (e == null || !e.path("entityType").asText().equals(ref.path("type").asText())) {
            throw new Violation("unknown entity reference " + ref);
        }
    }

    private void validate(String schemaPath, JsonNode body) {
        JsonSchema schema = schemaCache.computeIfAbsent(schemaPath,
                p -> factory.getSchema(SchemaLocation.of("classpath:json/schema/" + p)));
        // columnProfile.min/max are oneOf [number, integer]; in JSON Schema an integral number such as 0.0 matches
        // both. The server deserializes into POJOs (no schema validation) and accepts it, so this ambiguity is ignored.
        // Matched on the keyword, not on the message: getMessage() is localised, so a message-text filter silently
        // stops matching on any non-English JVM (de, fr, ja ...) and the ignored ambiguity resurfaces as a failure.
        List<ValidationMessage> errors = schema.validate(body).stream()
                .filter(m -> !("oneOf".equals(m.getType())
                        && m.getInstanceLocation().toString().matches(".*columnProfile\\[\\d+]\\.(min|max)$")
                        && isIntegralNumber(m.getInstanceNode())))
                .toList();
        if (!errors.isEmpty()) {
            throw new Violation(schemaPath + ": " + errors);
        }
    }

    /** True for a number whose value is integral, i.e. exactly the value that matches both "number" and "integer". */
    private static boolean isIntegralNumber(JsonNode n) {
        return n != null && n.isNumber() && n.canConvertToExactIntegral();
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static final class Violation extends RuntimeException {
        Violation(String message) {
            super(message);
        }
    }
}
