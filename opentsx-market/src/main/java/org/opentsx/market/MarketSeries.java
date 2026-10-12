package org.opentsx.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/** A collected series: its definition and its points, sorted by start, without duplicates. */
public final class MarketSeries {

    private final SeriesDef def;
    private final List<Point> points;

    public MarketSeries(SeriesDef def, List<Point> points) {
        this.def = def;
        TreeMap<Long, Point> byStart = new TreeMap<>();
        for (Point p : points) {
            byStart.put(p.tMicros(), p);   // a later point for the same start replaces an earlier one
        }
        this.points = Collections.unmodifiableList(new ArrayList<>(byStart.values()));
    }

    public SeriesDef def() {
        return def;
    }

    public List<Point> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** Points with start in {@code [fromMicros, toMicros)}. */
    public MarketSeries slice(long fromMicros, long toMicros) {
        List<Point> out = new ArrayList<>();
        for (Point p : points) {
            if (p.tMicros() >= fromMicros && p.tMicros() < toMicros) {
                out.add(p);
            }
        }
        return new MarketSeries(def, out);
    }

    /** This series with the points of {@code other} added (other wins on equal start). */
    public MarketSeries merge(List<Point> other) {
        List<Point> all = new ArrayList<>(points);
        all.addAll(other);
        return new MarketSeries(def, all);
    }
}
