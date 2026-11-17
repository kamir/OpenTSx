package org.opentsx.wayang.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class AggregateInventory {

    public record Scope(String groupId, String windowType, long startMicros, long endMicros) { }

    private static final List<String> FIELDS = List.of("assignment", "site", "node", "generation", "fence", "fragmentHash");
    private final String job;
    private final int planVersion;
    private final String specHash;
    private final Map<String, JsonNode> expected = new TreeMap<>();
    private final Map<String, String> texts = new TreeMap<>();
    private final Map<String, JsonNode> admitted = new TreeMap<>();
    private final Map<String, Scope> scopes = new TreeMap<>();

    public AggregateInventory(String job, int planVersion, String specHash, JsonNode expectedAssignments, JsonNode declaredScopes) {
        AggregateRecord.id(job);
        AggregateRecord.hash(specHash);
        if (planVersion < 1) throw new IllegalArgumentException("aggregate plan version must be positive");
        if (expectedAssignments == null || !expectedAssignments.isArray() || expectedAssignments.isEmpty() || expectedAssignments.size() > 128) {
            throw new IllegalArgumentException("expected aggregate assignments are required");
        }
        this.job = job;
        this.planVersion = planVersion;
        this.specHash = specHash;
        Set<String> sites = new java.util.HashSet<>();
        for (JsonNode member : expectedAssignments) {
            AggregateRecord.fields(member, Set.copyOf(FIELDS));
            ObjectNode normalized = AggregateRecord.JSON.createObjectNode();
            for (String field : FIELDS) normalized.put(field, AggregateRecord.text(member, field));
            for (String field : List.of("assignment", "site", "node")) AggregateRecord.id(normalized.path(field).textValue());
            if (!Integer.toString(planVersion).equals(normalized.path("generation").textValue())) {
                throw new IllegalArgumentException("aggregate generation must equal plan version");
            }
            AggregateRecord.decimal(normalized.path("fence").textValue(), true);
            AggregateRecord.hash(normalized.path("fragmentHash").textValue());
            if (!sites.add(normalized.path("site").textValue())) throw new IllegalArgumentException("bounded federation permits one assignment per site");
            if (expected.putIfAbsent(normalized.path("assignment").textValue(), normalized) != null) {
                throw new IllegalArgumentException("duplicate expected aggregate assignment");
            }
        }
        if (declaredScopes == null || !declaredScopes.isArray() || declaredScopes.isEmpty() || declaredScopes.size() > 1024) {
            throw new IllegalArgumentException("aggregate scopes are required and limited to 1024");
        }
        for (JsonNode entry : declaredScopes) {
            AggregateRecord.fields(entry, Set.of("groupId", "windowType", "startMicros", "endMicros"));
            Scope scope = new Scope(AggregateRecord.text(entry, "groupId"), AggregateRecord.text(entry, "windowType"),
                    AggregateRecord.signedDecimal(AggregateRecord.text(entry, "startMicros")),
                    AggregateRecord.signedDecimal(AggregateRecord.text(entry, "endMicros")));
            org.opentsx.wayang.TsxStatsMerge.merge(scope.groupId(), scope.windowType(), scope.startMicros(), scope.endMicros(), 0.01, List.of());
            if (!java.time.Duration.parse(scope.windowType()).toString().equals(scope.windowType())) {
                throw new IllegalArgumentException("aggregate scope duration must be canonical");
            }
            if (scopes.putIfAbsent(scopeKey(scope.groupId(), scope.windowType(), scope.startMicros()), scope) != null) {
                throw new IllegalArgumentException("duplicate aggregate scope");
            }
        }
    }

    public synchronized void admit(String assignmentText) {
        ExecutionAssignments.validate(assignmentText);
        JsonNode assignment = AggregateRecord.parse(assignmentText.getBytes(java.nio.charset.StandardCharsets.UTF_8), 65_536);
        String id = AggregateRecord.text(assignment, "assignment");
        JsonNode member = expected.get(id);
        if (member == null) throw new IllegalArgumentException("unknown aggregate assignment");
        if (!job.equals(assignment.path("deployment").textValue()) || !specHash.equals(assignment.path("specHash").textValue())
                || !"java".equals(assignment.path("runtime").textValue()) || !"bounded".equals(assignment.path("mode").textValue())) {
            throw new IllegalArgumentException("aggregate assignment context does not match");
        }
        for (String field : FIELDS) if (!member.path(field).equals(assignment.path(field))) {
            throw new IllegalArgumentException("aggregate assignment identity does not match: " + field);
        }
        String previous = texts.get(id);
        if (previous != null) {
            if (!previous.equals(assignmentText)) throw new IllegalArgumentException("aggregate assignment admission conflicts");
            return;
        }
        List<String> all = new ArrayList<>(texts.values());
        all.add(assignmentText);
        ExecutionAssignments.validateSet(all);
        texts.put(id, assignmentText);
        admitted.put(id, assignment.deepCopy());
    }

    public String job() { return job; }
    public int planVersion() { return planVersion; }
    public String specHash() { return specHash; }
    public List<String> sites() { return expected.values().stream().map(n -> n.path("site").textValue()).sorted().toList(); }
    public List<Scope> scopes() { return List.copyOf(scopes.values()); }

    private static String scopeKey(String group, String type, long start) { return group + "|" + type + "|" + start; }

    public void validateScope(String group, String type, long start, long end) {
        Scope scope = scopes.get(scopeKey(group, type, start));
        if (scope == null || scope.endMicros() != end) throw new IllegalArgumentException("aggregate window is outside the signed scopes");
    }

    public synchronized JsonNode assignment(String id) {
        JsonNode assignment = admitted.get(id);
        if (assignment == null) throw new IllegalArgumentException("aggregate assignment is not admitted");
        return assignment.deepCopy();
    }

    public synchronized String fingerprint() {
        if (admitted.size() != expected.size()) throw new IllegalArgumentException("every expected aggregate assignment must be admitted");
        ObjectNode value = AggregateRecord.JSON.createObjectNode().put("job", job).put("planVersion", planVersion).put("specHash", specHash);
        ArrayNode members = value.putArray("expectedAssignments");
        expected.values().forEach(members::add);
        ObjectNode assignments = value.putObject("assignmentTexts");
        texts.forEach(assignments::put);
        var windows = value.putArray("scopes");
        scopes.values().forEach(s -> windows.addObject().put("groupId", s.groupId()).put("windowType", s.windowType())
                .put("startMicros", Long.toString(s.startMicros())).put("endMicros", Long.toString(s.endMicros())));
        return ExecutionAssignments.sha256(value.toString());
    }

    public void validate(AggregateRecord record) {
        if (record == null) throw new IllegalArgumentException("aggregate record is required");
        if (record.merged()) throw new IllegalArgumentException("a merged aggregate cannot be a site partial");
        var statistics = record.statistics();
        validateScope(statistics.getSeriesId(), statistics.getWindowType(), org.opentsx.model.Micros.of(statistics.getWindowStart()),
                org.opentsx.model.Micros.of(statistics.getWindowEnd()));
        if (!job.equals(record.header("tsx-job")) || !Integer.toString(planVersion).equals(record.header("tsx-plan-version"))
                || !Integer.toString(planVersion).equals(record.header("tsx-generation")) || !specHash.equals(record.header("tsx-spec-hash"))) {
            throw new IllegalArgumentException("stale or incompatible aggregate generation");
        }
        JsonNode assignment = assignment(record.header("tsx-assignment"));
        for (String field : List.of("site", "fence", "fragmentHash")) {
            String header = field.equals("fragmentHash") ? "tsx-fragment-hash" : "tsx-" + field;
            if (!assignment.path(field).textValue().equals(record.header(header))) throw new IllegalArgumentException("aggregate assignment binding does not match");
        }
        JsonNode evidence = AggregateRecord.parse(record.header("tsx-evidence").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                AggregateRecord.MAX_EVIDENCE_BYTES);
        AggregateRecord.fields(evidence, Set.of("schema", "kind", "job", "site", "planVersion", "generation", "assignment",
                "fence", "specHash", "fragmentHash", "inputs", "payloadSha256"));
        if (!"opentsx.aggregate-evidence.v1".equals(evidence.path("schema").textValue())
                || !"bounded-source-cut".equals(evidence.path("kind").textValue())
                || !evidence.path("planVersion").isIntegralNumber() || !evidence.path("planVersion").canConvertToInt()
                || evidence.path("planVersion").intValue() != planVersion
                || !job.equals(evidence.path("job").textValue()) || !specHash.equals(evidence.path("specHash").textValue())) {
            throw new IllegalArgumentException("aggregate evidence context does not match");
        }
        for (String field : List.of("site", "generation", "assignment", "fence", "fragmentHash")) {
            if (!assignment.path(field).equals(evidence.path(field))) throw new IllegalArgumentException("aggregate evidence identity does not match");
        }
        if (!assignment.path("inputs").equals(evidence.path("inputs"))) throw new IllegalArgumentException("aggregate evidence cut is incomplete or changed");
        if (!AggregateRecord.sha256(record.value()).equals(evidence.path("payloadSha256").textValue())) {
            throw new IllegalArgumentException("aggregate evidence payload hash does not match");
        }
    }
}
