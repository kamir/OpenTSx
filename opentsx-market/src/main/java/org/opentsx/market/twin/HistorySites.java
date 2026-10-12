package org.opentsx.market.twin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sites of the history runs and their DWD stations ({@code market/history-sites.yaml}). */
public final class HistorySites {

    public static final String RESOURCE = "market/history-sites.yaml";
    public static final String HOUSE = "haus-erzgebirge";
    public static final String PARK = "windpark-ostsee";
    public static final String BOAT = "boot-kieler-bucht";

    /** One site; station ids are DWD CDC ids (5 digits). */
    public record Site(String id, double latitude, double longitude, String temperature, String wind,
                       String radiation, double anemometerHeightM, double hubHeightM, double shearExponent) {
    }

    private HistorySites() {
    }

    public static Map<String, Site> load(Path file) {
        try (InputStream in = file == null ? HistorySites.class.getClassLoader().getResourceAsStream(RESOURCE)
                : Files.newInputStream(file)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in).path("sites");
            Map<String, Site> out = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                out.put(e.getKey(), new Site(e.getKey(), n.path("latitude").asDouble(), n.path("longitude").asDouble(),
                        n.path("temperature").asText(null), n.path("wind").asText(null), n.path("radiation").asText(null),
                        n.path("anemometerHeightM").asDouble(10), n.path("hubHeightM").asDouble(120),
                        n.path("shearExponent").asDouble(0.14)));
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Site get(Path file, String id) {
        Site s = load(file).get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown site " + id);
        }
        return s;
    }
}
