# The TimeSeriesObject

The **TimeSeriesObject** is the fundamental building block of OpenTSx. Understanding this class is essential for effective time series analysis.

## Anatomy of a TimeSeriesObject

At its core, a TimeSeriesObject maintains two parallel sequences of values:

```java
public class TimeSeriesObject {
    public Vector xValues;  // Temporal coordinates
    public Vector yValues;  // Measurements
    public String label;    // Human-readable identifier
    // ... additional fields
}
```

### The X-Y Relationship

Every Y value has a corresponding X value:
- **X values** represent the temporal dimension (time, index, ordinal position)
- **Y values** represent the measurements or observations

This pairing creates ordered tuples: `(x₀, y₀), (x₁, y₁), (x₂, y₂), ...`

## Creating TimeSeriesObjects

### Empty Construction

The simplest way to create a time series:

```java
TimeSeriesObject ts = new TimeSeriesObject();
```

This initializes empty vectors for X and Y values, ready to accept data.

### With Label

Labels help identify time series in collections:

```java
TimeSeriesObject ts = new TimeSeriesObject("sensor_temperature");
```

### From Array

Construct directly from a double array (X values auto-generated):

```java
double[] data = {23.1, 23.5, 22.8, 24.2};
TimeSeriesObject ts = new TimeSeriesObject(data);
// Creates: (0, 23.1), (1, 23.5), (2, 22.8), (3, 24.2)
```

## Adding Data

### Single Value Pairs

Add one (x, y) pair at a time:

```java
TimeSeriesObject ts = new TimeSeriesObject();
ts.addValuePair(0.0, 100.5);
ts.addValuePair(1.0, 101.2);
ts.addValuePair(2.0, 99.8);
```

This is ideal for building time series incrementally.

### Y-Only Values

When X values are sequential integers:

```java
ts.addValue(100.5);  // Assigns x = 0
ts.addValue(101.2);  // Assigns x = 1
ts.addValue(99.8);   // Assigns x = 2
```

The framework automatically manages X indexing.

## Accessing Data

### Direct Vector Access

For maximum performance and flexibility:

```java
int length = ts.yValues.size();
Double firstValue = (Double) ts.yValues.elementAt(0);
Double lastValue = (Double) ts.yValues.elementAt(length - 1);
```

**Why cast to Double?** Java's Vector stores Objects, not primitives, requiring explicit casting.

### Statistical Methods

Compute common statistics without iteration:

```java
double mean = ts.getAvarage();      // Arithmetic mean
double stddev = ts.getStddev();     // Standard deviation
double min = ts.getMinY();          // Minimum Y value
double max = ts.getMaxY();          // Maximum Y value
double sum = ts.summeY();           // Sum of all Y values
```

**Note:** The method name `getAvarage()` contains a typo from the original implementation. This is preserved for API compatibility.

## Understanding Size and Length

Get the number of data points:

```java
int count = ts.yValues.size();
// Or equivalently:
int[] size = ts.getSize();  // Returns [xLength, yLength]
```

Both vectors should always have the same size — OpenTSx maintains this invariant.

## Labels and Identity

### Setting Labels

```java
ts.setLabel("warehouse_a.temp_sensor_01");
```

### Retrieving Labels

```java
String label = ts.getLabel();
```

### Label Conventions

Consider hierarchical naming for organization:
- `location.device.metric` (e.g., `dc1.server5.cpu_usage`)
- `experiment_phase_metric` (e.g., `exp42_training_loss`)
- `source:stream:field` (e.g., `kafka:metrics:throughput`)

## Key Characteristics

### Public Access Philosophy

Unlike traditional encapsulation, OpenTSx exposes data vectors publicly. This design choice reflects:

1. **Performance** — Direct access avoids method call overhead in tight loops
2. **Flexibility** — Custom operations don't require framework modification
3. **Transparency** — No hidden state or surprising behavior
4. **Simplicity** — Fewer abstractions to learn

### Vector-Based Storage

Using Java's `Vector` class provides:
- **Thread safety** — Synchronized access for concurrent operations
- **Dynamic sizing** — Grow automatically as data is added
- **Random access** — O(1) lookup by index

The trade-off is slightly higher memory overhead compared to primitive arrays.

## Common Patterns

### Iterating Over Values

```java
for (int i = 0; i < ts.yValues.size(); i++) {
    double x = (Double) ts.xValues.elementAt(i);
    double y = (Double) ts.yValues.elementAt(i);
    // Process (x, y) pair
}
```

### Conditional Selection

```java
double threshold = ts.getAvarage();
int countAbove = 0;
for (int i = 0; i < ts.yValues.size(); i++) {
    if ((Double) ts.yValues.elementAt(i) > threshold) {
        countAbove++;
    }
}
```

### Building Derived Series

```java
TimeSeriesObject squared = new TimeSeriesObject();
squared.setLabel(ts.getLabel() + "_squared");
for (int i = 0; i < ts.yValues.size(); i++) {
    double y = (Double) ts.yValues.elementAt(i);
    squared.addValue(y * y);
}
```

## Memory Considerations

Each TimeSeriesObject maintains:
- Two Vector instances (xValues, yValues)
- String labels and metadata
- Optional auxiliary structures

For large-scale analytics, consider:
- **Streaming processing** — Process and discard rather than accumulate
- **Downsampling** — Reduce resolution for long-term storage
- **Batch operations** — Group related time series for cache efficiency

## Next Steps

Now that you understand the TimeSeriesObject, explore:

- **[Data Model](data-model.md)** — Deeper dive into X-Y relationships
- **[Vector Storage](vector-storage.md)** — Performance implications of Vector usage
- **[Mutability](mutability.md)** — When operations modify vs. create objects

---

**Related Topics:**
- [Creating Time Series](../data-operations/creating-timeseries.md)
- [Accessing Data Points](../data-operations/accessing-data.md)
- [Descriptive Statistics](../statistical-analysis/descriptive-stats.md)
