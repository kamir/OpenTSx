package org.opentsx.conf;

import org.opentsx.conf.Registry.Key;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The configuration of one OpenTSx command, in the style of Spark's {@code SparkConf}. A value comes from one of six
 * sources, highest first: {@code code}, {@code commandLine}, {@code environment}, {@code propertiesFile},
 * {@code defaultsFile}, {@code default}. Every value is checked against the type of its key in the registry.
 */
public final class OpenTsxConf {

    public static final List<String> SOURCES =
            List.of("code", "commandLine", "environment", "propertiesFile", "defaultsFile", "default");

    /** The result of {@link #load}: with help or version, {@code conf} is null and nothing else was checked. */
    public record Loaded(String command, OpenTsxConf conf, List<String> args, boolean help, boolean version) {
    }

    private record Value(String text, String source) {
    }

    private final Registry registry;
    private final TreeMap<String, Value> values = new TreeMap<>();

    public OpenTsxConf() {
        this(Registry.shipped());
    }

    public OpenTsxConf(Registry registry) {
        this.registry = registry;
        for (Key e : registry.keys()) {
            if (e.defaultValue() != null) {
                values.put(e.key(), new Value(e.defaultValue(), "default"));
            }
        }
    }

    public Registry registry() {
        return registry;
    }

    /** {@code args} starts with the command words; {@code confDir} is the value of OPENTSX_CONF_DIR as given. */
    public static Loaded load(List<String> args, Map<String, String> env, String confDir) {
        return load(Registry.shipped(), args, env, confDir);
    }

    public static Loaded load(Registry registry, List<String> args, Map<String, String> env, String confDir) {
        ConfParser.Args a = ConfParser.parseArgs(registry, args);
        if (a.help() || a.version()) {
            return new Loaded(a.command(), null, List.of(), a.help(), a.version());
        }
        Map<String, String> fromEnv = ConfParser.readEnv(registry, env);
        Map<String, String> fromProperties = a.propertiesFile() == null ? Map.of() : ConfParser.readFile(registry, a.propertiesFile());
        Map<String, String> fromDefaults = confDir == null || ConfParser.trim(confDir).isEmpty()
                ? Map.of() : defaultsFile(registry, confDir);
        OpenTsxConf conf = new OpenTsxConf(registry);
        List<Map<String, String>> layers = List.of(a.pairs(), fromEnv, fromProperties, fromDefaults);
        for (Key e : registry.keys()) {
            for (int i = 0; i < layers.size(); i++) {
                String v = layers.get(i).get(e.key());
                if (v != null) {
                    conf.values.put(e.key(), new Value(v, SOURCES.get(i + 1)));
                    break;
                }
            }
        }
        return new Loaded(a.command(), conf, a.positional(), false, false);
    }

