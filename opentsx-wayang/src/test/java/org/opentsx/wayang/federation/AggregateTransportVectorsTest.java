package org.opentsx.wayang.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.wayang.federation.FederationFixtures.*;

class AggregateTransportVectorsTest {
    private static final Path FILE = Path.of("..", "docs", "datamodel-2026", "testvectors", "aggregate-transport.v1.json");
    private static JsonNode document;
    private static String generated;

    @BeforeAll static void load() throws Exception {
        generated = AggregateRecord.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(generate()) + "\n";
        if (Boolean.getBoolean("opentsx.aggregate.vectors.generate")) Files.writeString(FILE, generated, StandardCharsets.UTF_8);
        document = AggregateRecord.JSON.readTree(Files.readAllBytes(FILE));
        assertEquals("opentsx.testvectors.aggregate-transport.v1", document.path("schema").asText());
        assertEquals(4, document.path("cases").size());
        assertEquals(8, document.path("rejections").size());
    }

    @Test void generatorReproducesTheCommittedFileByteForByte() throws Exception {
        assertEquals(Files.readString(FILE, StandardCharsets.UTF_8), generated);
    }

    @TestFactory Stream<DynamicTest> sharedPayloads() {
        return StreamSupport.stream(document.path("cases").spliterator(), false).map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> {
            AggregateRecord record = AggregateRecord.read(c.path("record"));
            assertEquals(c.path("soeHex").asText(), HexFormat.of().formatHex(record.value()));
            assertEquals(c.path("statistics").toString(), statistics(record.statistics()).toString());
            var producer = record.producerRecord(TOPIC);
            var consumer = new ConsumerRecord<>(TOPIC, 0, 0, producer.timestamp(), TimestampType.CREATE_TIME, -1, -1,
                    producer.key().getBytes(StandardCharsets.UTF_8), producer.value(), producer.headers(), Optional.empty());
            assertEquals(record, AggregateRecord.fromConsumerRecord(consumer));
            if (!record.merged()) {
                inventory().validate(record);
                assertEquals(c.path("delivery"), AggregateRecord.toDelivery(List.of(record)));
                assertEquals(List.of(record), AggregateRecord.fromDelivery(c.path("delivery")));
                assertEquals(c.path("evidence"), AggregateRecord.parse(record.header("tsx-evidence").getBytes(StandardCharsets.UTF_8), AggregateRecord.MAX_EVIDENCE_BYTES));
            }
        }));
    }

    @TestFactory Stream<DynamicTest> sharedMetadataRejections() {
        return StreamSupport.stream(document.path("rejections").spliterator(), false).map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () ->
                assertEquals(c.path("error").asText(), rejection(document.path("cases").get(c.path("case").asInt()).path("record"), c))));
    }

    private static ObjectNode generate() throws Exception {
        var inventory = inventory();
        ObjectNode root = AggregateRecord.JSON.createObjectNode().put("schema", "opentsx.testvectors.aggregate-transport.v1")
                .put("description", "Synthetic bounded aggregate transport from the Java codec, including an empty window and incomplete/final revisions. "
                        + "These cases qualify transport bytes and metadata only. Regenerate with tools/aggregate_transport_vectors.py.");
        ObjectNode context = root.putObject("inventory").put("job", inventory.job()).put("planVersion", inventory.planVersion()).put("specHash", inventory.specHash());
        var texts = context.putArray("assignmentTexts"); assignments().forEach(texts::add);
        context.set("expectedAssignments", expected(assignments())); context.set("scopes", scopes());
        ArrayNode cases = root.putArray("cases");
        add(cases, "populated PT10M site partial", partial(inventory, "s1", 0, 1, -2, 0, Double.NaN));
        add(cases, "empty PT1H site partial", AggregateRecord.partial(inventory, "a-s1", 0, FederationFixtures.statistics("PT1H")));
        Path directory = Files.createTempDirectory("opentsx-aggregate-vectors-");
        try {
            var clock = new MutableClock();
            var coordinator = new CompletenessCoordinator(inventory, Duration.ofSeconds(5), 0.01, clock, directory.resolve("state.json"));
            assertTrue(coordinator.accept(partial(inventory, "s1", 0, 1, -2, 0, Double.NaN)).isEmpty());
            assertTrue(coordinator.accept(partial(inventory, "s2", 0, 3, 5)).isEmpty());
            clock.now = Instant.ofEpochSecond(605).plusNanos(1000);
            var incomplete = coordinator.advance(); assertEquals(1, incomplete.size());
            add(cases, "incomplete merged revision zero", incomplete.get(0));
            var complete = coordinator.accept(partial(inventory, "s3", 0, 7)); assertEquals(1, complete.size());
            add(cases, "final merged revision one", complete.get(0));
        } finally {
            try (var files = Files.walk(directory)) { for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        ArrayNode rejections = root.putArray("rejections");
        change(rejections, "missing site header", "codec", "headers", "/tsx-site", null, true, true);
        change(rejections, "noncanonical base64", "codec", "record", "/value", "AB==", false, false);
        change(rejections, "wrong window width key", "codec", "record", "/key", "wind|s1|" + GROUP + "|PT1H|0", false, false);
        change(rejections, "changed source cut", "inventory", "evidence", "/inputs/0/positions/0/to", "9", false, false);
        change(rejections, "changed payload hash", "inventory", "evidence", "/payloadSha256", "0".repeat(64), false, false);
        change(rejections, "changed fencing token", "inventory", "headers", "/tsx-fence", "2", false, false);
        change(rejections, "unknown assignment", "inventory", "headers", "/tsx-assignment", "missing", false, false);
        change(rejections, "extra evidence field", "inventory", "evidence", "/checkpointPersisted", "true", false, true);
        for (JsonNode c : rejections) ((ObjectNode) c).put("error", rejection(cases.get(0).path("record"), c));
        return root;
    }

    private static void add(ArrayNode cases, String name, AggregateRecord record) {
        ObjectNode c = cases.addObject().put("name", name).put("kind", record.merged() ? "merged" : "partial")
                .put("soeHex", HexFormat.of().formatHex(record.value()));
        c.set("record", AggregateRecord.json(record)); c.set("statistics", statistics(record.statistics()));
        if (!record.merged()) {
            c.set("delivery", AggregateRecord.toDelivery(List.of(record)));
            c.set("evidence", AggregateRecord.parse(record.header("tsx-evidence").getBytes(StandardCharsets.UTF_8), AggregateRecord.MAX_EVIDENCE_BYTES));
        }
    }

    private static ObjectNode statistics(SeriesStats s) {
        ObjectNode value = AggregateRecord.JSON.createObjectNode().put("seriesId", s.getSeriesId()).put("windowType", s.getWindowType())
                .put("windowStartMicros", Micros.of(s.getWindowStart())).put("windowEndMicros", Micros.of(s.getWindowEnd()))
                .put("validCount", s.getValidCount()).put("nanCount", s.getNanCount()).put("gapCount", s.getGapCount());
        double[] moments = {s.getMin(), s.getMax(), s.getMean(), s.getM2()};
        String[] names = {"min", "max", "mean", "m2"};
        for (int index = 0; index < names.length; index++) {
            if (Double.isFinite(moments[index])) value.put(names[index], moments[index]); else value.put(names[index], "NaN");
        }
        var buffer = s.getQuantileSketch().duplicate(); byte[] sketch = new byte[buffer.remaining()]; buffer.get(sketch);
        value.put("sketchHex", HexFormat.of().formatHex(sketch));
        if (s.getLastSeen() == null) value.putNull("lastSeenMicros"); else value.put("lastSeenMicros", Micros.of(s.getLastSeen()));
        return value;
    }

    private static void change(ArrayNode out, String name, String validation, String target, String pointer, String value, boolean remove, boolean schemaInvalid) {
        ObjectNode change = out.addObject().put("name", name).put("case", 0).put("validation", validation).put("target", target)
                .put("pointer", pointer).put("remove", remove).put("schemaInvalid", schemaInvalid);
        if (!remove) change.put("value", value);
    }

    private static String rejection(JsonNode original, JsonNode change) {
        ObjectNode record = original.deepCopy();
        JsonNode target = switch (change.path("target").asText()) {
            case "record" -> record;
            case "headers" -> record.path("headers");
            case "evidence" -> AggregateRecord.parse(record.path("headers").path("tsx-evidence").asText().getBytes(StandardCharsets.UTF_8), AggregateRecord.MAX_EVIDENCE_BYTES);
            default -> throw new IllegalArgumentException("unknown vector mutation target");
        };
        String pointer = change.path("pointer").asText(); int slash = pointer.lastIndexOf('/');
        ObjectNode parent = (ObjectNode) target.at(pointer.substring(0, slash));
        String field = pointer.substring(slash + 1);
        if (change.path("remove").asBoolean()) parent.remove(field); else parent.set(field, change.path("value").deepCopy());
        if (change.path("target").asText().equals("evidence")) ((ObjectNode) record.path("headers")).put("tsx-evidence", target.toString());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            AggregateRecord decoded = AggregateRecord.read(record);
            if (change.path("validation").asText().equals("inventory")) inventory().validate(decoded);
        });
        return error.getMessage();
    }
}
