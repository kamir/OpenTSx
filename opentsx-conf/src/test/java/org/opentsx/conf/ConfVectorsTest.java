package org.opentsx.conf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Runs every case of the shared vectors docs/datamodel-2026/testvectors/conf.v1.json, which Python runs as well. */
class ConfVectorsTest {

    static final Path VECTORS = Path.of("..", "docs/datamodel-2026/testvectors/conf.v1.json").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    @TestFactory
    Stream<DynamicTest> everyCase() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode doc = mapper.readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.conf.v1", doc.get("schema").asText());
        Registry fixture = Registry.parse(mapper.writeValueAsBytes(doc.get("registry")));
        List<JsonNode> cases = new ArrayList<>();
        doc.get("cases").forEach(cases::add);
        assertEquals(286, cases.size());
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.get("id").asText(), () -> {
            Path dir = Files.createDirectory(tmp.resolve(c.get("id").asText()));
            String level = System.getProperty(OpenTsxConf.SLF4J_LEVEL);
            try {
                run("shipped".equals(c.path("registry").asText()) ? Registry.shipped() : fixture, c, dir.toString());
            } finally {
                if (level == null) {
                    System.clearProperty(OpenTsxConf.SLF4J_LEVEL);
                } else {
                    System.setProperty(OpenTsxConf.SLF4J_LEVEL, level);
                }
            }
        }));
    }

    static void run(Registry reg, JsonNode c, String dir) throws IOException {
        JsonNode expect = c.get("expect");
        switch (c.get("kind").asText()) {
            case "keyGrammar" -> assertEquals(c.get("valid").asBoolean(), Registry.validKey(c.get("key").asText()));
            case "envName" -> assertEquals(c.get("name").asText(), Registry.envName(c.get("key").asText()));
            case "value" -> value(reg, c.get("key").asText(), c.get("input").asText(), expect);
            case "properties", "yaml" -> file(reg, c, dir, expect);
            case "load" -> load(reg, c, dir, expect);
            case "notSet" -> assertEquals(expect.get("error").asText(),
                    assertThrows(OpenTsxConfException.class, () -> new OpenTsxConf(reg).get(c.get("key").asText())).getMessage());
            case "show" -> show(reg, c, expect);
            case "debugString" -> {
                OpenTsxConf.Loaded loaded = OpenTsxConf.load(reg, strings(c.get("args"), dir), env(c), null);
                assertEquals(expect.get("text").asText(), loaded.conf().toDebugString());
                assertEquals(expect.get("line").asText(), loaded.startLine());
            }
            case "api" -> assertEquals(strings(c.get("names"), dir), publicNames());
            default -> fail("unknown kind " + c.get("kind").asText());
        }
    }

    static void value(Registry reg, String key, String input, JsonNode expect) {
        OpenTsxConf conf = new OpenTsxConf(reg);
        if (expect.has("error")) {
            assertEquals(expect.get("error").asText(), assertThrows(OpenTsxConfException.class, () -> conf.set(key, input)).getMessage());
            return;
        }
        conf.set(key, input);
        assertEquals(expect.get("text").asText(), conf.get(key));
        assertEquals("code", conf.source(key));
        Registry.Key e = reg.entry(key, key);
        JsonNode v = expect.get("value");
        switch (e.type()) {
            case "int" -> assertEquals(v.asLong(), conf.getInt(key));
            case "long" -> assertEquals(v.asLong(), conf.getLong(key));
            case "double" -> assertEquals(v.asDouble(), conf.getDouble(key));
            case "boolean" -> assertEquals(v.asBoolean(), conf.getBoolean(key));
            case "duration" -> assertEquals(v.get("millis").asLong(), conf.getDuration(key).toMillis());
            case "list" -> {
                List<Object> want = new ArrayList<>();
                List<Object> got = new ArrayList<>();
                if ("duration".equals(e.items())) {
                    v.forEach(x -> want.add(x.get("millis").asLong()));
                    conf.getDurationList(key).forEach(d -> got.add(d.toMillis()));
                } else {
                    v.forEach(x -> want.add(x.asText()));
                    got.addAll(conf.getList(key));
                }
                assertEquals(want, got);
            }
            default -> assertEquals(v.asText(), conf.get(key));
        }
    }

    static List<String> publicNames() {
        TreeSet<String> names = new TreeSet<>();
        for (Method m : OpenTsxConf.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        for (Field f : OpenTsxConf.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        return List.copyOf(names);
    }

    static void file(Registry reg, JsonNode c, String dir, JsonNode expect) throws IOException {
        String path = c.get("file").asText().replace("{dir}", dir);
        Files.write(Path.of(path), c.has("base64") ? Base64.getDecoder().decode(c.get("base64").asText())
                : c.get("text").asText().getBytes(StandardCharsets.UTF_8));
        if (expect.has("pairs")) {
            List<List<String>> want = new ArrayList<>();
            expect.get("pairs").forEach(p -> want.add(List.of(p.get(0).asText(), p.get(1).asText())));
            List<List<String>> got = new ArrayList<>();
            ConfParser.readFile(reg, path).forEach((k, v) -> got.add(List.of(k, v)));
            assertEquals(want, got);
            return;
        }
        String message = assertThrows(OpenTsxConfException.class, () -> ConfParser.readFile(reg, path)).getMessage();
        if (expect.has("error")) {
            assertEquals(expect.get("error").asText().replace("{dir}", dir), message);
        } else {
            String prefix = expect.get("errorStartsWith").asText().replace("{dir}", dir);
            assertTrue(message.startsWith(prefix) && message.length() > prefix.length() && !message.contains("\n"), message);
        }
    }

    static void load(Registry reg, JsonNode c, String dir, JsonNode expect) throws IOException {
        if (c.has("files")) {
            for (Map.Entry<String, JsonNode> f : c.get("files").properties()) {
                Path p = Path.of(dir, f.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.getValue().asText(), StandardCharsets.UTF_8);
            }
        }
        if (c.has("dirs")) {
            for (JsonNode d : c.get("dirs")) {
                Files.createDirectories(Path.of(dir, d.asText()));
            }
        }
        List<String> args = strings(c.get("args"), dir);
        Map<String, String> env = env(c);
        String confDir = c.has("confDir") ? c.get("confDir").asText().replace("{dir}", dir) : null;
        if (expect.has("error")) {
            String want = expect.get("error").asText().replace("{dir}", dir);
            assertEquals(2, expect.get("exit").asInt());
            String got = assertThrows(OpenTsxConfException.class, () -> {
                OpenTsxConf conf = OpenTsxConf.load(reg, args, env, confDir).conf();
                apply(conf, c);
            }).getMessage();
            assertEquals(want, got);
            if (!c.has("set") && !c.has("setIfMissing")) {
                Map<String, String> cliEnv = new HashMap<>(env);
                if (confDir != null) {
                    cliEnv.put("OPENTSX_CONF_DIR", confDir);
                }
                String[] streams = new String[2];
                assertEquals(2, cli(reg, args, cliEnv, streams));
                assertEquals("", streams[0]);
                assertEquals("opentsx: " + want + "\n", streams[1]);
            }
            return;
        }
        OpenTsxConf.Loaded loaded = OpenTsxConf.load(reg, args, env, confDir);
        assertEquals(expect.get("command").isNull() ? null : expect.get("command").asText(), loaded.command());
        if (expect.has("help") || expect.has("version")) {
            assertEquals(expect.has("help"), loaded.help());
            assertEquals(expect.has("version"), loaded.version());
            assertNull(loaded.conf());
            return;
        }
        apply(loaded.conf(), c);
        assertEquals(strings(expect.get("positional"), dir), loaded.args());
        for (Map.Entry<String, JsonNode> v : expect.get("values").properties()) {
            assertEquals(v.getValue().get("value").asText().replace("{dir}", dir), loaded.conf().get(v.getKey()), v.getKey());
            assertEquals(v.getValue().get("source").asText(), loaded.conf().source(v.getKey()), v.getKey());
        }
        for (JsonNode k : expect.path("unset")) {
            assertTrue(loaded.conf().getOption(k.asText()).isEmpty(), k.asText());
            assertNull(loaded.conf().source(k.asText()), k.asText());
        }
    }

    static void apply(OpenTsxConf conf, JsonNode c) {
        for (JsonNode p : c.path("set")) {
            conf.set(p.get(0).asText(), p.get(1).asText());
        }
        for (JsonNode p : c.path("setIfMissing")) {
            conf.setIfMissing(p.get(0).asText(), p.get(1).asText());
        }
    }

    static void show(Registry reg, JsonNode c, JsonNode expect) {
        String[] streams = new String[2];
        assertEquals(expect.get("exit").asInt(), cli(reg, strings(c.get("args"), ""), env(c), streams));
        assertEquals(expect.path("stdout").asText(""), streams[0]);
        assertEquals(expect.path("stderr").asText(""), streams[1]);
    }

    static int cli(Registry reg, List<String> args, Map<String, String> env, String[] streams) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = ConfCli.run(reg, args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        streams[0] = out.toString(StandardCharsets.UTF_8);
        streams[1] = err.toString(StandardCharsets.UTF_8);
        return exit;
    }

    static List<String> strings(JsonNode array, String dir) {
        List<String> out = new ArrayList<>();
        array.forEach(a -> out.add(a.asText().replace("{dir}", dir)));
        return out;
    }

    static Map<String, String> env(JsonNode c) {
        Map<String, String> out = new HashMap<>();
        c.path("env").properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
