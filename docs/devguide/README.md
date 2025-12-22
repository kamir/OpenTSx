# OpenTSx Documentation

Welcome to the comprehensive documentation for the **OpenTSx Time Series Analysis Framework**.

This **DevGuide** contains developer-facing docs, platform guides, and operational references. The **Manual** and **Onboarding** guides live in separate GitBook projects: `docs/manual/` and `docs/onboarding/`.

## 📚 Documentation Structure

This documentation is organized into the following parts:

### Part I: Getting Started
- Quick start guides, FAQ, and troubleshooting
- Perfect for new users getting up and running

### Part II: Onboarding Paths
- **Software Engineer Track (Java)** - 10 episodes, 15-20 hours
- **Time Series Expert Track (Java)** - 10 episodes, 15-20 hours
- **Python Developer Track** - 8 episodes, 12-15 hours ⭐ NEW
- **Flink Integration Track** - Specialized stream processing

### Part III: Core Manual
- Fundamental concepts and data structures
- Data operations and transformations
- Statistical analysis techniques

### Part IV: Python Implementation
- Complete Python package documentation
- DFA, MFDFA, Event Synchronization, RIS algorithms
- Java-Python interoperability patterns
- Production deployment guides

### Part V: Advanced Topics
- Distributed processing with Spark
- Real-time streaming with Kafka
- Storage backends and performance optimization

### Part VI: Architecture & Design
- System architecture overview
- Module documentation
- Platform guides (SaaS, Web UI, Security)

### Part VII: Deployment & Operations
- Production deployment strategies
- Release management

### Part VIII: Best Practices
- Error handling, testing, and code organization
- Performance patterns and common pitfalls

### Part IX: Reference Materials
- API documentation
- Glossary and migration guides
- Contributing guidelines

### Part X: Appendices
- Project planning and research
- Implementation summaries
- PDF generation instructions

## 🚀 Quick Links

### For Beginners
1. Start with [User Guide](USER-GUIDE.md)
2. Read [FAQ](FAQ.md) for common questions
3. Follow an [Onboarding Path](../onboarding/)

### For Java Developers
1. [Software Engineer Track](../onboarding/ONBOARDING-PATH-SWE.md)
2. [Architecture Guide](guides/ARCHITECTURE.md)
3. [Modules Documentation](guides/MODULES.md)

### For Python Developers
1. [Python Developer Track](../onboarding/ONBOARDING-PATH-Python.md) ⭐ NEW
2. [Python Installation](../manual/python/installation.md)
3. [Feature Comparison](guides/FEATURE_COMPARISON_JAVA_PYTHON.md)
4. [Interoperability Guide](guides/INTEROPERABILITY_GUIDE.md)

### For Time Series Experts
1. [Time Series Expert Track](../onboarding/ONBOARDING-PATH-TSx.md)
2. [Statistical Analysis](../manual/statistical-analysis/README.md)
3. [Python Algorithms](../manual/python/README.md)

## 📖 Browsing Options

### Online
- Browse this documentation on GitHub
- Navigate using the [SUMMARY.md](SUMMARY.md) table of contents

### PDF Generation

Generate a complete PDF of all documentation:

```bash
# Install GitBook CLI
npm install -g gitbook-cli

# Navigate to the project directory
cd docs/devguide

# Install GitBook plugins
gitbook install

# Generate PDF
gitbook pdf . OpenTSx-DevGuide.pdf
```

To build the other projects:

```bash
cd docs/manual && gitbook install && gitbook pdf . OpenTSx-Manual.pdf
cd docs/onboarding && gitbook install && gitbook pdf . OpenTSx-Onboarding.pdf
```

See [PDF Generation Guide](PDF-GENERATION.md) for detailed instructions.

### HTML Website

Generate a static website:

```bash
cd docs/devguide
gitbook build

# Serve locally
gitbook serve
# Open http://localhost:4000
```

## 🎯 Documentation by Role

### Software Engineers
- [Onboarding Path (SWE)](../onboarding/ONBOARDING-PATH-SWE.md)
- [Architecture](guides/ARCHITECTURE.md)
- [Deployment Guide](guides/DEPLOYMENT.md)
- [Best Practices](../manual/best-practices/README.md)

### Data Scientists (Python)
- [Onboarding Path (Python)](../onboarding/ONBOARDING-PATH-Python.md)
- [Python Implementation](../manual/python/README.md)
- [MFDFA Tutorial](../manual/python/mfdfa.md)
- [Interoperability](guides/INTEROPERABILITY_GUIDE.md)

