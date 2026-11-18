package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.Micros;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.SeriesStats;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AggregateEnvelopeSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String HASH = ExecutionAssignments.sha256("bounded aggregate schema fixture");

    private static JsonSchema schema(String name) throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(ROOT.resolve(
                "docs/datamodel-2026/schemas/" + name + ".schema.json").toFile()));
    }

    private static ObjectNode evidence() {
        ObjectNode value = JSON.createObjectNode().put("schema", "opentsx.aggregate-evidence.v1").put("kind", "bounded-source-cut")
                .put("job", "wind").put("site", "s1").put("planVersion", 1).put("generation", "1")
                .put("assignment", "a-s1").put("fence", "1").put("specHash", HASH).put("fragmentHash", HASH).put("payloadSha256", HASH);
        value.putArray("inputs").addObject().put("binding", "source").put("topic", "tsx.wind.episodes.v2")
                .put("partitionCount", "1").putArray("positions").addObject().put("partition", "0").put("from", "0").put("to", "10");
        return value;
    }

    private static ObjectNode delivery() throws Exception {
        JsonNode expected = JSON.readTree(ROOT.resolve("docs/datamodel-2026/testvectors/stats-window.v1.json").toFile())
                .path("cases").get(0).path("expect").get(0);
        SeriesStats statistics = SeriesStats.newBuilder().setSeriesId("0123456789abcdef").setWindowType(expected.path("windowType").asText())
                .setWindowStart(Micros.toInstant(expected.path("windowStartMicros").asLong())).setWindowEnd(Micros.toInstant(expected.path("windowEndMicros").asLong()))
                .setValidCount(expected.path("validCount").asLong()).setNanCount(expected.path("nanCount").asLong())
                .setGapCount(expected.path("gapCount").asLong()).setMin(expected.path("min").asDouble()).setMax(expected.path("max").asDouble())
                .setMean(expected.path("mean").asDouble()).setM2(expected.path("m2").asDouble())
                .setLastSeen(Micros.toInstant(expected.path("lastSeenMicros").asLong()))
                .setQuantileSketch(ByteBuffer.wrap(HexFormat.of().parseHex(expected.path("sketchHex").asText()))).build();
        String encoded = Base64.getEncoder().encodeToString(new OpenTsxAvro().encode(statistics));
        ObjectNode value = JSON.createObjectNode().put("schema", "opentsx.aggregate-delivery.v1");
        ObjectNode record = value.putArray("records").addObject().put("key", "wind|s1|0123456789abcdef|PT10M|0").put("value", encoded);
        record.putObject("headers").put("tsx-schema", "org.opentsx.model.v2.SeriesStats").put("tsx-job", "wind").put("tsx-site", "s1")
                .put("tsx-plan-version", "1").put("tsx-revision", "0").put("tsx-generation", "1").put("tsx-assignment", "a-s1")
                .put("tsx-fence", "1").put("tsx-spec-hash", HASH).put("tsx-fragment-hash", HASH).put("tsx-evidence", evidence().toString());
        return value;
    }

    @Test void closedCutEvidenceSchemaRejectsMissingFieldsTypesAndUnboundedPositions() throws Exception {
        JsonSchema schema = schema("opentsx.aggregate-evidence.v1");
        assertTrue(schema.validate(evidence()).isEmpty());
        var required = evidence().fieldNames();
        while (required.hasNext()) {
            ObjectNode copy = evidence();
            String name = required.next();
            copy.remove(name);
            assertFalse(schema.validate(copy).isEmpty(), name);
        }
        for (String field : List.of("generation", "fence", "planVersion", "inputs")) {
            ObjectNode copy = evidence();
            copy.put(field, "invalid");
            assertFalse(schema.validate(copy).isEmpty(), field);
        }
        ObjectNode unknown = evidence().put("checkpointPersisted", true);
        assertFalse(schema.validate(unknown).isEmpty());
        ObjectNode unbounded = evidence();
        ((ObjectNode) unbounded.path("inputs").get(0).path("positions").get(0)).remove("to");
        assertFalse(schema.validate(unbounded).isEmpty());
        assertFalse(schema.validate(evidence().put("planVersion", 2147483648L)).isEmpty());
    }

    @Test void deliverySchemaRequiresAllPartialMetadataAndBoundedRecordCount() throws Exception {
        JsonSchema schema = schema("opentsx.aggregate-delivery.v1");
        assertTrue(schema.validate(delivery()).isEmpty());
        var fields = delivery().path("records").get(0).path("headers").fieldNames();
        while (fields.hasNext()) {
            ObjectNode copy = delivery();
            String field = fields.next();
            ((ObjectNode) copy.path("records").get(0).path("headers")).remove(field);
            assertFalse(schema.validate(copy).isEmpty(), field);
        }
        ObjectNode wrongRevision = delivery();
        ((ObjectNode) wrongRevision.path("records").get(0).path("headers")).put("tsx-revision", "01");
        assertFalse(schema.validate(wrongRevision).isEmpty());
        ObjectNode merged = delivery();
        ((ObjectNode) merged.path("records").get(0).path("headers")).put("tsx-final", "true");
        assertFalse(schema.validate(merged).isEmpty());
        ObjectNode excessive = delivery();
        JsonNode record = excessive.path("records").get(0).deepCopy();
        for (int i = 1; i <= 256; i++) ((com.fasterxml.jackson.databind.node.ArrayNode) excessive.path("records")).add(record.deepCopy());
        assertFalse(schema.validate(excessive).isEmpty());
    }

    @Test void sharedTransportEnvelopesMatchTheSchemas() throws Exception {
        JsonNode vectors = JSON.readTree(ROOT.resolve("docs/datamodel-2026/testvectors/aggregate-transport.v1.json").toFile());
        int checked = 0;
        for (JsonNode c : vectors.path("cases")) if (c.path("kind").asText().equals("partial")) {
            assertTrue(schema("opentsx.aggregate-delivery.v1").validate(c.path("delivery")).isEmpty(), c.path("name").asText());
            assertTrue(schema("opentsx.aggregate-evidence.v1").validate(c.path("evidence")).isEmpty(), c.path("name").asText());
            checked++;
        }
        assertEquals(2, checked);
    }

    @Test void structuralSharedMutationsAreRejectedByTheSchemas() throws Exception {
        JsonNode vectors = JSON.readTree(ROOT.resolve("docs/datamodel-2026/testvectors/aggregate-transport.v1.json").toFile());
        int checked = 0;
        for (JsonNode change : vectors.path("rejections")) if (change.path("schemaInvalid").asBoolean()) {
            JsonNode c = vectors.path("cases").get(change.path("case").asInt());
            boolean evidence = change.path("target").asText().equals("evidence");
            ObjectNode value = (evidence ? c.path("evidence") : c.path("delivery")).deepCopy();
            ObjectNode target = evidence ? value : (ObjectNode) value.path("records").get(0).path("headers");
            String field = change.path("pointer").asText().substring(1);
            if (change.path("remove").asBoolean()) target.remove(field); else target.set(field, change.path("value"));
            assertFalse(schema(evidence ? "opentsx.aggregate-evidence.v1" : "opentsx.aggregate-delivery.v1").validate(value).isEmpty(), change.path("name").asText());
            checked++;
        }
        assertEquals(2, checked);
    }
}
