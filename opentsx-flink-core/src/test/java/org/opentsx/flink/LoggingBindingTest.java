package org.opentsx.flink;

import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LoggingBindingTest {

    @Test
    void slf4jLogsThroughLog4j2() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        assertFalse(factory instanceof NOPLoggerFactory, "SLF4J found no provider and logs to NOP");
        assertEquals("org.apache.logging.slf4j.Log4jLoggerFactory", factory.getClass().getName());
    }
}
