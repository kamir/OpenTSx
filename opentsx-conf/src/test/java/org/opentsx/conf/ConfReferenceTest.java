package org.opentsx.conf;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Appendix D and the two defaults templates are the rendering of the registry, and the templates read back as the defaults. */
class ConfReferenceTest {

    static final Path ROOT = RegistryTest.ROOT;
    static final Pattern PROPERTIES_KEY_LINE = Pattern.compile("^# (opentsx\\.[A-Za-z0-9.]+)(?: (.+))?$");

    static Map<String, String> rendered() {
        return ConfReference.render(Registry.shipped(), ConfCli.version());
    }

    @Test
    void theCheckedInFilesAreTheRendering() throws IOException {
        for (Map.Entry<String, String> f : rendered().entrySet()) {
            assertEquals(f.getValue(), Files.readString(ROOT.resolve(f.getKey()), StandardCharsets.UTF_8),
                    f.getKey() + " is out of date; regenerate it from the repository root: " + ConfReference.REGENERATE);
        }
    }

    @Test
    void theAppendixHasOneRowPerKeyAndMarksTheSecrets() {
        String page = rendered().get(ConfReference.APPENDIX);
        Registry reg = Registry.shipped();
        List<String> rows = page.lines().filter(l -> l.startsWith("| `opentsx.")).toList();
        assertEquals(reg.keys().size(), rows.size());
        for (Registry.Key e : reg.keys()) {
            List<String> row = rows.stream().filter(r -> r.startsWith("| `" + e.key() + "` |")).toList();
            assertEquals(1, row.size(), e.key());
            String[] cells = row.get(0).split(" \\| ");
            assertEquals(e.secret(), cells[5].startsWith("**Secret.** "), e.key());
            if (e.secret()) {
                assertEquals("none", cells[3], e.key());
            }
        }
        assertEquals(19, page.lines().filter(l -> l.startsWith("## opentsx.")).count());
        assertTrue(page.contains("| `opentsx.domain` | `string` | `wind` | `--domain` | `OPENTSX_DOMAIN` | Domain of the topic names "
                + "tsx.\\<domain\\>.\\<kind\\>.v2. | 3.0.1 |"));
        assertTrue(page.contains("| `opentsx.kafscale.bootstrap` | `string` | none | `--kafscale`, `--bootstrap` | "
                + "`OPENTSX_KAFSCALE_BOOTSTRAP`, `KAFSCALE_BOOTSTRAP` |"));
        assertTrue(page.contains("| `opentsx.modbus.out` | `enum`, one of `csv`, `episodes` | `csv` |"));
        assertTrue(page.contains("| `opentsx.market.fetchDelayMs` | `long`, unit `ms` | `300` |"));
    }

    @Test
    void theReaderStatementsNameEveryCommandThatLoadsTheConfiguration() {
        String appendix = rendered().get(ConfReference.APPENDIX);
        int start = appendix.indexOf("The Env column");
        String statement = appendix.substring(start, appendix.indexOf("\n\n", start)).replace('\n', ' ');
        for (String text : List.of(statement, ConfReference.READERS.replace("\n# ", " "))) {
            for (String command : Registry.LOADS_CONF) {
                assertTrue(text.contains(command), command + " is missing in: " + text);
            }
        }
    }

    @Test
    void thePropertiesTemplateNamesEveryKeyAndReadsBackAsTheDefaults() {
        String template = rendered().get(ConfReference.PROPERTIES_TEMPLATE);
        Registry reg = Registry.shipped();
        Map<String, String> named = new TreeMap<>();
        StringBuilder uncommented = new StringBuilder();
        for (String line : template.lines().toList()) {
            Matcher m = PROPERTIES_KEY_LINE.matcher(line);
            if (m.matches()) {
                assertEquals(null, named.put(m.group(1), String.valueOf(m.group(2))), m.group(1));
                if (m.group(2) != null) {
                    uncommented.append(line.substring(2)).append('\n');
                }
            }
        }
        assertEquals(expected(reg), named);
        assertEquals(defaults(reg), ConfParser.readProperties(reg, "opentsx-defaults.conf", uncommented.toString()));
    }

    @Test
    void theYamlTemplateNestsEveryKeyAndReadsBackAsTheDefaults() throws IOException {
        String template = rendered().get(ConfReference.YAML_TEMPLATE);
        Registry reg = Registry.shipped();
        StringBuilder uncommented = new StringBuilder();
        boolean body = false;
        for (String line : template.lines().toList()) {
            body |= line.equals("# opentsx:");
            if (body) {
                assertTrue(line.startsWith("# "), line);
                uncommented.append(line.substring(2)).append('\n');
            }
        }
        assertEquals(expected(reg), flatten(uncommented.toString()));
    }

    @Test
    void writeRendersTheRegistryFileBelowTheRoot(@TempDir Path root) throws IOException {
        Path registry = root.resolve(ConfReference.REGISTRY);
        Files.createDirectories(registry.getParent());
        Files.write(registry, Registry.shipped().json());
        assertEquals(List.of(ConfReference.APPENDIX, ConfReference.PROPERTIES_TEMPLATE, ConfReference.YAML_TEMPLATE),
                ConfReference.write(root));
        for (Map.Entry<String, String> f : rendered().entrySet()) {
            assertEquals(f.getValue(), Files.readString(root.resolve(f.getKey()), StandardCharsets.UTF_8));
        }
    }

    @Test
    void yamlScalarsThatAreNotPlainAreQuoted() {
        assertEquals("\"*\"", ConfReference.yamlScalar("*"));
        assertEquals("\"null\"", ConfReference.yamlScalar("null"));
        assertEquals("\"a \\\"b\\\"\"", ConfReference.yamlScalar("a \"b\""));
        assertEquals("0.30", ConfReference.yamlScalar("0.30"));
        assertEquals("[PT10M, PT1H]", ConfReference.yamlList("PT10M,PT1H"));
    }

    /** Every key with its default, "null" for a key without one. */
    static Map<String, String> expected(Registry reg) {
        Map<String, String> out = new TreeMap<>();
        for (Registry.Key e : reg.keys()) {
            out.put(e.key(), String.valueOf(e.defaultValue()));
        }
        return out;
    }

    static Map<String, String> defaults(Registry reg) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Registry.Key e : reg.keys()) {
            if (e.defaultValue() != null) {
                out.put(e.key(), e.defaultValue());
            }
        }
        return out;
    }

    /** Dotted key to the scalar text as the loader reads it; a sequence joined by commas; an empty value as "null". */
    static Map<String, String> flatten(String yaml) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Deque<String> path = new ArrayDeque<>();
        List<String> items = null;
        try (YAMLParser p = (YAMLParser) new YAMLFactory().createParser(yaml)) {
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                if (t == JsonToken.FIELD_NAME) {
                    path.addLast(p.getText());
                } else if (t == JsonToken.START_ARRAY) {
                    items = new ArrayList<>();
                } else if (t == JsonToken.END_ARRAY) {
                    out.put(String.join(".", path), String.join(",", items));
                    items = null;
                    path.removeLast();
                } else if (t == JsonToken.END_OBJECT) {
                    path.pollLast();
                } else if (t.isScalarValue() && items != null) {
                    items.add(p.getText());
                } else if (t.isScalarValue()) {
                    out.put(String.join(".", path), t == JsonToken.VALUE_NULL ? "null" : p.getText());
                    path.removeLast();
                }
            }
        }
        return out;
    }
}
