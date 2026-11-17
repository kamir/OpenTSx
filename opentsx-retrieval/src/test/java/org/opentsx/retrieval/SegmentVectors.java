package org.opentsx.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.segment.Segmentations;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.retrieval.segment.StreamingSegmenter;
import org.opentsx.store.EpisodeOps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SegmentVectors {

    private static final SeriesKey KEY = SeriesKeys.of("synthetic.signal", Map.of("site", "test"), "1");

    private SegmentVectors() {
    }

    static Segmenter segmenter(JsonNode c) {
        JsonNode p = c.path("params");
        Duration pre = Duration.parse(p.path("pre").textValue());
        Duration post = Duration.parse(p.path("post").textValue());
        if (c.path("op").textValue().equals("segment.threshold")) {
            return Segmenter.threshold(Segmenter.Direction.valueOf(p.path("direction").textValue()),
                    p.path("enter").doubleValue(), p.path("exit").doubleValue(), pre, post,
                    Duration.parse(p.path("minDuration").textValue()), Duration.parse(p.path("maxDuration").textValue()));
        }
        return Segmenter.extremum(Segmenter.Feature.valueOf(p.path("feature").textValue()),
                p.path("prominence").doubleValue(), Duration.parse(p.path("window").textValue()),
                Duration.parse(p.path("distance").textValue()), pre, post);
    }

    static List<Episode> episodes(JsonNode c, int chunk) {
        double[] values = OperatorVectors.numbers(c.path("values"));
        long[] ts = new long[values.length];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = c.path("timestampsMicros").get(i).longValue();
        }
        List<Episode> out = new ArrayList<>();
        for (int from = 0; from < values.length; from += chunk) {
            int to = Math.min(values.length, from + chunk);
            out.add(Episodes.irregular(KEY, Arrays.copyOfRange(ts, from, to), Arrays.copyOfRange(values, from, to))
                    .episodeId(Ulid.deterministic(Math.floorDiv(ts[from], 1000), from))
                    .createdAt(ts[to - 1] + 1).source("synthetic-vectors").build());
        }
        return out;
    }

    static ArrayNode batch(JsonNode c) {
        return records(Segmentations.segment(episodes(c, Math.max(1, c.path("values").size())), segmenter(c)));
    }

    static void verify(JsonNode c) {
        String expected = c.path("expect").toString();
        assertEquals(expected, batch(c).toString(), "batch");
        for (int chunk : new int[]{1, 7, Math.max(1, c.path("values").size())}) {
            StreamingSegmenter stream = new StreamingSegmenter(segmenter(c));
            List<Episode> output = new ArrayList<>();
            for (Episode episode : episodes(c, chunk)) {
                output.addAll(stream.onEpisode(episode));
            }
            output.addAll(stream.flush());
            output.sort(EpisodeOps.READ_ORDER);
            assertEquals(expected, records(output).toString(), "stream chunk " + chunk);
        }
    }

    static ArrayNode records(List<Episode> episodes) {
        ArrayNode out = OperatorVectors.JSON.createArrayNode();
        for (Episode episode : episodes) {
            Episodes.validate(episode);
            ObjectNode e = out.addObject();
            e.put("episodeId", episode.getEpisodeId());
            e.put("seriesId", episode.getSeries().getSeriesId());
            e.put("tStartMicros", Micros.of(episode.getTStart()));
            e.put("tEndMicros", Micros.of(episode.getTEnd()));
            e.put("timeEncoding", episode.getTimeEncoding().name());
            e.put("count", episode.getCount());
            e.put("anchorMicros", Micros.of(episode.getSegmentation().getAnchorTs()));
            e.put("strategy", episode.getSegmentation().getStrategy().name());
            e.set("params", OperatorVectors.JSON.valueToTree(episode.getSegmentation().getParams()));
            ArrayNode timestamps = e.putArray("timestampsMicros");
            for (long t : Episodes.timestampsMicros(episode)) {
                timestamps.add(t);
            }
            e.set("values", OperatorVectors.values(Episodes.values(episode)));
        }
        return out;
    }

    static ObjectNode input(ArrayNode cases, String name, String op, double[] values) {
        ObjectNode c = cases.addObject();
        c.put("name", name);
        c.put("op", op);
        c.set("values", OperatorVectors.values(values));
        ArrayNode timestamps = c.putArray("timestampsMicros");
        for (int i = 0; i < values.length; i++) {
            timestamps.add(i * 1_000_000L);
        }
        return c;
    }

    static void finish(ArrayNode cases) {
        for (JsonNode c : cases) {
            ((ObjectNode) c).set("expect", batch(c));
        }
    }
}
