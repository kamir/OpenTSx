package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MacroTrackerFrameTest {

    @Test
    void recordingATransformationPrintsNothing() {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
        } finally {
            System.setOut(stdout);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recordingATransformationInTheOpenTrackerPrintsNothing() throws Exception {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.init("probe");
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
                MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("b", "c", "NORMALIZE"));
            });
        } finally {
            System.setOut(stdout);
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.frame.dispose();
                MacroTrackerFrame.frame = null;
            });
            PFDRecorder.getInstance().reset("New Flow");
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }
}
