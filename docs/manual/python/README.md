# Using OpenTSx with Python

## Overview

OpenTSx provides a comprehensive Python package that enables time series analysis with near-complete feature parity with the Java implementation. The Python package is designed for data scientists, researchers, and Python developers who want to leverage OpenTSx's advanced time series analysis algorithms in a Pythonic way.

## Why Use the Python Package?

### Advantages

✅ **Pythonic API**: Designed to feel natural for Python developers
✅ **NumPy/pandas Integration**: Seamless integration with the scientific Python stack
✅ **Jupyter-Friendly**: Perfect for interactive analysis and exploration
✅ **Advanced Algorithms**: DFA, MFDFA, Event Synchronization, RIS
✅ **Visualization**: Built-in matplotlib plotting for all algorithms
✅ **Interoperability**: Exchange data with Java systems via Kafka/files/REST

### When to Use Python vs Java

**Use Python When:**
- Doing exploratory data analysis
- Working in Jupyter notebooks
- Integrating with scikit-learn, TensorFlow, pandas
- Building web APIs (FastAPI, Flask)
- Rapid prototyping
- Serverless deployments (AWS Lambda)

**Use Java When:**
- High-throughput streaming (millions of events/second)
- Kafka Streams applications
- ksqlDB custom functions
- Enterprise deployments with strict SLAs
- Integration with Hadoop/Spark at scale

**Use Both in Hybrid Architectures:**
- Java for data ingestion and streaming aggregation
- Python for analysis, ML, and web services
- Connected via Kafka topics with Avro serialization

## Feature Parity Status

| Feature | Java | Python | Status |
|---------|------|--------|--------|
| **Core Data Structures** | ✅ | ✅ | Full Parity |
| **DFA** | ✅ | ✅ | Full Parity |
| **MFDFA** | ✅ | ✅ | Full Parity ⭐ |
| **Event Synchronization** | ✅ | ✅ | Full Parity ⭐ |
| **RIS** | ✅ | ✅ | Full Parity ⭐ |
| **Statistical Tools** | ✅ | ⚠️ | Partial (use scipy/statsmodels) |
| **Kafka Integration** | ✅ | ⚠️ | Designed, needs production testing |

**Overall Completeness**: 86% (5/7 major features complete)

## Quick Start

### Installation

```bash
# Install from source (recommended for development)
cd OpenTSx/python-package
pip install -e .

# Install with optional dependencies
pip install -e ".[all]"  # All extras (Kafka, viz, ML, storage)
pip install -e ".[kafka]"  # Just Kafka support
pip install -e ".[viz]"  # Just visualization
```

### Hello World Example

```python
from opentsx import TimeSeriesObject
from opentsx.algorithms import DFA
import numpy as np

# Create time series
data = np.cumsum(np.random.randn(1000))
ts = TimeSeriesObject(data=data, label="example")

# Run DFA analysis
dfa = DFA(polynom_order=1)
results = dfa.analyze(ts)

print(f"Hurst exponent α: {results['alpha']:.3f}")
print(f"Interpretation: {results['interpretation']}")
```

## Package Structure

```
opentsx/
├── __init__.py
├── core/
│   ├── time_series.py      # TimeSeriesObject class
│   ├── bucket.py            # TSBucket container
│   └── processor.py         # TSProcessor abstraction
├── algorithms/
│   ├── dfa.py               # Detrended Fluctuation Analysis
│   ├── mfdfa.py             # Multifractal DFA
│   ├── event_sync.py        # Event Synchronization
│   └── ris.py               # Return Interval Statistics
└── connectors/
    └── kafka/               # Kafka producer/consumer (optional)
```

## Core Capabilities

### 1. TimeSeriesObject

The fundamental data structure for univariate time series:

```python
from opentsx import TimeSeriesObject
import numpy as np

# Create from various sources
ts1 = TimeSeriesObject(data=[1, 2, 3, 4, 5])
ts2 = TimeSeriesObject(data=np.random.randn(1000))
ts3 = TimeSeriesObject.from_pandas(series)

# Statistical methods
mean = ts.mean()
std = ts.std()
summary = ts.describe()

# Transformations (immutable - return new objects)
ts_normalized = ts.normalize(method='zscore')
ts_detrended = ts.detrend(order=1)
ts_diff = ts.diff(periods=1)

# Serialization
dict_repr = ts.to_dict()  # Compatible with Java Avro schema
pandas_series = ts.to_pandas()
```

### 2. Advanced Algorithms

#### DFA (Detrended Fluctuation Analysis)

Detects long-range correlations and calculates Hurst exponent:

```python
from opentsx.algorithms import DFA

dfa = DFA(polynom_order=1)
results = dfa.analyze(time_series)

print(f"α: {results['alpha']:.3f}")
print(f"Interpretation: {results['interpretation']}")
```

#### MFDFA (Multifractal DFA)

Detects multifractal scaling properties:

