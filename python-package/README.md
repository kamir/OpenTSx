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

## Read a topic

You need the `kafka` extra and a reachable Kafka endpoint or KafScale proxy.
`TopicReader.episodes()` explicitly assigns partitions and disables automatic commits and offset storage.
It reads up to the initial partition watermarks, filters episodes and keeps one episode per identity.
Records outside each initial low/high offset range and records from completed partitions are not decoded.
`max_records` defaults to 20,000 and rejects an initial snapshot above that limit.

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

## Features

- **DFA**: Detrended Fluctuation Analysis
- **MFDFA**: Multifractal DFA
- **Event Synchronization**: Detect synchronized events
- **NumPy/pandas integration**: Seamless data handling
- **Avro / Kafka**: v2 episodes via `opentsx.model.encode`, Kafka producer via the `kafka` extra

## Documentation

Full documentation: https://docs.opentsx.com

## License

Apache License 2.0
