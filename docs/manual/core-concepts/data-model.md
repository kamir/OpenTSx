# Data Model: X and Y Values

Understanding how OpenTSx models time series data is crucial for working effectively with the framework. The X-Y pairing model is both simple and powerful.

## The Coordinate System

Every time series in OpenTSx consists of ordered pairs: `(x, y)`

- **X values** — The independent variable (typically time)
- **Y values** — The dependent variable (measurements)

This creates a discrete function: `y = f(x)` where both x and y are stored explicitly.

## Why Store Both X and Y?

### Irregular Intervals

Real-world time series often have non-uniform sampling:

```
(0.0, 100.5)
(1.2, 101.0)
(3.5, 99.8)   ← 2.3 second gap
(4.1, 100.2)
```

Storing X values explicitly handles this naturally — no interpolation or padding required.

### Semantic Clarity

X values can represent different concepts:
- **Timestamps** — Unix milliseconds, epoch time
- **Sequential indices** — 0, 1, 2, 3, ...
- **Ordinal positions** — Sample numbers in an experiment
- **Custom scales** — Log scales, non-linear transformations

The framework doesn't dictate X's meaning — you define it.

## Working with X Values

### Auto-Generated Indices

When X values aren't semantically important:

```java
TimeSeriesObject ts = new TimeSeriesObject();
ts.addValue(100.5);  // X = 0
ts.addValue(101.0);  // X = 1
ts.addValue(99.8);   // X = 2
```

The framework assigns sequential integers automatically.

### Explicit Timestamps

For true time series:

```java
long t0 = System.currentTimeMillis();
ts.addValuePair(t0, 100.5);
ts.addValuePair(t0 + 1000, 101.0);  // 1 second later
ts.addValuePair(t0 + 2500, 99.8);   // 1.5 seconds after that
```

Now X values carry temporal meaning.

### Scaled Coordinates

For scientific data with specific units:

```java
// X = distance in meters, Y = sensor reading
ts.addValuePair(0.0, 25.3);
ts.addValuePair(0.5, 26.1);
ts.addValuePair(1.0, 24.8);
```

## Accessing Coordinates

### Parallel Access

X and Y vectors maintain synchronized indices:

```java
for (int i = 0; i < ts.xValues.size(); i++) {
    double x = (Double) ts.xValues.elementAt(i);
    double y = (Double) ts.yValues.elementAt(i);
    System.out.println("At position " + x + ", value is " + y);
}
```

### Y-Only Access

When X values aren't needed:

```java
for (int i = 0; i < ts.yValues.size(); i++) {
    double y = (Double) ts.yValues.elementAt(i);
    // Process measurement only
}
```

This is common for operations that don't depend on temporal positioning.

## Lookup Operations

### Finding Y for a Given X

```java
double y = ts.getYValueForX(targetX);
```

This searches for the closest X value and returns its corresponding Y.

### With Tolerance

```java
double epsilon = 0.01;
double y = ts.getYValueForX2(targetX, epsilon);
```

Returns Y only if an X value exists within `epsilon` of `targetX`.

### Reverse Lookup

```java
double x = ts.getX_for_Y(targetY);
```

Finds the X coordinate where Y equals (or is closest to) `targetY`.

## Invariants and Assumptions

### Size Consistency

**Invariant:** `xValues.size() == yValues.size()` always holds.

OpenTSx maintains this automatically. Adding a Y without an X (via `addValue()`) auto-generates an X.

### Ordering Expectations

Many operations assume X values are **monotonically increasing**:

```
X: 0.0, 1.0, 2.0, 3.0  ✓ Valid
X: 3.0, 1.0, 2.0, 0.0  ✗ May cause issues
```

While not enforced, violating this can produce unexpected results in:
- Trend detection
- Autocorrelation
- Change point detection
- Resampling operations

### No Duplicate X Values

While technically allowed, duplicate X values can complicate:
- Lookup operations (which one to return?)
- Merging time series
- Interpolation

Best practice: ensure X values are unique within a time series.

## Transforming Coordinates

### Scaling X

Adjust temporal granularity:

```java
TimeSeriesObject scaled = ts.scaleX(newMaxX);
```

This stretches or compresses the X axis to fit a new range.

### Offsetting X

Shift the time origin:

```java
TimeSeriesObject shifted = ts.addToX(offset);
```

Useful for aligning multiple time series to a common time zero.

### Logarithmic X

For data spanning orders of magnitude:

```java
TimeSeriesObject logX = new TimeSeriesObject();
for (int i = 0; i < ts.xValues.size(); i++) {
    double x = (Double) ts.xValues.elementAt(i);
    double y = (Double) ts.yValues.elementAt(i);
    logX.addValuePair(Math.log(x), y);
}
```

## Common Patterns

### Time-Based Filtering

Extract a time window:

```java
TimeSeriesObject window = ts.shrinkX(startTime, endTime);
```

Returns only points where `startTime ≤ x ≤ endTime`.

### Downsampling

Reduce temporal resolution:

```java
int binSize = 10;
TimeSeriesObject downsampled = ts.setBinningX_average(binSize);
```

Averages every `binSize` consecutive points, reducing data volume.

### Alignment

Ensuring multiple time series share X coordinates:

```java
// Align ts2 to ts1's X values (requires custom implementation)
// OpenTSx provides building blocks; alignment logic varies by use case
```

## Memory Layout

Internally, data is stored as:

```
xValues: [Double, Double, Double, ...]
yValues: [Double, Double, Double, ...]
```

Both vectors grow dynamically as data is added. Each Double object:
- Occupies ~24 bytes (object header + value)
- Allows null values (though rarely used)
- Provides thread-safe access

For maximum density, consider primitive arrays for very large series, though this requires custom extension.

## Conceptual Models

### As a Function

Think of TimeSeriesObject as a discrete function `f: X → Y` where:
- Domain is the set of X values
- Range is the set of Y values
- Function is defined only at discrete points

### As a Sequence

Alternatively, view it as an ordered sequence of observations:
- Each observation is a (timestamp, value) pair
- Sequence respects temporal ordering
- Gaps in time are explicit, not implicit

### As a Signal

In signal processing terms:
- X values define the sampling instants
- Y values represent signal amplitude
- Irregular X spacing means variable sampling rate

Choose the mental model that fits your problem domain.

## Next Steps

- **[Vector Storage](vector-storage.md)** — Understand performance implications
- **[Mutability](mutability.md)** — Learn which operations modify data
- **[Accessing Data](../data-operations/accessing-data.md)** — Practical techniques

---

**Related Topics:**
- [The TimeSeriesObject](timeseries-object.md)
- [Creating Time Series](../data-operations/creating-timeseries.md)
- [Transformations](../data-operations/transformations.md)
