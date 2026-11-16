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

class SegmentExtremumVectorsTest {

    private static final String NAME = "segment-extremum";

    @TestFactory
    Stream<DynamicTest> sharedVectors() throws IOException {
        if (Boolean.getBoolean("opentsx.vectors.generate")) {
            generate();
        }
        JsonNode doc = OperatorVectors.read(NAME);
        assertEquals("opentsx.testvectors.segment-extremum.v1", doc.path("schema").textValue());
        assertEquals(6, doc.path("cases").size());
        return StreamSupport.stream(doc.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest(c.path("name").textValue(), () -> SegmentVectors.verify(c)));
    }

    private static ObjectNode input(ArrayNode cases, String name, String feature, double prominence,
                                    String window, String distance, String pre, String post, double... values) {
        ObjectNode c = SegmentVectors.input(cases, name, "segment.extremum", values);
        ObjectNode p = c.putObject("params");
        p.put("feature", feature);
        p.put("prominence", prominence);
        p.put("window", window);
        p.put("distance", distance);
        p.put("pre", pre);
        p.put("post", post);
        return c;
    }

    private static void generate() throws IOException {
        ObjectNode doc = OperatorVectors.document(NAME,
                "Segmenter.extremum and StreamingSegmenter over synthetic samples. Expected segments include "
                        + "deterministic ids, timestamps, anchors, parameters and values. Batch and stream chunks "
                        + "of 1, 7 and all samples reproduce the same records. Source episode references are omitted "
                        + "because they depend on input chunk boundaries.");
        ArrayNode cases = (ArrayNode) doc.path("cases");
        input(cases, "two local maxima", "MAX", 2, "PT4S", "PT2S", "PT1S", "PT2S",
                0, 1, 5, 1, 0, 1, 6, 1, 0, 0);
        input(cases, "local minimum", "MIN", 2, "PT4S", "PT2S", "PT2S", "PT1S",
                0, -1, -5, -1, 0, 0);
        input(cases, "earliest equal peak wins inside distance", "MAX", 1, "PT2S", "PT4S", "PT1S", "PT1S",
                0, 5, 0, 5, 0, 0, 0, 0);
        input(cases, "prominence suppresses small peaks", "MAX", 3, "PT4S", "PT1S", "PT1S", "PT1S",
                0, 1, 2, 1, 0, 0);
        input(cases, "NaN neighbours and plateau", "MAX", 2, "PT6S", "PT1S", "PT2S", "PT2S",
                0, Double.NaN, 5, 5, 0, 0, 0);
        input(cases, "pending peak at end of input", "MAX", 1, "PT8S", "PT2S", "PT2S", "PT3S",
                0, 0, 1, 5, 1);
        SegmentVectors.finish(cases);
        OperatorVectors.write(NAME, doc);
    }
}
