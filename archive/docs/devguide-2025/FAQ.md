# OpenTSx Onboarding - Frequently Asked Questions (FAQ)

**Last Updated:** 2025-12-20

Welcome to the OpenTSx Onboarding FAQ! Find answers to common questions about getting started, exercises, and the learning tracks.

---

## Table of Contents

- [Getting Started](#getting-started)
- [Learning Tracks](#learning-tracks)
- [Exercises](#exercises)
- [Technical Questions](#technical-questions)
- [Troubleshooting](#troubleshooting)
- [Community & Support](#community--support)

---

## Getting Started

### Q: What is OpenTSx Onboarding?

**A:** OpenTSx Onboarding is a comprehensive, hands-on learning path for time series analysis in Java. It includes:
- 21 practical exercises across 4 episodes
- Two learning tracks (SWE and TSx)
- Complete solutions with detailed comments
- Automated validation framework
- Docker Compose infrastructure
- Comprehensive documentation

**Target:** Software engineers new to time series OR data scientists transitioning from R/Python to Java.

### Q: Is this course free?

**A:** Yes! OpenTSx Onboarding is completely free and open source (Apache 2.0 license). No hidden costs, no registration required.

### Q: How long does it take to complete?

**A:**
- **SWE Track:** ~3.25 hours (Episodes 2, 3, 10)
- **TSx Track:** ~1.25 hours (Episode 9) + 2 hours reviewing Episodes 2-3
- **Full Coverage:** ~6 hours for both tracks

These are estimates. Actual time varies based on experience and depth of exploration.

### Q: What are the prerequisites?

**Required:**
- Basic Java knowledge (variables, loops, methods, classes)
- Java 8+ installed
- Maven 3.6+
- Git
- Command line familiarity

**Optional but helpful:**
- Docker (for infrastructure setup)
- Time series concepts (for TSx track)
- R/Python experience (for TSx track)

### Q: Do I need to install anything?

**Yes, basic setup:**
```bash
# 1. Clone repository
git clone https://github.com/kamir/OpenTSx.git
cd OpenTSx

# 2. Build project
./bin/010_build.sh

# 3. Validate environment
./bin/000_validate_environment.sh

# Optional: Start infrastructure
docker-compose -f docker-compose.local.yml up -d
```

That's it! All dependencies are managed by Maven.

---

## Learning Tracks

### Q: Which track should I choose?

**Choose SWE Track if you:**
- Are a software engineer/backend developer
- Know Java but not time series
- Want to add time series to your applications
- Focus on production deployment

**Choose TSx Track if you:**
- Are a data scientist/quantitative analyst
- Know R/Python time series analysis
- Want to move to enterprise Java
- Focus on scaling algorithms

**Do both if you:**
- Want comprehensive coverage
- Have time for 6 hours of learning
- Plan to work at intersection of development and data science

### Q: Can I switch tracks midway?

**A:** Absolutely! The tracks aren't mutually exclusive. Many learners:
1. Start with SWE Episodes 2-3 (foundations)
2. Then do TSx Episode 9 (statistics)
3. Finish with SWE Episode 10 (production)

### Q: Do I get a certificate?

**A:** Not currently, but you'll gain:
- Practical, production-ready skills
- Portfolio of completed exercises
- GitHub commits showing your work
- Knowledge to pass technical interviews

We may add certificates in the future based on community feedback.

---

## Exercises

### Q: Are solutions provided?

**A:** Yes! Each exercise has:
- Starter code with TODO comments
- Complete solution in `exercises/solutions/`
- Detailed comments explaining the approach

**Recommendation:** Try exercises independently first before looking at solutions.

### Q: How do I know if my solution is correct?

**Three ways:**

1. **Automated Validation:**
   ```bash
   ./bin/run_all_tests.sh --validation-only
   ```

2. **Compare with expected output:**
   - Check statistics (mean, stddev)
   - Verify file generation
   - Review output visually

3. **Review solution files:**
   - Located in `exercises/solutions/`
   - Compare your approach

### Q: What if I get stuck on an exercise?

**Steps to take:**

1. **Review the demo:**
   ```bash
   ./bin/episode_02_create_timeseries.sh
   ```

2. **Check documentation:**
   - [Core Concepts](docs/manual/core-concepts/)
   - [API Reference](docs/manual/appendix/api-reference.md)

3. **Look at similar patterns:**
   - Review previous exercises
   - Check demo Java files

4. **Ask for help:**
   - GitHub Discussions
   - Review solution file (last resort)

### Q: Can I skip exercises?

**A:** You can, but it's not recommended. Each exercise builds on previous ones. If you're short on time:
- Do main exercises, skip bonus
- Focus on one track first
- Come back later for deep practice

### Q: How are exercises validated?

**A:** We provide automated validation tests that check:
- Statistical correctness (mean, stddev within tolerance)
- File generation
- TimeSeriesObject properties
- Edge cases

Run with: `./bin/run_all_tests.sh`

---

## Technical Questions

### Q: Why does `getMeanY()` not exist?

**A:** The correct method is `getAvarage()` (note the typo). This is a known API quirk kept for backward compatibility.

```java
// Wrong:
double mean = ts.getMeanY();

// Correct:
double mean = ts.getAvarage();
```

See [API Reference](docs/manual/appendix/api-reference.md#api-quirks) for other quirks.

### Q: Why do I need type casting for Vector elements?

**A:** TimeSeriesObject uses public Vector storage for transparency. Java generics require casting:

```java
// Correct:
double value = (Double)ts.yValues.elementAt(i);

// Wrong (compilation error):
double value = ts.yValues.elementAt(i);
```

This is a deliberate design choice for direct access.

### Q: What's the difference between in-place and immutable operations?

**A:** Critical distinction:

**In-place (modifies original):**
```java
ts.scaleY_2(2.0);        // ts is modified
ts.add_to_Y(10.0);       // ts is modified
```

**Immutable (returns new object):**
```java
TimeSeriesObject normalized = ts.normalizeToStdevIsOne();
// ts unchanged, normalized is new object
```

**Best practice:** Always copy before in-place operations if you need the original:
```java
TimeSeriesObject copy = ts.copy();
copy.scaleY_2(2.0);
```

### Q: How do I load CSV files?

**A:**
```java
import org.opentsx.data.loader.MessreihenLoader;
import java.io.File;

MessreihenLoader loader = MessreihenLoader.getLoader();
loader.delim = ",";

// Load columns 1 (time) and 2 (value)
TimeSeriesObject ts = loader.loadMessreihe_2(
    new File("data.csv"), 1, 2
);
```

See Episode 2, Exercise 3 for complete example.

### Q: How do I save to CSV?

**A:**
```java
import java.io.File;

TimeSeriesObject ts = /* your data */;
File output = new File("output.csv");
ts.writeToFile(output, ',');  // comma delimiter
```

### Q: What if Maven build fails?

**Common fixes:**

1. **Network issues:**
   ```bash
   # Clear cache and retry
   rm -rf ~/.m2/repository
   mvn clean install
   ```

2. **Java version:**
   ```bash
   # Check version
   java -version  # Should be 8+

   # Set JAVA_HOME if needed
   export JAVA_HOME=/path/to/jdk
   ```

3. **Compilation errors in demos:**
   - This is expected if network prevents downloading dependencies
   - Demo errors don't block your progress
   - Code is correct, just missing dependencies

---

## Troubleshooting

### Q: Project won't build

**See:** [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for detailed solutions.

**Quick checks:**
```bash
# 1. Verify Java
java -version

# 2. Verify Maven
mvn -version

# 3. Clean build
mvn clean install -DskipTests
```

### Q: Docker Compose fails to start

**Common issues:**

1. **Port already in use:**
   ```bash
   # Check what's using port 9092
   lsof -i :9092

   # Kill process or change port in docker-compose.local.yml
   ```

2. **Not enough memory:**
   - Increase Docker Desktop memory to 8GB+
   - Or reduce services in docker-compose.local.yml

3. **Containers not starting:**
   ```bash
   # Check logs
   docker-compose -f docker-compose.local.yml logs

   # Restart
   docker-compose -f docker-compose.local.yml down -v
   docker-compose -f docker-compose.local.yml up -d
   ```

### Q: Demo script won't run

**Checks:**

1. **Permission:**
   ```bash
   chmod +x bin/episode_02_create_timeseries.sh
   ```

2. **Project built:**
   ```bash
   ls opentsx-core/target/*.jar
   # Should see opentsx-core-2.3-SNAPSHOT.jar
   ```

3. **Working directory:**
   ```bash
   # Run from project root
   cd /path/to/OpenTSx
   ./bin/episode_02_create_timeseries.sh
   ```

### Q: Where can I find more help?

**See:** [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for comprehensive troubleshooting guide.

---

## Community & Support

### Q: How do I get help?

**Multiple channels:**

1. **GitHub Discussions** (recommended)
   - Ask questions
   - Share solutions
   - Connect with learners

2. **GitHub Issues**
   - Report bugs
   - Request features
   - Documentation improvements

3. **Documentation**
   - [OpenTSx Manual](docs/manual/README.md)
   - [API Reference](docs/manual/appendix/api-reference.md)
   - [Troubleshooting Guide](docs/TROUBLESHOOTING.md)

### Q: Can I contribute?

**Yes! We welcome:**
- Exercise improvements
- New exercises
- Documentation fixes
- Translation to other languages
- Bug reports
- Success stories

**How:**
1. Fork repository
2. Make changes
3. Submit pull request
4. Follow contribution guidelines

### Q: How do I report bugs?

**Steps:**

1. **Check if it's known:**
   - Review [existing issues](https://github.com/kamir/OpenTSx/issues)
   - Check [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)

2. **Create detailed issue:**
   - What you expected
   - What happened
   - Steps to reproduce
   - Your environment (OS, Java version, etc.)

3. **Include context:**
   - Error messages
   - Relevant code
   - Screenshots if helpful

### Q: Is there a community forum?

**A:** Yes! Use GitHub Discussions for:
- Questions and answers
- Show and tell (your projects)
- Ideas and suggestions
- General discussion

We're building an active, helpful community!

### Q: How can I stay updated?

**Options:**

1. **Watch the repository** (GitHub notifications)
2. **Star the repository** (shows support!)
3. **Follow releases** (new content announcements)
4. **Join discussions** (hear about updates)

### Q: Can I use this for commercial projects?

**A:** Absolutely! Apache 2.0 license allows:
- ✅ Commercial use
- ✅ Modification
- ✅ Distribution
- ✅ Private use

Just include license and copyright notice.

---

## Roadmap & Future

### Q: What's next for OpenTSx Onboarding?

**Planned:**
- Additional episodes (4-8)
- Video walkthroughs
- Interactive exercises
- Community contributions
- Translations

**Depends on community feedback!**

### Q: How can I influence the roadmap?

**Share feedback via:**
- GitHub Discussions (feature requests)
- GitHub Issues (specific improvements)
- Direct email to maintainers
- Survey (coming soon)

We prioritize based on community needs.

---

## Still Have Questions?

**Not finding your answer?**

1. Check [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
2. Search [GitHub Discussions](https://github.com/kamir/OpenTSx/discussions)
3. Ask in [GitHub Discussions](https://github.com/kamir/OpenTSx/discussions/new)
4. Review [Documentation](docs/manual/README.md)

**Found a FAQ mistake?**
- Open an issue
- Submit a pull request
- Help us improve!

---

**Quick Links:**
- [Main README](../README.md)
- [Getting Started Guide](../README.md#getting-started--onboarding)
- [Exercise Materials](../exercises/README.md)
- [API Reference](manual/appendix/api-reference.md)
- [Troubleshooting](TROUBLESHOOTING.md)

---

**Last Updated:** 2025-12-20
**Maintainers:** OpenTSx Core Team
