package org.opentsx.wayang;

import org.opentsx.retrieval.clean.SeriesStream;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

public record TsxCompileContext(Map<String, List<Runtime>> assignments, Map<String, Boundary> federatedSinks,
                                OptionalLong xcorrProductLimit, Map<String, Long> xcorrEstimates,
                                Map<String, List<String>> seriesByNode, Map<String, SeriesStream.Target> cleanTargets,
                                String site, OptionalLong nowMicros,
                                boolean complete) {

    public record Runtime(String track, Map<String, Integer> operators, Set<String> states) {
        public Runtime {
            if (!Set.of("A", "B").contains(track)) throw new IllegalArgumentException("runtime track must be A or B");
            operators = Map.copyOf(operators);
            states = Set.copyOf(states);
        }
    }

    public record Boundary(Set<String> statisticsWindows, boolean admitsRaw) {
        public Boundary {
            statisticsWindows = Set.copyOf(statisticsWindows);
        }
    }

    public TsxCompileContext {
        Map<String, List<Runtime>> replicas = new LinkedHashMap<>();
        assignments.forEach((node, runtimes) -> {
            if (runtimes.isEmpty()) throw new IllegalArgumentException("assigned node needs at least one runtime: " + node);
            replicas.put(node, List.copyOf(runtimes));
        });
        assignments = Map.copyOf(replicas);
        federatedSinks = Map.copyOf(federatedSinks);
        Objects.requireNonNull(xcorrProductLimit);
        if (xcorrProductLimit.isPresent() && xcorrProductLimit.getAsLong() < 1) {
            throw new IllegalArgumentException("xcorr product limit must be positive");
        }
        xcorrEstimates = Map.copyOf(xcorrEstimates);
        for (Map.Entry<String, Long> estimate : xcorrEstimates.entrySet()) {
            if (estimate.getValue() < 0) throw new IllegalArgumentException("xcorr product estimate must be non-negative: " + estimate.getKey());
        }
        Map<String, List<String>> series = new LinkedHashMap<>();
        seriesByNode.forEach((node, ids) -> series.put(node, List.copyOf(ids)));
        seriesByNode = Map.copyOf(series);
        cleanTargets = Map.copyOf(cleanTargets);
        if (site == null || site.isBlank()) throw new IllegalArgumentException("compile site must not be empty");
        Objects.requireNonNull(nowMicros);
    }

    public static TsxCompileContext local() {
        return new TsxCompileContext(Map.of(), Map.of(), OptionalLong.empty(), Map.of(), Map.of(), Map.of(), "local",
                OptionalLong.empty(), false);
    }
}
