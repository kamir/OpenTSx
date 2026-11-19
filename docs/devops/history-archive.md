# History archive on k3s: backfill and forward archiving

The market and weather history lives in the archive format of [`opentsx-market`](../../opentsx-market/README.md) on
the PVC `opentsx-history`: two Jobs fill it once, three CronJobs keep it current.

## What runs

| Object | Schedule | Command (image `opentsx/tools`) |
|--------|----------|----------------------------------|
| PVC `opentsx-history` | n/a | 2 Gi, mounted at `/data/history` |
| Job `opentsx-history-backfill` ([`backfill-job.yaml`](../../deploy/k3s/history/backfill-job.yaml)) | once | `opentsx market backfill --archive /data/history --series market --from-year 2019` |
| Job `opentsx-weather-backfill` ([`weather-backfill-job.yaml`](../../deploy/k3s/history/weather-backfill-job.yaml)) | once | `opentsx market weather-backfill --archive /data/history --from-year 2019` (hourly temperature and wind, 10-minute radiation of the site stations) |
| CronJob `opentsx-market-forward` ([`market-forward.yaml`](../../deploy/k3s/history/market-forward.yaml)) | `41 13 * * *` UTC | `opentsx market forward --archive /data/history --series market` (running and previous year; complete years are skipped) |
| CronJob `opentsx-weather-forward` ([`weather-forward.yaml`](../../deploy/k3s/history/weather-forward.yaml)) | `23 6 * * *` UTC | `opentsx market weather-forward --archive /data/history` (running and previous year) |
| CronJob `opentsx-mosmix-archive` ([`mosmix-archive.yaml`](../../deploy/k3s/history/mosmix-archive.yaml)) | `40 4,10,16,22 * * *` UTC | `opentsx market mosmix-archive --archive /data/history` (raw MOSMIX_L of 10579, 10097, 10046, E5411) |

Day-ahead results for the next day are published around 12:45 CET/CEST, and SMARD updates its files shortly after.
13:41 UTC is after that in both summer and winter. MOSMIX_L is issued at 03, 09, 15 and 21 UTC and is on the server
about 1 h later.
The DWD does not keep historical MOSMIX forecasts on opendata.dwd.de, only the latest runs: forecast history exists
only from the day `opentsx-mosmix-archive` first runs.

## Steps

```bash
# after 30-deploy.sh (namespace opentsx, image opentsx/tools imported)
# optional: ENTSOE_TOKEN=<token>, NTP_CLIENT_ID=<id> and NTP_CLIENT_SECRET=<secret>, one per line, in a file of mode 600 outside the repository
kubectl -n opentsx create secret generic opentsx-market --from-env-file=<file>
kubectl -n opentsx apply -k deploy/k3s/history                       # PVC + three CronJobs
kubectl -n opentsx apply -f deploy/k3s/history/backfill-job.yaml     # one-off, about 1 h at the default pause
kubectl -n opentsx apply -f deploy/k3s/history/weather-backfill-job.yaml   # one-off, a few minutes
kubectl -n opentsx logs -f job/opentsx-history-backfill
kubectl -n opentsx create job --from=cronjob/opentsx-mosmix-archive mosmix-now   # first MOSMIX run right away
```

Without the Secret `opentsx-market`, `opentsx-history-backfill` and `opentsx-market-forward` skip ENTSO-E and
netztransparenz.de and log the reason.

Check the archive: `kubectl -n opentsx exec` into any pod that mounts the PVC, or run a Job with
`opentsx market report --archive /data/history`. It lists every series and year with rows, quality summary and
whether the file still matches the SHA-256 in the manifest.

## Network

Egress to `www.smard.de`, `api.energy-charts.info`, `web-api.tp.entsoe.eu`, `ds.netztransparenz.de`,
`identity.netztransparenz.de` and `opendata.dwd.de` (HTTPS). The collector paces its requests: at least 500 ms
between requests, 1 s for ENTSO-E, and 600 ms for netztransparenz.de, whose WAF blocks an IP for two hours after
repeated bursts.

## Sizing

Measured on 2026-10-12: the SMARD series of the default market set for 2019 to 2026 take 13 MB gzip'ed
(quarter-hourly series about 0.2 MB per year each), and the Energy-Charts price 0.5 MB.
Raw MOSMIX is about 19 kB per station and issue, so about 30 MB per year for four stations and four runs a day.
The DWD observations of the nine station parameters for 2019 to 2026 add 6.3 MB. The 2 Gi PVC leaves plenty of room.

## Manifest

Every series directory has a `manifest.json` (format `opentsx-history/1`, example
[`history-manifest-example.json`](history-manifest-example.json)). It records the source, licence, unit and origin,
and per year the file, SHA-256, rows, queries (tokens redacted), retrieval time, completeness, and a quality report
(gaps, DST days, resolutions, implausible and robust outliers, statistics). The bulk data stays out of git; only
the format and this example are checked in.
