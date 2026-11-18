package org.opentsx.wayang.federation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.wayang.federation.FederationFixtures.*;

class AggregateRecordTest {

    @Test void kafkaAndDeliveryKeepPayloadHeadersAndWidthKeys() {
        var inventory = inventory();
        var ten = partial(inventory, "s1", 0, 1, Double.NaN, 3);
        var hour = AggregateRecord.partial(inventory, "a-s1", 0, statistics("PT1H", 1, Double.NaN, 3));
        assertNotEquals(ten.key(), hour.key());
        assertEquals("wind|s1|" + GROUP + "|PT10M|0", ten.key());
        var producer = ten.producerRecord(TOPIC);
        var consumer = new ConsumerRecord<>(TOPIC, 0, 0, producer.timestamp(), TimestampType.CREATE_TIME, -1, -1,
                producer.key().getBytes(StandardCharsets.UTF_8), producer.value(), producer.headers(), Optional.empty());
        assertEquals(ten, AggregateRecord.fromConsumerRecord(consumer));
        var delivery = AggregateRecord.toDelivery(List.of(ten, hour));
        assertEquals(List.of(ten, hour), AggregateRecord.fromDelivery(AggregateRecord.parseDelivery(delivery.toString().getBytes(StandardCharsets.UTF_8))));
        byte[] value = ten.value();
        value[0] = 0;
        assertArrayEquals(producer.value(), ten.value());
        assertThrows(UnsupportedOperationException.class, () -> ten.headers().put("tsx-site", "other"));
    }

    @Test void changedEvidenceCutAndPayloadHashAreRejected() {
        var inventory = inventory();
        var original = partial(inventory, "s1", 0, 1);
        for (String mutation : List.of("cut", "hash", "fence", "generation", "site", "extra")) {
            ObjectNode encoded = AggregateRecord.json(original);
            ObjectNode evidence = (ObjectNode) AggregateRecord.parse(original.header("tsx-evidence").getBytes(StandardCharsets.UTF_8), 131072);
            switch (mutation) {
                case "cut" -> ((ObjectNode) evidence.path("inputs").get(0).path("positions").get(0)).put("to", "9");
                case "hash" -> evidence.put("payloadSha256", "0".repeat(64));
                case "fence" -> evidence.put("fence", "2");
                case "generation" -> evidence.put("generation", "2");
                case "site" -> evidence.put("site", "s2");
                case "extra" -> evidence.put("extra", true);
                default -> fail(mutation);
            }
            ((ObjectNode) encoded.path("headers")).put("tsx-evidence", evidence.toString());
            var changed = AggregateRecord.read(encoded);
            assertThrows(IllegalArgumentException.class, () -> inventory.validate(changed), mutation);
        }
    }

    @Test void generationFenceBindingAndScopeAreRejected() {
        var inventory = inventory();
        var original = partial(inventory, "s1", 0, 1);
        for (String field : List.of("tsx-fence", "tsx-fragment-hash", "tsx-assignment", "tsx-spec-hash")) {
            ObjectNode encoded = AggregateRecord.json(original);
            ((ObjectNode) encoded.path("headers")).put(field, field.endsWith("hash") ? "0".repeat(64) : field.equals("tsx-assignment") ? "unknown" : "2");
            assertThrows(IllegalArgumentException.class, () -> inventory.validate(AggregateRecord.read(encoded)), field);
        }
        ObjectNode stale = AggregateRecord.json(original);
        ((ObjectNode) stale.path("headers")).put("tsx-generation", "2").put("tsx-plan-version", "2");
        assertThrows(IllegalArgumentException.class, () -> inventory.validate(AggregateRecord.read(stale)));
        assertThrows(IllegalArgumentException.class, () -> inventory.validateScope(GROUP, "PT10M", 600000000, 1200000000));
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.partial(inventory, "a-s1", 0, statistics("PT2H", 1)));
    }

    @Test void malformedTransportCannotBypassBoundaryValidation() {
        var record = partial(inventory(), "s1", 0, 1);
        ObjectNode encoded = AggregateRecord.json(record);
        encoded.put("value", "AB==");
        assertEquals("aggregate value must use canonical base64", assertThrows(IllegalArgumentException.class, () -> AggregateRecord.read(encoded)).getMessage());
        encoded.put("value", "@@@");
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.read(encoded));
        encoded.put("value", "AA==");
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.read(encoded));
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.parseDelivery("{\"schema\":1,\"schema\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.parseDelivery("{} {}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.parseDelivery(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.toDelivery(java.util.Collections.nCopies(257, record)));
        var producer = record.producerRecord(TOPIC);
        RecordHeaders headers = new RecordHeaders(producer.headers());
        headers.add("tsx-site", "s1".getBytes(StandardCharsets.UTF_8));
        var duplicate = new ConsumerRecord<>(TOPIC, 0, 0, 1, TimestampType.CREATE_TIME, -1, -1,
                producer.key().getBytes(StandardCharsets.UTF_8), producer.value(), headers, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.fromConsumerRecord(duplicate));
    }

    @Test void admissionRequiresExactExpectedMembersAndCompleteSignedInventory() {
        var assignments = assignments();
        var inventory = new AggregateInventory("wind", 1, SPEC, expected(assignments), scopes());
        assertThrows(IllegalArgumentException.class, inventory::fingerprint);
        assertThrows(IllegalArgumentException.class, () -> partial(inventory, "s1", 0, 1));
        inventory.admit(assignments.get(0));
        inventory.admit(assignments.get(0));
        assertThrows(IllegalArgumentException.class, () -> inventory.admit(assignments.get(0) + " "));
        assignments.subList(1, 3).forEach(inventory::admit);
        assertEquals(inventory().fingerprint(), inventory.fingerprint());
        assertEquals(List.of("s1", "s2", "s3"), inventory.sites());
        assertThrows(IllegalArgumentException.class, () -> new AggregateInventory("wind", 1, SPEC, expected(assignments), AggregateRecord.JSON.createArrayNode()));
        var duplicate = expected(assignments);
        duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> new AggregateInventory("wind", 1, SPEC, duplicate, scopes()));
        var badScopes = scopes();
        ((ObjectNode) badScopes.get(0)).put("startMicros", "00");
        assertThrows(IllegalArgumentException.class, () -> new AggregateInventory("wind", 1, SPEC, expected(assignments), badScopes));
    }
}
