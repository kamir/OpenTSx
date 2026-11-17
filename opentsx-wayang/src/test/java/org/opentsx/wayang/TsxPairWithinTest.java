package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TsxPairWithinTest {

    @Test
    void oneInputProducesEachUnorderedPairOnceAndKeepsInputOrientationAcrossRestarts() throws Exception {
        List<String> order = List.of("z", "a", "m");
        List<Episode> episodes = order.stream().map(name -> TsxPairVectorsTest.episode(
                SeriesKeys.of(name, Map.of()), new double[]{1, 3, 2, 5})).toList();
        Set<String> ids = new LinkedHashSet<>();
        episodes.forEach(e -> ids.add(e.getSeries().getSeriesId()));
        TsxState state = new TsxState(Map.of());
        List<SpecificRecord> out = new java.util.ArrayList<>();
        for (Episode episode : List.of(episodes.get(2), episodes.get(0), episodes.get(1))) {
            TsxPair op = new TsxPair("within", "pair.xcorr", Map.of("lags", 1, "every", 1), ids, ids,
                    "0".repeat(64), state, true);
            out.addAll(JavaTsxPair.run(op, List.of(new TsxTagged(TsxPair.A, episode))));
            state = new TsxState(state.snapshot());
        }
        assertEquals(9, out.size());
        Set<String> pairs = new java.util.HashSet<>();
        for (SpecificRecord record : out) {
            Episode episode = (Episode) record;
            String a = episode.getSeries().getTags().get("a");
            String b = episode.getSeries().getTags().get("b");
            assertEquals(true, ids.stream().toList().indexOf(a) < ids.stream().toList().indexOf(b));
            pairs.add(a + ":" + b);
        }
        assertEquals(3, pairs.size());
    }
}
