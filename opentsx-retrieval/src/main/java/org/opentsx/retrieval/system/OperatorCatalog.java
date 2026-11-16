package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The operator chapter of the user manual, rendered from the processor descriptions ({@code systems/processors/*.yaml},
 * schema {@code opentsx.processor.v1}). {@link #problems} checks that every runtime marked available names code that
 * exists, that every planned runtime names its plan, and that examples, charts and links exist.
 *
 * <pre>java -cp "$(bin/opentsx classpath opentsx-retrieval)" org.opentsx.retrieval.system.OperatorCatalog</pre>
 */
public final class OperatorCatalog {

    public static final String PAGE = "docs/user-manual/17-operators.md";
    static final List<String> RUNTIMES = List.of("workbench", "java", "python", "processor", "go");
    static final Set<String> LEGACY_MODULES = Set.of("opentsx-core", "opentsx-data", "opentsx-lg", "opentsx-flink-core", "opentsx-connectors");
    static final Map<String, String> CATEGORIES = categories();

    private OperatorCatalog() {
    }

    private static Map<String, String> categories() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("source", "Generate series");
        m.put("quality", "Check data quality");
        m.put("cleaning", "Clean and convert values");
        m.put("alignment", "Resample and align series");
        m.put("segmentation", "Cut series into episodes");
        m.put("statistics", "Compute statistics");
        m.put("transform", "Transform values");
        m.put("distribution", "Describe value distributions");
        m.put("spectral", "Compute spectra");
        m.put("fluctuation", "Measure fluctuations and extreme events");
        m.put("dependency", "Measure dependency between series");
        m.put("surrogate", "Test against surrogates");
        m.put("pattern", "Search for patterns");
        m.put("feature", "Build model features");
        m.put("model", "Apply physical models");
        m.put("forecast", "Forecast");
        m.put("network", "Reconstruct networks");
        return m;
    }

    public static List<JsonNode> load(Path root) {
        try (Stream<Path> files = Files.list(root.resolve("systems/processors"))) {
            return files.filter(f -> f.toString().endsWith(".yaml"))
                    .map(SystemDoc::read)
                    .sorted(Comparator.comparingInt((JsonNode d) -> categoryIndex(d)).thenComparing(d -> d.path("id").asText()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int categoryIndex(JsonNode d) {
        int i = new ArrayList<>(CATEGORIES.keySet()).indexOf(d.path("category").asText());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Problems that keep the descriptions out of step with the code, one line each; empty when in step. */
    public static List<String> problems(Path root, List<JsonNode> ops) {
        List<String> problems = new ArrayList<>();
        for (JsonNode d : ops) {
            String id = d.path("id").asText();
            if (!CATEGORIES.containsKey(d.path("category").asText())) {
                problems.add(id + ": category is missing");
            }
            boolean planned = false;
            boolean current = false;
            for (String rt : RUNTIMES) {
                JsonNode r = d.path("runtimes").path(rt);
                String status = r.path("status").asText();
                planned |= status.equals("planned");
                if (!status.equals("available")) {
                    continue;
                }
                String problem = switch (rt) {
                    case "java", "processor" -> checkJava(root, r.path("class").asText());
                    case "python" -> checkPython(root, r.path("class").asText());
                    case "workbench" -> checkWorkbench(root, r.path("class").asText(id));
                    default -> exists(root, r.path("class").asText()) ? null : "names no file of the repository";
                };
                if (problem != null) {
                    problems.add(id + "@" + rt + ": " + problem);
                }
                current |= !legacy(root, rt, r);
            }
            if (planned && !d.has("plan")) {
                problems.add(id + ": a runtime is planned but plan is missing");
            }
            if (d.has("plan") && !exists(root, d.path("plan").path("file").asText())) {
                problems.add(id + ": plan file " + d.path("plan").path("file").asText() + " does not exist");
            }
            if (d.has("docs") && !exists(root, d.path("docs").asText().replaceFirst("#.*$", ""))) {
                problems.add(id + ": docs " + d.path("docs").asText() + " does not exist");
            }
            if (d.has("testvectors") && !exists(root, d.path("testvectors").asText())) {
                problems.add(id + ": testvectors " + d.path("testvectors").asText() + " does not exist");
            }
            if (current && !d.has("example")) {
                problems.add(id + ": an operator with a current runtime needs an example");
            }
            if (d.has("example")) {
                String source = d.path("example").path("source").asText();
                if (!exists(root, source)) {
                    problems.add(id + ": example source " + source + " does not exist");
                } else if (usage(root, source) == null) {
                    problems.add(id + ": example source " + source + " has no usage:begin / usage:end block");
                } else if (run(source) == null) {
                    problems.add(id + ": example source " + source + " is no Java, Python or workbench test");
                }
                if (!exists(root, d.path("example").path("chart").asText())) {
                    problems.add(id + ": chart " + d.path("example").path("chart").asText() + " does not exist");
                }
            }
        }
        return problems;
    }

    private static boolean exists(Path root, String path) {
        return !path.isBlank() && !path.contains("..") && Files.exists(root.resolve(path));
    }

    /** A Java class {@code a.b.C} or {@code a.b.C#member}: its source in a module of the reactor, and the member in it. */
    private static String checkJava(Path root, String ref) {
        if (ref.isBlank()) {
            return "class is missing";
        }
        Path source = javaSource(root, ref);
        if (source == null) {
            return "no module has the source of " + ref;
        }
        String member = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : null;
        return member == null || read(source).contains(member) ? null : source + " has no " + member;
    }

    static Path javaSource(Path root, String ref) {
        String cls = ref.contains("#") ? ref.substring(0, ref.indexOf('#')) : ref;
        String file = "src/main/java/" + cls.replace('.', '/') + ".java";
        try (Stream<Path> modules = Files.list(root)) {
            return modules.map(m -> m.resolve(file)).filter(Files::exists).findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A Python name {@code opentsx.pkg.module.Name} or {@code ...Name#member}. */
    private static String checkPython(Path root, String ref) {
        if (ref.isBlank()) {
            return "class is missing";
        }
        String name = ref.contains("#") ? ref.substring(0, ref.indexOf('#')) : ref;
        String member = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : null;
        String[] parts = name.split("\\.");
        for (int i = parts.length - 1; i > 0; i--) {
            Path module = root.resolve("python-package/" + String.join("/", List.of(parts).subList(0, i)) + ".py");
            if (Files.exists(module)) {
                String text = read(module);
                String symbol = parts[i];
                if (!Pattern.compile("(?m)^(class|def) " + Pattern.quote(symbol) + "\\b").matcher(text).find()) {
                    return module + " defines no " + symbol;
                }
                return member == null || Pattern.compile("(?m)^\\s+def " + Pattern.quote(member) + "\\b").matcher(text).find()
                        ? null : module + " has no method " + member;
            }
        }
        return "no module of python-package defines " + name;
    }

    private static String checkWorkbench(Path root, String opId) {
        try (Stream<Path> files = Files.list(root.resolve("opentsx-saas-frontend/src/workbench"))) {
            boolean found = files.filter(f -> f.toString().endsWith(".ts") && !f.toString().endsWith(".test.ts"))
                    .anyMatch(f -> read(f).contains("'" + opId + "'"));
            return found ? null : "the workbench has no operator '" + opId + "'";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean legacy(Path root, String rt, JsonNode r) {
        if (!rt.equals("java")) {
            return false;
        }
        Path source = javaSource(root, r.path("class").asText());
        return source != null && LEGACY_MODULES.contains(root.relativize(source).getName(0).toString());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The lines between the markers usage:begin and usage:end, without their common indentation. */
    static String usage(Path root, String source) {
        List<String> lines = read(root.resolve(source)).lines().toList();
        int begin = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("usage:begin")) {
                if (begin >= 0) {
                    return null;
                }
                begin = i;
            } else if (lines.get(i).contains("usage:end")) {
                if (end >= 0) {
                    return null;
                }
                end = i;
            }
        }
        if (begin < 0 || end <= begin + 1) {
            return null;
        }
        List<String> block = lines.subList(begin + 1, end);
        int indent = block.stream().filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (String l : block) {
            sb.append(l.isBlank() ? "" : l.substring(indent)).append('\n');
        }
        return sb.toString();
    }

    /** The command that runs the example test, from the repository root. */
    static String run(String source) {
        Matcher java = Pattern.compile("^([a-z0-9-]+)/src/test/java/.*/([A-Za-z0-9]+)\\.java$").matcher(source);
        if (java.matches()) {
            return "mvn -B -ntp -pl " + java.group(1) + " test -Dtest=" + java.group(2);
        }
        if (source.startsWith("python-package/tests/") && source.endsWith(".py")) {
            return "(cd python-package && python -m pytest -q " + source.substring("python-package/".length()) + ")";
        }
        if (source.startsWith("opentsx-saas-frontend/src/") && source.endsWith(".test.ts")) {
            return "(cd opentsx-saas-frontend && npx vitest run " + source.substring("opentsx-saas-frontend/".length()) + ")";
        }
        return null;
    }

    private static String fence(String source) {
        if (source.endsWith(".java")) {
            return "java";
        }
        return source.endsWith(".py") ? "python" : "typescript";
    }

    static String version(Path root) {
        Matcher m = Pattern.compile("<artifactId>opentsx</artifactId>\\s*<packaging>pom</packaging>\\s*<version>([^<]+)</version>")
                .matcher(read(root.resolve("pom.xml")));
        if (!m.find()) {
            throw new IllegalStateException("pom.xml: the version of the parent project is not found");
        }
        return m.group(1);
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ").strip();
    }

    private static String status(Path root, String rt, JsonNode r) {
        String s = r.path("status").asText();
        if (s.isEmpty()) {
            return "";
        }
        return s.equals("available") && legacy(root, rt, r) ? "available (2.x)" : s;
    }

    private static boolean current(JsonNode d) {
        for (String rt : RUNTIMES) {
            if (d.path("runtimes").path(rt).path("status").asText().equals("available")) {
                return true;
            }
        }
        return false;
    }

    private static String anchor(JsonNode d) {
        return (d.path("title").asText() + " " + d.path("id").asText()).toLowerCase()
                .replaceAll("[^a-z0-9 -]", "").strip().replace(' ', '-');
    }

    public static String render(Path root, List<JsonNode> ops) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- Generated by org.opentsx.retrieval.system.OperatorCatalog from systems/processors/*.yaml. Do not edit. -->\n");
        sb.append("# 17. Time-series operators\n\n");
        sb.append("This chapter lists every time-series operator of OpenTSx ").append(version(root))
                .append(": what it computes, where it runs, and an example whose chart shows the result.\n\n");
        sb.append("Each operator is described once, in `systems/processors/<id>.yaml` (schema `opentsx.processor.v1`, ")
                .append("[`opentsx.processor.v1.schema.json`](../datamodel-2026/schemas/opentsx.processor.v1.schema.json)). ")
                .append("This page is generated from those files. A test fails when a runtime marked `available` names code ")
                .append("that does not exist, or when this page is out of date.\n\n");
        sb.append("## Read the status\n\n");
        sb.append("| Runtime | Where the operator runs |\n|---|---|\n");
        sb.append("| workbench | the browser workbench (`opentsx-saas-frontend/src/workbench`) |\n");
        sb.append("| java | the Java API of a module; `available (2.x)` means `opentsx-core` on the 2.x `TimeSeriesObject` model ([chapter 11](11-classic-analytics.md)) |\n");
        sb.append("| python | the `opentsx` Python package ([chapter 10](10-python.md)) |\n");
        sb.append("| processor | continuously on a topic: a node of an `opentsx.dag.v1` DAG (`bin/opentsx vectors`), or its own processor such as `bin/opentsx stats` |\n");
        sb.append("| go | the Go Smart Topic processor. It is not built. |\n\n");
        sb.append("`available` means the code exists in that runtime. Every operator with a runtime other than 2.x Java has an ")
                .append("example test that runs in every build. 2.x code often has no test; its note says so. `planned` means a plan ")
                .append("file names it. `none` means it does not exist and is not planned. An empty cell means the runtime is not ")
                .append("described.\n\n");
        sb.append("## Find an operator\n\n");
        sb.append("| Operator | Id | Section | workbench | java | python | processor | go |\n|---|---|---|---|---|---|---|---|\n");
        for (JsonNode d : ops) {
            sb.append("| ").append(current(d) ? "[" + cell(d.path("title").asText()) + "](#" + anchor(d) + ")" : cell(d.path("title").asText()))
                    .append(" | `").append(d.path("id").asText()).append("` | ")
                    .append(CATEGORIES.getOrDefault(d.path("category").asText(), ""));
            for (String rt : RUNTIMES) {
                sb.append(" | ").append(status(root, rt, d.path("runtimes").path(rt)));
            }
            sb.append(" |\n");
        }
        sb.append('\n');
        sb.append("## Run the examples and draw the charts\n\n");
        sb.append("You need the build from [chapter 2](02-getting-started.md), Node.js for the workbench examples, and ")
                .append("matplotlib for the charts (`python -m pip install -e \"python-package[viz]\"`). The order matters.\n\n");
        sb.append("```bash\n")
                .append("export OPENTSX_EXAMPLES_DIR=/tmp/opentsx-examples\n")
                .append("mkdir -p \"$OPENTSX_EXAMPLES_DIR\"\n")
                .append("mvn -B -ntp test -Dtest='*ExampleTest' -Dsurefire.failIfNoSpecifiedTests=false\n")
                .append("(cd python-package && python -m pytest -q tests/examples)\n")
                .append("(cd opentsx-saas-frontend && npx vitest run src/workbench/examples)\n")
                .append("python3 tools/operator_charts.py \"$OPENTSX_EXAMPLES_DIR\"\n")
                .append("java -cp \"$(bin/opentsx classpath opentsx-retrieval)\" org.opentsx.retrieval.system.OperatorCatalog\n")
                .append("```\n\n");
        sb.append("Each example test checks its result in every build. With `OPENTSX_EXAMPLES_DIR` set it also writes the chart ")
                .append("data as `<id>.csv`. `tools/operator_charts.py` draws `docs/user-manual/img/operators/<id>.png` from it, and ")
                .append("`OperatorCatalog` writes this page.\n\n");
        String section = null;
        for (JsonNode d : ops) {
            if (!current(d)) {
                continue;
            }
            String cat = d.path("category").asText();
            if (!cat.equals(section)) {
                section = cat;
                sb.append("## ").append(CATEGORIES.getOrDefault(cat, cat)).append("\n\n");
            }
            renderOperator(root, d, sb);
        }
        List<JsonNode> later = ops.stream().filter(d -> !current(d)).toList();
        if (!later.isEmpty()) {
            sb.append("## Find planned and missing operators\n\n");
            sb.append("These operators have no runtime today.\n\n");
            sb.append("| Operator | Id | Section | What it computes | Status | Plan |\n|---|---|---|---|---|---|\n");
            for (JsonNode d : later) {
                boolean planned = false;
                for (String rt : RUNTIMES) {
                    planned |= d.path("runtimes").path(rt).path("status").asText().equals("planned");
                }
                JsonNode plan = d.path("plan");
                sb.append("| ").append(cell(d.path("title").asText())).append(" | `").append(d.path("id").asText()).append("` | ")
                        .append(CATEGORIES.getOrDefault(d.path("category").asText(), "")).append(" | ")
                        .append(cell(d.path("description").asText())).append(" | ").append(planned ? "planned" : "none").append(" | ")
                        .append(plan.isMissingNode() ? "" : "[" + plan.path("item").asText() + "](../../" + plan.path("file").asText() + ")")
                        .append(" |\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void renderOperator(Path root, JsonNode d, StringBuilder sb) {
        String id = d.path("id").asText();
        sb.append("### ").append(d.path("title").asText()).append(" (`").append(id).append("`)\n\n");
        sb.append(d.path("description").asText().strip()).append("\n\n");
        sb.append("| Runtime | Status | Entry point | Note |\n|---|---|---|---|\n");
        for (String rt : RUNTIMES) {
            JsonNode r = d.path("runtimes").path(rt);
            if (r.isMissingNode()) {
                continue;
            }
            String entry = r.path("class").asText();
            if (rt.equals("workbench") && r.path("status").asText().equals("available")) {
                entry = "operator `" + r.path("class").asText(id) + "`";
            } else if (!entry.isEmpty()) {
                entry = "`" + entry + "`";
            }
            sb.append("| ").append(rt).append(" | ").append(status(root, rt, r)).append(" | ").append(entry)
                    .append(" | ").append(cell(r.path("note").asText())).append(" |\n");
        }
        sb.append('\n');
        sb.append("| Port | Direction | Type | Description |\n|---|---|---|---|\n");
        for (String dir : List.of("inputs", "outputs")) {
            for (JsonNode p : d.path(dir)) {
                String card = p.path("cardinality").asText();
                sb.append("| `").append(p.path("name").asText()).append("` | ").append(dir.equals("inputs") ? "in" : "out").append(" | ")
                        .append(p.path("type").asText()).append(card.isEmpty() ? "" : " (" + card + ")").append(" | ")
                        .append(cell(p.path("description").asText())).append(" |\n");
            }
        }
        sb.append('\n');
        if (d.path("params").size() > 0) {
            sb.append("| Parameter | Type | Default | Range | Description |\n|---|---|---|---|---|\n");
            for (JsonNode p : d.path("params")) {
                String type = p.path("type").asText();
                if (p.has("values")) {
                    List<String> v = new ArrayList<>();
                    p.path("values").forEach(x -> v.add(x.asText()));
                    type += ": " + String.join(", ", v);
                }
                String range = "";
                if (p.has("min") || p.has("max")) {
                    range = (p.has("min") ? p.path("min").asText() : "") + " .. " + (p.has("max") ? p.path("max").asText() : "");
                }
                String def = p.has("default") ? "`" + (p.path("default").isContainerNode() ? p.path("default").toString() : p.path("default").asText()) + "`"
                        : p.path("required").asBoolean() ? "required" : "";
                String desc = p.path("description").asText();
                if (p.has("unit")) {
                    desc = (desc + " Unit: " + p.path("unit").asText() + ".").strip();
                }
                if (p.has("from")) {
                    desc = (desc + " Preset from `" + p.path("from").asText() + "`.").strip();
                }
                sb.append("| `").append(p.path("name").asText()).append("` | ").append(cell(type)).append(" | ").append(def)
                        .append(" | ").append(range).append(" | ").append(cell(desc)).append(" |\n");
            }
            sb.append('\n');
        }
        JsonNode sem = d.path("semantics");
        if (!sem.isMissingNode()) {
            List<String> parts = new ArrayList<>();
            if (sem.has("needs")) {
                parts.add("needs one " + sem.path("needs").asText() + " of input per output value");
            }
            if (sem.has("deterministic")) {
                parts.add(sem.path("deterministic").asBoolean() ? "deterministic" : "not deterministic");
            }
            if (sem.has("keeps")) {
                List<String> k = new ArrayList<>();
                sem.path("keeps").forEach(x -> k.add(x.asText()));
                parts.add("keeps " + String.join(", ", k));
            }
            if (sem.has("origin")) {
                parts.add("origin " + sem.path("origin").asText());
            }
            sb.append("Semantics: ").append(String.join("; ", parts)).append(".\n\n");
        }
        List<String> refs = new ArrayList<>();
        if (d.has("docs")) {
            refs.add("[more](../../" + d.path("docs").asText() + ")");
        }
        if (d.has("testvectors")) {
            refs.add("[test vectors](../../" + d.path("testvectors").asText() + ")");
        }
        refs.add("[description](../../systems/processors/" + id + ".yaml)");
        if (d.has("plan")) {
            refs.add("plan: [" + d.path("plan").path("item").asText() + "](../../" + d.path("plan").path("file").asText() + ")");
        }
        sb.append("See: ").append(String.join(", ", refs)).append(".\n\n");
        if (d.path("literature").size() > 0) {
            List<String> lit = new ArrayList<>();
            d.path("literature").forEach(x -> lit.add(x.asText()));
            sb.append("Literature: ").append(String.join("; ", lit)).append(".\n\n");
        }
        JsonNode ex = d.path("example");
        if (!ex.isMissingNode()) {
            String source = ex.path("source").asText();
            sb.append("Example, from [`").append(source).append("`](../../").append(source).append("):\n\n");
            sb.append("```").append(fence(source)).append('\n').append(usage(root, source)).append("```\n\n");
            sb.append("Run it:\n\n```bash\n").append(run(source)).append("\n```\n\n");
            String chart = ex.path("chart").asText();
            sb.append("![").append(cell(ex.path("shows").asText())).append("](")
                    .append(chart.substring("docs/user-manual/".length())).append(")\n\n");
            sb.append(ex.path("shows").asText().strip()).append("\n\n");
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<JsonNode> ops = load(root);
        List<String> problems = problems(root, ops);
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.err.println("problem: " + p));
            System.exit(1);
        }
        Files.writeString(root.resolve(PAGE), render(root, ops), StandardCharsets.UTF_8);
        System.out.println("wrote " + PAGE + " (" + ops.size() + " operators)");
    }
}
