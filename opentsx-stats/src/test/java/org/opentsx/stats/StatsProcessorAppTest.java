package org.opentsx.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.TopicLayout;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StatsProcessorAppTest {

    static {
        // surefire sets the default level to warn; the start line is logged at INFO
        System.setProperty("org.slf4j.simpleLogger.log.org.opentsx.stats.StatsProcessorApp", "info");
    }

    private static final String DURATION_ALLOWED = "expected ISO-8601 such as PT10M or a whole number with the unit ms, s,"
            + " m, min, h or d such as 10m, at most 999999999 days";

    @TempDir
    Path tmp;

    private record Exit(int code, String out, String err) {
    }

    private static MockConsumer<byte[], byte[]> emptyTopic(String domain) {
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        String topic = new TopicLayout(domain).episodes();
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, node, new Node[]{node}, new Node[]{node})));
        TopicPartition tp = new TopicPartition(topic, 0);
        c.updateBeginningOffsets(Map.of(tp, 0L));
        c.updateEndOffsets(Map.of(tp, 0L));
        return c;
    }

    /** Runs the command in this JVM against an empty episode topic of domain wind; stderr also holds the log lines. */
    private static Exit run(Map<String, String> env, OutputStream stdout, String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        PrintStream stderr = System.err;
        System.setErr(errStream);
        try {
            int code = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> StatsProcessorApp.run(List.of(args), env,
                    new PrintStream(stdout, true, StandardCharsets.UTF_8), errStream, bootstrap -> emptyTopic("wind"),
                    bootstrap -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer())));
            String out = stdout instanceof ByteArrayOutputStream b ? b.toString(StandardCharsets.UTF_8) : "";
            return new Exit(code, out, err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(stderr);
        }
    }

    private static Exit run(String... args) {
        return run(Map.of(), new ByteArrayOutputStream(), args);
    }

    /** Runs the main class in a new JVM with the given environment and nothing else. */
    private Exit main(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), StatsProcessorApp.class.getName()));
        command.addAll(List.of(args));
        Path out = Files.createTempFile(tmp, "out", ".txt");
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 60 s: " + String.join(" ", args) + "\n" + Files.readString(err));
        }
        return new Exit(p.exitValue(), Files.readString(out), Files.readString(err));
    }

    @Test
    void anUnknownOptionExitsWith2AndNamesTheNearestOption() throws Exception {
        Exit e = main(Map.of(), "--kafscale", "127.0.0.1:9", "--window", "PT5M");
        assertEquals(new Exit(2, "", "opentsx: --window: unknown option of stats; did you mean --windows?\n"), e);
    }

    @Test
    void anOptionWithoutItsValueExitsWith2() throws Exception {
        Exit e = main(Map.of(), "--kafscale", "127.0.0.1:9", "--windows");
        assertEquals(new Exit(2, "", "opentsx: --windows: missing value; expected --windows <list>\n"), e);
        Exit checkpoint = run("--kafscale", "mock:9092", "--once", "--checkpoint");
        assertEquals(new Exit(2, "", "opentsx: --checkpoint: missing value; expected --checkpoint <path>\n"), checkpoint);
    }

    @Test
    void versionPrintsTheVersionOnTheGivenStdoutAndReturnsZero() {
        assertEquals(new Exit(0, ConfCli.versionLine(), ""), run("--version"));
    }

    @Test
    void helpPrintsTheKeysWithTheirDefaultsOnStdoutAndVersionTheVersion() throws Exception {
        Exit help = main(Map.of(), "--help");
        assertEquals(new Exit(0, Registry.shipped().help("stats"), ""), help);
        assertTrue(help.out().startsWith("usage: opentsx stats [options]\n"), help.out());
        assertTrue(help.out().contains("\n  opentsx.domain  string = wind  --domain  OPENTSX_DOMAIN\n"), help.out());
        assertTrue(help.out().contains("\n  opentsx.stats.checkpoint  path  --checkpoint  OPENTSX_STATS_CHECKPOINT\n"), help.out());
        assertTrue(help.out().contains("\n  opentsx.stats.windows  list of duration = PT10M,PT1H  --windows  OPENTSX_STATS_WINDOWS\n"),
                help.out());
        Exit version = main(Map.of(), "--kafscale", "127.0.0.1:9", "--version");
        assertEquals(0, version.code(), version.err());
        assertTrue(version.out().matches("opentsx [0-9]+\\.[0-9]+\\.[0-9]+\\S* \\(java\\)\n"), version.out());
    }

    @Test
    void aMalformedValuePrintsOneLineAndExitsWith2() throws Exception {
        Exit e = main(Map.of(), "--kafscale", "nohost", "--windows", "PT10");
        assertEquals(new Exit(2, "", "opentsx: --windows: item \"PT10\" is not a duration; " + DURATION_ALLOWED + "\n"), e);
    }

    @Test
    void aBooleanTakesTrueOrFalseOnly() throws Exception {
        Exit word = main(Map.of(), "--kafscale", "127.0.0.1:9", "--once", "yes");
        assertEquals(new Exit(2, "", "opentsx: yes: unexpected argument; expected opentsx stats\n"), word);
        Exit conf = main(Map.of(), "--kafscale", "127.0.0.1:9", "--conf", "opentsx.stats.once=yes");
        assertEquals(new Exit(2, "", "opentsx: opentsx.stats.once: \"yes\" is not a boolean; expected true or false\n"), conf);
        Exit env = main(Map.of("OPENTSX_STATS_ONCE", "yes"), "--kafscale", "127.0.0.1:9");
        assertEquals(new Exit(2, "", "opentsx: OPENTSX_STATS_ONCE: \"yes\" is not a boolean; expected true or false\n"), env);
    }

    @Test
    void aBareOnceAtTheEndRunsTheBacklogOnce() {
        Exit e = run("--kafscale", "mock:9092", "--once");
        assertEquals(0, e.code(), e.err());
        assertTrue(e.out().startsWith("RunResult[records=0, undecodable=0, invalid=0, lostToRetention=0, statsWritten=0, "),
                e.out());
    }

    @Test
    void theStartLineNamesEveryValueAndItsSource() {
        Exit e = run(Map.of("KAFSCALE_BOOTSTRAP", "mock:9092"), new ByteArrayOutputStream(), "--once", "true", "--alpha", "0.02");
        assertEquals(0, e.code(), e.err());
        List<String> start = e.err().lines().filter(l -> l.contains("starting stats")).toList();
        assertEquals(1, start.size(), e.err());
        String line = start.get(0);
        assertTrue(line.contains(" INFO org.opentsx.stats.StatsProcessorApp - starting stats: opentsx.domain=wind (default), "),
                line);
        assertTrue(line.contains(", opentsx.kafscale.bootstrap=mock:9092 (environment), "), line);
        assertTrue(line.contains(", opentsx.stats.once=true (commandLine), "), line);
        assertTrue(line.contains(", opentsx.stats.sketchAlpha=0.02 (commandLine), "), line);
        assertTrue(line.endsWith(", opentsx.stats.windows=PT10M,PT1H (default)"), line);
        assertFalse(line.contains("opentsx.stats.checkpoint"), "a key without value is not listed: " + line);
    }

    @Test
    void aSettingTheEngineRefusesExitsWith2() {
        Exit e = run("--kafscale", "mock:9092", "--once", "--alpha", "5");
        assertEquals(new Exit(2, "", "opentsx: sketchAlpha: \"5.0\" is not allowed; expected a number above 0 and below 1,"
                + " or 0 or less for no sketch\n"), e);
        Exit none = run("--once");
        assertEquals(new Exit(2, "", "opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale,"
                + " --conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP\n"), none);
    }

    @Test
    void aRuntimeFailurePrintsTheCauseChainAndExitsWith1() throws IOException {
        Path empty = Files.createFile(tmp.resolve("stats.ckpt"));
        Exit e = run("--kafscale", "mock:9092", "--once", "--checkpoint", empty.toString());
        assertEquals(1, e.code(), e.err());
        assertEquals("opentsx: cannot read checkpoint " + empty + "; caused by java.io.EOFException",
                e.err().lines().reduce((a, b) -> b).orElse(""), e.err());
    }

    @Test
    void aResultThatCannotBeWrittenIsARuntimeFailure() {
        OutputStream closed = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("closed");
            }
        };
        Exit e = run(Map.of(), closed, "--kafscale", "mock:9092", "--once");
        assertEquals(1, e.code(), e.err());
        assertTrue(e.err().endsWith("opentsx: stdout: cannot write the output; check that standard output is open and not full\n"),
                e.err());
    }

    @Test
    void theShutdownHookLogsStoppingAndWarnsWhenTheProcessorDoesNotStopInTime() throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch closed = new CountDownLatch(1);
        Thread processor = new Thread(() -> {
            while (!stop.get()) {
                Thread.onSpinWait();
            }
            closed.countDown();
        });
        processor.setDaemon(true);
        processor.start();
        AtomicBoolean stuck = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            StatsProcessorApp.stopHook(stop, closed, Duration.ofSeconds(60), LoggerFactory.getLogger(StatsProcessorApp.class)).run();
            StatsProcessorApp.stopHook(stuck, new CountDownLatch(1), Duration.ZERO, LoggerFactory.getLogger(StatsProcessorApp.class)).run();
            StatsProcessorApp.stopHook(ended, new CountDownLatch(0), Duration.ofSeconds(60), LoggerFactory.getLogger(StatsProcessorApp.class)).run();
        } finally {
            System.setErr(stderr);
        }

        assertTrue(stop.get(), "the hook tells the processor to stop");
        processor.join();
        assertTrue(stuck.get());
        assertFalse(ended.get(), "a hook after the end of the run does nothing");
        String app = "org.opentsx.stats.StatsProcessorApp - ";
        assertEquals(List.of(
                        "INFO " + app + "stopping; waiting for the final checkpoint wait=PT1M",
                        "INFO " + app + "stopping; waiting for the final checkpoint wait=PT0S",
                        "WARN " + app + "the processor did not stop in time; the final checkpoint may be missing wait=PT0S"),
                err.toString(StandardCharsets.UTF_8).lines().map(l -> l.substring(l.indexOf("] ") + 2)).toList());
    }

    @Test
    void theArgumentsOfTheK3sManifestLoad() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Map<String, String> literals = new HashMap<>();
        JsonNode kustomization = yaml.readTree(Path.of("..", "deploy", "k3s", "base", "kustomization.yaml").toFile());
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            literals.put(kv[0], kv[1]);
        }
        JsonNode podSpec = yaml.readTree(Path.of("..", "deploy", "k3s", "base", "stats.yaml").toFile())
                .path("spec").path("template").path("spec");
        JsonNode container = podSpec.path("containers").get(0);
        Map<String, String> env = new HashMap<>();
        if (podSpec.path("enableServiceLinks").asBoolean(true)) {
            for (JsonNode resource : kustomization.path("resources")) {
                for (JsonNode doc : yaml.readValues(yaml.createParser(Path.of("..", "deploy", "k3s", "base",
                        resource.asText()).toFile()), JsonNode.class).readAll()) {
                    if (doc.path("kind").asText().equals("Service")) {
                        env.putAll(serviceLinks(doc));
                    }
                }
            }
        }
        for (JsonNode e : container.path("env")) {
            String value = literals.get(e.path("valueFrom").path("configMapKeyRef").path("key").asText());
            assertNotNull(value, e.toString());
            env.put(e.path("name").asText(), value);
        }
        List<String> args = new ArrayList<>();
        Pattern reference = Pattern.compile("\\$\\(([A-Z_]+)\\)");
        for (JsonNode a : container.path("args")) {
            Matcher m = reference.matcher(a.asText());
            args.add(m.replaceAll(r -> Matcher.quoteReplacement(env.get(r.group(1)))));
        }
        assertEquals("stats", args.get(0));

        OpenTsxConf conf = OpenTsxConf.load(args, env, null).conf();

        assertEquals("kafscale.invalid:9092", conf.get("opentsx.kafscale.bootstrap"));
        assertEquals("wind", conf.get("opentsx.domain"));
        assertEquals("/state/stats.ckpt", conf.get("opentsx.stats.checkpoint"));
        assertEquals(List.of(Duration.ofMinutes(10), Duration.ofHours(1)), conf.getDurationList("opentsx.stats.windows"));
        assertEquals(Duration.ZERO, conf.getDuration("opentsx.stats.allowedLateness"));
        assertEquals("environment", conf.source("opentsx.stats.windows"));
        assertFalse(conf.getBoolean("opentsx.stats.once"));
    }

    /** The variables Kubernetes sets in a pod for a Service of its namespace unless enableServiceLinks is false. */
    private static Map<String, String> serviceLinks(JsonNode service) {
        String name = service.path("metadata").path("name").asText().toUpperCase().replace('-', '_');
        String ip = "10.43.0.10";
        Map<String, String> env = new HashMap<>();
        env.put(name + "_SERVICE_HOST", ip);
        JsonNode ports = service.path("spec").path("ports");
        for (int i = 0; i < ports.size(); i++) {
            JsonNode p = ports.get(i);
            String port = p.path("port").asText();
            String proto = p.path("protocol").asText("TCP");
            String url = proto.toLowerCase() + "://" + ip + ":" + port;
            if (i == 0) {
                env.put(name + "_SERVICE_PORT", port);
                env.put(name + "_PORT", url);
            }
            if (p.has("name")) {
                env.put(name + "_SERVICE_PORT_" + p.path("name").asText().toUpperCase().replace('-', '_'), port);
            }
            String prefix = name + "_PORT_" + port + "_" + proto;
            env.put(prefix, url);
            env.put(prefix + "_PROTO", proto.toLowerCase());
            env.put(prefix + "_PORT", port);
            env.put(prefix + "_ADDR", ip);
        }
        return env;
    }
}
