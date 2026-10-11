package org.opentsx.store.iceberg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.SeriesStoreContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-language check, step 1: writes the store-contract fixture into an Iceberg warehouse (SQLite catalog) under
 * $OPENTSX_EXPORT_DIR plus expected.json (episodeId -> Avro single-object encoding). The Python test reads the table
 * with PyIceberg and must reproduce the same bytes. Runs only when OPENTSX_EXPORT_DIR is set (CI job "cross-language").
 */
@EnabledIfEnvironmentVariable(named = "OPENTSX_EXPORT_DIR", matches = ".+")
class IcebergExportForPythonTest {

    @Test
    void exportFixture() throws Exception {
        Path dir = Path.of(System.getenv("OPENTSX_EXPORT_DIR")).toAbsolutePath();
        Files.createDirectories(dir);
        List<Episode> fixture = SeriesStoreContract.fixture();
        try (IcebergSeriesStore store = IcebergSeriesStore.open(IcebergSeriesStoreTest.catalogProps(dir), "tsx")) {
            store.write(fixture);
        }
        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, String> expected = new TreeMap<>();
        for (Episode e : fixture) {
            expected.put(e.getEpisodeId(), HexFormat.of().formatHex(avro.encode(e)));
        }
        StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : expected.entrySet()) {
            json.append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"')
                    .append(++i < expected.size() ? ",\n" : "\n");
        }
        Files.writeString(dir.resolve("expected.json"), json.append("}\n"), StandardCharsets.UTF_8);
    }
}
