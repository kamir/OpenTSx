package org.opentsx.wayang;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustB1FixtureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    private static ObjectNode manifest() {
        ObjectNode fixture = JSON.createObjectNode().put("schema", "opentsx.rust-b1-placement.v1")
                .put("count", RustB1Fixture.KEYS).put("partitions", RustB1Fixture.PARTITIONS)
                .put("javaTopic", RustB1Fixture.JAVA_TOPIC).put("rustTopic", RustB1Fixture.RUST_TOPIC);
        ArrayNode rows = fixture.putArray("records");
        List<String> keys = RustB1Fixture.keys();
        for (int index = 0; index < RustB1Fixture.KEYS; index++) {
            rows.addObject().put("key", keys.get(index)).put("index", index)
                    .put("partition", index % RustB1Fixture.PARTITIONS);
        }
        return fixture;
    }

    private static ObjectNode firstRow(ObjectNode fixture) {
        return (ObjectNode) fixture.get("records").get(0);
    }

    @Test
    void completeManifestRetainsEveryExactKeyAndTypedPosition() {
        var expected = RustB1Fixture.validateManifest(manifest());
        List<String> keys = RustB1Fixture.keys();
        assertEquals(10_000, expected.size());
        assertEquals(new HashSet<>(keys), expected.keySet());
        for (int index = 0; index < RustB1Fixture.KEYS; index++) {
            assertEquals(index, expected.get(keys.get(index)).index());
            assertEquals(index % RustB1Fixture.PARTITIONS, expected.get(keys.get(index)).partition());
        }
    }

    @Test
    void fixtureKeysAreRepeatableUniqueLowerCaseHex() {
        List<String> first = RustB1Fixture.keys();
        assertEquals(first, RustB1Fixture.keys());
        assertEquals(10_000, new HashSet<>(first).size());
        for (String key : first) assertTrue(key.matches("[0-9a-f]{32}"));
    }

    @Test
    void missingRootFieldsAreRejected() {
        for (String field : List.of("schema", "count", "partitions", "javaTopic", "rustTopic", "records")) {
            ObjectNode fixture = manifest();
            fixture.remove(field);
            assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(fixture), field);
        }
    }

    @Test
    void extraRootFieldsAndCoercedInventoryNumbersAreRejected() {
        ObjectNode unknown = manifest().put("request", "extra");
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(unknown));
        for (String field : List.of("count", "partitions")) {
            int value = field.equals("count") ? RustB1Fixture.KEYS : RustB1Fixture.PARTITIONS;
            ObjectNode text = manifest().put(field, Integer.toString(value));
            ObjectNode decimal = manifest().put(field, (double) value);
            assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(text), field);
            assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(decimal), field);
        }
    }

    @Test
    void nullNonObjectAndWrongRecordInventoriesAreRejected() {
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(null));
        for (JsonNode value : List.of(JSON.getNodeFactory().nullNode(), JSON.createArrayNode(), JSON.getNodeFactory().textNode("fixture"))) {
            assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(value));
        }
        ObjectNode missing = manifest();
        ((ArrayNode) missing.get("records")).remove(0);
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(missing));
        ObjectNode extra = manifest();
        ((ArrayNode) extra.get("records")).add(firstRow(extra).deepCopy());
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(extra));
        ObjectNode notArray = manifest();
        ObjectNode rows = JSON.createObjectNode();
        for (int index = 0; index < RustB1Fixture.KEYS; index++) rows.set(Integer.toString(index), notArray.get("records").get(index));
        notArray.set("records", rows);
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(notArray));
    }

    @Test
    void missingZeroIndexOrPartitionDoesNotBecomeZero() {
        for (String field : List.of("index", "partition")) {
            ObjectNode fixture = manifest();
            firstRow(fixture).remove(field);
            assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(fixture), field);
        }
    }

    @Test
    void nullTextBooleanAndDecimalPositionsAreRejected() {
        List<JsonNode> values = List.of(JSON.getNodeFactory().nullNode(), JSON.getNodeFactory().textNode("0"), JSON.getNodeFactory().booleanNode(false), JSON.getNodeFactory().numberNode(0.5));
        for (String field : List.of("index", "partition")) {
            for (JsonNode value : values) {
                ObjectNode fixture = manifest();
                firstRow(fixture).set(field, value);
                assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(fixture), field);
            }
        }
    }

    @Test
    void outOfRangeAndOverflowingPositionsAreRejected() {
        for (String field : List.of("index", "partition")) {
            for (JsonNode value : List.of(JSON.getNodeFactory().numberNode(-1), JSON.getNodeFactory().numberNode(10_000),
                    JSON.getNodeFactory().numberNode(Integer.MAX_VALUE + 1L), JSON.getNodeFactory().numberNode(BigInteger.ONE.shiftLeft(64)))) {
                ObjectNode fixture = manifest();
                firstRow(fixture).set(field, value);
                assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(fixture), field);
            }
        }
        ObjectNode partition = manifest();
        firstRow(partition).put("partition", 12);
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(partition));
    }

    @Test
    void recordIndicesMustBeInExactSequenceWithoutDuplicates() {
        ObjectNode duplicate = manifest();
        ((ObjectNode) duplicate.get("records").get(1)).put("index", 0);
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(duplicate));
        ObjectNode reordered = manifest();
        firstRow(reordered).put("index", 1);
        ((ObjectNode) reordered.get("records").get(1)).put("index", 0);
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(reordered));
    }

    @Test
    void duplicateMissingMalformedAndNonTextKeysAreRejected() {
        List<JsonNode> keys = List.of(JSON.getNodeFactory().nullNode(), JSON.getNodeFactory().numberNode(1), JSON.getNodeFactory().textNode(""),
                JSON.getNodeFactory().textNode("a".repeat(31)), JSON.getNodeFactory().textNode("A".repeat(32)), JSON.getNodeFactory().textNode("g".repeat(32)));
        for (JsonNode value : keys) {
            ObjectNode fixture = manifest();
            firstRow(fixture).set("key", value);
            assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(fixture));
        }
        ObjectNode missing = manifest();
        firstRow(missing).remove("key");
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(missing));
        ObjectNode duplicate = manifest();
        ((ObjectNode) duplicate.get("records").get(1)).put("key", firstRow(duplicate).get("key").textValue());
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(duplicate));
    }

    @Test
    void unknownOrNonObjectRowsAreRejected() {
        ObjectNode unknown = manifest();
        firstRow(unknown).put("unexpected", true);
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(unknown));
        ObjectNode notObject = manifest();
        ((ArrayNode) notObject.get("records")).set(0, JSON.getNodeFactory().textNode("row"));
        assertThrows(IllegalStateException.class, () -> RustB1Fixture.validateManifest(notObject));
    }

    @Test
    void duplicateJsonMembersFailBeforeAnyBrokerClientIsOpened() throws Exception {
        String text = JSON.writeValueAsString(manifest());
        for (String duplicate : List.of(text.replaceFirst("\"count\":10000", "\"count\":10000,\"count\":10000"),
                text.replaceFirst("\"index\":0", "\"index\":0,\"index\":0"))) {
            Files.writeString(directory.resolve("placement.json"), duplicate);
            assertThrows(JsonProcessingException.class, () -> RustB1Fixture.main(
                    new String[]{"placement-verify", "", directory.toString()}));
        }
    }
}
