package org.opentsx.windpark.catalog;

import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/** Unit conversion for the units that appear in wind-farm SCADA data (UCUM codes and common spellings). */
public final class Units {

    private static final Map<String, String> SPELLINGS = Map.ofEntries(
            Map.entry("m/s", "m/s"), Map.entry("mps", "m/s"), Map.entry("km/h", "km/h"), Map.entry("kmh", "km/h"),
            Map.entry("kph", "km/h"), Map.entry("kn", "[kn_i]"), Map.entry("kt", "[kn_i]"), Map.entry("knots", "[kn_i]"),
            Map.entry("[kn_i]", "[kn_i]"),
            Map.entry("w", "W"), Map.entry("kw", "kW"), Map.entry("mw", "MW"),
            Map.entry("cel", "Cel"), Map.entry("°c", "Cel"), Map.entry("degc", "Cel"), Map.entry("c", "Cel"),
            Map.entry("k", "K"), Map.entry("°f", "[degF]"), Map.entry("degf", "[degF]"), Map.entry("[degf]", "[degF]"),
            Map.entry("deg", "deg"), Map.entry("°", "deg"), Map.entry("rad", "rad"),
            Map.entry("rpm", "rpm"), Map.entry("1/min", "rpm"), Map.entry("rad/s", "rad/s"),
            Map.entry("1", "1"), Map.entry("", "1"));

    private static final Map<String, Map<String, DoubleUnaryOperator>> TO = Map.of(
            "m/s", Map.of("km/h", v -> v / 3.6, "[kn_i]", v -> v * 0.514444),
            "kW", Map.of("W", v -> v / 1000.0, "MW", v -> v * 1000.0),
            "Cel", Map.of("K", v -> v - 273.15, "[degF]", v -> (v - 32.0) * 5.0 / 9.0),
            "deg", Map.of("rad", Math::toDegrees),
            "rpm", Map.of("rad/s", v -> v * 60.0 / (2 * Math.PI)));

    private Units() {
    }

    /** Canonical UCUM spelling, e.g. "kw" -> "kW", "°C" -> "Cel". */
    public static String canonical(String unit) {
        String u = SPELLINGS.get(unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT));
        if (u == null) {
            throw new IllegalArgumentException("unknown unit '" + unit + "'");
        }
        return u;
    }

    public static double convert(double value, String fromUnit, String toUnit) {
        String from = canonical(fromUnit);
        String to = canonical(toUnit);
        if (from.equals(to)) {
            return value;
        }
        DoubleUnaryOperator f = TO.getOrDefault(to, Map.of()).get(from);
        if (f == null) {
            throw new IllegalArgumentException("cannot convert " + fromUnit + " to " + toUnit);
        }
        return f.applyAsDouble(value);
    }
}
