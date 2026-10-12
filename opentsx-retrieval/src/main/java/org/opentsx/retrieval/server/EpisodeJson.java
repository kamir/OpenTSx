package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;

import java.nio.ByteBuffer;
import java.util.Map;

/** JSON views for humans and curl; programs should prefer the Avro stream (identical objects in Java and Python). */
final class EpisodeJson {

    static final ObjectMapper JSON = new ObjectMapper();

    private EpisodeJson() {
    }

    static ObjectNode episode(Episode e) {
        ObjectNode n = JSON.createObjectNode();
        n.put("episodeId", e.getEpisodeId());
        n.put("seriesId", e.getSeries().getSeriesId());
        n.put("metric", e.getSeries().getMetric());
        ObjectNode tags = n.putObject("tags");
        e.getSeries().getTags().forEach((k, v) -> tags.put(k, v));
        if (e.getSeries().getUnit() != null) {
            n.put("unit", e.getSeries().getUnit());
        }
        n.put("tStart", e.getTStart().toString());
        n.put("tEnd", e.getTEnd().toString());
        n.put("count", e.getCount());
        n.put("timeEncoding", e.getTimeEncoding().name());
        if (e.getIntervalMicros() != null) {
            n.put("intervalMicros", e.getIntervalMicros());
        }
        ArrayNode ts = n.putArray("timestampsMicros");
        for (long t : Episodes.timestampsMicros(e)) {
            ts.add(t);
        }
        ArrayNode vs = n.putArray("values");
        for (double v : Episodes.values(e)) {
            if (Double.isNaN(v)) {
                vs.addNull();
            } else {
                vs.add(v);
            }
        }
        ByteBuffer q = e.getQuality();
        if (q != null) {
            ArrayNode qa = n.putArray("quality");
            ByteBuffer d = q.duplicate();
            while (d.hasRemaining()) {
                qa.add(d.get());
            }
        }
        ObjectNode s = n.putObject("summary");
        s.put("validCount", e.getSummary().getValidCount());
        s.put("nanCount", e.getSummary().getNanCount());
        s.put("min", e.getSummary().getMin());
        s.put("max", e.getSummary().getMax());
        s.put("mean", e.getSummary().getMean());
        s.put("m2", e.getSummary().getM2());
        ObjectNode labels = n.putObject("labels");
        e.getLabels().forEach((k, v) -> labels.put(k.toString(), v.toString()));
        if (e.getSegmentation() != null) {
            ObjectNode seg = n.putObject("segmentation");
            seg.put("strategy", e.getSegmentation().getStrategy().name());
            ObjectNode p = seg.putObject("params");
            e.getSegmentation().getParams().forEach((k, v) -> p.put(k.toString(), v.toString()));
            if (e.getSegmentation().getAnchorTs() != null) {
                seg.put("anchorTs", e.getSegmentation().getAnchorTs().toString());
            }
        }
        if (e.getProvenance().getParentEpisodeId() != null) {
            n.put("parentEpisodeId", e.getProvenance().getParentEpisodeId());
        }
        return n;
    }

    static ObjectNode match(PatternMatch m) {
        ObjectNode n = JSON.createObjectNode();
        for (Map.Entry<String, Object> f : Map.<String, Object>of("queryId", m.getQueryId(), "episodeId", m.getEpisodeId(),
                "seriesId", m.getSeriesId(), "metric", m.getMetric()).entrySet()) {
            n.put(f.getKey(), f.getValue().toString());
        }
        n.put("offset", m.getOffset());
        n.put("length", m.getLength());
        n.put("distance", m.getDistance());
        return n;
    }
}
