package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.DataQuality;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsAccumulator;
import org.opentsx.store.iceberg.Tables;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.ChannelDef;
import org.opentsx.windpark.catalog.ChannelKind;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.catalog.SourceMapping;
import org.opentsx.windpark.catalog.SourceSystem;
import org.opentsx.windpark.catalog.Turbine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fills OpenMetadata (2.0.x) with the wind-farm metadata pool.
 *
 * <p>OpenMetadata has no sensor or time-series entity, so the wind farm is mapped onto existing entities:
 * <ul>
 *   <li><b>Vocabulary:</b> the data-point catalog becomes a <i>glossary</i> (one term per channel, with IEC
 *       61400-25-2 mapping, unit, time reference, quality and source mappings); IEC logical nodes and mapping
 *       status become <i>classification tags</i>.</li>
 *   <li><b>Custom properties</b> on tables and topics: catalog version, IEC edition, wind farm, sampling.</li>
 *   <li><b>Assets:</b> KafScale topics (with Avro schemas), Iceberg tables, and one logical <i>view</i> per wind farm
 *       ({@code windpark_<park>_signals}) with one column per channel - the analyst's view (turbine, ts, channels)
 *       that carries glossary terms, profiles and data-quality tests.</li>
 *   <li><b>Pipelines and lineage:</b> simulator/collector → episodes topic → Iceberg / statistics processor.</li>
 *   <li><b>Profiles</b> (per window, merged over all turbines) and <b>data-quality results</b> (per channel and check,
 *       failing turbines listed) computed from {@link SeriesStats}.</li>
 * </ul>
 * All writes are create-or-update ({@code PUT}), so publishing again is idempotent.
 */
public final class WindparkMetadataPublisher {

    /** Names of the OpenMetadata services and the Iceberg location. */
    public record Settings(String messagingService, String databaseService, String database, String icebergNamespace,
                           String pipelineService, String kafkaBootstrap, int partitions) {

        public static Settings defaults() {
            return new Settings("kafscale", "opentsx-iceberg", "iceberg", "tsx", "opentsx", null, 6);
        }
    }

    public static final String IEC_CLASSIFICATION = "IEC61400-25";
    public static final String OPENTSX_CLASSIFICATION = "OpenTSx";
    static final String[] CUSTOM_PROPERTY_ENTITIES = {"table", "topic"};

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> LOGICAL_NODES = Map.ofEntries(
            Map.entry("WTUR", "Wind turbine general information"),
            Map.entry("WROT", "Wind turbine rotor information"),
            Map.entry("WTRM", "Wind turbine transmission information"),
            Map.entry("WGEN", "Wind turbine generator information"),
            Map.entry("WCNV", "Wind turbine converter information"),
            Map.entry("WTRF", "Wind turbine transformer information"),
            Map.entry("WNAC", "Wind turbine nacelle information"),
            Map.entry("WYAW", "Wind turbine yawing information"),
            Map.entry("WTOW", "Wind turbine tower information"),
            Map.entry("WMET", "Wind power plant meteorological information"),
            Map.entry("WALM", "Wind power plant alarm information"),
            Map.entry("WSLG", "Wind turbine state log information"),
            Map.entry("WALG", "Wind turbine analogue log information"),
            Map.entry("WREP", "Wind turbine report information"),
            Map.entry("WAPC", "Wind power plant active power control information"),
            Map.entry("WRPC", "Wind power plant reactive power control information"));

    private final OmApi api;
    private final ChannelCatalog catalog;
    private final Park park;
    private final TopicLayout layout;
    private final Settings settings;
    private final Map<String, JsonNode> entities = new LinkedHashMap<>();
    private final Map<String, SeriesKey> inventory = new HashMap<>();

    public WindparkMetadataPublisher(OmApi api, ChannelCatalog catalog, Park park, TopicLayout layout, Settings settings) {
        this.api = api;
        this.catalog = catalog;
        this.park = park;
        this.layout = layout;
        this.settings = settings;
        park.inventory(catalog).forEach(k -> inventory.put(k.getSeriesId(), k));
    }

    // ---- names -----------------------------------------------------------------------------------------------------

    public String glossaryName() {
        return catalog.name();
    }

    public static String termName(ChannelDef c) {
        return c.id().replace('.', '_');
    }

    public static String columnName(ChannelDef c) {
        return c.metric().replace('.', '_');
    }

    public String signalsTable() {
        return "windpark_" + park.id() + "_signals";
    }

    public String schemaFqn() {
        return Fqn.build(settings.databaseService(), settings.database(), settings.icebergNamespace());
    }

    public String signalsTableFqn() {
        return Fqn.child(schemaFqn(), signalsTable());
    }

