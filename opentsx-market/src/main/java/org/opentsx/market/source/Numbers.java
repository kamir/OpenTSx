package org.opentsx.market.source;

/** Number parsing for German CSV exports ({@code 1.234,56}, {@code N.A.}, empty cells). */
final class Numbers {

    private Numbers() {
    }

    /** Parses a German or plain number; NaN for empty, "N.A.", "-" and similar. */
    static double german(String s) {
        String t = s == null ? "" : s.trim().replace(" ", "");
        if (t.isEmpty() || t.equalsIgnoreCase("N.A.") || t.equalsIgnoreCase("n/a") || t.equals("-")) {
            return Double.NaN;
        }
        if (t.indexOf(',') >= 0) {
            t = t.replace(".", "").replace(',', '.');
        }
        return Double.parseDouble(t);
    }

    /** Parses a plain number (decimal point); NaN for empty and non-numeric markers. */
    static double plain(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("nan") || t.equalsIgnoreCase("null") || t.equals("-")) {
            return Double.NaN;
        }
        return Double.parseDouble(t);
    }
}
