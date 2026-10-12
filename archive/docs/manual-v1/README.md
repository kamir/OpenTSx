# OpenTSx Manual

**A Conceptual Guide to Time Series Analysis with OpenTSx**

Welcome to the OpenTSx Manual — a comprehensive, concept-driven guide to understanding and working with the OpenTSx time series analysis framework.

## What is OpenTSx?

OpenTSx is a powerful Java-based framework designed for time series analysis and processing. Built on top of industry-standard technologies like Apache Spark and Kafka Streams, OpenTSx provides developers and data scientists with the tools needed to:

- **Create and manipulate** time series data structures
- **Generate synthetic** time series for testing and simulation
- **Transform and analyze** temporal data at scale
- **Detect patterns** and anomalies in time-dependent data
- **Process streams** of time series data in real-time
- **Store and retrieve** time series efficiently using specialized backends

## Who Should Use This Manual?

This manual is designed for two primary audiences:

### Software Engineers (SWE)
Experienced developers who are new to time series analysis and want to understand both the framework and the underlying concepts. If you're comfortable with Java but haven't worked extensively with temporal data, this guide will walk you through the fundamental concepts and practical applications.

### Time Series Experts (TSx)
Domain experts coming from R, Python, MATLAB, or other statistical computing environments who need to leverage OpenTSx's distributed processing capabilities. This guide provides conceptual bridges between familiar operations and OpenTSx's API.

## How This Manual is Organized

This manual takes a **concept-first approach**, focusing on understanding the "why" before diving into the "how":

1. **Core Concepts** — Understand the fundamental building blocks: TimeSeriesObject, data models, and architectural principles
2. **Data Operations** — Learn how to create, load, transform, and export time series data
3. **Statistical Analysis** — Explore statistical methods for analyzing temporal patterns
4. **Advanced Topics** — Dive into distributed processing, real-time streams, and production deployment
5. **Best Practices** — Develop robust, maintainable time series applications

## Philosophy

OpenTSx embraces several key design principles:

- **Simplicity over complexity** — The core API is deliberately minimal and intuitive
- **Flexibility without magic** — Transparent data structures enable custom operations
- **Scalability by design** — Built on Spark and Kafka for horizontal scaling
- **Interoperability first** — Easy integration with R, Python, and visualization tools

## Getting Started

If you're new to OpenTSx, we recommend following this learning path:

1. Read [**Core Concepts**](core-concepts/README.md) to understand the fundamental data model
2. Work through [**Data Operations**](data-operations/README.md) to learn practical manipulation techniques
3. Explore [**Statistical Analysis**](statistical-analysis/README.md) for analytical methods
4. Review [**Best Practices**](best-practices/README.md) for production-ready code

## A Note on This Manual

This is a **living document** that grows with the OpenTSx ecosystem. Each section is designed to be read independently, so feel free to jump to topics that interest you. Code examples are conceptual rather than exhaustive — they illustrate ideas rather than serving as copy-paste templates.

---

**Ready to begin?** Start with [Core Concepts →](core-concepts/README.md)
