package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DynamicTest;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.site.ConformanceHandshake;

import javax.tools.ToolProvider;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class SiteConformanceChecks {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private SiteConformanceChecks() { }

    public static JsonNode report(JsonNode challenge, String bootstrap, Map<String, String> localBindings,
                                  Path repositoryRoot, Path privateDir) throws Exception {
        return report(challenge, bootstrap, localBindings, repositoryRoot, privateDir, null);
    }

    public static JsonNode report(JsonNode challenge, String bootstrap, Map<String, String> localBindings,
                                  Path repositoryRoot, Path privateDir, ConformanceHandshake.QualifiedRuntime actualRuntime) throws Exception {
        return report(challenge, bootstrap, localBindings, repositoryRoot, privateDir, actualRuntime, Clock.systemUTC());
    }

    public static JsonNode report(JsonNode challenge, String bootstrap, Map<String, String> localBindings,
                                  Path repositoryRoot, Path privateDir, ConformanceHandshake.QualifiedRuntime actualRuntime, Clock clock) throws Exception {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Files.createDirectories(privateDir);
        JsonNode contract = challenge.path("contract");
        Map<String, String> passed = new HashMap<>(), failures = new HashMap<>();
        check(passed, failures, "vectors.wire", () -> wire(root));
        check(passed, failures, "vectors.series-id", () -> series(root));
        check(passed, failures, "vectors.operators", () -> operators(root));
        check(passed, failures, "kafscale.required", () -> required(root, privateDir, bootstrap));
        ArrayNode topics = JSON.createArrayNode();
        List<Input> inputs = new ArrayList<>();
        Set<String> series = new TreeSet<>();
        check(passed, failures, "topics.names", () -> {
            for (JsonNode input : contract.path("inputs")) {
                String binding = input.path("binding").asText(), topic = input.path("topic").asText();
                String endpoint = binding(localBindings, binding);
                if (!org.opentsx.model.contracts.ExecutionAssignments.validTopic(topic)) throw new IllegalStateException("invalid input topic");
                JsonNode allowed = null;
                for (JsonNode entry : contract.path("inputContracts")) if (entry.path("binding").asText().equals(binding)
                        && entry.path("topic").asText().equals(topic)) allowed = entry.path("fingerprints");
                if (allowed == null) throw new IllegalStateException("missing input contract");
                ObjectNode row = topics.addObject().put("role", "input").put("binding", binding).put("topic", topic);
                inputs.add(new Input(endpoint, input, allowed, row));
            }
            for (JsonNode output : contract.path("outputs")) {
                String alias = output.path("cluster").asText(), topic = output.path("topic").asText();
                binding(localBindings, alias);
                if (!org.opentsx.model.contracts.ExecutionAssignments.validTopic(topic)) throw new IllegalStateException("invalid output topic");
                topics.addObject().put("role", "output").put("binding", alias).put("topic", topic);
            }
            if (topics.isEmpty()) throw new IllegalStateException("job has no topics");
            return topics.size() + " admitted input and output topic names";
        });
        check(passed, failures, "topics.partitions", () -> {
            int checked = 0;
            for (JsonNode row : topics) {
                String endpoint = binding(localBindings, row.path("binding").asText());
                String topic = row.path("topic").asText();
                try (Admin admin = KafScaleClients.admin(endpoint)) {
                    var description = admin.describeTopics(List.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS).get(topic);
                    ((ObjectNode) row).put("partitions", description.partitions().size());
                    JsonNode expected = null;
                    for (JsonNode item : contract.path(row.path("role").asText().equals("input") ? "inputs" : "outputs")) {
                        String alias = item.path(row.path("role").asText().equals("input") ? "binding" : "cluster").asText();
                        if (alias.equals(row.path("binding").asText()) && item.path("topic").asText().equals(topic)) expected = item;
                    }
                    if (expected == null || !Integer.toString(description.partitions().size()).equals(expected.path("partitionCount").asText())) {
                        throw new IllegalStateException("actual topic partition count differs");
                    }
                    checked++;
                }
            }
            for (Input input : inputs) try (KafkaConsumer<byte[], byte[]> consumer = consumer(input.bootstrap())) {
                JavaTsxTopicSource.validateStrictCut(consumer, read(input));
            }
            return checked + " topic inventories and all retained admitted cuts checked";
        });
        check(passed, failures, "topics.fingerprints", () -> {
            boolean valid = true;
            long records = 0;
            for (Input input : inputs) {
                Set<String> observed = new TreeSet<>();
                long[] count = {0};
                try (KafkaConsumer<byte[], byte[]> consumer = consumer(input.bootstrap())) {
                    TsxRead read = read(input);
                    JavaTsxTopicSource.validateStrictCut(consumer, read);
                    Map<TopicPartition, long[]> ranges = new HashMap<>();
                    read.ranges().forEach((partition, range) -> ranges.put(new TopicPartition(read.topic(), partition), range));
                    LogReader.read(consumer, ranges, record -> {
                        byte[] value = record.value();
                        count[0]++;
                        if (value == null || value.length < 10 || value[0] != (byte) 0xc3 || value[1] != 1) {
                            observed.add("invalid");
                            return;
                        }
                        String fingerprint = XxHash64.hex(ByteBuffer.wrap(value, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
                        observed.add(fingerprint);
                        if (!strings(input.allowed()).contains(fingerprint)) return;
                        SpecificRecord decoded = new OpenTsxAvro().decode(value);
                        if (!(decoded instanceof Episode episode)) throw new IllegalStateException("bounded input must contain Episode records");
                        SeriesKeys.verify(episode.getSeries());
                        series.add(episode.getSeries().getSeriesId());
                    }, TIMEOUT);
                }
                records += count[0];
                if (observed.isEmpty()) observed.add(XxHash64.hex(OpenTsxAvro.fingerprint(Episode.getClassSchema())));
                ArrayNode actual = input.row().putArray("fingerprints");
                observed.forEach(actual::add);
                if (!observed.equals(strings(input.allowed()))) valid = false;
            }
            for (JsonNode row : topics) if (row.path("role").asText().equals("output")) {
                String actual = XxHash64.hex(OpenTsxAvro.fingerprint(SeriesStats.getClassSchema()));
                ((ObjectNode) row).putArray("fingerprints").add(actual);
                for (JsonNode output : contract.path("outputs")) if (output.path("cluster").equals(row.path("binding"))
                        && output.path("topic").equals(row.path("topic")) && !strings(output.path("fingerprints")).equals(Set.of(actual))) valid = false;
            }
            if (!valid) throw new IllegalStateException("observed input or encoder output fingerprint differs");
            return records + " admitted input records checked, output encoder fingerprint checked";
        });
        check(passed, failures, "partitioner.murmur2", () -> murmur(bootstrap));
        check(passed, failures, "series.disjoint", () -> {
            if (failures.containsKey("topics.fingerprints") || failures.containsKey("topics.partitions")) throw new IllegalStateException("complete series inventory requires valid input cuts");
            return series.size() + " actual input series IDs, sorted and unique; driver checks cross-site disjointness";
        });
        check(passed, failures, "runtime.identity", () -> {
            if (actualRuntime == null || !actualRuntime.site().equals(contract.path("site").asText())
                    || !actualRuntime.node().equals(contract.path("node").asText())
                    || !actualRuntime.version().equals(version(root)) || !actualRuntime.digest().equals(artifactDigest())
                    || !actualRuntime.version().equals(contract.path("artifact").path("version").asText())
                    || !actualRuntime.digest().equals(contract.path("artifact").path("digest").asText())
                    || !actualRuntime.stateAbi().equals(contract.path("artifact").path("stateAbi").asText())) {
                throw new IllegalStateException("loaded artifact and explicit local runtime pin differ from admission");
            }
            return "loaded artifact digest, root POM version and explicit local state ABI match the qualified admission";
        });
        ObjectNode result = ConformanceHandshake.report(challenge, topics, series, passed, clock);
        for (JsonNode item : result.path("checks")) if (failures.containsKey(item.path("id").asText())) {
            ((ObjectNode) item).put("detail", failures.get(item.path("id").asText()));
        }
        return result;
    }

    private record Input(String bootstrap, JsonNode assignment, JsonNode allowed, ObjectNode row) { }

    private static String binding(Map<String, String> bindings, String name) {
        String result = bindings.get(name);
        if (result == null || result.isBlank()) throw new IllegalStateException("required local broker alias is absent");
        return result;
    }

    private static Set<String> strings(JsonNode values) {
        Set<String> result = new TreeSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static KafkaConsumer<byte[], byte[]> consumer(String bootstrap) {
        var properties = KafScaleClients.consumerProps(bootstrap);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        return new KafkaConsumer<>(properties, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    private static TsxRead read(Input input) {
        Map<Integer, long[]> ranges = new HashMap<>();
        input.assignment().path("positions").forEach(position -> ranges.put(Integer.parseInt(position.path("partition").asText()),
                new long[]{Long.parseLong(position.path("from").asText()), Long.parseLong(position.path("to").asText())}));
        return new TsxRead(input.bootstrap(), input.assignment().path("topic").asText(), ranges, strings(input.allowed()), "conformance", 1, true);
    }

    private static void check(Map<String, String> passed, Map<String, String> failures, String id, Callable<String> action) {
        try { passed.put(id, action.call()); }
        catch (Exception | AssertionError error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failures.put(id, "executed check failed: " + error.getClass().getSimpleName());
        }
    }

    static String wire(Path root) throws Exception {
        var tests = new TsxWireVectorsTest();
        tests.everyEpisodeVectorPassesTheSourceAndTheSinkUnchanged();
        tests.theFingerprintsOfThePlanAreThoseOfTheVectors();
        JsonNode vectors = JSON.readTree(root.resolve("opentsx-model/src/test/resources/testvectors/wire-format.json").toFile());
        Path classes = Files.createTempDirectory("opentsx-wire-checks-");
        try {
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("a JDK compiler is required");
            List<String> args = new ArrayList<>(List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
            Path source = root.resolve("opentsx-model/src/test/java/org/opentsx/model");
            for (String name : List.of("TestVectors.java", "EpisodesAndWireFormatTest.java", "AnalysisResultTest.java")) args.add(source.resolve(name).toString());
            try (var diagnostics = Files.newOutputStream(classes.resolve("compile.log"))) {
                if (compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)) != 0) throw new IllegalStateException("shared wire check compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL(),
                    root.resolve("opentsx-model/src/test/resources").toUri().toURL()}, SiteConformanceChecks.class.getClassLoader())) {
                for (var entry : Map.of("org.opentsx.model.EpisodesAndWireFormatTest", List.of("fingerprintsMatchPython", "episodesEncodeToTheSameBytesAsPython", "observationEncodesToTheSameBytesAsPython"),
                        "org.opentsx.model.AnalysisResultTest", List.of("analysisResultsEncodeToTheSameBytesAsPython")).entrySet()) {
                    Class<?> type = loader.loadClass(entry.getKey());
                    var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
                    Object instance = constructor.newInstance();
                    for (String name : entry.getValue()) {
                        var method = type.getDeclaredMethod(name); method.setAccessible(true); invoke(method, instance);
                    }
                }
            }
        } finally {
            try (var files = Files.walk(classes)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        int records = vectors.path("episodes").size() + 1 + vectors.path("analysisResults").size();
        return records + " shared single-object wire records and all schema fingerprints checked";
    }

    static String series(Path root) throws Exception {
        new TsxWireVectorsTest().seriesIdsMatchTheVectors();
        JsonNode vectors = JSON.readTree(root.resolve("opentsx-model/src/test/resources/testvectors/xxh64.json").toFile());
        for (JsonNode vector : vectors) if (!vector.path("hex").asText().equals(XxHash64.hex(XxHash64.hash(vector.path("input").asText().getBytes(StandardCharsets.UTF_8))))) {
            throw new IllegalStateException("xxh64 vector differs");
        }
        return vectors.size() + " xxh64 vectors and all shared series identities checked";
    }

    static String operators(Path root) throws Exception {
        if (!Path.of(".").toAbsolutePath().normalize().equals(root.resolve("opentsx-wayang"))) throw new IllegalStateException("vector checks require the Wayang module working directory");
        int cases = execute(new DagToWayangCatalogTest().allSixteenCatalogBindingsExecuteThroughCompiledWayangPlans());
        new TsxCleanVectorsTest().everyCaseMatchesThroughTheJavaExecutionOperator();
        cases += execute(new TsxVectorVectorsTest().everySharedCaseMatchesThroughAWayangPlan());
        cases += execute(new TsxPairVectorsTest().everySharedCaseMatchesThroughAWayangPlan());
        cases += execute(new TsxPairEsWayangTest().everySeriesCaseMatchesThroughAWayangPlan());
        cases += execute(new TsxStatsVectorsTest().everySharedCaseMatchesThroughAWayangPlan());
        cases += execute(new TsxSegmentVectorsTest().sharedVectorsThroughRealWayangPlans().stream());
        cases += execute(new TsxSaxVectorsTest().allSharedWordsMatchThroughWayang());
        cases += execute(new TsxDqVectorsTest().allSharedResultsMatchThroughWayang());
        cases += reflectedVectors("org.opentsx.wayang.ops.TsxPairEsVectorsTest", "sharedVectors");
        var type = Class.forName("org.opentsx.wayang.ops.TsxPairEsVectorsTest");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var mulberry = type.getDeclaredMethod("mulberry32MatchesEverySharedValueExactly"); mulberry.setAccessible(true);
        invoke(mulberry, constructor.newInstance());
        new TsxPairWithinTest().oneInputProducesEachUnorderedPairOnceAndKeepsInputOrientationAcrossRestarts();
        return cases + " dynamic vector cases, all 16 catalog bindings, clean vectors, exact Mulberry32 and within-pair checks executed";
    }

    private static int reflectedVectors(String name, String factory) throws Exception {
        var type = Class.forName(name);
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var method = type.getDeclaredMethod(factory); method.setAccessible(true);
        Object result = invoke(method, constructor.newInstance());
        if (!(result instanceof List<?> tests)) throw new IllegalStateException("unexpected vector factory type");
        List<DynamicTest> values = new ArrayList<>();
        for (Object test : tests) values.add((DynamicTest) test);
        return execute(values.stream());
    }

    private static int execute(Stream<DynamicTest> stream) throws Exception {
        int count = 0;
        try (stream) {
            for (DynamicTest test : stream.toList()) {
                try { test.getExecutable().execute(); }
                catch (Exception error) { throw error; }
                catch (Error error) { throw error; }
                catch (Throwable error) { throw new IllegalStateException("vector execution failed", error); }
                count++;
            }
        }
        if (count == 0) throw new IllegalStateException("no shared vector cases executed");
        return count;
    }

    private static String required(Path root, Path privateDir, String bootstrap) throws Exception {
        Path classes = privateDir.resolve("required-classes");
        Files.createDirectories(classes);
        String classpath = System.getProperty("java.class.path");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("a JDK compiler is required");
        List<String> args = new ArrayList<>(List.of("--release", "17", "-classpath", classpath, "-d", classes.toString()));
        Path source = root.resolve("opentsx-kafscale-conformance/src/test/java/org/opentsx/kafscale");
        args.add(source.resolve("Capabilities.java").toString());
        args.add(source.resolve("KafScaleConformanceTest.java").toString());
        try (var diagnostics = Files.newOutputStream(privateDir.resolve("required-compile.log"))) {
            if (compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)) != 0) throw new IllegalStateException("required conformance source compilation failed");
        }
        ProcessBuilder child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", classes + java.io.File.pathSeparator + classpath, SiteConformanceChecks.class.getName(), "required")
                .directory(privateDir.toFile()).redirectErrorStream(true).redirectOutput(privateDir.resolve("required-run.log").toFile());
        child.environment().put("KAFSCALE_BOOTSTRAP", bootstrap);
        Process process = child.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("required conformance run timed out"); }
        if (process.exitValue() != 0 || !Files.readString(privateDir.resolve("required-run.log")).contains("requiredChecks=9")) throw new IllegalStateException("required broker checks failed");
        return "9 existing required broker checks executed against this site";
    }

    private static Object invoke(java.lang.reflect.Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("check invocation failed", error.getCause());
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].equals("required")) throw new IllegalArgumentException("required check mode is required");
        Class<?> type = Class.forName("org.opentsx.kafscale.KafScaleConformanceTest");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object test = constructor.newInstance();
        java.lang.reflect.Method connect = type.getDeclaredMethod("connect"), close = type.getDeclaredMethod("close");
        connect.setAccessible(true); close.setAccessible(true);
        Throwable primary = null;
        int count = 0;
        try {
            invoke(connect, test);
            for (String name : List.of("createsTopicsViaAdminApi", "producesAndConsumesInOrderPerKey", "roundTripsOneMegabyteEpisode", "committedOffsetsAreReadableAfterRestart", "seeksToExplicitOffsetForIndexBasedReplay")) {
                var method = type.getDeclaredMethod(name); method.setAccessible(true); invoke(method, test); count++;
            }
            var codecs = type.getDeclaredMethod("roundTripsEveryCompressionCodec", String.class); codecs.setAccessible(true);
            for (String codec : List.of("none", "snappy", "lz4", "zstd")) { invoke(codecs, test, codec); count++; }
        } catch (Exception | Error error) { primary = error; throw error; }
        finally {
            try { invoke(close, test); }
            catch (Exception | Error error) { if (primary != null) primary.addSuppressed(error); else throw error; }
        }
        System.out.println("requiredChecks=" + count);
    }

    private static String murmur(String bootstrap) throws Exception {
        String topic = "tsx.conformance.repartition.murmur-" + UUID.randomUUID().toString().substring(0, 8) + ".v2";
        try (Admin admin = KafScaleClients.admin(bootstrap)) { admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(30, TimeUnit.SECONDS); }
        Map<String, Integer> expected = new LinkedHashMap<>();
        Set<Integer> partitions = new HashSet<>();
        for (int index = 0; partitions.size() < 3; index++) {
            String key = "probe-" + index;
            int partition = Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % 3;
            if (partitions.add(partition)) expected.put(key, partition);
        }
        try (var producer = KafScaleClients.producer(bootstrap)) {
            for (var entry : expected.entrySet()) if (producer.send(new ProducerRecord<>(topic, entry.getKey(), new byte[]{1})).get(30, TimeUnit.SECONDS).partition() != entry.getValue()) throw new IllegalStateException("producer Murmur2 partition differs");
        }
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        for (int partition = 0; partition < 3; partition++) ranges.put(new TopicPartition(topic, partition), new long[]{0, 1});
        Set<String> actual = new HashSet<>();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(bootstrap)) {
            LogReader.read(consumer, ranges, record -> {
                String key = new String(record.key(), StandardCharsets.UTF_8);
                if (!expected.containsKey(key) || expected.get(key) != record.partition() || !actual.add(key)) throw new IllegalStateException("broker Murmur2 probe differs");
            }, TIMEOUT);
        }
        if (!actual.equals(expected.keySet())) throw new IllegalStateException("partition probe inventory is incomplete");
        return "3 default-producer keys consumed from their expected Murmur2 partitions";
    }

    public static String artifactDigest() throws Exception {
        Path artifact = Path.of(org.opentsx.wayang.site.TsxSiteAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (Files.isRegularFile(artifact)) digest.update(Files.readAllBytes(artifact));
        else try (var files = Files.walk(artifact)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(artifact.relativize(file).toString().replace(java.io.File.separatorChar, '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    public static String version(Path root) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        var children = document.getDocumentElement().getChildNodes();
        for (int index = 0; index < children.getLength(); index++) if (children.item(index).getNodeName().equals("version")) return children.item(index).getTextContent();
        throw new IllegalStateException("root POM has no version");
    }
}
