package org.opentsx.algorithms.univariate;

import org.junit.jupiter.api.Test;
import org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleTsToolsTest {

    final IOException failure = new IOException("disk full");

    final Writer failing = new Writer() {
        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            throw failure;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @Test
    void aDfaResultThatCannotBeWrittenStopsTheTool() {
        SingleTsDFATool tool = new SingleTsDFATool(new String[]{"in", "label", "false", "false"});
        assertSame(failure, assertThrows(IOException.class, () -> tool.processReihe(failing, series(400), null)));
    }

    @Test
    void aPeakResultThatCannotBeWrittenStopsTheTool() {
        SingleTsPeakFilterTool tool = new SingleTsPeakFilterTool();
        assertSame(failure, assertThrows(IOException.class,
                () -> tool.processReihe(failing, series(400), new StringWriter())));
    }

    @Test
    void aDfaThatFailsStopsTheToolInsteadOfPrintingAStackTrace() {
        SingleTsDFATool tool = new SingleTsDFATool(new String[]{"in", "label", "true", "false"});
        StringWriter out = new StringWriter();
        assertThrows(IllegalArgumentException.class, () -> tool.processReihe(out, new TimeSeriesObject(), null));
        assertEquals("", out.toString());
    }

    @Test
    void aRisCollectorThatCannotBeShownStopsTheTool() {
        ReturnIntervallStatistik2 collector = SingleTsRISTool.risCollector;
        SingleTsRISTool.risCollector = new ReturnIntervallStatistik2("RISdata") {
            @Override
            public void showData() throws Exception {
                throw failure;
            }
        };
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SingleTsRISTool().finish());
            assertEquals("finish: the RIS of RISdata cannot be shown (java.io.IOException: disk full)", e.getMessage());
            assertSame(failure, e.getCause());
        } finally {
            SingleTsRISTool.risCollector = collector;
        }
    }

    @Test
    void theRisToolCollectsTheIntervalsBetweenTheValuesOfASeriesAndWritesTheirMean() throws Exception {
        ReturnIntervallStatistik2 collector = SingleTsRISTool.risCollector;
        int binning = ReturnIntervallStatistik2.binning;
        int scale = ReturnIntervallStatistik2.scale;
        ReturnIntervallStatistik2.binning = 400;
        ReturnIntervallStatistik2.scale = 8;
        try {
            SingleTsRISTool tool = new SingleTsRISTool();
            tool.init();
            StringWriter out = new StringWriter();

            tool.processReihe(out, values("events", 0.0, 2.0, 5.0, 9.0), null);

            assertEquals("events\t\t3.0\t\n", out.toString());
            ReturnIntervallStatistik2 collected = SingleTsRISTool.risCollector;
            collected.calcMessreihen();
            assertEquals(List.of(0.67, 1.01, 1.33), rounded(collected.mrVerteilungSkaliert.xValues));
            assertEquals(List.of(0.33, 0.33, 0.33), rounded(collected.mrVerteilungSkaliert.yValues));
        } finally {
            SingleTsRISTool.risCollector = collector;
            ReturnIntervallStatistik2.binning = binning;
            ReturnIntervallStatistik2.scale = scale;
        }
    }

    @Test
    void theRisToolWritesNanAndCollectsNothingForASeriesWithoutAnInterval() throws Exception {
        ReturnIntervallStatistik2 collector = SingleTsRISTool.risCollector;
        try {
            SingleTsRISTool tool = new SingleTsRISTool();
            tool.init();
            StringWriter out = new StringWriter();

            tool.processReihe(out, values("flat", 3.0, 3.0, 3.0), null);
            tool.processReihe(out, values("single", 7.0), null);

            assertEquals("flat\t\tNaN\t\nsingle\t\tNaN\t\n", out.toString());
            SingleTsRISTool.risCollector.calcMessreihen();
            assertEquals(List.of(), SingleTsRISTool.risCollector.mrVerteilungSkaliert.xValues);
        } finally {
            SingleTsRISTool.risCollector = collector;
        }
    }

    @Test
    void theRisToolWritesNanAndCollectsNothingForASeriesWithoutValues() throws Exception {
        ReturnIntervallStatistik2 collector = SingleTsRISTool.risCollector;
        try {
            SingleTsRISTool tool = new SingleTsRISTool();
            tool.init();
            StringWriter out = new StringWriter();

            tool.processReihe(out, values("empty"), null);

            assertEquals("empty\t\tNaN\t\n", out.toString());
            SingleTsRISTool.risCollector.calcMessreihen();
            assertEquals(List.of(), SingleTsRISTool.risCollector.mrVerteilungSkaliert.xValues);
        } finally {
            SingleTsRISTool.risCollector = collector;
        }
    }

    @Test
    void theRisToolRefusesASeriesWithAValueBelowTheValueBeforeIt() {
        ReturnIntervallStatistik2 collector = SingleTsRISTool.risCollector;
        try {
            SingleTsRISTool tool = new SingleTsRISTool();
            tool.init();
            StringWriter out = new StringWriter();

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> tool.processReihe(out, values("falling", 0.0, 5.0, 2.0), null));

            assertEquals("_addData: time 2 of R1 is 2.0, before time 1 at 5.0; expected times that do not decrease",
                    e.getMessage());
            assertEquals("", out.toString());
        } finally {
            SingleTsRISTool.risCollector = collector;
        }
    }

    private static TimeSeriesObject values(String label, double... values) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel(label);
        for (int i = 0; i < values.length; i++) {
            row.addValuePair(i, values[i]);
        }
        return row;
    }

    private static List<Double> rounded(List<?> values) {
        List<Double> rounded = new ArrayList<>();
        for (Object value : values) {
            rounded.add(Math.round((Double) value * 100.0) / 100.0);
        }
        return rounded;
    }

    private static TimeSeriesObject series(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("hourly");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i, 1.0 + (i % 24));
        }
        return row;
    }
}
