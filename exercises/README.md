# OpenTSx Exercises

Welcome to the OpenTSx hands-on exercises! This directory contains structured exercises designed to help you master the OpenTSx framework through practical, real-world examples.

## Overview

The exercises are organized by **learning track** and **episode**, matching the onboarding paths described in the main README.

### Learning Tracks

1. **SWE Track (Software Engineering)** — For developers new to time series analysis
2. **TSx Track (Time Series Experts)** — For data scientists from R/Python/MATLAB backgrounds

Each track has exercises tailored to the audience's background and learning objectives.

## Directory Structure

```
exercises/
├── README.md (this file)
├── swe-track/              # Software Engineering track
│   ├── episode-02/         # Creating Time Series
│   ├── episode-03/         # Basic Operations
│   └── episode-10/         # Production Configuration
├── tsx-track/              # Time Series Experts track
│   └── episode-09/         # Statistical Analysis
└── solutions/              # Solution files (try exercises first!)
    ├── swe-track/
    │   ├── episode-02/
    │   ├── episode-03/
    │   └── episode-10/
    └── tsx-track/
        └── episode-09/
```

## Getting Started

### Prerequisites

1. **Build the project:**
   ```bash
   ./bin/010_build.sh
   ```

2. **Verify environment:**
   ```bash
   ./bin/000_validate_environment.sh
   ```

3. **Review the corresponding demo:**
   - Each episode has a demo script in `bin/episode_XX_*.sh`
   - Run the demo before attempting exercises

### How to Use These Exercises

1. **Choose Your Track:**
   - New to time series? → Start with SWE Track
   - Coming from R/Python? → Start with TSx Track
   - Want full coverage? → Do both!

2. **Follow the Episode Order:**
   - Start with Episode 2 (fundamentals)
   - Progress sequentially through episodes
   - Each builds on previous knowledge

3. **Try Before Looking:**
   - Read the exercise description
   - Attempt the implementation independently
   - Use starter code as a template
   - Only check solutions after attempting

4. **Validate Your Work:**
   - Each exercise has a validation checklist
   - Compare output with expected results
   - Review solution files for alternative approaches

## Exercise Tracks

### SWE Track: Software Engineering

**Target Audience:** Developers new to time series analysis

**Episode 2: Creating Time Series** (45 minutes)
- Manual time series construction
- Synthetic data generation
- Loading from CSV files
- File I/O operations

**Episode 3: Basic Operations** (60 minutes)
- Normalization techniques
- In-place vs. immutable operations
- Time series arithmetic
- Windowing and subsetting

**Episode 10: Production Configuration** (90 minutes)
- Multi-source configuration
- Retry logic with exponential backoff
- Structured logging
- Thread-safe resource pooling

**Total SWE Track Time:** ~3.25 hours

### TSx Track: Time Series Experts

**Target Audience:** Data scientists from R/Python/MATLAB

**Episode 9: Statistical Analysis** (75 minutes)
- Moving averages (SMA, WMA, EMA)
- Autocorrelation function (ACF)
- Trend detection and detrending
- Anomaly detection (Z-score method)

**Total TSx Track Time:** ~1.25 hours

**Note:** TSx track users should also review SWE track Episodes 2-3 for Java/OpenTSx-specific patterns.

## Exercise Format

Each exercise directory contains:

### EXERCISES.md
- Learning objectives
- Prerequisites
- Exercise descriptions with tasks
- Starter code templates
- Expected outputs
- Validation checklists
- Common pitfalls
- Resource links

### Solution Files (in `solutions/` directory)
- Complete, working implementations
- Detailed comments explaining approach
- Best practices demonstrated
- Alternative implementations where applicable

## Compiling and Running Exercises

### Setup Classpath

```bash
# From project root
CLASSPATH="opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar"
for jar in opentsx-core/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done
```

### Compile Your Exercise

```bash
# Create output directory
mkdir -p exercises/build

# Compile
javac -cp "$CLASSPATH" \
      -d exercises/build \
      exercises/swe-track/episode-02/Exercise1_ManualCreation.java
```

### Run Your Exercise

```bash
java -cp "$CLASSPATH:exercises/build" \
     org.opentsx.exercises.swe.Exercise1_ManualCreation
```

### Or Use Maven (if you add exercises to opentsx-core)

```bash
# Add exercises to opentsx-core/src/main/java/org/opentsx/exercises/
# Then:
mvn clean compile
mvn exec:java -Dexec.mainClass="org.opentsx.exercises.swe.Exercise1_ManualCreation"
```

## Recommended Learning Paths

### Path 1: Complete Beginner (SWE Track)

**Total Time:** ~4 hours

1. Episode 2: Creating Time Series (45 min)
2. Episode 3: Basic Operations (60 min)
3. Episode 10: Production Config (90 min)
4. Bonus exercises if time permits

### Path 2: Data Scientist (TSx Track)

**Total Time:** ~3 hours

1. Quick review of SWE Track Episode 2 exercises (30 min)
2. SWE Track Episode 3 exercises (60 min)
3. TSx Track Episode 9 exercises (75 min)
4. Bonus: Seasonal decomposition

### Path 3: Full Mastery (Both Tracks)

**Total Time:** ~6 hours

1. All SWE Track exercises in order
2. All TSx Track exercises
3. All bonus exercises
4. Create your own custom exercise

## Exercise Difficulty Levels

- ⭐ **Beginner** — Basic concepts, guided starter code
- ⭐⭐ **Intermediate** — Requires understanding of concepts, less scaffolding
- ⭐⭐⭐ **Advanced** — Minimal starter code, requires independent problem-solving

