package org.opentsx.store.opentsdb;

import java.util.Map;
import java.util.regex.Pattern;

/** OpenTSDB naming rules: metric, tag keys and values use letters, digits and {@code - _ . /}; at most 8 tags. */
final class OpenTsdbNames {

    static final String UNIT_TAG = "_unit";
    static final int MAX_TAGS = 8;

    private static final Pattern VALID = Pattern.compile("[\\p{L}\\p{N}\\-_./]+");

    private OpenTsdbNames() {
    }

    static void validate(String metric, Map<String, String> tags, boolean withUnit) {
        check("metric", metric);
        tags.forEach((k, v) -> {
            check("tag key", k);
            check("tag value", v);
            if (UNIT_TAG.equals(k)) {
                throw new IllegalArgumentException("tag key " + UNIT_TAG + " is reserved for the unit");
            }
        });
        int n = tags.size() + (withUnit ? 1 : 0);
        if (n == 0) {
            throw new IllegalArgumentException("OpenTSDB needs at least one tag: " + metric);
        }
        if (n > MAX_TAGS) {
            throw new IllegalArgumentException("OpenTSDB allows at most " + MAX_TAGS + " tags (incl. unit), got " + n);
        }
    }

    private static void check(String what, String s) {
        if (s == null || !VALID.matcher(s).matches()) {
            throw new IllegalArgumentException("invalid OpenTSDB " + what + ": '" + s + "' (allowed: letters, digits, - _ . /)");
        }
    }
}
