package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.wayang.board.BoardChains;
import org.opentsx.wayang.board.BoardFeed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The reference output of a B4.2 chain: the chain's DAG run once over blocks {@code [0, blocks)} of the feed, without a
 * broker and without increments. Series are independent, so the reference runs in batches of series.
 */
public final class BoardReference {

    static final int BATCH = 500;

    private BoardReference() {
    }

    public static List<SpecificRecord> output(String chain, TopicLayout layout, BoardFeed feed, long blocks, Path catalog)
            throws Exception {
        List<String> ids = feed.seriesIds();
        DagToWayang compiled = DagToWayang.compile(BoardChains.dag(chain, layout, ids), layout, catalog, TsxCompileContext.local());
        List<SpecificRecord> output = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<SpecificRecord> input = new ArrayList<>();
            for (long block = 0; block < blocks; block++) {
                for (int i = from; i < Math.min(ids.size(), from + BATCH); i++) input.add(feed.episode(i, block));
            }
            CollectionSource<SpecificRecord> source = new CollectionSource<>(input, SpecificRecord.class);
            Map<String, Operator> outputs = compiled.outputsBySink(Map.of(layout.episodes(), source), new TsxState(Map.of()));
            if (outputs.size() != 1) throw new IllegalStateException("a board chain has exactly one sink");
            LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(output, SpecificRecord.class);
            outputs.values().iterator().next().connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        }
        return output;
    }
}
