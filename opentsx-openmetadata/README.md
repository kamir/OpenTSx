# opentsx-openmetadata: wind-farm metadata pool in OpenMetadata

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
bin/opentsx stats --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt --once true
# 2. the bot token, read without echo and kept out of the shell history
read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT
# 3. publish everything, including profiles (hourly) and data-quality results (10 min)
bin/opentsx metadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
  --om http://localhost:8585/api \
  --kafscale localhost:9092 --domain wind --profile-window PT1H --dq-window PT10M
# without a server: write every request as JSON for review
bin/opentsx metadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml --dry-run om-requests/
```

`bin/opentsx metadata --help` lists the keys. The command reads them through `OpenTsxConf.load`: from the command
line, the environment, `--properties-file` and the defaults file in `OPENTSX_CONF_DIR`. It checks every value and,
with profiles on, reads the statistics before the first request, so a bad KafScale bootstrap leaves no request behind.
It logs one line `starting metadata: key=value (source), ...` with the token as `******`. It exits with 0 on success,
1 on a runtime failure and 2 on a usage or configuration error.

* `--om` takes the API root, an `http` or `https` URL. `--dry-run <dir>` writes every request to `<dir>` instead.
  Set exactly one of them.
* `--profiles` decides whether the latest statistics are read from `tsx.<domain>.series-stats.v2` and published as
  table profiles and data-quality results: `always`, `never`, or `auto` (the default), which publishes them when
  `--kafscale` is on the command line. A bootstrap from `OPENTSX_KAFSCALE_BOOTSTRAP`, `KAFSCALE_BOOTSTRAP` or a file
  does not switch them on.
* `--profile-window` and `--dq-window` are durations such as `PT1H`, `PT60M` or `60m`. They select the statistics
  windows of the same length, so `PT60M` finds the windows the statistics processor writes as `PT1H`.

All writes are create-or-update, so publishing again is idempotent. Use a bot token. The command reads it from
`OPENMETADATA_JWT`, `OPENTSX_OPENMETADATA_TOKEN` or `opentsx.openmetadata.token` in a properties file. The token is
secret, so the command line refuses it:

```text
opentsx: --token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put opentsx.openmetadata.token in a properties file
```

The client retries the statuses 429, 502, 503 and 504 and I/O errors, up to 5 attempts. It waits 1, 2, 4 and 8 s,
or the seconds of a `Retry-After` header, at most 30 s each time.

The client is plain JDK HTTP plus Jackson. The official Java client `openmetadata-java-client:2.0.5` is compiled for
Java 21, and OpenTSx targets Java 17.

## Verification

The main CI runs no OpenMetadata server: it needs MySQL/Postgres and OpenSearch. `WindparkMetadataPublisherTest` therefore
runs the publisher against `FakeOpenMetadata`; the smoke test below covers the real server. That stand-in does two things:

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

The server also checks rules in code that the schemas do not contain (`DatabaseUtil.validateColumns` in 2.0.5):
`char`, `varchar`, `binary` and `varbinary` columns need a `dataLength`, arrays an `arrayDataType`, structs their
children. `FakeOpenMetadata` checks them too. The smoke test found the first one: variable-length Iceberg `binary`
columns (`time_deltas`, `quality`) are therefore published as `BYTES`, `fixed(n)` as `BINARY` with `dataLength` n.

The test also checks content against the simulator's ground truth. The stuck anemometer of T02 fails `opentsxFlatline`
in exactly the windows ending at minutes 70, 80 and 90. The power outage of T01 fails `opentsxCompleteness` at 130
and 140.

### Smoke test against a real server

The workflow [`openmetadata-smoke.yml`](../.github/workflows/openmetadata-smoke.yml) runs nightly, by hand and on pull
requests that change this module. It starts OpenMetadata 2.0.5 from the `docker-compose.yml` of the 2.0.5 release
(pinned by SHA-256) without the ingestion service, gets the token of `ingestion-bot`, and runs
`OpenMetadataSmokeTest` with `OM_URL` and `OPENMETADATA_JWT`. The test publishes the same simulated park as
`WindparkMetadataPublisherTest` with the real HTTP client and reads back:

* the episodes topic with its Avro schema and cleanup policy, the Iceberg `episodes` table and the signals view with
  its columns and IEC tags;
* the lineage from the episodes topic to the `episodes` table and to the series-stats topic;
* the latest profile of the signals view (wind speed: 3 turbines × 3600 values);
* the flatline results of the wind speed: 18 windows, 3 failed (minutes 70, 80 and 90).

It then publishes again to check that every write is create-or-update. Locally, against your own server:

```bash
read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT
OM_URL=http://localhost:8585/api \
  mvn -pl opentsx-openmetadata -am test -Dtest=OpenMetadataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```
