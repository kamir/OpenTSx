package org.opentsx.modbus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.poll.Poller;
import org.opentsx.modbus.sim.MeterSimulator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code bin/opentsx modbus ...}: options, help and the failure lines of {@code poll} (no device needed). */
class ModbusCliTest {

    static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    @TempDir
    Path dir;

    record Result(int code, String out, String err) {
    }

    static Result cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ModbusCli.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aMistypedOptionIsRefusedWithTheNearestOption() {
        assertEquals(new Result(2, "", "opentsx: --prot: unknown option of modbus check; did you mean --port?\n"),
                cli(Map.of(), "check", "--map", "example", "--prot", "99"));
        assertEquals(new Result(2, "", "opentsx: --port: missing value; expected --port <int>\n"),
                cli(Map.of(), "check", "--map", "example", "--port"));
    }

    @Test
    void malformedAndOutOfRangeValuesAreUsageErrorsWithoutATrace() {
        assertEquals(new Result(2, "", "opentsx: --port: \"abc\" is not an int; expected a whole number from -2147483648 to "
                + "2147483647\n"), cli(Map.of(), "check", "--map", "example", "--port", "abc"));
        assertEquals(new Result(2, "", "opentsx: opentsx.modbus.port: 70000 is not allowed; expected 1 to 65535\n"),
                cli(Map.of(), "read", "--map", "example", "--port", "70000"));
        assertEquals(new Result(2, "", "opentsx: opentsx.modbus.unitId: 256 is not allowed; expected 0 to 255\n"),
                cli(Map.of(), "check", "--map", "example", "--unit", "256"));
        assertEquals(new Result(2, "", "opentsx: opentsx.modbus.interval: 0s is not allowed; expected a positive duration "
                + "such as 5s\n"), cli(Map.of(), "poll", "--map", "example", "--simulate", "--interval", "0s"));
        assertEquals(new Result(2, "", "opentsx: opentsx.modbus.count: -1 is not allowed; expected 0 (poll until stopped) "
                + "or more\n"), cli(Map.of(), "poll", "--map", "example", "--simulate", "--count", "-1"));
        assertEquals(new Result(2, "", "opentsx: OPENTSX_MODBUS_PORT: \"abc\" is not an int; expected a whole number from "
                + "-2147483648 to 2147483647\n"), cli(Map.of("OPENTSX_MODBUS_PORT", "abc"), "check", "--map", "example"));
    }

    @Test
    void theMapIsRequiredAndAMissingMapFileIsAUsageError() {
        assertEquals(new Result(2, "", "opentsx: opentsx.modbus.map: not set; set it with --map, --conf "
                + "opentsx.modbus.map=<value> or OPENTSX_MODBUS_MAP\n"), cli(Map.of(), "check"));
        Path missing = dir.resolve("x.yaml");
        assertEquals(new Result(2, "", "opentsx: " + missing + ": no such file; give an existing register map file or example\n"),
                cli(Map.of(), "check", "--map", missing.toString()));
        assertEquals(new Result(2, "", "opentsx: modbus frob: unknown command; expected one of modbus check, modbus poll, "
                + "modbus read, modbus simulate\n"), cli(Map.of(), "frob", "--map", "example"));
    }

    @Test
    void anInvalidMapIsAUsageError() throws IOException {
        Path map = Files.writeString(dir.resolve("bad.yaml"), "schema: opentsx.modbus.registermap.v1\nname: t\n");
        Result r = cli(Map.of(), "check", "--map", map.toString());
        assertEquals(2, r.code(), r.toString());
        assertTrue(r.err().startsWith("opentsx: register map " + map + " is invalid:"), r.err());
        assertEquals(1, r.err().lines().count(), r.err());
        assertTrue(r.err().contains(" - points: at least one point is required"), r.err());
    }

