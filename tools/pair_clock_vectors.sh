#!/usr/bin/env bash
# Test vectors of the default clock of pair.xcorr and pair.es: episodes with timestamps whose sampling interval changes
# between episodes or jitters. Without `every` the clock of a pair range is the larger sampling interval of its two
# episodes, the median spacing of their sample times in epoch microseconds read as seconds. The expected values come
# from the Wayang kernel (opentsx-wayang, ops/TsxPairWindows), the reference of catalog 1; the refusals carry the
# message of the kernel.
#
# Usage, from the repository root (needs a JDK 17+ and Maven):
#   tools/pair_clock_vectors.sh [work directory]
# Writes docs/datamodel-2026/testvectors/pair-clock.v1.json.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$(mktemp -d)}
OUT="$ROOT/docs/datamodel-2026/testvectors/pair-clock.v1.json"
mkdir -p "$WORK/src/vectors" "$WORK/classes"

cat > "$WORK/src/vectors/PairClockVectors.java" <<'EOF'
package vectors;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.wayang.ops.TsxPairWindows;
import org.opentsx.wayang.ops.TsxPairXcorr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Builds the input episodes, runs the Wayang kernel over every pair of them and writes what it returns. */
public class PairClockVectors {

    static final ObjectMapper JSON = new ObjectMapper();
    /** 2026-09-21T13:46:40Z, a whole minute. */
    static final long T = 1_790_000_000_000_000L;
    static final long SECOND = 1_000_000L;
    static final double DELAY = 3;

    /** Three waves, and a peak of 4 in every 11th second: the events of pair.es. */
    static double signal(double t) {
        double peak = Math.floorMod((long) Math.floor(t), 11) == 0 ? 4 : 0;
        return StrictMath.sin(2 * Math.PI * t / 37) + 0.5 * StrictMath.sin(2 * Math.PI * t / 13.3)
                + 0.3 * StrictMath.sin(2 * Math.PI * t / 5.1) + peak;
    }

