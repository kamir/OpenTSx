package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.wayang.site.SignedJws;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustB1RunFixtureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Episode> W = RustB1RunFixture.firstW();

    @Test
    void usesTheUnchangedFirstThreeHundredWEpisodes() {
        assertEquals(300, W.size());
        assertEquals(100, W.stream().map(episode -> episode.getSeries().getSeriesId()).distinct().count());
        assertEquals(180_000, W.stream().mapToLong(episode -> episode.getValues().size()).sum());
        assertEquals(300, new HashSet<>(W.stream().map(Episode::getEpisodeId).toList()).size());
    }

    @Test
    void signsTheExactSharedContinuousAssignmentWithoutPersistingPrivateKeys() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-18T00:00:00Z");
        for (String name : List.of("normal", "crash-1", "crash-150", "crash-299", "unknown", "malformed", "stdin")) {
            ObjectNode test = RustB1RunFixture.metadata(name);
            ObjectNode plan = RustB1RunFixture.plan(test, "sha256:" + "a".repeat(64), now);
            JsonNode assignment = JSON.readTree(plan.path("assignmentText").textValue());
            ExecutionAssignments.validate(plan.path("assignmentText").textValue());
            assertEquals("rust", assignment.path("runtime").textValue());
            assertEquals("continuous", assignment.path("mode").textValue());
            assertEquals(test.path("partitions").intValue(), assignment.path("inputs").get(0).path("positions").size());
            assignment.path("inputs").get(0).path("positions").forEach(position -> assertFalse(position.has("to")));
            assertEquals(assignment.path("sourceDagText"), assignment.path("fragmentText"));
            assertFalse(plan.has("federation"));
            assertEquals("site", plan.path("outputs").get(0).path("cluster").textValue());
            assertEquals(now.minusSeconds(60), Instant.parse(plan.path("issued").textValue()));
            assertEquals(now.plusSeconds(3_600), Instant.parse(plan.path("expires").textValue()));
            byte[] payload = JSON.writeValueAsBytes(plan);
            assertArrayEquals(payload, SignedJws.verify(SignedJws.sign(payload, pair.getPrivate()), pair.getPublic()));
        }
    }

    @Test
    void keepsAllThreeCrashBoundariesAndTheSeparateEmptyStdinGrant() {
        for (int boundary : new int[]{1, 150, 299}) {
            JsonNode test = RustB1RunFixture.metadata("crash-" + boundary);
            assertEquals(boundary, test.path("failAfter").intValue());
            assertEquals(300 + boundary, test.path("expectedRecords").intValue());
            assertEquals(300, test.path("expectedDistinct").intValue());
            assertEquals(3, test.path("partitions").intValue());
        }
        for (String name : List.of("unknown", "malformed")) {
            JsonNode test = RustB1RunFixture.metadata(name);
            assertEquals(2, test.path("inputCount").intValue());
            assertEquals(0, test.path("expectedRecords").intValue());
            assertEquals(1, test.path("partitions").intValue());
            assertTrue(test.path("expectedError").textValue().startsWith("record " + test.path("inputTopic").textValue() + "/0@0"));
        }
        assertTrue(RustB1RunFixture.metadata("unknown").path("expectedError").textValue().endsWith(", not in plan b1-unknown version 1"));
        assertTrue(RustB1RunFixture.metadata("malformed").path("expectedError").textValue().endsWith(" is not Avro single-object encoded"));
        JsonNode stdin = RustB1RunFixture.metadata("stdin");
        assertEquals(0, stdin.path("inputCount").intValue());
        assertEquals(0, stdin.path("expectedDistinct").intValue());
        assertTrue(stdin.path("expectedError").isNull());
        assertThrows(IllegalArgumentException.class, () -> RustB1RunFixture.metadata("../normal"));
    }

    @Test
    void rejectsChangedPayloadKeyTimestampPartitionAndSchemaHeaders() {
        var produced = JavaTsxTopicSink.toRecord(new OpenTsxAvro(), "tsx.b1run.fixture.v2", W.get(0));
        byte[] key = produced.key().getBytes(StandardCharsets.UTF_8);
        byte[] payload = produced.value();
        int partition = Utils.toPositive(Utils.murmur2(key)) % 3;
        var expected = new RustB1RunFixture.Expected(W.get(0).getEpisodeId(), produced.key(), produced.timestamp(), payload, partition, 0);
        RustB1RunFixture.requireRecord(record(partition, produced.timestamp(), key, payload, headers()), expected, 3);
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.requireRecord(record(partition, produced.timestamp(), new byte[]{1}, payload, headers()), expected, 3));
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.requireRecord(record(partition, produced.timestamp(), key, new byte[]{1}, headers()), expected, 3));
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.requireRecord(record(partition, produced.timestamp() + 1, key, payload, headers()), expected, 3));
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.requireRecord(record((partition + 1) % 3, produced.timestamp(), key, payload, headers()), expected, 3));
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.requireRecord(record(partition, produced.timestamp(), key, payload, new RecordHeaders()), expected, 3));
        RecordHeaders duplicate = headers();
        duplicate.add(TopicLayout.HEADER_SCHEMA, Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.requireRecord(record(partition, produced.timestamp(), key, payload, duplicate), expected, 3));
    }

    @Test
    void refusesMissingDuplicatedOrInconsistentExpectedInputRows() {
        ObjectNode expected = JSON.createObjectNode().put("schema", "opentsx.rust-b1-run-expected.v1");
        var rows = expected.putArray("records");
        long[] offsets = new long[3];
        OpenTsxAvro avro = new OpenTsxAvro();
        for (Episode episode : W) {
            var record = JavaTsxTopicSink.toRecord(avro, "tsx.b1run.fixture.v2", episode);
            int partition = Utils.toPositive(Utils.murmur2(record.key().getBytes(StandardCharsets.UTF_8))) % 3;
            rows.addObject().put("id", episode.getEpisodeId()).put("key", record.key()).put("timestamp", record.timestamp())
                    .put("payload", HexFormat.of().formatHex(record.value())).put("inputPartition", partition).put("inputOffset", offsets[partition]++);
        }
        assertEquals(300, RustB1RunFixture.expected(expected).size());
        ObjectNode missing = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.get("records")).remove(0);
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.expected(missing));
        ObjectNode duplicate = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("records")).set(1, duplicate.get("records").get(0).deepCopy());
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.expected(duplicate));
        ObjectNode changed = expected.deepCopy();
        ((ObjectNode) changed.get("records").get(0)).put("key", "wrong");
        assertThrows(IllegalStateException.class, () -> RustB1RunFixture.expected(changed));
    }

    private static RecordHeaders headers() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(TopicLayout.HEADER_SCHEMA, Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    private static ConsumerRecord<byte[], byte[]> record(int partition, long timestamp, byte[] key, byte[] payload, RecordHeaders headers) {
        return new ConsumerRecord<>("tsx.b1run.fixture.v2", partition, 0, timestamp, TimestampType.CREATE_TIME,
                key.length, payload.length, key, payload, headers, Optional.empty());
    }
}
