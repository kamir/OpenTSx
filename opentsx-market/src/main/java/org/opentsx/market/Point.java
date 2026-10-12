package org.opentsx.market;

/**
 * One value of a market or weather series: the interval {@code [tMicros, tMicros + dtMicros)} in UTC and its value
 * (a price, an average power, a temperature). Energies per interval are converted to average powers by the parsers.
 */
public record Point(long tMicros, long dtMicros, double value) implements Comparable<Point> {

    public long endMicros() {
        return tMicros + dtMicros;
    }

    @Override
    public int compareTo(Point o) {
        return Long.compare(tMicros, o.tMicros);
    }
}
