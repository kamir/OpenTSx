package org.opentsx.market;

import org.opentsx.market.source.Marktwerte;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The series the collector knows, by archive path {@code source/name}. Metrics follow the v2 naming
 * ({@code market.*}, {@code grid.*}); tags carry the bidding zone or control area.
 * <p>
 * SMARD filter ids (chart_data API): 4169 day-ahead price DE-LU, 410 grid load (Netzlast), 4359 residual load,
 * 4067 wind onshore, 1225 wind offshore, 4068 photovoltaics; forecasts 123 wind onshore, 3791 wind offshore,
 * 125 photovoltaics, 411 load.
 */
public final class MarketCatalog {

    /** A SMARD series: filter id, region, and whether the values are energies per interval (MWh) to be averaged. */
    public record Smard(SeriesDef def, int filter, String region, boolean energy) {
    }

    private static final Map<String, SeriesDef> DEFS = new LinkedHashMap<>();
    private static final Map<String, Smard> SMARD = new LinkedHashMap<>();

    static {
        smard("day_ahead_price", "market.price.day_ahead", "DE-LU", "EUR/MWh", "REPORTED", 4169, false);
        smard("load", "grid.load", "DE", "MW", "REPORTED", 410, true);
        smard("residual_load", "grid.load.residual", "DE", "MW", "REPORTED", 4359, true);
        smard("wind_onshore", "grid.generation.wind_onshore", "DE", "MW", "REPORTED", 4067, true);
        smard("wind_offshore", "grid.generation.wind_offshore", "DE", "MW", "REPORTED", 1225, true);
        smard("solar", "grid.generation.solar", "DE", "MW", "REPORTED", 4068, true);
        smard("forecast_wind_onshore", "grid.generation.wind_onshore", "DE", "MW", "FORECAST", 123, true);
        smard("forecast_wind_offshore", "grid.generation.wind_offshore", "DE", "MW", "FORECAST", 3791, true);
        smard("forecast_solar", "grid.generation.solar", "DE", "MW", "FORECAST", 125, true);
        smard("forecast_load", "grid.load", "DE", "MW", "FORECAST", 411, true);

        def("energy-charts", "day_ahead_price", "market.price.day_ahead", Map.of("area", "DE-LU"), "EUR/MWh",
                "REPORTED", Licences.ENERGY_CHARTS);

        def("entsoe", "day_ahead_price", "market.price.day_ahead", Map.of("area", "DE-LU"), "EUR/MWh", "REPORTED",
                Licences.ENTSOE);
        def("entsoe", "load", "grid.load", Map.of("area", "DE-LU"), "MW", "REPORTED", Licences.ENTSOE);
        def("entsoe", "forecast_wind_onshore", "grid.generation.wind_onshore", Map.of("area", "DE-LU", "kind", "forecast"),
                "MW", "FORECAST", Licences.ENTSOE);
        def("entsoe", "forecast_wind_offshore", "grid.generation.wind_offshore",
                Map.of("area", "DE-LU", "kind", "forecast"), "MW", "FORECAST", Licences.ENTSOE);
        def("entsoe", "forecast_solar", "grid.generation.solar", Map.of("area", "DE-LU", "kind", "forecast"), "MW",
                "FORECAST", Licences.ENTSOE);

        def("netztransparenz", "rebap_short", "market.price.imbalance", Map.of("area", "DE", "side", "short"),
                "EUR/MWh", "REPORTED", Licences.NETZTRANSPARENZ);
        def("netztransparenz", "rebap_long", "market.price.imbalance", Map.of("area", "DE", "side", "long"),
                "EUR/MWh", "REPORTED", Licences.NETZTRANSPARENZ);
        for (String tech : Marktwerte.COLUMNS.values()) {
            def("netztransparenz", "marktwert_" + tech, "market.value.monthly", Map.of("area", "DE", "technology", tech),
                    "EUR/MWh", "REPORTED", Licences.NETZTRANSPARENZ);
        }
    }

    private MarketCatalog() {
    }

    private static void smard(String name, String metric, String region, String unit, String origin, int filter,
                              boolean energy) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("area", "DE-LU".equals(region) ? "DE-LU" : "DE");
        if (origin.equals("FORECAST")) {
            tags.put("kind", "forecast");
        }
        SeriesDef d = def("smard", name, metric, tags, unit, origin, Licences.SMARD);
        SMARD.put(name, new Smard(d, filter, region, energy));
    }

    private static SeriesDef def(String source, String name, String metric, Map<String, String> tags, String unit,
                                 String origin, String licence) {
        SeriesDef d = new SeriesDef(source, name, metric, tags, unit, origin, licence);
        DEFS.put(d.path(), d);
        return d;
    }

    /** Definition by archive path {@code source/name}. */
    public static Optional<SeriesDef> get(String path) {
        return Optional.ofNullable(DEFS.get(path));
    }

    public static SeriesDef require(String path) {
        return get(path).orElseThrow(() -> new IllegalArgumentException("unknown series " + path + "; known: "
                + DEFS.keySet()));
    }

    public static List<SeriesDef> all() {
        return List.copyOf(DEFS.values());
    }

    public static Smard smard(String name) {
        Smard s = SMARD.get(name);
        if (s == null) {
            throw new IllegalArgumentException("unknown SMARD series " + name + "; known: " + SMARD.keySet());
        }
        return s;
    }

    public static List<Smard> smardSeries() {
        return List.copyOf(SMARD.values());
    }

    /** A partner series (direct marketer schedule, intraday prices, ...) imported from a file. */
    public static SeriesDef partner(String name, String metric, String unit, String origin, Map<String, String> tags) {
        return new SeriesDef("partner", name, metric, tags, unit, origin, Licences.PARTNER);
    }

    /** A weather station series (DWD CDC); {@code param} is {@code air_temperature}, {@code wind_speed} or
     * {@code global_radiation}. */
    public static SeriesDef dwd(String station, String param) {
        String unit = switch (param) {
            case "air_temperature" -> "Cel";
            case "wind_speed" -> "m/s";
            case "global_radiation" -> "W/m2";
            default -> throw new IllegalArgumentException("unknown DWD parameter " + param);
        };
        return new SeriesDef("dwd-cdc", station + "/" + param, "weather." + param, Map.of("station", station), unit,
                "MEASURED", Licences.DWD);
    }
}
