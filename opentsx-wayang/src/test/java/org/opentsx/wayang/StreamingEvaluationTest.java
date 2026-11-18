package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsAccumulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class StreamingEvaluationTest {
    @TempDir Path directory;

    @Test void genericObserverRetainsEpisodeAndStatisticsPayloadsAndIgnoresControlJson() {
        Episode episode = IncrementLatencyTest.episode("one", 0, new double[]{1, 2});
        SeriesStats stats = statistics(episode, "PT10M", 0, 4_000_000);
        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, byte[]> payloads = Map.of("input", avro.encode(episode), "statistics", avro.encode(stats),
                "control", "{\"command\":\"deploy\"}".getBytes(StandardCharsets.UTF_8));
        var data = new ProduceRequestData().setAcks((short) -1).setTimeoutMs(30000);
        var response = new ProduceResponseData();
        for (String topic : new java.util.TreeSet<>(payloads.keySet())) {
            MemoryRecords records = MemoryRecords.withRecords(Compression.zstd().build(), new SimpleRecord(987, payloads.get(topic)));
            data.topicData().add(new ProduceRequestData.TopicProduceData().setName(topic).setPartitionData(List.of(
                    new ProduceRequestData.PartitionProduceData().setIndex(3).setRecords(records))));
            response.responses().add(new ProduceResponseData.TopicProduceResponse().setName(topic).setPartitionResponses(List.of(
                    new ProduceResponseData.PartitionProduceResponse().setIndex(3).setBaseOffset(41).setErrorCode((short) 0))));
        }
        RequestHeader header = new RequestHeader(ApiKeys.PRODUCE, (short) 9, "evaluation", 27);
        ProduceRequest request = new ProduceRequest(data, (short) 9);
        List<LatencyProtocolObserver.RecordEntry> captured = new ArrayList<>();
        LatencyProtocolObserver.acknowledgeRecords(IncrementLatencyTest.response(header.toResponseHeader(), response, (short) 9),
                new LatencyProtocolObserver.Pending(header, 100, Instant.EPOCH, 0, request), 200, captured, Set.of("input", "statistics"));
        assertEquals(2, captured.size());
        assertInstanceOf(Episode.class, captured.get(0).record());
        assertInstanceOf(SeriesStats.class, captured.get(1).record());
        for (var entry : captured) {
            assertArrayEquals(payloads.get(entry.topic()), entry.payload());
            assertEquals(41, entry.offset());
            assertEquals(3, entry.partition());
            assertEquals(100, entry.requestNanos());
            assertEquals(200, entry.acknowledgedNanos());
            assertEquals(0, entry.error());
        }
        response.responses().find("statistics").partitionResponses().get(0).setErrorCode((short) 7);
        captured.clear();
        LatencyProtocolObserver.acknowledgeRecords(IncrementLatencyTest.response(header.toResponseHeader(), response, (short) 9),
                new LatencyProtocolObserver.Pending(header, 300, Instant.EPOCH, 0, request), 400, captured, Set.of("statistics"));
        assertEquals(1, captured.size());
        assertEquals(-1, captured.get(0).offset());
        assertEquals(7, captured.get(0).error());
    }

    @Test void statisticsIdentityIncludesDurationAndBothBounds() {
        Episode episode = IncrementLatencyTest.episode("one", 0, new double[]{1, 2});
        SeriesStats first = statistics(episode, "PT10M", 0, 10);
        assertNotEquals(StreamingEvaluation.identity(first), StreamingEvaluation.identity(statistics(episode, "PT1H", 0, 10)));
        assertNotEquals(StreamingEvaluation.identity(first), StreamingEvaluation.identity(statistics(episode, "PT10M", 0, 11)));
        assertNotEquals(StreamingEvaluation.identity(first), StreamingEvaluation.identity(statistics(episode, "PT10M", 1, 10)));
    }

    @Test void checkpointValidationRequiresTheExactCutIncludingEmptyPartitions() throws Exception {
        Map<TopicPartition, Long> ends = Map.of(new TopicPartition("input", 0), 5L, new TopicPartition("input", 1), 0L);
        StreamingEvaluation.verifyPositions(StreamingEvaluation.JSON.readTree("{\"0\":5,\"1\":0}"), ends, false);
        StreamingEvaluation.verifyPositions(StreamingEvaluation.JSON.readTree("{\"input|0\":5,\"input|1\":0}"), ends, true);
        for (String invalid : List.of("{\"0\":5}", "{\"0\":5,\"1\":0,\"2\":0}", "{\"0\":4,\"1\":0}",
                "{\"0\":5.0,\"1\":0}", "{\"0\":\"5\",\"1\":0}")) {
            assertThrows(IllegalStateException.class, () -> StreamingEvaluation.verifyPositions(StreamingEvaluation.JSON.readTree(invalid), ends, false));
        }
    }

    @Test void statisticsProvenanceUsesRawSeriesAndTheActualWindowOverlap() {
        Episode first = IncrementLatencyTest.episode("one", 0, new double[]{1, 2});
        Episode second = IncrementLatencyTest.episode("two", 2_000_000, new double[]{3, 4});
        Episode third = IncrementLatencyTest.episode("three", 4_000_000, new double[]{5, 6});
        Episode other = IncrementLatencyTest.episode("other", 0, new double[]{7, 8});
        other.getSeries().setSeriesId("0123456789abcdef");
        SeriesStats window = statistics(first, "PT10M", 1_000_000, 3_000_000);
        assertEquals(List.of(first, second), StreamingEvaluation.contributors(window, "stats", List.of(first, second, third, other)));
        assertThrows(IllegalStateException.class, () -> StreamingEvaluation.contributors(window, "stats", List.of(third, other)));
    }

    @Test void cleanComparisonIgnoresCreationTimeButRejectsChangedValuesAndMissingIds() throws Exception {
        Episode input = IncrementLatencyTest.episode("input", 0, new double[]{10, 20});
        var document = StreamingEvaluation.document("clean", List.of(input));
        String hash = "a".repeat(64);
        var job = org.opentsx.retrieval.vector.DagPlan.of(document, StreamingEvaluation.LAYOUT, hash).series().get(0);
        var stream = new org.opentsx.retrieval.clean.SeriesStream(job.chain(), job.target(), job.seriesIds());
        Episode longInput = IncrementLatencyTest.episode("long", 2_000_000, new double[120]);
        List<Episode> inputRecords = List.of(input, longInput);
        Map<String, SpecificRecord> actual = new TreeMap<>();
        for (Episode episode : inputRecords) for (Episode output : stream.add(episode)) {
            output.getProvenance().setCreatedAt(Instant.EPOCH);
            actual.put(output.getEpisodeId(), output);
        }
        assertFalse(actual.isEmpty());
        StreamingEvaluation.validate("clean", document, hash, inputRecords, actual);
        assertThrows(IllegalStateException.class, () -> StreamingEvaluation.validate("clean", document, hash, inputRecords, Map.of()));
        Episode changed = (Episode) actual.values().iterator().next();
        changed.getValues().set(0, 123.0);
        assertThrows(IllegalStateException.class, () -> StreamingEvaluation.validate("clean", document, hash, inputRecords, actual));
    }

    @Test void statisticsComparisonChecksCompletePayloadAndEveryOutputIdentity() throws Exception {
        Episode input = IncrementLatencyTest.episode("input", 0, new double[]{1, 2, 3});
        var reference = new org.opentsx.stats.StatsEngine(StreamingEvaluation.STATS);
        reference.onEpisode(input);
        Map<String, SpecificRecord> outputs = new TreeMap<>();
        for (SeriesStats statistics : reference.flush()) outputs.put(StreamingEvaluation.identity(statistics), statistics);
        var document = StreamingEvaluation.document("stats", List.of(input));
        StreamingEvaluation.validate("stats", document, "a".repeat(64), List.of(input), outputs);
        SeriesStats changed = (SeriesStats) outputs.values().iterator().next();
        changed.setM2(changed.getM2() + 1);
        assertThrows(IllegalStateException.class, () -> StreamingEvaluation.validate("stats", document, "a".repeat(64), List.of(input), outputs));
    }

    @Test void backlogLatencyUsesTheLatestContributingProduceAndRetainsAcknowledgements() throws Exception {
        Episode first = IncrementLatencyTest.episode("one", 0, new double[]{1, 2});
        Episode second = IncrementLatencyTest.episode("two", 2_000_000, new double[]{3, 4});
        Episode output = IncrementLatencyTest.episode("out", 1_000_000, new double[]{2, 3});
        List<LatencyProtocolObserver.RecordEntry> observed = observations(first, second, output, 400, 450);
        StreamingEvaluation.write(directory, "clean", "wayang", 1, "a".repeat(64), Instant.EPOCH, Instant.EPOCH, Instant.EPOCH.plusNanos(200),
                300, 500, 100, 320, StreamingEvaluation.LAYOUT.clean(), List.of(first, second), observed, Map.of("out", output));
        String csv = Files.readString(directory.resolve("records.csv"));
        assertTrue(csv.contains("\"one;two\",\"200\",\"210\",\"400\",\"450\",\"2.0E-4\""), csv);
        var summary = StreamingEvaluation.JSON.readTree(directory.resolve("case.json").toFile());
        assertEquals(20, summary.path("firstFetchNanos").asLong());
        assertEquals(100, summary.path("firstOutputNanos").asLong());
        assertEquals(150, summary.path("lastOutputAckNanos").asLong());
        assertEquals(0, summary.path("intervalSeconds").asInt());
        assertEquals(2, summary.path("inputRecords").asLong());
        assertEquals(StreamingEvaluation.LAYOUT.episodes(), summary.path("inputTopic").asText());
        assertEquals(StreamingEvaluation.LAYOUT.clean(), summary.path("outputTopic").asText());
        assertEquals(Instant.EPOCH.plusNanos(200).toString(), summary.path("jobEndedAt").asText());
        assertTrue(summary.path("processStartedAt").isTextual());
    }

    @Test void failedOrDuplicateWireEvidenceAndInvalidClocksCannotProduceResults() throws Exception {
        Episode first = IncrementLatencyTest.episode("one", 0, new double[]{1, 2});
        Episode second = IncrementLatencyTest.episode("two", 2_000_000, new double[]{3, 4});
        Episode output = IncrementLatencyTest.episode("out", 1_000_000, new double[]{2, 3});
        for (int number = 0; number < 4; number++) {
            Path target = Files.createDirectory(directory.resolve("case" + number));
            List<LatencyProtocolObserver.RecordEntry> entries = new ArrayList<>(observations(first, second, output, number == 2 ? 299 : 400, number == 3 ? 501 : 450));
            if (number == 0) entries.add(entries.get(0));
            if (number == 1) entries.remove(0);
            assertThrows(IllegalStateException.class, () -> StreamingEvaluation.write(target, "clean", "wayang", 1, "a".repeat(64),
                    Instant.EPOCH, Instant.EPOCH, Instant.EPOCH.plusNanos(200), 300, 500, 100, 320,
                    StreamingEvaluation.LAYOUT.clean(), List.of(first, second), entries, Map.of("out", output)));
            assertFalse(Files.exists(target.resolve("case.json")));
        }
    }

    @Test void onlyTheDeclaredRuntimeAndPartitionMatrixIsAccepted() {
        for (int partitions : new int[]{1, 4, 12}) for (String chain : List.of("passthrough", "clean", "stats")) {
            StreamingEvaluation.validateArguments(39092, chain, "wayang", partitions);
            StreamingEvaluation.validateArguments(39092, chain, "java", partitions);
        }
        assertThrows(IllegalArgumentException.class, () -> StreamingEvaluation.validateArguments(0, "clean", "java", 1));
        assertThrows(IllegalArgumentException.class, () -> StreamingEvaluation.validateArguments(39092, "other", "java", 1));
        assertThrows(IllegalArgumentException.class, () -> StreamingEvaluation.validateArguments(39092, "clean", "other", 1));
        assertThrows(IllegalArgumentException.class, () -> StreamingEvaluation.validateArguments(39092, "clean", "java", 2));
    }

    private static List<LatencyProtocolObserver.RecordEntry> observations(Episode first, Episode second, Episode output, long request, long ack) {
        OpenTsxAvro avro = new OpenTsxAvro();
        return List.of(new LatencyProtocolObserver.RecordEntry(StreamingEvaluation.LAYOUT.episodes(), 0, 0, first, avro.encode(first), 100, 110, (short) 0),
                new LatencyProtocolObserver.RecordEntry(StreamingEvaluation.LAYOUT.episodes(), 0, 1, second, avro.encode(second), 200, 210, (short) 0),
                new LatencyProtocolObserver.RecordEntry(StreamingEvaluation.LAYOUT.clean(), 0, 0, output, avro.encode(output), request, ack, (short) 0));
    }

    private static SeriesStats statistics(Episode episode, String type, long from, long to) {
        StatsAccumulator accumulator = new StatsAccumulator(0.01);
        accumulator.add(0, 1);
        accumulator.add(1, 2);
        return accumulator.toSeriesStats(episode.getSeries().getSeriesId(), type, from, to);
    }
}
