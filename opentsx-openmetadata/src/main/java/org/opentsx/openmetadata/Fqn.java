package org.opentsx.openmetadata;

import java.util.ArrayList;
import java.util.List;

/** OpenMetadata fully qualified names: parts joined by '.', parts that contain a '.' are wrapped in double quotes. */
public final class Fqn {

    private Fqn() {
    }

    public static String quote(String name) {
        if (name.contains("\"")) {
            throw new IllegalArgumentException("OpenMetadata names must not contain '\"': " + name);
        }
        return name.contains(".") ? "\"" + name + "\"" : name;
    }

    public static String build(String... parts) {
        List<String> quoted = new ArrayList<>(parts.length);
        for (String p : parts) {
            quoted.add(quote(p));
        }
        return String.join(".", quoted);
    }

    /** Appends a local name to an existing FQN. */
    public static String child(String parentFqn, String name) {
        return parentFqn + "." + quote(name);
    }

    /** Entity link to a column, as used by column-level test cases. */
    public static String columnLink(String tableFqn, String column) {
        return "<#E::table::" + tableFqn + "::columns::" + column + ">";
    }
}
