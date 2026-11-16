package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.opentsx.model.v2.AnalysisResult;
import org.opentsx.model.v2.Provenance;
import org.opentsx.model.v2.ResultCurve;
import org.opentsx.model.v2.ResultMatrix;
import org.opentsx.model.v2.ResultScalar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisResultTest {

    private final OpenTsxAvro avro = new OpenTsxAvro();

    @Test
    void canonicalFormAndResultIdMatchSharedVectors() {
        JsonNode cases = TestVectors.load("result-id.json");
        assertEquals(9, cases.size());
        for (JsonNode v : cases) {
            String method = v.get("method").asText();
            String version = v.get("methodVersion").asText();
            Map<String, Object> params = typed(v.get("params"));
            List<String> inputs = TestVectors.strings(v.get("inputEpisodeIds"));
            assertEquals(v.get("canonical").asText(), AnalysisResults.canonical(method, version, params, inputs));
            assertEquals(v.get("resultId").asText(), AnalysisResults.resultId(method, version, params, inputs));
        }
    }

    @Test
    void identityIgnoresParamOrderButNotInputOrderOrType() {
        List<String> ids = new ArrayList<>();
        TestVectors.load("result-id.json").forEach(v -> ids.add(v.get("resultId").asText()));
        assertEquals(ids.get(0), ids.get(1));
        assertNotEquals(ids.get(2), ids.get(3));
        assertNotEquals(ids.get(2), ids.get(4));
    }

    @Test
    void integersAndNumberListsAreNormalizedToLongsAndDoubles() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scales", List.of(16, 32, 64, 128, 256));
        params.put("order", 1);
        params.put("fitTo", 256.0);
        params.put("fitFrom", 16.0);
        Map<String, Object> normalized = AnalysisResults.params(params);
        assertEquals(List.of("fitFrom", "fitTo", "order", "scales"), List.copyOf(normalized.keySet()));
        assertEquals(1L, normalized.get("order"));
        assertEquals(List.of(16.0, 32.0, 64.0, 128.0, 256.0), normalized.get("scales"));
        String expected = TestVectors.load("result-id.json").get(0).get("resultId").asText();
        assertEquals(expected, AnalysisResults.resultId("dfa", "1.0.0", params, List.of("01HF7Z1Y4X0000000000000000")));
    }

    @Test
    void analysisResultsEncodeToTheSameBytesAsPython() {
        JsonNode wire = TestVectors.load("wire-format.json");
        assertEquals(wire.get("fingerprints").get("AnalysisResult").asText(),
                XxHash64.hex(OpenTsxAvro.fingerprint(AnalysisResult.getClassSchema())));
        JsonNode vectors = wire.get("analysisResults");
        assertEquals(2, vectors.size());
        for (JsonNode v : vectors) {
            AnalysisResult built = fromSpec(v.get("spec"));
            assertEquals(v.get("resultId").asText(), built.getResultId());
            assertEquals(v.get("hex").asText(), TestVectors.hex(avro.encode(built)), built.getMethod());
            AnalysisResult decoded = avro.decode(TestVectors.bytes(v.get("hex").asText()), AnalysisResult.class);
            assertEquals(built, decoded);
            AnalysisResults.verify(decoded);
        }
    }

    @Test
    void rejectsBadParameters() {
        List<String> inputs = List.of("e1");
        assertMessage("NaN is not allowed in analysis parameters",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of("x", Double.NaN), inputs));
        assertMessage("NaN is not allowed in analysis parameters",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of("x", List.of(1.0, Double.NaN)), inputs));
        assertMessage("boolean parameters are not supported; use a long (0 or 1) or a string",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of("x", true), inputs));
        assertMessage("unsupported parameter type: Float",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of("x", 1.5f), inputs));
        assertMessage("parameter names must not be empty",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of("", 1L), inputs));
        assertMessage("method must not be empty", () -> AnalysisResults.resultId("", "1.0.0", Map.of(), inputs));
        assertMessage("methodVersion must not be empty", () -> AnalysisResults.resultId("dfa", "", Map.of(), inputs));
        assertMessage("an analysis result needs at least one input episode",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of(), List.of()));
        assertMessage("input episode ids must not be empty",
                () -> AnalysisResults.resultId("dfa", "1.0.0", Map.of(), List.of("e1", "")));
    }

    @Test
    void verifyRejectsInconsistentRecords() {
        JsonNode vectors = TestVectors.load("wire-format.json").get("analysisResults");
        JsonNode dfa = vectors.get(0).get("spec");
        JsonNode es = vectors.get(1).get("spec");
        String tamperedId = AnalysisResults.resultId("dfa", "1.0.0",
                Map.of("order", 2L, "scales", List.of(16.0, 32.0, 64.0, 128.0, 256.0)), List.of("01HF7Z1Y4X0000000000000000"));
        assertRejected(dfa, r -> r.getParams().put("order", 2L), "resultId 52d238b4de5f8107 does not match " + tamperedId);
        assertRejected(dfa, r -> r.setInputSeriesIds(List.of("a", "b")), "2 inputSeriesIds for 1 inputEpisodeIds");
        assertRejected(dfa, r -> r.setTEnd(r.getTStart()),
                "tStart 1700000000000000 must be before tEnd 1700000000000000");
        assertRejected(dfa, r -> r.getCurves().get(0).setY(List.of(1.5, 2.5, 4.25, 7.0)),
                "curve fluctuation: x has 5 values, y has 4");
        assertRejected(es, r -> r.getMatrices().get(0).setValues(List.of(1.0, 0.5, 0.25, 0.5, 1.0, 0.125, 0.25, 0.125)),
                "matrix Q: 8 values for 3 x 3");
    }

    private static void assertRejected(JsonNode spec, Consumer<AnalysisResult> tamper, String message) {
        AnalysisResult r = fromSpec(spec);
        AnalysisResults.verify(r);
        tamper.accept(r);
        assertMessage(message, () -> AnalysisResults.verify(r));
    }

    private static void assertMessage(String message, Executable call) {
        assertEquals(message, assertThrows(IllegalArgumentException.class, call).getMessage());
    }

    static Map<String, Object> typed(JsonNode params) {
        Map<String, Object> out = new LinkedHashMap<>();
        params.fields().forEachRemaining(e -> {
            Map.Entry<String, JsonNode> v = e.getValue().fields().next();
            Object value = switch (v.getKey()) {
                case "long" -> Long.parseLong(v.getValue().textValue());
                case "double" -> TestVectors.number(v.getValue());
                case "string" -> v.getValue().textValue();
                case "array" -> TestVectors.numbers(v.getValue());
                default -> throw new IllegalArgumentException("unknown parameter type " + v.getKey());
            };
            out.put(e.getKey(), value);
        });
        return out;
    }

    static AnalysisResult fromSpec(JsonNode s) {
        String method = s.get("method").asText();
        String version = s.get("methodVersion").asText();
        Map<String, Object> params = typed(s.get("params"));
        List<String> inputs = TestVectors.strings(s.get("inputEpisodeIds"));
        List<ResultScalar> scalars = new ArrayList<>();
        for (JsonNode x : s.get("scalars")) {
            scalars.add(ResultScalar.newBuilder()
                    .setName(x.get("name").asText())
                    .setValue(TestVectors.number(x.get("value")))
                    .setStdError(x.get("stdError").isNull() ? null : TestVectors.number(x.get("stdError")))
                    .setUnit(x.get("unit").isNull() ? null : x.get("unit").asText())
                    .setFitFrom(x.get("fitFrom").isNull() ? null : TestVectors.number(x.get("fitFrom")))
                    .setFitTo(x.get("fitTo").isNull() ? null : TestVectors.number(x.get("fitTo")))
                    .build());
        }
        List<ResultCurve> curves = new ArrayList<>();
        for (JsonNode x : s.get("curves")) {
            curves.add(ResultCurve.newBuilder()
                    .setName(x.get("name").asText())
                    .setXName(x.get("xName").asText())
                    .setXUnit(x.get("xUnit").isNull() ? null : x.get("xUnit").asText())
                    .setX(TestVectors.numbers(x.get("x")))
                    .setY(TestVectors.numbers(x.get("y")))
                    .setUnit(x.get("unit").isNull() ? null : x.get("unit").asText())
                    .build());
        }
        List<ResultMatrix> matrices = new ArrayList<>();
        for (JsonNode x : s.get("matrices")) {
            matrices.add(ResultMatrix.newBuilder()
                    .setName(x.get("name").asText())
                    .setRowKeys(TestVectors.strings(x.get("rowKeys")))
                    .setColKeys(TestVectors.strings(x.get("colKeys")))
                    .setValues(TestVectors.numbers(x.get("values")))
                    .setUnit(x.get("unit").isNull() ? null : x.get("unit").asText())
                    .build());
        }
        AnalysisResult r = AnalysisResult.newBuilder()
                .setResultId(AnalysisResults.resultId(method, version, params, inputs))
                .setMethod(method)
                .setMethodVersion(version)
                .setParams(AnalysisResults.params(params))
                .setInputEpisodeIds(inputs)
                .setInputSeriesIds(TestVectors.strings(s.get("inputSeriesIds")))
                .setTStart(Micros.toInstant(s.get("tStartUs").asLong()))
                .setTEnd(Micros.toInstant(s.get("tEndUs").asLong()))
                .setScalars(scalars)
                .setCurves(curves)
                .setMatrices(matrices)
                .setLabels(SeriesKeys.sortedByUtf8(TestVectors.map(s.get("labels"))))
                .setProvenance(Provenance.newBuilder()
                        .setSource(s.get("source").asText())
                        .setProducer(s.get("producer").asText())
                        .setCreatedAt(Micros.toInstant(s.get("createdAtUs").asLong()))
                        .setPipeline(s.get("pipeline").isNull() ? null : s.get("pipeline").asText())
                        .setParentEpisodeId(null)
                        .build())
                .build();
        AnalysisResults.verify(r);
        return r;
    }
}
