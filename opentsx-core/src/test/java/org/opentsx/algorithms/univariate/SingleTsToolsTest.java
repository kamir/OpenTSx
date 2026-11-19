package org.opentsx.algorithms.univariate;

import org.junit.jupiter.api.Test;
import org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

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

    private static TimeSeriesObject series(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("hourly");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i, 1.0 + (i % 24));
        }
        return row;
    }
}