```python
from opentsx.algorithms import MFDFA

mfdfa = MFDFA(polynom_order=1)
results = mfdfa.analyze(time_series)

print(f"Is multifractal: {results['is_multifractal']}")
print(f"Δh: {results['delta_h']:.3f}")

# Visualize h(q), τ(q), f(α)
mfdfa.plot_results(results)
```

#### Event Synchronization

Detects synchronized events and lead-lag relationships:

```python
from opentsx.algorithms import EventSynchronization

es = EventSynchronization()
results = es.analyze(ts1, ts2)

print(f"Overall sync Q: {results['overall_sync']:.3f}")
print(f"Leader: {results['leader']}")

es.plot_results(ts1, ts2, results)
```

#### RIS (Return Interval Statistics)

Analyzes extreme events and assesses risk:

```python
from opentsx.algorithms import RIS

ris = RIS(threshold_percentile=95)
results = ris.analyze(time_series)

print(f"Risk parameter R: {results['risk_parameter']:.3f}")
print(f"Risk: {results['risk_interpretation']}")

ris.plot_results(time_series, results)
```

## Integration with Python Ecosystem

### pandas Integration

```python
import pandas as pd
from opentsx import TimeSeriesObject

# From pandas
df = pd.read_csv('data.csv')
ts = TimeSeriesObject.from_pandas(df['value'])

# To pandas
series = ts.to_pandas()
```

### NumPy Compatibility

```python
import numpy as np

# TimeSeriesObject wraps NumPy arrays
ts.values  # Returns NumPy array
ts.timestamps  # Returns NumPy array

# NumPy operations
ts.apply(lambda x: np.log(x + 1))
```

### Matplotlib Visualization

```python
import matplotlib.pyplot as plt

plt.figure(figsize=(12, 6))
plt.plot(ts.timestamps, ts.values)
plt.title(ts.label)
plt.xlabel('Time')
plt.ylabel('Value')
plt.show()
```

## Data Interoperability

### JSON Serialization (Java-compatible)

```python
import json

# Serialize
ts_dict = ts.to_dict()
json_str = json.dumps(ts_dict)

# Deserialize
ts_loaded = TimeSeriesObject.from_dict(json.loads(json_str))
```

### File Exchange

```python
# Parquet (readable by Java)
from opentsx import TSBucket

bucket = TSBucket()
# ... add time series ...
bucket.save('/shared/data.parquet', format='parquet')

# Java can read with Apache Parquet libraries
```

### Kafka Streaming (optional)

```python
from opentsx.connectors.kafka import KafkaTimeSeriesConsumer

consumer = KafkaTimeSeriesConsumer(
    bootstrap_servers='localhost:9092',
    schema_registry_url='http://localhost:8081',
    topic='timeseries-data',
    group_id='python-consumer'
)

for ts in consumer.consume():
    results = dfa.analyze(ts)
    print(f"Analyzed {ts.label}: α={results['alpha']:.3f}")
```

## Learning Path

### For New Users

1. **Start Here**: [Installation & Setup](installation.md)
2. **Core Concepts**: [TimeSeriesObject in Python](timeseries-object.md)
3. **Basic Analysis**: [DFA Analysis](dfa.md)
4. **Advanced Analysis**: [MFDFA](mfdfa.md), [Event Sync](event-synchronization.md), [RIS](ris.md)
5. **Integration**: [Java-Python Interoperability](interoperability.md)
6. **Production**: [Production Deployment](production.md)

### For Java Developers

If you're coming from the Java implementation:
- Core concepts are the same
- API is more concise and Pythonic
- See [Java-Python Interoperability](interoperability.md) for data exchange patterns

### Comprehensive Curriculum

Follow the [Python Developer Onboarding Path](../../onboarding/ONBOARDING-PATH-Python.md) for a complete 8-episode, 12-15 hour learning curriculum.

## Documentation Resources

### API Documentation
- [Python API Overview](api-overview.md)
- [TimeSeriesObject Reference](timeseries-object.md)
- [Algorithm Reference](../appendix/api-reference.md)

### Guides
- [Feature Comparison: Java vs Python](../../../FEATURE_COMPARISON_JAVA_PYTHON.md)
- [Interoperability Guide](../../../INTEROPERABILITY_GUIDE.md)
- [Implementation Summary](../../../IMPLEMENTATION_SUMMARY.md)

### Examples
- Example scripts: `/python-package/examples/`
- Test suite: `/python-package/test_implementations.py`

## Getting Help

- **GitHub Issues**: https://github.com/kamir/OpenTSx/issues
- **Documentation**: https://docs.opentsx.org
- **Onboarding Path**: [Python Developer Track](../../onboarding/ONBOARDING-PATH-Python.md)

## Contributing

The Python package is actively developed and welcomes contributions:

- Implement missing statistical tools
- Add new algorithms
- Improve documentation
- Write tutorials and examples
- Report bugs and request features

See [Contributing to OpenTSx](../appendix/contributing.md) for guidelines.

---

**Next Steps**: [Install the Python package](installation.md) and start analyzing time series data!
