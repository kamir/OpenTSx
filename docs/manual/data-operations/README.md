# Working with Data

Time series analysis begins with data. OpenTSx provides flexible mechanisms for creating, loading, transforming, and exporting temporal data.

## The Data Lifecycle

A typical time series workflow involves:

1. **Acquisition** — Create or load data from external sources
2. **Transformation** — Clean, normalize, filter, or aggregate
3. **Analysis** — Extract insights through statistical methods
4. **Persistence** — Export results for visualization or further processing

This section focuses on the first, second, and fourth stages — the practical mechanics of data manipulation.

## Creating Time Series

OpenTSx offers multiple pathways for creating time series:

### From Scratch

Build time series programmatically by adding values one at a time:

```java
TimeSeriesObject ts = new TimeSeriesObject();
ts.addValuePair(timestamp, measurement);
```

Ideal for:
- Streaming data ingestion
- Sensor reading collection
- Incremental computation

### From Arrays

Convert existing data structures:

```java
double[] measurements = loadFromSomewhere();
TimeSeriesObject ts = new TimeSeriesObject(measurements);
```

Useful for:
- Batch data import
- Converting from other frameworks
- Working with in-memory datasets

### Synthetic Generation

Create test data with known statistical properties:

```java
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(1000, 50.0, 10.0);
```

Essential for:
- Unit testing
- Algorithm validation
- Performance benchmarking
- Simulation studies

## Loading External Data

Real-world time series often originate from files, databases, or streams.

### From CSV Files

The MessreihenLoader handles delimited text files:

```java
MessreihenLoader loader = MessreihenLoader.getLoader();
loader.delim = ",";
TimeSeriesObject ts = loader.loadMessreihe_2(file, columnX, columnY);
```

Supports:
- Custom delimiters (comma, tab, pipe, etc.)
- Column selection
- Header skipping
- Comment filtering

### From Other Formats

OpenTSx's simple data model integrates easily with:
- JSON via standard parsers
- Databases via JDBC
- Excel via Apache POI
- Parquet via Spark
- Avro, Protocol Buffers, etc.

## Transforming Data

Raw data rarely arrives in analysis-ready form. OpenTSx provides operations to reshape time series:

### Scaling and Offsetting

Adjust magnitude and baseline:

```java
ts.scaleY_2(0.5);     // Multiply all Y values by 0.5
ts.add_to_Y(25.0);    // Add 25 to all Y values
ts.divide_Y_by(100);  // Divide all Y values by 100
```

### Normalization

Standardize to zero mean and unit variance:

```java
TimeSeriesObject normalized = ts.normalizeToStdevIsOne();
```

Or remove mean only:

```java
TimeSeriesObject centered = ts.subtractAverage();
```

### Filtering

Extract subsets based on criteria:

```java
// Time-based filtering
TimeSeriesObject window = ts.shrinkX(startTime, endTime);

// Value-based filtering (manual)
TimeSeriesObject filtered = new TimeSeriesObject();
for (int i = 0; i < ts.yValues.size(); i++) {
    double y = (Double) ts.yValues.elementAt(i);
    if (y > threshold) {
        filtered.addValue(y);
    }
}
```

### Resampling

Change temporal resolution:

```java
// Downsample by averaging bins
TimeSeriesObject downsampled = ts.setBinningX_average(binSize);

// Downsample by summing bins
TimeSeriesObject summed = ts.setBinningX_sum(binSize);
```

## Combining Time Series

Merge multiple series through various operations:

### Element-wise Addition

```java
TimeSeriesObject combined = ts1.add(ts2);
```

Requires matching lengths; sums corresponding Y values.

### Division

```java
TimeSeriesObject ratio = ts1.divide_by(ts2);
```

Computes element-wise ratio; useful for normalization.

### Concatenation

Append one series to another:

```java
ts1.addValues(ts2);  // Modifies ts1 in place
```

## Exporting Results

Persist time series for sharing or visualization:

### To CSV

```java
ts.writeToFile(new File("output.csv"), ',');
```

Creates a two-column file:
```
x,y
0.0,100.5
1.0,101.2
2.0,99.8
```

### To TSV

```java
ts.writeToFile(new File("output.tsv"), '\t');
```

Tab-separated format, common in scientific computing.

### Custom Formats

Direct vector access enables any format:

```java
PrintWriter writer = new PrintWriter("output.json");
writer.println("[");
for (int i = 0; i < ts.yValues.size(); i++) {
    writer.printf("  {\"x\": %f, \"y\": %f}%s\n",
        (Double) ts.xValues.elementAt(i),
        (Double) ts.yValues.elementAt(i),
        (i < ts.yValues.size() - 1) ? "," : "");
}
writer.println("]");
writer.close();
```

## Memory-Efficient Patterns

For large-scale data processing:

### Streaming Transformations

Process data without full materialization:

```java
BufferedReader reader = new BufferedReader(new FileReader(inputFile));
PrintWriter writer = new PrintWriter(outputFile);
String line;
while ((line = reader.readLine()) != null) {
    double value = Double.parseDouble(line);
    double transformed = transform(value);  // Your logic
    writer.println(transformed);
}
```

### Windowed Processing

Analyze data in chunks:

```java
int windowSize = 1000;
for (int start = 0; start < fullSeries.yValues.size(); start += windowSize) {
    int end = Math.min(start + windowSize, fullSeries.yValues.size());
    TimeSeriesObject window = fullSeries.shrinkX(start, end);
    processWindow(window);
}
```

## Best Practices

1. **Label everything** — Use descriptive labels to track data provenance
2. **Validate on load** — Check for NaN, infinity, duplicates after importing
3. **Preserve originals** — Keep raw data immutable; transform copies
4. **Document units** — Encode measurement units in labels or metadata
5. **Test with synthetic data** — Validate pipelines with known-distribution generators

## Learning Path

Dive deeper into specific operations:

1. **[Creating Time Series](creating-timeseries.md)** — Detailed construction techniques
2. **[Synthetic Data](synthetic-data.md)** — Generating test data
3. **[Loading Data](loading-data.md)** — File formats and loaders
4. **[Transformations](transformations.md)** — Mathematical operations
5. **[Exporting Data](exporting-data.md)** — Persistence strategies

---

**Continue to:** [Creating Time Series →](creating-timeseries.md)
