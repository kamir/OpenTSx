package org.opentsx.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataQualityLocaleTest {

    @Test
    void messagesMatchTheSharedReferenceUnderAGermanDefaultLocale() throws Exception {
        JsonNode c = new ObjectMapper().readTree(Path.of("..", "docs", "datamodel-2026", "testvectors",
                "dq-result.v1.json").toFile()).path("cases").get(0);
        StatsAccumulator acc = new StatsAccumulator(c.path("sketchAlpha").doubleValue());
        int i = 0;
        for (JsonNode value : c.path("values")) acc.add(i++ * 1_000_000L, value.doubleValue());
        JsonNode expected = c.path("expect").get(1);
        JsonNode e = c.path("expectation");
        DataQuality.Expectation expectation = DataQuality.Expectation.of(e.path("min").doubleValue(),
                e.path("max").doubleValue(), Duration.ofNanos(e.path("expectedIntervalMicros").longValue() * 1000));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            DataQuality.Result result = DataQuality.evaluate(acc.toSeriesStats("synthetic-series", "PT10M", 0,
                    c.path("windowEndMicros").longValue()), expectation).get(1);
            assertEquals(expected.path("check").textValue(), result.check().name());
            assertEquals(expected.path("message").textValue(), result.message());
        } finally {
            Locale.setDefault(previous);
        }
    }
}
