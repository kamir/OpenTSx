package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TsxCleanStatsParity {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<SeriesKey> KEYS = List.of(TsxCleanVectorsTest.RAW,
            SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p1", "turbine", "T02"), "m/s"));

    static TsxState restore(TsxState state) throws Exception {
        JsonNode json = JSON.readTree(JSON.writeValueAsBytes(state.snapshot()));
        Map<String, JsonNode> saved = new TreeMap<>();
        json.fields().forEachRemaining(e -> saved.put(e.getKey(), e.getValue()));
        return new TsxState(saved);
    }

    static JsonNode cleanCase(JsonNode c, int chunk, boolean reverse) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        c.path("nodes").forEach(nodes::add);
        List<SeriesKey> order = reverse ? List.of(KEYS.get(1), KEYS.get(0)) : KEYS;
        TsxState state = new TsxState(Map.of());
        List<Episode> output = new ArrayList<>();
        long start = Instant.parse(c.path("t0").textValue()).toEpochMilli() * 1000;
        long every = c.path("intervalSeconds").longValue() * 1_000_000;
        int count = c.path("values").size();
        for (int from = 0; from < count; from += chunk) {
            int to = Math.min(count, from + chunk);
            List<SpecificRecord> input = new ArrayList<>();
            for (SeriesKey key : order) {
                double[] values = new double[to - from];
                for (int i = from; i < to; i++) values[i - from] = c.path("values").get(i).isNull()
                        ? Double.NaN : c.path("values").get(i).doubleValue();
                input.add(Episodes.regular(key, start + from * every, every, values)
                        .episodeId("clean-" + key.getSeriesId() + "-" + from).createdAt(0).build());
            }
            TsxSeriesChain operator = new TsxSeriesChain(nodes.get(nodes.size() - 1).path("id").textValue(),
                    nodes, TsxCleanVectorsTest.TARGET, List.of(), state);
            CollectionSource<SpecificRecord> source = new CollectionSource<>(input, SpecificRecord.class);
            List<SpecificRecord> records = new ArrayList<>();
            LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(records, SpecificRecord.class);
            source.connectTo(0, operator, 0);
            operator.connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
            records.forEach(r -> output.add((Episode) r));
            state = restore(state);
        }
        for (SeriesKey key : KEYS) {
            List<Episode> series = output.stream().filter(e -> e.getSeries().getTags().get("turbine")
                    .equals(key.getTags().get("turbine"))).sorted(Comparator.comparing(Episode::getTStart)).toList();
            TsxCleanVectorsTest.assertCase(c, series);
        }
        List<ObjectNode> samples = new ArrayList<>();
        for (Episode episode : output) {
            long[] times = Episodes.timestampsMicros(episode);
            double[] values = Episodes.values(episode);
            for (int i = 0; i < times.length; i++) {
                ObjectNode sample = JSON.createObjectNode();
                sample.put("seriesId", episode.getSeries().getSeriesId());
                sample.put("metric", episode.getSeries().getMetric());
                sample.set("tags", JSON.valueToTree(new TreeMap<>(episode.getSeries().getTags())));
                if (episode.getSeries().getUnit() == null) sample.putNull("unit");
                else sample.put("unit", episode.getSeries().getUnit());
                sample.put("timestampMicros", times[i]);
                sample.put("timeEncoding", episode.getTimeEncoding().name());
                sample.put("intervalMicros", episode.getIntervalMicros());
                sample.put("revision", episode.getRevision());
                if (Double.isFinite(values[i])) sample.put("value", values[i]);
                else sample.put("value", Double.toString(values[i]));
                if (episode.getQuality() == null) sample.putNull("quality");
                else sample.put("quality", episode.getQuality().get(episode.getQuality().position() + i));
                sample.set("labels", JSON.valueToTree(new TreeMap<>(episode.getLabels())));
                sample.put("source", episode.getProvenance().getSource());
                sample.put("pipeline", episode.getProvenance().getPipeline());
                sample.put("producer", episode.getProvenance().getProducer());
                sample.put("parentEpisodeId", episode.getProvenance().getParentEpisodeId());
                samples.add(sample);
            }
        }
        samples.sort(Comparator.comparing((ObjectNode n) -> n.path("seriesId").textValue())
                .thenComparingLong(n -> n.path("timestampMicros").longValue()));
        ArrayNode result = JSON.createArrayNode();
        samples.forEach(result::add);
        return result;
    }

    static JsonNode statsCase(JsonNode c, int chunk, boolean reverse) throws Exception {
        List<String> keys = reverse ? List.of("synthetic-second", "synthetic-series")
                : List.of("synthetic-series", "synthetic-second");
        List<SeriesStats> output = new ArrayList<>();
        TsxState state = new TsxState(Map.of());
        int count = c.path("samples").size();
        for (int from = 0; from < count; from += chunk) {
            List<SpecificRecord> input = new ArrayList<>();
            for (String key : keys) {
                for (int i = from; i < Math.min(count, from + chunk); i++) {
                    JsonNode sample = c.path("samples").get(i);
                    JsonNode value = sample.path("value");
                    input.add(new Observation(key, Micros.toInstant(sample.path("tsMicros").longValue()),
                            value.isTextual() ? Double.parseDouble(value.textValue()) : value.doubleValue(), null));
                }
            }
            output.addAll(TsxStatsVectorsTest.plan(new TsxStatsWindow("stats", TsxStatsVectorsTest.config(c), state), input));
            state = restore(state);
        }
        if (count == 0 && c.path("mode").textValue().equals("accumulator")) {
            for (String key : keys) output.addAll(TsxStatsVectorsTest.plan(TsxStatsWindow.window("stats",
                    TsxStatsVectorsTest.config(c), new TsxState(Map.of()), key, 0), List.of()));
        } else {
            output.addAll(TsxStatsVectorsTest.plan(TsxStatsWindow.bounded("stats", TsxStatsVectorsTest.config(c), state), List.of()));
        }
        for (String key : keys) {
            ArrayNode expected = c.path("expect").deepCopy();
            expected.forEach(n -> ((ObjectNode) n).put("seriesId", key));
            List<SeriesStats> series = output.stream().filter(s -> s.getSeriesId().equals(key)).toList();
            if (count > 0 && c.path("mode").textValue().equals("accumulator")) {
                List<SeriesStats> global = series.stream().filter(s -> s.getWindowType().equals("GLOBAL")).toList();
                assertEquals(1, global.size());
                ObjectNode globalExpected = expected.get(0).deepCopy();
                globalExpected.put("windowType", "GLOBAL");
                globalExpected.put("windowStartMicros", c.path("samples").get(0).path("tsMicros").longValue());
                globalExpected.put("windowEndMicros", c.path("samples").get(count - 1).path("tsMicros").longValue() + 1);
                assertEquals(globalExpected.toString(), TsxStatsVectorsTest.records(global).get(0).toString());
                series = series.stream().filter(s -> !s.getWindowType().equals("GLOBAL")).toList();
            }
            JsonNode actual = TsxStatsVectorsTest.records(series);
            assertEquals(sorted(expected).toString(), sorted(actual).toString(), c.path("name").textValue() + ": " + key);
        }
        return sorted(TsxStatsVectorsTest.records(output));
    }

    private static ArrayNode sorted(JsonNode input) {
        List<JsonNode> records = new ArrayList<>();
        input.forEach(records::add);
        records.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode result = JSON.createArrayNode();
        records.forEach(result::add);
        return result;
    }
}
