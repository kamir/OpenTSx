# OpenTSx Onboarding - Troubleshooting Guide

**Last Updated:** 2025-12-20

This guide helps you resolve common issues when working with OpenTSx Onboarding.

---

## Table of Contents

- [Build Issues](#build-issues)
- [Runtime Errors](#runtime-errors)
- [Docker Issues](#docker-issues)
- [Exercise Problems](#exercise-problems)
- [Demo Script Issues](#demo-script-issues)
- [Environment Issues](#environment-issues)
- [Getting More Help](#getting-more-help)

---

## Build Issues

### Maven Build Fails

**Symptom:**
```
[ERROR] Failed to execute goal... Could not resolve dependencies
```

**Causes & Solutions:**

#### 1. Network Issues

**Check:**
```bash
# Test Maven repository access
curl https://repo.maven.apache.org/maven2/

# Check proxy settings
env | grep -i proxy
```

**Fix:**
```bash
# Clear local cache
rm -rf ~/.m2/repository

# Retry build
mvn clean install
```

#### 2. Java Version Mismatch

**Check:**
```bash
java -version  # Should show 1.8+ or 8+
```

**Fix:**
```bash
# Install correct Java version
# macOS:
brew install openjdk@11

# Ubuntu:
sudo apt install openjdk-11-jdk

# Set JAVA_HOME
export JAVA_HOME=/path/to/jdk
```

#### 3. Maven Out of Memory

**Symptom:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Fix:**
```bash
# Increase Maven memory
export MAVEN_OPTS="-Xmx2g -Xms512m"
mvn clean install
```

### Compilation Errors in Demo Files

**Symptom:**
```
[ERROR] /path/to/Demo.java:[line] error: cannot find symbol
```

**This is usually OK!**
- Demo files may have errors if dependencies aren't downloaded
- Your exercises will still work
- Code is correct, just missing some libraries

**If you need demos to work:**
```bash
# Ensure full build completed
mvn clean install -U

# Force dependency download
mvn dependency:resolve
```

---

## Runtime Errors

### ClassNotFoundException

**Symptom:**
```
java.lang.ClassNotFoundException: org.opentsx.data.series.TimeSeriesObject
```

**Causes & Solutions:**

#### 1. Classpath Not Set

**Fix:**
```bash
# Build classpath correctly
CLASSPATH="opentsx-core/target/opentsx-core-2.3-SNAPSHOT.jar"
for jar in opentsx-core/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# Run with classpath
java -cp "$CLASSPATH" YourClass
```

#### 2. Project Not Built

**Check:**
```bash
ls opentsx-core/target/*.jar
```

**Fix:**
```bash
./bin/010_build.sh
```

### NoSuchMethodError

**Symptom:**
```
java.lang.NoSuchMethodError: org.opentsx.data.series.TimeSeriesObject.getMeanY()
```

**Cause:** Using wrong method name (API quirk)

**Fix:**
```java
// Wrong:
double mean = ts.getMeanY();

// Correct:
double mean = ts.getAvarage();  // Note the typo
```

**See:** [API Reference](manual/appendix/api-reference.md#api-quirks)

### NullPointerException

**Symptom:**
```
java.lang.NullPointerException
    at Exercise.java:42
```

**Common Causes:**

#### 1. Empty Time Series

**Check:**
```java
if (ts == null || ts.yValues.size() == 0) {
    System.err.println("Time series is empty!");
    return;
}
```

#### 2. File Not Found

**Check:**
```java
File file = new File("data.csv");
if (!file.exists()) {
    System.err.println("File not found: " + file.getAbsolutePath());
    return;
}
```

#### 3. Type Casting Issue

**Fix:**
```java
// Correct type casting
Double value = (Double)ts.yValues.elementAt(i);

// Check for null
if (value == null) {
    // Handle null case
}
```

---

## Docker Issues

### Port Already in Use

**Symptom:**
```
Error starting userland proxy: listen tcp4 0.0.0.0:9092: bind: address already in use
```

**Check what's using the port:**
```bash
# Find process
lsof -i :9092
# or
netstat -an | grep 9092
```

**Solutions:**

#### Option 1: Kill the Process
```bash
# Find PID
lsof -i :9092

# Kill it
kill -9 <PID>
```

#### Option 2: Change Port
Edit `docker-compose.local.yml`:
```yaml
ports:
  - "9093:9092"  # Changed from 9092:9092
```

### Container Won't Start

**Symptom:**
```
ERROR: for opentsx-kafka  Container "xxx" is unhealthy
```

**Check logs:**
```bash
docker-compose -f docker-compose.local.yml logs kafka
docker-compose -f docker-compose.local.yml logs hbase
```

**Common fixes:**

#### 1. Not Enough Memory
```bash
# Check Docker stats
docker stats

# Increase Docker Desktop memory:
# Settings → Resources → Memory → 8GB+
```

#### 2. Previous Container State
```bash
# Clean slate
docker-compose -f docker-compose.local.yml down -v
docker-compose -f docker-compose.local.yml up -d
```

#### 3. Startup Order
Some services need time:
```bash
# Wait for dependencies
docker-compose -f docker-compose.local.yml up -d postgres redis
sleep 10
docker-compose -f docker-compose.local.yml up -d
```

### HBase Connection Issues

**Symptom:**
OpenTSDB can't connect to HBase

**Solution:**
```bash
# HBase takes 1-2 minutes to fully start
# Check status
docker exec opentsx-hbase hbase shell -n

# In shell:
status

# Wait for "Master has completed initialization"
```

---

## Exercise Problems

### Statistics Don't Match Expected

**Symptom:**
Mean is 49.8 but expected 50.0

**This is usually OK!**
- Random distributions vary slightly
- Tolerance is built into validation
- Check if within acceptable range (usually ±2.0)

**If too far off:**

#### 1. Set Random Seed
```java
import org.opentsx.tsa.rng.RNGWrapper;

// For reproducible results
RNGWrapper.init(42);  // Fixed seed
```

#### 2. Increase Sample Size
```java
// More points = closer to expected
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(
    5000,  // Increased from 500
    50.0,
    10.0
);
```

### File Not Created

**Symptom:**
Output CSV file doesn't exist

**Checks:**

#### 1. Verify Write Path
```java
File output = new File("output.csv");
System.out.println("Writing to: " + output.getAbsolutePath());
ts.writeToFile(output, ',');
```

#### 2. Check Permissions
```bash
# Current directory writable?
ls -ld .

# Create output directory
mkdir -p output
```

#### 3. Verify No Exceptions
```java
try {
    ts.writeToFile(output, ',');
    System.out.println("File written successfully");
} catch (Exception e) {
    System.err.println("Write failed: " + e.getMessage());
    e.printStackTrace();
}
```

### Type Casting Errors

**Symptom:**
```
error: incompatible types: Object cannot be converted to double
```

**Cause:** Missing type cast for Vector elements

**Fix:**
```java
// Wrong:
double y = ts.yValues.elementAt(i);

// Correct:
double y = (Double)ts.yValues.elementAt(i);

// Or with null check:
Object obj = ts.yValues.elementAt(i);
if (obj instanceof Double) {
    double y = (Double)obj;
}
```

---

## Demo Script Issues

### Script Won't Execute

**Symptom:**
```
Permission denied: ./bin/episode_02_create_timeseries.sh
```

**Fix:**
```bash
chmod +x bin/episode_02_create_timeseries.sh
# Or all at once:
chmod +x bin/episode_*.sh
```

### Script Fails with "Command not found"

**Symptom:**
```
./bin/episode_02_create_timeseries.sh: line 42: java: command not found
```

**Cause:** Java not in PATH

**Fix:**
```bash
# Find Java
which java

# If not found, install Java
# macOS:
brew install openjdk@11

# Ubuntu:
sudo apt install openjdk-11-jdk

# Add to PATH
export PATH="/path/to/jdk/bin:$PATH"
```

### Script Can't Find JAR

**Symptom:**
```
Error: Could not find opentsx-core-2.3-SNAPSHOT.jar
```

**Check:**
```bash
ls opentsx-core/target/*.jar
```

**Fix:**
```bash
# Build project
./bin/010_build.sh

# Verify JAR exists
ls -lh opentsx-core/target/*.jar
```

---

## Environment Issues

### JAVA_HOME Not Set

**Symptom:**
```
Error: JAVA_HOME is not defined correctly
```

**Find Java location:**
```bash
# macOS:
/usr/libexec/java_home -V

# Linux:
update-alternatives --list java
```

**Set JAVA_HOME:**
```bash
# macOS (add to ~/.zshrc or ~/.bash_profile):
export JAVA_HOME=$(/usr/libexec/java_home -v 11)

# Linux (add to ~/.bashrc):
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64

# Apply:
source ~/.bashrc  # or ~/.zshrc
```

### M2_HOME Not Set (Optional)

Usually not needed, but if required:

```bash
# Find Maven
which mvn

# Set M2_HOME (add to ~/.bashrc)
export M2_HOME=/usr/share/maven
export PATH=$M2_HOME/bin:$PATH
```

### Git Issues

**Symptom:**
```
git: command not found
```

**Fix:**
```bash
# macOS:
xcode-select --install

# Ubuntu:
sudo apt install git

# Verify:
git --version
```

---

## Performance Issues

### Slow Exercise Execution

**Causes & Solutions:**

#### 1. Large Data Sets
**Symptom:** Processing 100K+ points is slow

**Optimize:**
```java
// Use batch operations
TimeSeriesObject.getGaussianDistribution(100000, 50.0, 10.0);
// Is faster than:
for (int i = 0; i < 100000; i++) {
    ts.addValue(RNGWrapper.getStdRandomGaussian(50.0, 10.0));
}
```

#### 2. Inefficient Iteration
**Optimize:**
```java
// Cache size
int size = ts.yValues.size();
for (int i = 0; i < size; i++) {
    // Process
}

// Instead of:
for (int i = 0; i < ts.yValues.size(); i++) {  // Called every iteration
    // Process
}
```

#### 3. Memory Issues
**Increase heap:**
```bash
java -Xmx4g -cp "$CLASSPATH" YourExercise
```

---

## Common API Mistakes

### Using Wrong Method Names

| ❌ Wrong | ✅ Correct | Notes |
|---------|----------|-------|
| `getMeanY()` | `getAvarage()` | API typo |
| `getLength()` | `yValues.size()` | No method |
| `getValueAt(i)` | `(Double)yValues.elementAt(i)` | Direct access |
| `sumY()` | `summeY()` | German naming |

### Mutability Confusion

**Problem:** Modifying original when you meant to create a copy

**Solutions:**
```java
// Always copy first for safety
TimeSeriesObject copy = original.copy();
copy.scaleY_2(2.0);  // Only copy is modified

// Or use immutable operations
TimeSeriesObject normalized = original.normalizeToStdevIsOne();
// original unchanged
```

**See:** [API Reference - Mutability](manual/appendix/api-reference.md#mutability)

---

## Validation Test Failures

### Tests Failing Unexpectedly

**Symptom:**
```
✗ FAIL: Mean: 50.12 (expected 50.0 ±1.0)
```

**Usually not a problem:**
- Random variations within tolerance
- Platform differences
- Floating point precision

**If consistently failing:**

#### 1. Check Random Seed
```java
// Add to test
RNGWrapper.init(42);
```

#### 2. Adjust Tolerance
For your own tests, you can increase tolerance:
```java
validator.checkStatistic("Mean", actual, 50.0, 2.0);  // Increased tolerance
```

#### 3. Verify Logic
```java
// Print intermediate values
System.out.println("Calculated mean: " + ts.getAvarage());
System.out.println("Expected: 50.0");
System.out.println("Difference: " + Math.abs(ts.getAvarage() - 50.0));
```

---

## Getting More Help

### Before Asking for Help

**Checklist:**

1. **Check this guide** — Most issues covered here
2. **Check FAQ** — [docs/FAQ.md](FAQ.md)
3. **Search existing issues** — [GitHub Issues](https://github.com/kamir/OpenTSx/issues)
4. **Review documentation** — [docs/manual/](manual/README.md)

### When Asking for Help

**Provide:**

1. **What you're trying to do**
2. **What you expected**
3. **What actually happened**
4. **Full error message** (copy-paste, not screenshot if possible)
5. **Your environment:**
   ```bash
   java -version
   mvn -version
   uname -a  # or `ver` on Windows
   ```

6. **Steps to reproduce**
7. **Relevant code snippet**

### Where to Ask

**GitHub Discussions** (preferred)
- Questions and answers
- Community support
- Searchable history

**GitHub Issues**
- Bugs only
- Feature requests
- Documentation errors

**Not recommended:**
- Email (doesn't help community)
- Social media (hard to track)

---

## Emergency Fixes

### Nuclear Option: Start Fresh

If everything is broken:

```bash
# 1. Clean all build artifacts
mvn clean
rm -rf target/
rm -rf ~/.m2/repository/org/opentsx

# 2. Stop and remove all Docker containers
docker-compose -f docker-compose.local.yml down -v

# 3. Delete generated files
rm -f *.csv *.log

# 4. Fresh build
./bin/010_build.sh

# 5. Verify
./bin/000_validate_environment.sh
```

### Still Broken?

**Last resort:**
1. Note your current work
2. Clone fresh repository
3. Copy your exercise solutions
4. Start from scratch

---

## Platform-Specific Issues

### macOS

**Issue:** SSL certificate problems
```bash
# Update certificates
brew install ca-certificates
```

**Issue:** Permission denied for Docker
```bash
# Add user to docker group (requires Docker Desktop)
# Or use sudo
```

### Linux

**Issue:** Docker requires sudo
```bash
# Add user to docker group
sudo usermod -aG docker $USER
# Log out and back in
```

**Issue:** Maven not found
```bash
sudo apt install maven
```

### Windows

**Issue:** Line ending problems
```bash
# Convert line endings
dos2unix bin/*.sh
```

**Issue:** Path with spaces
```bash
# Use quotes
cd "C:\Path With Spaces\OpenTSx"
```

---

## Still Stuck?

**We're here to help!**

1. **GitHub Discussions:** Ask the community
2. **GitHub Issues:** Report bugs
3. **Documentation:** [docs/manual/](manual/README.md)
4. **FAQ:** [docs/FAQ.md](FAQ.md)

**Remember:** There are no stupid questions. We all started somewhere!

---

**Quick Links:**
- [FAQ](FAQ.md)
- [Main README](../README.md)
- [API Reference](manual/appendix/api-reference.md)
- [Exercises](../exercises/README.md)

---

**Last Updated:** 2025-12-20
**Maintainers:** OpenTSx Core Team

**Found an issue with this guide?**
- Open an issue
- Submit a PR
- Help us improve!
