package org.opentsx.flink.serdes;

import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.flink.api.common.functions.util.ListCollector;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.metrics.util.InterceptingOperatorMetricGroup;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.SimpleUserCodeClassLoader;
import org.apache.flink.util.UserCodeClassLoader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.opentsx.data.model.Observation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationSchemaTest {

    private static final byte[] UNDECODABLE = {(byte) 0xff};

    interface Body {
        void run() throws Exception;
    }

    @Test
    void undecodableRecordsAreCountedAndOnlyTheFirstIsLoggedNotPrinted() throws Exception {
        ObservationSchema schema = new ObservationSchema();
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        schema.open(context(metrics));
        List<Observation> out = new ArrayList<>();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        List<String> lines;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            lines = logged(() -> {
                for (int i = 0; i < 100; i++) {
                    schema.deserialize(record(3, i, UNDECODABLE), new ListCollector<>(out));
                }
            });
        } finally {
            System.setErr(stderr);
        }

        assertTrue(out.isEmpty());
        assertNotNull(metrics.get("undecodable"), "the schema registers the metric undecodable");
        assertEquals(100, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals("", err.toString(StandardCharsets.UTF_8), "nothing goes to stderr directly");
        assertEquals("WARN org.opentsx.flink.serdes.ObservationSchema - undecodable observation skipped topic=observations"
                + " partition=3 offset=0 cause=java.io.EOFException", lines.get(0));
    }

    @Test
    void hundredUndecodableRecordsGiveTheCounter100AndTwoLogLines() throws Exception {
        long[] now = {0};
        ObservationSchema schema = new ObservationSchema(() -> now[0]);
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        schema.open(context(metrics));
        List<Observation> out = new ArrayList<>();

        List<String> lines = logged(() -> {
            for (int i = 0; i < 100; i++) {
                if (i == 99) {
                    now[0] = ObservationSchema.REPORT.toNanos();
                }
                schema.deserialize(record(3, i, UNDECODABLE), new ListCollector<>(out));
            }
        });

        assertTrue(out.isEmpty());
        assertEquals(100, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals(List.of("WARN org.opentsx.flink.serdes.ObservationSchema - undecodable observation skipped"
                        + " topic=observations partition=3 offset=0 cause=java.io.EOFException",
                "WARN org.opentsx.flink.serdes.ObservationSchema - undecodable observations skipped count=99 total=100"
                        + " topic=observations partition=3 offset=99"), lines);
    }

    @Test
    void anObservationWithoutAUriIsRejected() throws Exception {
        ObservationSchema schema = new ObservationSchema();
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        schema.open(context(metrics));
        List<Observation> out = new ArrayList<>();

        List<String> lines = logged(() -> {
            schema.deserialize(record(0, 7, encode(new Observation(1000L, "", 2.0))), new ListCollector<>(out));
            schema.deserialize(record(0, 8, encode(new Observation(2000L, " ", 3.0))), new ListCollector<>(out));
            schema.deserialize(record(0, 9, encode(new Observation(3000L, "quality-series", 4.0))), new ListCollector<>(out));
        });

        assertEquals(1, out.size(), out.toString());
        assertEquals("quality-series", out.get(0).getUri().toString());
        assertNotNull(metrics.get("invalid"), "the schema registers the metric invalid");
        assertEquals(2, ((Counter) metrics.get("invalid")).getCount());
        assertEquals(0, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals(List.of("WARN org.opentsx.flink.serdes.ObservationSchema - invalid observation skipped topic=observations"
                + " partition=0 offset=7 cause=uri: empty; expected the URI of the series"), lines);
    }

    @Test
    void aRecordWithoutAValueIsCountedAsUndecodable() throws Exception {
        ObservationSchema schema = new ObservationSchema();
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        schema.open(context(metrics));
        List<Observation> out = new ArrayList<>();

        List<String> lines = logged(() -> schema.deserialize(record(1, 5, null), new ListCollector<>(out)));

        assertTrue(out.isEmpty());
        assertEquals(1, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals(List.of("WARN org.opentsx.flink.serdes.ObservationSchema - undecodable observation skipped"
                + " topic=observations partition=1 offset=5 cause=no value"), lines);
    }

    @Test
    void theSchemaSurvivesTheSerializationThatShipsItToTheTasks() throws Exception {
        ObservationSchema schema = InstantiationUtil.clone(new ObservationSchema());
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        schema.open(context(metrics));
        List<Observation> out = new ArrayList<>();

        List<String> lines = logged(() -> {
            schema.deserialize(record(0, 1, UNDECODABLE), new ListCollector<>(out));
            schema.deserialize(record(0, 2, UNDECODABLE), new ListCollector<>(out));
            schema.deserialize(record(0, 3, encode(new Observation(1000L, "quality-series", 2.0))), new ListCollector<>(out));
        });

        assertEquals(1, out.size());
        assertEquals(2, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals(1, lines.size(), String.join("\n", lines));
    }

    private static ConsumerRecord<byte[], byte[]> record(int partition, long offset, byte[] value) {
        return new ConsumerRecord<>("observations", partition, offset, null, value);
    }

    private static byte[] encode(Observation observation) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(bytes, null);
        new SpecificDatumWriter<Observation>(Observation.getClassSchema()).write(observation, encoder);
        encoder.flush();
        return bytes.toByteArray();
    }

    private static DeserializationSchema.InitializationContext context(MetricGroup metrics) {
        return new DeserializationSchema.InitializationContext() {
            @Override
            public MetricGroup getMetricGroup() {
                return metrics;
            }

            @Override
            public UserCodeClassLoader getUserCodeClassLoader() {
                return SimpleUserCodeClassLoader.create(getClass().getClassLoader());
            }
        };
    }

    /** The lines that {@link ObservationSchema} logs while {@code body} runs, as {@code <level> <logger> - <message>}. */
    private static List<String> logged(Body body) throws Exception {
        Logger logger = (Logger) LogManager.getLogger(ObservationSchema.class);
        StringWriter logged = new StringWriter();
        WriterAppender appender = WriterAppender.newBuilder().setName("observation-schema").setTarget(logged)
                .setLayout(PatternLayout.newBuilder().withPattern("%p %c - %m%n").build()).build();
        appender.start();
        Level level = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            body.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(level);
            appender.stop();
        }
        return logged.toString().lines().toList();
    }
}
