# Best Practices

Writing production-ready time series code requires more than understanding the API. This section distills lessons learned from real-world OpenTSx deployments.

## Core Principles

### 1. Immutability When Possible

Preserve original data:

```java
// Good: Transform a copy
TimeSeriesObject normalized = rawData.copy();
normalized.normalize();

// Risky: Modify original
rawData.normalize();  // Original data lost
```

**Why?** Debugging is easier when you can compare transformed data to originals. Immutability prevents subtle bugs from state mutations.

### 2. Explicit Over Implicit

Be clear about operations:

```java
// Unclear: What does this modify?
result = processData(ts);

// Clear: Return value indicates creation
TimeSeriesObject result = transformAndReturn(ts.copy());
```

OpenTSx mixes mutable (`normalize()`) and immutable (`normalizeToStdevIsOne()`) patterns. Document which your functions use.

### 3. Validate Early

Check invariants at boundaries:

```java
public void processTimeSeries(TimeSeriesObject ts) {
    // Validate inputs
    if (ts == null) {
        throw new IllegalArgumentException("TimeSeriesObject cannot be null");
    }
    if (ts.yValues.size() == 0) {
        throw new IllegalArgumentException("Empty time series");
    }
    if (ts.xValues.size() != ts.yValues.size()) {
        throw new IllegalStateException("X and Y vectors mismatched");
    }

    // Process...
}
```

Fail fast with clear error messages.

## Memory Management

### Avoid Accumulation

```java
// Bad: Accumulates all results in memory
List<TimeSeriesObject> results = new ArrayList<>();
for (File file : files) {
    TimeSeriesObject ts = loadAndAnalyze(file);
    results.add(ts);  // Memory grows unbounded
}

// Good: Stream and discard
for (File file : files) {
    TimeSeriesObject ts = loadAndAnalyze(file);
    writeResults(ts);
    // ts eligible for GC
}
```

### Clear References

```java
// After processing, help GC
TimeSeriesObject large = loadHugeData();
processInChunks(large);
large = null;  // Hint to GC
System.gc();   // Request collection (not guaranteed)
```

### Monitor Vector Growth

Vectors resize automatically, but preallocating helps:

```java
// If you know final size
TimeSeriesObject ts = new TimeSeriesObject();
ts.xValues.ensureCapacity(expectedSize);
ts.yValues.ensureCapacity(expectedSize);
```

## Error Handling

### Handle Missing Data

```java
for (int i = 0; i < ts.yValues.size(); i++) {
    Double y = (Double) ts.yValues.elementAt(i);

    if (y == null || y.isNaN() || y.isInfinite()) {
        // Skip, interpolate, or flag error
        continue;
    }

    process(y);
}
```

### Graceful Degradation

```java
public TimeSeriesObject loadWithFallback(File primary, File backup) {
    try {
        return loader.loadMessreihe_2(primary, 1, 2);
    } catch (Exception e) {
        System.err.println("Primary load failed: " + e.getMessage());
        try {
            return loader.loadMessreihe_2(backup, 1, 2);
        } catch (Exception e2) {
            System.err.println("Backup load failed: " + e2.getMessage());
            return new TimeSeriesObject();  // Empty fallback
        }
    }
}
```

### Informative Exceptions

```java
// Bad
throw new Exception("Error");

// Good
throw new IllegalArgumentException(
    "Column index " + columnY + " exceeds available columns " + columnCount +
    " in file " + file.getName()
);
```

## Testing Strategies

### Use Synthetic Data

```java
@Test
public void testNormalization() {
    // Create data with known statistics
    TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(10000, 100.0, 15.0);

    // Normalize
    TimeSeriesObject normalized = ts.normalizeToStdevIsOne();

    // Verify
    assertEquals(0.0, normalized.getAvarage(), 0.1);
    assertEquals(1.0, normalized.getStddev(), 0.1);
}
```

### Test Edge Cases

```java
@Test
public void testEmptyTimeSeries() {
    TimeSeriesObject empty = new TimeSeriesObject();

    // Should handle gracefully
    assertEquals(0, empty.yValues.size());
    // getAvarage() on empty might throw or return NaN - document behavior
}

@Test
public void testSinglePoint() {
    TimeSeriesObject single = new TimeSeriesObject();
    single.addValue(42.0);

    assertEquals(1, single.yValues.size());
    assertEquals(42.0, single.getAvarage(), 0.001);
}
```

### Regression Tests

Save known-good outputs:

```java
@Test
public void testAgainstReferenceOutput() throws Exception {
    TimeSeriesObject input = loadTestData("reference_input.csv");
    TimeSeriesObject output = myTransform(input);

    TimeSeriesObject expected = loadTestData("reference_output.csv");

    for (int i = 0; i < output.yValues.size(); i++) {
        double actual = (Double) output.yValues.elementAt(i);
        double expect = (Double) expected.yValues.elementAt(i);
        assertEquals("Mismatch at index " + i, expect, actual, 0.0001);
    }
}
```

## Code Organization

### Separate Concerns

```java
// Data loading
public class TimeSeriesLoader {
    public TimeSeriesObject load(File file) { ... }
}

// Analysis
public class TimeSeriesAnalyzer {
    public Statistics analyze(TimeSeriesObject ts) { ... }
}

// Visualization
public class TimeSeriesExporter {
    public void export(TimeSeriesObject ts, File output) { ... }
}
```

