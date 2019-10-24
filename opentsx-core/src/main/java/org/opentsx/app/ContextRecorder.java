package org.opentsx.app;

/**
 *
 * @author kamir
 */
public class ContextRecorder {

    static public StringBuffer sb = new StringBuffer();

    public static void recordParameters(String label, double[] p) {
        sb.append(label + ": \n");
        int i = 0;
        for (double d : p) {
            sb.append(i + ": " + d + "\n");
        }
        sb.append("\n");

    }
    ;

    static String subL = null;

    public static void setSubLabel(String subLabel) {

        subL = subLabel;
        sb.append("Experiment: " + subL + "\n");

    }

    public static void resetSubLabel() {
        subL = "unnamed";
    }

}
