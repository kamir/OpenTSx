package org.opentsx.analysistools;

import org.junit.jupiter.api.Test;
import org.opentsx.analysistools.utils.FitbereichLogScale;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class FluctuationFunktionTest {

    @Test
    void fittingAlphaReturnsTheSlopeAndPrintsNothing() {
        FluctuationFunktion fs = new FluctuationFunktion(line(9, 0.7), "probe");
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, UTF_8);
        double alpha;
        double beta;
        try {
            System.setOut(sink);
            System.setErr(sink);
            alpha = fs.calcAlpha(new FitbereichLogScale(0.5, 1.5));
            beta = fs.calcBeta(new FitbereichLogScale(0.5, 1.5));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals(0.7, alpha, 1e-12);
        assertEquals(0.4, beta, 1e-12);
        assertEquals("", captured.toString(UTF_8));
    }

    @Test
    void anEmptyFluctuationFunctionFailsNamingTheSeries() {
        FluctuationFunktion fs = new FluctuationFunktion(new TimeSeriesObject(), "probe");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> fs.calcAlpha(new FitbereichLogScale(0.5, 1.5)));

        assertEquals("probe: alpha cannot be fitted in [0.5, 1.5]; expected a fluctuation function with values",
                e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void fewerThanTwoValuesInTheFitRangeGiveNaN() {
        FluctuationFunktion fs = new FluctuationFunktion(line(9, 0.7), "probe");

        assertTrue(Double.isNaN(fs.calcAlpha(new FitbereichLogScale(5.0, 6.0))));
    }

    @Test
    void valuesWithOneXInTheFitRangeGiveNaN() {
        TimeSeriesObject sameX = new TimeSeriesObject();
        sameX.addValuePair(1.0, 2.0);
        sameX.addValuePair(1.0, 3.0);
        sameX.addValuePair(2.0, 4.0);
        FluctuationFunktion fs = new FluctuationFunktion(sameX, "probe");

        assertTrue(Double.isNaN(fs.calcAlpha(new FitbereichLogScale(0.9, 1.1))));
    }

    private static TimeSeriesObject line(int n, double slope) {
        TimeSeriesObject fs = new TimeSeriesObject();
        for (int i = 0; i < n; i++) {
            double x = 0.25 * i;
            fs.addValuePair(x, slope * x + 1.0);
        }
        return fs;
    }
}
