package org.opentsx.retrieval.server;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExistingOnlyRetrievalTest {
    @Test
    void icebergStartupUsesExistingOnlyFactoryBeforeStartingHttp() {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> RetrievalServer.main(new String[]{"--iceberg", "name=synthetic", "--namespace", "synthetic"}));
        assertEquals("existing-only factory selected", failure.getCause().getMessage());
    }
}
