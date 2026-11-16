package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** TASK-020 A1.6: a record with a fingerprint outside the plan stops the increment; nothing is written. */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxFingerprintIntegrationTest {

    @Test
    void aSeriesStatsRecordOnTheEpisodeTopicStopsTheIncrementWithTheMessageOfTheSpecification(@TempDir Path dir)
            throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        Episode first = KafScaleFixture.scenarioW().get(0);
        SeriesStats stray = SeriesStats.newBuilder().setSeriesId(first.getSeries().getSeriesId()).setWindowType("PT10M")
                .setWindowStart(Instant.EPOCH).setWindowEnd(Instant.EPOCH).setValidCount(0).setMin(0).setMax(0)
                .setMean(0).setM2(0).build();
        k.write(k.layout.episodes(), List.of(stray));
        Path ckpt = dir.resolve("ckpt.json");

        TsxIncrementException e = assertThrows(TsxIncrementException.class, () -> TsxIncrement.run(
                k.config(ckpt, KafScaleFixture.EPISODE_ONLY),
                source -> TsxPassThroughIntegrationTest.connect(source, new TsxTopicSink(k.bootstrap, k.layout.clean()))));

        int partition = k.readAll(k.layout.episodes()).get(0).partition();
        assertEquals("record " + k.layout.episodes() + "/" + partition + "@0 has schema fingerprint e51b48188baa6606,"
                + " not in plan it version 1", e.getMessage());
        assertFalse(Files.exists(ckpt));
        assertEquals(0, k.readAll(k.layout.clean()).size());
    }
}
