package org.opentsx.flink.pfd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.app.bucketanalyser.PFDRecorder;
import org.opentsx.data.model.Observation;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PFDAcceptance {
    public static void main(String[] args) throws Exception {
        String brokers = System.getenv("KAFSCALE_BOOTSTRAP");
        if (brokers == null || brokers.isBlank()) {
            throw new IllegalArgumentException("KAFSCALE_BOOTSTRAP is required for PFD acceptance");
        }
        Path directory = Path.of("target", "pfd-acceptance").toAbsolutePath();
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve("result.json"));
        String topic = "pfd-acceptance-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", brokers))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        Map<String, Object> properties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
                properties, new StringSerializer(), new ByteArraySerializer())) {
            long[] timestamps = {1000L, 2000L, 600000L};
            double[] values = {2.0, 4.0, 100.0};
            for (int i = 0; i < values.length; i++) {
                Observation observation = new Observation(timestamps[i], "quality-series", values[i]);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                var encoder = EncoderFactory.get().binaryEncoder(bytes, null);
                new SpecificDatumWriter<Observation>(Observation.getClassSchema()).write(observation, encoder);
                encoder.flush();
                producer.send(new ProducerRecord<>(topic, "quality-series", bytes.toByteArray()))
                        .get(30, TimeUnit.SECONDS);
            }
        }
        System.setProperty("opentsx.pfd.input.topic", topic);
        PFDRecorder recorder = PFDRecorder.getInstance();
        recorder.reset("PFD acceptance");
        recorder.addStep("NORMALIZE", Map.of("method", "center"));
        Path descriptor = directory.resolve("flow.json");
        recorder.saveToFile(descriptor.toString());

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-XX:ActiveProcessorCount=1",
                "-Dbootstrap.servers=" + brokers, "-cp", System.getProperty("java.class.path"),
                DynamicTopologyJob.class.getName(), descriptor.toString()).redirectErrorStream(true).start();
        var reader = Executors.newSingleThreadExecutor();
        try {
            JsonObject output = reader.submit(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     var log = Files.newBufferedWriter(directory.resolve("flink.log"), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        log.write(line);
                        log.newLine();
                        log.flush();
                        String json = line.replaceFirst("^\\d+> ", "");
                        if (json.startsWith("{")) {
                            JsonObject result = JsonParser.parseString(json).getAsJsonObject();
                            if (result.has("label") && "quality-series".equals(result.get("label").getAsString())) {
                                return result;
                            }
                        }
                    }
                    throw new IllegalStateException("Flink exited before producing the completed PFD window; see "
                            + directory.resolve("flink.log"));
                }
            }).get(90, TimeUnit.SECONDS);
            JsonObject expected = JsonParser.parseString(
                    "{\"label\":\"quality-series\",\"timestamps\":[1000.0,2000.0],\"values\":[-1.0,1.0]}")
                    .getAsJsonObject();
            if (!expected.equals(output)) {
                throw new IllegalStateException("Unexpected completed PFD window: " + output);
            }
            Files.writeString(directory.resolve("result.json"), output.toString(), StandardCharsets.UTF_8);
            System.out.println("PFD acceptance passed: " + output);
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Flink process did not stop");
                }
            }
            reader.shutdownNow();
        }
    }
}
