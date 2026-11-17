package org.opentsx.wayang;

import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsAccumulator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TsxSitePlatformTest {
    @Test
    void aRemoteStageFeedsOnlyMergedStatisticsIntoTheJavaContinuation() {
        StatsAccumulator accumulator = new StatsAccumulator(0.01);
        accumulator.add(1, 3);
        accumulator.add(2, 7);
        SeriesStats expected = accumulator.toSeriesStats("a".repeat(16), "PT10M", 0, 600000000);
        AtomicInteger calls = new AtomicInteger();
        TsxSiteStage stage = new TsxSiteStage(1, () -> {
            calls.incrementAndGet();
            return List.of(expected);
        });
        List<SeriesStats> output = new ArrayList<>();
        LocalCallbackSink<SeriesStats> sink = LocalCallbackSink.createCollectingSink(output, SeriesStats.class);
        stage.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxSitePlugin()).with(new TsxJavaPlugin())
                .execute(new WayangPlan(sink));
        assertEquals(1, calls.get());
        assertEquals(1, output.size());
        OpenTsxAvro avro = new OpenTsxAvro();
        assertArrayEquals(avro.encode(expected), avro.encode(output.get(0)));
    }

    @Test
    void aFailedSiteStageCannotFallBackToLocalExecution() {
        AtomicInteger calls = new AtomicInteger();
        TsxSiteStage stage = new TsxSiteStage(1, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("site publication failed");
        });
        LocalCallbackSink<SeriesStats> sink = LocalCallbackSink.createCollectingSink(new ArrayList<>(), SeriesStats.class);
        stage.connectTo(0, sink, 0);
        assertThrows(RuntimeException.class, () -> new WayangContext(new Configuration())
                .with(new TsxSitePlugin()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink)));
        assertEquals(1, calls.get());
    }
}
