package org.opentsx.twin.demo;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The reason of a boat lever, part of the run id, writes doubles the same way on Java 17 and 21. */
class BoatDemoTest {

    @Test
    void leverReasonWritesDoublesTheSameWayOnEveryJvm() {
        assertEquals("wp=2.0E23", BoatDemo.set(Instant.parse("2026-07-04T00:00:00Z"), "solar", "wp", 2e23).reason());
    }
}