    static double[] values(long[] t, double delay) {
        double[] v = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            v[i] = signal((t[i] - T) / 1e6 - delay);
        }
        return v;
    }

    static Episode regular(String side, int k, long start, long interval, int n, double delay) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            t[i] = start + i * interval;
        }
        return Episodes.regular(SeriesKeys.of("synthetic." + side, Map.of()), start, interval, values(t, delay))
                .episodeId(side + "-" + k).build();
    }

    /** n samples, one per second from start, each up to 0.2 s off the middle of its second. */
    static Episode jittered(String side, int k, long start, int n, Random rng, double delay) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            t[i] = start + Math.round((i + 0.5 + 0.4 * rng.nextDouble() - 0.2) * SECOND);
        }
        return Episodes.irregular(SeriesKeys.of("synthetic." + side, Map.of()), t, values(t, delay))
                .tEnd(start + n * SECOND).episodeId(side + "-" + k).build();
    }

    /** The episode in the JSON form of GET /v1/episodes, as far as the pair operators read it. */
    static ObjectNode json(Episode e) {
        ObjectNode n = JSON.createObjectNode();
        n.put("episodeId", e.getEpisodeId());
        n.put("metric", e.getSeries().getMetric());
        n.put("tStart", e.getTStart().toString());
        n.put("tEnd", e.getTEnd().toString());
        if (e.getIntervalMicros() != null) {
            n.put("intervalMicros", e.getIntervalMicros());
        }
        ArrayNode t = n.putArray("timestampsMicros");
        for (long x : Episodes.timestampsMicros(e)) {
            t.add(x);
        }
        ArrayNode v = n.putArray("values");
        for (double x : Episodes.values(e)) {
            v.add(x);
        }
        return n;
    }

    static TsxPairWindows.Piece piece(Episode e) {
        long[] t = Episodes.timestampsMicros(e);
        double[] x = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            x[i] = t[i] / 1e6;
        }
        return new TsxPairWindows.Piece(e.getSeries().getSeriesId(), e.getEpisodeId(), Micros.of(e.getTStart()) / 1e6,
                Micros.of(e.getTEnd()) / 1e6, x, Episodes.values(e));
    }

    static ObjectNode fields(TsxPairWindows.Method method, TsxPairWindows.Result r) {
        ObjectNode n = JSON.createObjectNode();
        for (int f = 0; f < method.fields().size(); f++) {
            double v = r.values()[f][0];
            if (Double.isFinite(v)) {
                n.put(method.fields().get(f), v);
            } else {
                n.putNull(method.fields().get(f));
            }
        }
        n.put("quality", r.quality()[0]);
        return n;
    }

    static ObjectNode pairCase(String name, Map<String, Object> params, List<Episode> a, List<Episode> b) {
        TsxPairXcorr.PairSettings st = TsxPairXcorr.pairSettings(params);
        TsxPairWindows.Method xcorr = TsxPairWindows.xcorr(params);
        TsxPairWindows.Method es = TsxPairWindows.es(params);
        ObjectNode c = JSON.createObjectNode();
        c.put("name", name);
        c.set("params", JSON.valueToTree(params));
        ArrayNode inA = c.putArray("a");
        a.forEach(e -> inA.add(json(e)));
        ArrayNode inB = c.putArray("b");
        b.forEach(e -> inB.add(json(e)));
        ArrayNode ranges = c.putArray("ranges");
        for (Episode ea : a) {
            for (Episode eb : b) {
                TsxPairWindows.Range range = TsxPairWindows.pair(piece(ea), piece(eb), st.pairing(), st.window());
                if (range == null) {
                    continue;
                }
                TsxPairWindows.Result rx = TsxPairWindows.run(xcorr, st, range);
                TsxPairWindows.Result re = TsxPairWindows.run(es, st, range);
                ObjectNode n = ranges.addObject();
                n.put("a", ea.getEpisodeId());
                n.put("b", eb.getEpisodeId());
                n.put("every", rx.every());
                n.put("centreMicros", Math.round(rx.centres()[0] * 1e6));
                n.set("pair.xcorr", fields(xcorr, rx));
                n.set("pair.es", fields(es, re));
            }
        }
        return c;
    }

    static ObjectNode refusal(String name, Map<String, Object> params) {
        try {
            TsxPairXcorr.pairSettings(params);
        } catch (IllegalArgumentException e) {
            ObjectNode n = JSON.createObjectNode();
            n.put("name", name);
            n.set("params", JSON.valueToTree(params));
            n.put("error", e.getMessage());
            return n;
        }
        throw new IllegalStateException(name + ": the kernel accepts " + params);
    }

    static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    static List<Episode> rateChange(String side, int fast, int slow, double delay) {
        long split = T + fast * SECOND;
        return List.of(regular(side, 0, T, SECOND, fast, delay), regular(side, 1, split, 10 * SECOND, slow, delay));
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> lags = params("lags", 5);
        Map<String, Object> intersect = params("lags", 5, "pairing", "intersect");
        Random rng = new Random(15);
        List<Episode> jitterA = new ArrayList<>();
        List<Episode> jitterB = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            jitterA.add(jittered("a", k, T + 60 * k * SECOND, 60, rng, 0));
        }
        for (int k = 0; k < 3; k++) {
            jitterB.add(jittered("b", k, T + 60 * k * SECOND, 60, rng, DELAY));
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("schema", "opentsx.testvectors.pair-clock.v1");
        root.put("description", "Default clock of pair.xcorr and pair.es: without every, the clock of a pair range is "
                + "the larger sampling interval of its two episodes, the median spacing of their sample times in epoch "
                + "microseconds read as seconds. A is three waves with a peak in every 11th second, the events of pair.es; "
                + "B is A delayed by 3 s. Episodes are in the JSON form of GET "
                + "/v1/episodes; one item per pair range (window episode), stamped at its centre, NaN as null. "
                + "Expected values from the Wayang kernel (opentsx-wayang ops/TsxPairWindows); the Rust runner has no "
                + "pair.es. Refusals: parameters every runtime refuses, with the message. Generated by "
                + "tools/pair_clock_vectors.sh; run by opentsx-saas-frontend (pairs.test.ts), opentsx-wayang "
                + "(TsxPairClockVectorsTest) and rust/tsx-runner (pair_vector_vectors.rs).");
        ArrayNode cases = root.putArray("cases");
        cases.add(pairCase("sampling interval 1 s, then 10 s; 10 s holds the majority of the samples", lags,
                rateChange("a", 60, 80, 0), rateChange("b", 60, 80, DELAY)));
        cases.add(pairCase("sampling interval 1 s, then 10 s; 1 s holds the majority of the samples", lags,
                rateChange("a", 120, 30, 0), rateChange("b", 120, 30, DELAY)));
        cases.add(pairCase("samples up to 0.2 s off a 1 s grid, intersecting episodes", intersect, jitterA, jitterB));
        ArrayNode refusals = root.putArray("refusals");
        refusals.add(refusal("fill asof without the hyphen", params("fill", "asof", "maxAge", 60)));
        refusals.add(refusal("fill linear, which no runtime implements", params("fill", "linear", "maxAge", 60)));

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(
                Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(new DefaultIndenter(" ", "\n"));
        Files.writeString(Path.of(args[0]), JSON.writer(printer).writeValueAsString(root) + "\n");
    }
}
EOF

(cd "$ROOT" && mvn -q -pl opentsx-wayang -am install -DskipTests >/dev/null \
  && mvn -q -pl opentsx-wayang dependency:build-classpath -Dmdep.outputFile="$WORK/wayang.cp" >/dev/null)
CP="$ROOT/opentsx-wayang/target/classes:$(cat "$WORK/wayang.cp")"
javac -nowarn -encoding UTF-8 -d "$WORK/classes" -cp "$CP" "$WORK/src/vectors/PairClockVectors.java"
java -cp "$WORK/classes:$CP" vectors.PairClockVectors "$OUT"
echo "wrote $OUT"
