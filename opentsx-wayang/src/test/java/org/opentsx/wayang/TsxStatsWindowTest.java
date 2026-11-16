package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** TASK-020 A2.3: the statistics operator on Wayang writes the sketch bytes Java and Python share. */
class TsxStatsWindowTest {

    private static final long HOUR = 3_600_000_000L;
    private static final double[] VECTOR = {0.0, 1.0, 2.5, 2.5, -3.75, 8.2, 12.0, 1e-12, 1000.0, -0.5};

    private static StatsEngine.Config config() {
        return StatsEngine.Config.defaults().withWindows(Duration.ofHours(1)).withSketchAlpha(0.01)
                .withAllowedLateness(Duration.ZERO);
    }

    private static SeriesStats firstHour(List<SeriesStats> out) {
        return out.stream().filter(s -> s.getWindowType().equals("PT1H") && s.getWindowStart().toEpochMilli() == 0)
                .findFirst().orElseThrow(() -> new AssertionError("no statistics for the first hour: " + out));
    }

    private static String hex(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return HexFormat.of().formatHex(out);
    }

    @Test
    void theSketchOfAClosedWindowHasTheBytesOfTheSharedVector() {
        Episode first = Episodes.regular(TsxCleanVectorsTest.RAW, 0, 60_000_000L, VECTOR).build();
        Episode next = Episodes.regular(TsxCleanVectorsTest.RAW, HOUR, 60_000_000L, new double[]{1.0}).build();
        TsxStatsWindow op = new TsxStatsWindow("stats", config(), new TsxState(Map.of()));
        SeriesStats s = firstHour(JavaTsxStatsWindow.run(op, List.of(first, next)));
        assertEquals("017b14ae47e17a843f020500015c0278012601ba0301024301ca0101", hex(s.getQuantileSketch()));
        assertEquals(VECTOR.length, s.getValidCount());
    }

    @Test
    void anOpenWindowContinuesInTheNextIncrementFromTheSavedState() {
        double[] a = new double[5];
        double[] b = new double[5];
        System.arraycopy(VECTOR, 0, a, 0, 5);
        System.arraycopy(VECTOR, 5, b, 0, 5);
        TsxState state = new TsxState(Map.of());
        List<SeriesStats> out = new ArrayList<>(JavaTsxStatsWindow.run(new TsxStatsWindow("stats", config(), state),
                List.of(Episodes.regular(TsxCleanVectorsTest.RAW, 0, 60_000_000L, a).build())));
        TsxState restored = new TsxState(state.snapshot());
        out.addAll(JavaTsxStatsWindow.run(new TsxStatsWindow("stats", config(), restored), List.of(
                Episodes.regular(TsxCleanVectorsTest.RAW, 5 * 60_000_000L, 60_000_000L, b).build(),
                Episodes.regular(TsxCleanVectorsTest.RAW, HOUR, 60_000_000L, new double[]{1.0}).build())));
        assertEquals("017b14ae47e17a843f020500015c0278012601ba0301024301ca0101", hex(firstHour(out).getQuantileSketch()));
    }
}
