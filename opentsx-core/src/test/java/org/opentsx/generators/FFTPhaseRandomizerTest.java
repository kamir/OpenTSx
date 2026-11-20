package org.opentsx.generators;

import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class FFTPhaseRandomizerTest {

    @Test
    void thereIsNoMainThatOpensEmptyCharts() {
        assertThrows(NoSuchMethodException.class, () -> FFTPhaseRandomizer.class.getMethod("main", String[].class));
    }

    @Test
    void phaseRandomizingWritesNothingToStandardOutputOrError() throws Exception {
        TimeSeriesObject noise = FFTPhaseRandomizer.getRandomRow(256);
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, UTF_8);
        try {
            System.setOut(sink);
            System.setErr(sink);
            FFTPhaseRandomizer.getPhaseRandomizedRow(noise, false, true, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
            FFTPhaseRandomizer.getPhaseRandomizedRow(noise, false, true, 0,
                    FFTPhaseRandomizer.MODE_multiply_phase_with_random_value);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals("", captured.toString(UTF_8));
    }

    @Test
    void anUnknownModeIsRefusedNamingTheModesThatAreAllowed() throws Exception {
        TimeSeriesObject noise = FFTPhaseRandomizer.getRandomRow(16);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FFTPhaseRandomizer.getPhaseRandomizedRow(noise, false, false, 0, 2));

        assertEquals("mode: 2 is not allowed; expected 0 (MODE_shuffle_phase) or 1 (MODE_multiply_phase_with_random_value)",
                e.getMessage());
    }
}
