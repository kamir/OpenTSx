package org.opentsx.wayang;

import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.MapOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WayangSmokeTest {

    @Test
    void aWayangPlanRunsOnTheJavaPlatformInsideTheOpenTsxBuild() {
        List<Integer> input = IntStream.range(0, 1000).boxed().toList();
        List<Integer> output = new ArrayList<>();

        CollectionSource<Integer> source = new CollectionSource<>(input, Integer.class);
        MapOperator<Integer, Integer> square = new MapOperator<>(i -> i * i, Integer.class, Integer.class);
        LocalCallbackSink<Integer> sink = LocalCallbackSink.createCollectingSink(output, Integer.class);
        source.connectTo(0, square, 0);
        square.connectTo(0, sink, 0);

        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));

        assertEquals(IntStream.range(0, 1000).map(i -> i * i).boxed().toList(), output.stream().sorted().toList());
    }
}
