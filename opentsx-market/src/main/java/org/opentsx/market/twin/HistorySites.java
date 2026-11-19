package org.opentsx.market.twin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Sites of the history runs and their DWD stations ({@code market/history-sites.yaml}). */
public final class HistorySites {

    public static final String RESOURCE = "market/history-sites.yaml";
    public static final String HOUSE = "haus-erzgebirge";
    public static final String PARK = "windpark-ostsee";
    public static final String BOAT = "boot-kieler-bucht";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String NEEDS_SITES = "the file needs a map sites with one entry per site";
    private static final String FINITE = "expected a finite decimal number such as 0.01 or 1e-3";
    private static final String STATION = "expected a station id of 5 digits in quotes such as \"03166\"";

    static {
        // an unquoted 03166 is the octal number 1654 in YAML, so a station id must be a string
        YAML.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
    private static final Pattern MARK = Pattern.compile("^ in .*, line (\\d+), column \\d+:$", Pattern.MULTILINE);

    /** One site; station ids are DWD CDC ids (5 digits). */
    public record Site(String id, double latitude, double longitude, String temperature, String wind,
                       String radiation, double anemometerHeightM, double hubHeightM, double shearExponent) {
    }

    record SitesFile(Map<String, SiteFile> sites) {
    }

    record SiteFile(Double latitude, Double longitude, String temperature, String wind, String radiation,
                    Double anemometerHeightM, Double hubHeightM, Double shearExponent) {
    }

    private HistorySites() {
    }

    /**
     * Sites of {@code file}, or of the built-in resource when it is null. Latitude and longitude are required; a site
     * without a station for a variable has none; a station id is a string of 5 digits; the wind power fields default to
     * 10 m, 120 m and 0.14. An unknown or missing field fails naming the file and the field.
     */
    public static Map<String, Site> load(Path file) {
        String source = file == null ? "classpath:" + RESOURCE : file.toString();
        SitesFile f;
        try (InputStream in = file == null ? HistorySites.class.getClassLoader().getResourceAsStream(RESOURCE)
                : Files.newInputStream(file)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            f = YAML.readValue(in, SitesFile.class);
        } catch (UnrecognizedPropertyException e) {
            throw new IllegalArgumentException(source + path(e) + ": unknown field; allowed: " + e.getKnownPropertyIds()
                    .stream().map(String::valueOf).sorted().collect(Collectors.joining(", ")), e);
        } catch (InvalidFormatException e) {
            throw new IllegalArgumentException(source + path(e) + (e.getTargetType() == String.class
                    ? ": wrong type; " + STATION : ": \"" + e.getValue() + "\" is not a double; " + FINITE), e);
        } catch (MismatchedInputException e) {
            throw new IllegalArgumentException(source + (e.getPath().isEmpty() ? ": sites: missing; " + NEEDS_SITES
                    : path(e) + (e.getTargetType() == String.class ? ": wrong type; " + STATION : ": wrong type; expected "
                    + (e.getTargetType() == Double.class ? "a number" : "a map"))), e);
        } catch (JsonProcessingException e) {
            // a syntax error inside a value arrives wrapped in the mapping error of that value
            JsonProcessingException p = e.getCause() instanceof JsonProcessingException c ? c : e;
            throw new IllegalArgumentException(source + line(p) + ": not valid YAML; "
                    + problem(p.getOriginalMessage()), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (f == null || f.sites() == null) {
            throw new IllegalArgumentException(source + ": sites: missing; " + NEEDS_SITES);
        }
        Map<String, Site> out = new LinkedHashMap<>();
        f.sites().forEach((id, s) -> {
            if (s == null || s.latitude() == null || s.longitude() == null) {
                throw new IllegalArgumentException(source + ": sites." + id + "."
                        + (s == null || s.latitude() == null ? "latitude" : "longitude")
                        + ": missing; a site needs latitude and longitude in degrees");
            }
            Site site = new Site(id, s.latitude(), s.longitude(), s.temperature(), s.wind(), s.radiation(),
                    s.anemometerHeightM() == null ? 10 : s.anemometerHeightM(),
                    s.hubHeightM() == null ? 120 : s.hubHeightM(),
                    s.shearExponent() == null ? 0.14 : s.shearExponent());
            String[] fields = {"latitude", "longitude", "anemometerHeightM", "hubHeightM", "shearExponent"};
            double[] values = {site.latitude(), site.longitude(), site.anemometerHeightM(), site.hubHeightM(),
                    site.shearExponent()};
            for (int i = 0; i < fields.length; i++) {
                if (!Double.isFinite(values[i])) {
                    throw new IllegalArgumentException(source + ": sites." + id + "." + fields[i] + ": not finite; "
                            + FINITE);
                }
            }
            String[] stationFields = {"temperature", "wind", "radiation"};
            String[] stations = {site.temperature(), site.wind(), site.radiation()};
            for (int i = 0; i < stationFields.length; i++) {
                if (stations[i] != null && !stations[i].matches("[0-9]{5}")) {
                    throw new IllegalArgumentException(source + ": sites." + id + "." + stationFields[i] + ": \""
                            + stations[i] + "\" is not a station id; " + STATION);
                }
            }
            out.put(id, site);
        });
        return out;
    }

    /** {@code ": a.b.c"} for the field of {@code e}, or nothing when the problem is the whole file. */
    private static String path(JsonMappingException e) {
        return e.getPath().stream().map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining(".", e.getPath().isEmpty() ? "" : ": ", ""));
    }

    /**
     * {@code ":<line>"} of a parser problem: the last position SnakeYAML names in the message, else the parser's
     * location, which for a problem found while looking ahead is the line before it.
     */
    private static String line(JsonProcessingException p) {
        String line = p.getLocation() == null ? "" : ":" + p.getLocation().getLineNr();
        Matcher m = MARK.matcher(String.valueOf(p.getOriginalMessage()));
        while (m.find()) {
            line = ":" + m.group(1);
        }
        return line;
    }

    /** The context and problem lines of a parser message, without its position and excerpt lines. */
    private static String problem(String message) {
        return String.valueOf(message).lines().filter(l -> !l.isBlank() && !Character.isWhitespace(l.charAt(0)))
                .collect(Collectors.joining(", "));
    }

    public static Site get(Path file, String id) {
        Site s = load(file).get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown site " + id);
        }
        return s;
    }
}
