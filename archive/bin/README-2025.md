# OpenTSx Scripts Reference

This directory contains scripts for building, running, and demonstrating OpenTSx capabilities.

## Quick Start

```bash
# 0. Run a minimal demo (no Kafka)
./bin/005_minimal_no_kafka_demo.sh

# 1. Start local infrastructure (optional but recommended)
docker-compose -f docker-compose.local.yml up -d

# 2. Build the project
./bin/010_build.sh

# 3. Launch the GUI
./bin/000_launch_tsa_workbench.sh

# 4. Run a demo
./bin/120_run_demo.sh
```

For Kafka integration testing, see [Local Development Guide](../docs/devguide/infrastructure/local-development.md).

## Script Catalog

### Setup & Build Scripts

| Script | Purpose | Episode | Prerequisites |
|--------|---------|---------|---------------|
| `010_build.sh` | Build OpenTSx project with Maven | E01 | Java 8+, Maven |
| `001_build_containers.sh` | Build Docker containers | Advanced | Docker |

### Infrastructure Scripts

**Local Development (Recommended)**

Use the simplified Docker Compose setup for local development:

```bash
# Start core infrastructure (Kafka, OpenTSDB, databases)
docker-compose -f docker-compose.local.yml up -d

# Start full stack (includes backend/frontend)
docker-compose -f docker-compose.local.yml --profile full up -d

# Stop all services
docker-compose -f docker-compose.local.yml down
```

See [Local Development Guide](../docs/devguide/infrastructure/local-development.md) for details.

**Legacy Individual Container Scripts**

| Script | Purpose | Episode | Prerequisites |
|--------|---------|---------|---------------|
| `015_create_kudu_on_docker.sh` | Create Kudu Docker container | E07 | Docker, Git |
| `015_create_opentsdb_on_docker.sh` | Create OpenTSDB Docker container | E07 | Docker, Git |
| `run_kudu_on_docker_locally.sh` | Run Kudu locally | E07 | Docker |
| `run_opentsdb_on_docker_locally.sh` | Run OpenTSDB locally | E07 | Docker |
| `110_run_demo_services.sh` | Start both Kudu and OpenTSDB | E07 | Docker |

### Demo & Visualization Scripts

| Script | Purpose | Episode | Prerequisites |
|--------|---------|---------|---------------|
| `005_minimal_no_kafka_demo.sh` | Minimal local demo (no Kafka) | E00 | Java 8+, Maven |
| `000_launch_tsa_workbench.sh` | Launch GUI (MacroRecorder2) | E01, E03 | Project built |
| `120_run_demo.sh` | Run MacroRecorder demo | E01 | Project built |
| `130_run_demo_in_spark_shell_locally.sh` | Interactive Spark session | E06 | Spark installed |

### Deployment Scripts

| Script | Purpose | Episode | Prerequisites |
|--------|---------|---------|---------------|
| `020_deploy_to_cc_cluster.sh` | Deploy to Cloudera cluster | E10 | Cluster access |

## Recommended Learning Sequence

### For Software Engineers (SWE Track)

1. **Episode 1: Environment Setup**
   ```bash
   ./bin/010_build.sh                    # Build project
   ./bin/000_launch_tsa_workbench.sh    # Explore GUI
   ```

2. **Episode 7: Storage Backends**
   ```bash
   ./bin/015_create_kudu_on_docker.sh   # Setup Kudu
   ./bin/015_create_opentsdb_on_docker.sh # Setup OpenTSDB
   ./bin/110_run_demo_services.sh       # Run both services
   ```

3. **Episode 6: Spark Integration**
   ```bash
   ./bin/130_run_demo_in_spark_shell_locally.sh
   ```

### For Time Series Experts (TSx Track)

1. **Episode 1: From Python/R to OpenTSx**
   ```bash
   ./bin/010_build.sh                   # Build project
   ./bin/000_launch_tsa_workbench.sh   # Visual exploration
   ```

2. **Episode 3: Visualization**
   ```bash
   ./bin/120_run_demo.sh               # MacroRecorder demo
   ```

## Script Details

### 005_minimal_no_kafka_demo.sh

**Purpose**: Run a minimal local demo without Kafka or GUI

**Description**: Builds the `opentsx-lg` module and executes the sine wave generator with GUI and Kafka disabled. Outputs a local dataset to `./data/temp/` in CSV format by default.

**Usage**:
```bash
./bin/005_minimal_no_kafka_demo.sh
```

### 000_launch_tsa_workbench.sh

**Purpose**: Launch the Time Series Analysis Workbench (GUI)

**Description**: Starts the MacroRecorder2 GUI application for interactive time series analysis and visualization.

**Features**:
- Automatic Java detection
- Configurable memory settings
- Visual time series exploration
- Interactive charting

**Usage**:
```bash
# Default launch
./bin/000_launch_tsa_workbench.sh

# With custom memory settings
JAVA_OPTS="-Xmx4g -Xms1g" ./bin/000_launch_tsa_workbench.sh
```

