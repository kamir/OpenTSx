package org.opentsx.windpark.catalog;

/**
 * Turbine model parameters used by inventory, normalizer and simulator.
 *
 * @param gearRatio generator speed / rotor speed (1 for direct drive)
 */
public record TurbineType(String model, double ratedPowerKw, double rotorDiameterM, double hubHeightM,
                          double cutInMs, double ratedWindMs, double cutOutMs,
                          double minRotorRpm, double ratedRotorRpm, double gearRatio) {

    /** Generic 3 MW class turbine (roughly comparable to many onshore models). */
    public static final TurbineType GENERIC_3MW =
            new TurbineType("GENERIC-3MW", 3000, 112, 94, 3.0, 12.5, 25.0, 6.0, 15.5, 97.0);

    /** Generic 8 MW offshore class turbine. */
    public static final TurbineType GENERIC_8MW =
            new TurbineType("GENERIC-8MW", 8000, 164, 107, 3.5, 13.0, 25.0, 5.0, 10.5, 1.0);

    public static TurbineType byModel(String model) {
        return switch (model) {
            case "GENERIC-3MW" -> GENERIC_3MW;
            case "GENERIC-8MW" -> GENERIC_8MW;
            default -> throw new IllegalArgumentException("unknown turbine model " + model);
        };
    }
}
