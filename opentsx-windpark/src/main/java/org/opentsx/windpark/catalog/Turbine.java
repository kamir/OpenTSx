package org.opentsx.windpark.catalog;

/** A turbine of a park; x/y are metres east/north of the park origin. */
public record Turbine(String id, TurbineType type, double xM, double yM) {
}