    public String topicFqn(String topic) {
        return Fqn.child(Fqn.quote(settings.messagingService()), topic);
    }

    /** Entities created so far, by FQN. */
    public Map<String, JsonNode> entities() {
        return Map.copyOf(entities);
    }

    // ---- everything ------------------------------------------------------------------------------------------------

    public void publishAll() {
        publishVocabulary();
        publishCustomProperties();
        publishAssets();
        publishLineage();
    }

    // ---- 1. vocabulary ---------------------------------------------------------------------------------------------

    public void publishVocabulary() {
        ObjectNode iec = JSON.createObjectNode();
        iec.put("name", IEC_CLASSIFICATION);
        iec.put("displayName", "IEC 61400-25-2 logical nodes");
        iec.put("description", "Logical nodes of the wind-power information model (" + catalog.iecEdition()
                + "). Tags describe the MEANING of a signal; they do not imply a compliant information exchange "
                + "(IEC 61400-25-3) or protocol mapping (IEC 61400-25-4).");
        iec.put("mutuallyExclusive", true);
        put("/v1/classifications", iec);
        Set<String> nodes = new TreeSet<>();
        catalog.channels().forEach(c -> nodes.add(c.iec().logicalNode()));
        for (String ln : nodes) {
            ObjectNode t = JSON.createObjectNode();
            t.put("classification", IEC_CLASSIFICATION);
            t.put("name", ln);
            t.put("description", LOGICAL_NODES.getOrDefault(ln, "IEC 61400-25-2 logical node " + ln));
            put("/v1/tags", t);
        }

        ObjectNode ots = JSON.createObjectNode();
        ots.put("name", OPENTSX_CLASSIFICATION);
        ots.put("description", "OpenTSx data-point catalog markers: mapping status of source channels and vendor-specific extensions.");
        ots.put("mutuallyExclusive", false);
        put("/v1/classifications", ots);
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("MappingConfirmed", "The source mapping (name, unit, sign, time reference) is confirmed.");
        markers.put("MappingProvisional", "The source mapping is provisional; interpret with care.");
        markers.put("MappingUnmapped", "A source channel exists but has no agreed meaning yet.");
        markers.put("VendorSpecific", "Vendor-specific extension beyond IEC 61400-25-2.");
        markers.forEach((name, desc) -> {
            ObjectNode t = JSON.createObjectNode();
            t.put("classification", OPENTSX_CLASSIFICATION);
            t.put("name", name);
            t.put("description", desc);
            put("/v1/tags", t);
        });

        ObjectNode g = JSON.createObjectNode();
        g.put("name", glossaryName());
        g.put("displayName", "Wind-farm data points (" + catalog.version() + ")");
        g.put("description", glossaryDescription());
        g.put("mutuallyExclusive", false);
        put("/v1/glossaries", g);
        for (ChannelDef c : catalog.channels()) {
            ObjectNode term = JSON.createObjectNode();
            term.put("glossary", Fqn.quote(glossaryName()));
            term.put("name", termName(c));
            term.put("displayName", c.id());
            term.put("description", termDescription(c));
            ArrayNode syn = term.putArray("synonyms");
            Set<String> synonyms = new LinkedHashSet<>();
            synonyms.add(c.metric());
            for (SourceSystem s : catalog.sources()) {
                s.mappings().stream().filter(m -> m.channel().equals(c.id())).forEach(m -> synonyms.add(m.sourceName()));
            }
            synonyms.remove(c.id());
            synonyms.forEach(syn::add);
            term.set("tags", channelTags(c, false));
            put("/v1/glossaryTerms", term);
        }
    }

