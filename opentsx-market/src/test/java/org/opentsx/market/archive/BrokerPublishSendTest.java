package org.opentsx.market.archive;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.market.MarketCatalog;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;
import org.opentsx.market.SeriesDef;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code market publish} against a {@link MockProducer}: the stop at the first batch the broker does not confirm. */
class BrokerPublishSendTest {

    /** Confirms the first {@code confirmed} records and fails every later one the way an expired delivery does. */
    static final class Broker extends MockProducer<String, byte[]> {
        final int confirmed;
        final TimeoutException expired = new TimeoutException("Expiring 1 record(s) for tsx.test.episodes.v2-0");

        Broker(int confirmed) {
            super(false, new StringSerializer(), new ByteArraySerializer());
            this.confirmed = confirmed;
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> r) {
            Future<RecordMetadata> f = super.send(r);
            if (history().size() <= confirmed) {
                completeNext();
            } else {
                errorNext(expired);
            }
            return f;
        }
    }

    @Test
    void theFirstBatchTheBrokerDoesNotConfirmStopsThePublish(@TempDir Path dir) {
        SeriesDef load = MarketCatalog.require("smard/load");
        List<Point> hours = new ArrayList<>();
        long start = Micro.of(Instant.parse("2024-01-01T00:00:00Z"));
        for (int h = 0; h < 72; h++) {
            hours.add(new Point(start + h * Micro.HOUR, Micro.HOUR, 50_000 + h));
        }
        Archive a = new Archive(dir);
        new Backfill(a, null, Map.of(), new PrintStream(new ByteArrayOutputStream())).store(load, 2024,
                new MarketSeries(load, hours), List.of("fixture"), Instant.parse("2026-01-10T00:00:00Z"),
                start + 72 * Micro.HOUR);
        OpenTsxConf conf = OpenTsxConf.load(List.of("market", "publish", "--archive", dir.toString(), "--series",
                "smard/load", "--domain", "test", "--kafscale", "broker.example:9092", "--batch", "2", "--pause-ms",
                "0"), Map.of(), null).conf();
        Broker broker = new Broker(2);
        List<String> bootstraps = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> BrokerPublish.check(a, conf, b -> {
            bootstraps.add(b);
            return broker;
        }).accept(new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertEquals("the broker did not confirm the batch after episode 2 of 3; nothing after it was sent",
                e.getMessage());
        assertSame(broker.expired, e.getCause());
        assertEquals(List.of("broker.example:9092"), bootstraps);
        assertEquals(3, broker.history().size());
        assertEquals(Episodes.producer("opentsx-market"), new OpenTsxAvro().decode(broker.history().get(0).value(),
                Episode.class).getProvenance().getProducer());
        assertTrue(broker.closed());
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("    2 of 3 confirmed (batch in "), printed);
        assertFalse(printed.contains("published "), printed);
    }
}
