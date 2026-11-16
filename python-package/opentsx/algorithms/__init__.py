"""Time series analysis algorithms."""

from opentsx.algorithms.dfa import DFA
from opentsx.algorithms.mfdfa import MFDFA
from opentsx.algorithms.event_sync import EventSynchronization
from opentsx.algorithms.ris import RIS

__all__ = [
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
