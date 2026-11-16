package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxStatusRecordTest {

    @Test
    void anInitialStatusHasExplicitUnknownWatermarkAndEmptyProgress() {
        TsxIncrement.Config config = new TsxIncrement.Config("unused", "job", 1, "hash", "episodes",
                Set.of("fingerprint"), "earliest", Path.of("unused.checkpoint"));
        JsonNode record = TsxStatus.record(config, "running", 0, null);
        assertTrue(record.has("watermark"), "watermark must be explicit even before the first input");
        assertTrue(record.path("watermark").isNull());
        assertTrue(record.path("waitingFor").isArray());
        assertEquals(0, record.path("waitingFor").size());
        assertEquals(0, record.path("waitingForCount").asInt());
        assertTrue(record.has("skippedBlocks"));
        assertEquals(0, record.path("skippedBlocks").asLong());
        assertFalse(record.has("error"));
    }
}
