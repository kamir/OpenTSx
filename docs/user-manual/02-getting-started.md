# 2. Getting started

## 2.1 Prerequisites

| Tool | Version |
|------|---------|
| JDK | **17** or 21 (the Maven enforcer rejects older versions) |
| Maven | 3.9+ |
| Python | 3.10–3.13 (for the Python package) |
| Docker / Compose | for the local KafScale stack (optional) |

`bin/000_validate_environment.sh` checks the basics.

## 2.2 Build

```bash
mvn -q install -DskipTests          # all modules
mvn verify                          # with tests (a few minutes)
pip install -e "python-package[iceberg]"   # Python package, optionally with PyIceberg
```

Tests that need a running KafScale broker are skipped unless `KAFSCALE_BOOTSTRAP` is set.

## 2.3 Start KafScale locally

```bash
docker compose -f docker-compose.kafscale.yml up -d     # etcd, MinIO (S3), KafScale broker on localhost:9092
```

To run the conformance suite against the broker:

```bash
KAFSCALE_BOOTSTRAP=localhost:9092 mvn -pl opentsx-kafscale-conformance -am test
```

The suite writes `opentsx-kafscale-conformance/target/kafscale-capabilities.properties`, which lists what the
broker supports.

## 2.4 First end-to-end run (≈ 10 minutes)

**1. Simulate a wind farm.** This writes 6 h of 1 Hz data for 10 turbines and 10 channels as one sealed bucket to
KafScale, and also to a local Iceberg warehouse.

```bash
bin/opentsx windpark-sim --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
    --kafscale localhost:9092 --domain wind --index wind-index.tsv \
    --iceberg type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse --namespace tsx \
    --ground-truth ground-truth.json
```

The output shows the sealed bucket (episode count, checksum) and energy and states per turbine.
`ground-truth.json` lists every injected operation and sensor fault.

**2. Compute statistics and data quality.**

```bash
bin/opentsx stats --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt --once true
```

This writes 10-minute, hourly and global statistics per series to `tsx.wind.series-stats.v2`.

**3. Look at the metadata the tool would publish.** No OpenMetadata server is needed for this step.

```bash
bin/opentsx metadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml --dry-run om-requests/
```

Every request is written as JSON to `om-requests/`. To publish for real, use `--om http://host:8585/api --token
$OPENMETADATA_JWT --kafscale localhost:9092`.

**4. Search the data.**

```bash
bin/opentsx retrieval-server --port 8080 \
    --iceberg type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse --namespace tsx
```

```python
from opentsx import model as m
from opentsx.client import RetrievalClient
c = RetrievalClient("http://localhost:8080")
df = c.frame(metric="wind.met.wind_speed", tags={"turbine": "T07"}, limit=6)          # 1 h of 1 Hz data
gusts = c.segments("FEATURE_EXTREMUM", {"feature": "MAX", "prominence": 3, "window": "PT10M",
                                        "distance": "PT5M", "pre": "PT1M", "post": "PT2M"},
                   metric="wind.met.wind_speed", tags={"turbine": "T07"})
# segments are derived episodes (not stored) - use their values as the search pattern
hits = c.search(pattern=m.values(gusts[0]), metric="wind.met.wind_speed", top_k=10)
```

**5. Read the same tables from Python without Java.**

```python
from opentsx.store.iceberg import load_catalog, read_frame
cat = load_catalog(type="sql", uri="sqlite:////tmp/tsx/catalog.db", warehouse="file:/tmp/tsx/warehouse")
df = read_frame(cat, "tsx", metric="wind.turbine.power_active")
```

## 2.5 Without Docker

Every module can be tried without infrastructure:

* the in-memory store (`InMemorySeriesStore`),
* `RetrievalServer --demo true` (a synthetic store),
* the simulator writing to a local Iceberg warehouse (SQLite catalog, as above).
