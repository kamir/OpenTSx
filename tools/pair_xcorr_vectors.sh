#!/usr/bin/env bash
# Test vectors of pair.xcorr (EVOLUTION/TASK-015-multivariate-analysis.md, phase 1c), computed by the original
# implementation in kamir/WikiExplorer.NG (Apache License 2.0): KreuzKorrelation and CCFunction, compiled unchanged
# except for one import. Their TimeSeriesObject came from hadoop-ts-ng; it is compiled against the renamed successor
# in opentsx-core (org.opentsx.data.series.TimeSeriesObject). The chart, FFT and shuffle classes they import are
# stubbed: the vectors do not call them.
#
# Usage, from the repository root (needs git, a JDK 17+ and Maven):
#   tools/pair_xcorr_vectors.sh [work directory]
# Writes docs/datamodel-2026/testvectors/pair-xcorr.v1.json.
set -euo pipefail

COMMIT=7b56e3d1e638d760fe238dfd66d3775404335ff4
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$(mktemp -d)}
OUT="$ROOT/docs/datamodel-2026/testvectors/pair-xcorr.v1.json"
mkdir -p "$WORK/src" "$WORK/classes"

if [ ! -d "$WORK/we/.git" ]; then
  git init -q "$WORK/we"
  git -C "$WORK/we" fetch -q --depth 1 https://github.com/kamir/WikiExplorer.NG "$COMMIT"
  git -C "$WORK/we" checkout -q FETCH_HEAD
fi
for f in crosscorrelation/KreuzKorrelation.java linkstrength/CCFunction.java; do
  mkdir -p "$WORK/src/experiments/$(dirname "$f")"
  sed 's/^import org\.apache\.hadoopts\.data\.series\.TimeSeriesObject;/import org.opentsx.data.series.TimeSeriesObject;/' \
    "$WORK/we/src/main/java/experiments/$f" > "$WORK/src/experiments/$f"
done

stub() { mkdir -p "$WORK/src/$(dirname "$1")"; cat > "$WORK/src/$1"; }
stub org/apache/hadoopts/chart/simple/MultiChart.java <<'EOF'
package org.apache.hadoopts.chart.simple;
public class MultiChart {
    public MultiChart(Object owner, boolean modal) { }
    public void open(java.util.Vector<?> rows, String title, String x, String y, boolean legend) { }
}
EOF
stub org/apache/hadoopts/data/series/TimeSeriesObjectFFT.java <<'EOF'
package org.apache.hadoopts.data.series;
public class TimeSeriesObjectFFT { }
EOF
stub org/apache/hadoopts/app/thesis/FFTPhaseRandomizer.java <<'EOF'
package org.apache.hadoopts.app.thesis;
import org.opentsx.data.series.TimeSeriesObject;
public class FFTPhaseRandomizer {
    public static final int MODE_multiply_phase_with_random_value = 0;
    public static TimeSeriesObject getPhaseRandomizedRow(TimeSeriesObject r, boolean a, boolean b, int c, int mode) {
        throw new UnsupportedOperationException("not part of the vectors");
    }
}
EOF
stub org/apache/hadoopts/app/thesis/TSGenerator.java <<'EOF'
package org.apache.hadoopts.app.thesis;
import org.opentsx.data.series.TimeSeriesObject;
public class TSGenerator {
    public static TimeSeriesObject getSinusWave(double f, double t, double sr, double a) {
        throw new UnsupportedOperationException("not part of the vectors");
    }
}
EOF
stub experiments/linkstrength/CheckInfluenceOfSingelPeaks.java <<'EOF'
package experiments.linkstrength;
public class CheckInfluenceOfSingelPeaks { }
EOF
stub stdlib/StdStats.java <<'EOF'
package stdlib;
public class StdStats {
    public static double mean(double[] a) { throw new UnsupportedOperationException("not part of the vectors"); }
    public static double stddev(double[] a) { throw new UnsupportedOperationException("not part of the vectors"); }
}
EOF

stub experiments/crosscorrelation/Vectors.java <<'EOF'
package experiments.crosscorrelation;

import experiments.linkstrength.CCFunction;
import org.opentsx.data.series.TimeSeriesObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/** Runs the original methods on fixed inputs and writes what they return. */
public class Vectors {

    static final int N = 64;

    static double f(double i) {
        return Math.sin(2 * Math.PI * i / 16) + 0.3 * Math.sin(2 * Math.PI * i / 5.3) + 0.01 * i;
    }

    static TimeSeriesObject series(String label, IntToDoubleFunction g) {
        TimeSeriesObject s = new TimeSeriesObject();
        s.setLabel(label);
        for (int i = 0; i < N; i++) {
            s.addValuePair(i, g.applyAsDouble(i));
        }
        return s;
    }

    static String num(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "null";
    }

    static String values(TimeSeriesObject s) {
        List<String> out = new ArrayList<>();
        for (Object v : s.yValues) {
            out.add(num((Double) v));
        }
        return "[" + String.join(", ", out) + "]";
    }

