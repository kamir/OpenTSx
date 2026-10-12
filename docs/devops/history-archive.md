# History archive on k3s: backfill and forward archiving

> **Kurzfassung (Deutsch).** Die Historie (Marktdaten, später Wetter) liegt im Archivformat von
> [`opentsx-market`](../../opentsx-market/README.md) auf einem eigenen PVC `opentsx-history`. Einmalig füllt der Job
> `backfill-job.yaml` die Jahre 2019 bis heute (fortsetzbar, abgeschlossene Jahre werden übersprungen). Danach
> halten zwei CronJobs das Archiv aktuell: `opentsx-market-forward` täglich um 13:41 UTC (laufendes und Vorjahr,
> nach Veröffentlichung der Day-Ahead-Ergebnisse) und `opentsx-mosmix-archive` alle 6 Stunden (rohe MOSMIX_L-Dateien
> der Zwillingsstandorte). **Der DWD hält keine historischen MOSMIX-Prognosen vor**: Prognosehistorie entsteht nur
> ab dem Tag, an dem der CronJob läuft. Zugangsdaten (`ENTSOE_TOKEN`, `NTP_CLIENT_ID`, `NTP_CLIENT_SECRET`) kommen
> aus dem optionalen Secret `opentsx-market`; ohne sie werden ENTSO-E und netztransparenz.de übersprungen.

## What runs

| Object | Schedule | Command (image `opentsx/tools`) |
|--------|----------|----------------------------------|
| PVC `opentsx-history` | — | 2 Gi, mounted at `/data/history` |
| Job `opentsx-history-backfill` ([`backfill-job.yaml`](../../deploy/k3s/history/backfill-job.yaml)) | once | `opentsx market backfill --archive /data/history --series market --from-year 2019` |
| CronJob `opentsx-market-forward` ([`market-forward.yaml`](../../deploy/k3s/history/market-forward.yaml)) | `41 13 * * *` UTC | `opentsx market forward --archive /data/history --series market` (running and previous year; complete years are skipped) |
| CronJob `opentsx-mosmix-archive` ([`mosmix-archive.yaml`](../../deploy/k3s/history/mosmix-archive.yaml)) | `40 4,10,16,22 * * *` UTC | `opentsx market mosmix-archive --archive /data/history` (raw MOSMIX_L of 10579, 10097, 10046, E5411) |

Day-ahead results for the next day are published around 12:45 CET/CEST, and SMARD updates its files shortly after.
13:41 UTC is after that in both summer and winter. MOSMIX_L is issued at 03, 09, 15 and 21 UTC and is on the server
about 1 h later.

## Steps

```bash
# after 30-deploy.sh (namespace opentsx, image opentsx/tools imported)
kubectl -n opentsx create secret generic opentsx-market \
    --from-literal=ENTSOE_TOKEN=... --from-literal=NTP_CLIENT_ID=... --from-literal=NTP_CLIENT_SECRET=...   # optional
kubectl -n opentsx apply -k deploy/k3s/history                       # PVC + both CronJobs
kubectl -n opentsx apply -f deploy/k3s/history/backfill-job.yaml     # one-off, about 1 h at the default pause
kubectl -n opentsx logs -f job/opentsx-history-backfill
kubectl -n opentsx create job --from=cronjob/opentsx-mosmix-archive mosmix-now   # first MOSMIX run right away
```

Check the archive: `kubectl -n opentsx exec` into any pod that mounts the PVC, or run a Job with
`opentsx market report --archive /data/history`. It lists every series and year with rows, quality summary and
whether the file still matches the SHA-256 in the manifest.

## Network

Egress to `www.smard.de`, `api.energy-charts.info`, `web-api.tp.entsoe.eu`, `ds.netztransparenz.de`,
`identity.netztransparenz.de` and `opendata.dwd.de` (HTTPS). The collector paces its requests: at least 500 ms
between requests, 1 s for ENTSO-E, and 600 ms for netztransparenz.de, whose WAF blocks an IP for two hours after
repeated bursts.

## Sizing

The default market set for 2019 to 2026 is about 20 MB gzip'ed (quarter-hourly series about 1.5 MB per year each).
Raw MOSMIX is about 19 kB per station and issue, so about 30 MB per year for four stations and four runs a day. The
2 Gi PVC leaves room for the DWD station backfill (hourly and 10-minute values of a few stations).

## Manifest

Every series directory has a `manifest.json` (format `opentsx-history/1`, example
[`history-manifest-example.json`](history-manifest-example.json)). It records the source, licence, unit and origin,
and per year the file, SHA-256, rows, queries (tokens redacted), retrieval time, completeness, and a quality report
(gaps, DST days, resolutions, implausible and robust outliers, statistics). The bulk data stays out of git; only
the format and this example are checked in.
