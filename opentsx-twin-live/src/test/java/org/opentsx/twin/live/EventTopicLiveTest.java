package org.opentsx.twin.live;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinEvent;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTopicLiveTest {

    private static StudioScenario h2() {
        return Scenarios.all().stream().filter(s -> s.id().equals("h2-chain")).findFirst().orElseThrow();
    }

    @Test
    void topicNameFollowsTheTwinContractAndIsStable() {
        StudioScenario s = h2();
        RunResult a = EventPublisher.referenceRun(s), b = EventPublisher.referenceRun(s);
        assertEquals(a.runId(), b.runId(), "the Studio page and the publisher must arrive at the same topic");
        String topic = EventTopic.name(s.plan().twin(), a.runId());
        assertEquals("twin.h2-chain." + a.runId() + ".events", topic);
        assertTrue(EventTopic.isEventTopic(topic));
        assertFalse(EventTopic.isEventTopic("tsx.windpark.episodes.v2"));
        assertFalse(EventTopic.isEventTopic("twin.h2-chain.run.msgs"));
        assertFalse(EventTopic.isEventTopic("twin.h2-chain.../x.events"));
    }

    @Test
    void publishesEveryEventInRunOrderKeyedByAtom() throws Exception {
        StudioScenario s = h2();
        RunResult run = EventPublisher.referenceRun(s);
        MemoryTopics topics = new MemoryTopics();
        String topic = new EventPublisher(topics).publish(s.plan(), run, null);

        List<Topics.Rec> recs = topics.open(topic, Topics.EARLIEST).poll(Duration.ofMillis(10));
        assertEquals(run.events().size(), recs.size());
        assertFalse(recs.isEmpty());
        Instant last = Instant.MIN;
        for (int i = 0; i < recs.size(); i++) {
            JsonNode v = EventTopic.JSON.readTree(recs.get(i).value());
            assertEquals(i, recs.get(i).offset());
            assertEquals(recs.get(i).key(), v.get("atom").asText());
            assertEquals(run.runId(), v.get("run_id").asText());
            Instant t = Instant.parse(v.get("t").asText());
            assertFalse(t.isBefore(last), "events are in run order");
            last = t;
        }
        TwinEvent first = run.events().stream().min(java.util.Comparator.comparingLong(TwinEvent::tMicros)).orElseThrow();
        JsonNode v0 = EventTopic.JSON.readTree(recs.get(0).value());
        assertEquals(first.type(), v0.get("type").asText());
        assertEquals(first.k(), v0.get("k").asInt());
        first.attrs().forEach((k, val) -> assertEquals(val, v0.get("attrs").get(k).asText()));
    }

    @Test
    void pacedPublishingSpreadsTheRunOverTheDuration() throws Exception {
        StudioScenario s = h2();
        RunResult run = EventPublisher.referenceRun(s);
        List<Long> sleeps = new ArrayList<>();
        new EventPublisher(new MemoryTopics(), sleeps::add).publish(s.plan(), run, Duration.ofSeconds(360));
        // six hours in 360 s: one second per minute; the valve closes at 01:00, so the first wait is 60 s
        assertEquals(60_000, sleeps.get(0), 1_000);
        assertEquals(360_000, sleeps.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void bridgeStreamsATopicFromTheStartAndThenLive() throws Exception {
        MemoryTopics topics = new MemoryTopics();
        String topic = "twin.demo.run-1.events";
        topics.append(topic, "line", "{\"type\":\"a\"}".getBytes(StandardCharsets.UTF_8), EventTopic.SCHEMA);
        topics.append(topic, "ely", "{\"type\":\"b\"}".getBytes(StandardCharsets.UTF_8), EventTopic.SCHEMA);
        try (EventBridge bridge = new EventBridge(topics, 0, "*", 4)) {
            BlockingQueue<JsonNode> got = new LinkedBlockingQueue<>();
            Thread reader = follow(bridge, topic, "from=earliest", null, got);

            assertEquals("a", next(got).get("value").get("type").asText());
            JsonNode second = next(got);
            assertEquals(1, second.get("offset").asLong());
            assertEquals("ely", second.get("key").asText());
            assertEquals(topic, second.get("topic").asText());

            topics.append(topic, "line", "{\"type\":\"c\"}".getBytes(StandardCharsets.UTF_8), EventTopic.SCHEMA);
            assertEquals("c", next(got).get("value").get("type").asText());
            reader.interrupt();

            // a browser that reconnects continues behind the last offset it saw
            BlockingQueue<JsonNode> again = new LinkedBlockingQueue<>();
            Thread r2 = follow(bridge, topic, "from=earliest", "1", again);
            assertEquals(2, next(again).get("offset").asLong());
            r2.interrupt();

            // latest: nothing from the past
            BlockingQueue<JsonNode> live = new LinkedBlockingQueue<>();
            Thread r3 = follow(bridge, topic, "from=latest", null, live);
            assertNull(live.poll(700, TimeUnit.MILLISECONDS));
            topics.append(topic, "line", "{\"type\":\"d\"}".getBytes(StandardCharsets.UTF_8), EventTopic.SCHEMA);
            assertEquals("d", next(live).get("value").get("type").asText());
            r3.interrupt();
        }
    }

    @Test
    void aReaderCanOpenTheTopicBeforeTheFirstEvent() throws Exception {
        StudioScenario s = h2();
        RunResult run = EventPublisher.referenceRun(s);
        MemoryTopics topics = new MemoryTopics();
        String topic = EventTopic.name(s.plan().twin(), run.runId());
        java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread publisher = new Thread(() -> {
            try {
                new EventPublisher(topics, ms -> {
                    waiting.countDown();
                    go.await();
                }).publish(s.plan(), run, Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        publisher.setDaemon(true);
        publisher.start();
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        assertEquals(0, topics.size(topic));
        try (EventBridge bridge = new EventBridge(topics, 0, "*", 4)) {
            BlockingQueue<JsonNode> got = new LinkedBlockingQueue<>();
            Thread reader = follow(bridge, topic, "from=earliest", null, got);
            assertNull(got.poll(500, TimeUnit.MILLISECONDS), "connected, nothing published yet");
            go.countDown();
            assertEquals(0, next(got).get("offset").asLong());
            reader.interrupt();
        }
    }

    @Test
    void bridgeServesOnlyEventTopicsOfTwins() throws Exception {
        MemoryTopics topics = new MemoryTopics();
        topics.append("tsx.windpark.episodes.v2", "k", new byte[] {1}, "x");
        try (EventBridge bridge = new EventBridge(topics, 0, "https://studio.example", 4)) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + bridge.port();
            assertEquals(400, status(http, base + "/v1/topics/tsx.windpark.episodes.v2/events"));
            assertEquals(404, status(http, base + "/v1/topics/twin.none.run-1.events/events"));
            assertEquals(400, status(http, base + "/v1/topics/twin.demo.run-1.events/events?from=yesterday"));
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("https://studio.example", health.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        }
    }

    private static int status(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static JsonNode next(BlockingQueue<JsonNode> q) throws InterruptedException {
        JsonNode n = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(n, "no message within 5 s");
        return n;
    }

    /** Reads the stream on a daemon thread and queues the data of every message. */
    private static Thread follow(EventBridge bridge, String topic, String query, String lastEventId,
                                 BlockingQueue<JsonNode> out) {
        Thread t = new Thread(() -> {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + bridge.port() + "/v1/topics/" + topic + "/events?" + query));
                if (lastEventId != null) {
                    req.header("Last-Event-ID", lastEventId);
                }
                HttpResponse<java.io.InputStream> res = HttpClient.newHttpClient()
                        .send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, res.statusCode());
                assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
                try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            out.add(EventTopic.JSON.readTree(line.substring(6)));
                        }
                    }
                }
            } catch (Exception e) {
                // stream closed or test over
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
