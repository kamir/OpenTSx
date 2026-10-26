# 10. Python

Package: `python-package/` (`opentsx`), Python 3.10–3.13.

```bash
pip install -e python-package                    # core: numpy, pandas, scipy, fastavro, xxhash
pip install -e "python-package[iceberg]"         # + PyIceberg (SQL catalog, pyarrow)
pip install -e "python-package[kafka,viz]"       # + confluent-kafka, matplotlib/plotly
```

| Subpackage | What it gives you |
|------------|-------------------|
| `opentsx.model` | v2 data model, byte-identical to Java: `series_key`, `series_id`, `regular`, `irregular`, `values`, `encode`/`decode`, `summarize`/`merge_summaries`, ULIDs; pandas I/O in `opentsx.model.pandas_io` |
| `opentsx.store.iceberg` | `load_catalog`, `read_episodes`, `read_frame`: read the Iceberg tables written by Java |
| `opentsx.stats` | `QuantileSketch` (same format as Java), `stats_to_frame` for `SeriesStats` records |
| `opentsx.client` | `RetrievalClient` for the REST API (episodes, patterns, segments; the inventory, systems, signals, decisions; the scenario and runs of the twin); `TopicReader` reads the episodes of a topic directly from the broker, once, with a limit |
| `opentsx` (top level) | classic algorithms: `DFA`, `MFDFA`, `EventSynchronization`, `RIS`, plus `TimeSeriesObject`, `TSBucket` (see [chapter 11](11-classic-analytics.md)) |

## Typical notebook flow

For direct broker reads, `TopicReader` uses explicit partition assignment, disables automatic commits and
offset storage, and applies one `timeout_s` deadline across metadata, watermarks and polling.
See the [topic reader limits and error handling](../../python-package/README.md#read-a-topic).

```python
import pandas as pd
from opentsx import model as m
from opentsx.model.pandas_io import series_to_episode, episodes_to_frame

key = m.series_key("lab.temperature", {"sensor": "S1"}, "Cel")
s = pd.Series(values, index=pd.date_range("2026-10-01", periods=len(values), freq="1s", tz="UTC"))
ep = series_to_episode(s, key, source="notebook")          # REGULAR episode, summary computed
payload = m.encode("Episode", ep)                           # ready for Kafka/KafScale (key = ep["series"]["seriesId"])
df = episodes_to_frame([ep])                                # series_id, metric, episode_id, ts (UTC), value
```

* **PySpark:** read the Iceberg tables directly (`spark.read.table("tsx.episodes")`).
* **Decoding in Spark:** the `values` column is an array of doubles. Rebuild the timestamps from `t_start` and
  `interval_us` (REGULAR); for IRREGULAR episodes, use the Python `opentsx.model.timestamps_us`.