| Exercise | Track | Difficulty |
|----------|-------|------------|
| Episode 2: Creating TS | SWE | ⭐ Beginner |
| Episode 3: Basic Ops | SWE | ⭐⭐ Intermediate |
| Episode 9: Statistics | TSx | ⭐⭐ Intermediate |
| Episode 10: Production | SWE | ⭐⭐⭐ Advanced |

## Tips for Success

### Before You Start
- [ ] Read the corresponding episode demo code
- [ ] Run the demo script (`./bin/episode_XX_*.sh`)
- [ ] Review relevant documentation sections
- [ ] Understand the learning objectives

### While Working
- [ ] Read the entire exercise before coding
- [ ] Use the starter code as a template
- [ ] Compile frequently to catch errors early
- [ ] Test with different inputs
- [ ] Check edge cases

### After Completing
- [ ] Verify against validation checklist
- [ ] Compare output with expected results
- [ ] Review solution files
- [ ] Note any alternative approaches
- [ ] Try bonus exercises

## Common Issues

### Compilation Errors

**Issue:** `cannot find symbol` errors

**Solution:**
- Ensure project is built: `./bin/010_build.sh`
- Check classpath includes all required JARs
- Verify import statements

### Type Casting

**Issue:** `incompatible types` when accessing Vector elements

**Solution:**
```java
// Wrong:
double value = ts.yValues.elementAt(i);

// Correct:
double value = (Double)ts.yValues.elementAt(i);
```

### File Not Found

**Issue:** CSV files not found when loading

**Solution:**
- Use relative paths from project root
- Ensure `sample_data/` directory exists
- Check file names and extensions

## Getting Help

1. **Check the Documentation:**
   - [OpenTSx Manual](../docs/manual/README.md)
   - [API Reference](../docs/manual/appendix/api-reference.md)
   - [Best Practices](../docs/manual/best-practices/README.md)

2. **Review Demo Code:**
   - Each episode has a demo script
   - Demos show working examples
   - Check `opentsx-core/src/main/java/org/opentsx/demo/onboarding/`

3. **Look at Solutions:**
   - Try independently first!
   - Solutions show complete implementations
   - Compare your approach with solutions

4. **Common Pitfalls:**
   - Each exercise lists common mistakes
   - Read these before attempting

## Contributing Your Own Exercises

Have an idea for a new exercise? Contributions welcome!

### Exercise Submission Guidelines

1. **Follow the format:**
   - EXERCISES.md with clear learning objectives
   - Starter code with TODO comments
   - Complete solution with comments
   - Validation checklist

2. **Test thoroughly:**
   - Ensure starter code compiles
   - Verify solution works correctly
   - Check all edge cases

3. **Document well:**
   - Clear instructions
   - Expected outputs
   - Common pitfalls

4. **Submit a PR:**
   - Add exercise to appropriate track
   - Update this README
   - Include test data if needed

## Assessment and Validation

### Self-Assessment Checkpoints

After each episode, you should be able to:

**Episode 2:**
- [ ] Create TimeSeriesObject manually and programmatically
- [ ] Generate synthetic time series with different distributions
- [ ] Load and save time series from/to CSV files
- [ ] Access and iterate through time series data

**Episode 3:**
- [ ] Apply different normalization techniques
- [ ] Understand in-place vs. immutable operations
- [ ] Perform arithmetic on multiple time series
- [ ] Extract subsets and windows efficiently

**Episode 9:**
- [ ] Implement moving averages (SMA, WMA, EMA)
- [ ] Calculate autocorrelation function
- [ ] Detect and remove trends
- [ ] Identify anomalies using statistical methods

**Episode 10:**
- [ ] Implement multi-source configuration
- [ ] Create robust retry logic
- [ ] Set up structured logging
- [ ] Manage thread-safe resource pools

## Next Steps

After completing the exercises:

1. **Build a Real Project:**
   - Apply learned concepts to actual data
   - Integrate with your systems
   - Deploy to production

2. **Explore Advanced Topics:**
   - Detrended Fluctuation Analysis (DFA)
   - Event Synchronization
   - Multifractal Analysis
   - Machine Learning Integration

3. **Review Advanced Documentation:**
   - [Architecture Guide](../ARCHITECTURE.md)
   - [Features Documentation](../FEATURES.md)
   - [Deployment Guide](../DEPLOYMENT.md)

4. **Join the Community:**
   - Share your implementations
   - Ask questions
   - Contribute exercises

## Quick Reference Links

- **Main Documentation:** [README.md](../README.md)
- **Onboarding Guide:** [Getting Started](../README.md#getting-started--onboarding)
- **SWE Track Path:** [ONBOARDING-PATH-SWE.md](../ONBOARDING-PATH-SWE.md)
- **TSx Track Path:** [ONBOARDING-PATH-TSx.md](../ONBOARDING-PATH-TSx.md)
- **Demo Scripts:** [bin/README.md](../bin/README.md)
- **Local Development:** [docs/devguide/infrastructure/local-development.md](../docs/devguide/infrastructure/local-development.md)

---

**Happy Learning!**

These exercises are designed to give you practical, hands-on experience with OpenTSx. Take your time, experiment, and don't hesitate to explore beyond the exercises.

**Remember:** The best way to learn is by doing. Try the exercises independently before looking at solutions!

---

**Last Updated:** 2025-12-20
**Maintained By:** OpenTSx Core Team
