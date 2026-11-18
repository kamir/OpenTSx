package org.opentsx.wayang.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.TsxCheckpoint;
import org.opentsx.wayang.TsxStatsMerge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class CompletenessCoordinator {

    private static final class Window {
        final String group;
        final String type;
        final long start;
        final long end;
        final Map<String, AggregateRecord> seen = new TreeMap<>();
        final Map<String, AggregateRecord> latest = new TreeMap<>();
        long revision = -1;

        Window(String group, String type, long start, long end) {
            this.group = group;
            this.type = type;
            this.start = start;
            this.end = end;
        }
    }

    private final AggregateInventory inventory;
    private final Duration maxDelay;
    private final double alpha;
    private final Clock clock;
    private final Path path;
    private final String identity;
    private final Map<String, Window> windows = new TreeMap<>();
    private final List<AggregateRecord> outbox = new ArrayList<>();
    private final Map<String, String> published = new TreeMap<>();
    private boolean failed;

    public CompletenessCoordinator(AggregateInventory inventory, Duration maxDelay, double alpha, Clock clock, Path state) {
        if (inventory == null || maxDelay == null || maxDelay.isNegative() || clock == null || state == null || !(alpha > 0 && alpha < 1)) {
            throw new IllegalArgumentException("invalid aggregate coordinator configuration");
        }
        this.inventory = inventory;
        this.maxDelay = maxDelay;
        this.alpha = alpha;
        this.clock = clock;
        this.path = state;
        this.identity = AggregateRecord.sha256((inventory.fingerprint() + "|" + maxDelay + "|" + Double.toHexString(alpha))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        load();
        inventory.scopes().forEach(s -> addWindow(s.groupId(), s.windowType(), s.startMicros(), s.endMicros()));
    }

    public synchronized void expect(String groupId, String windowType, long startMicros, long endMicros) {
        healthy();
        addWindow(groupId, windowType, startMicros, endMicros);
        save();
    }

    private Window addWindow(String group, String type, long start, long end) {
        inventory.validateScope(group, type, start, end);
        TsxStatsMerge.merge(group, type, start, end, alpha, List.of());
        Micros.toInstant(end).plus(maxDelay);
        String key = group + "|" + type + "|" + start;
        Window previous = windows.get(key);
        if (previous != null) {
            if (previous.end != end) throw new IllegalArgumentException("aggregate window end conflicts");
            return previous;
        }
        Window window = new Window(group, type, start, end);
        windows.put(key, window);
        return window;
    }

    public synchronized List<AggregateRecord> accept(AggregateRecord record) {
        healthy();
        inventory.validate(record);
        SeriesStats statistics = record.statistics();
        TsxStatsMerge.merge(statistics.getSeriesId(), statistics.getWindowType(), Micros.of(statistics.getWindowStart()),
                Micros.of(statistics.getWindowEnd()), alpha, List.of(new TsxStatsMerge.Partial(record.header("tsx-site"), statistics)));
        String windowKey = statistics.getSeriesId() + "|" + statistics.getWindowType() + "|" + Micros.of(statistics.getWindowStart());
        Window window = windows.get(windowKey);
        if (window == null && record.revision() != 0) throw new IllegalArgumentException("first aggregate partial revision must be zero");
        if (window == null) window = addWindow(statistics.getSeriesId(), statistics.getWindowType(), Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()));
        String identity = recordIdentity(record);
        AggregateRecord previous = window.seen.get(identity);
        if (previous != null) {
            if (!previous.equals(record)) throw new IllegalArgumentException("aggregate partial revision conflicts");
            return List.of();
        }
        AggregateRecord latest = window.latest.get(record.header("tsx-site"));
        if (latest != null && latest.revision() >= record.revision()) throw new IllegalArgumentException("aggregate partial revision is stale");
        if (latest == null && record.revision() != 0) throw new IllegalArgumentException("first aggregate partial revision must be zero");
        Map<String, AggregateRecord> prospective = new TreeMap<>(window.latest);
        prospective.put(record.header("tsx-site"), record);
        TsxStatsMerge.merge(window.group, window.type, window.start, window.end, alpha, prospective.entrySet().stream()
                .map(e -> new TsxStatsMerge.Partial(e.getKey(), e.getValue().statistics())).toList());
        window.seen.put(identity, record);
        window.latest.put(record.header("tsx-site"), record);
        List<AggregateRecord> emitted = emit(window, true);
        save();
        return emitted;
    }

    public synchronized List<AggregateRecord> advance() {
        healthy();
        List<AggregateRecord> emitted = new ArrayList<>();
        windows.values().forEach(w -> emitted.addAll(emit(w, false)));
        if (!emitted.isEmpty()) save();
        return List.copyOf(emitted);
    }

    private List<AggregateRecord> emit(Window window, boolean changed) {
        List<String> missing = inventory.sites().stream().filter(site -> !window.latest.containsKey(site)).toList();
        boolean due = clock.instant().isAfter(Micros.toInstant(window.end).plus(maxDelay));
        if (!missing.isEmpty() && !due || window.revision >= 0 && !changed) return List.of();
        List<TsxStatsMerge.Partial> partials = window.latest.entrySet().stream()
                .map(e -> new TsxStatsMerge.Partial(e.getKey(), e.getValue().statistics())).toList();
        SeriesStats merged = TsxStatsMerge.merge(window.group, window.type, window.start, window.end, alpha, partials);
        try { window.revision = Math.addExact(window.revision, 1); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("aggregate merged revision overflow", e); }
        AggregateRecord record = AggregateRecord.merged(inventory, window.revision, merged, missing);
        outbox.add(record);
        return List.of(record);
    }

    public synchronized List<AggregateRecord> pending() {
        healthy();
        return List.copyOf(outbox);
    }

    public synchronized void acknowledge(AggregateRecord record) {
        healthy();
        String id = recordIdentity(record);
        String hash = AggregateRecord.sha256(AggregateRecord.json(record).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String previous = published.get(id);
        if (previous != null) {
            if (!previous.equals(hash)) throw new IllegalArgumentException("aggregate publication acknowledgement conflicts");
            return;
        }
        int index = outbox.indexOf(record);
        if (index < 0) throw new IllegalArgumentException("aggregate publication acknowledgement is unknown");
        outbox.remove(index);
        published.put(id, hash);
        save();
    }

    private static String recordIdentity(AggregateRecord record) { return record.key() + "|" + record.revision(); }

    private void healthy() {
        if (failed) throw new IllegalStateException("aggregate coordinator state is unavailable");
    }

    private void save() {
        ObjectNode value = AggregateRecord.JSON.createObjectNode().put("schema", "opentsx.aggregate-merge-state.v1").put("identity", identity);
        var entries = value.putArray("windows");
        for (Window window : windows.values()) {
            ObjectNode saved = entries.addObject().put("group", window.group).put("windowType", window.type)
                    .put("start", Long.toString(window.start)).put("end", Long.toString(window.end)).put("revision", window.revision);
            var partials = saved.putArray("partials");
            window.seen.values().forEach(r -> partials.add(AggregateRecord.json(r)));
        }
        var pending = value.putArray("outbox");
        outbox.forEach(r -> pending.add(AggregateRecord.json(r)));
        ObjectNode sent = value.putObject("published");
        published.forEach(sent::put);
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            new TsxCheckpoint(inventory.job(), inventory.planVersion(), identity, "aggregate-merge", Map.of(), Map.of("federation", value)).write(path);
        } catch (RuntimeException | java.io.IOException e) {
            failed = true;
            throw new IllegalStateException("cannot persist aggregate coordinator", e);
        }
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            TsxCheckpoint checkpoint = TsxCheckpoint.load(path, identity)
                    .orElseThrow(() -> new IllegalArgumentException("aggregate coordinator continuation does not match"));
            if (!inventory.job().equals(checkpoint.job()) || inventory.planVersion() != checkpoint.planVersion()
                    || !"aggregate-merge".equals(checkpoint.topic()) || !checkpoint.next().isEmpty()
                    || !checkpoint.state().keySet().equals(Set.of("federation"))) throw new IllegalArgumentException("invalid aggregate checkpoint context");
            JsonNode state = checkpoint.state().get("federation");
            AggregateRecord.fields(state, Set.of("schema", "identity", "windows", "outbox", "published"));
            if (!"opentsx.aggregate-merge-state.v1".equals(state.path("schema").textValue()) || !identity.equals(state.path("identity").textValue())
                    || !state.path("windows").isArray() || !state.path("outbox").isArray() || !state.path("published").isObject()) {
                throw new IllegalArgumentException("invalid aggregate coordinator state");
            }
            for (JsonNode entry : state.path("windows")) {
                AggregateRecord.fields(entry, Set.of("group", "windowType", "start", "end", "revision", "partials"));
                String key = AggregateRecord.text(entry, "group") + "|" + AggregateRecord.text(entry, "windowType") + "|" + AggregateRecord.text(entry, "start");
                if (windows.containsKey(key)) throw new IllegalArgumentException("duplicate saved aggregate window");
                Window window = addWindow(AggregateRecord.text(entry, "group"), AggregateRecord.text(entry, "windowType"),
                        Long.parseLong(AggregateRecord.text(entry, "start")), Long.parseLong(AggregateRecord.text(entry, "end")));
                if (!entry.path("revision").isIntegralNumber() || !entry.path("revision").canConvertToLong()
                        || entry.path("revision").longValue() < -1 || !entry.path("partials").isArray()) throw new IllegalArgumentException("invalid saved aggregate revision");
                window.revision = entry.path("revision").longValue();
                for (JsonNode partial : entry.path("partials")) {
                    AggregateRecord record = AggregateRecord.read(partial);
                    inventory.validate(record);
                    SeriesStats s = record.statistics();
                    if (!window.group.equals(s.getSeriesId()) || !window.type.equals(s.getWindowType())
                            || window.start != Micros.of(s.getWindowStart()) || window.end != Micros.of(s.getWindowEnd())) {
                        throw new IllegalArgumentException("saved aggregate partial window conflicts");
                    }
                    if (window.seen.putIfAbsent(recordIdentity(record), record) != null) throw new IllegalArgumentException("duplicate saved aggregate partial");
                    window.latest.merge(record.header("tsx-site"), record, (a, b) -> a.revision() > b.revision() ? a : b);
                }
            }
            if (windows.size() != inventory.scopes().size()) throw new IllegalArgumentException("saved aggregate scopes are incomplete");
            Set<String> ids = new java.util.HashSet<>();
            for (JsonNode entry : state.path("outbox")) {
                AggregateRecord record = AggregateRecord.read(entry);
                if (!record.merged() || !inventory.job().equals(record.header("tsx-job"))
                        || !Integer.toString(inventory.planVersion()).equals(record.header("tsx-generation"))
                        || !inventory.specHash().equals(record.header("tsx-spec-hash")) || !ids.add(recordIdentity(record))) {
                    throw new IllegalArgumentException("invalid saved aggregate publication");
                }
                SeriesStats statistics = record.statistics();
                Window window = windows.get(statistics.getSeriesId() + "|" + statistics.getWindowType() + "|" + Micros.of(statistics.getWindowStart()));
                if (window == null || record.revision() > window.revision) throw new IllegalArgumentException("saved aggregate publication has no window revision");
                String missing = record.header("tsx-missing-sites");
                if (!missing.isEmpty() && !inventory.sites().containsAll(List.of(missing.split(",")))) {
                    throw new IllegalArgumentException("saved aggregate publication has unknown missing sites");
                }
                outbox.add(record);
            }
            state.path("published").fieldNames().forEachRemaining(id -> {
                String hash = AggregateRecord.text(state.path("published"), id);
                AggregateRecord.hash(hash);
                if (ids.contains(id)) throw new IllegalArgumentException("aggregate publication is both pending and acknowledged");
                published.put(id, hash);
            });
        } catch (RuntimeException e) {
            failed = true;
            throw new IllegalStateException("cannot restore aggregate coordinator", e);
        }
    }
}
