# Run the deterministic windpark demo

Simulate, detect underperformance, store and replay windpark episodes locally,
verified with Maven 3.9.8 and JDK 21.0.8.

You need JDK 17 or newer, Maven 3.9.8 and Python 3 for the receipt check.
The initial build resolves dependencies. The demo needs no Docker, Kafka,
CP cluster or external catalog.

## Build and store the synthetic run

The order matters. Run from the repository root:

```bash
mvn --batch-mode --no-transfer-progress --projects opentsx-windpark \
  --also-make install
bin/opentsx windpark-demo run --output target/windpark-demo
```

The bundled [scenario](../../opentsx-windpark/src/main/resources/windpark/replay-demo.yaml)
uses seed 31 and the UTC window `2026-10-01T00:00:00Z` to `00:30:00Z`.
Three turbines supply ten channels each, sampled once per minute in ten-minute
episodes: 90 episodes and 900 values. North wind and the east-west layout avoid
wakes; turbulence and diurnal variation are zero for this acceptance fixture.

`target/windpark-demo/catalog.db` is the SQLite Iceberg catalog;
`target/windpark-demo/warehouse/` contains the actual Iceberg tables and Parquet
data. The pinned SQLite JDBC dependency is part of the runtime classpath.
The demo writes each batch twice to check idempotency, closes the store and
opens it again before publishing `target/windpark-demo/report.json`.

The existing `PowerCurveAnalysis` compares wind and power with the reference
curve. It detects underperformance; it does not diagnose its cause. Injected
ground truth is reported separately and is not an input to detection.

| Turbine | Injected condition | Produced kWh | Performance index | Lost kWh |
| --- | --- | ---: | ---: | ---: |
| T01 | Healthy control | 1500 | 1 | 0 |
| T02 | Fault, power zero | 1000 | 2/3 | 500 |
| T03 | Curtailment to 1500 kW | 1250 | 5/6 | 250 |

Expected energy is 1500 kWh per turbine. Both affected turbines have exactly
one detected period, `[00:10, 00:20)` UTC; T01 has none. The minute sampling and
catalog quantization give exact 3000, 0 and 1500 kW power levels. This is a
synthetic acceptance case, not a site-calibrated fault benchmark.

## Replay the closed store

```bash
bin/opentsx windpark-demo replay --output target/windpark-demo
cmp target/windpark-demo/report.json target/windpark-demo/replay.json
python3 - <<'PY'
import json
from pathlib import Path

report = json.loads(Path('target/windpark-demo/replay.json').read_text())
assert report['episodes'] == 90
assert report['values'] == 900
assert len(report['episodeSha256']) == 90
for turbine, result in report['analysis'].items():
    print(turbine, result['producedKwh'], result['performanceIndex'],
          len(result['underperformance']))
PY
```

Replay uses the existing-only Iceberg reader. It reads persisted episodes and
recomputes the analysis without rerunning the simulation. Each complete Avro
episode payload has a SHA-256 entry keyed by episode ID. The receipt includes
the full episode inventory, scenario, clock, counts, ground truth and analysis.
Random Iceberg metadata paths and snapshot times are outside receipt identity.

Acceptance requires byte-identical `report.json` and `replay.json`, including
all payload hashes. Two independent runs in unused directories must also
produce identical report bytes. Regression tests check those conditions and
reject changed data, changed receipts and missing state.

## Run the local edit checks

After the initial build, run the focused regressions without network access:

```bash
mvn --offline --batch-mode --no-transfer-progress --projects opentsx-windpark \
  --also-make test \
  --define test=WindparkDemoTest --define surefire.failIfNoSpecifiedTests=false
python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree

path = Path('opentsx-windpark/target/surefire-reports/TEST-org.opentsx.windpark.WindparkDemoTest.xml')
suite = ElementTree.parse(path).getroot()
assert int(suite.attrib['tests']) > 0
assert all(int(suite.attrib[key]) == 0 for key in ('failures', 'errors', 'skipped'))
print(suite.attrib['tests'], 'demo regressions passed')
PY
```

The Surefire check prevents an empty selection from looking successful.
Before pushing, run the complete local module reactor:

```bash
mvn --offline --batch-mode --no-transfer-progress --projects opentsx-windpark \
  --also-make verify
```

The existing broker integration cases remain explicit skips without
`KAFSCALE_BOOTSTRAP`. CI runs them with its isolated KafScale Proxy. Existing
affected-area selection covers this module; the launcher and Maven dependency
change select full CI. No narrower CI override is added.

## Resolve rejected output and replay

The [packaged image checks](published-artifact-acceptance.md) execute this demo
through the container dispatcher and validate published image provenance,
startup and numerical replay on native runners.

`output already exists: <directory>` rejects a second run before writing.
Choose a new unused output directory. The demo preserves existing data.

`missing demo report.json` or `missing demo catalog.db or warehouse` means the
saved run is incomplete. Restore that run's files or create a new run in an
unused directory; replay does not create a replacement database.

`cannot read persisted demo episodes` reports an unreadable catalog or data
file. Restore the saved run's catalog and warehouse together.

`replay does not match report.json` means the persisted payloads or original
receipt differ. Inspect the saved files; no new replay receipt is published.
An existing identical `replay.json` is accepted without rewriting it.
`existing replay.json does not match verified replay` preserves a conflicting
receipt. Keep the original receipts when investigating a mismatch.
