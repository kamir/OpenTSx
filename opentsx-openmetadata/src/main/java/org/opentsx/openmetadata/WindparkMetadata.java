package org.opentsx.openmetadata;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.SeriesStatsLog;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the wind-farm metadata pool to OpenMetadata.
 *
 * <pre>
 * WindparkMetadata --scenario scenarios/ostsee-demo.yaml [--catalog my-catalog.yaml]
 *                  (--om http://localhost:8585/api [--token $OPENMETADATA_JWT] | --dry-run out/)
 *                  [--domain wind] [--namespace tsx] [--partitions 6]
 *                  [--kafscale localhost:9092 --profile-window PT1H --dq-window PT10M]
 * </pre>
 * With {@code --kafscale}, the latest statistics are read from {@code tsx.<domain>.series-stats.v2} and published
 * as table profiles and data-quality results.
 */
public final class WindparkMetadata {

    private WindparkMetadata() {
    }

    public static void main(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --option value pairs, got " + args[i]);
            }
            a.put(args[i].substring(2), args[i + 1]);
        }
        if (!a.containsKey("scenario") || !(a.containsKey("om") || a.containsKey("dry-run"))) {
            System.err.println("usage: WindparkMetadata --scenario <file.yaml> (--om <url>/api [--token <jwt>] | --dry-run <dir>) "
                    + "[--catalog <file.yaml>] [--domain wind] [--namespace tsx] [--kafscale <bootstrap> "
                    + "--profile-window PT1H --dq-window PT10M]");
            System.exit(2);
        }
        System.out.println(run(a));
    }

    static String run(Map<String, String> a) {
        ChannelCatalog catalog = a.containsKey("catalog") ? ChannelCatalog.load(Path.of(a.get("catalog"))) : ChannelCatalog.standard();
        Park park = new WindparkSimulator(Scenario.load(Path.of(a.get("scenario"))), catalog).park();
        TopicLayout layout = new TopicLayout(a.getOrDefault("domain", "wind"));
        OmApi api = a.containsKey("dry-run") ? new DryRunOmApi(Path.of(a.get("dry-run")))
                : new HttpOmApi(URI.create(a.get("om")), a.getOrDefault("token", System.getenv("OPENMETADATA_JWT")));
        WindparkMetadataPublisher.Settings d = WindparkMetadataPublisher.Settings.defaults();
        WindparkMetadataPublisher p = new WindparkMetadataPublisher(api, catalog, park, layout,
                new WindparkMetadataPublisher.Settings(d.messagingService(), d.databaseService(), d.database(),
                        a.getOrDefault("namespace", d.icebergNamespace()), d.pipelineService(), a.get("kafscale"),
                        Integer.parseInt(a.getOrDefault("partitions", Integer.toString(d.partitions())))));
        p.publishAll();
        StringBuilder out = new StringBuilder("published " + p.entities().size() + " entities for wind farm " + park.id());
        if (a.containsKey("kafscale")) {
            List<SeriesStats> stats;
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(a.get("kafscale"))) {
                stats = SeriesStatsLog.readLatest(consumer, layout, Duration.ofMinutes(5));
            }
            int profiles = p.publishProfiles(stats, a.getOrDefault("profile-window", "PT1H"));
            int results = p.publishDataQuality(stats, a.getOrDefault("dq-window", "PT10M"), WindparkExpectations.of(catalog));
            out.append(", ").append(profiles).append(" profiles, ").append(results).append(" data-quality results");
        }
        return out.toString();
    }
}
