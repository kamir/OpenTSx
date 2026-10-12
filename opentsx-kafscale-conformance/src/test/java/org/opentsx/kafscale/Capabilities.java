package org.opentsx.kafscale;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.TreeMap;

/** Collects probed broker capabilities and writes them to target/kafscale-capabilities.properties. */
final class Capabilities {

    private static final TreeMap<String, String> RESULTS = new TreeMap<>();

    private Capabilities() {
    }

    static synchronized void record(String key, Object value) {
        RESULTS.put(key, String.valueOf(value));
        System.out.println("[kafscale-capability] " + key + " = " + value);
    }

    static synchronized void write() {
        Properties p = new Properties();
        p.putAll(RESULTS);
        p.put("bootstrap", String.valueOf(System.getenv("KAFSCALE_BOOTSTRAP")));
        Path out = Path.of("target", "kafscale-capabilities.properties");
        try {
            Files.createDirectories(out.getParent());
            try (OutputStream os = Files.newOutputStream(out)) {
                p.store(os, "Probed broker capabilities (OPTIONAL features for OpenTSx)");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + out, e);
        }
    }
}
