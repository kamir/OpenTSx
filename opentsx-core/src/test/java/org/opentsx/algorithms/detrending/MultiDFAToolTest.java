package org.opentsx.algorithms.detrending;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class MultiDFAToolTest {

    @TempDir
    Path dir;

    @Test
    void theFluctuationOfAllSeriesUsesTheWindowSizesOfTheLongestSeries() {
        Random random = new Random(3);
        Vector<TimeSeriesObject> rows = new Vector<>();
        for (int length : new int[]{300, 500, 400}) {
            rows.add(randomWalk(random, length));
        }
        MultiDFATool tool = new MultiDFATool();
        tool.savePath = dir + File.separator;
        List<Window> before = List.of(Window.getWindows());
        String home = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            tool.runDFA(rows, 2);
        } finally {
            System.setProperty("user.home", home);
            for (Window window : Window.getWindows()) {
                if (!before.contains(window)) {
                    window.dispose();
                }
            }
        }

        int[] s = tool.S;
        assertEquals(4, s[0]);
        assertEquals(s.length, tool.finalFS.xValues.size());
        for (int i = 0; i < s.length; i++) {
            assertEquals(Math.log10(s[i]), (Double) tool.finalFS.xValues.get(i), 1e-12);
            assertTrue(Double.isFinite((Double) tool.finalFS.yValues.get(i)), "s=" + s[i]);
        }
        assertTrue(Double.isFinite(tool.alpha));
        assertTrue(Files.exists(dir.resolve("3_dfa2.txt")));
    }

    private static TimeSeriesObject randomWalk(Random random, int length) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("walk " + length);
        double y = 0.0;
        for (int i = 0; i < length; i++) {
            y += random.nextGaussian();
            row.addValuePair(i, y);
        }
        return row;
    }
}
