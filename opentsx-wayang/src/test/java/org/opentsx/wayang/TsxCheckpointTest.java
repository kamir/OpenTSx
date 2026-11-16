package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S6.3 of the specification: the checkpoint belongs to one document and fails closed. */
class TsxCheckpointTest {

    @Test
    void aWrittenCheckpointReadsBackAndLeavesNoTemporaryFile(@TempDir Path dir) {
        Path f = dir.resolve("ckpt.json");
        TsxCheckpoint c = new TsxCheckpoint("job", 3, "a".repeat(64), "tsx.d.episodes.v2", Map.of(0, 10L, 2, 7L));
        c.write(f);
        assertEquals(Optional.of(c), TsxCheckpoint.load(f, "a".repeat(64)));
        assertFalse(Files.exists(dir.resolve("ckpt.json.tmp")));
    }

    @Test
    void aCheckpointOfAnotherDocumentIsNotUsed(@TempDir Path dir) {
        Path f = dir.resolve("ckpt.json");
        new TsxCheckpoint("job", 1, "a".repeat(64), "t", Map.of(0, 5L)).write(f);
        assertTrue(TsxCheckpoint.load(f, "b".repeat(64)).isEmpty());
    }

    @Test
    void anUnreadableCheckpointFailsInsteadOfStartingOver(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("ckpt.json");
        Files.writeString(f, "{ not json");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TsxCheckpoint.load(f, "a".repeat(64)));
        assertTrue(e.getMessage().startsWith("checkpoint " + f + " cannot be read"), e.getMessage());
    }
}
