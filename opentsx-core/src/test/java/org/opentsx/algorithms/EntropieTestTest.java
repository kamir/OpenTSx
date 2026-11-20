package org.opentsx.algorithms;

import org.junit.jupiter.api.Test;
import org.opentsx.app.ContextRecorder;
import org.opentsx.app.bucketanalyser.MacroTrackerFrame;
import org.opentsx.data.series.TimeSeriesObject;

import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.Vector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class EntropieTestTest {

    @Test
    void theCorrelationOfTwoSeriesIsReturnedAndNothingIsPrinted() throws Exception {
        TimeSeriesObject first = new TimeSeriesObject();
        TimeSeriesObject second = new TimeSeriesObject();
        for (int i = 0; i < 40; i++) {
            first.addValue(Math.sin(i / 3.0));
            second.addValue(2.0 * Math.sin(i / 3.0) + 1.0);
        }
        TimeSeriesObject cc = new TimeSeriesObject();
        Method calc = EntropieTest.class.getDeclaredMethod("calcTEandMI", TimeSeriesObject.class,
                TimeSeriesObject.class, int.class, String.class, String.class, TimeSeriesObject.class, int.class);
        calc.setAccessible(true);
        boolean scatterplot = EntropieTest.SCATTERPLOT;
        boolean singleDebug = EntropieTest.singleDebug;
        EntropieTest.SCATTERPLOT = false;
        EntropieTest.singleDebug = false;
        Object[] line = new Object[1];
        String out;
        try {
            out = stdoutOf(() -> line[0] = calc.invoke(null, first, second, 50, "r0", "r1", cc, 40));
        } finally {
            EntropieTest.SCATTERPLOT = scatterplot;
            EntropieTest.singleDebug = singleDebug;
        }

        assertEquals("", out);
        assertEquals(1.0, (Double) cc.yValues.get(0), 1e-9);
        assertEquals("50\t" + String.valueOf(cc.yValues.get(0)).replace('.', ','), line[0]);
    }

    @Test
    void theDfaOfTheComponentsPrintsNothing() throws Exception {
        Random random = new Random(5);
        TimeSeriesObject walk = new TimeSeriesObject();
        walk.setLabel("walk");
        double y = 0.0;
        for (int i = 0; i < 512; i++) {
            y = y + random.nextGaussian();
            walk.addValue(y);
        }
        Vector<TimeSeriesObject> rows = new Vector<>(List.of(walk));
        Method dfa = EntropieTest.class.getDeclaredMethod("applyDFA", Vector.class, String.class);
        dfa.setAccessible(true);
        boolean doDFA = EntropieTest.doDFA;
        List<Window> before = List.of(Window.getWindows());
        EntropieTest.doDFA = true;
        String out;
        try {
            out = stdoutOf(() -> dfa.invoke(null, rows, "components"));
        } finally {
            EntropieTest.doDFA = doDFA;
            for (Window window : Window.getWindows()) {
                if (!before.contains(window)) {
                    window.dispose();
                }
            }
        }

        assertEquals("", out);
    }

    @Test
    void theHeaderOfMainNamesTheColumnsOfItsRowsWithTheirSeparator() throws Exception {
        String experiment = EntropieTest.label_of_EXPERIMENT;
        double samplingRate = EntropieTest.samplingRate;
        boolean doDFA = EntropieTest.doDFA;
        boolean scatterplot = EntropieTest.SCATTERPLOT;
        boolean autoSave = EntropieTest.SCATTERPLOT_AUTO_SAVE;
        boolean singleDebug = EntropieTest.singleDebug;
        MacroTrackerFrame tracker = MacroTrackerFrame.frame;
        StringBuffer context = ContextRecorder.sb;
        ContextRecorder.sb = new StringBuffer();
        List<Window> before = List.of(Window.getWindows());
        String out;
        try {
            out = stdoutOf(() -> EntropieTest.main(new String[0]));
        } finally {
            EntropieTest.label_of_EXPERIMENT = experiment;
            EntropieTest.samplingRate = samplingRate;
            EntropieTest.doDFA = doDFA;
            EntropieTest.SCATTERPLOT = scatterplot;
            EntropieTest.SCATTERPLOT_AUTO_SAVE = autoSave;
            EntropieTest.singleDebug = singleDebug;
            MacroTrackerFrame.frame = tracker;
            ContextRecorder.sb = context;
            for (Window window : Window.getWindows()) {
                if (!before.contains(window)) {
                    window.dispose();
                }
            }
        }

        String[] lines = out.split("\n");
        assertEquals(5996, lines.length);
        for (int i = 1; i < lines.length; i++) {
            assertEquals(lines[0].split("\t", -1).length, lines[i].split("\t", -1).length, lines[0] + " | " + lines[i]);
        }
        assertEquals("length\tbins\tcc", lines[0]);
        assertTrue(lines[1].startsWith("5\t50\t"), lines[1]);
    }

    interface Action {
        void run() throws Exception;
    }

    private static String stdoutOf(Action action) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(out);
        }
        return captured.toString(UTF_8);
    }
}
