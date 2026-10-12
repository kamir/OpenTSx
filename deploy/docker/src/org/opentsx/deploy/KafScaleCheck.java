package org.opentsx.deploy;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Deployment helper (not part of the library): checks that a KafScale/Kafka bootstrap address works from where it
 * runs, including the broker addresses the cluster <em>advertises</em>, and optionally provisions the topics of a
 * domain with {@link TopicLayout#provision}. It only uses what KafScale supports: Metadata, ListOffsets
 * (earliest/latest) and CreateTopics; no consumer group.
 *
 * <pre>
 * KafScaleCheck --kafscale host:9092 [--domain wind] [--provision true --partitions 6]
 * </pre>
 *
 * Exit codes: 0 ok, 1 broker or advertised address not reachable / request failed, 2 usage.
 */
public final class KafScaleCheck {

    private KafScaleCheck() {
    }

    public static void main(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                usage();
            }
            a.put(args[i].substring(2), args[i + 1]);
        }
        if (!a.containsKey("kafscale") || args.length % 2 != 0) {
            usage();
        }
        String bootstrap = a.get("kafscale");
        TopicLayout layout = new TopicLayout(a.getOrDefault("domain", "wind"));
        Duration timeout = Duration.parse(a.getOrDefault("timeout", "PT20S"));
        int failures = 0;

        System.out.println("bootstrap " + bootstrap);
        for (String hp : bootstrap.split(",")) {
            failures += tcp("  bootstrap", hp.trim()) ? 0 : 1;
        }
        if (failures > 0) {
            System.out.println("FAIL: bootstrap address not reachable from here (routing, firewall, wrong port?)");
            System.exit(1);
        }

        try {
            if (Boolean.parseBoolean(a.getOrDefault("provision", "false"))) {
                int partitions = Integer.parseInt(a.getOrDefault("partitions", "6"));
                try (Admin admin = KafScaleClients.admin(bootstrap)) {
                    layout.provision(admin, partitions, (short) 1);
                }
                System.out.println("provisioned tsx." + layout.domain() + ".*.v2 (data partitions " + partitions
                        + ", existing topics are left unchanged)");
            }
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
                Map<String, List<PartitionInfo>> topics = consumer.listTopics(timeout);
                TreeSet<String> advertised = new TreeSet<>();
                topics.values().forEach(ps -> ps.forEach(p -> {
                    if (p.leader() != null && !p.leader().isEmpty()) {
                        advertised.add(p.leader().host() + ":" + p.leader().port());
                    }
                }));
                System.out.println("topics on the cluster: " + topics.size());
                System.out.println("advertised broker addresses (what clients really connect to):");
                if (advertised.isEmpty()) {
                    System.out.println("  (no partitions yet - cannot see advertised addresses; run with --provision true)");
                }
                for (String hp : advertised) {
                    if (!tcp("  advertised", hp)) {
                        failures++;
                    }
                }
                if (failures > 0) {
                    System.out.println("FAIL: the broker advertises an address that is not reachable from here. "
                            + "Set the broker's advertised host (KafScale: KAFSCALE_BROKER_HOST) to an address "
                            + "the clients can reach, or run the clients with hostNetwork.");
                }
                System.out.println("domain " + layout.domain() + ":");
                for (String t : layout.all()) {
                    List<PartitionInfo> ps = topics.get(t);
                    if (ps == null) {
                        System.out.printf("  %-36s missing%n", t);
                        continue;
                    }
                    List<TopicPartition> tps = new ArrayList<>();
                    ps.forEach(p -> tps.add(new TopicPartition(p.topic(), p.partition())));
                    Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, timeout);
                    Map<TopicPartition, Long> end = consumer.endOffsets(tps, timeout);
                    long records = 0;
                    for (TopicPartition tp : tps) {
                        records += end.get(tp) - begin.get(tp);
                    }
                    System.out.printf("  %-36s %2d partitions  %,12d records%n", t, ps.size(), records);
                }
            }
        } catch (RuntimeException e) {
            System.out.println("FAIL: " + e);
            System.exit(1);
        }
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("OK");
    }

    private static boolean tcp(String label, String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) {
            System.out.println(label + " " + hostPort + ": expected host:port");
            return false;
        }
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5_000);
            System.out.println(label + " " + hostPort + ": reachable");
            return true;
        } catch (Exception e) {
            System.out.println(label + " " + hostPort + ": NOT reachable (" + e.getMessage() + ")");
            return false;
        }
    }

    private static void usage() {
        System.err.println("usage: KafScaleCheck --kafscale <host:port> [--domain wind] [--provision true --partitions 6] [--timeout PT20S]");
        System.exit(2);
    }
}
