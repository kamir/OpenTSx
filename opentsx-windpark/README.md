# opentsx-windpark — data-point catalog, normalizer and wind-farm simulator

This module captures wind-farm time series, inventories them, normalizes them and stores them as v2 episodes
([`opentsx-model`](../opentsx-model)). It also generates reproducible simulated data with known ground truth for
experiments, written to KafScale ([`opentsx-kafka-v2`](../opentsx-kafka-v2)) and/or Iceberg
([`opentsx-store-iceberg`](../opentsx-store-iceberg)).

## 1. Versioned data-point catalog (IEC 61400-25-2)

[`channel-catalog.yaml`](src/main/resources/windpark/channel-catalog.yaml) is the dictionary between raw source
channels and analysable signals. Load it with `ChannelCatalog.standard()`, or load your own file with `ChannelCatalog.load(path)`.

```yaml
catalog: opentsx-windpark-datapoints
version: 1.0.0                          # semantic version; written as label "catalog" onto every episode
iecEdition: "IEC 61400-25-2 (Ed. 2)"
qualityCodes: { 0: GOOD, 1: IMPLAUSIBLE, 2: MISSING, 3: SUBSTITUTED, 4: QUESTIONABLE }
channels: [...]                         # what a signal MEANS
sources:  [...]                         # how a concrete source system DELIVERS it
```

### Fields per entry

| Requirement | Where it lives |
|-------------|----------------|
| Channel ID / original name | `channels[].id` (canonical, e.g. `WMET.HorWdSpd`); `sources[].mappings[].sourceName` (vendor name, e.g. `WindSpeed_Avg`) |
| Turbine and component | turbine = tag `turbine` of the series key / IEC logical device; `channels[].component` (e.g. "nacelle anemometer") |
| Functional description | `channels[].description` |
| IEC mapping | `channels[].iec`: `logicalDevice`, `logicalNode`, `dataObject`, `dataAttribute`, `cdc`; edition in `iecEdition`. `ChannelDef.iecReference()` returns e.g. `T07/WROT.RotSpd.mag.f` |
| Data type and unit | `dataType`, `unit` (UCUM: `m/s`, `kW`, `Cel`, `deg`, `rpm`) |
| Scaling and sign convention | per source: `scale`, `offset`, `invertSign`, `sourceUnit` (converted by `Units`) |
| Time reference | per source: `timezone` (IANA), `timestamp: INSTANT/START/END`, `aggregation {function, interval}`; per channel: `sampling` |
| Quality flags | `qualityCodes` (one byte per value in the episode), plausibility band `min`/`max`, `resolution` |
| Source address | per source: `sourceAddress` (e.g. `csv:column=WindSpeed_Avg`, `opc:ns=2;s=...`, `iec61850:T07/WMET$MX$HorWdSpd`) |
| Extensions / deviations | `vendorSpecific: true` and `notes` on a channel; `status: CONFIRMED/PROVISIONAL/UNMAPPED` and `notes` on a mapping |

### Example: what `WindSpeed_Avg = 8.2` means

```yaml
- sourceName: WindSpeed_Avg             # vendor column
  channel: WMET.HorWdSpd                # -> nacelle anemometer, hub height, IEC <turbine>/WMET.HorWdSpd.mag.f
  sourceUnit: m/s
  aggregation: { function: MEAN, interval: PT10M }
  timestamp: END                        # the vendor stamps the END of the interval
  sourceAddress: "csv:column=WindSpeed_Avg"
  status: CONFIRMED
```

The source system declares `timezone: Europe/Berlin`. The normalizer therefore stores the value as series
`wind.met.wind_speed{aggregation=mean.PT10M,park=…,turbine=T07}` in m/s. Its timestamp is the interval **start**,
in UTC. Its quality byte is 0, or 1 if the value is outside 0–60 m/s.

The catalog describes the **meaning** of data, oriented on IEC 61400-25-2. It does not make the information exchange (IEC
61400-25-3) or a protocol mapping (IEC 61400-25-4) compliant, and it is not a certified model. Vendor-specific
signals such as the turbine operating state (`WTUR.TurSt`, codes 1–6) are marked as `vendorSpecific`.

### Versioning

* Raise the **patch** version for descriptions and notes.
* Raise the **minor** version for new channels, mappings or sources.
* Raise the **major** version when meaning, unit or IEC mapping changes. Such a change also changes the series it produces.

Every episode carries the catalog version as a label, so old data stays interpretable. Loading rejects duplicate ids and
mappings that point to unknown channels. An aggregated value with `timestamp: INSTANT` is also rejected.

## 2. Assets and inventory

* `TurbineType` holds rated power, rotor diameter, hub height, cut-in/rated/cut-out wind speed, rpm range and gear ratio
  (`GENERIC_3MW`, `GENERIC_8MW`).
