package org.opentsx.model;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards schema evolution: BACKWARD_TRANSITIVE against all released versions, and explicit versioning. */
class SchemaCompatibilityTest {

    @Test
    void currentSchemasReadDataOfAllReleasedVersions() {
        for (String version : SchemaHistory.versions()) {
            Map<String, Schema> released = SchemaHistory.schemas(version);
            for (Class<? extends SpecificRecord> type : OpenTsxAvro.TOP_LEVEL_TYPES) {
                Schema reader = SpecificData.get().getSchema(type);
                Schema writer = released.get(reader.getName());
                if (writer == null) {
                    continue; // type introduced after this version
                }
                SchemaCompatibility.SchemaPairCompatibility result =
                        SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
                assertEquals(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE, result.getType(),
                        reader.getName() + " cannot read data written with " + version + ": " + result.getDescription());
            }
        }
    }

    @Test
    void schemaChangesRequireANewHistoryVersion() throws Exception {
        List<String> versions = SchemaHistory.versions();
        String latest = versions.get(versions.size() - 1);
        Path source = Path.of("src/main/avro");
        Path frozen = Path.of("src/main/resources/schema-history", latest);
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".avsc")).toList()) {
                Path released = frozen.resolve(file.getFileName());
                assertTrue(Files.exists(released) && Files.mismatch(file, released) == -1,
                        file.getFileName() + " differs from schema-history/" + latest
                                + " - copy src/main/avro to a new schema-history version and add it to versions.txt");
            }
        }
    }
}
