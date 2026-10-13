package org.opentsx.dwd;

/**
 * Canonical weather variables of the collector. Each has a v2 metric name, a UCUM unit (the unit every DWD source is
 * converted to) and a CSV column name.
 */
public enum Variable {
    WIND_SPEED("weather.wind_speed", "m/s", "wind_speed_m_s"),
    WIND_DIRECTION("weather.wind_direction", "deg", "wind_direction_deg"),
    AIR_TEMPERATURE("weather.air_temperature", "Cel", "air_temperature_c"),
    RELATIVE_HUMIDITY("weather.relative_humidity", "%", "relative_humidity_pct"),
    PRESSURE("weather.pressure", "hPa", "pressure_hpa"),
    GLOBAL_IRRADIANCE("weather.irradiance_global", "W/m2", "global_irradiance_w_m2"),
    DIFFUSE_IRRADIANCE("weather.irradiance_diffuse", "W/m2", "diffuse_irradiance_w_m2"),
    CLOUD_COVER("weather.cloud_cover", "%", "cloud_cover_pct"),
    PRECIPITATION("weather.precipitation", "mm", "precipitation_mm"),
    /** Highest gust of the interval. */
    WIND_GUST("weather.wind_gust", "m/s", "wind_gust_m_s");

    private final String metric;
    private final String unit;
    private final String column;

    Variable(String metric, String unit, String column) {
        this.metric = metric;
        this.unit = unit;
        this.column = column;
    }

    public String metric() {
        return metric;
    }

    /** UCUM unit of the converted values. */
    public String unit() {
        return unit;
    }

    /** Column name in the CSV export. */
    public String column() {
        return column;
    }
}
