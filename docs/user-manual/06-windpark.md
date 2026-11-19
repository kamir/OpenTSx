# 6. Wind farm: catalog, normalizer, simulator, power curve

Module: `opentsx-windpark` ([README](../../opentsx-windpark/README.md), which has the full field reference).

## 6.1 Describe channels in the catalog (IEC 61400-25-2)

[`channel-catalog.yaml`](../../opentsx-windpark/src/main/resources/windpark/channel-catalog.yaml) maps raw source
channels to analysable signals. For every channel it records:

| Field | Example (`WMET.HorWdSpd`) |
|-------|---------------------------|
| channel id, metric | `WMET.HorWdSpd`, `wind.met.wind_speed` |
| component, description | nacelle anemometer, horizontal wind speed at hub height |
| IEC mapping and edition | `<turbine>/WMET.HorWdSpd.mag.f`, CDC `MV`, IEC 61400-25-2 Ed. 2 |
| data type, unit (UCUM), resolution, plausible range | FLOAT64, `m/s`, 0.01, [0, 60] |
| kind, sampling | ANALOG, `PT1S` |
| vendor extensions | `vendorSpecific`, `notes` (for example the turbine state codes) |

Per **source system**, the catalog also pins down:

* original name and source address,
* scale, offset and sign,
* source unit and time zone,
* **timestamp reference** (INSTANT / START / END),
* **aggregation** (for example 10-minute mean),
* mapping status (CONFIRMED / PROVISIONAL / UNMAPPED).

Together these answer the classic question "what exactly is `WindSpeed_Avg = 8.2`?". The catalog describes the
*meaning* of the data. It does not make the communication (IEC 61400-25-3/-4) compliant.

Versioning follows semantic versioning:

| Bump | When |
|------|------|
| patch | descriptions |
| minor | new channels or mappings |
| major | a change of meaning, unit or IEC mapping |

Every episode carries the catalog version as a label.

## 6.2 List the expected series

`TurbineType` (`GENERIC_3MW`, `GENERIC_8MW`), `Park.grid(...)`, `park.inventory(catalog)`. The inventory is the list
of expected series, and it feeds OpenMetadata ([chapter 8](08-openmetadata.md)).

## 6.3 Normalize raw readings

```java
Normalizer n = new Normalizer(ChannelCatalog.standard(), park, Duration.ofMinutes(10), "example-vendor-scada-10min");
Normalizer.Result r = n.normalize(readings);    // RawReading(turbine, sourceName, Instant, value, unit)
r.episodes();  r.report();
```

The normalizer:

* resolves names through the catalog and reports anything unknown instead of guessing,
* applies scale, offset and sign, converts units, and quantizes to `resolution`,
* converts local time to UTC, and moves END-stamped aggregates to the interval start, each aggregate becoming its own
  series (`aggregation=mean.PT10M`),
* marks quality with the catalog's codes `MISSING` and `IMPLAUSIBLE`, 2 and 1 in the standard catalog. A catalog
  without exactly one code from 1 to 255 for each of the two is refused.

## 6.4 Simulator and scenarios

A scenario is a YAML file; [`ostsee-demo.yaml`](../../opentsx-windpark/scenarios/ostsee-demo.yaml) is the example.
It defines:

* the park layout and turbine type, the seed, and the duration,
* wind: Weibull mean, turbulence, direction,
* operations: `CURTAILMENT`, `FAULT`, `MAINTENANCE`,
* sensor faults: `STUCK`, `DRIFT`, `OFFSET`, `SPIKES`, `GAP`.

