package org.opentsx.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.retrieval.pattern.Paa;
import org.opentsx.retrieval.pattern.Sax;
import org.opentsx.retrieval.pattern.ZNorm;

import java.io.IOException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaxWordVectorsTest {

    private static final String NAME = "sax-word";

    @TestFactory
    Stream<DynamicTest> sharedVectors() throws IOException {
        if (Boolean.getBoolean("opentsx.vectors.generate")) {
            generate();
        }
        JsonNode doc = OperatorVectors.read(NAME);
        assertEquals("opentsx.testvectors.sax-word.v1", doc.path("schema").textValue());
        assertEquals(7, doc.path("cases").size());
        return StreamSupport.stream(doc.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest(c.path("name").textValue(), () ->
                        assertEquals(c.path("expect").toString(), evaluate(c).toString())));
    }

    private static ObjectNode evaluate(JsonNode c) {
        double[] input = OperatorVectors.numbers(c.path("values"));
        double[] z = c.path("normalize").booleanValue() ? ZNorm.of(input) : input;
        int w = c.path("w").intValue();
        int a = c.path("a").intValue();
        ObjectNode out = OperatorVectors.JSON.createObjectNode();
        out.set("normalized", OperatorVectors.values(z));
        out.set("paa", OperatorVectors.values(Paa.of(z, w)));
        out.set("breakpoints", OperatorVectors.values(Sax.breakpoints(a)));
        out.put("letters", Sax.letters(z, w, a));
        out.put("word", Sax.word(z, w, a));
        return out;
    }

    private static void input(ArrayNode cases, String name, boolean normalize, int w, int a, double... values) {
        ObjectNode c = cases.addObject();
        c.put("name", name);
        c.put("normalize", normalize);
        c.put("w", w);
        c.put("a", a);
        c.set("values", OperatorVectors.values(values));
        c.set("expect", evaluate(c));
    }

    private static void generate() throws IOException {
        ObjectNode doc = OperatorVectors.document(NAME,
                "Sax.word, Sax.letters, Sax.breakpoints and Paa.of, optionally after ZNorm.of. "
                        + "SAX methods take already normalized values. Cases cover fractional PAA boundaries, "
                        + "constant and NaN values, breakpoint equality and alphabet sizes 2 and 26. "
                        + "All inputs are synthetic.");
        ArrayNode cases = (ArrayNode) doc.path("cases");
        input(cases, "normalized rising signal", true, 4, 4, 1, 2, 3, 4, 5, 6, 7, 8);
        input(cases, "fractional PAA boundaries", true, 3, 8, 1, 4, 2, 9, 3, 8, 5);
        input(cases, "constant signal", true, 2, 4, 7, 7, 7, 7);
        input(cases, "NaN samples skipped by PAA", false, 3, 4, Double.NaN, Double.NaN, -1, 1, Double.NaN, 2);
        input(cases, "all NaN values normalize to zero", true, 2, 2, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        input(cases, "breakpoint equality chooses the upper letter", false, 3, 2, -1, 0, 1);
        input(cases, "largest alphabet", false, 5, 26, -3, -1, 0, 1, 3);
        OperatorVectors.write(NAME, doc);
    }
}
