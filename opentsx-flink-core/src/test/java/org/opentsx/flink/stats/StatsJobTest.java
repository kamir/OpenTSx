package org.opentsx.flink.stats;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.SeriesStatsLog;
import org.opentsx.stats.StatsEngine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsJobTest {

    private static final EngineSpec SPEC = EngineSpec.defaults();

    @Test
    void flinkJobComputesWhatTheEngineComputes() throws Exception {
        List<Episode> episodes = StatsData.episodes("parity", Duration.ofHours(1), 2);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        DataStream<StatsInput> inputs = env.fromData(StatsData.inputs(episodes), TypeInformation.of(StatsInput.class));
        List<StatsRecord> records = new ArrayList<>();
        StatsJob.statistics(inputs, SPEC, Duration.ZERO).executeAndCollect().forEachRemaining(records::add);

        List<SeriesStats> expected = StatsData.direct(episodes, SPEC);
        assertTrue(expected.size() > 0);
        assertEquals(expected, StatsData.decode(records));
    }

    @Test
    void restoreFromACheckpointCountsEveryValueOnce() throws Exception {
        List<StatsInput> inputs = StatsData.inputs(StatsData.episodes("restore", Duration.ofHours(1), 2));
        int half = inputs.size() / 2;

        List<StatsRecord> uninterrupted;
        try (var h = harness()) {
            h.open();
            for (StatsInput in : inputs) {
                h.processElement(new StreamRecord<>(in));
            }
            h.processWatermark(Long.MAX_VALUE);
            uninterrupted = h.extractOutputValues();
        }

        List<StatsRecord> resumed = new ArrayList<>();
        OperatorSubtaskState checkpoint;
        try (var h = harness()) {
            h.open();
            for (StatsInput in : inputs.subList(0, half)) {
                h.processElement(new StreamRecord<>(in));
            }
            checkpoint = h.snapshot(1L, 0L);
            resumed.addAll(h.extractOutputValues());
        }
        try (var h = harness()) {
            h.initializeState(checkpoint);
            h.open();
            for (StatsInput in : inputs.subList(half, inputs.size())) {
                h.processElement(new StreamRecord<>(in));
            }
            h.processWatermark(Long.MAX_VALUE);
            resumed.addAll(h.extractOutputValues());
        }

        List<SeriesStats> expected = StatsData.decode(uninterrupted);
        assertEquals(expected, StatsData.sorted(SeriesStatsLog.latest(StatsData.decode(resumed))));
        List<SeriesStats> global = expected.stream().filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).toList();
        assertEquals(inputs.stream().map(StatsInput::getSeriesId).distinct().count(), global.size());
        global.forEach(g -> assertEquals(3600, g.getValidCount() + g.getNanCount(), "every value counted exactly once"));
    }

    @Test
    void globalTimerEmitsChangedSeriesOnly() throws Exception {
        List<StatsInput> inputs = StatsData.inputs(StatsData.episodes("global", Duration.ofMinutes(10), 1));
        try (var h = harness(60_000)) {
            h.open();
            h.setProcessingTime(0);
            h.processElement(new StreamRecord<>(inputs.get(0)));
            h.setProcessingTime(60_000);
            long globals = StatsData.decode(h.extractOutputValues()).stream()
                    .filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).count();
            assertEquals(1, globals, "one GLOBAL record after the first interval");
            h.setProcessingTime(180_000);
            assertEquals(globals, StatsData.decode(h.extractOutputValues()).stream()
                    .filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).count(), "an idle series emits nothing");
        }
    }

    @Test
    void undecodableInputIsSkipped() throws Exception {
        try (var h = harness()) {
            h.open();
            h.processElement(new StreamRecord<>(new StatsInput("x", true, new byte[]{1, 2, 3})));
            h.processWatermark(Long.MAX_VALUE);
            assertTrue(h.extractOutputValues().isEmpty());
        }
    }

    @Test
    void optionsMirrorTheProcessor() {
        StatsJob.Options o = StatsJob.parse(new String[]{"--kafscale", "localhost:9092", "--domain", "solar",
                "--windows", "PT1H", "--once", "true"});
        assertEquals("tsx.solar.episodes.v2", o.layout().episodes());
        assertEquals(List.of(Duration.ofHours(1)), o.spec().windows());
        assertTrue(o.once());
        assertEquals(Duration.ofSeconds(30), o.globalEvery());
        assertThrows(IllegalArgumentException.class, () -> StatsJob.parse(new String[]{"--domain", "wind"}));
        assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--checkpoint", "stats.ckpt"}));
        assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--windws", "PT1H"}));
        assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--once"}));
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness() throws Exception {
        return harness(0);
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness(long globalEveryMs)
            throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new StatsFunction(SPEC, globalEveryMs)),
                StatsInput::getSeriesId, Types.STRING);
    }
}
