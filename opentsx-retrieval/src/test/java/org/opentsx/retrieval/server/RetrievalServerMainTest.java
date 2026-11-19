package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The command line of retrieval-server: every case here must end before a server or a client starts. */
class RetrievalServerMainTest {

    @TempDir
    Path tmp;

    private record Exit(int code, String out, String err) {
    }

    /** Runs the main class in a new JVM with the given environment and nothing else. */
    private Exit main(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), RetrievalServer.class.getName()));
        command.addAll(List.of(args));
        Path out = Files.createTempFile(tmp, "out", ".txt");
        Path err = Files.createTempFile(tmp, "err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
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

    private Exit main(String... args) throws IOException, InterruptedException {
        return main(Map.of(), args);
    }

    /** Runs the command in this JVM; {@code started} gets the server when one listens. */
    private static Exit run(Map<String, String> env, Consumer<RetrievalServer> started, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = RetrievalServer.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), started);
        return new Exit(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static RetrievalServer.Plan plan(Map<String, String> env, String... args) {
        List<String> argv = new ArrayList<>(List.of("retrieval-server"));
        argv.addAll(List.of(args));
        return RetrievalServer.check(OpenTsxConf.load(argv, env, null).conf());
    }

    private static String lastLine(String text) {
        return text.lines().reduce((a, b) -> b).orElse("");
    }

    @Test
    void aMisspelledOptionExitsWith2AndNamesTheNearestOption() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: --prot: unknown option of retrieval-server; did you mean --port?\n"),
                main("--prot", "18080", "--port", "0"));
        assertEquals(new Exit(2, "", "opentsx: --kafscle: unknown option of retrieval-server; did you mean --kafscale?\n"),
                main("--port", "0", "--kafscle", "nohost:9092"));
        assertEquals(new Exit(2, "", "opentsx: --demo: unknown option of retrieval-server; run opentsx retrieval-server --help"
                + " for the options\n"), main("--port", "0", "--demo", "true"));
    }

    @Test
    void anOptionWithoutItsValueAndAMalformedValueExitWith2() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: --kafscale: missing value; expected --kafscale <string>\n"),
                main("--port", "0", "--kafscale"));
        assertEquals(new Exit(2, "", "opentsx: --port: \"abc\" is not an int; expected a whole number from -2147483648 to"
                + " 2147483647\n"), main("--port", "abc"));
        assertEquals(new Exit(2, "", "opentsx: --refresh: \"PT30\" is not a duration; expected ISO-8601 such as PT10M or a whole"
                + " number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days\n"), main("--port", "0", "--refresh", "PT30"));
    }

    @Test
    void dagRunsTakeTrueOrFalseOnly() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: yes: unexpected argument; expected opentsx retrieval-server\n"),
                main("--port", "0", "--dag-runs", "yes"));
    }

    @Test
    void helpPrintsTheKeysOnStdoutAndBindsNoPort() throws Exception {
        Exit help = main("--help");
        assertEquals(new Exit(0, Registry.shipped().help("retrieval-server"), ""), help);
        assertTrue(help.out().startsWith("usage: opentsx retrieval-server [options]\n"), help.out());
        assertTrue(help.out().contains("\n  opentsx.retrieval.source  enum = auto  one of auto|demo|iceberg|kafscale  --source"
                + "  OPENTSX_RETRIEVAL_SOURCE\n      Store the retrieval server serves; auto takes iceberg when"
                + " opentsx.iceberg.properties is set on the command line, kafscale when opentsx.kafscale.bootstrap is, else demo;"
                + " both on the command line is an error.\n"), help.out());
        assertFalse(help.out().contains("parses its own flags"), help.out());
        assertEquals(help, main("--port", "0", "--help"));
        Exit version = main("--port", "0", "--version");
        assertEquals(0, version.code(), version.err());
        assertTrue(version.out().matches("opentsx [0-9]+\\.[0-9]+\\.[0-9]+\\S* \\(java\\)\n"), version.out());
    }

    @Test
    void dagRunsWithoutARunTokenExitWith2AndNameTheVariable() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: opentsx.retrieval.dagRuns: true needs a run token; set"
                        + " OPENTSX_RETRIEVAL_DAG_RUN_TOKEN or OPENTSX_DAG_RUN_TOKEN, or give --open-dag-runs to accept runs without"
                        + " a token on 127.0.0.1 only\n"),
                main("--kafscale", "localhost:9", "--dag-runs", "true"));
    }

    @Test
    void aRunTokenOnTheCommandLineExitsWith2AndNamesTheVariable() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: --dag-run-token: secret keys are not accepted on the command line; set"
                        + " OPENTSX_RETRIEVAL_DAG_RUN_TOKEN or OPENTSX_DAG_RUN_TOKEN or put opentsx.retrieval.dagRunToken in a"
                        + " properties file\n"),
                main("--port", "0", "--dag-run-token", "x"));
    }

    @Test
    void icebergWithKafScaleExitsWith2BeforeTheCatalogOpens() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: --iceberg: given together with --kafscale; give one of them, or choose with"
                        + " --source iceberg or --source kafscale\n"),
                main("--iceberg", "type=hadoop,warehouse=w", "--kafscale", "x"));
        assertEquals(new Exit(2, "", "opentsx: --iceberg: item \"foo\" is not key=value; expected catalog properties such as"
                        + " type=rest,uri=http://localhost:8181,warehouse=s3://lake/\n"),
                main("--iceberg", "foo"));
    }

    @Test
    void aCatalogThatFailsShowsItsOwnMessageAndExitsWith1() throws Exception {
        Exit e = main(Map.of("OPENTSX_DAG_RUN_TOKEN", "s3cret-run-token"), "--iceberg", "name=synthetic", "--namespace", "synthetic");
        assertEquals(1, e.code(), e.err());
        assertEquals("opentsx: existing-only factory selected", lastLine(e.err()), e.err());
        assertFalse(e.err().contains("InvocationTargetException"), e.err());
        assertFalse(e.err().contains("\tat "), e.err());
        List<String> start = e.err().lines().filter(l -> l.contains("starting retrieval-server")).toList();
        assertEquals(1, start.size(), e.err());
        assertTrue(start.get(0).contains(" INFO org.opentsx.retrieval.server.RetrievalServer - starting retrieval-server: "
                + "opentsx.domain=wind (default), opentsx.iceberg.namespace=synthetic (commandLine), "
                + "opentsx.iceberg.properties=name=synthetic (commandLine), "), start.get(0));
        assertTrue(start.get(0).contains(", opentsx.retrieval.dagRunToken=****** (environment), "), start.get(0));
        assertFalse(e.err().contains("s3cret-run-token"), e.err());
        Exit quiet = main(Map.of("OPENTSX_LOG_LEVEL", "warn"), "--iceberg", "name=synthetic", "--namespace", "synthetic");
        assertEquals(new Exit(1, "", "opentsx: existing-only factory selected\n"), quiet);
    }

    @Test
    void theEnvironmentAloneDoesNotSwitchTheSource() {
        Map<String, String> env = Map.of("OPENTSX_KAFSCALE_BOOTSTRAP", "127.0.0.1:9", "KAFSCALE_BOOTSTRAP", "127.0.0.1:9",
                "OPENTSX_ICEBERG_PROPERTIES", "type=rest,uri=http://127.0.0.1:9,warehouse=s3://lake/");
        RetrievalServer.Plan plan = plan(env, "--port", "0");
        assertEquals("demo", plan.source());
        assertEquals(null, plan.bootstrap());
        assertEquals(null, plan.iceberg());
        assertEquals("127.0.0.1:9", plan(env, "--source", "kafscale").bootstrap());
        assertEquals(Map.of("type", "rest", "uri", "http://127.0.0.1:9", "warehouse", "s3://lake/"),
                plan(env, "--source", "iceberg").iceberg());
    }

    @Test
    void openDagRunsListenOnLoopbackOnlyAndATokenKeepsEveryInterface() {
        RetrievalServer.Plan open = plan(Map.of(), "--kafscale", "localhost:9", "--dag-runs", "--open-dag-runs");
        assertTrue(open.dagRuns());
        assertEquals(null, open.runToken());
        assertEquals("127.0.0.1", open.address().getAddress().getHostAddress());
        RetrievalServer.Plan token = plan(Map.of("OPENTSX_DAG_RUN_TOKEN", "t0ken"), "--kafscale", "localhost:9", "--dag-runs");
        assertEquals("t0ken", token.runToken());
        assertTrue(token.address().getAddress().isAnyLocalAddress(), token.address().toString());

        RetrievalServer[] started = new RetrievalServer[1];
        Exit e = run(Map.of(), s -> started[0] = s, "--port", "0", "--open-dag-runs");
        try {
            assertEquals(new Exit(0, "", ""), e);
            assertEquals("127.0.0.1", started[0].address().getAddress().getHostAddress());
        } finally {
            started[0].close();
        }
        assertEquals(new Exit(2, "", "opentsx: opentsx.retrieval.bind: \"0.0.0.0\" is not a loopback address; with"
                        + " opentsx.retrieval.openDagRuns the server listens on 127.0.0.1 only; leave --bind unset or give 127.0.0.1\n"),
                run(Map.of(), s -> fail("started"), "--port", "0", "--open-dag-runs", "--bind", "0.0.0.0"));
    }

    @Test
    void bindChoosesTheAddressAndDagRunsNeedKafScale() {
        RetrievalServer[] started = new RetrievalServer[1];
        Exit e = run(Map.of(), s -> started[0] = s, "--port", "0", "--bind", "127.0.0.1");
        try {
            assertEquals(new Exit(0, "", ""), e);
            assertEquals("127.0.0.1", started[0].address().getAddress().getHostAddress());
        } finally {
            started[0].close();
        }
        Exit none = run(Map.of(), s -> fail("started"), "--port", "0", "--bind", "192.0.2.1");
        assertEquals(1, none.code(), none.err());
        assertTrue(none.err().startsWith("opentsx: cannot listen on 192.0.2.1:0; caused by java.net.BindException: "), none.err());
        Exit noKafScale = new Exit(2, "", "opentsx: opentsx.retrieval.dagRuns: true needs the kafscale source; give --kafscale"
                + " <host:port> with --source auto or --source kafscale, or leave --dag-runs off\n");
        assertEquals(noKafScale, run(Map.of(), s -> fail("started"), "--port", "0", "--dag-runs"));
        assertEquals(noKafScale, run(Map.of(), s -> fail("started"), "--port", "0", "--source", "iceberg", "--iceberg",
                "type=hadoop,warehouse=w", "--kafscale", "k:1", "--dag-runs"));
        assertEquals(noKafScale, run(Map.of(), s -> fail("started"), "--port", "0", "--source", "demo", "--dag-runs",
                "--open-dag-runs"));
        assertEquals(new Exit(2, "", "opentsx: opentsx.retrieval.port: \"70000\" is not allowed; expected 0 to 65535, 0 for"
                + " any free port\n"), run(Map.of(), s -> fail("started"), "--port", "70000"));
    }

    @Test
    void aSystemsDirectoryWithoutDocumentsExitsWith2AndNamesIt() throws Exception {
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        assertEquals(new Exit(2, "", "opentsx: " + empty + ": holds no *.system.yaml file; give a directory of system"
                + " documents\n"), main("--port", "0", "--systems", empty.toString()));
    }

    @Test
    void theProblemsOfTheSystemDocumentsAreOneWarnSummaryWithTheDetailsAtDebug() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path dir = Files.createDirectory(tmp.resolve("systems"));
        Path doc = Files.writeString(dir.resolve("h2-chain.system.yaml"), Files.readString(root.resolve("systems/h2-chain.system.yaml"))
                .replace("id: offtake.demand_kgph", "id: offtake.demand_tons").replace("role: estimate", "role: guess"));
        List<String> problems = org.opentsx.retrieval.system.SystemDoc.load(doc, root).problems();
        assertTrue(problems.size() >= 2, problems.toString());
        PrintStream stderr = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Exit exit;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            exit = run(Map.of(), RetrievalServer::close, "--port", "0", "--source", "demo", "--systems", dir.toString(),
                    "--systems-root", root.toString());
        } finally {
            System.setErr(stderr);
        }
        assertEquals(0, exit.code(), exit.err());
        List<String> lines = log.toString(StandardCharsets.UTF_8).lines().toList();
        List<String> warns = lines.stream().filter(l -> l.contains(" WARN ")).toList();
        assertEquals(List.of(" WARN org.opentsx.retrieval.server.RetrievalServer - system documents have problems;"
                        + " GET /v1/systems/<id>/signals lists them systems=h2-chain problems=" + problems.size()),
                warns.stream().map(l -> l.substring(l.indexOf(" WARN "))).toList(), String.join("\n", lines));
        for (String problem : problems) {
            assertFalse(lines.stream().anyMatch(l -> l.contains(problem)), "the problem itself is logged at DEBUG only: " + problem);
        }
        assertEquals("", exit.out());
    }

    @Test
    void sigtermClosesTheServerAndLogsStopped() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path"), RetrievalServer.class.getName(), "--port", "0", "--source", "demo")
                .redirectOutput(tmp.resolve("out.txt").toFile());
        pb.environment().clear();
        Process p = pb.start();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> read = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String l = r.readLine(); l != null; l = r.readLine()) {
                    lines.add(l);
                    if (l.contains(" - listening on ")) {
                        p.toHandle().destroy();     // SIGTERM, as the kubelet sends it; Process.destroy would close this stream
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            read.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            p.destroyForcibly().waitFor();
            fail("the server did not end within 60 s:\n" + String.join("\n", lines));
        }
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(143, p.exitValue(), String.join("\n", lines));
        List<String> end = lines.subList(lines.size() - 2, lines.size()).stream().map(l -> l.substring(l.indexOf("] ") + 2)).toList();
        assertTrue(end.get(0).startsWith("INFO org.opentsx.retrieval.server.RetrievalServer - stopping port="), String.join("\n", lines));
        assertEquals("INFO org.opentsx.retrieval.server.RetrievalServer - stopped", end.get(1), String.join("\n", lines));
        assertEquals("", Files.readString(tmp.resolve("out.txt")));
    }

    @Test
    void theArgumentsOfTheK3sManifestsLoad() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path k3s = Path.of("..", "deploy", "k3s");
        JsonNode kustomization = yaml.readTree(k3s.resolve("base/kustomization.yaml").toFile());
        Map<String, String> config = new HashMap<>();
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            config.put(kv[0], kv[1]);
        }
        // what 20-configure.sh writes into the overlay for LAKE=true and RETRIEVAL_DAG_RUNS=true
        config.put("ICEBERG_PROPS", "type=rest,uri=http://opentsx-iceberg-rest:8181,warehouse=s3://lake/");
        config.put("RETRIEVAL_DAG_RUNS", "true");
        Map<String, String> links = new HashMap<>();
        for (JsonNode resource : kustomization.path("resources")) {
            for (JsonNode doc : documents(yaml, k3s.resolve("base").resolve(resource.asText()))) {
                if (doc.path("kind").asText().equals("Service")) {
                    links.putAll(serviceLinks(doc));
                }
            }
        }
        JsonNode retrieval = deployment(yaml, k3s.resolve("base/retrieval.yaml"));
        JsonNode vectors = deployment(yaml, k3s.resolve("components/vector-processor/vector-processor.yaml"));

        OpenTsxConf demo = load(retrieval, config, links);
        assertEquals("demo", demo.get("opentsx.retrieval.source"));
        assertEquals(8080, demo.getInt("opentsx.retrieval.port"));

        OpenTsxConf kafscale = load(patched(yaml, retrieval, k3s.resolve("components/retrieval-kafscale/kustomization.yaml")),
                config, links);
        assertEquals("kafscale", kafscale.get("opentsx.retrieval.source"));
        assertEquals("kafscale.invalid:9092", kafscale.get("opentsx.kafscale.bootstrap"));
        assertEquals("environment", kafscale.source("opentsx.kafscale.bootstrap"));
        assertTrue(kafscale.getBoolean("opentsx.retrieval.dagRuns"));
        assertTrue(kafscale.contains("opentsx.retrieval.dagRunToken"));
        assertEquals("/opt/opentsx/share/repo", kafscale.get("opentsx.retrieval.systemsRoot"));

        OpenTsxConf lake = load(patched(yaml, retrieval, k3s.resolve("components/lake/kustomization.yaml")), config, links);
        assertEquals("iceberg", lake.get("opentsx.retrieval.source"));
        assertEquals("environment", lake.source("opentsx.iceberg.properties"));
        assertEquals("tsx", lake.get("opentsx.iceberg.namespace"));

        OpenTsxConf run = load(vectors, config, links);
        assertEquals("/state", run.get("opentsx.vectors.state"));
        assertEquals("wind", run.get("opentsx.domain"));
    }

    /** Loads the arguments of the first container of a Deployment with the environment Kubernetes gives its pod. */
    private static OpenTsxConf load(JsonNode deployment, Map<String, String> config, Map<String, String> links) {
        JsonNode podSpec = deployment.path("spec").path("template").path("spec");
        JsonNode container = podSpec.path("containers").get(0);
        Map<String, String> env = new HashMap<>();
        if (podSpec.path("enableServiceLinks").asBoolean(true)) {
            env.putAll(links);
        }
        for (JsonNode e : container.path("env")) {
            JsonNode from = e.path("valueFrom");
            String value = from.has("secretKeyRef") ? "0123456789abcdefghijklmnopqrstuvwxyz"
                    : config.get(from.path("configMapKeyRef").path("key").asText());
            assertNotNull(value, e.toString());
            env.put(e.path("name").asText(), value);
        }
        List<String> args = new ArrayList<>();
        Pattern reference = Pattern.compile("\\$\\(([A-Z_]+)\\)");
        for (JsonNode a : container.path("args")) {
            Matcher m = reference.matcher(a.asText());
            args.add(m.replaceAll(r -> Matcher.quoteReplacement(env.get(r.group(1)))));
        }
        return OpenTsxConf.load(args, env, null).conf();
    }

    private static List<JsonNode> documents(ObjectMapper yaml, Path file) throws IOException {
        return yaml.readValues(yaml.createParser(file.toFile()), JsonNode.class).readAll();
    }

    private static JsonNode deployment(ObjectMapper yaml, Path file) throws IOException {
        for (JsonNode doc : documents(yaml, file)) {
            if (doc.path("kind").asText().equals("Deployment")) {
                return doc;
            }
        }
        throw new AssertionError(file + ": no Deployment");
    }

    /** The Deployment with the replace and add operations of the component's patches applied. */
    private static JsonNode patched(ObjectMapper yaml, JsonNode deployment, Path component) throws IOException {
        JsonNode out = deployment.deepCopy();
        int applied = 0;
        for (JsonNode patch : yaml.readTree(component.toFile()).path("patches")) {
            if (!patch.path("target").path("name").asText().equals(deployment.path("metadata").path("name").asText())) {
                continue;
            }
            for (JsonNode op : yaml.readTree(patch.path("patch").asText())) {
                String[] path = op.path("path").asText().substring(1).split("/");
                JsonNode parent = out;
                for (int i = 0; i < path.length - 1; i++) {
                    parent = parent.isArray() ? parent.get(Integer.parseInt(path[i])) : parent.get(path[i]);
                }
                String last = path[path.length - 1];
                if (parent instanceof ArrayNode a) {
                    a.set(Integer.parseInt(last), op.get("value"));
                } else {
                    ((ObjectNode) parent).set(last, op.get("value"));
                }
                applied++;
            }
        }
        assertTrue(applied > 0, component + " patches nothing of " + deployment.path("metadata").path("name").asText());
        return out;
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
