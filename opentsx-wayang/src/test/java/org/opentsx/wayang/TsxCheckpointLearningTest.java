package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxCheckpointLearningTest {

    @Test
    void checkpointStarterCompilesRunsAndCleansItsFiles(@TempDir Path directory) throws Exception {
        JsonNode lessons = new ObjectMapper().readTree(Path.of("..", "docs", "learning", "library-operators.v1.json")
                .toFile()).path("lessons");
        String code = null;
        for (JsonNode lesson : lessons) {
            if ("library.checkpoint".equals(lesson.path("id").asText())) {
                code = lesson.path("starter").path("code").asText();
            }
        }
        assertTrue(code != null && !code.isBlank(), "missing checkpoint starter");
        StringBuilder imports = new StringBuilder();
        StringBuilder statements = new StringBuilder();
        for (String line : code.split("\n")) {
            (line.startsWith("import ") ? imports : statements).append(line).append('\n');
        }
        Path source = directory.resolve("CheckpointStarter.java");
        Files.writeString(source, imports + "public class CheckpointStarter { public static Object[] run() throws Exception {\n"
                + statements + "\nreturn new Object[]{loaded,other,path,directory};\n}}\n");
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(), source.toString()),
                diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
            var result = (Object[]) loader.loadClass("CheckpointStarter").getMethod("run").invoke(null);
            var loaded = (Optional<?>) result[0];
            var checkpoint = (TsxCheckpoint) loaded.orElseThrow();
            assertEquals(Map.of(0, 10L, 1, 7L), checkpoint.next());
            assertTrue(checkpoint.state().isEmpty());
            assertEquals(Optional.empty(), result[1]);
            Path checkpointPath = (Path) result[2];
            assertFalse(Files.exists(checkpointPath));
            assertFalse(Files.exists((Path) result[3]));
            assertTrue(TsxCheckpoint.load(checkpointPath, "a".repeat(64)).isEmpty());
        }
    }
}
