package org.opentsx.retrieval.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ExistingOnlyRetrievalTest {
    @Test
    void icebergStartupUsesExistingOnlyFactoryBeforeStartingHttp() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = RetrievalServer.run(List.of("--iceberg", "name=synthetic", "--namespace", "synthetic"), Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), server -> fail("no server may start"));
        assertEquals(1, code);
        assertEquals("opentsx: existing-only factory selected\n", err.toString(StandardCharsets.UTF_8));
    }
}
