package org.opentsx.model;

import org.apache.avro.Schema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen schemas of every released model version ({@code schema-history/<version>/}).
 * A schema change requires a new version directory; the build checks BACKWARD_TRANSITIVE compatibility.
 */
public final class SchemaHistory {

    private static final String ROOT = "/schema-history/";

    private SchemaHistory() {
    }

    public static List<String> versions() {
        return lines(ROOT + "versions.txt");
    }

    /** Record schemas of one released version, by simple name. */
    public static Map<String, Schema> schemas(String version) {
        Schema.Parser parser = new Schema.Parser();
        Map<String, Schema> out = new LinkedHashMap<>();
        for (String file : lines(ROOT + version + "/index.txt")) {
            try (InputStream in = open(ROOT + version + "/" + file)) {
                Schema s = parser.parse(in);
                out.put(s.getName(), s);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static List<String> lines(String resource) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(resource), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(line.trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static InputStream open(String resource) {
        InputStream in = SchemaHistory.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("missing resource " + resource);
        }
        return in;
    }
}
