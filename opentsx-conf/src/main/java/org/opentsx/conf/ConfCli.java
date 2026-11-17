package org.opentsx.conf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.conf.Registry.Key;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public final class ConfCli {

    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";
    static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?$");

    private ConfCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(Registry.shipped(), List.of(args), System.getenv(), out, err));
    }

    static int run(Registry registry, List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        String text;
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(registry, args, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.version()) {
                text = versionLine();
            } else if (loaded.help()) {
                text = loaded.command() == null ? registry.help() : registry.help(loaded.command());
            } else {
                String output = ConfParser.parseArgs(registry, args).output();
                text = switch (loaded.command()) {
                    case "config list" -> "json".equals(output)
                            ? new String(registry.json(), StandardCharsets.UTF_8) : list(registry);
                    case "config show" -> show(loaded.conf(), "json".equals(output));
                    case "config get" -> get(loaded.conf(), loaded.args().get(0));
                    case "version" -> versionLine();
                    default -> throw new OpenTsxConfException(loaded.command(), "not a command of " + ConfCli.class.getName(),
                            "run bin/opentsx " + loaded.command());
                };
            }
        } catch (OpenTsxConfException e) {
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            err.print("opentsx: " + (e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            err.flush();
            return 1;
        }
        out.print(text);
        out.flush();
        // PrintStream never throws; without this check a full disk or a closed stdout would exit 0.
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    static String list(Registry registry) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"KEY", "TYPE", "DEFAULT", "FLAG", "ENV"});
        for (Key e : registry.keys()) {
            String flags = e.secret() || e.flags().isEmpty() ? "-" : Registry.dashed(e.flags());
            rows.add(new String[]{e.key(), e.typeName(), e.defaultValue() == null ? "-" : e.defaultValue(), flags,
                    String.join(", ", e.envNames())});
        }
        return table(rows);
    }

    static String show(OpenTsxConf conf, boolean json) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> p : conf.getAll()) {
            rows.add(new String[]{p.getKey(), conf.shown(p.getKey()), conf.source(p.getKey())});
        }
        if (!json) {
            rows.add(0, new String[]{"KEY", "VALUE", "SOURCE"});
            return table(rows);
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode doc = mapper.createObjectNode().put("schema", "opentsx.config.show.v1");
        ArrayNode values = doc.putArray("values");
        for (String[] r : rows) {
            values.addObject().put("key", r[0]).put("value", r[1]).put("source", r[2]);
        }
        try {
            return mapper.writeValueAsString(doc) + "\n";
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String get(OpenTsxConf conf, String key) {
        Key e = conf.registry().entry(key, key);
        if (conf.getOption(key).isEmpty()) {
            throw OpenTsxConf.notSet(e, false);
        }
        return conf.shown(key) + "\n";
    }

    static String table(List<String[]> rows) {
        int columns = rows.get(0).length;
        int[] width = new int[columns];
        for (String[] r : rows) {
            for (int c = 0; c < columns - 1; c++) {
                width[c] = Math.max(width[c], r[c].codePointCount(0, r[c].length()));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            for (int c = 0; c < columns - 1; c++) {
                sb.append(Registry.pad(r[c], width[c])).append("  ");
            }
            sb.append(r[columns - 1]).append('\n');
        }
        return sb.toString();
    }

    static String versionLine() {
        return "opentsx " + version() + " (java)\n";
    }

    static String version() {
        try (InputStream in = ConfCli.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                throw new IllegalStateException("org/opentsx/conf/version.properties: missing from the class path; build opentsx-conf with Maven");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            if (v == null || !VERSION.matcher(v).matches()) {
                throw new IllegalStateException("org/opentsx/conf/version.properties: version \"" + v
                        + "\" is not stamped; build opentsx-conf with Maven");
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException("org/opentsx/conf/version.properties: cannot read", e);
        }
    }
}
