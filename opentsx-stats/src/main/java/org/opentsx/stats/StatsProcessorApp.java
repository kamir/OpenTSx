package org.opentsx.stats;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Command line for the statistics processor.
 *
 * <pre>
 * StatsProcessorApp --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt
 *                   [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true]
 *                   [--once true]   (process the backlog, close all windows, exit)
 * </pre>
 */
public final class StatsProcessorApp {

    private StatsProcessorApp() {
    }

    public static void main(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --option value pairs, got " + args[i]);
            }
            a.put(args[i].substring(2), args[i + 1]);
        }
        if (!a.containsKey("kafscale")) {
            System.err.println("usage: StatsProcessorApp --kafscale <bootstrap> --domain <d> --checkpoint <file> "
                    + "[--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true] [--once true]");
            System.exit(2);
        }
        StatsEngine.Config config = StatsEngine.Config.defaults()
                .withWindows(Arrays.stream(a.getOrDefault("windows", "PT10M,PT1H").split(",")).map(Duration::parse).toArray(Duration[]::new))
                .withAllowedLateness(Duration.parse(a.getOrDefault("lateness", "PT0S")))
                .withSketchAlpha(Double.parseDouble(a.getOrDefault("alpha", "0.01")));
        TopicLayout layout = new TopicLayout(a.getOrDefault("domain", "wind"));
        String bootstrap = a.get("kafscale");
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            StatsProcessor p = new StatsProcessor(consumer, producer, layout, new StatsEngine(config),
                    a.containsKey("checkpoint") ? Path.of(a.get("checkpoint")) : null,
                    Boolean.parseBoolean(a.getOrDefault("observations", "false")));
            if (Boolean.parseBoolean(a.getOrDefault("once", "false"))) {
                System.out.println(p.runUntilCaughtUp(Duration.ofHours(1), true));
            } else {
                AtomicBoolean stop = new AtomicBoolean();
                Thread main = Thread.currentThread();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    stop.set(true);
                    try {
                        main.join(60_000);    // let the processor write its final checkpoint
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                p.run(Duration.ofSeconds(30), stop::get);
            }
        }
    }
}
