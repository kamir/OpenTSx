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
    private static final Pattern SMART_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

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

    /**
     * Topic of a vector Smart Topic: {@code tsx.<domain>.smart.<name>.vectors.v1}, records
     * {@code opentsx.vector.v1} (JSON). See docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5a.
     */
    public String smartVectors(String name) {
        if (!SMART_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("smart topic name must match " + SMART_NAME + ": " + name);
        }
        return "tsx." + domain + ".smart." + name + ".vectors.v1";
    }

    /** The name part of a vector topic of this domain, or null when the topic is not one. */
    public String smartVectorName(String topic) {
        String prefix = "tsx." + domain + ".smart.";
        String suffix = ".vectors.v1";
        if (topic == null || !topic.startsWith(prefix) || !topic.endsWith(suffix)
                || topic.length() <= prefix.length() + suffix.length()) {
            return null;
        }
        String name = topic.substring(prefix.length(), topic.length() - suffix.length());
        return SMART_NAME.matcher(name).matches() ? name : null;
    }

    /**
     * Control topic of the vector processor: one record per deployment or stop of a DAG, key = name of the DAG,
     * the latest record of a key counts ({@code opentsx.dag-control.v1}, JSON).
     */
    public String dags() {
        return "tsx." + domain + ".dags.v1";
    }

    /** What the vector processor reports per sink ({@code opentsx.dag-status.v1}, JSON); the latest record counts. */
    public String dagStatus() {
        return "tsx." + domain + ".dag-status.v1";
    }

    /** Creates the control and status topics when they are missing, one partition each. */
    public void provisionDagTopics(Admin admin, short replicationFactor) {
        try {
            Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            List<NewTopic> missing = new ArrayList<>();
            for (String t : List.of(dags(), dagStatus())) {
                if (!existing.contains(t)) {
                    missing.add(new NewTopic(t, 1, replicationFactor).configs(Map.of("compression.type", "zstd", "cleanup.policy", "delete")));
                }
            }
            if (!missing.isEmpty()) {
                admin.createTopics(missing).all().get(30, TimeUnit.SECONDS);
            }
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("could not create the DAG topics of domain " + domain, e.getCause());
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not create the DAG topics of domain " + domain, e);
        }
    }

    /** Creates the vector topic when it is missing: one partition, so a scan reads the measuring points in order. */
    public void provisionSmartVectors(Admin admin, String name, short replicationFactor) {
        String topic = smartVectors(name);
        try {
            if (!admin.listTopics().names().get(30, TimeUnit.SECONDS).contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, 1, replicationFactor)
                        .configs(Map.of("compression.type", "zstd", "cleanup.policy", "delete")))).all().get(30, TimeUnit.SECONDS);
            }
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("could not create " + topic, e.getCause());
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not create " + topic, e);
        }
    }

    private String topic(String kind) {
        return "tsx." + domain + "." + kind + ".v2";
    }
}
