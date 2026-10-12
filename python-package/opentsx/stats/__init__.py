"""Readers for the OpenTSx streaming statistics (``opentsx-stats``): quantile sketches and SeriesStats frames."""
from .sketch import QuantileSketch
from .frames import stats_to_frame

__all__ = ["QuantileSketch", "stats_to_frame"]
