package org.opentsx.data.series;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class MRTTest {

    @Test
    void expandingToAnEndThatIsNotAfterTheStartFails() {
        Calendar start = at(10_000);
        TimeSeriesObject events = new TimeSeriesObject();
        events.addValuePair(5_000, 1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MRT.expand(events, start, at(10_000), 1.0, true));

        assertEquals("expand: the end 10000 ms is not after the start 10000 ms; expected an end after the start",
                e.getMessage());
    }

    @Test
    void expandingCountsTheEventsPerFieldAndWritesNothingToStandardOutput() {
        TimeSeriesObject events = new TimeSeriesObject();
        events.addValuePair(500, 1);
        events.addValuePair(1_500, 1);
        events.addValuePair(1_700, 1);
        events.addValuePair(3_500, 1);
        events.addValuePair(9_000, 1);
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        TimeSeriesObject expanded;
        try {
            expanded = MRT.expand(events, at(0), at(4_000), 1.0, false);
            MRT.getTimesOfPeaksDaysOverTS(1.0, events);
            MRT.convertDates2Messreihe(new Vector<>(List.of(1L, 2L)), "dates", "two dates");
        } finally {
            System.setOut(out);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(0.0, 1.0, 2.0, 3.0), expanded.xValues);
        assertEquals(List.of(1.0, 2.0, 0.0, 1.0), expanded.yValues);
    }

    @Test
    void expandingWithOneValuePerFieldMarksEachFieldWithAnEvent() {
        TimeSeriesObject events = new TimeSeriesObject();
        events.addValuePair(0, 1);
        events.addValuePair(1_500, 1);
        events.addValuePair(1_700, 1);
        events.addValuePair(3_999, 1);

        TimeSeriesObject expanded = MRT.expand(events, at(0), at(4_000), 1.0, true);

        assertEquals(List.of(1.0, 1.0, 0.0, 1.0), expanded.yValues);
    }

    @Test
    void anEventInThePartialFieldBeforeTheEndIsSkipped() {
        TimeSeriesObject events = new TimeSeriesObject();
        events.addValuePair(4_200, 1);

        TimeSeriesObject expanded = MRT.expand(events, at(0), at(4_500), 1.0, false);

        assertEquals(List.of(0.0, 0.0, 0.0, 0.0), expanded.yValues);
    }

    @Test
    void anEventBeforeTheStartIsRefused() {
        TimeSeriesObject events = new TimeSeriesObject();
        events.setLabel("events");
        events.addValuePair(1_500, 1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MRT.expand(events, at(2_000), at(6_000), 1.0, false));

        assertEquals("expand: the event at 1500.0 ms of events is before the start 2000 ms; expected events at or"
                + " after the start", e.getMessage());
    }

    private static Calendar at(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(millis);
        return c;
    }
}
