package org.opentsx.twin.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The committed Studio page carries the export of the current scenarios, run hashes included. */
class StudioPageTest {

    private static final Path PAGE = Path.of("..", "docs", "prototypes", "smart-topics-studio.html");
    private static final String DATA_START = "const ALL = ";
    private static final String DATA_END = ";\nlet SC = null";

    @Test
    void exportCarriesTheRunHash() {
        Map<String, Object> m = StudioExport.export(new H2ChainScenario());
        List<Object> runs = new ArrayList<>();
        runs.add(m.get("reference"));
        runs.addAll((List<?>) m.get("alternatives"));
        runs.addAll((List<?>) m.get("forecasts"));
        assertEquals(28, runs.size());
        for (Object run : runs) {
            Map<?, ?> r = (Map<?, ?>) run;
            assertTrue(String.valueOf(r.get("hash")).matches("[0-9a-f]{16}"), "run " + r.get("runId") + " has no hash");
        }
    }

    @Test
    void committedPageCarriesTheExportOfTheScenarios(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("studio.json");
        StudioExport.write(Scenarios.all(), json);
        String export = Files.readString(json, StandardCharsets.UTF_8);
        String page = Files.readString(PAGE, StandardCharsets.UTF_8);
        int start = page.indexOf(DATA_START);
        int end = page.indexOf(DATA_END, start);
        assertTrue(start >= 0 && end > start, PAGE + " has no scenario data");
        String embedded = page.substring(start + DATA_START.length(), end);
        int at = firstDifference(export, embedded);
        assertEquals(-1, at, () -> PAGE + " differs from the export at character " + at + ": export "
                + around(export, at) + ", page " + around(embedded, at) + "; rebuild it with tools/build_studio.py");
    }

    private static int firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return a.length() == b.length() ? -1 : n;
    }

    private static String around(String s, int at) {
        return "\"" + s.substring(Math.max(0, at - 40), Math.min(s.length(), at + 40)) + "\"";
    }
}
