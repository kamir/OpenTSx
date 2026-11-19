# 2. Getting started

## 2.1 Prerequisites

| Tool | Version |
|------|---------|
| JDK | **17** or 21 (the Maven enforcer rejects older versions) |
| Maven | 3.9+ |
| Python | 3.10 to 3.13 (for the Python package) |
| Docker / Compose | for the local KafScale stack (optional) |

`bin/000_validate_environment.sh` checks the JDK, Maven 3.9+, Git, Docker and the host resources. It exits 1 when a
check fails and lists the failed checks on stderr; warnings alone exit 0. A `java`, `mvn` or `git` that does not start
is a failed check with the first line of its output, for example
`Maven: mvn -version failed: The JAVA_HOME environment variable is not defined correctly,`. Set `JAVA_HOME` to the
directory of a JDK 17 or later and run the script again.

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
KafScale, and also to a local Iceberg warehouse. The SQLite catalog needs an existing directory, so create
`/tmp/tsx` first.

```bash
mkdir -p /tmp/tsx
bin/opentsx windpark-sim --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
    --kafscale localhost:9092 --domain wind --index wind-index.tsv \
    --iceberg type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse --namespace tsx \
    --ground-truth ground-truth.json
```

The output shows the sealed bucket (episode count, checksum) and energy and states per turbine.
`ground-truth.json` lists every injected operation and sensor fault.

Without the directory the simulator stops with
`org.sqlite.SQLiteException: [SQLITE_CANTOPEN] Unable to open the database file`. Create the directory and run
the command again.

**2. Compute statistics and data quality.**

```bash
bin/opentsx stats --kafscale localhost:9092 --domain wind --checkpoint stats.ckpt --once true
```

This writes 10-minute, hourly and global statistics per series to `tsx.wind.series-stats.v2`.

**3. Look at the metadata the tool would publish.** No OpenMetadata server is needed for this step.

```bash
bin/opentsx metadata --scenario opentsx-windpark/scenarios/ostsee-demo.yaml --dry-run om-requests/
```

Every request is written as JSON to `om-requests/`. To publish for real, read the bot token with
`read -rs OPENMETADATA_JWT && export OPENMETADATA_JWT` and use
`--om http://localhost:8585/api --kafscale localhost:9092` ([chapter 8](08-openmetadata.md#82-publish-to-openmetadata)).

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
# Segments are derived episodes and are not stored. Use their values as the search pattern.
hits = c.search(pattern=m.values(gusts[0]), metric="wind.met.wind_speed", top_k=10)
```

**5. Read the same tables from Python without Java.**

```python
from opentsx.store.iceberg import load_catalog, read_frame
cat = load_catalog(type="sql", uri="sqlite:////tmp/tsx/catalog.db", warehouse="file:/tmp/tsx/warehouse")
df = read_frame(cat, "tsx", metric="wind.turbine.power_active")
```

## 2.5 Run without Docker or a broker

These parts run without Docker or a broker:

* The in-memory store `InMemorySeriesStore`.
* `bin/opentsx retrieval-server --port 8080 --source demo`. It serves a synthetic demo store. Without `--source`,
  `--iceberg` and `--kafscale` it serves the same store.
* `bin/opentsx windpark-sim` with `--iceberg` and without `--kafscale`. It writes to a local Iceberg warehouse
  (SQLite catalog, as in step 1).
* `bin/opentsx twin-live demo`. It plays twin events through the SSE bridge on port 8090 with topics in memory
  ([README](../../opentsx-twin-live/README.md)).

`opentsx-kafscale-conformance`, `opentsx-connectors` and `opentsx-lg` need a Kafka-protocol broker.