    @Test
    void checkPrintsThePlanAndMarksTheExample() {
        Result r = cli(Map.of(), "check", "--map", "example");
        assertEquals(0, r.code(), r.toString());
        assertTrue(r.out().contains("read plan (5 requests per cycle)"), r.out());
        assertEquals("note: register map 'example-grid-meter' is an EXAMPLE, not a real register sheet\n", r.err());
        Result env = cli(Map.of("OPENTSX_MODBUS_INTERVAL", "1s"), "check", "--map", "example");
        assertTrue(env.out().contains(", poll PT5S\n"), "check does not read opentsx.modbus.interval: " + env.out());
    }

    @Test
    void theHelpOfSimulateShowsTheLoopbackBindDefault() {
        Result r = cli(Map.of(), "simulate", "--help");
        assertEquals(new Result(0, Registry.shipped().help("modbus simulate"), ""), r);
        assertTrue(r.out().contains("  opentsx.modbus.simulator.bind  string = 127.0.0.1  --bind  OPENTSX_MODBUS_SIMULATOR_BIND\n"),
                r.out());
        assertFalse(r.out().contains("0.0.0.0"), r.out());
        assertFalse(r.out().contains("parses its own flags"), r.out());
        assertEquals(new Result(0, Registry.shipped().help(), ""), cli(Map.of(), "--help"));
        assertEquals(new Result(0, ConfCli.versionLine(), ""), cli(Map.of(), "poll", "--version"));
    }

