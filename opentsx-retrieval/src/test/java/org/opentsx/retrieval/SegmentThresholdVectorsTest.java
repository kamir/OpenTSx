package org.opentsx.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentThresholdVectorsTest {

    private static final String NAME = "segment-threshold";

    @TestFactory
    Stream<DynamicTest> sharedVectors() throws IOException {
        if (Boolean.getBoolean("opentsx.vectors.generate")) {
            generate();
        }
        JsonNode doc = OperatorVectors.read(NAME);
        assertEquals("opentsx.testvectors.segment-threshold.v1", doc.path("schema").textValue());
        assertEquals(6, doc.path("cases").size());
        return StreamSupport.stream(doc.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest(c.path("name").textValue(), () -> SegmentVectors.verify(c)));
    }

    private static ObjectNode input(ArrayNode cases, String name, String direction, double enter, double exit,
                                    String pre, String post, String min, String max, double... values) {
        ObjectNode c = SegmentVectors.input(cases, name, "segment.threshold", values);
        ObjectNode p = c.putObject("params");
        p.put("direction", direction);
        p.put("enter", enter);
        p.put("exit", exit);
        p.put("pre", pre);
        p.put("post", post);
        p.put("minDuration", min);
        p.put("maxDuration", max);
        return c;
    }

    private static void generate() throws IOException {
        ObjectNode doc = OperatorVectors.document(NAME,
                "Segmenter.threshold and StreamingSegmenter over synthetic samples. Expected segments include "
                        + "deterministic ids, timestamps, anchors, parameters and values. Batch and stream chunks "
                        + "of 1, 7 and all samples reproduce the same records. Source episode references are omitted "
                        + "because they depend on input chunk boundaries.");
        ArrayNode cases = (ArrayNode) doc.path("cases");
        input(cases, "above with hysteresis, pre and post", "ABOVE", 5, 3, "PT1S", "PT2S", "PT2S", "PT0S",
                0, 1, 5, 4, 3, 2, 1, 0, 0);
        input(cases, "below with duration cap and re-entry", "BELOW", -3, -1, "PT0S", "PT1S", "PT0S", "PT3S",
                0, -4, -5, -4, -4, -5, 0, -4, -4, 0, 0);
        input(cases, "short phase discarded", "ABOVE", 5, 3, "PT0S", "PT0S", "PT3S", "PT0S",
                0, 6, 2, 0, 0);
        input(cases, "NaN inside an open phase flushed at end", "ABOVE", 5, 3, "PT1S", "PT2S", "PT1S", "PT0S",
                0, 6, Double.NaN, 6, 5);
        input(cases, "no threshold entry", "ABOVE", 5, 3, "PT0S", "PT0S", "PT0S", "PT0S",
                0, 1, Double.NaN, 4, 2);
        ObjectNode irregular = input(cases, "irregular timestamps and inclusive entry", "ABOVE", 5, 3,
                "PT1S", "PT1S", "PT1S", "PT0S", 0, 5, 4, 2, 0, 0);
        irregular.set("timestampsMicros", OperatorVectors.JSON.valueToTree(
                new long[]{0, 1_000_000, 2_500_000, 4_000_000, 4_500_000, 6_000_000}));
        SegmentVectors.finish(cases);
        OperatorVectors.write(NAME, doc);
    }
}
