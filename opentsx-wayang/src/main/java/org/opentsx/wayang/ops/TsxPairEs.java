package org.opentsx.wayang.ops;

import java.util.Arrays;
import java.util.Objects;

public final class TsxPairEs {

    public record Sync(double qTotal, double qAb, double qBa) { }

    private TsxPairEs() { }

    public static double[] detectEvents(double[] values, double sigma) {
        Objects.requireNonNull(values, "values");
        if (!Double.isFinite(sigma)) throw new IllegalArgumentException("sigma \"" + sigma + "\" is not a number.");
        int n = 0;
        double sum = 0;
        for (double value : values) {
            if (Double.isFinite(value)) {
                n++;
                sum += value;
            }
        }
        if (n == 0) return new double[0];
        double mean = sum / n;
        double ss = 0;
        for (double value : values) {
            if (Double.isFinite(value)) ss += (value - mean) * (value - mean);
        }
        double threshold = mean + sigma * Math.sqrt(ss / n);
        double[] events = new double[values.length];
        int count = 0;
        for (int i = 1; i < values.length - 1; i++) {
            if (values[i] > threshold && values[i] > values[i - 1] && values[i] > values[i + 1]) events[count++] = i;
        }
        return Arrays.copyOf(events, count);
    }

    public static Sync eventSync(double[] eventsA, double[] eventsB, Double tauMax) {
        if (tauMax != null && !(tauMax >= 0)) throw new IllegalArgumentException("tauMax must be nonnegative");
        double[] x = sortedUnique(eventsA);
        double[] y = sortedUnique(eventsB);
        if (x.length == 0 || y.length == 0) return new Sync(0, 0, 0);
        double[] hx = halfMinGap(x);
        double[] hy = halfMinGap(y);
        double cyx = 0;
        double cxy = 0;
        int next = 0;
        for (int i = 0; i < x.length; i++) {
            while (next < y.length && y[next] < x[i]) next++;
            for (int j = next - 1; j <= next; j++) {
                if (j < 0 || j >= y.length) continue;
                double tau = Math.min(hx[i], hy[j]);
                if (tauMax != null) tau = Math.min(tau, tauMax);
                double d = y[j] - x[i];
                if (d == 0) {
                    cyx += 0.5;
                    cxy += 0.5;
                } else if (d > 0 && d < tau) {
                    cyx++;
                } else if (-d > 0 && -d < tau) {
                    cxy++;
                }
            }
        }
        double norm = Math.sqrt((double) x.length * y.length);
        return new Sync((cyx + cxy) / norm, cyx / norm, cxy / norm);
    }

    public static double[] isiShuffle(double[] events, Mulberry32 rand) {
        validateEvents(events);
        Objects.requireNonNull(rand, "rand");
        if (events.length < 3) return events.clone();
        double[] gaps = new double[events.length - 1];
        for (int i = 0; i < gaps.length; i++) gaps[i] = events[i + 1] - events[i];
        for (int i = gaps.length - 1; i > 0; i--) {
            int j = (int) (rand.nextDouble() * (i + 1));
            double gap = gaps[i];
            gaps[i] = gaps[j];
            gaps[j] = gap;
        }
        double[] out = new double[events.length];
        out[0] = events[0];
        double acc = 0;
        for (int i = 0; i < gaps.length; i++) {
            acc += gaps[i];
            out[i + 1] = events[0] + acc;
        }
        return out;
    }

    public static double surrogateSync(double[] eventsA, double[] eventsB, int shuffles, long seed, Double tauMax) {
        if (shuffles < 1) throw new IllegalArgumentException("shuffles must be at least 1");
        double[] y = sortedUnique(eventsB);
        Mulberry32 rand = new Mulberry32(seed);
        double total = 0;
        for (int i = 0; i < shuffles; i++) total += eventSync(eventsA, isiShuffle(y, rand), tauMax).qTotal();
        return total / shuffles;
    }

    private static void validateEvents(double[] events) {
        Objects.requireNonNull(events, "events");
        for (double event : events) {
            if (!Double.isFinite(event)) throw new IllegalArgumentException("event times must be finite");
        }
    }

    private static double[] sortedUnique(double[] events) {
        validateEvents(events);
        double[] out = events.clone();
        Arrays.sort(out);
        int n = 0;
        for (double event : out) {
            if (n == 0 || event != out[n - 1]) out[n++] = event;
        }
        return Arrays.copyOf(out, n);
    }

    private static double[] halfMinGap(double[] events) {
        double[] half = new double[events.length];
        for (int i = 0; i < events.length; i++) {
            double left = i > 0 ? events[i] - events[i - 1] : Double.POSITIVE_INFINITY;
            double right = i < events.length - 1 ? events[i + 1] - events[i] : Double.POSITIVE_INFINITY;
            half[i] = 0.5 * Math.min(left, right);
        }
        return half;
    }
}
