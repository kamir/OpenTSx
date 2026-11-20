package org.opentsx.wayang.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Track B of one B4.2 condition: starts {@code tsx-runner serve} as the measured process and sends it one
 * {@code opentsx.local-increment.v1} request per interval over the input written since the previous one. Each request
 * carries the whole chain DAG; the worker keeps operator state between increments.
 */
public final class BoardTrackB {

    static final String NODE = "board";
    static final String SITE = "board";
    static final String INCREMENT_SCHEMA = "opentsx.local-increment.v1";
    static final List<String> LIMITS = List.of("records", "payloadBytes", "inputBytes", "samples", "outputBytes", "series", "stateBytes");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(30);

    /** One partition's cut {@code [from, to)}. */
    record Position(int partition, long from, long to) {
    }

    private BoardTrackB() {
    }

    /** The increment request for {@code positions}; {@code previous} is the hash of the preceding request or null. */
    static String request(String chain, String dag, JsonNode capabilities, String bootstrap, String input, String output,
                          int outputPartitions, List<Position> positions, long sequence, String previous) {
        ObjectNode assignment = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                .put("deployment", "board-" + chain).put("generation", "1").put("assignment", "assignment-1")
                .put("attempt", "attempt-1").put("fence", "1").put("fragment", "fragment-1")
                .put("site", capabilities.path("site").asText()).put("node", capabilities.path("node").asText())
                .put("runtime", "rust").put("catalog", "tsx-catalog/1")
                .put("artifactDigest", capabilities.path("artifactDigest").asText())
                .put("specHash", ExecutionAssignments.sha256(dag)).put("sourceDagText", dag)
                .put("fragmentHash", ExecutionAssignments.sha256(dag)).put("fragmentText", dag).put("mode", "bounded");
        ObjectNode in = assignment.putArray("inputs").addObject().put("binding", "local").put("topic", input)
                .put("partitionCount", Integer.toString(positions.size()));
        ArrayNode cut = in.putArray("positions");
        for (Position p : positions) {
            cut.addObject().put("partition", Integer.toString(p.partition())).put("from", Long.toString(p.from()))
                    .put("to", Long.toString(p.to()));
        }
        String envelope = assignment.toString();
        ExecutionAssignments.validate(envelope);
        ObjectNode request = JSON.createObjectNode().put("schema", INCREMENT_SCHEMA).put("assignmentText", envelope)
                .put("createdAtMicros", Long.toString(BoardFeed.BASE_MICROS)).put("sequence", Long.toString(sequence));
        if (previous == null) request.putNull("previousRequestHash"); else request.put("previousRequestHash", previous);
        ObjectNode binding = request.putObject("bindings").putObject("local").put("bootstrap", bootstrap);
        binding.putArray("inputs").add(input);
        binding.putArray("outputs").add(output);
        request.putObject("sink").put("name", BoardChains.SINK).put("binding", "local").put("topic", output)
                .put("partitionCount", Integer.toString(outputPartitions));
        boolean catalog = chain.equals("stats");
        JsonNode extended = capabilities.path("catalogExecution").path("limits");
        ObjectNode limits = request.putObject("limits");
        for (String name : LIMITS) {
            JsonNode value = catalog && extended.has(name) ? extended.path(name) : capabilities.path("limits").path(name);
            if (!value.canConvertToLong() || value.asLong() < 1) throw new IllegalArgumentException("worker reports no " + name + " limit");
            limits.put(name, value.asLong());
        }
        if (catalog) request.putObject("context");
        return request.toString();
    }

    /** Cuts {@code [from, end)} per partition down to at most {@code budget} records, partitions in ascending order. */
    static List<Position> cut(long[] from, long[] end, long budget) {
        List<Position> out = new ArrayList<>(from.length);
        long left = budget;
        for (int p = 0; p < from.length; p++) {
            long take = Math.min(end[p] - from[p], Math.max(left, 0));
            out.add(new Position(p, from[p], from[p] + take));
            left -= take;
        }
        return out;
    }