    @Test
    void aDeviceThatFailsEveryCycleGivesOneFailureLineAndOneCountLinePerMinute() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ModbusCli.ReadFailures failures = new ModbusCli.ReadFailures(new PrintStream(buf, true, StandardCharsets.UTF_8));
        RegisterMap map = RegisterMap.exampleGridMeter();
        try (MeterSimulator sim = MeterSimulator.local(map, 7, T0);
             Poller poller = Poller.connect(sim.clientMap())) {
            for (int k = 0; k < 100; k++) {
                sim.server().failNext(4);
                failures.cycle(poller.poll(T0.plusSeconds(5L * k)));
            }
            failures.cycle(poller.poll(T0.plusSeconds(500)));
            failures.finish();
        }
        List<String> lines = buf.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(10, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).startsWith("2026-10-12T10:00:00Z read failed: discrete 0..0"), lines.get(0));
        assertTrue(lines.get(0).contains("SERVER_DEVICE_FAILURE"), lines.get(0));
        assertTrue(lines.get(1).startsWith("2026-10-12T10:01:00Z read failed 12 more times since 2026-10-12T10:00:00Z; last: "
                + "discrete 0..0"), lines.get(1));
        assertTrue(lines.get(8).startsWith("2026-10-12T10:08:00Z read failed 12 more times since 2026-10-12T10:07:00Z; "),
                lines.get(8));
        assertEquals("2026-10-12T10:08:20Z reads succeed again after 100 failed reads since 2026-10-12T10:00:00Z", lines.get(9));
    }

    @Test
    void theFailuresNotYetCountedArePrintedWhenThePollEnds() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ModbusCli.ReadFailures failures = new ModbusCli.ReadFailures(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try (MeterSimulator sim = MeterSimulator.local(RegisterMap.exampleGridMeter(), 7, T0);
             Poller poller = Poller.connect(sim.clientMap())) {
            for (int k = 0; k < 3; k++) {
                sim.server().failNext(4, poller.blocks().size());
                failures.cycle(poller.poll(T0.plusSeconds(k)));
            }
        }
        failures.finish();
        List<String> lines = buf.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(1).startsWith("2026-10-12T10:00:02Z read failed 14 more times since 2026-10-12T10:00:00Z; last: "),
                lines.get(1));
    }

    /** The pod of a manifest in the namespace of deploy/k3s/base: its env with the config map and the Service links. */
    static Map<String, String> podEnv(JsonNode pod) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path base = Path.of("../deploy/k3s/base");
        JsonNode kustomization = yaml.readTree(base.resolve("kustomization.yaml").toFile());
        Map<String, String> literals = new HashMap<>();
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            literals.put(kv[0], kv[1]);
        }
        Map<String, String> env = new HashMap<>();
        if (pod.path("enableServiceLinks").asBoolean(true)) {
            for (JsonNode resource : kustomization.path("resources")) {
                for (JsonNode doc : yaml.readValues(yaml.createParser(base.resolve(resource.asText()).toFile()), JsonNode.class)
                        .readAll()) {
                    if (doc.path("kind").asText().equals("Service")) {
                        String name = doc.path("metadata").path("name").asText().toUpperCase(Locale.ROOT).replace('-', '_');
                        String port = doc.path("spec").path("ports").get(0).path("port").asText();
                        env.put(name + "_SERVICE_HOST", "10.43.0.10");
                        env.put(name + "_SERVICE_PORT", port);
                        env.put(name + "_PORT", "tcp://10.43.0.10:" + port);
                    }
                }
            }
            assertTrue(env.containsKey("OPENTSX_RETRIEVAL_PORT"), env.toString());
        }
        for (JsonNode e : pod.path("containers").get(0).path("env")) {
            JsonNode ref = e.path("valueFrom").path("configMapKeyRef");
            env.put(e.path("name").asText(), ref.isMissingNode() ? e.path("value").asText() : literals.get(ref.path("key").asText()));
        }
        return env;
    }

    @Test
    void theJobModbusPollLoadsItsConfigurationInTheNamespaceOfTheBase() throws IOException {
        JsonNode pod = new ObjectMapper(new YAMLFactory()).readTree(Path.of("../deploy/k3s/jobs/modbus-poll.yaml").toFile())
                .path("spec").path("template").path("spec");
        Map<String, String> env = podEnv(pod);
        String script = pod.path("containers").get(0).path("args").get(0).asText().replace("\\\n", " ");
        String line = script.lines().filter(l -> l.contains("opentsx modbus ")).findFirst().orElseThrow();
        String cmd = line.substring(line.indexOf("opentsx modbus ") + "opentsx ".length()).replace("\"", "")
                .replace("$f", "/data/modbus/episodes.jsonl");
        List<String> argv = new ArrayList<>(List.of(cmd.strip().split("\\s+")));
        OpenTsxConf conf = OpenTsxConf.load(argv, env, null).conf();
        assertEquals("modbus-poll", pod.path("containers").get(0).path("name").asText());
        assertEquals(1502, conf.getInt("opentsx.modbus.port"));
        assertEquals(30, conf.getLong("opentsx.modbus.count"));
        assertEquals("episodes", conf.get("opentsx.modbus.out"));
        argv.addAll(List.of("--count", "300"));
        assertEquals(300, OpenTsxConf.load(argv, env, null).conf().getLong("opentsx.modbus.count"),
                "40-demo.sh modbus -- --count 300 appends to the command through OPENTSX_EXTRA_ARGS");
    }

    @Test
    void theSimulatorDeploymentLoadsItsConfigurationInTheNamespaceOfTheBase() throws IOException {
        JsonNode deployment = new ObjectMapper(new YAMLFactory()).readValues(new YAMLFactory()
                .createParser(Path.of("../deploy/k3s/components/modbus-sim/modbus-sim.yaml").toFile()), JsonNode.class).next();
        JsonNode pod = deployment.path("spec").path("template").path("spec");
        List<String> argv = new ArrayList<>();
        pod.path("containers").get(0).path("args").forEach(a -> argv.add(a.asText()));
        OpenTsxConf conf = OpenTsxConf.load(argv, podEnv(pod), null).conf();
        assertEquals("0.0.0.0", conf.get("opentsx.modbus.simulator.bind"));
        assertEquals(1502, conf.getInt("opentsx.modbus.simulator.port"));
    }
}
