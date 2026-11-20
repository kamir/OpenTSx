package org.opentsx.wayang.board;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.wayang.JavaTsxTopicSink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The paced B4.2 feed (D-T15): every series carries one sample per second. Series {@code i} sends one episode of
 * {@link #EPISODE_SECONDS} samples per block; the series are staggered evenly over the block, so the broker receives
 * {@code series / EPISODE_SECONDS} episodes per second. Event times start at {@link #BASE_MICROS} and the values depend
 * only on the seed, the series and the second, so every run of one size writes the same records whatever the wall clock.
 */
public final class BoardFeed {

    /** 2026-01-01T00:00:00Z. */
    public static final long BASE_MICROS = 1_767_225_600_000_000L;
    public static final int EPISODE_SECONDS = 10;
    public static final String METRIC = "board.value";

    private final int series;
    private final long seed;
    private final List<SeriesKey> keys;
    private final OpenTsxAvro avro = new OpenTsxAvro();

    public BoardFeed(int series, long seed) {
        if (series < 1 || series > 16_384) throw new IllegalArgumentException("series must be within 1..16384");
        this.series = series;
        this.seed = seed;
        List<SeriesKey> list = new ArrayList<>(series);
        for (int i = 0; i < series; i++) list.add(key(i));
        this.keys = List.copyOf(list);
    }

    public static SeriesKey key(int index) {
        return SeriesKeys.of(METRIC, Map.of("sensor", String.format("s%05d", index)), "1");
    }

    /** The series ids in index order; the source inventory of every chain. */
    public List<String> seriesIds() {
        return keys.stream().map(SeriesKey::getSeriesId).toList();
    }

    /** The value of series {@code index} in second {@code second}: a slow wave per series plus seeded noise. */
    double value(int index, long second) {
        double noise = new SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ index * 0xBF58476D1CE4E5B9L ^ second).nextDouble(-1, 1);
        return Math.round((50 + 20 * Math.sin(2 * Math.PI * second / 600.0 + index) + noise) * 1e6) / 1e6;
    }

    /** Episode {@code block} of series {@code index}: seconds {@code [block * 10, block * 10 + 10)}. */
    public Episode episode(int index, long block) {
        double[] values = new double[EPISODE_SECONDS];
        for (int j = 0; j < EPISODE_SECONDS; j++) values[j] = value(index, block * EPISODE_SECONDS + j);
        long start = BASE_MICROS + block * EPISODE_SECONDS * 1_000_000L;
        return Episodes.regular(keys.get(index), start, 1_000_000L, values)
                .episodeId("board-" + index + "-" + block).source("opentsx-board-feed").createdAt(BASE_MICROS).build();
    }

    /** Offset in nanoseconds within a block at which series {@code index} sends. */
    long phaseNanos(int index) {
        return TimeUnit.SECONDS.toNanos(EPISODE_SECONDS) * index / series;
    }

    /** The records of {@code blocks} blocks in send order. */
    public List<ProducerRecord<String, byte[]>> records(String topic, long blocks) {
        List<ProducerRecord<String, byte[]>> out = new ArrayList<>();
        for (long block = 0; block < blocks; block++) {
            for (int i = 0; i < series; i++) out.add(JavaTsxTopicSink.toRecord(avro, topic, episode(i, block)));
        }
        return out;
    }

    /** SHA-256 over key length, key, payload length and payload of each record in send order: the run's input digest. */
    public String digest(String topic, long blocks) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        for (long block = 0; block < blocks; block++) {
            for (int i = 0; i < series; i++) {
                ProducerRecord<String, byte[]> r = JavaTsxTopicSink.toRecord(avro, topic, episode(i, block));
                byte[] key = r.key().getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(key.length).array());
                sha.update(key);
                sha.update(ByteBuffer.allocate(4).putInt(r.value().length).array());
                sha.update(r.value());
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /**
     * Sends {@code blocks} blocks paced by the wall clock: block {@code b} of series {@code i} leaves at
     * {@code (b + 1) * 10 s + phase(i)} after the start, once its last second has passed.
     */
    public void run(String bootstrap, String topic, long blocks, BoardEvents events) throws Exception {
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            producer.partitionsFor(topic);
            long origin = System.nanoTime();
            long sent = 0;
            for (long block = 0; block < blocks; block++) {
                List<Future<?>> pending = new ArrayList<>(series);
                for (int i = 0; i < series; i++) {
                    long due = origin + TimeUnit.SECONDS.toNanos((block + 1) * EPISODE_SECONDS) + phaseNanos(i);
                    long wait = due - System.nanoTime();
                    if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                    pending.add(producer.send(JavaTsxTopicSink.toRecord(avro, topic, episode(i, block))));
                }
                for (Future<?> f : pending) f.get();
                sent += series;
                events.emit("feed", Map.of("block", block, "records", sent));
            }
            producer.flush();
        }
    }
}