    public static long run(Path runner, String listen, Path state, String profile, String bootstrap, TopicLayout layout,
                           String chain, int series, long seed, Duration interval, Duration duration, Path log,
                           Path lagFile, BoardEvents events) throws Exception {
        String dag = BoardChains.dag(chain, layout, new BoardFeed(series, seed).seriesIds());
        String input = layout.episodes();
        String output = BoardChains.output(chain, layout);
        AtomicLong processed = new AtomicLong(-1);
        Process process = new ProcessBuilder(runner.toString(), "serve", "--listen", listen, "--node", NODE,
                "--site", SITE, "--bootstrap", bootstrap, "--state", state.toString(), "--profile", profile)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        events.emit("started", Map.of("pid", process.pid(), "track", "B", "chain", chain, "series", series));
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        URI base = URI.create("http://" + listen);
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             OutputWatch watch = new OutputWatch(bootstrap, output, events);
             LagWatch lag = lagFile == null ? null : new LagWatch(bootstrap, input, lagFile,
                     begin -> processed.get() < 0 ? begin : processed.get(), events)) {
            JsonNode capabilities = awaitCapabilities(http, base, process);
            List<TopicPartition> inputs = partitions(consumer, input);
            int outputPartitions = partitions(consumer, output).size();
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(inputs, METADATA_TIMEOUT);
            long[] from = inputs.stream().mapToLong(begin::get).toArray();
            long budget = capabilities.path("limits").path("records").asLong();
            long deadline = System.nanoTime() + duration.toNanos();
            long sequence = 0;
            long records = 0;
            String previous = null;
            while (System.nanoTime() < deadline) {
                long started = System.nanoTime();
                Map<TopicPartition, Long> ends = consumer.endOffsets(inputs, METADATA_TIMEOUT);
                long[] end = inputs.stream().mapToLong(ends::get).toArray();
                List<Position> positions = cut(from, end, budget);
                long size = positions.stream().mapToLong(p -> p.to() - p.from()).sum();
                if (size > 0) {
                    String text = request(chain, dag, capabilities, bootstrap, input, output, outputPartitions,
                            positions, ++sequence, previous);
                    String hash = ExecutionAssignments.sha256(text);
                    events.firstFetch();
                    expect(post(http, base.resolve("/v1/prepare"), text), "prepared");
                    expect(post(http, base.resolve("/v1/activate/" + hash), ""), "completed");
                    previous = hash;
                    records += size;
                    for (Position p : positions) from[p.partition()] = p.to();
                    processed.set(java.util.Arrays.stream(from).sum());
                    events.emit("increment", Map.of("sequence", sequence, "records", size));
                }
                long rest = interval.toNanos() - (System.nanoTime() - started);
                if (rest > 0) TimeUnit.NANOSECONDS.sleep(Math.min(rest, Math.max(deadline - System.nanoTime(), 0)));
            }
            events.emit("stopped", Map.of("records", records, "increments", sequence));
            return records;
        } finally {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
    }

    private static List<TopicPartition> partitions(KafkaConsumer<byte[], byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, METADATA_TIMEOUT);
        if (infos == null || infos.isEmpty()) throw new IllegalStateException("topic " + topic + " has no partitions");
        return infos.stream().map(i -> new TopicPartition(topic, i.partition()))
                .sorted((a, b) -> Integer.compare(a.partition(), b.partition())).toList();
    }

    private static JsonNode awaitCapabilities(HttpClient http, URI base, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        String last = "no answer";
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException("tsx-runner exited with " + process.exitValue());
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/v1/capabilities"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return JSON.readTree(r.body());
                last = "status " + r.statusCode() + ": " + r.body();
            } catch (IOException e) {
                last = e.toString();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("tsx-runner did not report its capabilities: " + last);
    }

    private static JsonNode post(HttpClient http, URI uri, String body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException(uri.getPath() + " answered " + r.statusCode() + ": " + r.body());
        return JSON.readTree(r.body());
    }

    private static void expect(JsonNode status, String state) {
        if (!status.path("state").asText().equals(state)) {
            throw new IllegalStateException("worker state is " + status.path("state").asText() + ", expected " + state
                    + (status.has("error") ? ": " + status.path("error").asText() : ""));
        }
    }
}
