# Glossary of Terms

## Time Series Concepts

**Autocorrelation**
The correlation of a time series with a lagged version of itself. Measures how current values relate to past values. High autocorrelation indicates strong temporal dependencies.

**Binning**
Grouping consecutive data points and computing aggregate statistics (mean, sum). Used for downsampling and noise reduction.

**Change Point**
A moment in time when the statistical properties of a series change significantly. Also called "regime shift" or "structural break."

**Detrending**
Removing long-term patterns or trends from a time series to isolate short-term fluctuations or noise.

**Downsampling**
Reducing the temporal resolution of a time series by keeping every nth point or aggregating over windows. Opposite of upsampling.

**IID (Independent and Identically Distributed)**
A statistical assumption that observations are independent and come from the same distribution. Time series often violate this assumption due to temporal dependencies.

**Irregular Time Series**
A time series where observations are not equally spaced in time. Contrast with regular (evenly-spaced) time series.

**Lag**
The time difference between two points in a time series. Used in autocorrelation analysis and forecasting.

**Moving Average**
A smoothing technique that computes the average of values within a sliding window. Reduces noise while preserving trends.

**Normalization**
Transforming data to a standard scale, typically zero mean and unit variance (z-score normalization). Enables comparison across series with different units.

**Outlier**
An observation that deviates significantly from other observations. May indicate measurement error or genuine anomaly.

**Periodicity**
Regular repetition of patterns at fixed intervals. Daily, weekly, or seasonal cycles are common examples.

**Resampling**
Changing the sampling frequency of a time series, either by downsampling (reducing) or upsampling (increasing) resolution.

**Seasonality**
Periodic fluctuations that repeat at known, calendar-based intervals (daily, monthly, yearly).

**Stationary**
A time series whose statistical properties (mean, variance) don't change over time. Many analytical methods assume stationarity.

**Trend**
Long-term increase or decrease in a time series. Can be linear, exponential, polynomial, etc.

**Z-score**
Number of standard deviations a value is from the mean: `z = (x - μ) / σ`. Used in anomaly detection and normalization.

## OpenTSx-Specific Terms

**MessreihenLoader**
OpenTSx utility class for loading time series from delimited text files. "Messreihen" is German for "measurement series."

**RNGWrapper**
OpenTSx wrapper around random number generation libraries, providing convenient methods for creating synthetic time series.

**TimeSeriesObject**
The core data structure in OpenTSx representing a time series as paired (X, Y) vectors with metadata.

**X Values**
The independent variable in a TimeSeriesObject, typically representing time or sequence position.

**Y Values**
The dependent variable in a TimeSeriesObject, representing measurements or observations.

## Statistical Terms

**Coefficient of Variation (CV)**
Ratio of standard deviation to mean: `CV = σ / μ`. Measures relative variability.

**Mean (Average)**
Sum of values divided by count. Central tendency measure. In OpenTSx: `getAvarage()` (note typo).

**Median**
Middle value when data is sorted. Less sensitive to outliers than mean.

**Standard Deviation (SD)**
Measure of spread around the mean: `σ = √(Σ(x - μ)² / n)`. In OpenTSx: `getStddev()`.

**Variance**
Square of standard deviation: `σ²`. Measures data dispersion.

## Distribution Types

**Cauchy Distribution**
Heavy-tailed distribution with no defined mean or variance. Used for extreme events.

**Exponential Distribution**
Models time between events in a Poisson process. Always positive, right-skewed.

**Gaussian (Normal) Distribution**
Bell-shaped distribution characterized by mean and standard deviation. Common in natural phenomena.

**Geometric Distribution**
Models number of trials until first success. Discrete distribution for count data.

**Pareto Distribution**
Power-law distribution modeling "80-20 rule" phenomena. Very heavy-tailed.

**Poisson Distribution**
Models count of events in fixed time interval. Used for rare events.

**Uniform Distribution**
Equal probability across a range. Flat distribution.

## Technical Terms

**Apache Kafka**
Distributed streaming platform used by OpenTSx for real-time time series processing.

**Apache Kudu**
Columnar storage engine optimized for time series data, integrated with OpenTSx.

**Apache Spark**
Distributed computing framework used by OpenTSx for large-scale batch processing.

**Cassandra**
NoSQL database designed for high write throughput, used for time series storage.

**Element-wise Operation**
Operation applied to corresponding elements of two series: `c[i] = a[i] op b[i]`.

**In-place Operation**
Method that modifies the original object rather than returning a new one. Example: `ts.normalize()`.

**Immutable Operation**
Method that returns a new object, leaving the original unchanged. Example: `ts.normalizeToStdevIsOne()`.

**OHLC**
Open, High, Low, Close — standard format for financial time series (candlestick data).

**OpenTSDB**
Time series database optimized for metrics storage and retrieval.

**Vector**
Java's thread-safe dynamic array class used by OpenTSx for storing X and Y values.

**Window**
Subset of consecutive observations in a time series. Used in moving average, sliding statistics, etc.

## Acronyms

**ACF** — Autocorrelation Function

**AR** — Autoregressive (model)

**CV** — Coefficient of Variation

**FFT** — Fast Fourier Transform

**IID** — Independent and Identically Distributed

**LSTM** — Long Short-Term Memory (neural network)

**MA** — Moving Average

**OHLC** — Open-High-Low-Close

**PACF** — Partial Autocorrelation Function

**SWE** — Software Engineer (learner track)

**TSx** — Time Series Expert (learner track)

---

**See Also:**
- [Core Concepts](../core-concepts/README.md)
- [API Reference](api-reference.md)
- [Statistical Analysis](../statistical-analysis/README.md)
