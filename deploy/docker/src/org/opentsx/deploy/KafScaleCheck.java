package org.opentsx.deploy;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The command {@code kafscale-check} (deployment helper, not part of the library): checks that a KafScale/Kafka
 * bootstrap address works from where it runs, including the broker addresses the cluster <em>advertises</em>, and
 * with {@code opentsx.kafscale.provision} creates the topics of a domain with {@link TopicLayout#provision}. It only
 * uses what KafScale supports: Metadata, ListOffsets (earliest/latest) and CreateTopics; no consumer group. It reads
 * the keys of {@code kafscale-check} in the registry {@code opentsx.config.v1} through {@link OpenTsxConf#load};
 * {@code opentsx kafscale-check --help} lists them. The result goes to stdout. Exit codes: 0 ok, 1 broker or advertised
 * address not reachable or a request failed, 2 usage or configuration error.
 */
public final class KafScaleCheck {

    static final String COMMAND = "kafscale-check";
    static final String BOOTSTRAP = "opentsx.kafscale.bootstrap";
    static final String DOMAIN = "opentsx.domain";
    static final String TIMEOUT = "opentsx.kafscale.checkTimeout";
    static final String PROVISION = "opentsx.kafscale.provision";
    static final String PARTITIONS = "opentsx.kafscale.partitions";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    private KafScaleCheck() {
    }

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.getenv(), System.out, System.err, KafScaleClients::consumer, KafScaleClients::admin));
    }

    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   Function<String, Consumer<byte[], byte[]>> consumers, Function<String, Admin> admins) {
        List<String> words = new ArrayList<>(args.size() + 1);
        words.add(COMMAND);
        words.addAll(args);
        String bootstrap;
        List<String> addresses;
        TopicLayout layout;
        Duration timeout;
        boolean provision;
        int partitions;
        Logger log;
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(words, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                out.print(Registry.shipped().help(COMMAND));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            OpenTsxConf conf = loaded.conf();
            bootstrap = conf.get(BOOTSTRAP);
            addresses = addresses(bootstrap);
            layout = new TopicLayout(conf.get(DOMAIN));
            timeout = conf.getDuration(TIMEOUT);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new OpenTsxConfException(TIMEOUT, "\"" + conf.get(TIMEOUT) + "\" is not allowed", "expected more than zero, such as PT20S");
            }
            provision = conf.getBoolean(PROVISION);
            partitions = conf.getInt(PARTITIONS);
            if (partitions < 1) {
                throw new OpenTsxConfException(PARTITIONS, "\"" + partitions + "\" is not allowed", "expected 1 or more");
            }
            log = LoggerFactory.getLogger(KafScaleCheck.class);
            log.info(loaded.startLine());
        } catch (IllegalArgumentException e) {
            // OpenTsxConfException, and a domain that TopicLayout refuses
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }

        int failures = 0;
        out.println("bootstrap " + bootstrap);
        for (String hp : addresses) {
            failures += tcp(out, "  bootstrap", hp) ? 0 : 1;
        }
        if (failures > 0) {
            out.println("FAIL: bootstrap address not reachable from here (routing, firewall, wrong port?)");
            written(out, err);
            return 1;
        }

        try {
            if (provision) {
                try (Admin admin = admins.apply(bootstrap)) {
                    layout.provision(admin, partitions, (short) 1);
                }
                out.println("provisioned tsx." + layout.domain() + ".*.v2 (data partitions " + partitions
                        + ", existing topics are left unchanged)");
            }
            try (Consumer<byte[], byte[]> consumer = consumers.apply(bootstrap)) {
                Map<String, List<PartitionInfo>> topics = consumer.listTopics(timeout);
                TreeSet<String> advertised = new TreeSet<>();
                topics.values().forEach(ps -> ps.forEach(p -> {
                    if (p.leader() != null && !p.leader().isEmpty()) {
                        advertised.add(p.leader().host() + ":" + p.leader().port());
                    }
                }));
                out.println("topics on the cluster: " + topics.size());
                out.println("advertised broker addresses (what clients really connect to):");
                if (advertised.isEmpty()) {
                    out.println("  (no partitions yet; cannot see advertised addresses; run with --provision true)");
                }
                for (String hp : advertised) {
                    if (!tcp(out, "  advertised", hp)) {
                        failures++;
                    }
                }
                if (failures > 0) {
                    out.println("FAIL: the broker advertises an address that is not reachable from here. "
                            + "Set the broker's advertised host (KafScale: KAFSCALE_BROKER_HOST) to an address "
                            + "the clients can reach, or run the clients with hostNetwork.");
                }
                out.println("domain " + layout.domain() + ":");
                for (String t : layout.all()) {
                    List<PartitionInfo> ps = topics.get(t);
                    if (ps == null) {
                        out.printf("  %-36s missing%n", t);
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
                    out.printf("  %-36s %2d partitions  %,12d records%n", t, ps.size(), records);
                }
            }
        } catch (RuntimeException e) {
            log.debug("kafscale-check failed", e);
            out.println("FAIL: " + causes(e));
            written(out, err);
            return 1;
        }
        if (failures > 0) {
            written(out, err);
            return 1;
        }
        out.println("OK");
        return written(out, err);
    }

    /** The items of the bootstrap value; each is host:port. */
    static List<String> addresses(String bootstrap) {
        List<String> out = new ArrayList<>();
        for (String item : bootstrap.split(",", -1)) {
            String hp = item.trim();
            int colon = hp.lastIndexOf(':');
            int port = colon <= 0 ? -1 : port(hp.substring(colon + 1));
            if (port < 1 || port > 65535) {
                throw new OpenTsxConfException(BOOTSTRAP, "\"" + hp + "\" is not host:port",
                        "expected host:port with a port from 1 to 65535, several separated by commas, such as kafscale:9092");
            }
            out.add(hp);
        }
        return out;
    }

    private static int port(String text) {
        if (text.isEmpty() || text.length() > 5 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        return Integer.parseInt(text);
    }

    private static boolean tcp(PrintStream out, String label, String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5_000);
            out.println(label + " " + hostPort + ": reachable");
            return true;
        } catch (Exception e) {
            out.println(label + " " + hostPort + ": NOT reachable (" + e.getMessage() + ")");
            return false;
        }
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        // PrintStream never throws; without this check a full disk or a closed stdout would exit 0.
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /** The message and its causes on one line. */
    static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString();
    }
}
