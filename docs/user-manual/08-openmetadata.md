# 8. OpenMetadata

Module: `opentsx-openmetadata` ([README](../../opentsx-openmetadata/README.md)). Validated against OpenMetadata
**2.0.5**.

## 8.1 Check what gets published

| OpenTSx | OpenMetadata |
|---------|--------------|
| data-point catalog | **glossary**: one term per channel, with the IEC mapping, unit, time reference and source mappings |
| IEC logical nodes, mapping status | **classifications / tags** |
| catalog version, IEC edition, park, sampling | **custom properties** on tables and topics |
| KafScale topics | **topics** of the messaging service `kafscale`, with Avro schema and fields |
| Iceberg tables | **tables** of the service `opentsx-iceberg` (columns from the real Iceberg schemas) |
| wind farm | **view** `windpark_<park>_signals`: one column per channel (tagged with term and IEC node), turbine inventory in the description |
| simulator, log sync, statistics processor | **pipelines**, plus **lineage** between topics, tables and the view |
| `SeriesStats` | **table profiles** per window: merged over turbines, with quartiles from merged sketches |
| data-quality checks | **test definitions, test cases and results** per channel and window; failing turbines are named |

## 8.2 Publish to OpenMetadata

```bash
# review first: every request is written to a directory
bin/opentsx metadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml --dry-run om-requests/

# the bot token: paste it and press Enter; it is not echoed and not kept in the shell history
read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT

# publish, including profiles (hourly) and DQ results (10 minutes) from the statistics topic
bin/opentsx metadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
    --om http://localhost:8585/api \
    --kafscale localhost:9092 --domain wind --profile-window PT1H --dq-window PT10M
```

* Use a **bot token**. The client reads it from the environment variable `OPENMETADATA_JWT`. `--token <jwt>` also
  sets it and wins over the variable, but the process list and the shell history then show the token.
* All writes are create-or-update, so publishing again is idempotent.
* The client retries 429, 502, 503 and 504 responses, up to 5 attempts, with pauses of 1, 2, 4 and 8 s. Any other
  error status, 500 included, fails at once. A `GET` that gets 404 reads as a missing entity. A network error is not
  retried.
* The official Java client 2.0.5 requires Java 21, so OpenTSx uses a plain HTTP client instead.
