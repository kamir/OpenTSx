package org.opentsx.market.archive;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.market.EpisodeMapper;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.SeriesDef;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Writes archived series as raw episodes into the episodes topic of a domain, where the retrieval server and the
 * flows of the domain read them: one episode per UTC day and series, split where data is missing, with the origin,
 * the licence and the archive path of the series in its labels.
 *
 * <p>The broker may be shared and may refuse for a while. The writer is careful for that reason: it sends a batch,
 * waits for every confirmation, pauses, and stops at the first batch the broker does not confirm. It says how far it
 * came. An episode has an identity that follows from the series and the time it begins at, so a repeat writes the
 * same episodes again and a reader that keeps episodes by identity holds each once.
 */
public final class BrokerPublish {

    private BrokerPublish() {
    }

    public static int run(Archive archive, Map<String, String> o, PrintStream out) {
        String bootstrap = o.get("kafscale");
        String domain = o.get("domain");
        boolean dry = o.containsKey("dry-run");
        if (o.get("series") == null || domain == null || (bootstrap == null && !dry)) {
            throw new IllegalArgumentException("publish needs --series source/name,... --domain <domain> and --kafscale host:port (or --dry-run)");
        }
        int y0 = Integer.parseInt(o.getOrDefault("from-year", "2000"));
        int y1 = Integer.parseInt(o.getOrDefault("to-year", "2100"));
        int batch = Integer.parseInt(o.getOrDefault("batch", "100"));
        long pauseMs = Long.parseLong(o.getOrDefault("pause-ms", "3000"));
        Instant fromDay = day(o, "from-day", Instant.MIN);
        Instant toDay = day(o, "to-day", Instant.MAX);
        String topic = new TopicLayout(domain).episodes();

        List<Episode> all = new ArrayList<>();
        for (String path : o.get("series").split(",")) {
            SeriesDef def = archive.archivedSeries().stream().filter(d -> d.path().equals(path.trim())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("the archive has no series " + path.trim()));
            int before = all.size();
            long values = 0;
            for (String y : archive.manifest(def).years.keySet()) {
                int year = Integer.parseInt(y);
                if (year < y0 || year > y1) {
                    continue;
                }
                for (Episode e : EpisodeMapper.episodes(new MarketSeries(def, archive.read(def, year)), "opentsx-market/3.0.0")) {
                    if (e.getTStart().isBefore(fromDay) || !e.getTStart().isBefore(toDay)) {
                        continue;
                    }
                    all.add(identified(e));
                    values += e.getCount();
                }
            }
            out.printf("%-40s %6d episodes %8d values  metric %s, origin %s%n", def.path(), all.size() - before, values,
                    def.key().getMetric(), def.origin());
        }
        if (dry) {
            out.printf("dry run: %d episodes would go to %s%n", all.size(), topic);
            return 0;
        }
        OpenTsxAvro avro = new OpenTsxAvro();
        int done = 0;
        long started = System.nanoTime();
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            while (done < all.size()) {
                List<Episode> part = all.subList(done, Math.min(all.size(), done + batch));
                List<Future<RecordMetadata>> sent = new ArrayList<>();
                for (Episode e : part) {
                    sent.add(producer.send(new ProducerRecord<>(topic, e.getSeries().getSeriesId(), avro.encode(e))));
                }
                long t = System.nanoTime();
                try {
                    for (Future<RecordMetadata> f : sent) {
                        f.get(KafScaleClients.DELIVERY_TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
                    }
                } catch (ExecutionException | TimeoutException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    out.printf("STOPPED: the broker did not confirm the batch after episode %d of %d (%s: %s). Nothing after it was sent.%n",
                            done, all.size(), cause.getClass().getSimpleName(), cause.getMessage());
                    return 3;
                }
                done += part.size();
                out.printf("%5d of %d confirmed (batch in %d ms)%n", done, all.size(), (System.nanoTime() - t) / 1_000_000);
                if (done < all.size()) {
                    Thread.sleep(pauseMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.printf("STOPPED: interrupted after episode %d of %d%n", done, all.size());
            return 3;
        }
        out.printf("published %d episodes to %s in %d s%n", done, topic, (System.nanoTime() - started) / 1_000_000_000);
        return 0;
    }

    /** Start of the UTC day given as option {@code key} (YYYY-MM-DD), or {@code absent}. */
    public static Instant day(Map<String, String> o, String key, Instant absent) {
        return o.containsKey(key) ? LocalDate.parse(o.get(key)).atStartOfDay(ZoneOffset.UTC).toInstant() : absent;
    }

    /** The episode with an identity that follows from its series and the time it begins at. */
    public static Episode identified(Episode e) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((e.getSeries().getSeriesId() + "|" + e.getTStart() + "|" + e.getCount()).getBytes(StandardCharsets.UTF_8));
            return Episode.newBuilder(e).setEpisodeId("h" + HexFormat.of().formatHex(sha.digest(), 0, 12)).build();
        } catch (NoSuchAlgorithmException x) {
            throw new IllegalStateException(x);
        }
    }
}
