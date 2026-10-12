package org.opentsx.twin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atom parameters from the wiring plan. */
public final class Params {

    private final Map<String, Object> values;

    public Params(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Params of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new Params(m);
    }

    public double d(String name, double dflt) {
        Object v = values.get(name);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(v.toString());
    }

    public int i(String name, int dflt) {
        Object v = values.get(name);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(v.toString());
    }

    public String s(String name, String dflt) {
        Object v = values.get(name);
        return v == null ? dflt : v.toString();
    }

    @SuppressWarnings("unchecked")
    public List<String> list(String name, List<String> dflt) {
        Object v = values.get(name);
        return v instanceof List<?> l ? (List<String>) l : dflt;
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