Loading a scenario checks its rules and names the file and the entry that breaks one. `duration`, `sampleInterval`
and `episodeDuration` are positive. `park.rows` and `park.columns` are 1 or more. `park.spacingRotorDiameters`,
`park.rowSpacingRotorDiameters` and `park.wakeDecay` are 0 or more; 0 or a missing value takes the default of 5, 7 and
0.05. Every operation and sensor fault has an `offset` of `PT0S` or later and a positive `duration`.
`CURTAILMENT` needs `limitKw`, `DRIFT` needs `ratePerHour`, `OFFSET` needs `magnitude`, `SPIKES` needs `magnitude` and
`probability`; a parameter of another type is refused. Every named turbine exists in the park and every sensor-fault
`channel` is a metric of the catalog. The [README](../../opentsx-windpark/README.md#scenario-rules) lists the rules.

The simulator models:

* wind: Ornstein-Uhlenbeck turbulence with exact discretization, plus wakes (Jensen model),
* the turbine: power curve, rotor and generator speed, pitch, yaw and temperatures, and the operating state.

Output is deterministic, including episode ids. Every injected event is returned as **ground truth**, which makes the
data a benchmark for data quality, pattern search and forecasting. The operator catalog lists the scenario parameters
under `gen.windpark` ([chapter 17](17-operators.md)).

Run the shipped scenario from the repository root. Without a sink the run writes no topic and no table. With the
default `--sinks auto`, a sink is on only when `--kafscale` or `--iceberg`, or `--conf` with its key, is on the command
line. `--ground-truth` writes the injected events to `ground-truth.json` in the current directory.

```bash
bin/opentsx windpark-sim --scenario opentsx-windpark/scenarios/ostsee-demo.yaml --ground-truth ground-truth.json
```

It logs one `starting windpark-sim` line with every value and its source on stderr. It prints a summary and one line
per turbine with the energy, the mean wind and the seconds per operating state code (trimmed):

```text
scenario ostsee-demo: 3,600 episodes, 2,160,000 values, 17 ground-truth events
  T01    4538.4 kWh  mean wind  7.51 m/s  states {1=21310.0, 2=1.0, 3=289.0}
  T02    2927.4 kWh  mean wind  6.50 m/s  states {1=21503.0, 2=97.0}
```

Add flags to write the episodes. Every flag takes a value. `bin/opentsx windpark-sim --help` prints the keys behind
the flags with their defaults and environment variables, and `--conf <key>=<value>` and `--properties-file <path>` work
as in [chapter 18](18-configuration.md):

| Flag | Effect |
|------|--------|
| `--kafscale <bootstrap>` | creates the missing topics of the domain and writes the episodes as one sealed bucket. `<bootstrap>` is the address of the KafScale proxy, for example `localhost:9092`. |
| `--domain <domain>` | topic domain, default `wind`. Used with the KafScale sink. |
| `--partitions <n>` | partitions of the data topics when they are created, 1 or more, default `6`. Used with the KafScale sink. |
| `--index <file>` | writes the episode index to `<file>` (`FileEpisodeIndex`). Without it the index lives in memory and is lost at exit. Used with the KafScale sink. |
| `--iceberg <props>` | writes the episodes to Iceberg. `<props>` is a comma-separated list of `key=value` catalog properties with `warehouse` and exactly one of `type` and `catalog-impl`, for example `type=jdbc,uri=jdbc:sqlite:/tmp/tsx/catalog.db,warehouse=file:/tmp/tsx/warehouse`. Create `/tmp/tsx` first ([chapter 5](05-stores.md)). |
| `--namespace <ns>` | Iceberg namespace, default `tsx`. Used with the Iceberg sink. |
| `--sinks <list>` | `auto` (default), `kafscale`, `iceberg` or `kafscale,iceberg`. With `auto` a sink is on when its flag, or `--conf` with its key, is on the command line; a value from the environment, for example `KAFSCALE_BOOTSTRAP`, or from a file adds no sink. |
| `--catalog <file>` | channel catalog YAML. Default: the built-in catalog. |
| `--ground-truth <file>` | writes the injected events as JSON after the sinks. A directory, or a file in a directory that does not exist, is refused before a client opens. |

The command checks the keys, the required Iceberg properties, the ground-truth path, the scenario and the catalog
before it opens a client. A usage or configuration error prints one line on stderr, creates no topic and exits with
code 2:

```text
opentsx: --partitons: unknown option of windpark-sim; did you mean --partitions?
```

The last Iceberg batch is written when the run closes its clients. If that write fails, or closing the producer, the
index or the Iceberg store fails, the command prints one line `opentsx: windpark-sim: <failure>; caused by <cause>` on
stderr and exits with code 1 without the summary. When the run has already failed, for example because the
ground-truth file cannot be written, these close failures follow as `; suppressed <failure>`.
`--conf opentsx.log.level=debug` also logs the stack trace of a runtime failure.
[Appendix A](appendix-a-cli.md) lists all commands.

## 6.5 Power-curve analysis

`PowerCurveAnalysis` applies the method of bins (IEC 61400-12-1, simplified):

* It works on 10-minute means of nacelle wind speed and active power, with 0.5 m/s bins.
* NaN and infinite values are skipped. An interval without a wind or a power value is left out. A partly covered
  interval counts with the mean of the values it has.
* Each bin is compared with the turbine type's reference curve, or with any curve you supply.
* The **performance index** is produced / expected energy.
* **Underperformance periods** are runs of intervals below 80 % of expected, with the lost energy.

The operator catalog lists the parameters under `powercurve.bins` ([chapter 17](17-operators.md)).

```java
PowerCurveAnalysis.Result r = PowerCurveAnalysis.analyze(TurbineType.GENERIC_3MW, windEpisodes, powerEpisodes,
        PowerCurveAnalysis.Settings.defaults());
r.performanceIndex();  r.bins();  r.underperformance();
```

Against simulator ground truth:

* A healthy turbine scores PI ≈ 1.
* Curtailment and faults show up as underperformance, almost entirely inside the injected periods.
