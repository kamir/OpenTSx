# OpenTSx Python package

[![Python Version](https://img.shields.io/badge/python-3.10%E2%80%933.13-blue)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

The Python side of OpenTSx. It contains the v2 data model (byte-identical to Java), Iceberg reading, streaming-statistics
helpers, a client for the retrieval REST API, and the classic algorithms (DFA, MFDFA, event synchronization, RIS).

## Installation

Install from the repository; the package is not published on PyPI yet:

```bash
pip install -e python-package                 # numpy, pandas, scipy, fastavro, xxhash
pip install -e "python-package[iceberg]"      # + PyIceberg
pip install -e "python-package[yaml]"         # + PyYAML, for YAML configuration files
pip install -e "python-package[kafka,viz]"    # + confluent-kafka, plotting
```

## Subpackages

| Subpackage | Content |
|------------|---------|
| `opentsx.model` | series identity, episodes, Avro single-object encode/decode, summaries, pandas I/O (`opentsx.model.pandas_io`) |
| `opentsx.store.iceberg` | `load_catalog`, `read_episodes`, `read_frame` for tables written by the Java Iceberg store |
| `opentsx.stats` | `QuantileSketch` (same bytes as Java), `stats_to_frame` |
| `opentsx.conf` | `OpenTsxConf`: the key registry `opentsx.config.v1`, the six configuration sources and the loader of the `opentsx` command line |
| `opentsx.client` | `RetrievalClient`: episodes, pattern search, similar episodes, segments; `TopicReader`: bounded direct topic reads |
| `opentsx` | classic algorithms `DFA`, `MFDFA`, `EventSynchronization`, `RIS`; `TimeSeriesObject`, `TSBucket` |

See the [user manual](../docs/user-manual/10-python.md) (chapters 10 and 11).

## Read the configuration

`opentsx.conf` reads the key registry that the Java module `opentsx-conf` ships; the package holds a byte-identical copy.
A value comes from one of six sources, highest first: code (`set`), the command line, the environment,
`--properties-file`, the defaults file in `$OPENTSX_CONF_DIR`, the registry default. The rules are in
[TASK-020](../EVOLUTION/TASK-020-configuration-and-cli.md).

```python
import os
from opentsx.conf import OpenTsxConf

conf = OpenTsxConf.load(["stats", "--alpha", "0.02"], os.environ, os.environ.get("OPENTSX_CONF_DIR")).conf
conf.getDouble("opentsx.stats.sketchAlpha")    # 0.02
conf.source("opentsx.stats.sketchAlpha")       # 'commandLine'
conf.getDurationList("opentsx.stats.windows")  # [datetime.timedelta(seconds=600), datetime.timedelta(seconds=3600)]
```

A mistake raises `OpenTsxConfError` with one line that names the key, the flag or the file. A YAML file needs the
`yaml` extra; without it the load fails with `<file>: YAML files need the extra opentsx[yaml]; run pip install "opentsx[yaml]"`.

`OpenTsxConf.load` sets the level of the logger `opentsx` when a source sets `opentsx.log.level` (`error`, `warn`,
`info`, `debug`, `trace`). `Loaded.startLine()` returns one line `starting <command>: <key>=<value> (<source>), ...`
with the keys the command reads, secrets as `******`; `toDebugString()` returns `key=value (source)` lines.

## Run the opentsx command

The installation registers the console script `opentsx`. The Python runtime runs `config list`, `config show`,
`config get` and `version`; every other command runs in the Java runtime, `bin/opentsx` or the `opentsx-tools`
image. The exit code is 0 on success, 1 on a runtime failure and 2 on a usage or configuration error. A result that
cannot be written to stdout is a runtime failure.

```bash
opentsx config list
opentsx config show --conf opentsx.domain=solar
opentsx config get opentsx.retrieval.port
opentsx version
```

`opentsx config show` prints every key with a value and its source; secrets show as `******`. A misspelled key
exits with 2 and prints one line on stderr:

```text
opentsx: opentsx.domian: unknown key; did you mean opentsx.domain?
```

`opentsx <command> --help` prints the keys a command reads, also for a command that runs only in the Java runtime:

```bash
opentsx stats --help
```

[Chapter 18](../docs/user-manual/18-configuration.md) of the user manual shows defaults files, YAML, secrets and the
error messages with their fix. [Appendix D](../docs/user-manual/appendix-d-configuration.md) lists every key.

## Query the retrieval server

You need a running retrieval server ([chapter 9](../docs/user-manual/09-retrieval.md)). The examples use
`http://localhost:8080`. Each request waits up to `timeout` seconds; the default is 120 seconds.

```python
from opentsx.client import RetrievalClient, RetrievalError

c = RetrievalClient("http://localhost:8080", timeout=120.0)
eps = c.episodes(metric="demo.signal", tags={"n": "1"}, start="2026-09-21T00:00:00Z", end="2026-09-22T00:00:00Z")
```

The client checks its arguments before it sends a request and raises `ValueError`:

| Argument | Allowed | Message when it is not |
|----------|---------|------------------------|
| `timeout` | a finite number of seconds above 0 | `timeout: -5 is not allowed; pass a finite number of seconds above 0` |
| `start`, `end` | an ISO-8601 string with a zone, or a timezone-aware `datetime` or pandas `Timestamp`, with at most six decimal places; the client sends it in UTC | `start: "yesterday" is not an ISO-8601 time; pass a time with a zone, such as 2026-10-01T00:00:00Z` |
| an episode, system or run id | a non-empty string without `/` | `episode_id: "a/b" is not an id; pass a non-empty id without '/'` |
| `series` | a list of series ids | `series: "ab" is a string; pass a list of series ids, such as ["ab"]` |

A time string without a zone fails with `end: "2026-10-01T00:00:00" has no time zone; pass a time with a zone, such
as 2026-10-01T00:00:00Z`.
A time with nanoseconds fails with `start: 2026-10-01T00:00:00.123456789+00:00 is finer than a microsecond; pass at
most six decimal places`. With pandas 2, a time after 2262-04-11T23:47:16Z or before 1677-09-21T00:12:44Z fails with
`start: "2500-01-01T00:00:00Z" is outside the times pandas 2.3.3 can hold; pass a time from 1677-09-21T00:12:44Z to
2262-04-11T23:47:16Z`; pandas 3 accepts it.

### Handle retrieval errors

Every failed request raises `RetrievalError`. Its message starts with the method and the URL. It has five fields:

| Field | Value |
|-------|-------|
| `method` | `GET` or `POST` |
| `url` | the base URL, the path and the query of the request |
| `status` | the HTTP status of the answer; `None` when no answer came |
| `type` | the `type` of the server's error body ([appendix B](../docs/user-manual/appendix-b-rest.md)); `None` when the body has none |
| `reason` | the text after the status: the `error` of the server's body, or what the client found wrong |

An error answer of the server keeps its text and its type, here `status` 400 and `type` `bad_request`:

```text
GET http://localhost:8080/v1/episodes?metric=demo.signal&resample=PT1M&agg=NOPE: HTTP 400: agg: "NOPE" is not allowed; expected one of MEAN, MIN, MAX, FIRST, LAST, SUM, COUNT
```

No answer (connection refused, timeout) gives `status` `None`. Check the base URL, the network and `timeout`:

```text
GET http://localhost:9/v1/inventory: no answer: [Errno 111] Connection refused; check the base URL, the network and the timeout of 120.0 s
```

An answer the client cannot read (a login page of a proxy, a truncated episode stream, an error page that is not
JSON) keeps its status. Check that the base URL points at the retrieval server. This output comes from a web server on
port 8081 that is not the retrieval server:

```text
GET http://localhost:8081/v1/inventory: HTTP 200: the answer is not JSON (Expecting value: line 1 column 1 (char 0)); check that the base URL points at the retrieval server
```

`c.episode(<id>)` returns `None` only when the server answers 404 with the type `not_found` and the text
`unknown episode <id>`. Any other 404 raises `RetrievalError`: the answer to a path the server does not know
(`no such endpoint: <path>`, for a base URL with a wrong path prefix such as `http://localhost:8080/api`), a 404 page of
a proxy, and an answer without exactly one episode.

## Read a topic

You need the `kafka` extra and a reachable Kafka endpoint or KafScale proxy.
`TopicReader.episodes()` explicitly assigns partitions and disables automatic commits and offset storage.
It reads up to the initial partition watermarks, filters episodes and keeps one episode per identity.
Records outside each initial low/high offset range and records from completed partitions are not decoded.
`max_records` defaults to 20,000 and rejects an initial snapshot above that limit. A value below 1 fails before
consumer creation with `max_records: 0 is below 1; pass a limit of at least 1`.

A record that is not a v2 record stops the read with `ValueError`. The message starts with
`<topic>[<partition>]@<offset>:`, so you can find the record:

```text
tsx.wind.episodes.v2[0]@5: not an Avro single-object payload
tsx.wind.episodes.v2[0]@5: a record without a value (tombstone); the reader reads v2 records only
```

A v2 record of another type, for example `Observation`, is skipped. `reader.skipped` holds how many records the last
successful read skipped. A topic the broker does not know raises `ValueError` that starts with
`the broker has no topic <topic>`. Any other metadata error of the topic, for example a missing read permission,
raises `RuntimeError` with `<topic>: metadata error from the broker: <broker text>`.

`timeout_s` defaults to 30 seconds and must be finite and positive. One monotonic deadline covers
metadata, watermark calls and polling. It also detects a deadline exceeded by a returned call;
it does not interrupt consumer construction, cleanup or an unresponsive native call.
Invalid time filters fail before consumer creation. Start and end values require a timezone.

After explicit assignment, only nonfatal `_UNSUPPORTED_FEATURE` events whose text starts with
`FindCoordinator response error:` are ignored. The 33rd such event raises `RuntimeError`.
These events consume the same deadline. Other broker errors propagate; partition EOF ends that partition.
The consumer closes on success and failure. These rules have deterministic local regression tests;
they do not establish compatibility with every broker deployment.

## Quick Start

```python
from opentsx import TimeSeriesObject, DFA
import numpy as np

# Create time series
data = np.random.randn(1000)
ts = TimeSeriesObject(data=data, label="example")

# Run DFA analysis
dfa = DFA(polynom_order=1)
results = dfa.analyze(ts)

print(f"Alpha: {results['alpha']:.3f}")
print(f"Interpretation: {results['interpretation']}")
```

`DFA`, `MFDFA`, `EventSynchronization` and `RIS` raise `ValueError` naming the argument when the input gives no
result: a value that is not finite, a constant series, or too few values or events.
[Chapter 11](../docs/user-manual/11-classic-analytics.md) of the user manual lists the messages.

## License

Apache License 2.0
