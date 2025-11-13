"""
OpenTSx - Advanced Time Series Analysis Platform
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A comprehensive Python library for time series analysis, focusing on
long-range correlation detection, multifractal analysis, and real-time
streaming analytics.

Basic usage:

   >>> from opentsx import TimeSeriesObject, DFA
   >>> ts = TimeSeriesObject(data=[1, 2, 3, 4, 5], label="example")
   >>> dfa = DFA(polynom_order=1)
   >>> results = dfa.analyze(ts)
   >>> print(f"Alpha: {results['alpha']:.3f}")

:copyright: (c) 2025 by OpenTSx Contributors.
:license: Apache 2.0, see LICENSE for more details.
"""

__version__ = "1.0.0"
__author__ = "OpenTSx Contributors"
__license__ = "Apache-2.0"

from opentsx.core.time_series import TimeSeriesObject
from opentsx.core.bucket import TSBucket
from opentsx.core.processor import TSProcessor, ProcessorChain

from opentsx.algorithms.dfa import DFA
from opentsx.algorithms.mfdfa import MFDFA
from opentsx.algorithms.event_sync import EventSynchronization

__all__ = [
    # Version info
    "__version__",
    "__author__",
    "__license__",
    # Core classes
    "TimeSeriesObject",
    "TSBucket",
    "TSProcessor",
    "ProcessorChain",
    # Algorithms
    "DFA",
    "MFDFA",
    "EventSynchronization",
]