* `Park.grid(...)` builds a park layout. `park.inventory(catalog)` lists every expected series (turbine × channel)
  as a `SeriesKey`. The inventory is what the metadata pool (OpenMetadata, P4) is filled from.

## 3. Normalizer

```java
Normalizer n = new Normalizer(ChannelCatalog.standard(), park, Duration.ofMinutes(10), "example-vendor-scada-10min");
Normalizer.Result r = n.normalize(readings);   // RawReading(turbine, sourceName, Instant, value, unit)
r.episodes();                                  // aligned FIXED_DURATION episodes with quality bytes
r.report();                                    // accepted / unmapped / unit errors / implausible / duplicates / ...
```

* **Names.** The normalizer uses the source mapping first, then the canonical name. Anything else is reported as *unmapped* and
  never guessed.
* **Values.** The steps run in this order: `scale`/`offset`, then sign inversion, then unit conversion, then quantization to `resolution`.
* **Time.** Local timestamps are converted to UTC in the source timezone (`n.local(...)`). `END` timestamps are moved to the
  interval start. Aggregates become their own series, tagged `aggregation=mean.PT10M`.
* **Quality.** Missing values (NaN) get code 2 and implausible values get code 1. The value itself is kept.
* **Labels.** Episodes carry the labels `catalog`, `iec`, `source.channel` and `mapping.status`, so a `PROVISIONAL` mapping stays visible downstream.

## 4. Simulator

`WindparkSimulator` takes a `Scenario` (YAML, see [`scenarios/ostsee-demo.yaml`](scenarios/ostsee-demo.yaml)) and produces
1 Hz signals for every turbine and channel:

* **Wind.** The hourly mean follows a Weibull distribution. Turbulence comes from Ornstein-Uhlenbeck processes, discretized exactly so they stay stable for every `dt`.
  Direction is a slow random walk.
* **Wakes.** A Jensen wake model applied per turbine: turbines downwind of others see lower wind speed.
* **Turbine.** The model covers the power curve, rotor and generator speed, pitch above rated wind, nacelle yaw with lag, and generator temperature.
  The operating state uses codes 1–6.
* **Operations.** `CURTAILMENT`, `FAULT` and `MAINTENANCE`.
* **Sensor faults.** `STUCK`, `DRIFT`, `OFFSET`, `SPIKES` and `GAP`.
* **Ground truth.** Every injected operation and fault is returned as `GroundTruth`. Episodes are also labelled with
  `gt.operation` and `gt.sensor_fault`, which makes the data a benchmark for anomaly and pattern detection.
* **Determinism.** The same scenario and seed give byte-identical episodes, including episode ids
  (`Ulid.deterministic`). Re-running a scenario therefore deduplicates instead of duplicating.
* **SCADA statistics.** Episodes are 10-minute blocks, and an episode's summary *is* the classic 10-minute SCADA statistic
  (mean/min/max/std).

### CLI

```bash
mvn -q -pl opentsx-windpark -am install -DskipTests
mvn -q -pl opentsx-windpark exec:java -Dexec.mainClass=org.opentsx.windpark.WindparkSim -Dexec.args="\
  --scenario opentsx-windpark/scenarios/ostsee-demo.yaml \
  --kafscale localhost:9092 --domain wind --index wind-index.tsv \
  --iceberg type=rest,uri=http://localhost:8181,warehouse=s3://lake/ --namespace tsx \
  --ground-truth ground-truth.json"
```

* `--kafscale` writes one sealed bucket (`<scenario>/<start>`, descriptor with seed, park and catalog version) to `tsx.<domain>.*.v2`. Replay it with
  `ReplayService` and verify it against the manifest checksum.
* `--iceberg` writes to the `episodes` and `series` tables of `<namespace>`. Any Iceberg catalog properties work (`type=jdbc`, `type=rest`, …).
* `--catalog` uses your own data-point catalog. `--ground-truth` writes the injected events as JSON.

The demo scenario has 10 turbines, 6 h at 1 Hz, 9 channels plus state, and 17 ground-truth events. It produces about 3,600 episodes and 2.2 M values.

## Tests

* `CatalogAndNormalizerTest` covers the catalog, IEC references, unit conversion, the vendor 10-minute mapping (END timestamp, sign,
  °F) and the unmapped/implausible/duplicate reporting.
* `SimulatorTest` covers determinism, 10-minute blocks and summaries, the power curve, wakes, operations, sensor faults vs.
  ground truth, and the 30-day Weibull mean.
* `WindparkSimEndToEndTest` runs the CLI into Iceberg (JDBC/SQLite catalog), and, when `KAFSCALE_BOOTSTRAP` is set, the CLI into
  KafScale with a verified bucket replay.
