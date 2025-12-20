# Sample Datasets for OpenTSx Onboarding

This directory contains sample datasets for learning and testing OpenTSx functionality.

## Available Datasets

### 1. sensor_data.csv
**Purpose**: IoT sensor readings from warehouse monitoring
**Format**: CSV with header
**Size**: 30 records
**Time Range**: ~10 minutes (2024-01-01)
**Fields**:
- `timestamp` - Unix timestamp in milliseconds
- `sensor_id` - Sensor identifier (sensor_01, sensor_02, sensor_03)
- `location` - Physical location (warehouse_a, warehouse_b)
- `measurement_type` - Type of measurement (temperature, humidity)
- `value` - Measurement value
- `unit` - Unit of measurement (celsius, percent)

**Use Cases**:
- Episode 2: Loading and basic operations
- Episode 7: Storage backend integration
- Episode 9: Anomaly detection

**Example Usage**:
```java
TimeSeriesObject ts = TimeSeriesObject.loadFromFile(
    new File("data/sample_datasets/sensor_data.csv")
);
```

### 2. stock_prices.tsv
**Purpose**: Financial time series data
**Format**: TSV (tab-separated values) with header
**Size**: 24 records
**Time Range**: 10 days (2024-01-01 to 2024-01-10)
**Fields**:
- `date` - Trading date
- `symbol` - Stock symbol (AAPL, MSFT, GOOGL)
- `open` - Opening price
- `high` - Daily high
- `low` - Daily low
- `close` - Closing price
- `volume` - Trading volume

**Use Cases**:
- Episode 3: Resampling and aggregation
- Episode 9: Financial time series analysis
- TSx Track: Comparison with R/Python analysis

**Example Usage**:
```java
// Load and filter by symbol
TimeSeriesObject aapl = loadStockData("AAPL");
double dailyReturn = calculateReturn(aapl);
```

### 3. weather_data.csv
**Purpose**: Environmental monitoring data
**Format**: CSV with header
**Size**: 26 records
**Time Range**: 13 hours (2024-01-01)
**Fields**:
- `timestamp` - Human-readable timestamp
- `station_id` - Weather station ID
- `location` - Geographic location
- `temperature_c` - Temperature in Celsius
- `humidity_percent` - Relative humidity
- `pressure_hpa` - Atmospheric pressure in hPa
- `wind_speed_kmh` - Wind speed in km/h
- `precipitation_mm` - Precipitation in mm

**Use Cases**:
- Episode 2: Multiple time series comparison
- Episode 3: Filtering and transformation
- Episode 9: Trend analysis and forecasting

**Example Usage**:
```java
// Load and analyze temperature trends
TimeSeriesObject temp = loadWeatherMetric("temperature_c", "WS001");
TimeSeriesObject smoothed = temp.setBinningX_average(3);
```

### 4. network_metrics.csv
**Purpose**: Infrastructure monitoring metrics
**Format**: CSV with header
**Size**: 36 records
**Time Range**: 3 time intervals
**Fields**:
- `timestamp` - Unix timestamp
- `host` - Hostname (web01, db01)
- `metric` - Metric name
- `value` - Metric value

**Metrics Available**:
- CPU usage (%)
- Memory usage (%)
- Disk I/O (read/write in MB/s)
- Network traffic (rx/tx in MB/s)
- Database query rate (QPS)
- Connection count

**Use Cases**:
- Episode 4: Stream processing
- Episode 7: Multi-metric analysis
- Episode 9: Anomaly detection in infrastructure

**Example Usage**:
```java
// Load CPU metrics for all hosts
TimeSeriesObject webCPU = loadMetric("web01", "cpu_percent");
TimeSeriesObject dbCPU = loadMetric("db01", "cpu_percent");
compareHosts(webCPU, dbCPU);
```

## Dataset Characteristics

| Dataset | Records | Sensors/Symbols | Metrics | Frequency | Use For |
|---------|---------|-----------------|---------|-----------|---------|
| sensor_data.csv | 30 | 3 | 2 | 1 min | IoT, anomaly detection |
| stock_prices.tsv | 24 | 3 | 6 | Daily | Financial analysis |
| weather_data.csv | 26 | 2 | 7 | Hourly | Environmental, trends |
| network_metrics.csv | 36 | 2 | Multiple | Varied | Infrastructure monitoring |

## Episode Mapping

### SWE Track
- **E02**: sensor_data.csv, weather_data.csv
- **E03**: All datasets (operations practice)
- **E07**: sensor_data.csv (storage backends)
- **E09**: network_metrics.csv (anomaly detection)

### TSx Track
- **E02**: All datasets (R/Python comparison)
- **E03**: weather_data.csv (visualization)
- **E06**: stock_prices.tsv (custom analytics)
- **E09**: sensor_data.csv (domain-specific application)

## Data Generation

These datasets were created for educational purposes. To generate larger datasets:

```bash
# Generate more sensor data
cd scripts
./generate_sample_data.sh --type sensor --records 10000

# Generate time series with specific patterns
./generate_sample_data.sh --type periodic --period 24 --records 1000
```

## Data Quality Notes

- **No missing values**: All datasets are complete
- **Clean timestamps**: Properly formatted, sequential
- **Realistic ranges**: Values within expected domains
- **Multiple entities**: Each dataset includes multiple sensors/symbols/hosts
- **Temporal patterns**: Some datasets include trends, seasonality

## Common Loading Patterns

### Pattern 1: Load entire CSV
```java
File dataFile = new File("data/sample_datasets/sensor_data.csv");
TimeSeriesObject ts = TimeSeriesObject.loadFromFile(dataFile);
```

### Pattern 2: Load and filter
```java
// Load all data
TimeSeriesObject all = TimeSeriesObject.loadFromFile(dataFile);

// Filter by condition
TimeSeriesObject filtered = all.filterByValue(v -> v > 22.0);
```

### Pattern 3: Load multiple series
```java
List<TimeSeriesObject> series = new ArrayList<>();
for (String symbol : Arrays.asList("AAPL", "MSFT", "GOOGL")) {
    series.add(loadStockData(symbol));
}
```

## Validation

Each dataset has been validated for:
- Format correctness
- Timestamp ordering
- Value ranges
- Data completeness

## Extensions

To create your own datasets:
1. Follow the format of existing datasets
2. Include headers
3. Use consistent timestamps
4. Add metadata in filename or comments
5. Document in this README

## License

Sample datasets are provided for educational use with OpenTSx.
Feel free to modify and extend for your learning purposes.

---

**Last Updated**: 2025-12-20
**Maintained By**: OpenTSx Onboarding Team
