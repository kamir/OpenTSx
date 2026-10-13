package org.opentsx.retrieval.generate;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Demo generators: series for a signal nobody delivers data for, made of its expectation. They give plausible
 * single values and know nothing of the rest of the system: no coupling between wind, price and demand, no balance,
 * no dynamics of a storage. They are for trying out the surface and the pipes; a comparison of strategies needs a
 * scenario model (docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md, section 5.3, decision D61).
 *
 * <p>The value at a time depends on the seed, the parameters and that time only, not on where a run began: two runs
 * over overlapping periods agree where they overlap, and a period can be generated in pieces.
 *
 * <ul>
 *   <li>{@code gen.ou}: mean-reverting noise. {@code mean}, {@code sigma} (standard deviation of one step),
 *   {@code reversion} (share of the distance to the mean taken back per step, default 0.1).</li>
 *   <li>{@code gen.sine}: a daily or other cycle with noise. {@code mean}, {@code amplitude}, {@code period}
 *   (ISO-8601, default P1D), {@code phase} (share of the period, default 0), {@code sigma} (noise, default 0).</li>
 * </ul>
 */
public final class Generators {

    /** Processors this runtime can run, by id. */
    public static final List<String> IDS = List.of("gen.ou", "gen.sine");

    /** Samples per episode; pieces are cut on this grid, so the same period always gives the same episodes. */
    public static final int CHUNK = 600;

    /** A run may not ask for more than this many samples: a demo generator is no load test. */
    public static final long MAX_SAMPLES = 2_000_000;

    private Generators() {
    }

    /** What the generated series is: its key and the labels of its episodes. */
    public record Target(String system, String signal, String metric, String unit, String processor, long seed,
                         Double min, Double max, String systemHash) {
    }

    /**
     * The values at {@code n} steps of the grid, beginning at step {@code first} ({@code time = step * interval}).
     *
     * @throws IllegalArgumentException with a message for the user when the processor or a parameter is wrong
     */
    public static double[] values(String processor, JsonNode params, long seed, long intervalMicros, long first, int n,
                                  Double min, Double max) {
        double[] out = switch (processor) {
            case "gen.ou" -> ou(params, seed, first, n);
            case "gen.sine" -> sine(params, seed, intervalMicros, first, n);
            default -> throw new IllegalArgumentException("generator " + processor + " is not available in this runtime: it runs " + IDS);
        };
        for (int i = 0; i < n; i++) {
            if (min != null && out[i] < min) {
                out[i] = min;
            }
            if (max != null && out[i] > max) {
                out[i] = max;
            }
        }
        return out;
    }

    /**
     * AR(1) around the mean, written so that the value of a step does not depend on where the run began: the
     * recursion starts far enough before the first step that what came earlier has faded below a thousandth.
     */
    private static double[] ou(JsonNode p, long seed, long first, int n) {
        double mean = required(p, "mean", "gen.ou");
        double sigma = required(p, "sigma", "gen.ou");
        double reversion = p.path("reversion").asDouble(0.1);
        if (sigma < 0 || reversion <= 0 || reversion > 1) {
            throw new IllegalArgumentException("gen.ou: sigma must not be below zero, reversion must be above 0 and at most 1");
        }
        double keep = 1 - reversion;
        int warm = keep <= 0 ? 0 : (int) Math.min(100_000, Math.ceil(Math.log(1e-3) / Math.log(keep)));
        double x = 0;
        for (long k = first - warm; k < first; k++) {
            x = keep * x + sigma * noise(seed, k);
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            x = keep * x + sigma * noise(seed, first + i);
            out[i] = mean + x;
        }
        return out;
    }

    /** Standard normal value of a step: the same for the same seed and step, whatever was asked before. */
    static double noise(long seed, long step) {
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ (step * 0xC2B2AE3D27D4EB4FL)).nextGaussian();
    }

    private static double required(JsonNode p, String name, String processor) {
        if (!p.path(name).isNumber()) {
            throw new IllegalArgumentException(processor + " needs the parameter " + name);
        }
        return p.path(name).asDouble();
    }

    private static double[] sine(JsonNode p, long seed, long intervalMicros, long first, int n) {
        double mean = required(p, "mean", "gen.sine");
        double amplitude = required(p, "amplitude", "gen.sine");
        double sigma = p.path("sigma").asDouble(0);
        double phase = p.path("phase").asDouble(0);
        long period;
        try {
            period = java.time.Duration.parse(p.path("period").asText("P1D")).toNanos() / 1000;
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("gen.sine: period must be a duration like P1D");
        }
        if (period <= 0 || sigma < 0) {
            throw new IllegalArgumentException("gen.sine: period must be above zero and sigma not below it");
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            long t = (first + i) * intervalMicros;
            double angle = 2 * Math.PI * (Math.floorMod(t, period) / (double) period + phase);
            out[i] = mean + amplitude * Math.sin(angle) + sigma * noise(seed, first + i);
        }
        return out;
    }

    /**
     * Episodes of the generated series over [from, to), cut on the grid of {@link #CHUNK} samples.
     *
     * @param intervalMicros the sampling interval of the signal
     */
    public static List<Episode> episodes(Target target, JsonNode params, long intervalMicros, long fromMicros, long toMicros) {
        if (intervalMicros <= 0) {
            throw new IllegalArgumentException("the signal needs a sampling interval to be generated (expect.interval)");
        }
        if (toMicros <= fromMicros) {
            throw new IllegalArgumentException("the period to generate ends before it begins");
        }
        long first = Math.floorDiv(fromMicros + intervalMicros - 1, intervalMicros);
        long last = Math.floorDiv(toMicros - 1, intervalMicros);
        if (last - first + 1 > MAX_SAMPLES) {
            throw new IllegalArgumentException("the period gives " + (last - first + 1) + " samples; a demo generator writes at most "
                    + MAX_SAMPLES + " in one run");
        }
        Map<String, String> tags = new TreeMap<>();
        tags.put("system", target.system());
        tags.put("signal", target.signal());
        tags.put("grade", "raw");
        SeriesKey key = SeriesKeys.of(target.metric(), tags, target.unit());
        List<Episode> out = new ArrayList<>();
        for (long chunk = Math.floorDiv(first, CHUNK); chunk * CHUNK <= last; chunk++) {
            long a = Math.max(first, chunk * CHUNK);
            long b = Math.min(last, chunk * CHUNK + CHUNK - 1);
            int n = (int) (b - a + 1);
            double[] v = values(target.processor(), params, target.seed(), intervalMicros, a, n, target.min(), target.max());
            // the same piece of the same series has the same id: generated again, it replaces itself for a reader
            String identity = key.getSeriesId() + "|" + a * intervalMicros + "|" + (b + 1) * intervalMicros;
            out.add(Episodes.regular(key, a * intervalMicros, intervalMicros, v)
                    .episodeId("g" + XxHash64.hex(XxHash64.hash(identity.getBytes(StandardCharsets.UTF_8))))
                    .source("opentsx-generator").pipeline(target.processor())
                    .label("grade", "raw").label("origin", "SYNTHETIC").label("generator", target.processor())
                    .label("seed", Long.toString(target.seed())).label("fidelity", "demo").label("systemHash", target.systemHash())
                    .build());
        }
        return out;
    }
}