### Time Series Researchers
- [Onboarding Path (TSx)](../onboarding/ONBOARDING-PATH-TSx.md)
- [Statistical Analysis](../manual/statistical-analysis/README.md)
- [Advanced Algorithms](../manual/python/README.md#core-capabilities)

### DevOps Engineers
- [Deployment Guide](guides/DEPLOYMENT.md)
- [Security Guide](guides/SECURITY.md)
- [Infrastructure Docs](infrastructure/)

### Product Managers
- [Features Overview](guides/FEATURES.md)
- [SaaS Platform](guides/SAAS-PLATFORM.md)
- [Market Research](guides/MARKET_RESEARCH_ANALYSIS.md)

## 📦 What's New in Version 3.0

### ⭐ Python Implementation (December 2025)
- **Complete MFDFA** - Full multifractal analysis with h(q), τ(q), f(α)
- **Event Synchronization** - Directional sync and lead-lag detection
- **RIS** - Return interval statistics for risk assessment
- **Python Onboarding Path** - 8-episode comprehensive curriculum
- **86% Feature Parity** with Java implementation

### 📚 Enhanced Documentation
- New Python Developer Track onboarding path
- Comprehensive interoperability guide (Java ↔ Python)
- Feature comparison matrix
- Implementation summaries

### 🔧 Infrastructure Improvements
- GitBook-ready structure with SUMMARY.md
- PDF generation support
- Organized guides directory
- Cross-referenced documentation

## 🛠️ Documentation Structure

```
docs/
├── README.md                          # This file
├── SUMMARY.md                         # GitBook table of contents
├── book.json                          # GitBook configuration
│
├── ../manual/                            # Core manual
│   ├── introduction/                  # Getting started
│   ├── core-concepts/                 # Fundamental concepts
│   ├── data-operations/               # Working with data
│   ├── statistical-analysis/          # Analysis techniques
│   ├── python/                        # Python implementation ⭐ NEW
│   ├── advanced-topics/               # Advanced features
│   ├── best-practices/                # Production patterns
│   └── appendix/                      # Reference materials
│
├── ../onboarding/                        # Learning paths
│   ├── ONBOARDING-PATH-SWE.md        # Software engineers (Java)
│   ├── ONBOARDING-PATH-TSx.md        # Time series experts (Java)
│   ├── ONBOARDING-PATH-Python.md     # Python developers ⭐ NEW
│   └── ONBOARDING-PATH-Flink.md      # Flink integration
│
├── guides/                            # Comprehensive guides
│   ├── ARCHITECTURE.md                # System architecture
│   ├── FEATURES.md                    # Feature overview
│   ├── MODULES.md                     # Module documentation
│   ├── DEPLOYMENT.md                  # Deployment strategies
│   ├── SECURITY.md                    # Security guide
│   ├── SAAS-PLATFORM.md              # SaaS platform guide
│   ├── WEB-UI-VISUAL-FLOW-BUILDER.md # Web UI documentation
│   ├── FEATURE_COMPARISON_JAVA_PYTHON.md  # Java vs Python ⭐ NEW
│   ├── INTEROPERABILITY_GUIDE.md     # Java-Python interop ⭐ NEW
│   ├── IMPLEMENTATION_SUMMARY.md     # Implementation details ⭐ NEW
│   ├── PYTHON-IMPLEMENTATION-DESIGN.md  # Python design
│   ├── PLAN.md                        # Project plan
│   ├── ROADMAP.md                     # Development roadmap
│   ├── RELEASE-SUMMARY.md            # Release notes
│   └── MARKET_RESEARCH_ANALYSIS.md   # Market analysis
│
├── USER-GUIDE.md                      # Quick start user guide
├── FAQ.md                             # Frequently asked questions
├── TROUBLESHOOTING.md                 # Troubleshooting guide
├── API-DOCUMENTATION.md               # API reference
└── PDF-GENERATION.md                  # PDF generation instructions
```

## 🤝 Contributing to Documentation

We welcome contributions to improve the documentation!

### How to Contribute

1. **Fix typos or errors**: Submit a pull request
2. **Add examples**: Share your use cases
3. **Improve clarity**: Suggest better explanations
4. **Write tutorials**: Create step-by-step guides
5. **Translate**: Help with internationalization

### Documentation Guidelines

- Use clear, concise language
- Include code examples
- Add screenshots where helpful
- Link to related sections
- Keep formatting consistent

See [Contributing Guidelines](../manual/appendix/contributing.md) for details.

## 📧 Getting Help

- **GitHub Issues**: https://github.com/kamir/OpenTSx/issues
- **Discussions**: https://github.com/kamir/OpenTSx/discussions
- **Email**: info@opentsx.org

## 📄 License

This documentation is licensed under the Apache License 2.0.
See the project LICENSE file for details.

---

**Last Updated**: December 21, 2025
**Version**: 3.0.0
**Maintained By**: OpenTSx Core Team

**Start Learning**: Choose your [Onboarding Path](../onboarding/) →
