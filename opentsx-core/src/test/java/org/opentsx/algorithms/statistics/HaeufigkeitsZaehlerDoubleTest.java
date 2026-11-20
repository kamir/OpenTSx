package org.opentsx.algorithms.statistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class HaeufigkeitsZaehlerDoubleTest {

    @Test
    void theNormalizedHistogramIsNamedWithTheLabelOfSetLabel() {
        HaeufigkeitsZaehlerDouble short20 = histogram(1.0, 2.0, 2.0);
        HaeufigkeitsZaehlerDouble long40 = histogram(1.0, 2.0, 2.0);
        short20.setLabel("l=20");
        long40.setLabel("l=40");

        short20.calcWS();
        long40.calcWS();

        assertEquals("l=20", short20.getHistogramNORM().getLabel());
        assertEquals("l=40", long40.getHistogramNORM().getLabel());
    }

    @Test
    void withoutSetLabelTheNormalizedHistogramIsNamedWithTheLabelAndTheCount() {
        HaeufigkeitsZaehlerDouble histogram = histogram(1.0, 2.0, 2.0);

        histogram.calcWS();

        assertEquals("HISTOGRAM/3", histogram.getHistogramNORM().getLabel());
    }

    private static HaeufigkeitsZaehlerDouble histogram(Double... values) {
        HaeufigkeitsZaehlerDouble histogram = new HaeufigkeitsZaehlerDouble();
        histogram.addData(new Vector<>(List.of(values)));
        return histogram;
    }
}
