package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.wayang.rust.RustRollout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RustFencingDenialProbeTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @TempDir Path temporary;

    private static ObjectNode candidate() throws Exception {
        JsonNode vector = new ObjectMapper().readTree(Path.of("../docs/datamodel-2026/testvectors/execution-contracts.v1.json").toFile()).path("cases").get(0);
        String assignmentText = vector.path("input").asText();
        JsonNode assignment = RustRollout.json(assignmentText);
        ObjectNode request = JSON.objectNode().put("schema", RustRollout.INCREMENT_SCHEMA).put("assignmentText", assignmentText).put("sequence", "1");
        request.putNull("previousRequestHash");
        request.putObject("sink").put("topic", "tsx.wind.clean.v2").put("partitionCount", "3");
        String requestText = RustRollout.stringify(request);
        byte[] credential = new byte[32];
        new SecureRandom().nextBytes(credential);
        ObjectNode candidate = JSON.objectNode().put("requestText", requestText).put("phase", "prepared").put("publicationStarted", false);
        candidate.putObject("publicationGrant").put("schema", "opentsx.publication-grant.v1").put("operation", "install-first")
                .put("deployment", assignment.path("deployment").asText()).put("epoch", "1").put("node", assignment.path("node").asText())
                .put("inputTopic", assignment.path("inputs").get(0).path("topic").asText()).put("outputTopic", "tsx.wind.clean.v2")
                .put("token", HexFormat.of().formatHex(credential));
        ObjectNode prepared = candidate.putObject("prepared");
        prepared.putArray("records").addObject().put("key", "0123456789abcdef").put("timestamp", 1).putArray("payload").add(0).add(255);
        prepared.putObject("completion").putObject("receipt").put("requestHash", ExecutionAssignments.sha256(requestText))
                .put("outputRecords", 1).put("outputDigest", RustFencingDenialProbe.digest(prepared.path("records")));
        return candidate;
    }

    private Path write(ObjectNode candidate) throws Exception {
        ObjectNode payload = JSON.objectNode();
        payload.set("candidate", candidate);
        ObjectNode envelope = JSON.objectNode().put("schema", "opentsx.local-increment-checkpoint.v1")
                .put("checksum", ExecutionAssignments.sha256(RustRollout.stringify(payload)));
        envelope.set("payload", payload);
        Path path = temporary.resolve("checkpoint.json");
        Files.writeString(path, RustRollout.stringify(envelope));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }

    @Test
    void readsCapturedAuthorityAndPreparedBytesFromThePrivateJournalShape() throws Exception {
        ObjectNode candidate = candidate();
        JsonNode saved = RustFencingDenialProbe.candidate(write(candidate));
        assertEquals("prepared", saved.path("phase").asText());
        assertEquals("1", saved.path("publicationGrant").path("epoch").asText());
        assertEquals(1, saved.path("prepared").path("records").size());
    }

    @Test
    void rejectsCompletedPublishingEmptyOrTamperedSnapshotsBeforeNetworkAccess() throws Exception {
        ObjectNode original = candidate();
        for (String change : List.of("completed", "publishing", "empty", "digest", "scope", "credential")) {
            ObjectNode invalid = original.deepCopy();
            switch (change) {
                case "completed" -> invalid.put("phase", "completed");
                case "publishing" -> invalid.put("publicationStarted", true);
                case "empty" -> ((ObjectNode) invalid.path("prepared")).putArray("records");
                case "digest" -> ((ObjectNode) invalid.path("prepared").path("completion").path("receipt")).put("outputDigest", ExecutionAssignments.sha256("other"));
                case "scope" -> ((ObjectNode) invalid.path("publicationGrant")).put("outputTopic", "tsx.other.clean.v2");
                case "credential" -> ((ObjectNode) invalid.path("publicationGrant")).remove("token");
                default -> throw new IllegalStateException("unknown snapshot mutation");
            }
            Path path = write(invalid);
            assertThrows(IllegalStateException.class, () -> RustFencingDenialProbe.candidate(path));
        }
    }

    @Test
    void rejectsPublicFilesAndInvalidControlArgumentsWithoutNetworkAccess() throws Exception {
        Path path = write(candidate());
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class, () -> RustFencingDenialProbe.candidate(path));
        assertThrows(IllegalArgumentException.class, () -> RustFencingDenialProbe.main(new String[]{"activate", "http://worker:8090", "invalid"}));
        assertThrows(IllegalArgumentException.class, () -> RustFencingDenialProbe.main(new String[]{"abort", "http://worker:8090", "invalid"}));
        assertThrows(IllegalArgumentException.class, () -> RustFencingDenialProbe.main(new String[]{"prepare", "http://worker:8090", "x".repeat(65_537)}));
        write(candidate());
        assertThrows(IllegalArgumentException.class, () -> RustFencingDenialProbe.main(new String[]{"deny", "kafscale-read:39093", "http://wayang-publication:8096", path.toString(), "1", temporary.resolve("report.json").toString()}));
    }
}
