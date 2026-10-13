package org.opentsx.retrieval.vector;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Command line of the vector processor.
 *
 * <pre>
 * VectorProcessorApp --kafscale host:9092 --domain wind --state /state              run (exactly one per domain)
 * VectorProcessorApp deploy --kafscale host:9092 --domain wind --dag flow.dag.yaml  deploy a DAG, or replace it
 * VectorProcessorApp stop   --kafscale host:9092 --domain wind --name wind-exp2-smart
 * </pre>
 * {@code deploy} and {@code stop} only write a record to the control topic; the running processor takes it up.
 */
public final class VectorProcessorApp {

    private VectorProcessorApp() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
        Map<String, String> a = new HashMap<>();
        for (int i = command.equals("run") && (args.length == 0 || args[0].startsWith("--")) ? 0 : 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --option value pairs, got " + args[i]);
            }
            a.put(args[i].substring(2), args[i + 1]);
        }
        if (!a.containsKey("kafscale")) {
            System.err.println("usage: VectorProcessorApp [run] --kafscale <bootstrap> --domain <d> --state <dir>\n"
                    + "       VectorProcessorApp deploy --kafscale <bootstrap> --domain <d> --dag <file.yaml> [--by <who>]\n"
                    + "       VectorProcessorApp stop --kafscale <bootstrap> --domain <d> --name <dag> [--by <who>]");
            System.exit(2);
        }
        String bootstrap = a.get("kafscale");
        TopicLayout layout = new TopicLayout(a.getOrDefault("domain", "wind"));
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            layout.provisionDagTopics(admin, (short) 1);
        }
        String by = a.getOrDefault("by", "cli");
        switch (command) {
            case "deploy" -> {
                String spec = Files.readString(Path.of(a.get("dag")));
                String name = VectorProcessor.dagName(spec);
                // refuse here what the processor would refuse, so the caller sees the reason at once
                VectorJob.of(new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                        .readTree(spec), layout.domain(), layout.episodes());
                send(bootstrap, layout, name, VectorProcessor.deployRecord(name, spec, by));
                System.out.println("deployed " + name + " (control topic " + layout.dags() + ")");
            }
            case "stop" -> {
                send(bootstrap, layout, a.get("name"), VectorProcessor.stopRecord(a.get("name"), by));
                System.out.println("stopped " + a.get("name"));
            }
            case "run" -> run(bootstrap, layout, Path.of(a.getOrDefault("state", "vector-state")));
            default -> throw new IllegalArgumentException("unknown command " + command);
        }
    }

    private static void send(String bootstrap, TopicLayout layout, String key, byte[] value) throws Exception {
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            producer.send(new ProducerRecord<>(layout.dags(), key, value))
                    .get(KafScaleClients.DELIVERY_TIMEOUT.toSeconds() + 30, TimeUnit.SECONDS);
        }
    }

    private static void run(String bootstrap, TopicLayout layout, Path state) {
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            // sinks are created as their DAGs arrive; the processor needs the right to create topics
            layout.provisionDagTopics(admin, (short) 1);
        }
        VectorProcessor p = new VectorProcessor(() -> KafScaleClients.consumer(bootstrap), () -> KafScaleClients.producer(bootstrap),
                layout, state, VectorProcessor.Timing.standard());
        p.sinkProvisioner(name -> {
            try (Admin admin = KafScaleClients.admin(bootstrap)) {
                layout.provisionSmartVectors(admin, name, (short) 1);
            }
        });
        AtomicBoolean stop = new AtomicBoolean();
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.set(true);
            try {
                main.join(60_000);    // let the runners write their final checkpoints
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        System.out.println("vector processor for domain " + layout.domain() + ", state in " + state.toAbsolutePath());
        p.run(stop::get);
    }
}
