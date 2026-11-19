package org.opentsx.conf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key registry {@code opentsx.config.v1}: every key with its type, default, aliases and the commands that read
 * it, and every command. The constructor checks the registry rules of TASK-020 and refuses a registry that breaks one.
 */
public final class Registry {

    static final String RESOURCE = "/org/opentsx/conf/opentsx.config.v1.json";
    static final String MASK = "******";
    static final Pattern KEY = Pattern.compile("^opentsx(\\.[a-z][a-z0-9]*([A-Z][a-z0-9]+)*)+$");
    static final Pattern FLAG = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    static final Pattern WORD = Pattern.compile("[a-z0-9]+|[A-Z][a-z0-9]+");
    static final Pattern DOC = Pattern.compile("^[A-Z].*\\.$");
    static final Set<String> TYPES = Set.of("string", "int", "long", "double", "boolean", "duration", "list", "enum", "path");
    static final Set<String> ITEM_TYPES = Set.of("string", "int", "long", "double", "duration", "enum", "path");
    static final Set<String> COMMON_OPTIONS = Set.of("conf", "c", "properties-file", "help", "h", "version", "output");
    static final Set<String> LAUNCHER_VARIABLES = Set.of("OPENTSX_HOME", "OPENTSX_CONF_DIR", "OPENTSX_EXTRA_ARGS");
    static final List<String> READ_EVERY_KEY = List.of("config get", "config show");
    static final List<String> WITH_OUTPUT = List.of("config list", "config show");
    static final List<String> LOADS_CONF = List.of("boat", "config get", "config list", "config show", "dwd house",
            "dwd mosmix", "dwd obs", "dwd stations", "history-twin boat", "history-twin house", "history-twin twin",
            "house", "market backfill", "market catalog", "market forward", "market import", "market load",
            "market marktwert", "market mosmix-archive", "market prices", "market publish", "market rebap",
            "market report", "market series", "market weather-backfill", "market weather-forward", "metadata",
            "modbus check", "modbus poll", "modbus read", "modbus simulate", "retrieval-server", "stats", "stats-flink",
            "studio-export", "twin", "twin-coupling", "twin-live bridge", "twin-live demo", "twin-live publish",
            "twin-schemas", "vectors", "vectors deploy", "vectors run", "vectors stop", "version", "windpark-sim");

    public record Key(String key, String type, String items, List<String> values, String unit, String defaultValue,
                      boolean secret, String flag, List<String> legacyFlags, List<String> env, List<String> commands,
                      String since, String doc) {

        public List<String> flags() {
            List<String> out = new ArrayList<>();
            if (flag != null) {
                out.add(flag);
            }
            out.addAll(legacyFlags);
            return out;
        }

        public String typeName() {
            return items == null ? type : type + " of " + items;
        }

        public List<String> envNames() {
            List<String> out = new ArrayList<>();
            out.add(envName(key));
            out.addAll(env);
            return out;
        }

        String placeholder() {
            return "enum".equals(type) ? "<" + String.join("|", values) + ">" : "<" + type + ">";
        }
    }

    public record Command(String name, List<String> args, String since, String doc) {

        String usage() {
            StringBuilder sb = new StringBuilder("opentsx ").append(name);
            for (String a : args) {
                sb.append(" <").append(a).append('>');
            }
            return sb.toString();
        }
    }

    private final byte[] json;
    private final Map<String, Key> keys = new TreeMap<>();
    private final Map<String, Command> commands = new TreeMap<>();

