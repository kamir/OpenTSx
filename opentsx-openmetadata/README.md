# opentsx-openmetadata — wind-farm metadata pool in OpenMetadata

This module publishes the wind-farm inventory, data-point catalog, data assets, lineage, profiles and data-quality
results to [OpenMetadata](https://open-metadata.org) **2.0.x**. It was validated against the 2.0.5 API.

## Mapping

OpenMetadata has no sensor or time-series entity. The wind farm is therefore mapped onto existing entities:

| OpenTSx | OpenMetadata |
|---------|--------------|
| Data-point catalog `channel-catalog.yaml` | **Glossary** `opentsx-windpark-datapoints`. One term per channel (`WMET_HorWdSpd`, display `WMET.HorWdSpd`). The term table holds the IEC LD/LN/DO/DA and CDC, unit, resolution, range, sampling and status codes. A second table holds the **source mappings**: original name, address, scale/offset/sign, time zone, timestamp reference, aggregation and status. Synonyms are the metric and the vendor names. |
| IEC 61400-25-2 logical nodes | **Classification** `IEC61400-25` (WMET, WNAC, WROT, …) |
| Mapping status, vendor extensions | **Classification** `OpenTSx` (MappingConfirmed/Provisional/Unmapped, VendorSpecific) |
| Catalog version, IEC edition, park, sampling | **Custom properties** on `table` and `topic` |
| KafScale topics `tsx.<domain>.*.v2` | **Messaging service** `kafscale`. Topics carry the Avro schema (`schemaText` + `schemaFields`) and cleanup policy `delete`. |
| Iceberg tables `episodes`, `series`, `episode_index` | **Database service** `opentsx-iceberg` (CustomDatabase), table type `Iceberg`. Columns come from the real Iceberg schemas. |
| Wind farm | **View** `windpark_<park>_signals`: one row per (turbine, ts) and one column per channel. Each column is tagged with its glossary term and IEC node. The turbine inventory (type, rated power, rotor, hub, position) is in the description. |
| Simulator, log sync, statistics | **Pipeline service** `opentsx` with pipelines `windpark-sim`, `episode-log-sync`, `stats-processor` |
| Data flow | **Lineage**: sim → episodes/buckets topics; episodes topic → Iceberg `episodes` (via log sync); episodes topic → series-stats topic (via stats processor); `episodes` → signals view |
| `SeriesStats` (per window) | **Table profile** of the signals view, one per window. For each channel, the statistics of all turbines are merged exactly (counts, NaN share, min/max/mean/std, quartiles from merged sketches). |
| Data-quality checks | **Test definitions** `opentsxCompleteness`, `opentsxPlausibleRange`, `opentsxFlatline`, `opentsxGaps`, `opentsxFreshness` (platform `Other`). There is one column test case per channel and check, and one result per window. A result is Failed if any turbine fails, and the message names the failing turbines. |

Profiles and test results are aggregated per channel and window, not per event or turbine. This keeps OpenMetadata at
dozens to hundreds of writes per hour. The per-turbine detail stays in `series-stats.v2`.

## Usage

```bash
# 1. statistics (opentsx-stats) over the simulated episodes
StatsProcessorApp --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt --once true
# 2. publish everything, including profiles (hourly) and data-quality results (10 min)
WindparkMetadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
  --om http://localhost:8585/api --token "$OPENMETADATA_JWT" \
  --kafscale localhost:9092 --domain wind --profile-window PT1H --dq-window PT10M
# without a server: write every request as JSON for review
WindparkMetadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml --dry-run om-requests/
```

All writes are create-or-update, so publishing again is idempotent. Use a bot token. The client retries on 429 and 5xx.

The client is plain JDK HTTP plus Jackson. The official Java client `openmetadata-java-client:2.0.5` is compiled for
Java 21, and OpenTSx targets Java 17.

## Verification

No OpenMetadata server runs in CI: it needs MySQL/Postgres and OpenSearch. `WindparkMetadataPublisherTest` therefore runs
the publisher against `FakeOpenMetadata`. That stand-in does two things:

* **Schema validation.** It validates **every request body against the JSON schema of its endpoint** as shipped in
  `org.open-metadata:openmetadata-spec:2.0.5`.
* **Reference checks, as the server does them.** It checks:
  * that services, parents, tags and glossary terms exist;
  * that test definitions and their parameters exist;
  * column entity links;
  * that custom properties are registered before they are used;
  * lineage ids.

Two quirks of the 2.0.5 spec are handled in the test and documented there:
* `columnProfile.min/max` is `oneOf [number, integer]`. An integral double matches both branches, but the server
  accepts it.
* `createTestCaseResult.json` declares a wrong `$id`.

The test also checks content against the simulator's ground truth. The stuck anemometer of T02 fails `opentsxFlatline`
in exactly the windows ending at minutes 70, 80 and 90. The power outage of T01 fails `opentsxCompleteness` at 130
and 140.

Open: a smoke test against a real OpenMetadata 2.0.5 (docker compose) in a nightly job.