    /** One window: CC(k) for k = -lags..lags (calcKR), its maximum as the old code finds it, the link strength. */
    static String window(double centre, TimeSeriesObject a, TimeSeriesObject b, int lags) {
        KreuzKorrelation.debug = false;
        KreuzKorrelation._defaultK = lags;
        // the shuffled baseline (calcKR__adjusted) is random and belongs to strength version C, not to pair.xcorr
        KreuzKorrelation._DO_CALC_ADJUSTED = false;
        KreuzKorrelation kr = KreuzKorrelation.calcKR(a, b, false, false);
        double strength = CCFunction.calcStrength_VERSION_A(kr);
        return "{ \"centre\": " + num(centre) + ", \"cc\": " + values(kr) + ", \"tMax\": " + num(kr.t_max)
                + ", \"ttMax\": " + num(kr.tt_max) + ", \"strength\": " + num(strength) + " }";
    }

    static String whole(String name, TimeSeriesObject a, TimeSeriesObject b, int lags) {
        return "    { \"name\": \"" + name + "\", \"lags\": " + lags + ", \"window\": null, \"step\": null,\n"
                + "      \"a\": " + values(a) + ",\n      \"b\": " + values(b) + ",\n"
                + "      \"windows\": [ " + window(N / 2.0, a, b, lags) + " ] }";
    }

    public static void main(String[] args) throws Exception {
        TimeSeriesObject a = series("a", Vectors::f);
        TimeSeriesObject follows3 = series("b", i -> f(i - 3));
        TimeSeriesObject inverted = series("b", i -> -f(i) + 0.2 * Math.cos(i / 2.0));
        TimeSeriesObject other = series("b", i -> Math.cos(2 * Math.PI * i / 11) + 0.1 * Math.sin(i));
        TimeSeriesObject follows2 = series("b", i -> f(i - 2));

        List<String> cases = new ArrayList<>();
        cases.add(whole("B follows A by 3 samples", a, follows3, 5));
        cases.add(whole("B is A inverted, plus a slower wave", a, inverted, 5));
        cases.add(whole("unrelated waves", a, other, 5));

        // sliding windows of 32 samples, step 8, each with its own CC function over lags -4..4
        List<String> windows = new ArrayList<>();
        for (int from = 0; from + 32 <= N; from += 8) {
            windows.add(window(from + 16.0, a.cutOut(from, from + 32), follows2.cutOut(from, from + 32), 4));
        }
        cases.add("    { \"name\": \"B follows A by 2 samples, sliding windows with lags\", \"lags\": 4, \"window\": 32, \"step\": 8,\n"
                + "      \"a\": " + values(a) + ",\n      \"b\": " + values(follows2) + ",\n"
                + "      \"windows\": [\n        " + String.join(",\n        ", windows) + " ] }");

        // calcKR_SLIDINGWINDOW itself: CC(0) per window of 16 samples, step 1, stamped at the window centre
        TimeSeriesObject sliding = KreuzKorrelation.calcKR_SLIDINGWINDOW(a, follows3, 16);
        List<String> points = new ArrayList<>();
        for (int i = 0; i < sliding.xValues.size(); i++) {
            points.add("{ \"centre\": " + num((Double) sliding.xValues.get(i)) + ", \"cc\": " + num((Double) sliding.yValues.get(i)) + " }");
        }
        cases.add("    { \"name\": \"calcKR_SLIDINGWINDOW, window 16, B follows A by 3 samples\", \"lags\": 0, \"window\": 16, \"step\": 1,\n"
                + "      \"a\": " + values(a) + ",\n      \"b\": " + values(follows3) + ",\n"
                + "      \"sliding\": [\n        " + String.join(",\n        ", points) + " ] }");

        String json = "{\n"
                + "  \"schema\": \"opentsx.testvectors.pair-xcorr.v1\",\n"
                + "  \"description\": \"Cross-correlation of pair.xcorr from the original implementation: KreuzKorrelation.calcKR (CC(k) for k = -lags..lags), its maximum t_max at tt_max, CCFunction.calcStrength_VERSION_A, and calcKR_SLIDINGWINDOW. Samples are 1 s apart from x = 0. Generated by tools/pair_xcorr_vectors.sh; run by opentsx-saas-frontend (xcorr.test.ts).\",\n"
                + "  \"source\": \"kamir/WikiExplorer.NG@" + args[0] + ": src/main/java/experiments/crosscorrelation/KreuzKorrelation.java, src/main/java/experiments/linkstrength/CCFunction.java\",\n"
                + "  \"cases\": [\n" + String.join(",\n", cases) + "\n  ]\n}\n";
        Files.writeString(Path.of(args[1]), json, StandardCharsets.UTF_8);
    }
}
EOF

(cd "$ROOT" && mvn -q -pl opentsx-core -am install -DskipTests >/dev/null \
  && mvn -q -pl opentsx-core dependency:build-classpath -Dmdep.outputFile="$WORK/core.cp" >/dev/null)
CP="$ROOT/opentsx-core/target/classes:$(cat "$WORK/core.cp")"
javac -nowarn -encoding UTF-8 -d "$WORK/classes" -cp "$CP" $(find "$WORK/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
java -cp "$WORK/classes:$CP" experiments.crosscorrelation.Vectors "$COMMIT" "$OUT"
echo "wrote $OUT"
