# Concept 19: graph layers on KafScale and Iceberg

> Status: **proposal**, 2026-10-26, not implemented. Checked against this repository at `551815a` and against
> GeoQB ([`GeoQB/geoqb`](https://github.com/GeoQB/geoqb) at `393955c`). Related: [06](06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md)
> (P8, edge table), [07](07-DIGITAL-TWIN-BSP.md) (twin), [12](12-EXPLORATIVES-UI-INVENTAR.md) (inventory),
> [13](13-SEMANTIK-ALS-ZEITREIHE.html) (semantics as time series).

## 1. Goal

One storage contract for every graph that OpenTSx works with:

- the twin wiring plan (atoms and wires),
- the asset inventory (assets, their location, their series, their atom),
- dependency edges measured from time series (P8),
- influence matrices entered by experts (Vester style),
- location context from GeoQB (OpenStreetMap features and tags on H3 cells),
- software architecture, designed and as built.

Every graph is a **layer**. All layers share one node table and one edge table in Iceberg, are written through
KafScale topics, and join the existing `series` and `episodes` tables by ID.

## 2. Current state

| Graph | Where it lives today | Read by code | Evidence |
|---|---|---|---|
| Twin wiring | YAML per twin; `TwinPlan(atoms, wiring)`, `Wire(fromAtom, fromPort, toAtom, toPort, delayed)` | yes | `opentsx-twin/src/main/java/org/opentsx/twin/TwinPlan.java:31-57`, `opentsx-twin/src/main/resources/twin/h2-chain.yaml` |
| Twin plan identity | `TwinPlan.hash()`: xxHash64 over the plan serialized with sorted keys | yes | `TwinPlan.java:116-121` |
| Inventory | `opentsx.inventory.v1` draft: assets with `location {plan, lat, lon}`, `series [{metric, tags}]`, `atom`, `parent` | no ("Not read by code yet (stage E1)") | `opentsx-twin/src/main/resources/twin/inventar-beispiel.yaml:7` |
| Measured dependencies | concept: versioned Iceberg edge table `src, dst, layer, method, lag, score, p_value, null_model, correction, episode_id, window, replay_offsets, run_id` | no | [06](06-SYSTEMREKONSTRUKTION-OPTIMIERUNG.md), line 197 |
| Expert influence matrices | none | no | |
| GeoQB location graph | TigerGraph: vertices `h3place`, `osmplace`, `osmtag`, `fact`; edges `hasOSMTag`, `located_on_h3_cell`, `h3_grid_link`, `observed_at(value, time)` | GeoQB only | [`geoqb_tg.py:166-176`](https://github.com/GeoQB/geoqb/blob/393955c852dfb50ff8f50d9f6443f45daf48b40e/pyGeoQB/geoanalysis/geoqb/geoqb_tg.py#L166-L176) |

Constraints for every new topic and table:

- Topic names come from `TopicLayout`: `"tsx." + domain + "." + kind + ".v2"` (`opentsx-kafka-v2/src/main/java/org/opentsx/kafka/TopicLayout.java:189-191`).
- KafScale has no compaction, no idempotent producer and no timestamp seek ([README](../../README.md), line 60).
  Producers use `enable.idempotence=false` and `acks=all` (`KafScaleClients.java:19-20`).
- Consumer-group rejoin is broken on KafScale (measured); OpenTSx uses manual assignment only
  ([03](03-GAP-ANALYSE.md), line 120; [`opentsx-kafka-v2/README.md`](../../opentsx-kafka-v2/README.md), line 12).
- Existing Iceberg tables use `format-version` 2 (`IcebergSeriesStore.java:74`).
- Buckets already use an OPEN and SEALED manifest with a checksum: "xxHash64 hex over the sorted episodeIds joined
  by newline" (`opentsx-model/src/main/avro/BucketManifest.avsc`).

## 3. Decisions

| Id | Decision | Reason |
|---|---|---|
| G1 | One edge table for all layers. Its fields are the P8 list plus `rel`, `directed`, `src_port`, `dst_port` and the layer version. | P8 already defines the integration contract. Adding fields keeps every P8 consumer valid. |
| G2 | Node IDs are typed strings `<type>:<local-id>` (section 4). | One ID space across layers; the type is readable without a join. |
| G3 | A layer version is immutable. A producer opens it, writes its nodes and edges, and seals it with counts and a checksum. The current state of a layer is its latest SEALED version. | KafScale cannot compact, so upserts and deletes on a log are costly to reconcile. The bucket manifest already uses this pattern. |
| G4 | Three new `TopicLayout` kinds: `layers`, `nodes`, `edges`. Record key: `<layer_id>@<version>`. | Same split as `buckets` (manifest) and `episodes` (content). One key keeps a version in one partition, in order. |
| G5 | Five new Iceberg tables in the same namespace: `layers`, `nodes`, `edges`, `cell_features`, `view_layout`. | Joins with `series` and `episodes` in one catalog. |
| G6 | Existing series keep their identity. No H3 tag is added to an existing series; series link to cells through edges. | A new tag changes `seriesId` (`SeriesKeys.java:37-38`). |
| G7 | Snapshot values per cell go to `cell_features`. Only values that change over time in a cell become series, with an `h3` tag at a fixed resolution chosen when the series is created. | Avoids one series per cell for static data; [13](13-SEMANTIK-ALS-ZEITREIHE.html) names the same cardinality trap for tags. |
| G8 | H3 cells are stored as BIGINT plus resolution. The hex string is for display only. | Iceberg v3 geometry types are not portable across engines yet ([CARTO, May 2026](https://carto.com/blog/iceberg-v3-geometry-one-year-later/)); BIGINT works in every engine. |
| G9 | Every layer carries a licence. OSM-derived layers carry `ODbL-1.0`. | Share-alike and attribution travel with derived data ([OSM copyright](https://www.openstreetmap.org/copyright)). |
| G10 | Python gets a producer with the same settings as `KafScaleClients.producerProps`. | The Python package has only a `TopicReader` (`python-package/opentsx/client/topics.py`); GeoQB is Python. |
| G11 | At seal time the manifest records the snapshot ID of every table the version touched, and the sync tags each of those tables with `<layer_id>@<version>`. No branch per layer. | Iceberg branches and tags are per table and tags keep snapshots from expiry ([Iceberg branching](https://iceberg.apache.org/docs/latest/branching/)); one branch per OPEN layer on a shared table would serialize publishes. |
| G12 | Diagram positions live in the `view_layout` table, outside the layer checksum. | Moving a box must not create a new model version. Structurizr keeps layout in the views of its workspace file, not in the model ([file types](https://docs.structurizr.com/workspaces/file-types)). |
| G13 | External tools never consume KafScale through consumer groups. They read through the layer service (section 13) or a bridge with manual assignment. | Consumer groups are broken on KafScale (section 2). ArcGIS Velocity and GeoEvent subscribe with groups ([Velocity Kafka feed](https://doc.esri.com/en/arcgis-velocity/latest/ingest/kafka.html), [GeoEvent input](https://enterprise.arcgis.com/en/geoevent/latest/ingest/subscribe-to-a-kafka-topic-for-json.htm)). |

## 4. Node IDs

| Type | Local ID | Example |
|---|---|---|
| `h3` | H3 cell, hex string | `h3:891f02b358bffff` |
| `osm` | `node/<id>`, `way/<id>`, `relation/<id>` | `osm:way/881148350` |
| `tag` | `<key>=<value>` | `tag:amenity=hospital` |
| `series` | `seriesId` (16 hex chars) | `series:58280afbd12100b7` |
| `atom` | `<twin>/<atom-id>` | `atom:h2-chain/park` |
| `asset` | inventory asset ID | `asset:h2-chain/park/T02` |
| `station` | `<catalog>/<id>` | `station:dwd/10577` |
| `var` | `<model>/<variable>` | `var:heat-plan-x/heat-demand` |
| `fact` | fact name | `fact:demographics.population` |
| `arch`, `k8s`, `kafka`, `dag`, `ol` | architecture elements (section 8) | `kafka:topic:tsx.windpark.episodes.v2` |

A `tag` node without its key is invalid. GeoQB's tag vertices store only the value and are rebuilt from the OSM
source (section 10).

## 5. Records (Avro, `org.opentsx.model.v2`)

**LayerManifest** (topic `tsx.<domain>.layers.v2`)

| Field | Type | Meaning |
|---|---|---|
| `layerId` | string | Stable slug, for example `twin:h2-chain`, `geoqb:transport-pos:amenity:chemnitz` |
| `version` | string | ULID of this version |
| `kind` | enum `WIRING, INVENTORY, MEASURED, EXPERT, OSM_CONTEXT, GRID, CELL_FEATURE, ARCHITECTURE` | `ARCHITECTURE` covers design and as-built layers (section 8) |
| `title` | string | Display name |
| `definition` | string | The layer as code: query, method parameters or source file, as JSON |
| `definitionHash` | string | xxHash64 hex of the canonical definition; for twins `TwinPlan.hash()` |
| `h3Resolution` | null or int | Resolution of all `h3` nodes in this version |
| `weight` | null or double | Sign or weight for scoring layers (GeoQB POS = +1, NEG = -1) |
| `licence` | string | SPDX ID or text |
| `state` | enum `OPEN, SEALED` | |
| `nodeCount`, `edgeCount` | long | Set on SEALED |
| `checksum` | null or string | Set on SEALED: xxHash64 hex over the sorted node IDs and edge identities, joined by newline |
| `snapshots` | map of string to long | Set on SEALED: table name to Iceberg snapshot ID (G11) |
| `provenance` | `Provenance` | Existing record (`source`, `producer`, `createdAt`, `pipeline`, `parentEpisodeId`) |

**Node** (topic `tsx.<domain>.nodes.v2`): `nodeId`, `nodeType`, `layerId`, `version`, `label` (null or string),
`lat`, `lon` (null or double), `h3Cell` (null or long), `h3Res` (null or int), `attrs` (map of string).

**Edge** (topic `tsx.<domain>.edges.v2`): `layerId`, `version`, `src`, `dst`, `rel`, `directed` (boolean), `srcPort`,
`dstPort` (null or string), `score` (null or double), `lagMicros` (null or long), `method`, `nullModel`,
`correction` (null or string), `pValue` (null or double), `episodeId` (null or string), `windowStart`, `windowEnd`
(null or timestamp-micros), `replayOffsets` (null or string), `runId` (null or string), `attrs` (map of string).

**Edge identity:** `layerId, version, src, srcPort, dst, dstPort, rel`. Ports are part of the identity because one
atom pair can carry several wires: `h2-chain.yaml` has 20 wires between 18 atom pairs.

**Write order for one version:** LayerManifest OPEN, then nodes and edges, then LayerManifest SEALED with counts,
checksum and snapshot IDs. A version that is never sealed is invisible to readers.

## 6. Iceberg tables (namespace as for `episodes`, default `tsx`)

| Table | Columns | Partition spec |
|---|---|---|
| `layers` | the LayerManifest fields, one row per version and state | none (small) |
| `nodes` | `node_id, node_type, layer_id, version, label, lat, lon, h3_cell BIGINT, h3_res INT, attrs MAP` | `identity(node_type)`, `bucket(16, node_id)` |
| `edges` | the Edge fields in snake_case | `bucket(32, layer_id)`; sort order `layer_id, version, src` |
| `cell_features` | `h3_cell BIGINT, h3_res INT, feature, value DOUBLE, as_of, layer_id, version` | `identity(h3_res)`, `bucket(32, h3_cell)` |
| `view_layout` | `view_id, layer_id, version, node_id, x DOUBLE, y DOUBLE, w DOUBLE, h DOUBLE` | none (small); not part of any checksum (G12) |

Readers use the current version per layer:

```sql
CREATE VIEW edges_current AS
SELECT e.* FROM edges e
JOIN (SELECT layer_id, max(version) AS version FROM layers WHERE state = 'SEALED' GROUP BY layer_id) v
  USING (layer_id, version);
```

ULIDs sort by time, so `max(version)` is the latest sealed version.

## 7. Sync from KafScale to Iceberg

A `GraphLogSync` follows the pattern of `EpisodeLogSync` (`opentsx-store-iceberg/src/main/java/org/opentsx/store/iceberg/EpisodeLogSync.java`):
manual partition assignment, read up to the end offsets seen at call time, return the next positions for the caller
to persist, and keep writes idempotent so a restart from older positions is safe.

On a SEALED manifest it recomputes counts and checksum from the records of that version. On a match it commits
nodes, edges and the SEALED row in one Iceberg transaction and tags the tables (G11). On a mismatch it commits
nothing for that version and reports the layer and version as an error. Fail closed.

## 8. Mapping of existing sources

| Source | Nodes | Edges (`rel`) | Layer kind |
|---|---|---|---|
| Twin plan | `atom:<twin>/<id>` | `wire` atom to atom with `srcPort`, `dstPort`; a `delayed` wire sets `lagMicros` to the plan's base step | WIRING; `definitionHash = TwinPlan.hash()` |
| Inventory | `asset:<id>`, `series:<id>`, `h3:<cell>` | `part_of` (asset to parent), `represents` (asset to atom), `measures` (asset to series), `located_on` (asset to cell, only for `plan.kind = geo`; floor-plan coordinates stay in `attrs`) | INVENTORY |
| P8 screening and confirmation | `series:<id>` | `depends_on` with `score`, `lagMicros`, `pValue`, `nullModel`, `correction`, `episodeId`, window, `replayOffsets`, `runId` | MEASURED |
| Expert influence matrix | `var:<model>/<name>` | `influences` with `score` 0 to 3, `method = expert`; `operationalized_by` (variable to series) | EXPERT |
| GeoQB `h3place`, `osmplace`, `osmtag` | `h3:`, `osm:`, `tag:` | `has_tag` (cell to tag, `score = tagCount`), `located_on` (OSM feature to cell) | OSM_CONTEXT, `licence = ODbL-1.0` |
| GeoQB `h3_grid_link` | | not stored; neighbours are computed with H3 functions | |
| GeoQB `fact` + `observed_at` | | none: becomes `cell_features` rows (`feature`, `value`, `as_of`) | CELL_FEATURE |
| DWD station catalog | `station:dwd/<id>`, `h3:<cell>` | `located_on`, `measures` | INVENTORY |
| Architecture design (definition as code, C4-shaped) | `arch:<kind>:<name>`; kinds `person`, `system`, `container`, `component`, `topic`, `schema`, `processor`, `deploy_node`, `instance`, `adr` | `contains`, `uses`, `publishes`, `consumes`, `reads`, `writes`, `deployed_as`, `runs_on`, `decides` | ARCHITECTURE, layer `arch.design` |
| `deploy/k3s/` manifests, the `dags` topic, OpenLineage, OpenTelemetry service graph | `k8s:<kind>:<ns>/<name>`, `kafka:topic:<TopicLayout name>`, `dag:processor:<id>`, `ol:job:<ns>/<name>` | the same vocabulary, with `method` = `parsed:k8s`, `declared:dag`, `observed:lineage` or `observed:otel`; `realizes` maps design to as-built | ARCHITECTURE, layers `asbuilt.*` |

A broker cannot report which client produces into a topic, so producer edges of the as-built layers come from the
`dags` topic (declared) and from producer spans (observed). A drift report compares `arch.design` with the
`asbuilt.*` layers edge by edge: convergent, divergent, absent, unmapped.

## 9. Example queries

Context around an asset (DuckDB with the H3 extension, or H3 rings computed in Python):

```sql
SELECT l.weight, e.layer_id, count(DISTINCT e.dst) AS tags, sum(e.score) AS hits
FROM nodes a
JOIN edges_current loc ON loc.src = a.node_id AND loc.rel = 'located_on'
JOIN nodes c ON c.node_id = loc.dst
JOIN nodes ring ON ring.h3_cell IN (SELECT unnest(h3_grid_disk(c.h3_cell, 3)))
JOIN edges_current e ON e.src = ring.node_id AND e.rel = 'has_tag'
JOIN layers l USING (layer_id, version)
WHERE a.node_id = 'asset:h2-chain/park/T02'
GROUP BY 1, 2;
```

Vester roles on any layer (active sum, passive sum, P, Q):

```sql
SELECT node, sum(a) AS active_sum, sum(p) AS passive_sum, sum(a) * sum(p) AS vester_p,
       sum(a) / nullif(sum(p), 0) AS vester_q
FROM (SELECT src AS node, score AS a, 0 AS p FROM edges_current WHERE layer_id = :layer
      UNION ALL SELECT dst, 0, score FROM edges_current WHERE layer_id = :layer)
GROUP BY node;
```

Expert matrix against measured dependencies:

```sql
SELECT b.src, b.dst, b.score AS expert, m.score AS measured, m.lag_micros, m.p_value
FROM edges_current b
JOIN edges_current os ON os.src = b.src AND os.rel = 'operationalized_by'
JOIN edges_current od ON od.src = b.dst AND od.rel = 'operationalized_by'
LEFT JOIN edges_current m ON m.layer_id = :measured_layer AND m.src = os.dst AND m.dst = od.dst
WHERE b.layer_id = :expert_layer;
```

A measured score is not on Vester's 0 to 3 scale. Mapping it (for example by quantiles of the significant edges) is
a documented, versioned part of the comparison, never implicit.

## 10. Findings in the GeoQB sample export

Counted in `workspace/sample1d/OSMLayers_Demo6a_9_OSM_tag_layer_node_list.csv` and `..._edge_list.csv` of
[`GeoQB/geoqb`](https://github.com/GeoQB/geoqb/tree/393955c852dfb50ff8f50d9f6443f45daf48b40e/workspace/sample1d).
A migration into this model fixes each one:

| Finding | Count | Fix |
|---|---|---|
| Every undirected edge is exported twice | `located_on_h3_cell`: 103,766 rows, 51,883 distinct; `hasOSMTag`: 71,380 rows, 35,690 distinct | directed edges with an identity (section 5) |
| `located_on_h3_cell` stores resolution 6 while its cells are resolution 9 | all 103,766 rows | resolution taken from the cell |
| `osmtag` vertices hold the tag value only (`yes`, `no` and `1` among them) | 438 vertices | `tag:<key>=<value>`, rebuilt from OSM |
| Layer IDs are display titles, such as `Leisure_POS_amenity around (Halle (Saale)) radius=15.0` | 105 strings | slug `layerId` plus `definition` |
| Snapshot values stored as edges (`observed_at`, population 2019) | 25,686 edges | `cell_features` rows (G7) |

GeoQB can create OpenTSx series IDs without Java: `opentsx.model.series_key.series_id` in the Python package is
checked against `opentsx-model/src/test/resources/testvectors/series-id.json` by `python-package/tests/test_model_v2.py`.

## 11. Implementation plan

Each step is one pull request against `master` with a regression test that fails before and passes after, module
README and `CHANGELOG.md` under "Unreleased", and `mvn -B -ntp verify` green on Java 17 and 21.

1. `opentsx-model`: `LayerManifest.avsc`, `Node.avsc`, `Edge.avsc` and their schema-history entries; golden vectors
   for edge identity and checksum; Java and Python produce identical bytes.
2. `opentsx-kafka-v2`: `TopicLayout.layers()`, `nodes()`, `edges()`; a graph writer that enforces the write order;
   `python-package`: a producer with `enable.idempotence=false` and `acks=all`.
3. `opentsx-store-iceberg`: the five tables and `GraphLogSync`; tests: replaying a version twice gives the same table
   content; a wrong checksum leaves the version invisible; a sealed version keeps its tags after snapshot expiry.
4. `opentsx-twin`: export of a plan as a WIRING layer; the inventory reader of stage E1 with validation against the
   plan, emitting an INVENTORY layer.
5. GeoQB (separate repository): a writer for these topics through the `opentsx` Python package, in place of
   TigerGraph, with the fixes of section 10.
6. P8 screening writes MEASURED layers; CSV import for expert matrices; the roles view.
7. The layer service of section 13: OGC API Features over GeoParquet snapshots, GEXF, GraphML and Parquet views, then
   the Gephi stream; a manual-assignment bridge for ArcGIS Velocity.
8. ARCHITECTURE layers: parse `deploy/k3s/` and the `dags` topic into `asbuilt.*`, import and export Structurizr
   workspace JSON for `arch.design`, and the drift report.

## 12. Open questions

1. Retention of old versions: keep them for time travel, or delete by predicate after N versions?
2. Should `TopicLayout.all()` include the graph topics? Provisioning on a shared broker must stay additive.
3. Streaming P8 screening produces one version per window. Is a small version per window acceptable, or are windows
   batched?
4. Two layers can describe the same node differently (for example an asset's position). Proposal: node rows belong to
   a layer version; a view picks by layer priority.
5. The export format of existing influence-matrix tools is unknown; the CSV import depends on it.
6. Is an atomic cross-table version needed (Nessie tags), or are per-table tags plus recorded snapshot IDs enough (G11)?
7. Does the catalog's change-event producer work against KafScale without `InitProducerId`? Not checked.

## 13. Serving layers to tools

A stateless layer service reads SEALED versions only and renders them per tool. Every response names layer ID,
version and licence.

| Path (proposed) | Format | Consumers |
|---|---|---|
| `/layers/{layer_id}/v/{version}/features` | OGC API Features Parts 1 and 3 over GeoParquet 1.1 snapshots | QGIS reads and edits ([3.12](https://qgis.org/project/visual-changelogs/visualchangelog312/)), ArcGIS Pro reads only ([Esri](https://doc.esri.com/en/arcgis-pro/latest/help/data/services/use-ogc-api-services.html)) |
| `/layers/{layer_id}/v/{version}/tiles` | OGC API Tiles, PMTiles | large H3 layers |
| `/layers/{layer_id}/v/{version}/graph.gexf` | GEXF 1.3 ([spec](https://github.com/gephi/gexf)): `rel` as edge kind, window as spell | Gephi, Gephi Lite, sigma.js |
| `/layers/{layer_id}/v/{version}/graph.graphml` | GraphML with ports; IDs escaped to `NMTOKEN` ([XML 1.0](https://www.w3.org/TR/xml/#NT-Nmtoken), no `/`), typed ID kept in a `node_id` key | yEd, yFiles, Cytoscape |
| `/layers/{layer_id}/v/{version}/nodes.parquet`, `edges.parquet` | lossless lists | DuckDB, Graphistry, Neo4j and Memgraph imports |
| `/layers/{layer_id}/stream` | Gephi Graph Streaming events `an`, `ae`, `ce`, `de` ([plugin](http://gephi.org/desktop/plugins/graphstreaming/)) | Gephi desktop, live and writable |

Edits that come back from a tool (OGC API Features Part 4 draft from QGIS, `ce` and `de` events from Gephi) become
edit commands against a new OPEN version. SEALED versions are never changed.

## 14. Out of scope

A graph database, graph algorithms (Node2Vec and similar run on exported layers outside OpenTSx), polygon geometry
(only points and H3 cells), and changes to the existing `series` and `episodes` schemas.
