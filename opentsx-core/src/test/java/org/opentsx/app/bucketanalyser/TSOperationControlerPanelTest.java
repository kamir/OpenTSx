package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelTest {

    @TempDir
    Path home;

    @Test
    void openingAPanelPrintsNothingAndCreatesNoFolder() throws Exception {
        String original = System.getProperty("user.home");
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setProperty("user.home", home.toString());
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(TSOperationControlerPanel::new);
        } finally {
            System.setOut(stdout);
            System.setProperty("user.home", original);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(home.resolve("opentsx")));
    }
}
