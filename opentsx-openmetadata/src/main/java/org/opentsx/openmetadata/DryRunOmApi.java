package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes every request as a numbered JSON file instead of calling OpenMetadata, and answers with plausible
 * entities (stable ids, OpenMetadata FQN rules). Lets you review exactly what would be published.
 */
public final class DryRunOmApi implements OmApi {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dir;
    private int seq;

    public DryRunOmApi(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public JsonNode put(String path, JsonNode body) {
        write("PUT", path, body);
        return answer(path, body);
    }

    @Override
    public JsonNode post(String path, JsonNode body) {
        write("POST", path, body);
        return JSON.createObjectNode();
    }

    @Override
    public Optional<JsonNode> get(String path) {
        if (path.startsWith("/v1/metadata/types/name/")) {
            String name = path.substring("/v1/metadata/types/name/".length()).split("\\?")[0];
            ObjectNode t = JSON.createObjectNode();
            t.put("id", UUID.nameUUIDFromBytes(("type:" + name).getBytes(StandardCharsets.UTF_8)).toString());
            t.put("name", name);
            t.put("fullyQualifiedName", name);
            return Optional.of(t);
        }
        return Optional.empty();
    }

    public int requests() {
        return seq;
    }

    private JsonNode answer(String path, JsonNode body) {
        ObjectNode e = body.isObject() ? ((ObjectNode) body).deepCopy() : JSON.createObjectNode();
        EntityPaths.fqnOf(path, body).ifPresent(fqn -> {
            e.put("fullyQualifiedName", fqn);
            e.put("id", UUID.nameUUIDFromBytes((EntityPaths.typeOf(path).orElse("") + ":" + fqn)
                    .getBytes(StandardCharsets.UTF_8)).toString());
        });
        return e;
    }

    private void write(String method, String path, JsonNode body) {
        String file = String.format("%04d-%s%s.json", ++seq, method, path.replaceAll("[^A-Za-z0-9]+", "_"));
        try {
            Files.writeString(dir.resolve(file), JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
