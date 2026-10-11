package org.opentsx.windpark.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned data-point catalog ("Kanal-Katalog") mapped to IEC 61400-25-2, with source-system mappings,
 * units, time reference, aggregation and quality codes. The default ships as
 * {@code windpark/channel-catalog.yaml}; projects maintain their own and load it with {@link #load(Path)}.
 */
public final class ChannelCatalog {

    private final String name;
    private final String version;
    private final String iecEdition;
    private final Map<Integer, String> qualityCodes;
    private final Map<String, ChannelDef> byId = new LinkedHashMap<>();
    private final Map<String, ChannelDef> byKey = new LinkedHashMap<>();
    private final Map<String, SourceSystem> sources = new LinkedHashMap<>();

    public ChannelCatalog(String name, String version, String iecEdition, Map<Integer, String> qualityCodes,
                          List<ChannelDef> channels, List<SourceSystem> sources) {
        this.name = name;
        this.version = version;
        this.iecEdition = iecEdition;
        this.qualityCodes = qualityCodes == null ? Map.of() : Map.copyOf(qualityCodes);
        for (ChannelDef c : channels) {
            if (byId.put(c.id(), c) != null) {
                throw new IllegalArgumentException("duplicate channel id " + c.id());
            }
            register(c.metric(), c);
            register(c.iecReference(), c);
        }
        for (SourceSystem s : sources == null ? List.<SourceSystem>of() : sources) {
            if (this.sources.put(s.system(), s) != null) {
                throw new IllegalArgumentException("duplicate source system " + s.system());
            }
            for (SourceMapping m : s.mappings()) {
                if (!byId.containsKey(m.channel())) {
                    throw new IllegalArgumentException(s.system() + "/" + m.sourceName() + " maps to unknown channel " + m.channel());
                }
            }
        }
    }

    public static ChannelCatalog standard() {
        try (InputStream in = ChannelCatalog.class.getResourceAsStream("/windpark/channel-catalog.yaml")) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ChannelCatalog load(Path yaml) {
        try (InputStream in = Files.newInputStream(yaml)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ChannelCatalog load(InputStream yaml) throws IOException {
        ObjectMapper m = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        CatalogFile f = m.readValue(yaml, CatalogFile.class);
        return new ChannelCatalog(f.catalog(), f.version(), f.iecEdition(), f.qualityCodes(), f.channels(), f.sources());
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String iecEdition() {
        return iecEdition;
    }

    public Map<Integer, String> qualityCodes() {
        return qualityCodes;
    }

    public List<ChannelDef> channels() {
        return List.copyOf(byId.values());
    }

    public List<SourceSystem> sources() {
        return List.copyOf(sources.values());
    }

    /** By catalog id (e.g. {@code WTUR.W}) or canonical metric. */
    public ChannelDef channel(String idOrMetric) {
        ChannelDef c = byId.get(idOrMetric);
        if (c == null) {
            c = byKey.get(key(idOrMetric));
        }
        if (c == null) {
            throw new IllegalArgumentException("unknown channel " + idOrMetric);
        }
        return c;
    }

    /** Canonical metric or IEC reference (case-insensitive). Vendor names go through {@link #mapping}. */
    public Optional<ChannelDef> resolve(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byKey.get(key(name)));
    }

    public Optional<SourceSystem> source(String system) {
        return Optional.ofNullable(sources.get(system));
    }

    /** The mapping of an original channel name of a source system (exact name, then case-insensitive). */
    public Optional<SourceMapping> mapping(String system, String sourceName) {
        SourceSystem s = sources.get(system);
        if (s == null || sourceName == null) {
            return Optional.empty();
        }
        return s.mappings().stream().filter(m -> m.sourceName().equals(sourceName)).findFirst()
                .or(() -> s.mappings().stream().filter(m -> m.sourceName().equalsIgnoreCase(sourceName)).findFirst());
    }

    private void register(String name, ChannelDef c) {
        ChannelDef old = byKey.put(key(name), c);
        if (old != null && old != c) {
            throw new IllegalArgumentException("name '" + name + "' maps to " + old.id() + " and " + c.id());
        }
    }

    private static String key(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    record CatalogFile(String catalog, String version, String iecEdition, Map<Integer, String> qualityCodes,
                       List<ChannelDef> channels, List<SourceSystem> sources) {
    }
}
