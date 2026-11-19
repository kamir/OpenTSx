package org.opentsx.twin.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TwinLiveTest {

    @TempDir
    Path tmp;

    record Exit(int code, String out, String err) {
    }

    /** What a long-running command printed: its first line on stdout and stderr up to then. */
    record Started(String firstLine, String err) {
    }

    /** Runs the command in this JVM; {@code brokers} gives the topics of a broker. */
    private static Exit run(Map<String, String> env, Function<String, Topics> brokers, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = TwinLive.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), brokers, hook -> { });
        return new Exit(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Function<String, Topics> noBroker() {
        return b -> {
            throw new AssertionError("no broker may be opened, asked for " + b);
        };
    }

    private static StudioScenario h2() {
        return Scenarios.all().stream().filter(s -> s.id().equals("h2-chain")).findFirst().orElseThrow();
    }

    private List<String> java(String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), TwinLive.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    /** Runs TwinLive in a new JVM with the given environment and nothing else. */
    private Exit main(Map<String, String> env, String... args) throws IOException, InterruptedException {
        Path out = Files.createTempFile(tmp, "out", ".txt");
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(java(args)).redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 30 s: " + String.join(" ", args) + "\nstdout: " + Files.readString(out)
                    + "\nstderr: " + Files.readString(err));
        }
        return new Exit(p.exitValue(), Files.readString(out), Files.readString(err));
    }

    /** Starts a command that runs for good, reads its first line on stdout and stops it. */
    private Started start(Map<String, String> env, String... args) throws IOException, InterruptedException {
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(java(args)).redirectError(err.toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String first = out.readLine();
            return new Started(first, Files.readString(err));
        } finally {
            p.destroyForcibly().waitFor();
        }
    }

    @Test
    void anUnknownOptionExitsWith2BeforeTheBridgeStarts() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: --prot: unknown option of twin-live demo; did you mean --port?\n"),
                main(Map.of(), "demo", "--prot", "9999", "--port", "0"));
    }

    @Test
    void anUnknownScenarioOrABadDurationExitsWith2BeforeTheBridgeStarts() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: opentsx.twin.scenario: \"nosuch\" is not a scenario; expected one of h2-chain, "
                + "haus, boot\n"), main(Map.of(), "demo", "--scenario", "nosuch", "--port", "0"));
        assertEquals(new Exit(2, "", "opentsx: --duration: \"abc\" is not a long; expected a whole number from "
                + "-9223372036854775808 to 9223372036854775807\n"), main(Map.of(), "demo", "--duration", "abc", "--port", "0"));
        assertEquals(new Exit(2, "", "opentsx: opentsx.twinLive.durationSeconds: \"0\" is not allowed; expected 1 to "
                + "9223372036854775 seconds\n"), main(Map.of(), "demo", "--duration", "0", "--port", "0"));
    }

    @Test
    @Timeout(120)
    void kafscaleBootstrapAndTheEnvironmentNameTheSameBroker() throws Exception {
        String bridge = "event bridge on http://localhost:\\d+/v1/topics/<topic>/events, broker h:9092";
        for (String flag : List.of("--kafscale", "--bootstrap")) {
            Started s = start(Map.of(), "bridge", flag, "h:9092", "--port", "0");
            assertNotNull(s.firstLine(), flag + ": " + s.err());
            assertTrue(s.firstLine().matches(bridge), flag + ": " + s.firstLine());
        }
        Started env = start(Map.of("KAFSCALE_BOOTSTRAP", "h:9092"), "bridge", "--port", "0");
        assertNotNull(env.firstLine(), env.err());
        assertTrue(env.firstLine().matches(bridge), env.firstLine());
    }

    @Test
    @Timeout(120)
    void theLogLevelKeySetsTheLevelAndTheStartLineNamesTheValues() throws Exception {
        Started info = start(Map.of("OPENTSX_LOG_LEVEL", "info"), "bridge", "--bootstrap", "h:9092", "--port", "0");
        assertNotNull(info.firstLine(), info.err());
        List<String> lines = info.err().lines().filter(l -> l.contains(" INFO ")).toList();
        assertEquals(1, lines.size(), info.err());
        assertTrue(lines.get(0).endsWith(" INFO org.opentsx.twin.live.TwinLive - starting twin-live bridge: "
                + "opentsx.kafscale.bootstrap=h:9092 (commandLine), opentsx.log.level=info (environment), "
                + "opentsx.twinLive.allowOrigin=* (default), opentsx.twinLive.maxStreams=16 (default), "
                + "opentsx.twinLive.port=0 (commandLine)"), lines.get(0));

        Started warn = start(Map.of("OPENTSX_LOG_LEVEL", "warn"), "bridge", "--bootstrap", "h:9092", "--port", "0");
        assertNotNull(warn.firstLine(), warn.err());
        assertFalse(warn.err().contains(" INFO "), warn.err());
    }

    @Test
    @Timeout(120)
    void aTerminatedDemoStopsTheBridgeAndThePublisherAndLogsBoth() throws Exception {
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(java("demo", "--port", "0", "--scenario", "h2-chain")).redirectError(err.toFile());
        pb.environment().clear();
        Process p = pb.start();
        String first;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            first = out.readLine();
            assertNotNull(first, Files.readString(err));
            p.destroy();
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the demo did not end after SIGTERM: " + Files.readString(err));
        } finally {
            p.destroyForcibly().waitFor();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("event bridge on http://localhost:(\\d+) ").matcher(first);
        assertTrue(m.find(), first);
        List<String> info = Files.readString(err).lines().filter(l -> l.contains(" INFO ")).toList();
        assertTrue(info.stream().anyMatch(l -> l.endsWith(" INFO org.opentsx.twin.live.TwinLive - publisher started"
                + " scenarios=h2-chain loop=true duration=PT2M")), String.join("\n", info));
        assertTrue(info.stream().anyMatch(l -> l.contains(" INFO org.opentsx.twin.live.TwinLive - publisher stopped runs=")),
                String.join("\n", info));
        assertTrue(info.stream().anyMatch(l -> l.endsWith(" INFO org.opentsx.twin.live.EventBridge - event bridge stopped port="
                + m.group(1))), String.join("\n", info));
    }

    @Test
    @Timeout(60)
    void theStopHookOfTheDemoStopsTheBridgeAndEndsTheRun() throws Exception {
        BlockingQueue<Thread> hooks = new LinkedBlockingQueue<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AtomicInteger code = new AtomicInteger(-1);
        Thread demo = new Thread(() -> code.set(TwinLive.run(List.of("demo", "--port", "0", "--scenario", "h2-chain"), Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), noBroker(),
                hooks::add)));
        demo.setDaemon(true);
        demo.start();
        Thread hook = hooks.poll(30, TimeUnit.SECONDS);
        assertNotNull(hook, "the demo registers a stop hook");
        String base = null;
        while (base == null && demo.isAlive()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("event bridge on (http://localhost:\\d+) ")
                    .matcher(out.toString(StandardCharsets.UTF_8));
            base = m.find() ? m.group(1) : null;
            Thread.onSpinWait();
        }
        assertNotNull(base, err.toString(StandardCharsets.UTF_8));
        HttpClient http = HttpClient.newHttpClient();
        URI health = URI.create(base + "/v1/health");
        assertEquals(200, http.send(HttpRequest.newBuilder(health).build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        hook.run();

        demo.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(demo.isAlive(), "the run ends once the hook has run");
        assertEquals(0, code.get(), err.toString(StandardCharsets.UTF_8));
        assertThrows(ConnectException.class, () -> http.send(HttpRequest.newBuilder(health).build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @Timeout(60)
    void publishWithLoopFalseWritesOneRoundAndABareLoopMeansTrue() {
        RunResult run = EventPublisher.referenceRun(h2());
        String topic = TopicLayout.twinEvents(h2().plan().twin(), run.runId());
        MemoryTopics topics = new MemoryTopics();
        Exit once = run(Map.of(), b -> topics, "publish", "--kafscale", "mock:9092", "--scenario", "h2-chain", "--loop", "false");
        assertEquals(new Exit(0, "h2-chain: " + run.events().size() + " events -> " + topic + "\n", ""), once);
        assertEquals(run.events().size(), topics.size(topic));

        OpenTsxConf bare = OpenTsxConf.load(List.of("twin-live", "publish", "--kafscale", "mock:9092", "--loop"), Map.of(), null)
                .conf();
        assertTrue(TwinLive.check(TwinLive.PUBLISH, bare).loop());
        assertEquals(new Exit(2, "", "opentsx: yes: unexpected argument; expected opentsx twin-live publish\n"),
                run(Map.of(), noBroker(), "publish", "--kafscale", "mock:9092", "--loop", "yes"));
    }

    @Test
    @Timeout(60)
    void theFlagsAndVariablesOfTheBootstrapAllReachTheBroker() {
        List<List<String>> asked = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Function<String, Topics> brokers = b -> {
            seen.add(b);
            return new MemoryTopics();
        };
        for (String flag : List.of("--kafscale", "--bootstrap")) {
            assertEquals(0, run(Map.of(), brokers, "publish", flag, "h:9092", "--scenario", "h2-chain").code());
            asked.add(List.copyOf(seen));
            seen.clear();
        }
        for (String variable : List.of("KAFSCALE_BOOTSTRAP", "OPENTSX_KAFSCALE_BOOTSTRAP")) {
            assertEquals(0, run(Map.of(variable, "h:9092"), brokers, "publish", "--scenario", "h2-chain").code());
            asked.add(List.copyOf(seen));
            seen.clear();
        }
        assertEquals(List.of(List.of("h:9092"), List.of("h:9092"), List.of("h:9092"), List.of("h:9092")), asked);

        assertEquals(0, run(Map.of("KAFSCALE_BOOTSTRAP", "legacy:9092", "OPENTSX_KAFSCALE_BOOTSTRAP", "h:9092"), brokers,
                "publish", "--scenario", "h2-chain").code());
        assertEquals(List.of("h:9092"), seen);
        assertEquals(new Exit(2, "", "opentsx: --bootstrap: set twice on the command line (also by --kafscale); set each key once\n"),
                run(Map.of(), noBroker(), "publish", "--kafscale", "h:9092", "--bootstrap", "h:9092"));
        assertEquals(new Exit(2, "", "opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale, "
                + "--conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP\n"), run(Map.of(), noBroker(), "bridge"));
    }

    @Test
    void helpAndVersionPrintOnStdoutAndAMissingSubcommandExitsWith2() {
        assertEquals(new Exit(0, Registry.shipped().help("twin-live publish"), ""), run(Map.of(), noBroker(), "publish", "--help"));
        assertFalse(Registry.shipped().help("twin-live bridge").contains("In the Java runtime"));
        assertEquals(new Exit(0, ConfCli.versionLine(), ""), run(Map.of(), noBroker(), "demo", "--version"));
        assertEquals(new Exit(2, "", "opentsx: twin-live: missing subcommand; expected one of twin-live bridge, twin-live demo, "
                + "twin-live publish\n"), run(Map.of(), noBroker()));
    }

    @Test
    void aPortOrAStreamLimitOutOfRangeExitsWith2() {
        assertEquals(new Exit(2, "", "opentsx: opentsx.twinLive.port: \"70000\" is not allowed; expected 0 to 65535, 0 for any "
                + "free port\n"), run(Map.of(), noBroker(), "bridge", "--kafscale", "h:9092", "--port", "70000"));
        assertEquals(new Exit(2, "", "opentsx: opentsx.twinLive.maxStreams: \"0\" is not allowed; expected 1 or more\n"),
                run(Map.of(), noBroker(), "demo", "--max-streams", "0"));
    }

    @Test
    void aFailedPublishExitsWith1WithItsCausesOnOneLine() {
        Topics refusing = new Topics() {
            @Override
            public void create(String topic) {
                throw new IllegalStateException("could not create " + topic,
                        new java.util.concurrent.TimeoutException("the broker did not answer"));
            }

            @Override
            public void append(String topic, String key, byte[] value, String schema) {
                throw new AssertionError("nothing is appended to a topic that was not created");
            }

            @Override
            public Cursor open(String topic, long offset) {
                throw new AssertionError("publish reads nothing");
            }

            @Override
            public void close() {
            }
        };
        RunResult run = EventPublisher.referenceRun(h2());
        String topic = TopicLayout.twinEvents(h2().plan().twin(), run.runId());
        Exit failed = run(Map.of(), b -> refusing, "publish", "--kafscale", "h:9092", "--scenario", "h2-chain");
        assertEquals(new Exit(1, "h2-chain: " + run.events().size() + " events -> " + topic + "\n",
                "opentsx: publishing failed; caused by java.lang.IllegalStateException: could not create " + topic
                        + "; caused by java.util.concurrent.TimeoutException: the broker did not answer\n"), failed);
    }

    @Test
    void theArgumentsOfTheK3sManifestLoad() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path k3s = Path.of("..", "deploy", "k3s");
        Map<String, String> literals = new HashMap<>();
        JsonNode kustomization = yaml.readTree(k3s.resolve("base/kustomization.yaml").toFile());
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            literals.put(kv[0], kv[1]);
        }
        List<JsonNode> docs = new ArrayList<>();
        for (JsonNode resource : kustomization.path("resources")) {
            docs.addAll(yaml.readValues(yaml.createParser(k3s.resolve("base").resolve(resource.asText()).toFile()), JsonNode.class)
                    .readAll());
        }
        List<JsonNode> bridgeDocs = yaml.readValues(yaml.createParser(
                k3s.resolve("components/event-bridge/event-bridge.yaml").toFile()), JsonNode.class).readAll();
        docs.addAll(bridgeDocs);
        JsonNode podSpec = bridgeDocs.get(0).path("spec").path("template").path("spec");
        JsonNode container = podSpec.path("containers").get(0);
        Map<String, String> env = new HashMap<>();
        if (podSpec.path("enableServiceLinks").asBoolean(true)) {
            for (JsonNode doc : docs) {
                if (doc.path("kind").asText().equals("Service")) {
                    env.putAll(serviceLinks(doc));
                }
            }
        }
        for (JsonNode e : container.path("env")) {
            JsonNode ref = e.path("valueFrom").path("configMapKeyRef");
            String value = literals.get(ref.path("key").asText());
            if (value == null && ref.path("optional").asBoolean(false)) {
                continue;
            }
            assertNotNull(value, e.toString());
            env.put(e.path("name").asText(), value);
        }
        List<String> args = new ArrayList<>();
        for (JsonNode a : container.path("args")) {
            args.add(a.asText());
        }
        assertEquals(List.of("twin-live", "bridge"), args.subList(0, 2));

        OpenTsxConf conf = OpenTsxConf.load(args, env, null).conf();

        assertEquals("kafscale.invalid:9092", conf.get("opentsx.kafscale.bootstrap"));
        assertEquals("environment", conf.source("opentsx.kafscale.bootstrap"));
        assertEquals(8090, conf.getInt("opentsx.twinLive.port"));
        assertEquals("*", conf.get("opentsx.twinLive.allowOrigin"));
        TwinLive.Plan plan = TwinLive.check(TwinLive.BRIDGE, conf);
        assertEquals("kafscale.invalid:9092", plan.bootstrap());
        assertEquals(8090, plan.port());
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
