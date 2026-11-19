"""
The Python side of OpenTSx: the v2 data model, Iceberg reading, streaming statistics, a client for the retrieval
server and the classic algorithms DFA, MFDFA, event synchronization and RIS.

Basic usage:

   >>> import numpy as np
   >>> from opentsx import TimeSeriesObject, DFA
   >>> ts = TimeSeriesObject(data=np.random.default_rng(0).standard_normal(1000), label="example")
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
    # Algorithms
    "DFA",
    "MFDFA",
    "EventSynchronization",
    "RIS",
]
