# API Quick Reference

This reference provides a concise overview of the most commonly used OpenTSx classes and methods. For complete details, consult the JavaDoc.

## TimeSeriesObject

The central class for representing time series data.

### Construction

```java
// Empty time series
TimeSeriesObject ts = new TimeSeriesObject();

// With label
TimeSeriesObject ts = new TimeSeriesObject("sensor_01");

// From array
double[] data = {1.0, 2.0, 3.0};
TimeSeriesObject ts = new TimeSeriesObject(data);
```

### Adding Data

```java
// Add (x, y) pair
ts.addValuePair(double x, double y);

// Add y-only (x auto-generated)
ts.addValue(double y);

// Add from another series
ts.addValues(TimeSeriesObject other);
```

### Data Access

```java
// Direct vector access
int size = ts.yValues.size();
Double value = (Double) ts.yValues.elementAt(int index);
Double xCoord = (Double) ts.xValues.elementAt(int index);

// Statistics
double mean = ts.getAvarage();           // Mean (note: typo in API)
double stddev = ts.getStddev();          // Standard deviation
double min = ts.getMinY();               // Minimum Y value
double max = ts.getMaxY();               // Maximum Y value
double sum = ts.summeY();                // Sum of Y values

// Size information
int[] size = ts.getSize();               // Returns [xLength, yLength]
```

### Transformations

```java
// Normalization
ts.normalize();                          // In-place: remove mean
TimeSeriesObject norm = ts.normalizeToStdevIsOne();  // Returns new: z-score

// Centering
TimeSeriesObject centered = ts.subtractAverage();  // Returns new: mean = 0

// Scaling
ts.scaleY_2(double factor);              // In-place: multiply Y values
ts.add_to_Y(double offset);              // In-place: add to Y values
ts.divide_Y_by(double divisor);          // In-place: divide Y values

// X-axis operations
TimeSeriesObject scaled = ts.scaleX(int newMaxX);
TimeSeriesObject shifted = ts.addToX(int offset);
```

### Filtering and Subsetting

```java
// Extract time window
TimeSeriesObject window = ts.shrinkX(double minX, double maxX);

// Copy operations
TimeSeriesObject copy = ts.copy();
TimeSeriesObject partial = ts.copy(int limit);
```

### Combining Series

```java
// Element-wise addition
TimeSeriesObject sum = ts1.add(TimeSeriesObject ts2);

// Element-wise division
TimeSeriesObject ratio = ts1.divide_by(TimeSeriesObject ts2);

// Append values
ts1.addValues(TimeSeriesObject ts2);  // Modifies ts1
```

### Resampling

```java
// Downsample by averaging
TimeSeriesObject downsampled = ts.setBinningX_average(int binSize);

// Downsample by summing
TimeSeriesObject summed = ts.setBinningX_sum(int binSize);
```

### Metadata

```java
// Labels
ts.setLabel(String label);
String label = ts.getLabel();

// Comments and info
ts.addComment(String comment);
ts.addStatusInfo(String info);
ts.setAddinfo(String additionalInfo);
```

### File I/O

```java
// Write to file
ts.writeToFile(File file);
ts.writeToFile(File file, Character delimiter);

// Example
ts.writeToFile(new File("output.csv"), ',');
```

### Static Factory Methods

```java
// Gaussian distribution
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(int length);
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(int length, double mean, double stddev);

// Uniform distribution
TimeSeriesObject ts = TimeSeriesObject.getUniformDistribution(int length, double min, double max);

// Exponential distribution
TimeSeriesObject ts = TimeSeriesObject.getExpDistribution(int length, double lambda);

// Pareto distribution
TimeSeriesObject ts = TimeSeriesObject.getParetoDistribution(int length, double alpha);

// Geometric distribution
TimeSeriesObject ts = TimeSeriesObject.getGeometricDistribution(int length, double p);

// Linear function
TimeSeriesObject ts = TimeSeriesObject.getLinearFunction(double slope, double intercept,
                                                          double dx, double xMin, int N);

// Average of multiple series
TimeSeriesObject avg = TimeSeriesObject.averageForAll(Vector<TimeSeriesObject> series);
```

## MessreihenLoader

Loads time series from delimited text files.

### Basic Usage

