# 10. Python

Install the `opentsx` package, turn a pandas series into a v2 episode and read episodes back.
The example output comes from package version 1.0.0 on Python 3.13 with pandas 3.1.1 and NumPy 2.5.5.

## Install the package

You need Python 3.10 to 3.13 (the versions CI tests) and a clone of the repository.
The package is in `python-package/` (`opentsx`). Run the commands from the repository root.

```bash
pip install -e python-package                    # core: numpy, pandas, scipy, fastavro, xxhash
pip install -e "python-package[iceberg]"         # + PyIceberg (SQL catalog, pyarrow)
pip install -e "python-package[kafka,viz]"       # + confluent-kafka, matplotlib/plotly
pip install -e "python-package[all]"             # + the extras kafka, iceberg, viz and yaml
```

## Find the right subpackage

| Subpackage | What it gives you |
|------------|-------------------|
| `opentsx.model` | v2 data model, byte-identical to Java ([chapter 3](03-data-model.md)). Series identity: `canonical`, `series_key`, `series_id`, `verify_series_key`. Episodes: `regular`, `irregular`, `values`. Time axis: `timestamps_us`, `encode_deltas`, `decode_deltas`. Summaries: `summarize`, `merge_summaries`, `variance`. Wire format: `encode`, `decode`, `fingerprint`, `schema`. IDs and hashes: `new_ulid`, `xxh64_hex`, `bucket_checksum`. pandas I/O in `opentsx.model.pandas_io`: `series_to_episode`, `episode_to_series`, `episodes_to_frame`. A refused episode or time axis raises `ValueError` with the message of Java, for example `tEnd: "20" is not after the last timestamp 20; expected a time after the last timestamp`; [`model-refusals.v1.json`](../datamodel-2026/testvectors/model-refusals.v1.json) lists them |
| `opentsx.store.iceberg` | `load_catalog`, `read_episodes`, `read_frame`: read the Iceberg tables written by Java |
| `opentsx.stats` | `QuantileSketch` (same format as Java), `stats_to_frame` for `SeriesStats` records. `QuantileSketch.from_bytes` raises `ValueError` with the messages of Java, for example `sketch bytes: truncated at <k> of <m>`, `sketch bytes: input ends inside a varint; expected complete varints`, `sketch bytes: bin index <n> out of range; expected an int` and `sketch bytes: <n> trailing bytes; expected exactly <m>` (full list, and how the alpha in a refusal is written, in the [opentsx-stats README](../../opentsx-stats/README.md#quantile-sketch-format-v1)) |
| `opentsx.client` | `RetrievalClient` for the REST API (episodes, patterns, segments; the inventory, systems, signals, decisions; the scenario and runs of the twin). Every failed request raises `RetrievalError` with `method`, `url`, `status` (`None` when no answer came) and the server's error `type`. `TopicReader` reads the episodes of a topic directly, once, with a limit, and raises `TopicLimitReached` above it |
| `opentsx` (top level) | classic algorithms `DFA`, `MFDFA`, `EventSynchronization`, `RIS` (see [chapter 11](11-classic-analytics.md)). `TimeSeriesObject` with `describe`, `normalize`, `detrend`, `diff`, `resample` and `apply`. `TSBucket` with `add`, `apply` and `filter` ([use TimeSeriesObject](#use-timeseriesobject)) |

## Build an episode from a pandas series

`series_to_episode` turns a pandas series with a time-zone-aware `DatetimeIndex` into an episode and computes its
summary. An equally spaced index with at least two values gives a `REGULAR` episode. Any other index gives an
`IRREGULAR_DELTA` episode. The summary counts NaN and infinite values in `nanCount`; `min`, `max`, `mean` and `m2`
cover the finite values, as in Java.

```python
import numpy as np
import pandas as pd
from opentsx import model as m
from opentsx.model.pandas_io import series_to_episode, episodes_to_frame

values = np.sin(np.linspace(0, 10, 50))
key = m.series_key("lab.temperature", {"sensor": "S1"}, "Cel")
s = pd.Series(values, index=pd.date_range("2026-10-01", periods=len(values), freq="1s", tz="UTC"))
ep = series_to_episode(s, key, source="notebook")
payload = m.encode("Episode", ep)
df = episodes_to_frame([ep])
print(ep["timeEncoding"], ep["intervalMicros"], ep["count"], ep["series"]["seriesId"], len(payload))
print(list(df.columns))
```

Output:

```text
REGULAR 1000000 50 1b2b2c4c590fd5e2 592
['series_id', 'metric', 'episode_id', 'ts', 'value']
```

`payload` holds the encoded episode for Kafka or KafScale. The Java writer uses `ep["series"]["seriesId"]` as the
record key. `episodes_to_frame` gives one row per value, with `ts` in UTC.

An index without a time zone fails with `ValueError: naive DatetimeIndex; localize it or pass assume_utc=True`.
Localize the index, or pass `assume_utc=True` when the times are UTC.

## Use TimeSeriesObject

`TimeSeriesObject.from_pandas` takes the timestamps of a `DatetimeIndex` as epoch microseconds, with pandas 2 and 3.
An index without a time zone is read as UTC. `diff(periods=k)` is the lag difference x[i] − x[i − k].
`normalize` with `zscore`, `minmax` or `robust` raises `ValueError` when the standard deviation, the range or the
interquartile range is 0. `TSBucket.apply` runs the function on one series after the other.

```python
import pandas as pd
from opentsx import TimeSeriesObject

s = pd.Series([1.0, 2.0, 4.0, 7.0, 11.0], index=pd.date_range("2026-10-01", periods=5, freq="1min", tz="UTC"))
ts = TimeSeriesObject.from_pandas(s, label="demo")
print(int(ts.timestamps[0]), int(ts.timestamps[1]))
print(ts.diff(periods=2).values)
TimeSeriesObject(data=[3.0, 3.0, 3.0]).normalize("zscore")
```

Output:

```text
1790812800000000 1790812860000000
[3. 5. 7.]
ValueError: values: the standard deviation is 0, and method 'zscore' divides by it; expected a positive standard deviation
```

## Query the retrieval server

You need a running retrieval server ([chapter 9](09-retrieval.md)).
`RetrievalClient("http://localhost:8080")` waits up to 120 seconds for each answer; pass `timeout=<seconds>` for
another limit. It checks times, ids, `series` and `timeout` before it sends a request and raises `ValueError` for
a bad one.
Every failed request raises `RetrievalError`; its message starts with the method and the URL, and `status` is `None`
when no answer came. `c.episode(<id>)` returns `None` only when the server answers 404 with the type `not_found`.
The server also answers `not_found` for a path it does not know, so check the base URL when every id gives `None`.
See [the messages and their fix](../../python-package/README.md#handle-retrieval-errors).

## Read a topic directly

You need the `kafka` extra and a reachable Kafka endpoint or KafScale proxy.
`TopicReader` uses explicit partition assignment, disables automatic commits and offset storage, and applies one
`timeout_s` deadline across metadata, watermarks and polling. `episodes()` raises `TopicLimitReached` when the topic
holds more than `max_records` records (default 20,000). A record that is not a v2 record raises `ValueError` with a
message that starts with `<topic>[<partition>]@<offset>:`. A v2 record of another type is skipped and counted in
`reader.skipped`.
See the [topic reader limits and error handling](../../python-package/README.md#read-a-topic).

## Read the Iceberg tables from Python

You need the `iceberg` extra. `read_episodes(catalog, start=..., end=...)` and `read_frame` take each bound as a
timezone-aware `datetime`, an ISO 8601 string with an offset that `datetime.fromisoformat` reads, or epoch
microseconds. Python 3.10 reads only the form that `datetime.isoformat` writes, with `Z` allowed, such as
`2026-10-01T00:00:00Z` or `2026-10-01T00:00:00.250+02:00`; it refuses `2026-10-01T00:00:00.5Z` as not an ISO 8601
time. Both functions check both bounds before they load the table. A bound without a time zone fails, for example
`ValueError: start: 2026-10-01T00:00:00 has no time zone; expected a timezone-aware datetime, an ISO 8601 time with an offset such as 2026-10-01T00:00:00Z, or epoch microseconds`.
Add the offset, for example `2026-10-01T00:00:00Z`.

## Read the Iceberg tables from Spark

* Read the Iceberg tables directly (`spark.read.table("tsx.episodes")`). `tsx` is the default namespace of the Java
  writers.
* The `values` column is an array of doubles. For `REGULAR` episodes, rebuild the timestamps from `t_start` and
  `interval_us`. For `IRREGULAR_DELTA` episodes, decode the `time_deltas` column with
  `opentsx.model.decode_deltas(t_start_us, count, time_deltas)`.

## Open the example notebooks

The repository has four Jupyter notebooks in [`python-package/notebooks/`](../../python-package/notebooks/).
They write nothing to the broker. You need Jupyter, the `viz` extra and a running retrieval server
([chapter 9](09-retrieval.md)). Set `OPENTSX_RETRIEVAL` to its URL.

| Notebook | What it does |
|----------|--------------|
| `01-zeitreihen-inventar.ipynb` | Shows the inventory of the retrieval server and one series with its coverage per day and its gaps. Without a reachable retrieval server, it reads `tsx.<domain>.episodes.v2` with `TopicReader`: set `KAFKA_BOOTSTRAP`, and `OPENTSX_DOMAIN` for a domain other than `wind`. |
| `02-ein-signal.ipynb` | Plots one signal of the system `h2-chain`, raw and cleaned, against its plausible range and its operating range. |
| `03-verproben.ipynb` | Checks a CSV file against a signal: unit, time step, time zone, value range, gaps and duplicate times. The sample file is `beispiel-windmessung.csv` with the columns `date;time;wind_kmh`. |
| `04-den-zwilling-fragen.ipynb` | Asks the twin of the system `h2-chain` questions. A run is computed and kept on the server. |

In `03-verproben.ipynb`, set `TIME_COLUMNS` and `VALUE` in the first code cell to the columns of your file. A column
`date`, `time` or `wind_kmh` is also found under its German name `Datum`, `Uhrzeit` or `Wind_kmh`. A name the file
does not have stops the cell with `<setting>: the file has no column "<name>"; expected one of <columns>`, where
`<setting>` is `TIME_COLUMNS` or `VALUE`.
