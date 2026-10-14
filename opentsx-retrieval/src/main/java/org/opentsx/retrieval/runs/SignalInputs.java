package org.opentsx.retrieval.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The inputs of a run taken from the series of the signals of a system instead of the inputs of its scenario. For
 * every input of the plan that comes from outside and has a signal, the clean grade counts when it has data, else the
 * raw grade; a constant of the system document is bound as a constant. The values are put on the grid of the plan:
 * a step takes the newest value at or before its start, held for at most the interval of its series. What is missing
 * stays missing: the atom then uses its default, and the input says how much it covered.
 */
public final class SignalInputs {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** The inputs and, for each port, where its values came from. */
    public record Bound(SeriesInputs inputs, List<ObjectNode> described) {
    }

    private SignalInputs() {
    }

    /**
     * @param signals the state of the signals ({@code opentsx.signal-status.v1}), with the working copy if there is one
     * @param plan    the plan of the run: start, step, number of steps, and the inputs from outside
     * @param read    the episodes of the given series between two instants (microseconds)
     */
    public static Bound bind(JsonNode signals, TwinPlan plan, Map<String, Boolean> outside, ReadEpisodes read) {
        long t0 = plan.startMicros();
        long step = plan.baseStepMicros();
        int n = plan.steps();
        SeriesInputs in = new SeriesInputs();
        List<ObjectNode> described = new ArrayList<>();
        for (JsonNode s : signals.path("signals")) {
            String port = s.path("id").asText();
            if (!outside.containsKey(port)) {
                continue;
            }
            ObjectNode d = JSON.objectNode();
            d.put("port", port);
            d.put("signal", port);
            JsonNode raw = s.path("grades").path("raw");
            JsonNode clean = s.path("grades").path("clean");
            if ("manual".equals(raw.path("source").path("kind").asText()) && raw.path("source").has("value")) {
                Origin o = origin(raw.path("source").path("origin").asText(s.path("origin").asText("REPORTED")));
                in.constant(port, raw.path("source").path("value").asDouble(), o);
                d.put("source", "constant of the system document").put("origin", o.name()).put("coverage", 1.0);
                described.add(d);
                continue;
            }
            String grade = clean.path("data").path("series").isArray() && !clean.path("data").path("series").isEmpty() ? "clean"
                    : raw.path("data").path("series").isArray() && !raw.path("data").path("series").isEmpty() ? "raw" : null;
            if (grade == null) {
                d.put("source", "no data: the atom uses its default").put("coverage", 0.0);
                described.add(d);
                continue;
            }
            JsonNode data = s.path("grades").path(grade).path("data");
            List<String> ids = new ArrayList<>();
            data.path("series").forEach(x -> ids.add(x.asText()));
            long hold = Math.max(step, data.path("intervalMicros").asLong(step));
            TreeMap<Long, Double> samples = new TreeMap<>();
            for (Episode e : read.read(ids, t0 - hold, t0 + n * step)) {
                long[] ts = Episodes.timestampsMicros(e);
                double[] v = Episodes.values(e);
                for (int i = 0; i < ts.length; i++) {
                    if (!Double.isNaN(v[i])) {
                        samples.put(ts[i], v[i]);
                    }
                }
            }
            double[] values = new double[n];
            int covered = 0;
            for (int k = 0; k < n; k++) {
                Map.Entry<Long, Double> at = samples.floorEntry(t0 + k * step);
                boolean fresh = at != null && t0 + k * step - at.getKey() < hold;
                values[k] = fresh ? at.getValue() : Double.NaN;
                covered += fresh ? 1 : 0;
            }
            String originName = data.path("origin").asText(s.path("origin").asText("SYNTHETIC"));
            boolean demo = "demo".equals(data.path("fidelity").asText()) || data.path("madeOf").asText("").contains("SYNTHETIC");
            Origin o = demo ? Origin.SYNTHETIC : origin(originName);
            if (covered > 0) {
                in.bind(port, t0, step, values, o);
            }
            d.put("source", "signal " + port + " (" + grade + ")").put("origin", o.name()).put("coverage", covered / (double) n);
            ids.forEach(d.putArray("series")::add);
            described.add(d);
        }
        return new Bound(in, described);
    }

    private static Origin origin(String name) {
        try {
            return Origin.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Origin.SYNTHETIC;
        }
    }

    /** Reads the episodes of series between two instants. */
    @FunctionalInterface
    public interface ReadEpisodes {
        List<Episode> read(List<String> seriesIds, long fromMicros, long toMicros);
    }

    /** Where a run on signals gets the state of the signals and the episodes from. */
    public interface Source {
        JsonNode signals(String system, JsonNode bindings);

        List<Episode> read(List<String> seriesIds, long fromMicros, long toMicros);
    }

}
