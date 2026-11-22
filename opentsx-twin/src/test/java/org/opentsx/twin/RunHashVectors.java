package org.opentsx.twin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.util.List;
import java.util.Map;

/**
 * The runs whose ids and hashes {@code twin/run-hashes.v1.json} pins: the reference (shadow) run of every Studio
 * scenario, a what-if and a forecast of the H2 chain as the Studio export runs them, and a variant L run of the H2
 * chain with transport jitter. {@code main} prints the vector file:
 * {@code java -cp <test classpath> org.opentsx.twin.RunHashVectors > src/test/resources/twin/run-hashes.v1.json}.
 */
public final class RunHashVectors {

    static final String WHAT_IF = "reopen-120-pressure-aware";
    static final String FORECAST = "now-90-aware";

    private RunHashVectors() {
    }

    static ObjectNode compute() {
        ObjectMapper json = new ObjectMapper();
        ObjectNode doc = json.createObjectNode();
        doc.put("description", "Run ids and run hashes of opentsx-twin runs; equal on every conforming JVM "
                + "(TWIN-COUPLING-SPEC section 8). Regenerate with RunHashVectors.main.");
        ArrayNode runs = doc.putArray("runs");
        for (StudioScenario s : Scenarios.all()) {
            TwinRunner runner = new TwinRunner(s.plan());
            RunResult ref = runner.run(spec(Mode.SHADOW, s.observed(), s.reference(), null, 0, Map.of()));
            add(runs, s, "reference", ref);
            if (!s.id().equals("h2-chain")) {
                continue;
            }
            StudioScenario.Alternative a = s.alternatives().stream().filter(x -> x.id().equals(WHAT_IF)).findFirst()
                    .orElseThrow();
            add(runs, s, "alternative " + WHAT_IF,
                    runner.run(spec(Mode.WHAT_IF, s.observed(), a.interventions(), null, 0, a.policies())));
            StudioScenario.Forecast f = s.forecasts().stream().filter(x -> x.id().equals(FORECAST)).findFirst()
                    .orElseThrow();
            add(runs, s, "forecast " + FORECAST, runner.run(spec(Mode.FORECAST,
                    ((SeriesInputs) s.observed()).as(Origin.FORECAST), List.of(),
                    ref.snapshotAfter(f.nowStep() - 1), ref.size() - f.nowStep(), f.policies())));
            add(runs, s, "variant L async, jitter 3, seed 7", new LooseRunner(runner)
                    .run(spec(Mode.SHADOW, s.observed(), s.reference(), null, 0, Map.of()),
                            LooseRunner.Coupling.async().withJitter(3, 7)).result());
        }
        return doc;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(compute()));
    }

    private static TwinRunner.RunSpec spec(Mode mode, Inputs in, List<Intervention> log, Snapshot from, int steps,
                                           Map<String, String> policies) {
        return new TwinRunner.RunSpec(mode, in, log, from, steps, policies, null);
    }

    private static void add(ArrayNode runs, StudioScenario s, String run, RunResult r) {
        runs.addObject().put("scenario", s.id()).put("run", run).put("mode", r.mode().name()).put("runId", r.runId())
                .put("hash", r.hash());
    }
}
