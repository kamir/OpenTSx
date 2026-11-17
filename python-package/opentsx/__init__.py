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
from opentsx.algorithms.ris import RIS

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
    "RIS",
    "forecast_arima",
    "forecast_baseline",
    "rolling_backtest",
    "seasonal_decomposition",
    "power_transform",
    "inverse_power_transform",
]


def __getattr__(name):
    if name == "forecast_arima":
        from opentsx.algorithms.forecasting import forecast_arima

        return forecast_arima
    if name == "forecast_baseline":
        from opentsx.algorithms.evaluation import forecast_baseline

        return forecast_baseline
    if name == "rolling_backtest":
        from opentsx.algorithms.evaluation import rolling_backtest

        return rolling_backtest
    if name in ("seasonal_decomposition", "power_transform", "inverse_power_transform"):
        from opentsx.algorithms import decomposition

        return getattr(decomposition, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
