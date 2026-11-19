package org.opentsx.conf;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import org.opentsx.conf.Registry.Key;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConfParser {

    static final Pattern INT = Pattern.compile("^(0|-?[1-9][0-9]*)$");
    static final Pattern DOUBLE = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?$");
    static final Pattern ISO = Pattern.compile(
            "^P(?:(0|[1-9][0-9]*)D)?(?:T(?:(0|[1-9][0-9]*)H)?(?:(0|[1-9][0-9]*)M)?(?:(0|[1-9][0-9]*)(?:\\.([0-9]{1,3}))?S)?)?$");
    static final Pattern UNIT = Pattern.compile("^(0|[1-9][0-9]*)(ms|s|min|m|h|d)$");
    static final BigInteger MAX_MILLIS = BigInteger.valueOf(999_999_999L * 86_400_000L);
    static final Map<String, Long> UNIT_MILLIS = Map.of("ms", 1L, "s", 1_000L, "m", 60_000L, "min", 60_000L,
            "h", 3_600_000L, "d", 86_400_000L);
    static final Pattern KEY_PART = Pattern.compile("^[^ \t=]*");
    static final Set<String> YAML_NULLS = Set.of("", "~", "null", "Null", "NULL");

    static final String INT_ALLOWED = "expected a whole number from -2147483648 to 2147483647";
    static final String LONG_ALLOWED = "expected a whole number from -9223372036854775808 to 9223372036854775807";
    static final String DOUBLE_ALLOWED = "expected a finite decimal number such as 0.01 or 1e-3";
    static final String DURATION_ALLOWED = "expected ISO-8601 such as PT10M or a whole number with the unit ms, s, m, min, "
            + "h or d such as 10m, at most 999999999 days";
    static final String UTF8_ALLOWED = "pass UTF-8 text in a UTF-8 locale such as LANG=C.UTF-8";
    static final String LINE_ALLOWED = "write key value or key = value";
    static final int MAX_YAML_DEPTH = 100;

    record Args(String command, boolean help, boolean version, Map<String, String> pairs, String propertiesFile,
                String output, List<String> positional) {
    }

    private ConfParser() {
    }

    static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) {
            b--;
        }
        return s.substring(a, b);
    }

    static String parse(Key e, String raw, String subject) {
        String v = trim(raw);
        if (v.isEmpty()) {
            throw new OpenTsxConfException(subject, "empty value", "set a value or leave the key unset");
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new OpenTsxConfException(subject, "value contains a control character", "remove it");
            }
        }
        if (!utf8(v)) {
            throw new OpenTsxConfException(subject, "value is not valid UTF-8", UTF8_ALLOWED);
        }
        if (!"list".equals(e.type())) {
            scalar(e, e.type(), v, subject, false);
            return v;
        }
        List<String> items = new ArrayList<>();
        for (String part : v.split(",", -1)) {
            String item = trim(part);
            if (item.isEmpty()) {
                throw new OpenTsxConfException(subject, "\"" + shown(e, v) + "\" has an empty item",
                        "expected comma-separated items such as a,b");
            }
            items.add(item);
        }
        String itemType = e.items() == null ? "string" : e.items();
        for (String item : items) {
            scalar(e, itemType, item, subject, true);
        }
        return String.join(",", items);
    }

    /**
     * False for U+FFFD and lone surrogates: the JVM decodes argv and the environment with the locale and puts U+FFFD for
     * bytes it cannot map, Python keeps an invalid byte as a lone surrogate.
     */
    static boolean utf8(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp == 0xFFFD || (cp >= 0xD800 && cp <= 0xDFFF)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static String shown(Key e, String v) {
        return e.secret() ? Registry.MASK : v;
    }

    private static void scalar(Key e, String type, String v, String subject, boolean item) {
        String q = (item ? "item " : "") + "\"" + shown(e, v) + "\"";
        switch (type) {
            case "string", "path" -> {
            }
            case "int" -> {
                if (!INT.matcher(v).matches() || !fits(v, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                    throw new OpenTsxConfException(subject, q + " is not an int", INT_ALLOWED);
                }
            }
            case "long" -> {
                if (!INT.matcher(v).matches() || !fits(v, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    throw new OpenTsxConfException(subject, q + " is not a long", LONG_ALLOWED);
                }
            }
            case "double" -> {
                if (!DOUBLE.matcher(v).matches() || !Double.isFinite(Double.parseDouble(v))) {
                    throw new OpenTsxConfException(subject, q + " is not a double", DOUBLE_ALLOWED);
                }
            }
            case "boolean" -> {
                String low = Registry.ascii(v, true);
                if (!low.equals("true") && !low.equals("false")) {
                    throw new OpenTsxConfException(subject, q + " is not a boolean", "expected true or false");
                }
            }
            case "duration" -> {
                if (millis(v) < 0) {
                    throw new OpenTsxConfException(subject, q + " is not a duration", DURATION_ALLOWED);
                }
            }
            case "enum" -> {
                if (!e.values().contains(v)) {
                    throw new OpenTsxConfException(subject, q + " is not allowed", "expected one of " + String.join(", ", e.values()));
                }
            }
            default -> throw new IllegalStateException(e.key() + ": unknown type " + type);
        }
    }

    private static boolean fits(String digits, long min, long max) {
        BigInteger n = new BigInteger(digits);
        return n.compareTo(BigInteger.valueOf(min)) >= 0 && n.compareTo(BigInteger.valueOf(max)) <= 0;
    }

    /** -1 when the text is not a duration of D3. */
    static long millis(String v) {
        BigInteger ms;
        Matcher m = ISO.matcher(v);
        if (m.matches() && !v.equals("P") && !v.endsWith("T")) {
            ms = big(m.group(1)).multiply(BigInteger.valueOf(86_400_000L))
                    .add(big(m.group(2)).multiply(BigInteger.valueOf(3_600_000L)))
                    .add(big(m.group(3)).multiply(BigInteger.valueOf(60_000L)))
                    .add(big(m.group(4)).multiply(BigInteger.valueOf(1_000L)));
            if (m.group(5) != null) {
                ms = ms.add(new BigInteger((m.group(5) + "00").substring(0, 3)));
            }
        } else {
            m = UNIT.matcher(v);
            if (!m.matches()) {
                return -1;
            }
            ms = new BigInteger(m.group(1)).multiply(BigInteger.valueOf(UNIT_MILLIS.get(m.group(2))));
        }
        return ms.compareTo(MAX_MILLIS) > 0 ? -1 : ms.longValueExact();
    }

    private static BigInteger big(String digits) {
        return digits == null ? BigInteger.ZERO : new BigInteger(digits);
    }

    static Map<String, String> readFile(Registry reg, String path) {
        Path p;
        try {
            p = Path.of(path);
        } catch (InvalidPathException e) {
            throw new OpenTsxConfException(path, "no such file", "give an existing file");
        }
        if (!Files.isRegularFile(p)) {
            throw new OpenTsxConfException(path, "no such file", "give an existing file");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(path + ": cannot read the file (" + e + ")", e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new OpenTsxConfException(path, "not valid UTF-8", "save the file as UTF-8");
        }
        return path.endsWith(".yaml") || path.endsWith(".yml") ? readYaml(reg, path, text) : readProperties(reg, path, text);
    }

    static Map<String, String> readProperties(Registry reg, String path, String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Integer> firstLine = new HashMap<>();
        String[] lines = text.split("\n", -1);
        for (int n = 1; n <= lines.length; n++) {
            String line = lines[n - 1];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String t = trim(line);
            if (t.isEmpty() || t.charAt(0) == '#' || t.charAt(0) == '!') {
                continue;
            }
            String loc = path + ":" + n;
            if (t.indexOf('\\') >= 0) {
                if (t.endsWith("\\")) {
                    throw new OpenTsxConfException(loc, "line continuation is not supported", "write the value on one line");
                }
                throw new OpenTsxConfException(loc, "escape sequences are not supported", "write the character itself");
            }
            Matcher m = KEY_PART.matcher(t);
            String key = m.find() ? m.group() : "";
            String rest = trim(t.substring(key.length()));
            String value = rest.startsWith("=") ? trim(rest.substring(1)) : rest;
            int colon = key.indexOf(':');
            if (key.isEmpty() || colon == 0) {
                throw new OpenTsxConfException(loc, "line has no key", LINE_ALLOWED);
            }
            // The text after a colon or of an unknown key without value can be a secret glued to the key: never echo it.
            if (colon > 0) {
                throw new OpenTsxConfException(loc + ": " + key.substring(0, colon), "\":\" is not a separator", LINE_ALLOWED);
            }
            if (value.isEmpty()) {
                throw new OpenTsxConfException(loc, reg.known(key) ? key + " has no value" : "line has no value", LINE_ALLOWED);
            }
            String subject = loc + ": " + key;
            Key e = reg.entry(key, subject);
            Integer first = firstLine.putIfAbsent(key, n);
            if (first != null) {
                throw new OpenTsxConfException(subject, "set twice in this file (first on line " + first + ")", "set each key once");
            }
            out.put(key, parse(e, value, subject));
        }
        return out;
    }

    private record Token(JsonToken type, String text, boolean alias, boolean tagged) {
    }

    static Map<String, String> readYaml(Registry reg, String path, String text) {
        List<Token> tokens = new ArrayList<>();
        try (YAMLParser p = (YAMLParser) new YAMLFactory().createParser(text)) {
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                tokens.add(new Token(t, p.getText(), p.isCurrentAlias(), p.getTypeId() != null));
            }
        } catch (IOException e) {
            String detail = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            throw new OpenTsxConfException(path + ": not valid YAML; " + detail);
        }
        int documents = 0;
        int depth = 0;
        int maxDepth = 0;
        for (Token t : tokens) {
            if (t.type() == JsonToken.START_OBJECT || t.type() == JsonToken.START_ARRAY) {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else if (t.type() == JsonToken.END_OBJECT || t.type() == JsonToken.END_ARRAY) {
                depth--;
            }
            if (depth == 0 && t.type() != JsonToken.FIELD_NAME) {
                documents++;
            }
        }
        if (documents > 1) {
            throw new OpenTsxConfException(path, "more than one YAML document", "keep one document per file");
        }
        if (maxDepth > MAX_YAML_DEPTH) {
            throw new OpenTsxConfException(path, "not valid YAML", "nesting deeper than " + MAX_YAML_DEPTH + " levels");
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            return out;
        }
        Yaml y = new Yaml(reg, path, tokens, out);
        Token first = y.next();
        if (first.type() == JsonToken.VALUE_NULL) {
            return out;
        }
        if (first.type() != JsonToken.START_OBJECT) {
            throw new OpenTsxConfException(path, "the top level must be a mapping of keys to values", "write key: value lines");
        }
        y.mapping("");
        return out;
    }

    private static final class Yaml {
        private final Registry reg;
        private final String path;
        private final List<Token> tokens;
        private final Map<String, String> out;
        private int pos;

        Yaml(Registry reg, String path, List<Token> tokens, Map<String, String> out) {
            this.reg = reg;
            this.path = path;
            this.tokens = tokens;
            this.out = out;
        }

        Token next() {
            Token t = tokens.get(pos++);
            if (t.alias()) {
                throw new OpenTsxConfException(path, "aliases (*name) are not supported", "write the value");
            }
            if (t.tagged()) {
                throw new OpenTsxConfException(path, "tags (!!type) are not supported", "write the value as plain text");
            }
            return t;
        }

        void mapping(String prefix) {
            while (true) {
                Token k = next();
                if (k.type() == JsonToken.END_OBJECT) {
                    return;
                }
                String key = prefix.isEmpty() ? k.text() : prefix + "." + k.text();
                String subject = path + ": " + key;
                Token v = next();
                if (v.type() == JsonToken.START_OBJECT) {
                    mapping(key);
                } else if (v.type() == JsonToken.START_ARRAY) {
                    Key e = reg.entry(key, subject);
                    List<String> items = new ArrayList<>();
                    for (Token it = next(); it.type() != JsonToken.END_ARRAY; it = next()) {
                        if (!it.type().isScalarValue() || it.type() == JsonToken.VALUE_NULL) {
                            throw new OpenTsxConfException(subject, "list items must be plain values", "write one value per item");
                        }
                        if (it.text().contains(",")) {
                            throw new OpenTsxConfException(subject, "item \"" + shown(e, it.text()) + "\" contains a comma",
                                    "write one value per item");
                        }
                        items.add(it.text());
                    }
                    if (!"list".equals(e.type())) {
                        throw new OpenTsxConfException(subject, "a YAML list is only allowed for keys of type list", "write one value");
                    }
                    leaf(key, subject, String.join(",", items));
                } else if (v.type() == JsonToken.VALUE_NULL) {
                    reg.entry(key, subject);
                    throw new OpenTsxConfException(subject, "empty value", "set a value or leave the key unset");
                } else {
                    leaf(key, subject, v.text());
                }
            }
        }

        private void leaf(String key, String subject, String raw) {
            Key e = reg.entry(key, subject);
            if (out.containsKey(key)) {
                throw new OpenTsxConfException(subject, "set twice in this file", "set each key once");
            }
            out.put(key, parse(e, raw, subject));
        }
    }

    static Map<String, String> readEnv(Registry reg, Map<String, String> env) {
        Map<String, String> out = new HashMap<>();
        for (Key e : reg.keys()) {
            for (String name : e.envNames()) {
                String raw = env.get(name);
                if (raw != null && !trim(raw).isEmpty()) {
                    out.putIfAbsent(e.key(), parse(e, raw, name));
                }
            }
        }
        return out;
    }

    static Args parseArgs(Registry reg, List<String> argv) {
        int dashes = argv.indexOf("--");
        List<String> beforeDashes = dashes < 0 ? argv : argv.subList(0, dashes);
        boolean help = beforeDashes.contains("--help") || beforeDashes.contains("-h");
        if (help || beforeDashes.contains("--version")) {
            boolean version = !help || argv.get(0).equals("--version");
            return new Args(longestCommand(reg, words(argv)), !version, version, Map.of(), null, null, List.of());
        }
        int[] used = new int[1];
        String command = resolveCommand(reg, argv, used);
        List<String> rest = argv.subList(used[0], argv.size());
        Map<String, Key> flags = reg.flags(command);
        Set<String> common = new HashSet<>(List.of("conf", "properties-file", "help", "version"));
        if (Registry.WITH_OUTPUT.contains(command)) {
            common.add("output");
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        Map<String, String> givenBy = new HashMap<>();
        List<String> positional = new ArrayList<>();
        String props = null;
        String output = null;
        int i = 0;
        while (i < rest.size()) {
            String t = rest.get(i);
            if (t.equals("--")) {
                positional.addAll(rest.subList(i + 1, rest.size()));
                break;
            }
            if (!t.startsWith("-")) {
                positional.add(t);
                i++;
                continue;
            }
            boolean hasValue = i + 1 < rest.size() && !rest.get(i + 1).startsWith("--");
            if (t.equals("--conf") || t.equals("-c")) {
                if (!hasValue) {
                    throw new OpenTsxConfException(t, "missing value", "expected " + t + " key=value");
                }
                String kv = rest.get(i + 1);
                int eq = kv.indexOf('=');
                if (eq < 0) {
                    throw new OpenTsxConfException(t, "\"" + kv + "\" has no \"=\"", "expected " + t + " key=value");
                }
                String key = kv.substring(0, eq);
                Key e = reg.entry(key, key);
                if (e.secret()) {
                    throw secretOnCommandLine(key, e);
                }
                if (!reg.reads(command, e)) {
                    throw new OpenTsxConfException(key, "not read by " + command, "run opentsx " + command + " --help for its keys");
                }
                set(pairs, givenBy, e, kv.substring(eq + 1), key);
                i += 2;
                continue;
            }
            if (t.equals("--properties-file") || (t.equals("--output") && common.contains("output"))) {
                boolean file = t.equals("--properties-file");
                if (!hasValue) {
                    throw new OpenTsxConfException(t, "missing value", "expected " + t + (file ? " <path>" : " <text|json>"));
                }
                if ((file ? props : output) != null) {
                    throw new OpenTsxConfException(t, "given twice", "give it once");
                }
                String value = rest.get(i + 1);
                if (file) {
                    if (!utf8(value)) {
                        throw new OpenTsxConfException(t, "path is not valid UTF-8", UTF8_ALLOWED);
                    }
                    props = value;
                } else {
                    if (!value.equals("text") && !value.equals("json")) {
                        throw new OpenTsxConfException(t, "\"" + value + "\" is not allowed", "expected one of text, json");
                    }
                    output = value;
                }
                i += 2;
                continue;
            }
            String name = t.startsWith("--") ? t.substring(2) : null;
            Key e = name == null ? null : flags.get(name);
            if (e == null) {
                Set<String> candidates = new TreeSet<>(flags.keySet());
                candidates.addAll(common);
                String n = Registry.near(name != null ? name : t.replaceFirst("^-+", ""), candidates);
                throw new OpenTsxConfException(t, "unknown option of " + command,
                        n != null ? "did you mean --" + n + "?" : "run opentsx " + command + " --help for the options");
            }
            if (e.secret()) {
                throw secretOnCommandLine(t, e);
            }
            String raw;
            int step;
            if ("boolean".equals(e.type())) {
                boolean next = i + 1 < rest.size() && isAsciiBoolean(rest.get(i + 1));
                raw = next ? rest.get(i + 1) : "true";
                step = next ? 2 : 1;
            } else {
                if (!hasValue) {
                    throw new OpenTsxConfException(t, "missing value", "expected " + t + " " + e.placeholder());
                }
                raw = rest.get(i + 1);
                step = 2;
            }
            set(pairs, givenBy, e, raw, t);
            i += step;
        }
        List<String> args = reg.commands().get(command).args();
        String usage = reg.commands().get(command).usage();
        if (positional.size() > args.size()) {
            throw new OpenTsxConfException(positional.get(args.size()), "unexpected argument", "expected " + usage);
        }
        if (positional.size() < args.size()) {
            throw new OpenTsxConfException(command, "missing argument <" + args.get(positional.size()) + ">", "expected " + usage);
        }
        return new Args(command, false, false, pairs, props, output, List.copyOf(positional));
    }

    private static void set(Map<String, String> pairs, Map<String, String> givenBy, Key e, String raw, String subject) {
        String first = givenBy.get(e.key());
        if (first != null) {
            throw new OpenTsxConfException(subject, "set twice on the command line (also by " + first + ")", "set each key once");
        }
        pairs.put(e.key(), parse(e, raw, subject));
        givenBy.put(e.key(), subject);
    }

    private static boolean isAsciiBoolean(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        String low = Registry.ascii(s, true);
        return low.equals("true") || low.equals("false");
    }

    static OpenTsxConfException secretOnCommandLine(String subject, Key e) {
        return new OpenTsxConfException(subject, "secret keys are not accepted on the command line",
                "set " + String.join(" or ", e.envNames()) + " or put " + e.key() + " in a properties file");
    }

    private static List<String> words(List<String> argv) {
        List<String> words = new ArrayList<>();
        for (String t : argv) {
            if (t.startsWith("-")) {
                break;
            }
            words.add(t);
        }
        return words;
    }

    private static String longestCommand(Registry reg, List<String> words) {
        for (int n = words.size(); n > 0; n--) {
            String name = String.join(" ", words.subList(0, n));
            if (reg.commands().containsKey(name)) {
                return name;
            }
        }
        return null;
    }

    private static String resolveCommand(Registry reg, List<String> argv, int[] used) {
        List<String> words = words(argv);
        String found = longestCommand(reg, words);
        if (found != null) {
            used[0] = found.split(" ").length;
            List<String> subcommands = reg.commands().keySet().stream().filter(c -> c.startsWith(found + " "))
                    .map(c -> c.substring(found.length() + 1)).sorted().toList();
            if (words.size() > used[0] && !subcommands.isEmpty() && reg.commands().get(found).args().isEmpty()) {
                String word = words.get(used[0]);
                String n = Registry.near(word, subcommands);
                throw new OpenTsxConfException(word, "unknown subcommand of " + found,
                        n != null ? "did you mean " + n + "?" : "expected one of " + String.join(", ", subcommands));
            }
            return found;
        }
        if (words.isEmpty()) {
            throw new OpenTsxConfException("<command>", "missing", "run opentsx --help for the commands");
        }
        String first = words.get(0);
        List<String> group = reg.commands().keySet().stream().filter(c -> c.split(" ")[0].equals(first)).sorted().toList();
        if (group.isEmpty()) {
            Set<String> firstWords = new TreeSet<>();
            for (String c : reg.commands().keySet()) {
                firstWords.add(c.split(" ")[0]);
            }
            String n = Registry.near(first, firstWords);
            throw new OpenTsxConfException(first, "unknown command",
                    n != null ? "did you mean " + n + "?" : "run opentsx --help for the commands");
        }
        if (words.size() == 1) {
            throw new OpenTsxConfException(first, "missing subcommand", "expected one of " + String.join(", ", group));
        }
        String two = words.get(0) + " " + words.get(1);
        String n = Registry.near(two, group);
        throw new OpenTsxConfException(two, "unknown command",
                n != null ? "did you mean " + n + "?" : "expected one of " + String.join(", ", group));
    }
}
