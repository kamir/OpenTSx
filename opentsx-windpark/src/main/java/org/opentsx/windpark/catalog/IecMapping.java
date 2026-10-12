package org.opentsx.windpark.catalog;

/**
 * Position of a data point in the IEC 61400-25-2 information model.
 *
 * @param logicalDevice usually the turbine; {@code <turbine>} in the catalog is replaced by the turbine id
 * @param cdc           common data class, e.g. MV (measured value), INS (integer status)
 */
public record IecMapping(String logicalDevice, String logicalNode, String dataObject, String dataAttribute, String cdc) {

    /** e.g. {@code T07/WROT.RotSpd.mag.f} */
    public String reference(String turbineId) {
        String ld = logicalDevice == null ? turbineId : logicalDevice.replace("<turbine>", turbineId);
        return ld + "/" + logicalNode + "." + dataObject + (dataAttribute == null ? "" : "." + dataAttribute);
    }
}