```java
// Get loader instance
MessreihenLoader loader = MessreihenLoader.getLoader();

// Set delimiter
loader.delim = ",";  // Comma-separated
loader.delim = "\t"; // Tab-separated

// Load from file
TimeSeriesObject ts = loader.loadMessreihe(File file);
// Loads X from column 1, Y from column 2

// Load specific columns
TimeSeriesObject ts = loader.loadMessreihe_2(File file, int columnX, int columnY);
// Column indices start at 1

// Load Y-only (X auto-generated)
TimeSeriesObject ts = loader.loadMessreihe_1(File file);
```

### Advanced Loading

```java
// With row limit
loader.limit = 100000;  // Load max 100k rows
TimeSeriesObject ts = loader.loadMessreihe_2(file, 1, 2, limit);

// With custom delimiter
TimeSeriesObject ts = loader.loadMessreihe_2(file, 1, 2, ";");

// Check file before loading
boolean accessible = MessreihenLoader.checkFileAccess(String path);
```

### File Format Requirements

- Text files (.txt, .dat) preferred
- Comments start with `#`
- Blank lines skipped
- Columns space-separated by default, configurable via `delim`
- Commas in numbers replaced with periods

## RNGWrapper

Random number generation for synthetic data.

### Initialization

```java
RNGWrapper.init();  // Initialize with seed (fixed at 1)
```

### Random Values

```java
// Gaussian (normal) distribution
double value = RNGWrapper.getStdRandomGaussian();                    // N(0, 1)
double value = RNGWrapper.getStdRandomGaussian(double mean, double std);

// Uniform distribution
double value = RNGWrapper.getStdRandomUniform(double min, double max);

// Exponential distribution
double value = RNGWrapper.getStdRandomExp(double lambda);

// Pareto distribution
double value = RNGWrapper.getStdRandomPareto(double alpha);

// Geometric distribution
double value = RNGWrapper.getStdRandomGeometric(double p);

// Cauchy distribution
double value = RNGWrapper.getStdRandomCauchy();

// Poisson distribution
double value = RNGWrapper.getStdPoisson(double lambda);

// Bernoulli trial
boolean outcome = RNGWrapper.getStdRandomBernoulli();
```

## Common Patterns

### Create and Fill Time Series

```java
TimeSeriesObject ts = new TimeSeriesObject("my_series");
for (int i = 0; i < 100; i++) {
    ts.addValuePair(i, Math.random() * 100);
}
```

### Load, Transform, Export

```java
MessreihenLoader loader = MessreihenLoader.getLoader();
loader.delim = ",";
TimeSeriesObject ts = loader.loadMessreihe_2(new File("input.csv"), 1, 2);

TimeSeriesObject normalized = ts.normalizeToStdevIsOne();
normalized.writeToFile(new File("output.csv"), ',');
```

### Statistical Summary

```java
System.out.println("Count: " + ts.yValues.size());
System.out.println("Mean: " + ts.getAvarage());
System.out.println("Std Dev: " + ts.getStddev());
System.out.println("Min: " + ts.getMinY());
System.out.println("Max: " + ts.getMaxY());
System.out.println("Range: " + (ts.getMaxY() - ts.getMinY()));
```

### Iterate Over Values

```java
for (int i = 0; i < ts.yValues.size(); i++) {
    double x = (Double) ts.xValues.elementAt(i);
    double y = (Double) ts.yValues.elementAt(i);
    // Process (x, y) pair
}
```

### Generate Synthetic Test Data

```java
RNGWrapper.init();
TimeSeriesObject test = TimeSeriesObject.getGaussianDistribution(1000, 100.0, 15.0);

// Verify
assertEquals(100.0, test.getAvarage(), 1.0);  // Within 1 unit
assertEquals(15.0, test.getStddev(), 1.0);
```

## Method Naming Quirks

OpenTSx preserves historical method names for compatibility:

- `getAvarage()` — Typo for "average" (note missing 'e')
- `summeY()` — Typo for "sum" (German influence)
- `Messreihen` — German for "measurement series"
- `setBinningX_average` — Underscore, not camelCase

These are intentional and documented.

## Type Casts Required

Java's Vector stores Objects, requiring casts:

```java
// Always cast when accessing vectors
Double value = (Double) ts.yValues.elementAt(i);

// Or use doubleValue() for primitives
double primitive = ((Double) ts.yValues.elementAt(i)).doubleValue();
```

## Memory Considerations

- Each Double object: ~24 bytes
- Vector overhead: ~40 bytes + capacity
- For 1M points: ~50 MB per TimeSeriesObject

Consider downsampling for very large series.

---

**See Also:**
- [Core Concepts](../core-concepts/timeseries-object.md)
- [Data Operations](../data-operations/README.md)
- [Best Practices](../best-practices/README.md)
