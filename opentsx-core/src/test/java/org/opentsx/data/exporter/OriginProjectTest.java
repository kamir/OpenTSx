package org.opentsx.data.exporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OriginProjectTest {

    @TempDir
    Path dir;

    @Test
    void aProjectFolderThatCannotBeCreatedFailsNamingIt() throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "x");
        String folder = blocker.resolve("project").toString();
        OriginProject project = new OriginProject();

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> project.initBaseFolder(folder));

        assertEquals(folder + ": cannot create the project folder (" + e.getCause() + ")", e.getMessage());
    }

    @Test
    void aLineForALogThatWasNotCreatedFailsNamingTheLog() {
        OriginProject project = new OriginProject();
        project.initBaseFolder(dir.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> project.logLine("missing", "x"));

        assertEquals("logLine: " + dir + " has no log named missing; call createLogFile with that name first",
                e.getMessage());
    }
}
