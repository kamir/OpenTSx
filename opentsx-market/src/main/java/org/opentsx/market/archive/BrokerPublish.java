package org.opentsx.market.archive;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.market.EpisodeMapper;
import org.opentsx.market.MarketCli;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.SeriesDef;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

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

    static final String DOMAIN = "opentsx.domain";

    private BrokerPublish() {
    }

    /**
     * Checks the keys of {@code market publish} and the series in the archive; reads and sends nothing. The work it
     * returns stops with an exception at the first batch the broker does not confirm.
     */
    public static Consumer<PrintStream> check(Archive archive, OpenTsxConf conf) {
        return check(archive, conf, KafScaleClients::producer);
    }

    /** {@link #check(Archive, OpenTsxConf)} with the producer for a bootstrap address from {@code producers}. */
    static Consumer<PrintStream> check(Archive archive, OpenTsxConf conf,
                                       Function<String, Producer<String, byte[]>> producers) {
        boolean dry = conf.getBoolean("opentsx.market.publish.dryRun");
        List<String> paths = conf.getList("opentsx.market.publish.series");
        if (!conf.contains(DOMAIN)) {
            throw new OpenTsxConfException(DOMAIN, "not set", "set it with --domain, --conf " + DOMAIN
                    + "=<value> or OPENTSX_DOMAIN");
        }
        String topic = new TopicLayout(conf.get(DOMAIN)).episodes();
        String bootstrap = dry ? null : conf.get("opentsx.kafscale.bootstrap");
        int y0 = conf.getInt("opentsx.market.fromYear");
        int y1 = conf.getInt("opentsx.market.toYear");
        int batch = conf.getInt("opentsx.market.publish.batch");
        if (batch < 1) {
            throw new OpenTsxConfException(MarketCli.named(conf, "opentsx.market.publish.batch"), "\"" + batch
                    + "\" is not allowed", "expected 1 or more");
        }
        long pauseMs = conf.getLong("opentsx.market.publish.pauseMs");
        if (pauseMs < 0) {
            throw new OpenTsxConfException(MarketCli.named(conf, "opentsx.market.publish.pauseMs"), "\"" + pauseMs
                    + "\" is not allowed", "expected 0 or more");
        }
        Instant fromDay = day(conf, "opentsx.market.fromDay", Instant.MIN);
        Instant toDay = day(conf, "opentsx.market.toDay", Instant.MAX);
        List<SeriesDef> defs = new ArrayList<>();
        for (String path : paths) {
            defs.add(archive.archivedSeries().stream().filter(d -> d.path().equals(path)).findFirst()
                    .orElseThrow(() -> new OpenTsxConfException(MarketCli.named(conf, "opentsx.market.publish.series"),
                            "item \"" + path + "\" is not in the archive " + archive.root(),
                            "expected an archived series; run opentsx market report for them")));
        }
        return out -> {
            List<Episode> all = new ArrayList<>();
            for (SeriesDef def : defs) {
                int before = all.size();
                long values = 0;
                for (String y : archive.manifest(def).years.keySet()) {
                    int year = Integer.parseInt(y);
                    if (year < y0 || year > y1) {
                        continue;
                    }
                    for (Episode e : EpisodeMapper.episodes(new MarketSeries(def, archive.read(def, year)),
                            Episodes.producer("opentsx-market"))) {
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
                return;
            }
            send(all, topic, producers.apply(bootstrap), batch, pauseMs, out);
        };
    }

    private static void send(List<Episode> all, String topic, Producer<String, byte[]> producer, int batch, long pauseMs,
                             PrintStream out) {
        OpenTsxAvro avro = new OpenTsxAvro();
        int done = 0;
        long started = System.nanoTime();
        try (producer) {
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
                    throw new IllegalStateException("the broker did not confirm the batch after episode " + done + " of "
                            + all.size() + "; nothing after it was sent", e.getCause() == null ? e : e.getCause());
                }
                done += part.size();
                out.printf("%5d of %d confirmed (batch in %d ms)%n", done, all.size(), (System.nanoTime() - t) / 1_000_000);
                if (done < all.size()) {
                    Thread.sleep(pauseMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted after episode " + done + " of " + all.size(), e);
        }
        out.printf("published %d episodes to %s in %d s%n", done, topic, (System.nanoTime() - started) / 1_000_000_000);
    }

    /** Start of the UTC day a key names (YYYY-MM-DD), or {@code absent} when it is not set. */
    public static Instant day(OpenTsxConf conf, String key, Instant absent) {
        Optional<String> v = conf.getOption(key);
        if (v.isEmpty()) {
            return absent;
        }
        try {
            return LocalDate.parse(v.get()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException e) {
            throw new OpenTsxConfException(MarketCli.named(conf, key), "\"" + v.get() + "\" is not a day",
                    "expected a UTC day YYYY-MM-DD");
        }
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
