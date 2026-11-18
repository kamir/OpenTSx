package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.wayang.rust.RustRollout;
import org.opentsx.wayang.rust.RustWorkerClient;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class RustFencingDenialProbe {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private RustFencingDenialProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("prepare <worker-origin> <exact-requestText>");
            System.out.println("abort <worker-origin> <requestHash>");
            System.out.println("activate <worker-origin> <requestHash>");
            System.out.println("deny <read-bootstrap> <publisher-origin> <private-prepared-checkpoint.json> <current-epoch> <report.json>");
        } else if (args.length == 3 && Set.of("prepare", "abort", "activate").contains(args[0])) {
            String body = args[0].equals("prepare") ? args[2] : "";
            String route = "/v1/" + args[0];
            if (!args[0].equals("prepare")) {
                if (!args[2].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid worker request hash");
                route += "/" + args[2];
            } else if (body.getBytes(StandardCharsets.UTF_8).length > 65_536) {
                throw new IllegalArgumentException("worker request exceeds its byte limit");
            }
            JsonNode status = RustWorkerClient.http().post(new RustRollout.Worker("probe", args[1]), route, body);
            if (!status.path("schema").asText().equals("opentsx.local-worker-status.v1")) throw new IllegalStateException("unexpected worker status schema");
            System.out.println(RustRollout.stringify(status));
        } else if (args.length == 6 && args[0].equals("deny")) {
            deny(args[1], args[2], Path.of(args[3]), args[4], Path.of(args[5]));
        } else {
            throw new IllegalArgumentException("invalid fencing probe arguments; use --help");
        }
    }

    static JsonNode candidate(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 85_000_000
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalStateException("prepared snapshot is not a private regular checkpoint");
        }
        JsonNode envelope;
        try { envelope = RustRollout.json(Files.readString(path, StandardCharsets.UTF_8)); }
        catch (RuntimeException error) { throw new IllegalStateException("prepared snapshot is not valid JSON"); }
        if (!envelope.path("schema").asText().equals("opentsx.local-increment-checkpoint.v1")
                || !envelope.path("checksum").asText().equals(ExecutionAssignments.sha256(RustRollout.stringify(envelope.path("payload"))))) {
            throw new IllegalStateException("prepared snapshot checksum or schema mismatch");
        }
        JsonNode candidate = envelope.path("payload").path("candidate");
        JsonNode grant = candidate.path("publicationGrant");
        JsonNode records = candidate.path("prepared").path("records");
        if (!candidate.path("phase").asText().equals("prepared") || !candidate.path("publicationStarted").isBoolean()
                || candidate.path("publicationStarted").asBoolean() || !records.isArray() || records.isEmpty()
                || !candidate.path("requestText").isTextual() || !grant.path("schema").asText().equals("opentsx.publication-grant.v1")
                || !grant.path("token").isTextual() || !grant.path("token").asText().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("snapshot does not contain an unpublished prepared candidate with a captured grant");
        }
        positive(grant.path("epoch").asText());
        JsonNode request = RustRollout.json(candidate.path("requestText").asText());
        try { ExecutionAssignments.validate(request.path("assignmentText").asText()); }
        catch (RuntimeException error) { throw new IllegalStateException("prepared snapshot has an invalid complete topic assignment"); }
        JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
        JsonNode receipt = candidate.path("prepared").path("completion").path("receipt");
        if (!request.path("schema").asText().equals(RustRollout.INCREMENT_SCHEMA)
                || !grant.path("deployment").equals(assignment.path("deployment")) || !grant.path("node").equals(assignment.path("node"))
                || assignment.path("inputs").size() != 1 || !grant.path("inputTopic").equals(assignment.path("inputs").get(0).path("topic"))
                || !grant.path("outputTopic").equals(request.path("sink").path("topic"))
                || !ExecutionAssignments.validTopic(grant.path("outputTopic").asText())
                || !receipt.path("requestHash").asText().equals(ExecutionAssignments.sha256(candidate.path("requestText").asText()))
                || !receipt.path("outputRecords").isIntegralNumber() || receipt.path("outputRecords").asLong() != records.size()
                || !receipt.path("outputDigest").asText().equals(digest(records))) {
            throw new IllegalStateException("captured grant and prepared bytes do not match the complete topic request");
        }
        return candidate;
    }

    private static void deny(String bootstrap, String publisher, Path snapshot, String epoch, Path reportPath) throws Exception {
        JsonNode candidate = candidate(snapshot);
        JsonNode grant = candidate.path("publicationGrant");
        if (positive(epoch) <= positive(grant.path("epoch").asText())) throw new IllegalArgumentException("current publication epoch must exceed the captured epoch");
        URI proxy = URI.create("http://" + bootstrap);
        URI publication = URI.create(publisher);
        if (!"kafscale-read".equals(proxy.getHost()) || proxy.getPort() < 1 || proxy.getPort() > 65_535 || !proxy.getPath().isEmpty()
                || proxy.getUserInfo() != null || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                || !publisher.equals("http://wayang-publication:8096")) throw new IllegalArgumentException("denial probe requires the Compose read proxy and publication origin");
        String output = grant.path("outputTopic").asText();
        int count = Math.toIntExact(positive(RustRollout.json(candidate.path("requestText").asText()).path("sink").path("partitionCount").asText()));
        Map<Integer, Long> before = offsets(bootstrap, output, count);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        for (String attemptedEpoch : List.of(grant.path("epoch").asText(), epoch)) {
            ObjectNode body = JSON.objectNode().put("epoch", attemptedEpoch).put("requestText", candidate.path("requestText").asText());
            body.set("records", candidate.path("prepared").path("records"));
            HttpRequest request = HttpRequest.newBuilder(publication.resolve("/v1/publish")).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + grant.path("token").asText()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(RustRollout.stringify(body), StandardCharsets.UTF_8)).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 403) throw new IllegalStateException("revoked publication did not receive HTTP 403");
        }
        denyProduce(bootstrap, output, candidate.path("prepared").path("records").get(0));
        denyConnection("172.29.73.2", 19092);
        denyConnection("172.29.75.2", proxy.getPort());
        denyConnection("host.docker.internal", proxy.getPort());
        Map<Integer, Long> after = offsets(bootstrap, output, count);
        if (!before.equals(after)) throw new IllegalStateException("denied attempts changed output end offsets");
        ObjectNode report = JSON.objectNode().put("schema", "opentsx.fencing-denials.v1")
                .put("requestHash", ExecutionAssignments.sha256(candidate.path("requestText").asText()))
                .put("capturedEpoch", grant.path("epoch").asText()).put("currentEpoch", epoch).put("outputTopic", output)
                .put("oldGrantHttpStatus", 403).put("forgedEpochHttpStatus", 403).put("readProxyProduceDenied", true)
                .put("directBrokerBlocked", true).put("writeProxyBlocked", true).put("hostGatewayBlocked", true).put("outputOffsetsUnchanged", true);
        ObjectNode positions = report.putObject("endOffsets");
        after.forEach((partition, end) -> positions.put(Integer.toString(partition), Long.toString(end)));
        Files.writeString(reportPath, RustRollout.stringify(report) + "\n", StandardCharsets.UTF_8);
        System.out.println(RustRollout.stringify(report));
    }

    private static Map<Integer, Long> offsets(String bootstrap, String output, int count) {
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            if (consumer.partitionsFor(output, TIMEOUT).size() != count) throw new IllegalStateException("output topic partition inventory differs");
            List<TopicPartition> partitions = new ArrayList<>();
            for (int partition = 0; partition < count; partition++) partitions.add(new TopicPartition(output, partition));
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, TIMEOUT);
            Map<Integer, Long> result = new LinkedHashMap<>();
            for (TopicPartition partition : partitions) result.put(partition.partition(), ends.get(partition));
            return result;
        }
    }

    private static void denyProduce(String bootstrap, String output, JsonNode record) throws Exception {
        var configuration = KafScaleClients.producerProps(bootstrap);
        configuration.put(ProducerConfig.CLIENT_ID_CONFIG, "172.29.73.11");
        configuration.put(ProducerConfig.RETRIES_CONFIG, 0);
        configuration.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        configuration.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        configuration.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
        configuration.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(configuration, new StringSerializer(), new ByteArraySerializer())) {
            ProducerRecord<String, byte[]> value = new ProducerRecord<>(output, null, record.path("timestamp").asLong(), record.path("key").asText(), payload(record.path("payload")));
            try {
                producer.send(value).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof AuthorizationException) return;
                throw new IllegalStateException("read proxy Produce failed without an authorization denial");
            }
        }
        throw new IllegalStateException("read proxy accepted Produce with a forged client identity");
    }

    private static void denyConnection(String host, int port) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        try (Socket socket = new Socket()) {
            try { socket.connect(new InetSocketAddress(address, port), 2_000); }
            catch (IOException error) { return; }
        }
        throw new IllegalStateException("worker network reached a forbidden broker path");
    }

    static String digest(JsonNode records) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            for (JsonNode record : records) {
                if (!record.path("key").isTextual() || !record.path("key").asText().matches("[0-9a-f]{16}")
                        || !record.path("timestamp").isIntegralNumber() || !record.path("timestamp").canConvertToLong()
                        || record.path("timestamp").asLong() < 1) throw new IllegalStateException("invalid saved output record");
                byte[] key = record.path("key").asText().getBytes(StandardCharsets.UTF_8);
                byte[] payload = payload(record.path("payload"));
                output.writeLong(key.length); output.write(key); output.writeLong(record.path("timestamp").asLong());
                output.writeLong(payload.length); output.write(payload);
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static byte[] payload(JsonNode value) {
        if (!value.isArray() || value.size() > 1_048_576) throw new IllegalStateException("invalid saved payload size");
        byte[] result = new byte[value.size()];
        for (int index = 0; index < result.length; index++) {
            JsonNode octet = value.get(index);
            if (!octet.isIntegralNumber() || !octet.canConvertToInt() || octet.asInt() < 0 || octet.asInt() > 255) throw new IllegalStateException("invalid saved payload byte");
            result[index] = (byte) octet.asInt();
        }
        return result;
    }

    private static long positive(String text) {
        long value = Long.parseLong(text);
        if (value < 1 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("publication epoch or partition count must be canonical positive decimal");
        return value;
    }
}