**Troubleshooting**:
- If GUI doesn't appear, check DISPLAY environment variable
- For headless systems, use X11 forwarding or VNC
- Increase memory with JAVA_OPTS if OutOfMemory errors occur

### 010_build.sh

**Purpose**: Build the entire OpenTSx project

**Description**: Compiles all modules using Maven, runs tests (optional), and installs artifacts.

**Usage**:
```bash
# Standard build
./bin/010_build.sh

# Build without tests (faster)
mvn clean install -DskipTests=true
```

### 110_run_demo_services.sh

**Purpose**: Start local Kudu and OpenTSDB services

**Description**: Launches both storage backends in Docker for local development and testing.

**Prerequisites**:
- Docker running
- Containers created (run `015_create_*` scripts first)

**Ports**:
- Kudu Master: 7051
- Kudu Tablet Server: 7050, 8050
- OpenTSDB: 4242
- HBase: 60010

### 120_run_demo.sh

**Purpose**: Run MacroRecorder2 demo with sample data

**Description**: Launches the TSA Workbench with pre-generated time series data for demonstration.

**Usage**:
```bash
./bin/120_run_demo.sh
```

### 130_run_demo_in_spark_shell_locally.sh

**Purpose**: Start Spark shell with OpenTSx libraries

**Description**: Interactive Spark session with Kudu integration and OpenTSx jars loaded.

**Usage**:
```bash
./bin/130_run_demo_in_spark_shell_locally.sh

# In Spark shell:
scala> import org.opentsx.data.series.TimeSeriesObject
scala> val ts = TimeSeriesObject.getGaussianDistribution(1000, 10, 1)
scala> ts.getMeanY()
```

## Environment Variables

### JAVA_HOME
Location of Java Development Kit

```bash
# macOS (example)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home/

# Linux (example)
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

# Homebrew OpenJDK 21 (macOS)
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home
```

### JAVA_OPTS
Java runtime options (memory, GC, etc.)

```bash
# High memory
export JAVA_OPTS="-Xmx8g -Xms2g"

# Debug mode
export JAVA_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

# Performance tuning
export JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### DISPLAY
X11 display for GUI applications

```bash
# Local display
export DISPLAY=:0

# Remote display (X11 forwarding)
export DISPLAY=localhost:10.0
```

## Common Issues

### Build Failures

**Issue**: Maven build fails with dependency errors

**Solution**:
```bash
# Clear local Maven cache
rm -rf ~/.m2/repository

# Rebuild
./bin/010_build.sh
```

### Docker Container Issues

**Issue**: Container ports already in use

**Solution**:
```bash
# Find and stop conflicting containers
docker ps
docker stop <container-id>

# Or change ports in docker run command
```

### GUI Doesn't Launch

**Issue**: TSA Workbench doesn't appear

**Solution**:
```bash
# Check Java installation
java -version

# Verify JAVA_HOME
echo $JAVA_HOME

# Check X11 display (Linux/macOS)
echo $DISPLAY

# For macOS, install XQuartz if needed
brew install --cask xquartz
```

## Performance Tips

1. **Increase Memory**: For large time series
   ```bash
   JAVA_OPTS="-Xmx8g" ./bin/000_launch_tsa_workbench.sh
   ```

2. **Skip Tests**: Faster builds
   ```bash
   mvn clean install -DskipTests=true
   ```

3. **Parallel Build**: Use multiple cores
   ```bash
   mvn -T 4 clean install  # 4 threads
   ```

4. **Docker Resources**: Allocate more CPU/memory
   ```bash
   # Docker Desktop → Preferences → Resources
   # Increase CPU and Memory limits
   ```

## Integration with Onboarding Paths

### SWE Track Episodes

- **E01**: `010_build.sh`, `000_launch_tsa_workbench.sh`
- **E06**: `130_run_demo_in_spark_shell_locally.sh`
- **E07**: `015_create_kudu_on_docker.sh`, `015_create_opentsdb_on_docker.sh`, `110_run_demo_services.sh`
- **E10**: `020_deploy_to_cc_cluster.sh`

### TSx Track Episodes

- **E01**: `010_build.sh`, `000_launch_tsa_workbench.sh`
- **E03**: `120_run_demo.sh`
- **E04**: `130_run_demo_in_spark_shell_locally.sh`
- **E07**: `110_run_demo_services.sh`

## Additional Resources

- **Main Documentation**: [README.md](../README.md)
- **Onboarding Plan**: [PLAN.md](../PLAN.md)
- **SWE Track**: [ONBOARDING-PATH-SWE.md](../ONBOARDING-PATH-SWE.md)
- **TSx Track**: [ONBOARDING-PATH-TSx.md](../ONBOARDING-PATH-TSx.md)
- **Task Tracking**: [EVOLUTION/](../EVOLUTION/)

## Contributing

When adding new scripts:

1. Follow the naming convention: `<number>_<descriptive_name>.sh`
2. Make scripts executable: `chmod +x script.sh`
3. Add comprehensive header comments
4. Update this README
5. Link to relevant onboarding episodes

---

**Last Updated**: 2025-12-20
**Maintained By**: OpenTSx Core Team
