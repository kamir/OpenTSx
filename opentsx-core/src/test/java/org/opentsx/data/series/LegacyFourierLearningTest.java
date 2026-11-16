package org.opentsx.data.series;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.generators.FFTPhaseRandomizer;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class LegacyFourierLearningTest {

    private static JsonObject lesson(String operator) throws IOException {
        var document = JsonParser.parseString(Files.readString(Path.of("..", "docs", "learning", "library-operators.v1.json"))).getAsJsonObject();
        for (var value : document.getAsJsonArray("lessons")) {
            var lesson = value.getAsJsonObject();
            if (operator.equals(lesson.get("operator").getAsString())) {
                return lesson;
            }
        }
        throw new AssertionError("missing legacy Fourier lesson " + operator);
    }

    private static TimeSeriesObject input(JsonObject data) {
        var out = new TimeSeriesObject("Fourier learning");
        var x = data.getAsJsonArray("x");
        var y = data.getAsJsonArray("y");
        for (int i = 0; i < y.size(); i++) {
            out.addValuePair(x.get(i).getAsDouble(), y.get(i).getAsDouble());
        }
        return out;
    }

    @Test
    void fourierStarterCodeCompilesAndRuns(@TempDir Path directory) throws Exception {
        for (String operator : new String[]{"TimeSeriesObjectFFT.getFFT", "FFTPhaseRandomizer.getPhaseRandomizedRow"}) {
            StringBuilder imports = new StringBuilder();
            StringBuilder statements = new StringBuilder();
            for (String line : lesson(operator).getAsJsonObject("starter").get("code").getAsString().split("\n")) {
                (line.startsWith("import ") ? imports : statements).append(line).append('\n');
            }
            Path source = directory.resolve("LearningStarter.java");
            String resultName = operator.equals("TimeSeriesObjectFFT.getFFT") ? "result" : "shuffled";
            Files.writeString(source, imports + "public class LearningStarter { public static org.opentsx.data.series.TimeSeriesObject run() throws Exception {\n"
                    + statements + "\nreturn " + resultName + ";\n}}\n");
            var diagnostics = new ByteArrayOutputStream();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                    "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(), source.toString()),
                    diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
            try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                var result = (TimeSeriesObject) loader.loadClass("LearningStarter").getMethod("run").invoke(null);
                assertArrayEquals(new double[]{0, 1, 2, 3, 4, 5, 6, 7}, result.getData()[0], 1e-12);
                assertArrayEquals(operator.equals("TimeSeriesObjectFFT.getFFT")
                        ? new double[]{1, 1, 1, 1, 1, 1, 1, 1} : new double[8], result.getYData(), 1e-12);
            }
        }
    }

    @Test
    void fftStarterProducesAllRealCoefficientsOnTheFrequencyGrid() throws IOException {
        var data = lesson("TimeSeriesObjectFFT.getFFT").getAsJsonObject("starter").getAsJsonObject("data");
        var original = input(data);
        var converted = TimeSeriesObjectFFT.convertToMessreiheFFT(original);
        assertNotSame(original.xValues, converted.xValues);
        assertNotSame(original.yValues, converted.yValues);
        var result = converted.getFFT(data.get("samplingRate").getAsDouble());
        assertArrayEquals(new double[]{0, 1, 2, 3, 4, 5, 6, 7}, result.getData()[0], 1e-12);
        assertArrayEquals(new double[]{1, 1, 1, 1, 1, 1, 1, 1}, result.getYData(), 1e-12);
        assertArrayEquals(new double[]{1, 0, 0, 0, 0, 0, 0, 0}, original.getYData());
        var sine = new TimeSeriesObjectFFT();
        for (int i = 0; i < 8; i++) {
            sine.addValuePair(i / 8.0, new double[]{0, 1, 0, -1}[i % 4]);
        }
        assertArrayEquals(new double[8], sine.getFFT(8).getYData(), 1e-12);
    }

    @Test
    void phaseFacadeRemovesAConstantSignalInBothModesWithoutChangingTheInput() throws Exception {
        var data = lesson("FFTPhaseRandomizer.getPhaseRandomizedRow").getAsJsonObject("starter").getAsJsonObject("data");
        var original = input(data);
        for (int mode : new int[]{FFTPhaseRandomizer.MODE_shuffle_phase, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value}) {
            var result = FFTPhaseRandomizer.getPhaseRandomizedRow(original, false, false, 0, mode);
            assertArrayEquals(new double[]{0, 1, 2, 3, 4, 5, 6, 7}, result.getData()[0]);
            assertArrayEquals(new double[8], result.getYData(), 1e-12);
            assertArrayEquals(new double[]{7, 7, 7, 7, 7, 7, 7, 7}, original.getYData());
        }
    }

    @Test
    void imaginaryShuffleDoesNotRotateTheRealCosineCoefficients() throws Exception {
        lesson("FFTPhaseRandomizer.getPhaseRandomizedRow");
        var original = new TimeSeriesObject("Cosine with real Fourier coefficients");
        double[] values = {1, 0, -1, 0, 1, 0, -1, 0};
        for (int i = 0; i < values.length; i++) {
            original.addValuePair(i / 8.0, values[i]);
        }
        var result = FFTPhaseRandomizer.getPhaseRandomizedRow(original, false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
        assertArrayEquals(values, result.getYData(), 1e-12);
        assertEquals(8, result.getData()[0].length);
    }
}
