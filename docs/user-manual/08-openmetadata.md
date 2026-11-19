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

* `bin/opentsx metadata --help` lists the keys, their flags and their environment variables.
* Use a **bot token**. The command reads it from `OPENMETADATA_JWT`, `OPENTSX_OPENMETADATA_TOKEN` or
  `opentsx.openmetadata.token` in a properties file. The command line refuses it, so that the process list and the
  shell history never show it.
* Set exactly one of `--om` and `--dry-run`. `--om` is the API root, an `http` or `https` URL such as
  `http://localhost:8585/api`.
* `--profiles` switches the profiles and DQ results: `always`, `never`, or `auto` (the default), which publishes them
  when `--kafscale` is on the command line. `KAFSCALE_BOOTSTRAP` or `OPENTSX_KAFSCALE_BOOTSTRAP` in the environment
  does not switch them on. With profiles on, the command reads the statistics before the first request, so a
  KafScale bootstrap that fails leaves no request on the server or in the `--dry-run` directory.
* `--profile-window` and `--dq-window` select the statistics windows of the same length: `PT60M`, `60m` and `PT1H`
  are the same window.
* All writes are create-or-update, so publishing again is idempotent.
* The client retries 429, 502, 503 and 504 responses and network errors, up to 5 attempts, with pauses of 1, 2, 4
  and 8 s, or the seconds of a `Retry-After` header, at most 30 s each. Any other error status, 500 included, fails
  at once. A `GET` that gets 404 reads as a missing entity.
* The command logs one line `starting metadata: key=value (source), ...` on stderr with the token as `******`.
* The official Java client 2.0.5 requires Java 21, so OpenTSx uses a plain HTTP client instead.

## 8.3 Fix a failed run

The command exits with 0 on success, 1 on a runtime failure and 2 on a usage or configuration error. Each error is
one line on stderr.

| Message | Fix |
|---------|-----|
| `opentsx: --token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put opentsx.openmetadata.token in a properties file` | export `OPENMETADATA_JWT` as in [8.2](#82-publish-to-openmetadata) |
| `opentsx: --om: empty value; set a value or leave the key unset` | set `--om` to the API root, such as `http://localhost:8585/api` |
| `opentsx: opentsx.openmetadata.url: "localhost:8585/api" is not an http or https URL; expected the API root such as http://localhost:8585/api` | add the scheme |
| `opentsx: opentsx.openmetadata.dryRunDir: set together with opentsx.openmetadata.url; set only one of them: the directory for a dry run or the URL to publish to` | drop `--dry-run` or the URL, also from the environment and the files |
| `opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP` | `--profiles always` needs the KafScale bootstrap |
| `opentsx: --namspace: unknown option of metadata; did you mean --namespace?` | correct the flag; `bin/opentsx metadata --help` lists them |
| `opentsx: metadata: org.apache.kafka.common.KafkaException: Failed to construct kafka consumer; caused by ...` | correct `--kafscale` to the `host:port` of the KafScale proxy; no request was sent |
