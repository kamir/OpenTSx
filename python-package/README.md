# OpenTSx Python Package

**Advanced Time Series Analysis Library for Python**

[![PyPI version](https://badge.fury.io/py/opentsx.svg)](https://badge.fury.io/py/opentsx)
[![Python Version](https://img.shields.io/badge/python-3.9%2B-blue)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Installation

```bash
pip install opentsx
```

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
- **Kafka connectors**: Real-time streaming

## Documentation

Full documentation: https://docs.opentsx.com

## License

Apache License 2.0
