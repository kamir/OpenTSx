package org.opentsx.wayang.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.wayang.DagToWayang;
import org.opentsx.wayang.TsxCompileContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardWorkloadTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CATALOG = Path.of("..", "systems", "processors");
    static final TopicLayout LAYOUT = new TopicLayout("board");

    @Test
    void theFeedIsDeterministicPerSeedAndCarriesOneSamplePerSecond() {
        BoardFeed feed = new BoardFeed(100, 7);
        Episode e = feed.episode(42, 3);
        assertEquals("board-42-3", e.getEpisodeId());
        long[] times = Episodes.timestampsMicros(e);
        assertEquals(BoardFeed.EPISODE_SECONDS, times.length);
        assertEquals(BoardFeed.BASE_MICROS + 30_000_000L, times[0]);
        assertEquals(1_000_000L, times[1] - times[0]);
        assertArrayEquals(Episodes.values(e), Episodes.values(new BoardFeed(100, 7).episode(42, 3)));
        assertEquals(feed.digest(LAYOUT.episodes(), 2), new BoardFeed(100, 7).digest(LAYOUT.episodes(), 2));
        assertNotEquals(feed.digest(LAYOUT.episodes(), 2), new BoardFeed(100, 8).digest(LAYOUT.episodes(), 2));
        assertEquals(200, feed.records(LAYOUT.episodes(), 2).size());
        for (double v : Episodes.values(e)) assertTrue(v > 25 && v < 75, "value " + v);
    }

    @Test
    void theSeriesAreStaggeredEvenlyOverOneBlock() {
        BoardFeed feed = new BoardFeed(10_000, 1);
        assertEquals(0, feed.phaseNanos(0));
        assertEquals(TimeUnit.SECONDS.toNanos(5), feed.phaseNanos(5_000));
        assertTrue(feed.phaseNanos(9_999) < TimeUnit.SECONDS.toNanos(BoardFeed.EPISODE_SECONDS));
        assertEquals(10_000, feed.seriesIds().stream().distinct().count());
        assertThrows(IllegalArgumentException.class, () -> new BoardFeed(16_385, 1));
    }

    @Test
    void everyChainCompilesOnWayangForTenThousandSeries() throws Exception {
        List<String> ids = new BoardFeed(10_000, 1).seriesIds();
        for (String chain : BoardChains.CHAINS) {
            String dag = BoardChains.dag(chain, LAYOUT, ids);
            DagToWayang compiled = DagToWayang.compile(dag, LAYOUT, CATALOG, TsxCompileContext.local());
            assertNotNull(compiled.incrementPlan("localhost:9092"), chain);
            assertEquals(BoardChains.output(chain, LAYOUT), JSON.readTree(dag).path("sinks").get(0).path("topic").asText());
        }
        assertThrows(IllegalArgumentException.class, () -> BoardChains.dag("vector", LAYOUT, ids));
    }

    @Test
    void aTrackBRequestCarriesTheChainDagAndTheWorkerLimits() throws Exception {
        JsonNode capabilities = JSON.readTree("""
                {"node":"board","site":"board","artifactDigest":"sha256:%s",
                 "limits":{"records":100000,"payloadBytes":1048576,"inputBytes":67108864,"samples":4000000,
                           "outputBytes":67108864,"series":16384,"stateBytes":268435456},
                 "catalogExecution":{"limits":{"series":16384,"samples":4000000}}}""".formatted("a".repeat(64)));
        List<String> ids = new BoardFeed(10_000, 1).seriesIds();
        List<BoardTrackB.Position> cut = List.of(new BoardTrackB.Position(0, 0, 10_000));
        for (String chain : BoardChains.CHAINS) {
            String dag = BoardChains.dag(chain, LAYOUT, ids);
            String first = BoardTrackB.request(chain, dag, capabilities, "proxy:9092", LAYOUT.episodes(),
                    BoardChains.output(chain, LAYOUT), 1, cut, 1, null);
            JsonNode request = JSON.readTree(first);
            assertTrue(request.path("previousRequestHash").isNull());
            assertEquals(chain.equals("stats"), request.has("context"), chain);
            assertEquals(16_384, request.path("limits").path("series").asInt());
            JsonNode assignment = JSON.readTree(request.path("assignmentText").asText());
            assertEquals(dag, assignment.path("fragmentText").asText());
            assertEquals("10000", assignment.path("inputs").get(0).path("positions").get(0).path("to").asText());
            int bytes = first.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes > 65_536 && bytes < 4_390_912, chain + " request of " + bytes + " bytes");
            String second = BoardTrackB.request(chain, dag, capabilities, "proxy:9092", LAYOUT.episodes(),
                    BoardChains.output(chain, LAYOUT), 1, cut, 2, ExecutionAssignments.sha256(first));
            assertEquals(ExecutionAssignments.sha256(first), JSON.readTree(second).path("previousRequestHash").asText());
        }
    }

    @Test
    void aCutNeverExceedsTheRecordBudget() {
        assertEquals(List.of(new BoardTrackB.Position(0, 5, 10), new BoardTrackB.Position(1, 0, 3)),
                BoardTrackB.cut(new long[]{5, 0}, new long[]{10, 9}, 8));
        assertEquals(List.of(new BoardTrackB.Position(0, 5, 5), new BoardTrackB.Position(1, 9, 9)),
                BoardTrackB.cut(new long[]{5, 9}, new long[]{5, 9}, 8));
    }

    @Test
    void optionsAreCheckedBeforeAnythingRuns() {
        assertEquals("stats", BoardWorkload.options("track-a", List.of("--bootstrap", "b", "--domain", "d", "--chain", "stats",
                "--series", "100", "--seed", "1", "--catalog", "c", "--checkpoint", "k", "--interval-seconds", "10",
                "--duration-seconds", "2100")).get("chain"));
        assertThrows(IllegalArgumentException.class, () -> BoardWorkload.options("track-a", List.of("--chain", "stats")));
        assertThrows(IllegalArgumentException.class, () -> BoardWorkload.options("track-c", List.of()));
        assertThrows(IllegalArgumentException.class, () -> BoardWorkload.options("digest", List.of("--domain", "d",
                "--series", "1", "--seed", "1", "--blocks", "1", "--profile", "x")));
    }

    @Test
    void eventsAreJsonLinesAndTheTimingsAreWrittenOnce() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BoardEvents events = new BoardEvents(new PrintStream(bytes, true, StandardCharsets.UTF_8), System.nanoTime());
        events.firstFetch();
        events.firstFetch();
        events.firstOutput();
        events.firstOutput();
        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size());
        assertEquals("firstFetch", JSON.readTree(lines.get(0)).path("event").asText());
        assertEquals("firstOutput", JSON.readTree(lines.get(1)).path("event").asText());
        assertTrue(JSON.readTree(lines.get(0)).path("seconds").asDouble() <= JSON.readTree(lines.get(1)).path("seconds").asDouble());
    }
}
