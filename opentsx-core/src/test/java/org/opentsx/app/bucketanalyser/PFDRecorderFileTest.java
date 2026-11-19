package org.opentsx.app.bucketanalyser;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PFDRecorderFileTest {

    @TempDir
    Path directory;

    private String originalTopic;
    private PFDRecorder recorder;

    @BeforeEach
    void configureRecorder() {
        originalTopic = System.getProperty("opentsx.pfd.input.topic");
        System.setProperty("opentsx.pfd.input.topic", "fixture-input");
        recorder = PFDRecorder.getInstance();
        recorder.reset("Flow \u0394 \uD83C\uDF21");
    }

    @AfterEach
    void restoreRecorder() {
        if (originalTopic == null) System.clearProperty("opentsx.pfd.input.topic");
        else System.setProperty("opentsx.pfd.input.topic", originalTopic);
        recorder.reset("New Flow");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializationFailureCannotCreateOrTruncateAnExport(boolean existing) throws Exception {
        RuntimeException failure = new IllegalStateException("Fixture serialization failure");
        recorder.addStep("NORMALIZE", new AbstractMap<>() {
            @Override
            public Set<String> keySet() {
                return Set.of("method");
            }

            @Override
            public boolean containsKey(Object key) {
                return "method".equals(key);
            }

            @Override
            public Object get(Object key) {
                return "method".equals(key) ? "center" : null;
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw failure;
            }
        });
        Path target = directory.resolve("flow.json");
        if (existing) Files.writeString(target, "Keep original flow");

        assertSame(failure, assertThrows(RuntimeException.class, () -> recorder.saveToFile(target.toString())));
        if (existing) assertEquals("Keep original flow", Files.readString(target));
        else assertFalse(Files.exists(target));
        assertEquals(existing ? List.of("flow.json") : List.of(), childNames(directory));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedUnicodeCannotCreateOrReplaceAnExport(boolean existing) throws Exception {
        recorder.reset("Invalid \uD800");
        Path target = directory.resolve("flow.json");
        if (existing) Files.writeString(target, "Keep original flow");
        String before = recorder.toJSON();

        assertThrows(CharacterCodingException.class, () -> recorder.saveToFile(target.toString()));
        if (existing) assertEquals("Keep original flow", Files.readString(target));
        else assertFalse(Files.exists(target));
        assertEquals(before, recorder.toJSON());
        assertEquals(existing ? List.of("flow.json") : List.of(), childNames(directory));
    }

    @Test
    void symbolicLinkDestinationIsRejectedWithoutChangingItsTarget() throws Exception {
        Path original = directory.resolve("original.json");
        Files.writeString(original, "Keep original flow");
        Path target = Files.createSymbolicLink(directory.resolve("flow.json"), original);
        String before = recorder.toJSON();

        IOException failure = assertThrows(IOException.class, () -> recorder.saveToFile(target.toString()));
        assertTrue(failure.getMessage().contains("symbolic link"));
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("Keep original flow", Files.readString(original));
        assertEquals(before, recorder.toJSON());
        assertEquals(List.of("flow.json", "original.json"), childNames(directory));
    }

    @Test
    void failedReplacementPreservesDirectoryAndCleansTemporaryFiles() throws Exception {
        Path target = Files.createDirectory(directory.resolve("flow.json"));
        Files.writeString(target.resolve("marker"), "Keep destination bytes");
        String before = recorder.toJSON();

        assertThrows(IOException.class, () -> recorder.saveToFile(target.toString()));
        assertEquals("Keep destination bytes", Files.readString(target.resolve("marker")));
        assertEquals(List.of("flow.json"), childNames(directory));
        assertEquals(before, recorder.toJSON());
    }

    @Test
    void parentCreationFailurePreservesExistingFileAndRecorder() throws Exception {
        Path parent = directory.resolve("parent");
        Files.writeString(parent, "Keep parent bytes");
        String before = recorder.toJSON();

        assertThrows(IOException.class, () -> recorder.saveToFile(parent.resolve("flow.json").toString()));
        assertEquals("Keep parent bytes", Files.readString(parent));
        assertEquals(List.of("parent"), childNames(directory));
        assertEquals(before, recorder.toJSON());
    }

    @Test
    void exportsExactUtf8AndCreatesMissingParentDirectories() throws Exception {
        recorder.addStep("NORMALIZE", Map.of("method", "center"));
        String before = recorder.toJSON();
        Path target = directory.resolve("nested/flow.json");

        recorder.saveToFile(target.toString());
        assertArrayEquals(before.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertEquals("Flow \u0394 \uD83C\uDF21",
                JsonParser.parseString(Files.readString(target)).getAsJsonObject().get("name").getAsString());
        assertEquals(List.of("flow.json"), childNames(target.getParent()));
        assertEquals(before, recorder.toJSON());
    }

    @Test
    void relativeDestinationCanReplaceAnExistingExportAndRetainPermissions() throws Exception {
        Path target = directory.resolve("flow.json");
        Files.writeString(target, "Old flow");
        var permissions = Files.getFileAttributeView(target, PosixFileAttributeView.class);
        var expected = PosixFilePermissions.fromString("rw-r-----");
        if (permissions != null) permissions.setPermissions(expected);
        Path relative = Path.of("").toAbsolutePath().relativize(target.toAbsolutePath());
        String before = recorder.toJSON();

        recorder.saveToFile(relative.toString());
        assertArrayEquals(before.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        if (permissions != null) assertEquals(expected, Files.getPosixFilePermissions(target));
        assertEquals(List.of("flow.json"), childNames(directory));
        assertEquals(before, recorder.toJSON());
    }

    private List<String> childNames(Path parent) throws IOException {
        try (var paths = Files.list(parent)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
