package org.opentsx.algorithms.detrending;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Random;
import java.util.Vector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class MFDFAAnalyzerTest {

    @TempDir
    Path dir;

    @Test
    void theAnalysisOfAllOrdersWritesNothingToStandardOutput() throws Exception {
        Random random = new Random(5);
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("noise");
        for (int i = 0; i < 1024; i++) {
            row.addValuePair(i, random.nextGaussian());
        }
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.MFDFA2);
        dfa.setNrOfValues(1024);
        dfa.initIntervalSlog();
        OriginProject project = new OriginProject();
        project.initBaseFolder(dir.toString());
        StringBuffer calcLog = new StringBuffer();

        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, UTF_8));
        Vector<TimeSeriesObject> results;
        try {
            results = MFDFAAnalyzer.processMFDFA(row, dfa, calcLog, "_raw", project, false, false);
        } finally {
            System.setOut(out);
        }

        assertEquals("", captured.toString(UTF_8));
        assertEquals(10, results.size());
        assertEquals(10, calcLog.toString().lines().count());
    }
}
