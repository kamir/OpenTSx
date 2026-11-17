package org.opentsx.data.series;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectTextTest {

    @Test
    void splitPadsMissingValuesWithTheMeanAndSaysSo() {
        TimeSeriesObject[] parts = series(2.0, 4.0).split(4, 1);
        assertEquals(List.of(2.0, 4.0, 3.0, 3.0), parts[0].yValues);
        assertEquals("# 2 missing values replaced with mean=3.0.\n", parts[0].sbComments.toString());
    }

    @Test
    void splitOfAnFftSeriesPadsMissingValuesWithTheMeanAndSaysSo() {
        TimeSeriesObject[] parts = TimeSeriesObjectFFT.convertToMessreiheFFT(series(2.0, 4.0)).split(4, 1);
        assertEquals(List.of(2.0, 4.0, 3.0, 3.0), parts[0].yValues);
        assertEquals("# 2 missing values replaced with mean=3.0.\n", parts[0].sbComments.toString());
    }

    @Test
    void shiftBeyondTheLengthFailsNamingTheOffset() {
        TimeSeriesObject row = series(1.0, 2.0, 3.0);
        Exception left = assertThrows(Exception.class, () -> row.shift(-4));
        assertEquals("shift: offset -4 exceeds the 3 values; expected an offset from -3 to 3", left.getMessage());
        Exception right = assertThrows(Exception.class, () -> row.shift(4, 0.0));
        assertEquals("shift: offset 4 exceeds the 3 values; expected an offset from -3 to 3", right.getMessage());
    }

    @Test
    void cutFromStartNamesTheRemovedValuesInTheLabel() {
        TimeSeriesObject row = series(1.0, 2.0, 3.0);
        row.setLabel("s");
        assertEquals("s( -1 values )", row.cutFromStart(1).getLabel());
    }

    @Test
    void averageOfRowsNamesTheNumberOfSeriesInTheLabel() throws Exception {
        Vector<TimeSeriesObject> rows = new Vector<>(List.of(series(1.0), series(3.0)));
        assertEquals("mean of 2 series", TimeSeriesObject.calcAveragOfRows(rows).getLabel());
    }

    @Test
    void binningRecordsTheNumberOfBlocks() {
        TimeSeriesObject binned = series(1.0, 2.0, 3.0, 4.0, 5.0).setBinningX_sum(2);
        String status = binned.getStatus().toString();
        assertTrue(status.contains("#[BINNING] bin=2\tRest=1\tblocks=2"), status);
    }

    private static TimeSeriesObject series(double... values) {
        TimeSeriesObject row = new TimeSeriesObject();
        for (int i = 0; i < values.length; i++) {
            row.addValuePair(i + 1, values[i]);
        }
        return row;
    }
}
