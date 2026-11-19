package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.Registry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The command line of vectors: options and the DAG file are checked before the first Admin call. */
class VectorProcessorAppTest {

    @TempDir
    Path dir;

    private static final Node BROKER = new Node(0, "127.0.0.1", 1);
    private final MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER);
    private final MockProducer<String, byte[]> producer =
            new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    private final List<String> admins = new ArrayList<>();
    private final List<String> producers = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        out.reset();
        err.reset();
        return VectorProcessorApp.run(List.of(args), Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), b -> {
                    admins.add(b);
                    return admin;
                }, b -> {
                    producers.add(b);
                    return producer;
                }, b -> {
                    throw new AssertionError("no consumer expected");
                });
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Set<String> topics() throws Exception {
        return new TreeSet<>(admin.listTopics().names().get());
    }

    @Test
    void stopWithoutANameExitsWith2AndBuildsNoClient() throws Exception {
        assertEquals(2, run("stop", "--kafscale", "ks:9092", "--domain", "wind"));
        assertEquals("opentsx: opentsx.vectors.name: not set; set it with --name, --conf opentsx.vectors.name=<value> or"
                + " OPENTSX_VECTORS_NAME\n", err());
        assertEquals(2, run("stop", "--kafscale", "ks:9092", "--name", "../wind"));
        assertEquals("opentsx: opentsx.vectors.name: \"../wind\" is not allowed; expected letters, digits, dots, hyphens and"
                + " underscores, starting with a letter or a digit\n", err());
        assertEquals(2, run("stop", "--kafscale", "ks:9092", "--nmae", "wind-exp2-smart"));
        assertEquals("opentsx: --nmae: unknown option of vectors stop; did you mean --name?\n", err());
        assertEquals("", out());
        assertEquals(List.of(), admins);
        assertEquals(List.of(), producers);
        assertEquals(Set.of(), topics());
    }

    @Test
    void aMisspelledCommandExitsWith2BeforeProvisioning() throws Exception {
        assertEquals(2, run("depoly", "--kafscale", "ks:9092", "--domain", "wind"));
        assertEquals("opentsx: depoly: unknown subcommand of vectors; did you mean deploy?\n", err());
        assertEquals(2, run("deploy", "--kafscale", "ks:9092"));
        assertEquals("opentsx: opentsx.vectors.dag: not set; set it with --dag, --conf opentsx.vectors.dag=<value> or"
                + " OPENTSX_VECTORS_DAG\n", err());
        assertEquals(2, run("stop", "--name", "wind-exp2-smart"));
        assertEquals("opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf"
                + " opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP\n", err());
        assertEquals(List.of(), admins);
        assertEquals(Set.of(), topics());
    }

    @Test
    void aDagThatCannotRunExitsWith2BeforeProvisioning() throws Exception {
        Path missing = dir.resolve("none.dag.yaml");
        assertEquals(2, run("deploy", "--kafscale", "ks:9092", "--dag", missing.toString()));
        assertEquals("opentsx: " + missing + ": no such file; give an existing file\n", err());
        Path broken = Files.writeString(dir.resolve("broken.dag.yaml"), "dag: [unclosed\n");
        assertEquals(2, run("deploy", "--kafscale", "ks:9092", "--dag", broken.toString()));
        assertTrue(err().startsWith("opentsx: " + broken + ": not valid YAML; while parsing a flow sequence"), err());
        assertEquals(1, err().lines().count(), err());
        Path unnamed = Files.writeString(dir.resolve("unnamed.dag.yaml"), "version: 1\n");
        assertEquals(2, run("deploy", "--kafscale", "ks:9092", "--dag", unnamed.toString()));
        assertEquals("opentsx: " + unnamed + ": the DAG needs a name (dag: ...) of letters, digits, dots, hyphens and"
                + " underscores\n", err());
        assertEquals(List.of(), admins);
        assertEquals(List.of(), producers);
        assertEquals(Set.of(), topics());
    }

    @Test
    void deployWritesOneDeployRecordToTheControlTopic() throws Exception {
        Path dag = Files.writeString(dir.resolve("good.dag.yaml"), VectorProcessorTest.goodDag());
        assertEquals(0, run("deploy", "--kafscale", "ks:9092", "--dag", dag.toString(), "--by", "ops"), err());
        assertEquals("deployed wind-exp2-smart (control topic tsx.wind.dags.v1)\n", out());
        assertEquals(List.of("ks:9092"), admins);
        assertEquals(List.of("ks:9092"), producers);
        assertEquals(Set.of("tsx.wind.dag-status.v1", "tsx.wind.dags.v1"), topics());
        ProducerRecord<String, byte[]> r = producer.history().get(0);
        assertEquals("tsx.wind.dags.v1", r.topic());
        assertEquals("wind-exp2-smart", r.key());
        JsonNode record = new ObjectMapper().readTree(r.value());
        assertEquals("deployed", record.path("state").asText());
        assertEquals("ops", record.path("by").asText());
        assertEquals(VectorProcessorTest.goodDag(), record.path("spec").asText());
    }

    @Test
    void stopWritesOneStopRecord() throws Exception {
        assertEquals(0, run("stop", "--kafscale", "ks:9092", "--domain", "wind", "--name", "wind-exp2-smart"), err());
        assertEquals("stopped wind-exp2-smart\n", out());
        assertEquals(1, producer.history().size());
        JsonNode record = new ObjectMapper().readTree(producer.history().get(0).value());
        assertEquals("wind-exp2-smart", record.path("dag").asText());
        assertEquals("stopped", record.path("state").asText());
        assertEquals("cli", record.path("by").asText());
    }

    @Test
    void aProcessorThatFailsAfterTheStartExitsWith1AtOnce() throws Exception {
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path"), FailingProcessorMain.class.getName(),
                dir.resolve("state").toString()).redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 30 s\nstderr: " + Files.readString(err));
        }
        assertEquals(1, p.exitValue(), Files.readString(err));
        assertEquals("opentsx: consumer factory failed", Files.readString(err).lines().reduce((a, b) -> b).orElse(""));
        assertEquals("", Files.readString(out));
    }

    @Test
    void helpPrintsTheKeysOfTheCommandWithoutAnyClient() {
        assertEquals(0, run("--help"));
        assertEquals(Registry.shipped().help("vectors"), out());
        assertTrue(out().contains("\n  opentsx.vectors.state  path = vector-state  --state  OPENTSX_VECTORS_STATE\n"), out());
        assertEquals(0, run("stop", "--kafscale", "ks:9092", "--help"));
        assertEquals(Registry.shipped().help("vectors stop"), out());
        assertEquals("", err());
        assertEquals(List.of(), admins);
    }
}
