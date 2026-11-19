package org.opentsx.twin.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.Registry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The command studio-export: help on stdout, usage errors exit 2, the file is written only for a run. */
class StudioExportTest {

    private static List<Object> run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = StudioExport.run(List.of(args), Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return List.of(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpPrintsTheUsageAndWritesNoFile() {
        assertFalse(Files.exists(Path.of("--help")));
        assertEquals(List.of(0, Registry.shipped().help("studio-export"), ""), run("--help"));
        assertFalse(Files.exists(Path.of("--help")), "--help must not become the output file");
    }

    @Test
    void usageErrorsExitTwoWithOneLine() {
        assertEquals(List.of(2, "", "opentsx: studio-export: missing argument <out.json>; expected opentsx studio-export "
                + "<out.json>\n"), run());
        assertEquals(List.of(2, "", "opentsx: b.json: unexpected argument; expected opentsx studio-export <out.json>\n"),
                run("a.json", "b.json"));
        assertEquals(List.of(2, "", "opentsx: --out: unknown option of studio-export; run opentsx studio-export --help "
                + "for the options\n"), run("--out", "a.json"));
    }

    @Test
    void writesEveryScenarioAndExitsOneWhenTheFileCannotBeWritten(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("studio.json");
        assertEquals(List.of(0, "", ""), run(file.toString()));
        List<String> ids = new ArrayList<>();
        for (JsonNode s : new ObjectMapper().readTree(file.toFile()).path("scenarios")) {
            ids.add(s.path("id").asText());
        }
        assertEquals(List.of("h2-chain", "haus", "boot"), ids);

        Path dir = Files.createDirectory(tmp.resolve("dir.json"));
        assertEquals(List.of(1, "", "opentsx: java.io.FileNotFoundException: " + dir + " (Is a directory)\n"),
                run(dir.toString()));
    }
}
