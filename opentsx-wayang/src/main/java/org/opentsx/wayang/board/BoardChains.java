package org.opentsx.wayang.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.kafka.TopicLayout;

import java.util.List;

/** The three chains of the B4.2 matrix (D-T15) as one DAG text each, the same text for Track A and Track B. */
public final class BoardChains {

    public static final List<String> CHAINS = List.of("passthrough", "clean", "stats");
    public static final String SINK = "board";
    private static final ObjectMapper JSON = new ObjectMapper();

    private BoardChains() {
    }

    /** The output topic of a chain. */
    public static String output(String chain, TopicLayout layout) {
        return switch (chain) {
            case "passthrough" -> layout.synthetic();
            case "clean" -> layout.clean();
            case "stats" -> layout.seriesStats();
            default -> throw new IllegalArgumentException("unknown chain " + chain + "; expected one of " + CHAINS);
        };
    }

    /** The DAG of {@code chain} over the source inventory {@code seriesIds}. */
    public static String dag(String chain, TopicLayout layout, List<String> seriesIds) {
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "board-" + chain);
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker");
        source.putArray("topics").add(layout.episodes());
        ArrayNode series = source.putArray("series");
        seriesIds.forEach(series::add);
        ArrayNode nodes = dag.putArray("nodes");
        nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        ObjectNode sink = dag.putArray("sinks").addObject().put("name", SINK);
        switch (chain) {
            case "passthrough" -> sink.put("from", "decode").put("schema", "opentsx.episode.v2");
            case "clean" -> {
                ObjectNode check = nodes.addObject().put("id", "check").put("op", "dq.checks");
                check.putArray("in").add("decode");
                ObjectNode p = check.putObject("params");
                p.putArray("checks").add("PLAUSIBLE_RANGE");
                p.put("min", 0).put("max", 1_000_000).put("mode", "gap");
                ObjectNode minute = nodes.addObject().put("id", "minute").put("op", "resample");
                minute.putArray("in").add("check");
                minute.putObject("params").put("every", "PT1M").put("agg", "MEAN");
                sink.put("from", "minute").put("schema", "opentsx.episode.v2");
                sink.putObject("signal").put("system", "board").put("id", "board/value").put("grade", "clean");
                sink.put("origin", "SYNTHETIC");
            }
            case "stats" -> {
                ObjectNode stats = nodes.addObject().put("id", "stats").put("op", "stats.welford-ddsketch");
                stats.putArray("in").add("decode");
                ObjectNode p = stats.putObject("params");
                p.putArray("windows").add("PT1M");
                p.put("alpha", 0.01);
                sink.put("from", "stats").put("schema", "org.opentsx.model.v2.SeriesStats");
            }
            default -> throw new IllegalArgumentException("unknown chain " + chain + "; expected one of " + CHAINS);
        }
        sink.put("topic", output(chain, layout));
        return dag.toString();
    }
}
