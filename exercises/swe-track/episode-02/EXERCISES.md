# Episode 2 Exercises: Creating Time Series

**Learning Track:** SWE (Software Engineering)
**Difficulty:** Beginner
**Estimated Time:** 45 minutes
**Prerequisites:** Java basics, project built

## Learning Objectives

By completing these exercises, you will:
- Create TimeSeriesObject instances using multiple methods
- Generate synthetic time series data
- Load time series from CSV files
- Save time series to files
- Access and manipulate time series data

## Setup

Before starting, ensure:
1. Project is built: `./bin/010_build.sh`
2. You've reviewed the Episode 2 demo: `./bin/episode_02_create_timeseries.sh`
3. Sample data is available in `sample_data/`

## Exercise 1: Manual Time Series Creation (15 minutes)

**Goal:** Create a TimeSeriesObject manually and populate it with data.

**Task:**
Create a Java program that:
1. Creates an empty TimeSeriesObject with label "manual_series"
2. Adds 20 data points where:
   - X values are: 0, 1, 2, ..., 19
   - Y values follow the formula: `y = 2*x + 5` (linear relationship)
3. Prints the mean and standard deviation
4. Saves the series to "exercise1_output.csv"

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;

public class Exercise1_ManualCreation {
    public static void main(String[] args) {
        // TODO: Create TimeSeriesObject with label "manual_series"


        // TODO: Add 20 data points using addValuePair(x, y)
        // Formula: y = 2*x + 5


        // TODO: Print statistics
        System.out.println("Series Label: " + /* ... */);
        System.out.println("Number of points: " + /* ... */);
        System.out.println("Mean: " + /* ... */);
        System.out.println("Standard Deviation: " + /* ... */);

        // TODO: Save to file

    }
}
```

**Expected Output:**
```
Series Label: manual_series
Number of points: 20
Mean: 24.0
Standard Deviation: 11.83...
Saved to: exercise1_output.csv
```

**Validation:**
- Your CSV file should have 20 rows
- Mean should be approximately 24.0
- File should be readable and properly formatted

---

## Exercise 2: Synthetic Data Generation (10 minutes)

**Goal:** Generate synthetic time series using built-in distribution methods.

**Task:**
Create three different synthetic time series:
1. Gaussian distribution: 500 points, mean=50, stddev=10
2. Uniform distribution: 500 points, min=0, max=100
3. Exponential distribution: 500 points, lambda=0.05

For each series:
- Print the theoretical vs. actual mean
- Print the theoretical vs. actual standard deviation
- Save to separate CSV files

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;
import java.io.File;

public class Exercise2_SyntheticData {
    public static void main(String[] args) {
        // TODO: Create Gaussian distribution
        // Expected mean: 50, Expected stddev: 10


        // TODO: Create Uniform distribution
        // Expected mean: 50, Expected stddev: ~28.87


        // TODO: Create Exponential distribution
        // Expected mean: 1/lambda = 20


        // TODO: For each series, print statistics and save

    }
}
```

**Expected Output:**
```
Gaussian Series:
  Theoretical Mean: 50.0, Actual: 49.87 (close)
  Theoretical StdDev: 10.0, Actual: 9.92 (close)

Uniform Series:
  Theoretical Mean: 50.0, Actual: 50.23 (close)
  Theoretical StdDev: 28.87, Actual: 28.45 (close)

Exponential Series:
  Theoretical Mean: 20.0, Actual: 19.84 (close)
```

---

## Exercise 3: Loading and Transforming CSV Data (20 minutes)

**Goal:** Load time series from CSV files and perform basic transformations.

**Task:**
1. Load the sensor data from `sample_data/sensor_data.csv`
2. Extract only the temperature column (assume it's column 2)
3. Count how many data points exist
4. Find the minimum and maximum temperatures
5. Create a new series with only temperatures above 25°C
6. Save the filtered series to "high_temp_readings.csv"

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.loader.MessreihenLoader;
import java.io.File;

public class Exercise3_LoadAndTransform {
    public static void main(String[] args) throws Exception {
        // TODO: Load sensor_data.csv
        // Hint: Use MessreihenLoader
        MessreihenLoader loader = MessreihenLoader.getLoader();
        loader.delim = ",";


        // TODO: Print basic statistics


        // TODO: Create filtered series (temp > 25)
        TimeSeriesObject filtered = new TimeSeriesObject();
        filtered.setLabel("high_temperatures");

        // Hint: Iterate through original series
        // If value > 25, add to filtered series


        // TODO: Save filtered series


        System.out.println("Original points: " + /* ... */);
        System.out.println("Filtered points (>25°C): " + /* ... */);
    }
}
```

**Expected Behavior:**
- Successfully loads CSV file
- Correctly counts data points
- Filters based on threshold
- Saves filtered data to new file

---

## Bonus Exercise: Pattern Creation (Optional, 15 minutes)

**Goal:** Create a time series with multiple patterns combined.

**Task:**
Create a time series with 1000 points that combines:
1. Linear trend: `trend = 0.1 * t`
2. Seasonal pattern: `seasonal = 10 * sin(2π * t / 50)` (period of 50)
3. Gaussian noise: mean=0, stddev=2

Final value at each point: `y = trend + seasonal + noise`

Save the result and visually inspect it (if you have plotting tools).

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsa.rng.RNGWrapper;
import java.io.File;

public class BonusExercise_PatternCreation {
    public static void main(String[] args) {
        TimeSeriesObject combined = new TimeSeriesObject();
        combined.setLabel("trend_seasonal_noise");

        // TODO: Create combined pattern
        for (int t = 0; t < 1000; t++) {
            double trend = /* ... */;
            double seasonal = /* ... */;
            double noise = RNGWrapper.getStdRandomGaussian(0.0, 2.0);

            double y = /* ... */;
            combined.addValuePair(t, y);
        }

        // Save and print stats
        // ...
    }
}
```

---

## Validation Checklist

After completing all exercises, verify:

- [ ] Exercise 1: CSV file created with 20 points, mean ≈ 24.0
- [ ] Exercise 2: Three CSV files created with expected statistics
- [ ] Exercise 3: Filtered CSV contains only values > 25
- [ ] All programs compile without errors
- [ ] All CSV files are readable and properly formatted
- [ ] Statistics printed match expected values

## Common Pitfalls

1. **Type Casting Required:**
   ```java
   // Wrong:
   double y = ts.yValues.elementAt(i);  // Compiler error

   // Correct:
   double y = (Double)ts.yValues.elementAt(i);
   ```

2. **File Path Issues:**
   - Use relative paths from project root
   - Check that sample_data/ directory exists

3. **API Quirks:**
   - `getAvarage()` has a typo (not `getAverage()`)
   - `yValues.size()` not `getLength()`

## Next Steps

After completing these exercises:
1. Review the solutions in `exercises/solutions/swe-track/episode-02/`
2. Compare your approach with the provided solutions
3. Continue to Episode 3 exercises (Basic Operations)
4. Try the bonus exercise if you have extra time

## Resources

- [Core Concepts Documentation](../../docs/manual/core-concepts/timeseries-object.md)
- [Data Operations Guide](../../docs/manual/data-operations/creating-timeseries.md)
- [API Reference](../../docs/manual/appendix/api-reference.md)

---

**Need Help?**
- Review the Episode 2 demo: `./bin/episode_02_create_timeseries.sh`
- Check the documentation links above
- Look at the solution files (but try on your own first!)
