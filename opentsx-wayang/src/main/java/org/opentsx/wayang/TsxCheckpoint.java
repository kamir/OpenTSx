package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where an increment stopped: the next offset per partition of the input topic, and the document and plan it belongs
 * to (S6.3 of docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md). The file format is private to Track A.
 *
 * @param next partition to the first offset not yet processed
 */
public record TsxCheckpoint(String job, int planVersion, String specHash, String topic, Map<Integer, Long> next) {

    static final String SCHEMA = "opentsx.wayang.checkpoint.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The checkpoint of {@code path} if it belongs to the same document; empty when there is none or when it was
     * written for another {@code specHash} (S6.3, rule 4).
     *
     * @throws IllegalStateException when the file exists but cannot be read (S6.3, rule 5: fail closed)
     */
    public static Optional<TsxCheckpoint> load(Path path, String specHash) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            JsonNode n = JSON.readTree(path.toFile());
            if (!SCHEMA.equals(n.path("schema").asText())) {
                throw new IllegalStateException("checkpoint " + path + " is no " + SCHEMA);
            }
            if (!specHash.equals(n.path("specHash").asText())) {
                return Optional.empty();
            }
            Map<Integer, Long> next = new TreeMap<>();
            n.path("next").fields().forEachRemaining(f -> next.put(Integer.parseInt(f.getKey()), f.getValue().asLong()));
            return Optional.of(new TsxCheckpoint(n.path("job").asText(), n.path("planVersion").asInt(),
                    n.path("specHash").asText(), n.path("topic").asText(), next));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("checkpoint " + path + " cannot be read: " + e.getMessage(), e);
        }
    }

    /** Writes to a temporary file, forces it to disk, then renames it over the previous checkpoint. */
    public void write(Path path) {
        ObjectNode n = JSON.createObjectNode().put("schema", SCHEMA).put("job", job).put("planVersion", planVersion)
                .put("specHash", specHash).put("topic", topic);
        ObjectNode positions = n.putObject("next");
        new TreeMap<>(next).forEach((p, o) -> positions.put(Integer.toString(p), o));
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.write(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(n));
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write checkpoint " + path, e);
        }
    }
}
