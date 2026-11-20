package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsAccumulator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class MixedSketchQualificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();
    private static final String GROUP = "0123456789abcdef";
    private static final long END = 600_000_000;
    private static final double ALPHA = 0.01;
    private static final double[] QUANTILES = {0, 0.01, 0.5, 0.99, 1};
    private static final int[][] ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
    @TempDir Path directory;

    private record Sample(long timestamp, double value, int site) { }
    private record Case(String name, String domain, List<Sample> samples, String sharedSketch) { }
    private record ProcessResult(int exit, String stdout, String stderr) { }
    record Stores(long zeros, Map<Integer, Long> positive, Map<Integer, Long> negative) { }

    @Test void mixedSketchesQualifySharedBoundaryZeroBucketAndCollapsedTailCases() throws Exception {
        Path report = Path.of("target", "rust-b4-sketch", "result.json");
        Files.createDirectories(report.getParent());
        Files.deleteIfExists(report);
        List<Case> cases = cases();
        assertEquals(58, cases.size());
        ObjectNode request = JSON.createObjectNode().put("schema", "opentsx.rust-b4-sketch-input.v1");
        ArrayNode inputs = request.putArray("cases");
        for (Case test : cases) {
            request(inputs, test.name() + "-all", test.samples());
            request(inputs, test.name() + "-s3", test.samples().stream().filter(s -> s.site() == 3).toList());
        }
        ProcessResult process = execute(JSON.writeValueAsBytes(request));
        assertEquals(0, process.exit(), process.stderr());
        assertEquals("", process.stderr());
        Files.write(report.getParent().resolve("input.json"), JSON.writeValueAsBytes(request));
        Files.writeString(report.getParent().resolve("rust-output.json"), process.stdout());
        JsonNode output = JSON.readTree(process.stdout());
        assertEquals("opentsx.rust-b4-sketch-output.v1", text(output, "schema"));
        assertEquals(ALPHA, required(output, "alpha").doubleValue());
        assertEquals(4096, required(output, "maxBins").intValue());
        JsonNode outputCases = required(output, "cases");
        assertTrue(outputCases.isArray());
        assertEquals(cases.size() * 2, outputCases.size());
        Map<String, JsonNode> rust = new TreeMap<>();
        outputCases.forEach(value -> assertNull(rust.put(text(value, "name"), value)));
        ObjectNode evidence = JSON.createObjectNode().put("schema", "opentsx.rust-b4-sketch-qualification.v1")
                .put("sourceCommit", System.getProperty("opentsx.sourceCommit"))
                .put("rustVersion", text(output, "version")).put("javaVersion", System.getProperty("java.version"))
                .put("osName", System.getProperty("os.name")).put("osArch", System.getProperty("os.arch"))
                .put("rustArtifactDigest", "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable()))))
                .put("alpha", ALPHA).put("maxBins", 4096);
        ArrayNode observations = evidence.putArray("cases");
        int compared = 0;
        int differingBoundaries = 0;
        for (Case test : cases) {
            JsonNode rustAll = rust.get(test.name() + "-all"), rustSite = rust.get(test.name() + "-s3");
            assertNotNull(rustAll);
            assertNotNull(rustSite);
            SeriesStats javaAll = statistics(test.samples());
            SeriesStats rustAllRecord = decode(rustAll), rustPartial = decode(rustSite);
            assertExact(javaAll, rustAllRecord, test.name());
            assertMoments(javaAll, rustAllRecord, test.name());
            assertRustStores(rustAll, rustAllRecord);
            assertRustStores(rustSite, rustPartial);
            Stores javaStores = stores(sketch(javaAll)), rustStores = stores(sketch(rustAllRecord));
            if (test.domain().equals("boundary") || test.domain().equals("threshold")) {
                assertEquals(javaStores.zeros(), rustStores.zeros(), test.name());
                if (!javaStores.positive().isEmpty() || !javaStores.negative().isEmpty()) {
                    assertNeighbour(javaStores.positive(), rustStores.positive(), test.name());
                    assertNeighbour(javaStores.negative(), rustStores.negative(), test.name());
                } else assertEquals(javaStores, rustStores, test.name());
                if (!Arrays.equals(sketch(javaAll), sketch(rustAllRecord))) differingBoundaries++;
            } else if (!test.domain().startsWith("collapsed")) {
                assertArrayEquals(sketch(javaAll), sketch(rustAllRecord), test.name());
            }
            if (test.sharedSketch() != null) {
                byte[] wanted = HexFormat.of().parseHex(test.sharedSketch());
                assertArrayEquals(wanted, sketch(javaAll), test.name());
                assertArrayEquals(wanted, sketch(rustAllRecord), test.name());
            }
            List<TsxStatsMerge.Partial> partials = List.of(
                    new TsxStatsMerge.Partial("s1", statistics(test.samples().stream().filter(s -> s.site() == 1).toList())),
                    new TsxStatsMerge.Partial("s2", statistics(test.samples().stream().filter(s -> s.site() == 2).toList())),
                    new TsxStatsMerge.Partial("s3", rustPartial));
            byte[] baseline = null;
            SeriesStats mixed = null;
            for (int[] order : ORDERS) {
                SeriesStats merged = TsxStatsMerge.merge(GROUP, "PT10M", 0, END, ALPHA,
                        Arrays.stream(order).mapToObj(partials::get).toList());
                assertExact(javaAll, merged, test.name());
                assertMoments(javaAll, merged, test.name());
                byte[] encoded = AVRO.encode(merged);
                if (baseline == null) { baseline = encoded; mixed = merged; }
                else assertArrayEquals(baseline, encoded, test.name());
                compared++;
            }
            assertNotNull(mixed);
            ObjectNode row = observations.addObject().put("name", test.name()).put("domain", test.domain())
                    .put("sampleCount", test.samples().size()).put("mergeOrders", ORDERS.length)
                    .put("javaRustSketchEqual", Arrays.equals(sketch(javaAll), sketch(rustAllRecord)));
            ArrayNode samples = row.putArray("samples");
            test.samples().forEach(s -> samples.addObject().put("timestampMicros", s.timestamp())
                    .put("valueBits", bits(s.value())).put("site", "s" + s.site()));
            row.set("java", describe(javaAll));
            row.set("rust", rustAll.deepCopy());
            row.set("rustSitePartial", rustSite.deepCopy());
            row.set("mixed", describe(mixed));
            if (javaAll.getValidCount() == 0) row.putNull("meanRelativeError").putNull("m2RelativeError");
            else {
                row.put("meanRelativeError", relative(javaAll.getMean(), mixed.getMean()));
                row.put("m2RelativeError", relative(javaAll.getM2(), mixed.getM2()));
            }
            if (test.domain().startsWith("collapsed")) qualifyCollapse(test, javaAll, rustPartial, mixed, row);
            else {
                assertTrue(javaStores.positive().size() < 4096 && javaStores.negative().size() < 4096);
                assertTrue(rustStores.positive().size() < 4096 && rustStores.negative().size() < 4096);
            }
            double[] sorted = test.samples().stream().mapToDouble(Sample::value).filter(Double::isFinite).sorted().toArray();
            ArrayNode quantiles = row.putArray("quantileChecks");
            for (double q : QUANTILES) {
                for (String runtime : List.of("java", "rust", "mixed")) {
                    SeriesStats candidate = switch (runtime) {
                        case "java" -> javaAll;
                        case "rust" -> rustAllRecord;
                        case "mixed" -> mixed;
                        default -> throw new AssertionError(runtime);
                    };
                    double decoded = QuantileSketch.fromBytes(candidate.getQuantileSketch()).quantile(q);
                    double found = runtime.equals("rust") ? nativeQuantile(rustAll, q) : decoded;
                    ObjectNode check = quantiles.addObject().put("runtime", runtime).put("q", q)
                            .put("valueBits", bits(found)).put("decodedJavaValueBits", bits(decoded));
                    if (sorted.length == 0) {
                        assertTrue(Double.isNaN(found));
                        check.put("accuracyDomain", "empty");
                    } else {
                        double exact = sorted[(int) Math.floor(q * (sorted.length - 1))];
                        double lower = Math.min(exact * (1 - ALPHA), exact * (1 + ALPHA));
                        double upper = Math.max(exact * (1 - ALPHA), exact * (1 + ALPHA));
                        boolean within = found >= lower && found <= upper;
                        check.put("exactBits", bits(exact)).put("lowerAlphaBoundBits", bits(lower)).put("upperAlphaBoundBits", bits(upper))
                                .put("withinAlphaInterval", within).put("absoluteError", Math.abs(found - exact));
                        if (test.domain().startsWith("collapsed")) check.put("accuracyDomain", "excluded-collapsed-tail");
                        else if (Math.abs(exact) < QuantileSketch.MIN_INDEXABLE) {
                            assertEquals(0.0, found, test.name());
                            check.put("accuracyDomain", "zero-bucket");
                        } else {
                            assertTrue(within, test.name() + " " + runtime + " q=" + q + " exact=" + exact + " found=" + found);
                            check.put("accuracyDomain", "outside-zero-without-collapse");
                        }
                    }
                }
            }
        }
        assertEquals(348, compared);
        evidence.put("caseCount", cases.size()).put("mixedMergeOrders", compared).put("boundaryDifferencesObserved", differingBoundaries);
        JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), evidence);
    }

    @Test void helperReportsItsVersionAndRejectsMalformedOrOversizedInput() throws Exception {
        ProcessResult version = execute(new byte[0], "--version");
        assertEquals(0, version.exit(), version.stderr());
        assertTrue(version.stdout().matches("tsx-runner [0-9]+\\.[0-9]+\\.[0-9]+\\n"));
        ProcessResult help = execute(new byte[0], "--help");
        assertEquals(0, help.exit(), help.stderr());
        assertTrue(help.stdout().contains("opentsx.rust-b4-sketch-input.v1"));
        for (String input : List.of(
                "{\"schema\":\"wrong\",\"cases\":[]}",
                "{\"schema\":\"opentsx.rust-b4-sketch-input.v1\",\"cases\":[{\"name\":\"bad\",\"samples\":[{\"timestampMicros\":0,\"valueBits\":\"ABC\"}]}]}",
                "{\"schema\":\"opentsx.rust-b4-sketch-input.v1\",\"cases\":[{\"name\":\"same\",\"samples\":[]},{\"name\":\"same\",\"samples\":[]}]}",
                "{\"schema\":\"opentsx.rust-b4-sketch-input.v1\",\"cases\":[{\"name\":\"extra\",\"samples\":[]}],\"extra\":true}")) {
            ProcessResult result = execute(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, result.exit());
            assertEquals("", result.stdout());
            assertFalse(result.stderr().isBlank());
        }
        ProcessResult oversized = execute(new byte[4_194_305]);
        assertEquals(2, oversized.exit());
        assertEquals("", oversized.stdout());
        assertTrue(oversized.stderr().contains("exceeds 4194304 bytes"));
    }

    private static List<Case> cases() throws Exception {
        List<Case> cases = new ArrayList<>();
        JsonNode shared = JSON.readTree(Path.of("..", "docs", "datamodel-2026", "testvectors", "stats-window.v1.json").toFile());
        for (String name : List.of("canonical sketch values", "empty window")) {
            JsonNode selected = null;
            for (JsonNode test : shared.path("cases")) if (test.path("name").asText().equals(name)) selected = test;
            assertNotNull(selected, name);
            List<Sample> samples = new ArrayList<>();
            for (JsonNode sample : selected.path("samples")) samples.add(new Sample(sample.path("tsMicros").longValue(),
                    sample.path("value").doubleValue(), samples.size() % 3 + 1));
            cases.add(new Case(name.equals("empty window") ? "empty-shared" : "canonical-shared", "ordinary", List.copyOf(samples),
                    selected.path("expect").get(0).path("sketchHex").textValue()));
        }
        cases.add(point("negative-zero", "zero", -0.0));
        cases.add(point("positive-zero", "zero", 0.0));
        for (int sign : new int[]{-1, 1}) {
            double threshold = sign * QuantileSketch.MIN_INDEXABLE;
            for (int neighbour = -1; neighbour <= 1; neighbour++) cases.add(point("threshold-" + (sign < 0 ? "negative" : "positive")
                    + "-" + (neighbour < 0 ? "before" : neighbour > 0 ? "after" : "at"), "threshold", adjacent(threshold, neighbour)));
            double gamma = (1 + ALPHA) / (1 - ALPHA);
            for (int exponent : new int[]{-1000, -97, -1, 0, 1, 97, 1000}) {
                double boundary = sign * StrictMath.pow(gamma, exponent);
                for (int neighbour = -1; neighbour <= 1; neighbour++) cases.add(point("boundary-" + (sign < 0 ? "negative" : "positive")
                        + "-" + (exponent < 0 ? "minus" + -exponent : Integer.toString(exponent)) + "-"
                        + (neighbour < 0 ? "before" : neighbour > 0 ? "after" : "at"), "boundary", adjacent(boundary, neighbour)));
            }
        }
        cases.add(values("zero-bucket-mix", "zero", new double[]{-5e-10, -0.0, 0.0, 1e-12}, false));
        cases.add(values("nonfinite-and-signed-zeros", "ordinary", new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.0, 0.0}, false));
        for (int sign : new int[]{-1, 1}) for (boolean local : new boolean[]{false, true}) {
            double[] values = new double[5001];
            for (int index = 0; index < values.length; index++) values[index] = sign * StrictMath.exp(index * 0.03);
            cases.add(values("collapsed-" + (sign < 0 ? "negative" : "positive") + "-" + (local ? "site" : "merge"),
                    "collapsed-" + (sign < 0 ? "negative" : "positive") + "-" + (local ? "site" : "merge"), values, local));
        }
        return List.copyOf(cases);
    }

    private static Case point(String name, String domain, double value) { return values(name, domain, new double[]{value, value, value}, false); }
    private static double adjacent(double value, int direction) { return direction < 0 ? Math.nextDown(value) : direction > 0 ? Math.nextUp(value) : value; }
    private static Case values(String name, String domain, double[] values, boolean local) {
        List<Sample> samples = new ArrayList<>();
        for (int index = 0; index < values.length; index++) samples.add(new Sample(index, values[index], local ? 3 : index % 3 + 1));
        return new Case(name, domain, List.copyOf(samples), null);
    }

    private static void request(ArrayNode cases, String name, List<Sample> samples) {
        ArrayNode values = cases.addObject().put("name", name).putArray("samples");
        samples.forEach(sample -> values.addObject().put("timestampMicros", sample.timestamp()).put("valueBits", bits(sample.value())));
    }

    private static SeriesStats statistics(List<Sample> samples) {
        StatsAccumulator accumulator = new StatsAccumulator(ALPHA);
        samples.forEach(sample -> accumulator.add(sample.timestamp(), sample.value()));
        return accumulator.toSeriesStats(GROUP, "PT10M", 0, END);
    }

    private static SeriesStats decode(JsonNode value) { return AVRO.decode(HexFormat.of().parseHex(text(value, "soeHex")), SeriesStats.class); }
    private static byte[] sketch(SeriesStats value) {
        ByteBuffer buffer = value.getQuantileSketch().duplicate();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    private static String bits(double value) { return "%016x".formatted(Double.doubleToRawLongBits(value)); }
    private static JsonNode required(JsonNode value, String name) { JsonNode field = value.get(name); assertNotNull(field, name); return field; }
    private static String text(JsonNode value, String name) { JsonNode field = required(value, name); assertTrue(field.isTextual(), name); return field.textValue(); }

    private static void assertExact(SeriesStats expected, SeriesStats actual, String name) {
        assertEquals(expected.getSeriesId(), actual.getSeriesId(), name);
        assertEquals(expected.getWindowType(), actual.getWindowType(), name);
        assertEquals(expected.getWindowStart(), actual.getWindowStart(), name);
        assertEquals(expected.getWindowEnd(), actual.getWindowEnd(), name);
        assertEquals(expected.getValidCount(), actual.getValidCount(), name);
        assertEquals(expected.getNanCount(), actual.getNanCount(), name);
        assertEquals(expected.getGapCount(), actual.getGapCount(), name);
        assertEquals(bits(expected.getMin()), bits(actual.getMin()), name);
        assertEquals(bits(expected.getMax()), bits(actual.getMax()), name);
        assertEquals(expected.getLastSeen(), actual.getLastSeen(), name);
    }

    private static double relative(double expected, double actual) {
        if (Double.isNaN(expected)) { assertTrue(Double.isNaN(actual)); return 0; }
        return expected == 0 ? Math.abs(actual) : Math.abs((actual - expected) / expected);
    }
    private static void assertMoments(SeriesStats expected, SeriesStats actual, String name) {
        assertTrue(relative(expected.getMean(), actual.getMean()) <= 3e-12, name + " mean");
        assertTrue(relative(expected.getM2(), actual.getM2()) <= 3e-12, name + " m2");
    }

    private static void assertNeighbour(Map<Integer, Long> javaBins, Map<Integer, Long> rustBins, String name) {
        if (javaBins.isEmpty()) { assertTrue(rustBins.isEmpty(), name); return; }
        assertEquals(1, javaBins.size(), name);
        assertEquals(1, rustBins.size(), name);
        assertTrue(Math.abs((long) javaBins.keySet().iterator().next() - rustBins.keySet().iterator().next()) <= 1, name);
        assertEquals(javaBins.values().iterator().next(), rustBins.values().iterator().next(), name);
    }

    private static double nativeQuantile(JsonNode reported, double q) {
        JsonNode quantiles = required(reported, "quantiles");
        assertTrue(quantiles.isArray());
        assertEquals(QUANTILES.length, quantiles.size());
        for (int index = 0; index < QUANTILES.length; index++) {
            JsonNode point = quantiles.get(index);
            assertEquals(QUANTILES[index], required(point, "q").doubleValue());
            String value = text(point, "valueBits");
            assertTrue(value.matches("[0-9a-f]{16}"));
            if (q == QUANTILES[index]) return Double.longBitsToDouble(Long.parseUnsignedLong(value, 16));
        }
        throw new AssertionError("the requested qualification quantile is missing");
    }

    private static void assertRustStores(JsonNode reported, SeriesStats record) {
        Stores decoded = stores(sketch(record));
        assertEquals(decoded.zeros(), required(reported, "zeroCount").longValue());
        var counts = new com.fasterxml.jackson.core.type.TypeReference<Map<Integer, Long>>() { };
        assertEquals(decoded.positive(), JSON.convertValue(required(reported, "positive"), counts));
        assertEquals(decoded.negative(), JSON.convertValue(required(reported, "negative"), counts));
        assertEquals(record.getValidCount(), decoded.zeros() + decoded.positive().values().stream().mapToLong(Long::longValue).sum()
                + decoded.negative().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(bits(record.getMin()), text(reported, "minBits"));
        assertEquals(bits(record.getMax()), text(reported, "maxBits"));
    }

    private static ObjectNode describe(SeriesStats record) {
        Stores stores = stores(sketch(record));
        ObjectNode value = JSON.createObjectNode().put("validCount", record.getValidCount()).put("nanCount", record.getNanCount())
                .put("gapCount", record.getGapCount()).put("minBits", bits(record.getMin())).put("maxBits", bits(record.getMax()))
                .put("meanBits", bits(record.getMean())).put("m2Bits", bits(record.getM2()))
                .put("sketchHex", HexFormat.of().formatHex(sketch(record))).put("zeroCount", stores.zeros());
        value.set("positive", JSON.valueToTree(stores.positive()));
        value.set("negative", JSON.valueToTree(stores.negative()));
        return value;
    }

    private static void qualifyCollapse(Case test, SeriesStats javaAll, SeriesStats rustPartial, SeriesStats mixed, ObjectNode row) {
        boolean positive = test.domain().contains("positive");
        Stores java = stores(sketch(javaAll)), rust = stores(sketch(rustPartial)), combined = stores(sketch(mixed));
        assertEquals(4096, (positive ? java.positive() : java.negative()).size(), test.name());
        assertEquals(4096, (positive ? combined.positive() : combined.negative()).size(), test.name());
        if (test.domain().endsWith("site")) assertEquals(4096, (positive ? rust.positive() : rust.negative()).size(), test.name());
        else assertTrue((positive ? rust.positive() : rust.negative()).size() < 4096, test.name());
        QuantileSketch uncollapsed = new QuantileSketch(ALPHA, Integer.MAX_VALUE);
        test.samples().forEach(sample -> uncollapsed.add(sample.value()));
        Stores unlimited = stores(uncollapsed.toBytes());
        assertEquals(5001, (positive ? unlimited.positive() : unlimited.negative()).size(), test.name());
        assertFalse(Arrays.equals(uncollapsed.toBytes(), sketch(javaAll)), test.name());
        double[] sorted = test.samples().stream().mapToDouble(Sample::value).sorted().toArray();
        double lowMagnitudeQ = positive ? 0 : 1, highMagnitudeQ = positive ? 1 : 0;
        for (SeriesStats candidate : List.of(javaAll, mixed)) {
            QuantileSketch sketch = QuantileSketch.fromBytes(candidate.getQuantileSketch());
            double lowExact = sorted[(int) (lowMagnitudeQ * (sorted.length - 1))];
            double highExact = sorted[(int) (highMagnitudeQ * (sorted.length - 1))];
            assertTrue(Math.abs(sketch.quantile(lowMagnitudeQ) - lowExact) > ALPHA * Math.abs(lowExact), test.name());
            double high = sketch.quantile(highMagnitudeQ);
            assertTrue(high >= Math.min(highExact * (1 - ALPHA), highExact * (1 + ALPHA))
                    && high <= Math.max(highExact * (1 - ALPHA), highExact * (1 + ALPHA)), test.name());
        }
        row.put("accuracyException", "bounded-bin collapse degrades the lowest-magnitude tail")
                .put("uncollapsedBinCount", positive ? unlimited.positive().size() : unlimited.negative().size())
                .put("lowMagnitudeTailExceedsAlpha", true).put("highMagnitudeTailWithinAlpha", true);
    }

    static Stores inspect(ByteBuffer encoded) {
        ByteBuffer buffer = encoded.duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return stores(bytes);
    }

    private static Stores stores(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, input.get());
        assertEquals(ALPHA, input.getDouble());
        long zeros = unsigned(input);
        List<Map<Integer, Long>> maps = new ArrayList<>();
        for (int store = 0; store < 2; store++) {
            long count = unsigned(input), previous = 0;
            assertTrue(count <= 20_000);
            Map<Integer, Long> bins = new TreeMap<>();
            for (long index = 0; index < count; index++) {
                long zigzag = unsigned(input);
                long key = Math.addExact(previous, (zigzag >>> 1) ^ -(zigzag & 1));
                long occurrences = unsigned(input);
                assertTrue(occurrences > 0);
                assertTrue(key > previous || index == 0);
                assertNull(bins.put(Math.toIntExact(key), occurrences));
                previous = key;
            }
            maps.add(bins);
        }
        assertFalse(input.hasRemaining());
        return new Stores(zeros, maps.get(0), maps.get(1));
    }

    private static long unsigned(ByteBuffer input) {
        long result = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            int value = input.get() & 255;
            result |= (long) (value & 127) << shift;
            if ((value & 128) == 0) return result;
        }
        throw new AssertionError("sketch varint exceeds the qualification count bound");
    }

    private static Path executable() throws Exception {
        Path executable = Path.of(System.getProperty("opentsx.rust.b4.sketch", "../rust/tsx-runner/target/debug/examples/b4_sketch")).toRealPath();
        assertTrue(Files.isRegularFile(executable) && Files.isExecutable(executable));
        assertTrue(Files.size(executable) <= 268_435_456);
        return executable;
    }

    private ProcessResult execute(byte[] input, String... arguments) throws Exception {
        Path request = Files.createTempFile(directory, "input-", ".json");
        Path output = Files.createTempFile(directory, "output-", ".json");
        Path error = Files.createTempFile(directory, "error-", ".txt");
        Files.write(request, input);
        List<String> command = new ArrayList<>();
        command.add(executable().toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectInput(request.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        boolean completed;
        try { completed = process.waitFor(120, TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw failure;
        }
        if (!completed) {
            process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        }
        assertTrue(completed, "B4 sketch process exceeded its bounded qualification time");
        assertTrue(Files.size(output) <= 8_388_608 && Files.size(error) <= 262_144);
        return new ProcessResult(process.exitValue(), Files.readString(output), Files.readString(error));
    }
}
