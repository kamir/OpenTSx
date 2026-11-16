package org.opentsx.wayang;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** TASK-020 A1.3: the source and the sink build their clients with the KafScale rules (S3.1, S6). */
class TsxClientSettingsTest {

    @Test
    void theSourceJoinsNoGroupAndCommitsNothing() {
        Properties p = JavaTsxTopicSource.consumerProperties("127.0.0.1:1");
        assertFalse(p.containsKey("group.id"), p.toString());
        assertEquals("false", String.valueOf(p.get("enable.auto.commit")));
    }

    @Test
    void theSinkHasNoIdempotenceAllAcksAndOneRequestInFlight() {
        Properties p = JavaTsxTopicSink.producerProperties("127.0.0.1:1");
        assertEquals("false", String.valueOf(p.get("enable.idempotence")));
        assertEquals("all", String.valueOf(p.get("acks")));
        assertEquals("1", String.valueOf(p.get("max.in.flight.requests.per.connection")));
    }
}