    private String glossaryDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Versioned data-point catalog **").append(catalog.name()).append("** version **")
                .append(catalog.version()).append("**, mapped to ").append(catalog.iecEdition()).append(".\n\n");
        sb.append("Every term describes the meaning of one signal: IEC logical device / logical node / data object / "
                + "data attribute, unit (UCUM), resolution, plausible range, sampling, and how source systems deliver "
                + "it (original name, address, scaling, sign, time zone, timestamp reference, aggregation).\n\n");
        sb.append("Per-value quality codes:\n\n| code | meaning |\n|---|---|\n");
        catalog.qualityCodes().forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        return sb.toString();
    }

    private String termDescription(ChannelDef c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.description()).append("\n\n");
        sb.append("| attribute | value |\n|---|---|\n");
        row(sb, "channel id", c.id());
        row(sb, "metric (OpenTSx series)", c.metric());
        row(sb, "component", c.component());
        row(sb, "IEC 61400-25-2", c.iec().logicalDevice() + "/" + c.iec().logicalNode() + "." + c.iec().dataObject()
                + "." + c.iec().dataAttribute() + " (CDC " + c.iec().cdc() + ", " + catalog.iecEdition() + ")");
        row(sb, "data type", c.dataType());
        row(sb, "unit (UCUM)", c.unit());
        row(sb, "resolution", Double.toString(c.resolution()));
        row(sb, "plausible range", "[" + c.min() + ", " + c.max() + "]");
        row(sb, "kind", c.kind().name());
        row(sb, "sampling", c.sampling() == null ? "-" : c.sampling().toString());
        row(sb, "timestamps", "UTC, epoch microseconds; aggregates are stamped with the interval start");
        if (!c.statusCodes().isEmpty()) {
            row(sb, "status codes", new TreeMap<>(c.statusCodes()).toString());
        }
        row(sb, "vendor-specific", Boolean.toString(c.vendorSpecific()));
        if (c.notes() != null) {
            row(sb, "notes", c.notes());
        }
        boolean header = false;
        for (SourceSystem s : catalog.sources()) {
            for (SourceMapping m : s.mappings()) {
                if (!m.channel().equals(c.id())) {
                    continue;
                }
                if (!header) {
                    sb.append("\n**Source mappings**\n\n| source | original name | address | unit | scale/offset/sign "
                            + "| time zone | timestamp | aggregation | status | notes |\n|---|---|---|---|---|---|---|---|---|---|\n");
                    header = true;
                }
                sb.append("| ").append(s.system()).append(" | ").append(m.sourceName()).append(" | ")
                        .append(nz(m.sourceAddress())).append(" | ").append(nz(m.sourceUnit())).append(" | ")
                        .append(m.scale()).append(" / ").append(m.offset()).append(m.invertSign() ? " / inverted" : " / as is")
                        .append(" | ").append(s.timezone()).append(" | ").append(m.timestamp()).append(" | ")
                        .append(m.aggregation() == null ? "-" : m.aggregation().tag()).append(" | ")
                        .append(m.status()).append(" | ").append(nz(m.notes()).replace('|', '/')).append(" |\n");
            }
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("| ").append(k).append(" | ").append(v.replace('|', '/')).append(" |\n");
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private ArrayNode channelTags(ChannelDef c, boolean withTerm) {
        ArrayNode tags = JSON.createArrayNode();
        tags.add(tagLabel(Fqn.build(IEC_CLASSIFICATION, c.iec().logicalNode()), "Classification"));
        if (c.vendorSpecific()) {
            tags.add(tagLabel(Fqn.build(OPENTSX_CLASSIFICATION, "VendorSpecific"), "Classification"));
        }
        if (withTerm) {
            tags.add(tagLabel(Fqn.build(glossaryName(), termName(c)), "Glossary"));
        }
        return tags;
    }

    private static ObjectNode tagLabel(String fqn, String source) {
        ObjectNode t = JSON.createObjectNode();
        t.put("tagFQN", fqn);
        t.put("source", source);
        t.put("labelType", "Automated");
        t.put("state", "Confirmed");
        return t;
    }

    // ---- 2. custom properties --------------------------------------------------------------------------------------

    public void publishCustomProperties() {
        String stringType = api.get("/v1/metadata/types/name/string")
                .orElseThrow(() -> new IllegalStateException("OpenMetadata has no 'string' property type")).path("id").asText();
        Map<String, String> props = new LinkedHashMap<>();
        props.put("opentsxCatalog", "OpenTSx data-point catalog name and version the data was produced/normalized with.");
        props.put("iecEdition", "Edition of IEC 61400-25-2 the catalog maps to.");
        props.put("windPark", "Wind farm id (OpenTSx park).");
        props.put("samplingInterval", "Sampling interval of the raw signals (ISO-8601 duration).");
        for (String entity : CUSTOM_PROPERTY_ENTITIES) {
            String typeId = api.get("/v1/metadata/types/name/" + entity + "?fields=customProperties")
                    .orElseThrow(() -> new IllegalStateException("OpenMetadata has no entity type " + entity)).path("id").asText();
            props.forEach((name, desc) -> {
                ObjectNode p = JSON.createObjectNode();
                p.put("name", name);
                p.put("description", desc);
                ObjectNode ref = p.putObject("propertyType");
                ref.put("id", stringType);
                ref.put("type", "type");
                api.put("/v1/metadata/types/" + typeId, p);
            });
        }
    }

    private ObjectNode extension() {
        ObjectNode e = JSON.createObjectNode();
        e.put("opentsxCatalog", catalog.name() + "@" + catalog.version());
        e.put("iecEdition", catalog.iecEdition());
        e.put("windPark", park.id());
        catalog.channels().stream().map(ChannelDef::sampling).filter(java.util.Objects::nonNull).findFirst()
                .ifPresent(s -> e.put("samplingInterval", s.toString()));
        return e;
    }

    // ---- 3. assets -------------------------------------------------------------------------------------------------

    public void publishAssets() {
        ObjectNode ms = JSON.createObjectNode();
        ms.put("name", settings.messagingService());
        ms.put("serviceType", "Kafka");
        ms.put("description", "KafScale (Kafka protocol, S3-backed). Append-only topics: no compaction, no idempotent "
                + "producer, no timestamp seek - see opentsx-kafscale-conformance."
                + (settings.kafkaBootstrap() == null ? "" : " Bootstrap: `" + settings.kafkaBootstrap() + "`."));
        put("/v1/services/messagingServices", ms);

        topic(layout.episodes(), Episode.getClassSchema(), "v2 episodes (Avro single-object encoding), key = seriesId, "
                + "record timestamp = tStart, headers tsx-schema / tsx-bucket.");
        topic(layout.buckets(), BucketManifest.getClassSchema(), "Bucket manifests (OPEN -> SEALED with checksum); "
                + "the latest SEALED record per bucket wins.");
        topic(layout.series(), SeriesKey.getClassSchema(), "Series dimension: seriesId -> metric, tags, unit.");
        topic(layout.observations(), Observation.getClassSchema(), "Live single observations.");
        topic(layout.seriesStats(), SeriesStats.getClassSchema(), "Windowed and running statistics per series "
                + "(opentsx-stats); latest record per (seriesId, windowType, windowStart) wins.");

        ObjectNode ds = JSON.createObjectNode();
        ds.put("name", settings.databaseService());
        ds.put("serviceType", "CustomDatabase");
        ds.put("description", "Apache Iceberg tables written by OpenTSx (opentsx-store-iceberg).");
        put("/v1/services/databaseServices", ds);
        ObjectNode db = JSON.createObjectNode();
        db.put("name", settings.database());
        db.put("service", Fqn.quote(settings.databaseService()));
        put("/v1/databases", db);
        ObjectNode schema = JSON.createObjectNode();
        schema.put("name", settings.icebergNamespace());
        schema.put("database", Fqn.build(settings.databaseService(), settings.database()));
        schema.put("description", "Iceberg namespace with OpenTSx episodes.");
        put("/v1/databaseSchemas", schema);

        icebergTable(Tables.EPISODES, Tables.EPISODE_SCHEMA, "One row per v2 episode, lossless; partitioned by "
                + "day(t_start) and bucket(32, series_id). Readable from Spark, Trino and PyIceberg.");
        icebergTable(Tables.SERIES, Tables.SERIES_SCHEMA, "Series dimension (seriesId -> metric, tags, unit).");
        icebergTable(Tables.EPISODE_INDEX, Tables.INDEX_SCHEMA, "Replay index: episode -> KafScale topic/partition/offset.");
        signalsView();

        ObjectNode ps = JSON.createObjectNode();
        ps.put("name", settings.pipelineService());
        ps.put("serviceType", "CustomPipeline");
        ps.put("description", "OpenTSx jobs (simulator/collector, log sync, statistics).");
        put("/v1/services/pipelineServices", ps);
        pipeline("windpark-sim", "Wind-farm simulator / collector: writes episodes of a scenario as a sealed bucket.",
                List.of("simulate", "write-bucket"));
        pipeline("episode-log-sync", "Copies episodes from KafScale into Iceberg (idempotent per episodeId).",
                List.of("read-episodes", "write-iceberg"));
        pipeline("stats-processor", "Streaming statistics (Welford, quantile sketch, gaps) per series and window; "
                + "data-quality checks.", List.of("windows", "emit-stats"));
    }

    private void topic(String name, Schema avro, String description) {
        ObjectNode t = JSON.createObjectNode();
        t.put("name", name);
        t.put("service", Fqn.quote(settings.messagingService()));
        t.put("partitions", name.equals(layout.buckets()) ? 1 : settings.partitions());
        t.putArray("cleanupPolicies").add("delete");
        t.put("description", description);
        ObjectNode ms = t.putObject("messageSchema");
        ms.put("schemaType", "Avro");
        ms.put("schemaText", avro.toString());
        ms.set("schemaFields", SchemaMapping.avroFields(avro));
        t.set("extension", extension());
        put("/v1/topics", t);
    }

    private void icebergTable(String name, org.apache.iceberg.Schema schema, String description) {
        ObjectNode t = JSON.createObjectNode();
        t.put("name", name);
        t.put("databaseSchema", schemaFqn());
        t.put("tableType", "Iceberg");
        t.put("description", description);
        t.set("columns", SchemaMapping.icebergColumns(schema));
        t.set("extension", extension());
        put("/v1/tables", t);
    }

    private void signalsView() {
        ObjectNode t = JSON.createObjectNode();
        t.put("name", signalsTable());
        t.put("displayName", "Wind farm " + park.name() + " - signals");
        t.put("databaseSchema", schemaFqn());
        t.put("tableType", "View");
        t.put("description", signalsDescription());
        ArrayNode cols = t.putArray("columns");
        int pos = 1;
        ObjectNode turbine = cols.addObject();
        turbine.put("name", "turbine");
        turbine.put("dataType", "STRING");
        turbine.put("description", "Turbine id (IEC logical device), see the table description for the inventory.");
        turbine.put("ordinalPosition", pos++);
        ObjectNode ts = cols.addObject();
        ts.put("name", "ts");
        ts.put("dataType", "TIMESTAMPZ");
        ts.put("description", "Sample time, UTC, microsecond precision.");
        ts.put("ordinalPosition", pos++);
        for (ChannelDef c : catalog.channels()) {
            ObjectNode col = cols.addObject();
            col.put("name", columnName(c));
            col.put("displayName", c.metric());
            col.put("dataType", c.kind() == ChannelKind.STATUS ? "INT" : "DOUBLE");
            col.put("dataTypeDisplay", c.dataType() + " [" + c.unit() + "]");
            col.put("description", c.description() + " - " + c.unit() + ", IEC " + c.iec().logicalNode() + "."
                    + c.iec().dataObject() + "." + c.iec().dataAttribute() + ", sampling " + c.sampling() + ".");
            col.set("tags", channelTags(c, true));
            col.put("ordinalPosition", pos++);
        }
        t.put("schemaDefinition", "-- logical view: one row per (turbine, ts), one column per catalog channel\n"
                + "-- derived from " + Fqn.child(schemaFqn(), Tables.EPISODES) + " (explode values, pivot by metric)");
        t.set("extension", extension());
        put("/v1/tables", t);
    }

    private String signalsDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyst view of wind farm **").append(park.name()).append("** (`").append(park.id()).append("`): one row per "
                + "turbine and timestamp, one column per channel of data-point catalog `").append(catalog.name())
                .append("@").append(catalog.version()).append("`. Columns carry the glossary term and IEC tag; profiles "
                        + "and data-quality results are computed by opentsx-stats over all turbines.\n\n");
        sb.append("Location ").append(park.latitude()).append(", ").append(park.longitude()).append("; ")
                .append(park.turbines().size()).append(" turbines, ").append(inventory.size()).append(" series.\n\n");
        sb.append("| turbine | model | rated kW | rotor m | hub m | x m | y m |\n|---|---|---|---|---|---|---|\n");
        for (Turbine tu : park.turbines()) {
            sb.append(String.format(java.util.Locale.ROOT, "| %s | %s | %.0f | %.0f | %.0f | %.0f | %.0f |%n", tu.id(),
                    tu.type().model(), tu.type().ratedPowerKw(), tu.type().rotorDiameterM(), tu.type().hubHeightM(),
                    tu.xM(), tu.yM()));
        }
        return sb.toString();
    }

    private void pipeline(String name, String description, List<String> tasks) {
        ObjectNode p = JSON.createObjectNode();
        p.put("name", name);
        p.put("service", Fqn.quote(settings.pipelineService()));
        p.put("description", description);
        ArrayNode ts = p.putArray("tasks");
        for (int i = 0; i < tasks.size(); i++) {
            ObjectNode t = ts.addObject();
            t.put("name", tasks.get(i));
            if (i + 1 < tasks.size()) {
                t.putArray("downstreamTasks").add(tasks.get(i + 1));
            }
        }
        put("/v1/pipelines", p);
    }

    // ---- 4. lineage ------------------------------------------------------------------------------------------------

    public void publishLineage() {
        String pipelines = Fqn.quote(settings.pipelineService());
        lineage(ref(Fqn.child(pipelines, "windpark-sim")), ref(topicFqn(layout.episodes())), null, "Manual");
        lineage(ref(Fqn.child(pipelines, "windpark-sim")), ref(topicFqn(layout.buckets())), null, "Manual");
        lineage(ref(topicFqn(layout.episodes())), ref(Fqn.child(schemaFqn(), Tables.EPISODES)),
                ref(Fqn.child(pipelines, "episode-log-sync")), "PipelineLineage");
        lineage(ref(topicFqn(layout.episodes())), ref(topicFqn(layout.seriesStats())),
                ref(Fqn.child(pipelines, "stats-processor")), "PipelineLineage");
        lineage(ref(Fqn.child(schemaFqn(), Tables.EPISODES)), ref(signalsTableFqn()), null, "ViewLineage");
    }

    private void lineage(ObjectNode from, ObjectNode to, ObjectNode pipeline, String source) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode edge = body.putObject("edge");
        edge.set("fromEntity", from);
        edge.set("toEntity", to);
        ObjectNode details = edge.putObject("lineageDetails");
        if (pipeline != null) {
            details.set("pipeline", pipeline);
        }
        details.put("source", source);
        api.put("/v1/lineage", body);
    }

    private ObjectNode ref(String fqn) {
        JsonNode e = entities.get(fqn);
        if (e == null) {
            throw new IllegalStateException("not published yet: " + fqn + " (publish assets first)");
        }
        ObjectNode r = JSON.createObjectNode();
        r.put("id", e.path("id").asText());
        r.put("type", e.path("_type").asText());
        return r;
    }

    // ---- 5. profiles -----------------------------------------------------------------------------------------------

    /**
     * Publishes one table profile of the signals view per window of the given type: per channel, the statistics of
     * all turbines merged (exact moments, merged quantile sketches). Returns the number of profiles.
     */
    public int publishProfiles(List<SeriesStats> stats, String windowType) {
        String tableId = requireEntity(signalsTableFqn()).path("id").asText();
        Map<Long, Map<ChannelDef, StatsAccumulator>> byWindow = mergeByChannel(stats, windowType);
        for (Map.Entry<Long, Map<ChannelDef, StatsAccumulator>> w : byWindow.entrySet()) {
            long tsMillis = w.getKey() / 1000;
            ObjectNode body = JSON.createObjectNode();
            ObjectNode tp = body.putObject("tableProfile");
            tp.put("timestamp", tsMillis);
            tp.put("columnCount", catalog.channels().size() + 2);
            long rows = 0;
            ArrayNode cps = body.putArray("columnProfile");
            for (Map.Entry<ChannelDef, StatsAccumulator> c : w.getValue().entrySet()) {
                StatsAccumulator a = c.getValue();
                long values = a.validCount() + a.nanCount();
                rows = Math.max(rows, values);
                ObjectNode cp = cps.addObject();
                cp.put("name", columnName(c.getKey()));
                cp.put("timestamp", tsMillis);
                cp.put("valuesCount", (double) values);
                cp.put("validCount", a.validCount());
                cp.put("nullCount", (double) a.nanCount());
                cp.put("nullProportion", values == 0 ? 0.0 : (double) a.nanCount() / values);
                if (a.validCount() > 0) {
                    cp.put("min", a.min());
                    cp.put("max", a.max());
                    cp.put("mean", a.mean());
                }
                if (a.validCount() > 1) {
                    cp.put("stddev", a.stddev());
                    cp.put("variance", a.variance());
                }
                QuantileSketch q = a.sketch();
                if (q != null && !q.isEmpty()) {
                    double q1 = q.quantile(0.25);
                    double q3 = q.quantile(0.75);
                    cp.put("firstQuartile", q1);
                    cp.put("median", q.quantile(0.5));
                    cp.put("thirdQuartile", q3);
                    cp.put("interQuartileRange", q3 - q1);
                }
            }
            tp.put("rowCount", (double) rows);
            api.put("/v1/tables/" + tableId + "/tableProfile", body);
        }
        return byWindow.size();
    }

    private Map<Long, Map<ChannelDef, StatsAccumulator>> mergeByChannel(List<SeriesStats> stats, String windowType) {
        Map<Long, Map<ChannelDef, StatsAccumulator>> byWindow = new TreeMap<>();
        for (SeriesStats s : stats) {
            if (!s.getWindowType().toString().equals(windowType)) {
                continue;
            }
            SeriesKey key = inventory.get(s.getSeriesId().toString());
            if (key == null) {
                continue;
            }
            ChannelDef c = catalog.channel(key.getMetric());
            Map<ChannelDef, StatsAccumulator> m = byWindow.computeIfAbsent(Micros.of(s.getWindowEnd()), k -> new LinkedHashMap<>());
            StatsAccumulator acc = StatsAccumulator.of(s);
            m.merge(c, acc, (x, y) -> {
                x.merge(y);
                return x;
            });
        }
        return byWindow;
    }

    // ---- 6. data quality -------------------------------------------------------------------------------------------

    /**
     * Registers the OpenTSx test definitions and one column test case per (channel, check) on the signals view, then
     * posts one result per window: Failed if any turbine fails, with the failing turbines in the message.
     * Returns the number of results posted.
     */
    public int publishDataQuality(List<SeriesStats> stats, String windowType, Map<String, DataQuality.Expectation> byMetric) {
        String tableFqn = signalsTableFqn();
        requireEntity(tableFqn);
        for (DataQuality.Check check : DataQuality.Check.values()) {
            put("/v1/dataQuality/testDefinitions", testDefinition(check));
        }
        ObjectNode suite = JSON.createObjectNode();
        suite.put("name", tableFqn + ".testSuite");
        suite.put("basicEntityReference", tableFqn);
        suite.put("description", "OpenTSx data-quality checks evaluated on streaming statistics.");
        put("/v1/dataQuality/testSuites/basic", suite);

        // evaluate per series, then aggregate per (channel, check, window)
        record Key(ChannelDef channel, DataQuality.Check check, long windowEnd) {
        }
        Map<Key, List<String>> failures = new LinkedHashMap<>();
        Map<Key, Integer> evaluated = new LinkedHashMap<>();
        Map<Key, Double> worst = new LinkedHashMap<>();
        List<SeriesStats> sorted = new ArrayList<>(stats);
        sorted.sort(java.util.Comparator.comparing((SeriesStats s) -> s.getWindowEnd()).thenComparing(s -> s.getSeriesId().toString()));
        for (SeriesStats s : sorted) {
            if (!s.getWindowType().toString().equals(windowType)) {
                continue;
            }
            SeriesKey key = inventory.get(s.getSeriesId().toString());
            if (key == null || !byMetric.containsKey(key.getMetric())) {
                continue;
            }
            ChannelDef c = catalog.channel(key.getMetric());
            String turbine = key.getTags().get("turbine");
            for (DataQuality.Result r : DataQuality.evaluate(s, byMetric.get(key.getMetric()))) {
                Key k = new Key(c, r.check(), r.windowEndMicros());
                evaluated.merge(k, 1, Integer::sum);
                failures.computeIfAbsent(k, x -> new ArrayList<>());
                if (r.failed()) {
                    failures.get(k).add(turbine + " (" + r.message() + ")");
                }
                double badness = switch (r.check()) {
                    case COMPLETENESS, FLATLINE -> -r.value();    // lower is worse
                    default -> r.value();
                };
                worst.merge(k, badness, Math::max);
            }
        }
        Set<String> testCases = new LinkedHashSet<>();
        for (Key k : evaluated.keySet()) {
            String name = columnName(k.channel()) + "_" + k.check().name().toLowerCase(java.util.Locale.ROOT);
            if (testCases.add(name)) {
                put("/v1/dataQuality/testCases", testCase(tableFqn, k.channel(), k.check(), name, byMetric.get(k.channel().metric())));
            }
        }
        int posted = 0;
        for (Map.Entry<Key, Integer> e : evaluated.entrySet()) {
            Key k = e.getKey();
            List<String> failed = failures.get(k);
            String name = columnName(k.channel()) + "_" + k.check().name().toLowerCase(java.util.Locale.ROOT);
            String fqn = entities.get(testCaseFqn(tableFqn, columnName(k.channel()), name)).path("fullyQualifiedName").asText();
            ObjectNode result = JSON.createObjectNode();
            result.put("timestamp", k.windowEnd() / 1000);
            result.put("testCaseStatus", failed.isEmpty() ? "Success" : "Failed");
            result.put("result", failed.isEmpty() ? e.getValue() + " turbines passed"
                    : failed.size() + " of " + e.getValue() + " turbines failed: " + String.join("; ", failed));
            ArrayNode values = result.putArray("testResultValue");
            ObjectNode v1 = values.addObject();
            v1.put("name", "failedTurbines");
            v1.put("value", Integer.toString(failed.size()));
            ObjectNode v2 = values.addObject();
            v2.put("name", "evaluatedTurbines");
            v2.put("value", Integer.toString(e.getValue()));
            double w = worst.get(k);
            ObjectNode v3 = values.addObject();
            v3.put("name", "worstValue");
            v3.put("value", Double.toString(k.check() == DataQuality.Check.COMPLETENESS
                    || k.check() == DataQuality.Check.FLATLINE ? -w : w));
            api.post("/v1/dataQuality/testCases/testCaseResults/" + URLEncoder.encode(fqn, StandardCharsets.UTF_8)
                    .replace("+", "%20"), result);
            posted++;
        }
        return posted;
    }

    private static String testCaseFqn(String tableFqn, String column, String name) {
        return tableFqn + "." + Fqn.quote(column) + "." + Fqn.quote(name);
    }

    static String testDefinitionName(DataQuality.Check check) {
        return switch (check) {
            case COMPLETENESS -> "opentsxCompleteness";
            case PLAUSIBLE_RANGE -> "opentsxPlausibleRange";
            case FLATLINE -> "opentsxFlatline";
            case GAPS -> "opentsxGaps";
            case FRESHNESS -> "opentsxFreshness";
        };
    }

    private static ObjectNode testDefinition(DataQuality.Check check) {
        ObjectNode d = JSON.createObjectNode();
        d.put("name", testDefinitionName(check));
        d.put("entityType", "COLUMN");
        d.putArray("testPlatforms").add("Other");
        ArrayNode params = d.putArray("parameterDefinition");
        String dimension;
        String description;
        switch (check) {
            case COMPLETENESS -> {
                dimension = "Completeness";
                description = "Share of expected samples per window (window length / sampling interval) that are valid.";
                param(params, "minCompleteness", "Minimum share of valid samples");
                param(params, "expectedIntervalMicros", "Expected sampling interval in microseconds");
            }
            case PLAUSIBLE_RANGE -> {
                dimension = "Validity";
                description = "Share of values outside the catalog's plausible band, estimated from the quantile sketch.";
                param(params, "min", "Lower plausible bound");
                param(params, "max", "Upper plausible bound");
                param(params, "maxOutOfRange", "Maximum share outside the band");
            }
            case FLATLINE -> {
                dimension = "Accuracy";
                description = "Stuck sensor: standard deviation of a window is (almost) zero.";
                param(params, "epsilon", "Maximum standard deviation regarded as constant");
                param(params, "minCount", "Minimum number of values");
            }
            case GAPS -> {
                dimension = "Completeness";
                description = "Number of steps between consecutive timestamps larger than 1.5 x the sampling interval.";
                param(params, "maxGaps", "Maximum number of gaps");
            }
            default -> {
                dimension = "Completeness";
                description = "Age of the most recent value.";
                param(params, "maxAgeSeconds", "Maximum age in seconds");
            }
        }
        d.put("description", description + " Evaluated by opentsx-stats on streaming statistics, not by OpenMetadata.");
        d.put("dataQualityDimension", dimension);
        ArrayNode types = d.putArray("supportedDataTypes");
        types.add("DOUBLE");
        types.add("INT");
        return d;
    }

    private static void param(ArrayNode params, String name, String description) {
        ObjectNode p = params.addObject();
        p.put("name", name);
        p.put("displayName", name);
        p.put("description", description);
        p.put("dataType", "NUMBER");
        p.put("required", false);
    }

    private static ObjectNode testCase(String tableFqn, ChannelDef c, DataQuality.Check check, String name, DataQuality.Expectation e) {
        ObjectNode t = JSON.createObjectNode();
        t.put("name", name);
        t.put("displayName", c.metric() + " - " + check.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        t.put("description", "OpenTSx " + check + " check for " + c.id() + " over all turbines of the wind farm.");
        t.put("testDefinition", testDefinitionName(check));
        t.put("entityLink", Fqn.columnLink(tableFqn, columnName(c)));
        ArrayNode pv = t.putArray("parameterValues");
        switch (check) {
            case COMPLETENESS -> {
                pv(pv, "minCompleteness", e.minCompleteness());
                pv(pv, "expectedIntervalMicros", e.expectedIntervalMicros());
            }
            case PLAUSIBLE_RANGE -> {
                pv(pv, "min", e.minPlausible());
                pv(pv, "max", e.maxPlausible());
                pv(pv, "maxOutOfRange", e.maxOutOfRange());
            }
            case FLATLINE -> {
                pv(pv, "epsilon", e.flatlineEpsilon());
                pv(pv, "minCount", e.flatlineMinCount());
            }
            case GAPS -> pv(pv, "maxGaps", e.maxGaps());
            case FRESHNESS -> pv(pv, "maxAgeSeconds", e.maxAge() == null ? null : e.maxAge().toSeconds());
        }
        return t;
    }

    private static void pv(ArrayNode pv, String name, Object value) {
        if (value != null) {
            ObjectNode p = pv.addObject();
            p.put("name", name);
            p.put("value", value.toString());
        }
    }

    // ---- plumbing --------------------------------------------------------------------------------------------------

    private JsonNode requireEntity(String fqn) {
        JsonNode e = entities.get(fqn);
        if (e == null) {
            throw new IllegalStateException("not published yet: " + fqn + " (publish assets first)");
        }
        return e;
    }

    private JsonNode put(String path, ObjectNode body) {
        JsonNode e = api.put(path, body);
        String fqn = e.path("fullyQualifiedName").asText(null);
        if (fqn == null) {
            fqn = EntityPaths.fqnOf(path, body).orElseThrow();
        }
        ObjectNode copy = e.isObject() ? ((ObjectNode) e).deepCopy() : JSON.createObjectNode();
        copy.put("_type", EntityPaths.typeOf(path).orElse("unknown"));
        entities.put(fqn, copy);
        return e;
    }
}
