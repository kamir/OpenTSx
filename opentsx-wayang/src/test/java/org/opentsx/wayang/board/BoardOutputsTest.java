package org.opentsx.wayang.board;

import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.Episode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardOutputsTest {

    static final BoardFeed FEED = new BoardFeed(4, 7);

    static List<SpecificRecord> episodes(int[] blocksPerSeries) {
        List<SpecificRecord> out = new ArrayList<>();
        for (int i = 0; i < blocksPerSeries.length; i++) {
            for (int b = 0; b < blocksPerSeries[i]; b++) out.add(FEED.episode(i, b));
        }
        return out;
    }

    @Test
    void aRunThatIsAPrefixOfEverySeriesPasses() {
        Map<String, List<String>> reference = BoardOutputs.bySeries(episodes(new int[]{6, 6, 6, 6}));
        BoardOutputs.Comparison c = BoardOutputs.compare(reference, BoardOutputs.bySeries(episodes(new int[]{4, 4, 3, 3})));
        assertEquals(4, c.series());
        assertEquals(14, c.records());
        List<SpecificRecord> shuffled = episodes(new int[]{2, 2, 2, 2});
        java.util.Collections.reverse(shuffled);
        BoardOutputs.compare(reference, BoardOutputs.bySeries(shuffled));
    }

    @Test
    void aChangedValueAnExtraRecordOrALaggingSeriesFails() {
        Map<String, List<String>> reference = BoardOutputs.bySeries(episodes(new int[]{6, 6, 6, 6}));
        List<SpecificRecord> run = episodes(new int[]{3, 3, 3, 3});
        Episode first = (Episode) run.get(0);
        Episode changed = Episode.newBuilder(first).build();
        double[] values = org.opentsx.model.Episodes.values(first);
        values[0] = Math.nextUp(values[0]);
        run.set(0, org.opentsx.model.Episodes.regular(first.getSeries(), org.opentsx.model.Episodes.timestampsMicros(first)[0],
                1_000_000L, values).episodeId(changed.getEpisodeId()).source("opentsx-board-feed").createdAt(BoardFeed.BASE_MICROS).build());
        assertTrue(assertThrows(AssertionError.class, () -> BoardOutputs.compare(reference, BoardOutputs.bySeries(run)))
                .getMessage().contains("record 0"));
        assertThrows(AssertionError.class, () -> BoardOutputs.compare(BoardOutputs.bySeries(episodes(new int[]{2, 2, 2, 2})),
                BoardOutputs.bySeries(episodes(new int[]{3, 2, 2, 2}))));
        assertTrue(assertThrows(AssertionError.class, () -> BoardOutputs.compare(reference,
                BoardOutputs.bySeries(episodes(new int[]{5, 5, 5, 2})))).getMessage().contains("fewer"));
        assertThrows(AssertionError.class, () -> BoardOutputs.compare(reference, Map.of()));
        assertThrows(AssertionError.class, () -> BoardOutputs.compare(BoardOutputs.bySeries(episodes(new int[]{6, 6, 6, 0})),
                BoardOutputs.bySeries(episodes(new int[]{2, 2, 2, 2}))));
    }

    @Test
    void theProofIsJunitXmlWithOnlyTheGivenProperties() throws Exception {
        String xml = BoardOutputs.proof("org.opentsx.wayang.BoardOutputProofTest", "conditionOutputEqualsTheReference",
                Map.of("opentsx.conditionId", "arm64-stats-s100-rust-r1", "opentsx.inputSha256", "a<&\"b"), 1.5);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element suite = doc.getDocumentElement();
        assertEquals("testsuite", suite.getTagName());
        assertEquals("org.opentsx.wayang.BoardOutputProofTest", suite.getAttribute("name"));
        for (String zero : List.of("failures", "errors", "skipped")) assertEquals("0", suite.getAttribute(zero));
        assertEquals("1", suite.getAttribute("tests"));
        NodeList properties = suite.getElementsByTagName("property");
        assertEquals(2, properties.getLength());
        assertEquals("a<&\"b", ((Element) properties.item(1)).getAttribute("value"));
        Element testcase = (Element) suite.getElementsByTagName("testcase").item(0);
        assertEquals("org.opentsx.wayang.BoardOutputProofTest", testcase.getAttribute("classname"));
    }
}