    private Registry(byte[] json, List<Key> keyList, List<Command> commandList) {
        this.json = json.clone();
        for (Key k : keyList) {
            keys.put(k.key(), k);
        }
        for (Command c : commandList) {
            commands.put(c.name(), c);
        }
        List<String> problems = problems(keyList, commandList);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("registry: " + String.join("; ", problems));
        }
    }

    public static Registry shipped() {
        try (InputStream in = Registry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + ": missing from the class path");
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + ": cannot read the registry", e);
        }
    }

    public static Registry parse(byte[] json) {
        JsonNode doc;
        try {
            doc = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("registry: not valid JSON; " + e.getMessage(), e);
        }
        if (doc == null || !"opentsx.config.v1".equals(doc.path("schema").asText())) {
            throw new IllegalStateException("registry: schema is not opentsx.config.v1");
        }
        List<Key> keyList = new ArrayList<>();
        for (JsonNode e : doc.path("keys")) {
            keyList.add(new Key(required(e, "key"), required(e, "type"), optional(e, "items"), strings(e, "values"),
                    optional(e, "unit"), optional(e, "default"), e.path("secret").asBoolean(false), optional(e, "flag"),
                    strings(e, "legacyFlags"), strings(e, "env"), strings(e, "commands"), required(e, "since"),
                    required(e, "doc")));
        }
        List<Command> commandList = new ArrayList<>();
        for (JsonNode c : doc.path("commands")) {
            commandList.add(new Command(required(c, "name"), strings(c, "args"), required(c, "since"), required(c, "doc")));
        }
        return new Registry(json, keyList, commandList);
    }

    private static String required(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            throw new IllegalStateException("registry: " + field + " missing in " + node);
        }
        return v.asText();
    }

    private static String optional(JsonNode node, String field) {
        return node.has(field) ? required(node, field) : null;
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : node.path(field)) {
            out.add(v.asText());
        }
        return List.copyOf(out);
    }

    public byte[] json() {
        return json.clone();
    }

    public Collection<Key> keys() {
        return Collections.unmodifiableCollection(keys.values());
    }

    public Map<String, Command> commands() {
        return Collections.unmodifiableMap(commands);
    }

    boolean known(String key) {
        return keys.containsKey(key);
    }

    public Key entry(String key, String subject) {
        Key e = keys.get(key);
        if (e != null) {
            return e;
        }
        String n = near(key, keys.keySet());
        if (key.startsWith("opentsx.")) {
            throw new OpenTsxConfException(subject, "unknown key",
                    n != null ? "did you mean " + n + "?" : "run opentsx config list for the known keys");
        }
        throw new OpenTsxConfException(subject, "not an OpenTSx key",
                n != null ? "did you mean " + n + "?" : "expected a key that starts with opentsx.");
    }

    Map<String, Key> flags(String command) {
        Map<String, Key> out = new HashMap<>();
        for (Key e : keys.values()) {
            if (e.commands().contains(command)) {
                for (String f : e.flags()) {
                    out.put(f, e);
                }
            }
        }
        return out;
    }

    boolean reads(String command, Key e) {
        return e.commands().contains(command) || READ_EVERY_KEY.contains(command);
    }

    public static boolean validKey(String key) {
        return KEY.matcher(key).matches();
    }

    public static String envName(String key) {
        StringBuilder sb = new StringBuilder("OPENTSX");
        String[] segments = key.split("\\.");
        for (int i = 1; i < segments.length; i++) {
            Matcher m = WORD.matcher(segments[i]);
            while (m.find()) {
                sb.append('_').append(ascii(m.group(), false));
            }
        }
        return sb.toString();
    }

    static String ascii(String s, boolean lower) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (lower && c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            } else if (!lower && c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** On a tie the candidate that sorts first. */
    static String near(String word, Collection<String> candidates) {
        String best = null;
        int bestDistance = 3;
        for (String c : new TreeSet<>(candidates)) {
            int d = distance(word, c);
            if (d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        return best;
    }

    static int distance(String a, String b) {
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int[] prev = new int[y.length + 1];
        int[] cur = new int[y.length + 1];
        for (int j = 0; j <= y.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= x.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length; j++) {
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[y.length];
    }

    /** Rules 1 to 8 of the registry section of TASK-020, plus the field rules of the schema the loader relies on. */
    private static List<String> problems(List<Key> keyList, List<Command> commandList) {
        List<String> problems = new ArrayList<>();
        for (int i = 1; i < commandList.size(); i++) {
            if (commandList.get(i - 1).name().compareTo(commandList.get(i).name()) >= 0) {
                problems.add("commands not sorted or not unique at " + commandList.get(i).name());
            }
        }
        for (int i = 1; i < keyList.size(); i++) {
            if (keyList.get(i - 1).key().compareTo(keyList.get(i).key()) >= 0) {
                problems.add("keys not sorted or not unique at " + keyList.get(i).key());
            }
        }
        Set<String> commandNames = new LinkedHashSet<>();
        for (Command c : commandList) {
            commandNames.add(c.name());
            if (!DOC.matcher(c.doc()).matches()) {
                problems.add(c.name() + ": doc is not one sentence");
            }
        }
        Map<String, String> envOwner = new HashMap<>();
        for (Key e : keyList) {
            String k = e.key();
            if (!validKey(k)) {
                problems.add(k + ": breaks the key grammar");
            }
            for (Key other : keyList) {
                if (other.key().startsWith(k + ".")) {
                    problems.add(k + ": prefix of " + other.key());
                }
            }
            for (String n : e.envNames()) {
                String owner = envOwner.putIfAbsent(n, k);
                if (owner != null) {
                    problems.add("environment variable " + n + ": used by " + owner + " and " + k);
                }
                if (LAUNCHER_VARIABLES.contains(n)) {
                    problems.add("environment variable " + n + ": launcher variable");
                }
            }
            for (String f : e.flags()) {
                if (!FLAG.matcher(f).matches() || COMMON_OPTIONS.contains(f)) {
                    problems.add(k + ": flag " + f + " is not allowed");
                }
            }
            if (e.commands().isEmpty()) {
                problems.add(k + ": no command");
            }
            for (String c : e.commands()) {
                if (!commandNames.contains(c)) {
                    problems.add(k + ": command " + c + " is not in commands");
                }
            }
            if (!TYPES.contains(e.type())) {
                problems.add(k + ": type " + e.type());
            }
            if (e.items() != null && (!"list".equals(e.type()) || !ITEM_TYPES.contains(e.items()))) {
                problems.add(k + ": items " + e.items());
            }
            if (("enum".equals(e.type()) || "enum".equals(e.items())) == e.values().isEmpty()) {
                problems.add(k + ": values belong to enum keys and lists of enum");
            }
            if (e.unit() != null && !List.of("int", "long", "double").contains(e.type())) {
                problems.add(k + ": unit on a key of type " + e.type());
            }
            if (e.secret() && e.defaultValue() != null) {
                problems.add(k + ": secret with a default");
            }
            if (e.defaultValue() != null && TYPES.contains(e.type())) {
                try {
                    ConfParser.parse(e, e.defaultValue(), k);
                } catch (OpenTsxConfException x) {
                    problems.add("default of " + x.getMessage());
                }
            }
            if (!DOC.matcher(e.doc()).matches()) {
                problems.add(k + ": doc is not one sentence");
            }
        }
        for (String c : commandNames) {
            Map<String, String> owner = new HashMap<>();
            for (Key e : keyList) {
                if (e.commands().contains(c)) {
                    for (String f : e.flags()) {
                        String first = owner.putIfAbsent(f, e.key());
                        if (first != null) {
                            problems.add(c + ": --" + f + " belongs to " + first + " and " + e.key());
                        }
                    }
                }
            }
        }
        return problems;
    }

    public String help(String command) {
        Command c = commands.get(command);
        StringBuilder sb = new StringBuilder();
        sb.append("usage: ").append(c.usage()).append(" [options]\n").append(c.doc()).append('\n');
        if (!LOADS_CONF.contains(command)) {
            sb.append("In the Java runtime, ").append(command).append(" parses its own flags and does not read --conf, ")
                    .append("--properties-file, OPENTSX_CONF_DIR or the canonical OPENTSX_ variables.\n");
        }
        List<Key> read = keys.values().stream().filter(e -> e.commands().contains(command)).toList();
        if (!read.isEmpty()) {
            sb.append("\nkeys:\n");
        }
        for (Key e : read) {
            List<String> parts = new ArrayList<>();
            parts.add(e.key());
            parts.add(e.typeName() + (e.defaultValue() != null ? " = " + e.defaultValue() : ""));
            if (!e.values().isEmpty()) {
                parts.add("one of " + String.join("|", e.values()));
            }
            if (e.unit() != null) {
                parts.add("unit " + e.unit());
            }
            if (!e.secret() && !e.flags().isEmpty()) {
                parts.add(dashed(e.flags()));
            }
            parts.add(String.join(", ", e.envNames()));
            if (e.secret()) {
                parts.add("secret: environment or file only");
            }
            sb.append("  ").append(String.join("  ", parts)).append('\n');
            sb.append("      ").append(e.doc()).append('\n');
        }
        sb.append('\n').append(commonOptions());
        if (WITH_OUTPUT.contains(command)) {
            sb.append("  --output <text|json>        print a table or one line of JSON\n");
        }
        return sb.toString();
    }

    public String help() {
        int width = commands.keySet().stream().mapToInt(n -> n.codePointCount(0, n.length())).max().orElse(0);
        StringBuilder sb = new StringBuilder("usage: opentsx <command> [options] [args]\n\ncommands:\n");
        for (Command c : commands.values()) {
            sb.append("  ").append(pad(c.name(), width)).append("  ").append(c.doc()).append('\n');
        }
        sb.append('\n').append(commonOptions());
        sb.append("\nrun opentsx <command> --help for the keys of a command\n");
        return sb.toString();
    }

    private static String commonOptions() {
        return "common options:\n"
                + "  -c, --conf <key>=<value>    set a key on the command line; repeatable\n"
                + "  --properties-file <path>    read keys from a properties file or a YAML file (.yaml, .yml)\n"
                + "  -h, --help                  print this help and exit\n"
                + "  --version                   print the version and exit\n";
    }

    static String dashed(List<String> flags) {
        return String.join(", ", flags.stream().map(f -> "--" + f).toList());
    }

    static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.codePointCount(0, s.length())));
    }
}
