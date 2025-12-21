# OpenTSx Onboarding Paths

Welcome to the OpenTSx learning paths! Choose the track that best matches your background and goals.

## 🎯 Available Learning Tracks

### 1. Software Engineer Track (Java)
**Target Audience**: Experienced software engineers with Java/Scala skills
**Duration**: 15-20 hours (10 episodes)
**Focus**: Building production-ready time series applications

👉 **[Start Learning →](ONBOARDING-PATH-SWE.md)**

**You'll Learn:**
- OpenTSx architecture and data structures
- Creating and manipulating time series
- Building streaming pipelines with Kafka
- Deploying to production
- Performance optimization

**Prerequisites:**
- Java 8+ proficiency
- Basic Scala knowledge (helpful)
- Distributed systems concepts
- Spark and/or Kafka (recommended)

---

### 2. Time Series Expert Track (Java)
**Target Audience**: Domain experts in time series analysis (R/Python background)
**Duration**: 15-20 hours (10 episodes)
**Focus**: Translating time series knowledge to OpenTSx

👉 **[Start Learning →](ONBOARDING-PATH-TSx.md)**

**You'll Learn:**
- R/Python to OpenTSx translation
- Statistical analysis in OpenTSx
- Advanced algorithms (DFA, MFDFA, Event Sync)
- Validating results against R/Python
- Integration patterns

**Prerequisites:**
- Strong time series analysis background
- R or Python experience
- Statistical computing knowledge
- Basic Java understanding (helpful)

---

### 3. Python Developer Track ⭐ NEW
**Target Audience**: Python developers and data scientists
**Duration**: 12-15 hours (8 episodes)
**Focus**: Time series analysis with Python OpenTSx package

👉 **[Start Learning →](ONBOARDING-PATH-Python.md)**

**You'll Learn:**
- Installing and using the Python package
- TimeSeriesObject fundamentals
- DFA (Detrended Fluctuation Analysis)
- MFDFA (Multifractal Analysis)
- Event Synchronization
- RIS (Return Interval Statistics)
- Java-Python interoperability
- Production deployment

**Prerequisites:**
- Python 3.9+ proficiency
- NumPy and pandas experience
- Basic time series concepts
- Jupyter notebooks (recommended)

**What's Included:**
- ✅ Complete Python API
- ✅ 86% feature parity with Java
- ✅ NumPy/pandas integration
- ✅ Jupyter-friendly
- ✅ Visualization support

---

### 4. Flink Integration Track
**Target Audience**: Apache Flink developers
**Duration**: 8-10 hours
**Focus**: Integrating OpenTSx with Flink streaming

👉 **[Start Learning →](ONBOARDING-PATH-Flink.md)**

**You'll Learn:**
- Flink + OpenTSx integration patterns
- Custom Flink functions
- State management
- Windowing strategies
- Deployment to Flink clusters

**Prerequisites:**
- Apache Flink experience
- Java proficiency
- Streaming concepts
- Basic OpenTSx knowledge

---

## 📊 Comparison Matrix

| Feature | SWE Track | TSx Track | Python Track | Flink Track |
|---------|-----------|-----------|--------------|-------------|
| **Duration** | 15-20h | 15-20h | 12-15h | 8-10h |
| **Episodes** | 10 | 10 | 8 | 6 |
| **Language** | Java/Scala | Java | Python | Java |
| **Focus** | Engineering | Analysis | Data Science | Stream Processing |
| **Prerequisites** | Java + Distributed Systems | R/Python + Stats | Python + NumPy | Flink + Java |
| **Exercises** | ✅ 21 exercises | ✅ 21 exercises | ✅ Hands-on | ✅ Hands-on |
| **Production Ready** | ✅ Yes | ⚠️ Partial | ✅ Yes | ✅ Yes |

---

## 🎓 Learning Path Selector

### I want to...

#### Build production streaming applications
→ **[Software Engineer Track (Java)](ONBOARDING-PATH-SWE.md)**
- Kafka Streams integration
- High-throughput processing
- Enterprise deployment

#### Analyze time series data with Python
→ **[Python Developer Track](ONBOARDING-PATH-Python.md)** ⭐ NEW
- Jupyter notebooks
- pandas/NumPy integration
- Quick prototyping

#### Translate my R/Python knowledge to OpenTSx
→ **[Time Series Expert Track](ONBOARDING-PATH-TSx.md)**
- Statistical methods
- Algorithm validation
- R/Python integration

#### Integrate with Apache Flink
→ **[Flink Integration Track](ONBOARDING-PATH-Flink.md)**
- Flink custom functions
- Streaming analytics
- Cluster deployment

---

## 📚 Episode Overview by Track

### Software Engineer Track (Java)

| Episode | Topic | Duration | Focus |
|---------|-------|----------|-------|
| 1 | Environment Setup | 90 min | Build & run locally |
| 2 | Time Series Data Structures | 90 min | Core concepts |
| 3 | Basic Operations | 120 min | Transformations |
| 4 | Kafka Integration | 180 min | Streaming setup |
| 5 | Avro Serialization | 120 min | Data formats |
| 6 | Storage Backends | 150 min | Persistence |
| 7 | Streaming Processing | 180 min | KStreams apps |
| 8 | ksqlDB Integration | 120 min | SQL streaming |
| 9 | Statistical Analysis | 150 min | Algorithms |
| 10 | Production Configuration | 180 min | Deployment |

### Python Developer Track ⭐

