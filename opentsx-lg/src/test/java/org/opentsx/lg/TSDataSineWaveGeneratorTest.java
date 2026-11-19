package org.opentsx.lg;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.EventFlowStateProducer;
import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.lg.metrics.TSGBeanImpl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TSDataSineWaveGeneratorTest {

    @TempDir
    Path dir;

    private static final TSOProducer SERIES_SENT_NOWHERE = new TSOProducer() {
        @Override
        public void pushTSDataAsEpisodesToKafka_String_Avro(Vector<TSData> data, String URI, String topic_suffix) {
        }

        @Override
        public void pushTSDataAsEventsToKafka_String_Avro(Vector<TSData> data, String URI) {
        }
    };

    private static MockProducer<String, String> mockProducer() {
        return new MockProducer<>(true, new StringSerializer(), new StringSerializer());
    }

    @Test
    void theRoundsShareOneFlowStateProducerAndCloseIt() throws Exception {
        RNGWrapper.init(7);
        List<MockProducer<String, String>> created = new ArrayList<>();
        Supplier<EventFlowStateProducer> flowStates = () -> {
            MockProducer<String, String> producer = mockProducer();
            created.add(producer);
            return new EventFlowStateProducer(producer);
        };
        TSDataSineWaveGenerator.generate(3, false, dir + "/", SERIES_SENT_NOWHERE, flowStates, new TSGBeanImpl());
        assertEquals(1, created.size());
        assertEquals(3, created.get(0).history().size());
        assertTrue(created.get(0).closed());
    }

    @Test
    void aFailedFlowStateSendStopsTheRoundsAndClosesTheProducer() throws Exception {
        RNGWrapper.init(7);
        MockProducer<String, String> producer = mockProducer();
        producer.sendException = new KafkaException("probe: the broker is unreachable");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TSDataSineWaveGenerator.generate(3,
                false, dir + "/", SERIES_SENT_NOWHERE, () -> new EventFlowStateProducer(producer), new TSGBeanImpl()));
        assertEquals("OpenTSx_Event_Flow_State: could not send the record; probe: the broker is unreachable",
                e.getMessage());
        assertTrue(producer.closed());
    }

    @Test
    void eachRoundPrintsOneResultLineOnStdout() throws Exception {
        RNGWrapper.init(7);
        PrintStream previous = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            TSDataSineWaveGenerator.generate(2, false, dir + "/", null, () -> {
                throw new AssertionError("no flow-state producer without a series producer");
            }, new TSGBeanImpl());
        }
        finally {
            System.setOut(previous);
        }
        List<String> rounds = out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("round done")).collect(Collectors.toList());
        assertEquals(2, rounds.size(), out.toString(StandardCharsets.UTF_8));
        assertTrue(rounds.get(0).matches("round done round=1 series=16 generateSeconds=\\d+\\.\\d+ sendSeconds=\\d+\\.\\d+"),
                rounds.get(0));
        assertTrue(rounds.get(1).matches("round done round=2 series=16 generateSeconds=\\d+\\.\\d+ sendSeconds=\\d+\\.\\d+"),
                rounds.get(1));
    }
}
