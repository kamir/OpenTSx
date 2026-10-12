# R/Python to OpenTSx Translation Guide

This quick guide maps common R/Python time series concepts to OpenTSx.

## Common Mapping

| R/Python Concept | OpenTSx Equivalent | Notes |
|------------------|--------------------|-------|
| pandas Series | `TimeSeriesObject` | Core time series container |
| numpy array | `double[]` / `TSData` | Used in algorithms and buckets |
| pandas DataFrame | `TSBucket` | Container for grouped series |
| matplotlib/plotly | TSA Workbench | GUI-based visualization |

## Typical Flow

1. Load or generate data into a `TimeSeriesObject`.
2. Group series into a `TSBucket` when working with many sensors.
3. Apply algorithms from `org.opentsx.algorithms`.
4. Persist to OpenTSDB/Cassandra if needed.

## Next Step
- Continue with [Statistical Analysis](../statistical-analysis/README.md)
