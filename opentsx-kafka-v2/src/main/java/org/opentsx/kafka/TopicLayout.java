package org.opentsx.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Topic names of one OpenTSx domain: {@code tsx.<domain>.<kind>.v2}.
 *
 * <p>All topics are append-only logs (KafScale does not compact). The "latest state" of series, buckets and
 * statistics is derived by reading the log or kept in the catalog / Iceberg.
 */
public final class TopicLayout {

    public static final String HEADER_SCHEMA = "tsx-schema";
    public static final String HEADER_BUCKET = "tsx-bucket";

    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    private final String domain;

    public TopicLayout(String domain) {
        if (!DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("domain must match " + DOMAIN + ": " + domain);
        }
        this.domain = domain;
    }

    public String domain() {
        return domain;
    }

    public String episodes() {
        return topic("episodes");
    }

    public String buckets() {
        return topic("buckets");
    }

    public String series() {
        return topic("series");
    }

    public String observations() {
        return topic("observations");
    }

    public String seriesStats() {
        return topic("series-stats");
    }

    public List<String> all() {
        return List.of(episodes(), buckets(), series(), observations(), seriesStats());
    }

    /**
     * Creates missing topics (zstd, delete policy). Buckets and series get one partition so a full scan
     * stays ordered; data topics get {@code dataPartitions}.
     */
    public void provision(Admin admin, int dataPartitions, short replicationFactor) {
        Map<String, String> configs = Map.of("compression.type", "zstd", "cleanup.policy", "delete");
        List<NewTopic> topics = new ArrayList<>();
        for (String t : all()) {
            int partitions = t.equals(buckets()) || t.equals(series()) ? 1 : dataPartitions;
            topics.add(new NewTopic(t, partitions, replicationFactor).configs(configs));
        }
        try {
            Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            topics.removeIf(t -> existing.contains(t.name()));
            if (!topics.isEmpty()) {
                admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
            }
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("topic provisioning failed for domain " + domain, e.getCause());
            }
        } catch (Exception e) {
            throw new IllegalStateException("topic provisioning failed for domain " + domain, e);
        }
    }

    private String topic(String kind) {
        return "tsx." + domain + "." + kind + ".v2";
    }
}
