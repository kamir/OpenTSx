package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxTopicSinkStreamingTest {

    @Test
    void avroRecordsAreSentBeforeTheNextUpstreamRecordIsRead() throws Exception {
        JsonNode wire = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build()
                .readTree(Path.of("..", "opentsx-model", "src", "test", "resources",
                "testvectors", "wire-format.json").toFile());
        byte[] bytes = HexFormat.of().parseHex(wire.path("episodes").get(0).path("hex").textValue());
        Episode episode = new OpenTsxAvro().decode(bytes, Episode.class);
        RuntimeException failure = new IllegalStateException("upstream failed after the first record");
        AtomicInteger seen = new AtomicInteger();
        var input = List.of(episode, episode).stream().map(record -> {
            if (seen.incrementAndGet() > 1) throw failure;
            return record;
        }).iterator();
        try (MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer())) {
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> JavaTsxTopicSink.write(producer, "tsx.test.clean.v2", input)));
            assertEquals(1, producer.history().size());
            assertArrayEquals(bytes, producer.history().get(0).value());
        }
    }

    @Test
    void aLaterInvalidJsonSignalPreventsEverySend() throws Exception {
        JsonNode c = TsxDqVectorsTest.cases().get(0);
        JsonNode signal = TsxDqVectorsTest.plan(TsxDqVectorsTest.operator(c), List.of(TsxDqVectorsTest.statistics(c, 0, 0))).get(0);
        ObjectNode invalid = signal.deepCopy();
        invalid.put("schema", "unsupported");
        try (MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer())) {
            assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSink.write(producer, "tsx.test.signals.v1",
                    List.of(signal, invalid).iterator()));
            assertTrue(producer.history().isEmpty());
        }
    }
}
