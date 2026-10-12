# Core Concepts

Understanding the foundational concepts of OpenTSx is essential for effective time series analysis. This section explores the building blocks that make OpenTSx both powerful and flexible.

## The Central Abstraction: TimeSeriesObject

At the heart of OpenTSx is the **TimeSeriesObject** — a simple yet powerful abstraction for representing time-ordered data. Unlike heavyweight frameworks that hide complexity behind layers of abstraction, OpenTSx embraces transparency. The TimeSeriesObject exposes its internals, giving you direct access when needed while providing convenient methods for common operations.

## Key Design Principles

### 1. Simplicity Through Transparency

OpenTSx doesn't hide data behind getters and setters. The `xValues` and `yValues` vectors are public, allowing direct manipulation when performance matters or when built-in methods don't cover your use case.

```java
TimeSeriesObject ts = new TimeSeriesObject();
// Direct access to data
ts.yValues.elementAt(5);  // Get 6th value
ts.yValues.size();        // Get length
```

This transparency means you're never "fighting the framework" — if you need low-level control, you have it.

### 2. Flexibility Over Convention

OpenTSx doesn't enforce strict typing for X values. They can represent:
- Unix timestamps
- Sequential indices (0, 1, 2, ...)
- Irregular time points
- Custom ordinal values

This flexibility accommodates diverse use cases without requiring specialized subclasses.

### 3. Composition Over Inheritance

Rather than deep class hierarchies, OpenTSx favors composition. A TimeSeriesObject can contain metadata, labels, and even nested structures, all while maintaining a simple interface.

## What Makes Time Series Special?

Time series data differs from regular datasets in fundamental ways:

- **Order matters** — Unlike tabular data where rows are interchangeable, time series have inherent sequence
- **Temporal dependencies** — Values are often correlated with nearby values (autocorrelation)
- **Patterns emerge over time** — Trends, seasonality, and cycles are first-class concepts
- **Missing data is meaningful** — Gaps in time series can indicate system failures or anomalies

OpenTSx's design acknowledges these characteristics, providing operations that respect temporal ordering and dependencies.

## The Mental Model

Think of a TimeSeriesObject as:

```
A container holding:
  ├─ X values (temporal coordinates)
  ├─ Y values (measurements)
  ├─ Metadata (labels, descriptions)
  └─ Optional structures (date mappings, custom labels)
```

It's intentionally lightweight — you can create millions of TimeSeriesObjects without significant overhead.

## Learning Path

To build mastery of OpenTSx's core concepts, we recommend this sequence:

1. **[The TimeSeriesObject](timeseries-object.md)** — Understand the central data structure
2. **[Data Model](data-model.md)** — Learn how X and Y values work together
3. **[Labels and Metadata](labels-metadata.md)** — Organize and identify your data
4. **[Vector Storage](vector-storage.md)** — Understand the underlying storage mechanism
5. **[Mutability](mutability.md)** — Know when operations modify objects vs. return new ones

## Why These Concepts Matter

A solid grasp of these fundamentals enables you to:

- **Write efficient code** — Understanding data layout informs performance decisions
- **Debug effectively** — Knowing the data model helps diagnose unexpected behavior
- **Extend the framework** — Build custom operations that integrate seamlessly
- **Optimize memory** — Make informed decisions about copying vs. modifying data

---

**Continue to:** [The TimeSeriesObject →](timeseries-object.md)
