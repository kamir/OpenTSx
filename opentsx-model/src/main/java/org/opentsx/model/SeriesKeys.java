package org.opentsx.model;

import org.opentsx.model.v2.SeriesKey;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Series identity.
 *
 * <p>Canonical form: {@code escape(metric) + "{" + k1=v1,k2=v2,... + "}"} with keys sorted by their UTF-8 bytes
 * (unsigned) and {@code \ { } , =} escaped with a backslash. {@code seriesId = hex(xxHash64(utf8(canonical), 0))}.
 * The unit is not part of the identity. No Unicode normalisation is applied; callers should pass NFC strings.
 */
public final class SeriesKeys {

    private SeriesKeys() {
    }

    public static SeriesKey of(String metric, Map<String, String> tags, String unit) {
        Map<String, String> copy = tags == null ? Map.of() : tags;
        String id = seriesId(metric, copy);
        return SeriesKey.newBuilder()
                .setMetric(metric)
                .setTags(sortedByUtf8(copy))
                .setUnit(unit)
                .setSeriesId(id)
                .build();
    }

    public static SeriesKey of(String metric, Map<String, String> tags) {
        return of(metric, tags, null);
    }

    public static String seriesId(String metric, Map<String, String> tags) {
        return XxHash64.hex(XxHash64.hash(canonical(metric, tags).getBytes(StandardCharsets.UTF_8)));
    }

    public static String canonical(String metric, Map<String, String> tags) {
        if (metric == null || metric.isEmpty()) {
            throw new IllegalArgumentException("metric must not be empty");
        }
        StringBuilder sb = new StringBuilder(escape(metric)).append('{');
        String[] keys = tags.keySet().toArray(new String[0]);
        byte[][] utf8 = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null || keys[i].isEmpty()) {
                throw new IllegalArgumentException("tag keys must not be empty");
            }
            utf8[i] = keys[i].getBytes(StandardCharsets.UTF_8);
        }
        Integer[] order = new Integer[keys.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Arrays.compareUnsigned(utf8[a], utf8[b]));
        for (int n = 0; n < order.length; n++) {
            if (n > 0) {
                sb.append(',');
            }
            String key = keys[order[n]];
            String value = tags.get(key);
            if (value == null) {
                throw new IllegalArgumentException("tag value must not be null: " + key);
            }
            sb.append(escape(key)).append('=').append(escape(value));
        }
        return sb.append('}').toString();
    }

    /** Throws if the stored seriesId does not match metric and tags. */
    public static void verify(SeriesKey key) {
        String expected = seriesId(key.getMetric(), key.getTags());
        if (!expected.equals(key.getSeriesId())) {
            throw new IllegalArgumentException("seriesId " + key.getSeriesId() + " does not match " + expected);
        }
    }

    /**
     * Copy with keys in UTF-8 byte order. Avro writes maps in iteration order, so sorted maps make the
     * encoding deterministic (same bytes in Java and Python, stable checksums).
     */
    public static Map<String, String> sortedByUtf8(Map<String, String> map) {
        String[] keys = map.keySet().toArray(new String[0]);
        Arrays.sort(keys, (a, b) -> Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, map.get(k));
        }
        return out;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '{' || c == '}' || c == ',' || c == '=') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