### Builder Pattern for Complex Configurations

```java
public class TimeSeriesProcessor {
    private int windowSize = 10;
    private double threshold = 3.0;
    private boolean removeOutliers = false;

    public TimeSeriesProcessor withWindowSize(int size) {
        this.windowSize = size;
        return this;
    }

    public TimeSeriesProcessor withThreshold(double t) {
        this.threshold = t;
        return this;
    }

    public TimeSeriesProcessor removingOutliers() {
        this.removeOutliers = true;
        return this;
    }

    public TimeSeriesObject process(TimeSeriesObject input) {
        // Use configured parameters
    }
}

// Usage:
TimeSeriesObject result = new TimeSeriesProcessor()
    .withWindowSize(20)
    .withThreshold(2.5)
    .removingOutliers()
    .process(input);
```

## Performance Optimization

### Profile Before Optimizing

Don't guess — measure:

```java
long start = System.currentTimeMillis();
TimeSeriesObject result = expensiveOperation(ts);
long duration = System.currentTimeMillis() - start;
System.out.println("Operation took " + duration + " ms");
```

### Batch Operations

```java
// Slow: Many small operations
for (TimeSeriesObject ts : collection) {
    ts.normalize();
    ts.writeToFile(new File("output_" + ts.getLabel() + ".csv"), ',');
}

// Faster: Batch transformations, batch I/O
List<TimeSeriesObject> normalized = new ArrayList<>();
for (TimeSeriesObject ts : collection) {
    normalized.add(ts.normalizeToStdevIsOne());
}
// Write all at once or use parallel streams
```

### Avoid Repeated Calculations

```java
// Bad: Recalculates mean every iteration
for (int i = 0; i < ts.yValues.size(); i++) {
    double deviation = (Double) ts.yValues.elementAt(i) - ts.getAvarage();
}

// Good: Calculate once
double mean = ts.getAvarage();
for (int i = 0; i < ts.yValues.size(); i++) {
    double deviation = (Double) ts.yValues.elementAt(i) - mean;
}
```

## Common Pitfalls

### 1. Assuming Order

```java
// Dangerous if X values aren't sorted
for (int i = 1; i < ts.yValues.size(); i++) {
    double diff = (Double) ts.xValues.elementAt(i) -
                  (Double) ts.xValues.elementAt(i-1);
    // If X is unsorted, diff can be negative
}
```

**Fix:** Sort or verify order first.

### 2. Ignoring Null Checks

```java
// Risky
TimeSeriesObject ts = loader.loadMessreihe_2(file, 1, 2);
double mean = ts.getAvarage();  // NPE if load failed

// Safe
TimeSeriesObject ts = loader.loadMessreihe_2(file, 1, 2);
if (ts != null && ts.yValues.size() > 0) {
    double mean = ts.getAvarage();
}
```

### 3. Off-by-One Errors

```java
// Wrong: Accesses index out of bounds
for (int i = 0; i <= ts.yValues.size(); i++) {
    process((Double) ts.yValues.elementAt(i));  // Exception when i == size
}

// Correct
for (int i = 0; i < ts.yValues.size(); i++) {
    process((Double) ts.yValues.elementAt(i));
}
```

### 4. Type Confusion

```java
// Wrong: Treats Integer as Double
Vector v = ts.xValues;
double x = (Double) v.elementAt(0);  // ClassCastException if X values are Integers

// Safe
Object obj = v.elementAt(0);
double x = (obj instanceof Double) ? (Double) obj : ((Number) obj).doubleValue();
```

## Documentation Standards

### Document Units

```java
/**
 * Loads temperature measurements from sensor data.
 *
 * @param file CSV file with columns: timestamp_ms, temp_celsius
 * @return TimeSeriesObject where X = Unix timestamp (ms), Y = temperature (°C)
 */
public TimeSeriesObject loadTemperature(File file) {
    // ...
}
```

### Document Side Effects

```java
/**
 * Normalizes the time series IN PLACE.
 * WARNING: Modifies the original object.
 *
 * @param ts The time series to normalize (MODIFIED)
 */
public void normalizeInPlace(TimeSeriesObject ts) {
    ts.normalize();
}
```

### Version Compatibility

```java
/**
 * Uses getAvarage() method which contains a historical typo.
 * Compatible with OpenTSx 1.x and 2.x.
 * Note: Method may be renamed to getAverage() in 3.x.
 */
```

## Deployment Checklist

Before production:

- [ ] Validate input data sources
- [ ] Test with realistic data volumes
- [ ] Profile memory usage
- [ ] Handle missing/corrupt data
- [ ] Log key decision points
- [ ] Document expected behavior
- [ ] Version outputs for reproducibility
- [ ] Monitor runtime performance
- [ ] Plan for data growth
- [ ] Document dependencies

---

**Explore specific topics:**

- [Error Handling](error-handling.md)
- [Memory Management](memory-management.md)
- [Testing Strategies](testing.md)
- [Code Organization](code-organization.md)
- [Performance Patterns](performance-patterns.md)
- [Common Pitfalls](common-pitfalls.md)