    private static Map<String, String> defaultsFile(Registry registry, String dir) {
        if (!ConfParser.utf8(dir)) {
            throw new OpenTsxConfException("OPENTSX_CONF_DIR", "path is not valid UTF-8", ConfParser.UTF8_ALLOWED);
        }
        if (!isDirectory(dir)) {
            throw new OpenTsxConfException(dir, "not a directory",
                    "set OPENTSX_CONF_DIR to the directory that holds opentsx-defaults.conf or opentsx-defaults.yaml");
        }
        if (Files.exists(Path.of(dir, "opentsx-defaults.yml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new OpenTsxConfException(dir + "/opentsx-defaults.yml", "not a defaults file name",
                    "rename it to opentsx-defaults.yaml");
        }
        List<String> found = new ArrayList<>();
        for (String name : List.of("opentsx-defaults.conf", "opentsx-defaults.yaml")) {
            if (Files.exists(Path.of(dir, name), LinkOption.NOFOLLOW_LINKS)) {
                found.add(dir + "/" + name);
            }
        }
        if (found.size() == 2) {
            throw new OpenTsxConfException(dir, "both opentsx-defaults.conf and opentsx-defaults.yaml exist", "keep one");
        }
        return found.isEmpty() ? Map.of() : ConfParser.readFile(registry, found.get(0));
    }

    private static boolean isDirectory(String dir) {
        try {
            return Files.isDirectory(Path.of(dir));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    public OpenTsxConf set(String key, String value) {
        Key e = registry.entry(key, key);
        values.put(key, new Value(ConfParser.parse(e, value, key), "code"));
        return this;
    }

    /** The value is checked also when the key is set. */
    public OpenTsxConf setIfMissing(String key, String value) {
        Key e = registry.entry(key, key);
        String text = ConfParser.parse(e, value, key);
        if (!contains(key)) {
            values.put(key, new Value(text, "code"));
        }
        return this;
    }

    public OpenTsxConf setAll(Map<String, String> pairs) {
        for (Map.Entry<String, String> p : pairs.entrySet()) {
            set(p.getKey(), p.getValue());
        }
        return this;
    }

    public String get(String key) {
        Key e = registry.entry(key, key);
        Value v = values.get(key);
        if (v == null) {
            throw notSet(e, true);
        }
        return v.text();
    }

    public String get(String key, String defaultValue) {
        return getOption(key).orElse(defaultValue);
    }

    public Optional<String> getOption(String key) {
        registry.entry(key, key);
        Value v = values.get(key);
        return v == null ? Optional.empty() : Optional.of(v.text());
    }

    public int getInt(String key) {
        return Integer.parseInt(typed(key, "getInt"));
    }

    public long getLong(String key) {
        return Long.parseLong(typed(key, "getLong"));
    }

    public double getDouble(String key) {
        return Double.parseDouble(typed(key, "getDouble"));
    }

    public boolean getBoolean(String key) {
        return Registry.ascii(typed(key, "getBoolean"), true).equals("true");
    }

    public Duration getDuration(String key) {
        return Duration.ofMillis(ConfParser.millis(typed(key, "getDuration")));
    }

    public List<String> getList(String key) {
        return List.of(typed(key, "getList").split(","));
    }

    public List<Duration> getDurationList(String key) {
        List<Duration> out = new ArrayList<>();
        for (String item : typed(key, "getDurationList").split(",")) {
            out.add(Duration.ofMillis(ConfParser.millis(item)));
        }
        return List.copyOf(out);
    }

    private String typed(String key, String getter) {
        Key e = registry.entry(key, key);
        String expected = getter(e);
        boolean ok = getter.equals(expected) || (getter.equals("getList") && "list".equals(e.type()));
        if (!ok) {
            throw new OpenTsxConfException(key, "has type " + e.typeName(), "read it with " + expected);
        }
        return get(key);
    }

    private static String getter(Key e) {
        return switch (e.type()) {
            case "int" -> "getInt";
            case "long" -> "getLong";
            case "double" -> "getDouble";
            case "boolean" -> "getBoolean";
            case "duration" -> "getDuration";
            case "list" -> "duration".equals(e.items()) ? "getDurationList" : "getList";
            default -> "get";
        };
    }

    /** A registry default does not count. */
    public boolean contains(String key) {
        registry.entry(key, key);
        Value v = values.get(key);
        return v != null && !v.source().equals("default");
    }

    /** Real values, secrets included. */
    public List<Map.Entry<String, String>> getAll() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, Value> v : values.entrySet()) {
            out.add(Map.entry(v.getKey(), v.getValue().text()));
        }
        return out;
    }

    public String source(String key) {
        registry.entry(key, key);
        Value v = values.get(key);
        return v == null ? null : v.source();
    }

    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Value> v : values.entrySet()) {
            sb.append(v.getKey()).append('=').append(shown(v.getKey())).append('\n');
        }
        return sb.toString();
    }

    String shown(String key) {
        return registry.entry(key, key).secret() ? Registry.MASK : get(key);
    }

    /** {@code withFlag} false for a command that does not take the flag of the key, such as {@code config get}. */
    static OpenTsxConfException notSet(Key e, boolean withFlag) {
        String env = Registry.envName(e.key());
        String ways;
        if (e.secret()) {
            ways = env + " or a properties file";
        } else if (withFlag && e.flag() != null) {
            ways = "--" + e.flag() + ", --conf " + e.key() + "=<value> or " + env;
        } else {
            ways = "--conf " + e.key() + "=<value> or " + env;
        }
        return new OpenTsxConfException(e.key(), "not set", "set it with " + ways);
    }
}
