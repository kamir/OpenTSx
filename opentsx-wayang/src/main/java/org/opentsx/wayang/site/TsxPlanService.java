package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class TsxPlanService implements AutoCloseable {
    public static final int MAX_AGGREGATE_BYTES = 16 * 1024 * 1024;
    private final PublicKey driverKey;
    private final Map<String, PublicKey> siteKeys;
    private final Map<String, String> certificateSites;
    private final Map<String, ConformanceHandshake.QualifiedRuntime> qualified = new HashMap<>();
    private final Map<String, Integer> inFlight = new HashMap<>();
    private final SiteState state;
    private final Clock clock;
    private final BiConsumer<RunnerPlan, JsonNode> statusConsumer;
    private final BiConsumer<RunnerPlan, JsonNode> aggregateConsumer;
    private HttpsServer server;
    private ExecutorService threads;

    public static final class HttpFailure extends IllegalArgumentException {
        private final int status;
        public HttpFailure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    public TsxPlanService(PublicKey driverKey, Map<String, PublicKey> siteKeys, Map<String, String> certificateSites,
                          Path durableDirectory, Clock clock, BiConsumer<RunnerPlan, JsonNode> statusConsumer,
                          BiConsumer<RunnerPlan, JsonNode> aggregateConsumer) {
        this.driverKey = java.util.Objects.requireNonNull(driverKey);
        this.siteKeys = Map.copyOf(siteKeys);
        this.certificateSites = Map.copyOf(certificateSites);
        for (var entry : certificateSites.entrySet()) {
            if (!entry.getKey().matches("[0-9a-f]{64}") || !siteKeys.containsKey(entry.getValue())) {
                throw new IllegalArgumentException("certificate-to-site pin is invalid");
            }
        }
        this.clock = java.util.Objects.requireNonNull(clock);
        this.statusConsumer = java.util.Objects.requireNonNull(statusConsumer);
        this.aggregateConsumer = java.util.Objects.requireNonNull(aggregateConsumer);
        state = new SiteState(durableDirectory);
    }

    public synchronized void registerQualifiedRuntime(String site, String node, String version, String digest, String stateAbi) {
        registerQualifiedRuntime(site, node, version, digest, stateAbi, "java");
    }

    public synchronized void registerQualifiedRuntime(String site, String node, String version, String digest, String stateAbi, String runtime) {
        knownSite(site);
        var pin = new ConformanceHandshake.QualifiedRuntime(site, node, version, digest, stateAbi, runtime);
        var previous = qualified.putIfAbsent(site, pin);
        if (previous != null && !previous.equals(pin)) throw new IllegalArgumentException("a runtime pin cannot change while the service is running");
    }

    public synchronized void admitAggregateInventory(String site, String job, org.opentsx.wayang.federation.AggregateInventory inventory) {
        state.check();
        RunnerPlan plan = latest(site, job);
        admitted(site, plan);
        inventory.requireContract(plan.job(), plan.planVersion(), plan.document().path("specHash").asText(),
                plan.document().path("federation").path("expectedAssignments"), plan.document().path("federation").path("scopes"));
        var pin = runtime(site);
        if (pin.runtime().equals("rust")) inventory.admitRustBounded(plan.exactAssignmentText(), pin);
        else inventory.admit(plan.exactAssignmentText());
    }

    public synchronized String publish(String compact) {
        state.check();
        RunnerPlan plan = RunnerPlan.verify(compact, driverKey);
        knownSite(plan.site());
        var assignmentTexts = new ArrayList<String>();
        for (JsonNode member : plan.document().path("federation").path("expectedAssignments")) knownSite(member.path("site").asText());
        for (JsonNode otherSite : state.value.path("sites")) for (JsonNode item : otherSite.path("deliveries")) {
            if (!item.path("job").asText().equals(plan.job()) || item.path("planVersion").asInt() != plan.planVersion()) continue;
            RunnerPlan other = RunnerPlan.verify(item.path("plan").asText(), driverKey);
            if (!other.document().path("federation").equals(plan.document().path("federation"))
                    || !other.assignment().path("sourceDagText").equals(plan.assignment().path("sourceDagText"))
                    || !other.assignment().path("catalog").equals(plan.assignment().path("catalog"))) {
                throw new HttpFailure(409, "federation generation contracts differ");
            }
            if (!other.site().equals(plan.site())) assignmentTexts.add(other.assignmentText());
        }
        assignmentTexts.add(plan.assignmentText());
        try { org.opentsx.model.contracts.ExecutionAssignments.validateSet(assignmentTexts); }
        catch (IllegalArgumentException error) { throw new HttpFailure(409, "federation assignment ownership conflicts"); }
        ObjectNode site = state.value.with("sites").with(plan.site());
        ObjectNode deliveries = site.with("deliveries");
        RunnerPlan previousPlan = null;
        JsonNode previousDelivery = null;
        for (JsonNode item : deliveries) {
            if (item.path("job").asText().equals(plan.job()) && item.path("planVersion").asInt() == plan.planVersion()) {
                if (!item.path("commandHash").asText().equals(plan.commandHash())) throw new HttpFailure(409, "published command identity conflicts");
                return item.path("sequence").asText();
            }
            if (item.path("job").asText().equals(plan.job()) && (previousPlan == null || item.path("planVersion").asInt() > previousPlan.planVersion())) {
                previousPlan = RunnerPlan.verify(item.path("plan").asText(), driverKey);
                previousDelivery = item;
            }
        }
        if (previousPlan != null) {
            JsonNode stopped = state.value.path("statuses").path(plan.site()).path(plan.job());
            if (plan.planVersion() < previousPlan.planVersion() || inFlight.containsKey(callbackKey(plan))
                    || !stopped.path("state").asText().equals("stopped")
                    || stopped.path("planVersion").asInt() != previousPlan.planVersion()
                    || !previousDelivery.path("ack").path("result").asText().equals("accepted")) {
                throw new HttpFailure(409, "plan.busy: the previous assignment has not retired");
            }
        }
        for (var sites = state.value.path("sites").fields(); sites.hasNext();) {
            var otherSite = sites.next();
            JsonNode previous = null;
            for (JsonNode item : otherSite.getValue().path("deliveries")) {
                if (item.path("job").asText().equals(plan.job()) && (previous == null || item.path("planVersion").asInt() > previous.path("planVersion").asInt())) previous = item;
            }
            if (previous == null || previous.path("planVersion").asInt() == plan.planVersion()) continue;
            JsonNode stopped = state.value.path("statuses").path(otherSite.getKey()).path(plan.job());
            if (previous.path("planVersion").asInt() > plan.planVersion() || inFlight.containsKey(otherSite.getKey() + "|" + plan.job())
                    || !stopped.path("state").asText().equals("stopped") || !stopped.path("planVersion").equals(previous.path("planVersion"))
                    || !previous.path("ack").path("result").asText().equals("accepted")) {
                throw new HttpFailure(409, "plan.busy: the previous job generation has not retired");
            }
        }
        long previous = SiteJson.decimal(site.path("sequence").asText("0"), false);
        if (previous == Long.MAX_VALUE) throw new IllegalStateException("site delivery sequence is exhausted");
        String sequence = Long.toString(previous + 1);
        ObjectNode delivery = SiteJson.JSON.createObjectNode().put("sequence", sequence).put("job", plan.job())
                .put("planVersion", plan.planVersion()).put("commandHash", plan.commandHash()).put("plan", compact);
        deliveries.set(sequence, delivery);
        site.put("sequence", sequence);
        state.save();
        return sequence;
    }

    public synchronized JsonNode challenge(String site, String job) {
        state.check();
        return ConformanceHandshake.challenge(latest(site, job), runtime(site));
    }

    public synchronized JsonNode conformance(String site, String signedReport) {
        state.check();
        knownSite(site);
        byte[] payload = SignedJws.verify(signedReport, siteKeys.get(site));
        JsonNode report = SiteJson.parse(payload, SiteJson.MAX_BYTES);
        String job = SiteJson.id(report, "job");
        RunnerPlan plan = latest(site, job);
        Set<String> otherSeries = new HashSet<>();
        JsonNode admissions = state.value.path("admissions").path(job);
        admissions.fields().forEachRemaining(entry -> {
            JsonNode other = entry.getValue();
            if (!entry.getKey().equals(site) && other.path("planVersion").asInt() == plan.planVersion()
                    && clock.instant().isBefore(RunnerPlan.instant(other, "expires"))) {
                other.path("series").path("ids").forEach(id -> otherSeries.add(id.asText()));
            }
        });
        JsonNode result = ConformanceHandshake.validate(payload, plan, runtime(site), otherSeries, clock);
        if (result.path("result").asText().equals("accepted")) {
            state.value.with("admissions").with(job).set(site, report);
            state.save();
        }
        return result;
    }

    public synchronized JsonNode next(String site, String after) {
        state.check();
        knownSite(site);
        long cursor = SiteJson.decimal(after, false);
        ObjectNode response = SiteJson.JSON.createObjectNode().put("next", after);
        var returned = response.putArray("deliveries");
        for (JsonNode delivery : state.value.path("sites").path(site).path("deliveries")) {
            if (SiteJson.decimal(delivery.path("sequence").asText(), true) <= cursor) continue;
            RunnerPlan plan = RunnerPlan.verify(delivery.path("plan").asText(), driverKey);
            admitted(site, plan);
            ObjectNode value = delivery.deepCopy();
            value.remove("commandHash");
            value.remove("ack");
            returned.add(value);
            response.put("next", delivery.path("sequence").asText());
            break;
        }
        return response;
    }

    public synchronized void ack(String site, String sequence, JsonNode acknowledgement) {
        state.check();
        knownSite(site);
        SiteJson.decimal(sequence, true);
        ObjectNode delivery = delivery(site, sequence);
        String result = SiteJson.text(acknowledgement, "result");
        if (result.equals("accepted")) SiteJson.fields(acknowledgement, "job", "planVersion", "commandHash", "result");
        else if (result.equals("refused")) {
            SiteJson.fields(acknowledgement, "job", "planVersion", "commandHash", "result", "code", "message");
            if (!Set.of("plan.signature", "plan.expired", "plan.version", "plan.site", "plan.catalog", "plan.invalid", "plan.policy", "plan.busy")
                    .contains(SiteJson.text(acknowledgement, "code"))) throw new HttpFailure(400, "unknown refusal code");
            SiteJson.text(acknowledgement, "message");
        } else throw new HttpFailure(400, "invalid acknowledgement result");
        for (String name : Set.of("job", "planVersion", "commandHash")) {
            if (!delivery.path(name).equals(acknowledgement.path(name))) throw new HttpFailure(409, "acknowledgement identity differs");
        }
        if (delivery.has("ack") && !delivery.get("ack").equals(acknowledgement)) throw new HttpFailure(409, "acknowledgement content conflicts");
        delivery.set("ack", acknowledgement.deepCopy());
        state.save();
    }

    public void status(String site, String job, JsonNode value) {
        if (value == null) throw new HttpFailure(400, "status must be an object");
        JsonNode copy = value.deepCopy();
        RunnerPlan plan;
        synchronized (this) {
            state.check();
            plan = latest(site, job);
            currentVersion(copy, plan);
            try { SiteStatus.validate(copy, plan, runtime(site)); }
            catch (IllegalArgumentException error) { throw new HttpFailure(400, error.getMessage()); }
            JsonNode previous = state.value.path("statuses").path(site).path(job);
            if (previous.path("planVersion").asInt() == plan.planVersion()
                    && Set.of("stopped", "failed").contains(previous.path("state").asText())
                    && java.util.Arrays.equals(SiteJson.bytes(previous), SiteJson.bytes(copy))) return;
            admitted(site, plan);
            if (previous.path("planVersion").asInt() == plan.planVersion()
                    && Set.of("stopped", "failed").contains(previous.path("state").asText())
                    && !copy.path("state").equals(previous.path("state"))) throw new HttpFailure(409, "a terminal assignment cannot change state");
            beginCallback(plan);
        }
        try {
            statusConsumer.accept(plan, copy);
            synchronized (this) {
                state.value.with("statuses").with(site).set(job, copy.deepCopy());
                state.save();
            }
        } finally {
            synchronized (this) { endCallback(plan); }
        }
    }

    public void aggregates(String site, String job, JsonNode value) {
        if (value == null) throw new HttpFailure(400, "aggregate delivery must be an object");
        JsonNode copy = value.deepCopy();
        RunnerPlan plan;
        synchronized (this) {
            state.check();
            plan = latest(site, job);
            admitted(site, plan);
            if (SiteJson.bytes(copy).length > MAX_AGGREGATE_BYTES || !copy.path("schema").asText().equals("opentsx.aggregate-delivery.v1")
                    || !copy.path("records").isArray() || copy.path("records").isEmpty() || copy.path("records").size() > 256) {
                throw new HttpFailure(400, "invalid aggregate delivery");
            }
            for (JsonNode record : copy.path("records")) {
                JsonNode headers = record.path("headers");
                if (!headers.path("tsx-site").asText().equals(site) || !headers.path("tsx-job").asText().equals(job)) {
                    throw new HttpFailure(403, "aggregate belongs to another site or job");
                }
                if (!headers.path("tsx-plan-version").asText().equals(Integer.toString(plan.planVersion()))) {
                    throw new HttpFailure(409, "aggregate version is not current");
                }
            }
            beginCallback(plan);
        }
        try { aggregateConsumer.accept(plan, copy); }
        finally { synchronized (this) { endCallback(plan); } }
    }

    private static String callbackKey(RunnerPlan plan) { return plan.site() + "|" + plan.job(); }

    private void beginCallback(RunnerPlan plan) { inFlight.merge(callbackKey(plan), 1, Math::addExact); }

    private void endCallback(RunnerPlan plan) {
        String key = callbackKey(plan);
        Integer count = inFlight.get(key);
        if (count == null || count < 1) throw new IllegalStateException("callback ownership is missing");
        if (count == 1) inFlight.remove(key);
        else inFlight.put(key, count - 1);
    }

    public synchronized void refusal(String site, String job, JsonNode value) {
        state.check();
        RunnerPlan plan = latest(site, job);
        SiteJson.fields(value, "planVersion", "code", "message");
        currentVersion(value, plan);
        if (!Set.of("plan.signature", "plan.expired", "plan.version", "plan.site", "plan.catalog", "plan.invalid", "plan.policy", "plan.busy")
                .contains(SiteJson.text(value, "code"))) throw new HttpFailure(400, "unknown refusal code");
        SiteJson.text(value, "message");
        state.value.with("refusals").with(job).set(site, value.deepCopy());
        state.save();
    }

    private void currentVersion(JsonNode value, RunnerPlan plan) {
        if (!value.path("planVersion").isIntegralNumber() || !value.path("planVersion").canConvertToInt()
                || value.path("planVersion").asInt() != plan.planVersion()) {
            throw new HttpFailure(409, "status version is not current");
        }
    }

    private void admitted(String site, RunnerPlan plan) {
        JsonNode admission = state.value.path("admissions").path(plan.job()).path(site);
        if (!admission.path("admissionHash").asText().equals(ConformanceHandshake.challenge(plan, runtime(site)).path("contractHash").asText())
                || !clock.instant().isBefore(RunnerPlan.instant(admission, "expires"))) {
            throw new HttpFailure(409, "site conformance is required");
        }
    }

    private void knownSite(String site) { if (!siteKeys.containsKey(site)) throw new HttpFailure(401, "site is not registered"); }

    private ConformanceHandshake.QualifiedRuntime runtime(String site) {
        var value = qualified.get(site);
        if (value == null) throw new HttpFailure(409, "runtime.identity is not explicitly qualified");
        return value;
    }

    private ObjectNode delivery(String site, String sequence) {
        JsonNode value = state.value.path("sites").path(site).path("deliveries").path(sequence);
        if (!value.isObject()) throw new HttpFailure(404, "delivery is unknown");
        return (ObjectNode) value;
    }

    private RunnerPlan latest(String site, String job) {
        knownSite(site);
        RunnerPlan latest = null;
        for (JsonNode value : state.value.path("sites").path(site).path("deliveries")) {
            if (value.path("job").asText().equals(job) && (latest == null || value.path("planVersion").asInt() > latest.planVersion())) {
                latest = RunnerPlan.verify(value.path("plan").asText(), driverKey);
            }
        }
        if (latest == null) throw new HttpFailure(404, "job is unknown");
        return latest;
    }

    public JsonNode request(String method, String path, String query, String certificateFingerprint, String contentType, byte[] body) {
        String principal = certificateFingerprint == null ? null : certificateSites.get(certificateFingerprint);
        if (principal == null) throw new HttpFailure(401, "client certificate is not a known site");
        String[] parts = path.split("/", -1);
        if (parts.length < 5 || !parts[1].equals("v1") || !parts[2].equals("sites")) throw new HttpFailure(404, "route is unknown");
        String site = parts[3];
        if (!site.equals(principal)) throw new HttpFailure(403, "certificate site differs from request site");
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("plans") && query != null && query.startsWith("after=")) {
            return next(site, query.substring(6));
        }
        if (method.equals("GET") && parts.length == 5 && parts[4].equals("conformance-challenge") && query != null && query.startsWith("job=")) {
            String job = query.substring(4);
            if (!job.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new HttpFailure(400, "invalid job query");
            return challenge(site, job);
        }
        if (!method.equals("POST") || query != null) throw new HttpFailure(400, "invalid method or query");
        if (parts.length == 5 && parts[4].equals("conformance")) {
            if (!"application/jose".equals(contentType) || body.length > SignedJws.MAX_COMPACT_BYTES) throw new HttpFailure(400, "invalid conformance content type or size");
            return conformance(site, new String(body, StandardCharsets.US_ASCII));
        }
        if (!"application/json".equals(contentType)) throw new HttpFailure(400, "JSON content type is required");
        if (parts.length == 7 && parts[4].equals("deliveries") && parts[6].equals("ack")) {
            ack(site, parts[5], SiteJson.parse(body, SiteJson.MAX_BYTES));
            return null;
        }
        if (parts.length == 7 && parts[4].equals("jobs")) {
            String job = parts[5];
            if (!job.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new HttpFailure(400, "invalid job identity");
            if (parts[6].equals("status")) { status(site, job, SiteJson.parse(body, SiteJson.MAX_BYTES)); return null; }
            if (parts[6].equals("aggregates")) { aggregates(site, job, SiteJson.parse(body, MAX_AGGREGATE_BYTES)); return null; }
            if (parts[6].equals("refusal")) {
                refusal(site, job, SiteJson.parse(body, SiteJson.MAX_BYTES));
                return null;
            }
        }
        throw new HttpFailure(404, "route is unknown");
    }

    public synchronized HttpsServer start(InetSocketAddress address, SSLContext context) throws IOException {
        if (server != null) throw new IllegalStateException("plan service is already listening");
        server = HttpsServer.create(address, 16);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override public void configure(HttpsParameters parameters) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setProtocols(new String[]{"TLSv1.3"});
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        threads = Executors.newFixedThreadPool(4);
        server.setExecutor(threads);
        server.createContext("/", exchange -> {
            int status = 200;
            JsonNode response;
            try {
                HttpsExchange secure = (HttpsExchange) exchange;
                String fingerprint = SiteJson.hash(secure.getSSLSession().getPeerCertificates()[0].getEncoded());
                boolean aggregate = exchange.getRequestURI().getPath().endsWith("/aggregates");
                int limit = aggregate ? MAX_AGGREGATE_BYTES : SignedJws.MAX_COMPACT_BYTES;
                byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
                if (body.length > limit) throw new HttpFailure(413, "request exceeds its byte limit");
                response = request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                        fingerprint, exchange.getRequestHeaders().getFirst("Content-Type"), body);
                if (response == null) status = 204;
            } catch (HttpFailure error) {
                status = error.status();
                response = SiteJson.JSON.createObjectNode().put("error", status == 400 ? "bad-request" : "request-refused").put("message", error.getMessage());
            } catch (PlanRefusal error) {
                status = 400;
                response = SiteJson.JSON.createObjectNode().put("error", "bad-request").put("message", error.getMessage());
            } catch (CertificateEncodingException | javax.net.ssl.SSLPeerUnverifiedException error) {
                status = 401;
                response = SiteJson.JSON.createObjectNode().put("error", "unknown-client");
            } catch (RuntimeException error) {
                status = 503;
                response = SiteJson.JSON.createObjectNode().put("error", "service-failed");
            }
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] bytes = response == null ? new byte[0] : SiteJson.bytes(response);
                exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                if (status != 204) exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        return server;
    }

    @Override public synchronized void close() {
        if (server != null) server.stop(0);
        if (threads != null) threads.shutdownNow();
        state.close();
    }
}