| Episode | Topic | Duration | Focus |
|---------|-------|----------|-------|
| 1 | Installation & Setup | 60 min | Environment |
| 2 | TimeSeriesObject Fundamentals | 90 min | Core API |
| 3 | DFA Analysis | 120 min | Long-range correlations |
| 4 | MFDFA | 150 min | Multifractal analysis |
| 5 | Event Synchronization | 120 min | Event patterns |
| 6 | RIS | 120 min | Risk assessment |
| 7 | Java-Python Interoperability | 150 min | Data exchange |
| 8 | Production Deployment | 120 min | Best practices |

### Time Series Expert Track (Java)

| Episode | Topic | Duration | Focus |
|---------|-------|----------|-------|
| 1 | Introduction for TSx Experts | 60 min | Overview |
| 2 | Translation Guide (R/Python → OpenTSx) | 120 min | Rosetta stone |
| 3 | Statistical Methods | 150 min | Core stats |
| 4 | Time Series Decomposition | 120 min | Trends, seasonality |
| 5 | Advanced Algorithms | 180 min | DFA, MFDFA |
| 6 | Event Detection | 120 min | Pattern recognition |
| 7 | Forecasting | 150 min | Predictive models |
| 8 | Validation & Testing | 120 min | Result verification |
| 9 | Integration with R/Python | 150 min | Hybrid workflows |
| 10 | Research Applications | 120 min | Case studies |

---

## 🚀 Getting Started

### Step 1: Choose Your Track

Select the track that matches your background using the selector above.

### Step 2: Prepare Your Environment

**For Java Tracks (SWE, TSx, Flink):**
```bash
# Clone repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx

# Validate environment
./bin/000_validate_environment.sh

# Build project
./bin/010_build.sh
```

**For Python Track:**
```bash
# Clone repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx/python-package

# Create virtual environment
python3 -m venv venv
source venv/bin/activate

# Install package
pip install -e ".[all]"

# Verify installation
python test_implementations.py
```

### Step 3: Follow the Episodes

Each track is divided into episodes with:
- **Theory** - Concepts and background (10-30 min)
- **Demo** - Live examples and code walkthroughs
- **Exercises** - Hands-on practice (60-90 min)
- **Validation** - Checkpoints to verify understanding

### Step 4: Complete Exercises

All tracks include hands-on exercises:
- **SWE & TSx Tracks**: 21+ exercises with solutions
- **Python Track**: 32+ exercises across 8 episodes
- **Flink Track**: 15+ integration exercises

### Step 5: Validate Your Progress

Use validation scripts to check your work:
```bash
# Java tracks
cd exercises/episode-02
javac Exercise1_ManualCreation.java
java Exercise1_ManualCreation

# Python track
cd python-package/examples
python 01_time_series_basics.py
```

---

## 📖 Additional Resources

### Documentation
- [User Guide](../USER-GUIDE.md) - Quick start guide
- [FAQ](../FAQ.md) - Frequently asked questions
- [Troubleshooting](../TROUBLESHOOTING.md) - Common issues
- [API Reference](../API-DOCUMENTATION.md) - Complete API docs

### Guides
- [Architecture Guide](../guides/ARCHITECTURE.md) - System design
- [Feature Comparison](../guides/FEATURE_COMPARISON_JAVA_PYTHON.md) - Java vs Python
- [Interoperability Guide](../guides/INTEROPERABILITY_GUIDE.md) - Java ↔ Python
- [Deployment Guide](../guides/DEPLOYMENT.md) - Production deployment

### Examples
- `/demo/` - Java demo applications
- `/python-package/examples/` - Python examples
- `/bin/` - Utility scripts

---

## 💡 Tips for Success

### 1. Hands-On Practice
- Don't just read - code along
- Complete all exercises
- Experiment beyond the examples

### 2. Use the Right Tools
- **Java**: IntelliJ IDEA or Eclipse
- **Python**: Jupyter Lab or VS Code
- **Version Control**: Git for tracking progress

### 3. Join the Community
- Ask questions on GitHub Issues
- Share your progress
- Help other learners

### 4. Build Real Projects
- Start with sample data
- Apply to your domain
- Share your use cases

---

## 🎯 Completion Criteria

You've completed a track when you can:

### SWE Track ✅
- [ ] Build OpenTSx from source
- [ ] Create and manipulate time series
- [ ] Set up Kafka streaming pipeline
- [ ] Deploy to production
- [ ] Optimize for performance

### Python Track ✅
- [ ] Install and configure Python package
- [ ] Run DFA, MFDFA, Event Sync, RIS
- [ ] Integrate with pandas/NumPy
- [ ] Exchange data with Java systems
- [ ] Deploy production pipelines

### TSx Track ✅
- [ ] Translate R/Python code to OpenTSx
- [ ] Run statistical analyses
- [ ] Validate results
- [ ] Integrate with existing workflows

### Flink Track ✅
- [ ] Create Flink custom functions
- [ ] Deploy streaming jobs
- [ ] Manage state and windows

---

## 🏆 Next Steps After Completion

1. **Build a Project**: Apply your knowledge
2. **Contribute**: Help improve OpenTSx
3. **Share**: Write blog posts or tutorials
4. **Advance**: Explore advanced topics
5. **Mentor**: Help onboard others

---

## 📧 Support

Need help?

- **GitHub Issues**: https://github.com/kamir/OpenTSx/issues
- **Discussions**: https://github.com/kamir/OpenTSx/discussions
- **Documentation**: https://docs.opentsx.org
- **Email**: info@opentsx.org

---

## 📄 License

OpenTSx is licensed under the Apache License 2.0.

---

**Ready to start?** Choose your track above and begin your OpenTSx journey! 🚀

**Last Updated**: December 21, 2025
**Maintained By**: OpenTSx Core Team
