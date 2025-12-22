# Summary

* [Introduction](README.md)

## Getting Started

* [Introduction to OpenTSx](introduction/README.md)
  * [What is Time Series Analysis?](introduction/what-is-time-series.md)
  * [OpenTSx Architecture](introduction/architecture.md)
  * [When to Use OpenTSx](introduction/when-to-use.md)
  * [Installation & Setup](introduction/installation.md)

## Core Concepts

* [Fundamental Concepts](core-concepts/README.md)
  * [The TimeSeriesObject](core-concepts/timeseries-object.md)
  * [Data Model: X and Y Values](core-concepts/data-model.md)
  * [Labels and Metadata](core-concepts/labels-metadata.md)
  * [Understanding Vector Storage](core-concepts/vector-storage.md)
  * [Immutability vs Mutability](core-concepts/mutability.md)

## Data Operations

* [Working with Data](data-operations/README.md)
  * [Creating Time Series](data-operations/creating-timeseries.md)
  * [Generating Synthetic Data](data-operations/synthetic-data.md)
  * [Loading from Files](data-operations/loading-data.md)
  * [Exporting and Persistence](data-operations/exporting-data.md)
  * [Accessing Data Points](data-operations/accessing-data.md)
  * [Transformations](data-operations/transformations.md)
  * [Filtering and Subsetting](data-operations/filtering.md)
  * [Combining Time Series](data-operations/combining.md)

## Statistical Analysis

* [Analyzing Time Series](statistical-analysis/README.md)
  * [Descriptive Statistics](statistical-analysis/descriptive-stats.md)
  * [Normalization Techniques](statistical-analysis/normalization.md)
  * [Moving Averages](statistical-analysis/moving-averages.md)
  * [Trend Detection](statistical-analysis/trends.md)
  * [Seasonality and Periodicity](statistical-analysis/seasonality.md)
  * [Autocorrelation](statistical-analysis/autocorrelation.md)
  * [Anomaly Detection](statistical-analysis/anomaly-detection.md)
  * [Change Point Detection](statistical-analysis/change-points.md)

## Python Implementation

* [Using OpenTSx with Python](python/README.md)
  * [Installation & Setup](python/installation.md)
  * [Python API Overview](python/api-overview.md)
  * [TimeSeriesObject in Python](python/timeseries-object.md)
  * [DFA Analysis](python/dfa.md)
  * [MFDFA (Multifractal Analysis)](python/mfdfa.md)
  * [Event Synchronization](python/event-synchronization.md)
  * [RIS (Return Interval Statistics)](python/ris.md)
  * [Java-Python Interoperability](python/interoperability.md)
  * [Production Deployment](python/production.md)

## Advanced Topics

* [Advanced Capabilities](advanced-topics/README.md)
  * [Distributed Processing with Spark](advanced-topics/spark-processing.md)
  * [Real-time Streams with Kafka](advanced-topics/kafka-streams.md)
  * [Storage Backends](advanced-topics/storage-backends.md)
  * [Performance Optimization](advanced-topics/performance.md)
  * [Custom Operations](advanced-topics/custom-operations.md)
  * [Integration with R and Python](advanced-topics/interoperability.md)

## Best Practices

* [Production-Ready Code](best-practices/README.md)
  * [Error Handling](best-practices/error-handling.md)
  * [Memory Management](best-practices/memory-management.md)
  * [Testing Time Series Code](best-practices/testing.md)
  * [Code Organization](best-practices/code-organization.md)
  * [Performance Patterns](best-practices/performance-patterns.md)
  * [Common Pitfalls](best-practices/common-pitfalls.md)

## Appendix

* [Reference Materials](appendix/README.md)
  * [Configuration Properties](appendix/CONFIGURATION-PROPERTIES.md)
  * [API Quick Reference](appendix/api-reference.md)
  * [Glossary of Terms](appendix/glossary.md)
  * [Migration from R/Python](appendix/migration-guide.md)
  * [Further Reading](appendix/further-reading.md)
  * [Contributing to OpenTSx](appendix/contributing.md)
