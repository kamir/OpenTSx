# Episode 3 Exercises: Basic Operations

**Learning Track:** SWE (Software Engineering)
**Difficulty:** Beginner
**Estimated Time:** 60 minutes
**Prerequisites:** Episode 2 completed, Java basics

## Learning Objectives

By completing these exercises, you will:
- Understand in-place vs. immutable operations
- Normalize and standardize time series data
- Apply scaling and transformations
- Combine multiple time series
- Extract subsets and windows
- Iterate through time series efficiently

## Setup

Before starting, ensure:
1. Project is built: `./bin/010_build.sh`
2. You've completed Episode 2 exercises
3. You've reviewed Episode 3 demo: `./bin/episode_03_basic_operations.sh`

## Exercise 1: Normalization and Standardization (15 minutes)

**Goal:** Understand different normalization techniques and when to use them.

**Task:**
1. Load or create a time series with mean≠0 and stddev≠1
2. Create three normalized versions:
   - Version A: Subtract mean (centering)
   - Version B: Z-score normalization (mean=0, stddev=1)
   - Version C: Min-max normalization to range [0, 1]
3. Verify the statistics of each normalized version
4. Save all versions to CSV files

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise1_Normalization {
    public static void main(String[] args) {
        // Create test data (Gaussian with mean=100, stddev=15)
        TimeSeriesObject original = TimeSeriesObject.getGaussianDistribution(500, 100.0, 15.0);

        System.out.println("Original Statistics:");
        System.out.println("  Mean: " + original.getAvarage());
        System.out.println("  StdDev: " + original.getStddev());
        System.out.println();

        // TODO: Version A - Center to mean=0
        // Hint: Use subtractAverage() or normalize()


        // TODO: Version B - Z-score normalization
        // Hint: Use normalizeToStdevIsOne()


        // TODO: Version C - Min-max normalization [0, 1]
        // Hint: Manual implementation needed
        // Formula: (x - min) / (max - min)


        // Print statistics for each version
        // ...
    }
}
```

**Expected Output:**
```
Original: mean=100, stddev=15
Version A (centered): mean≈0, stddev=15
Version B (z-score): mean≈0, stddev≈1
Version C (min-max): min=0, max=1
```

---

## Exercise 2: In-Place vs. Immutable Operations (15 minutes)

**Goal:** Understand the critical difference between operations that modify the original vs. return new objects.

**Task:**
Create a program that demonstrates the difference by:
1. Creating an original time series
2. Applying an in-place operation (like `scaleY_2()`)
3. Showing that the original is modified
4. Creating a fresh copy
5. Applying an immutable operation (like `normalizeToStdevIsOne()`)
6. Showing that the original copy is unchanged

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise2_MutabilityDemo {
    public static void main(String[] args) {
        // Create original series
        TimeSeriesObject original = TimeSeriesObject.getGaussianDistribution(100, 50.0, 10.0);
        double originalMean = original.getAvarage();

        System.out.println("Original mean: " + originalMean);

        // TODO: Test in-place operation
        // 1. Create a copy
        // 2. Apply scaleY_2(2.0) to the copy
        // 3. Check if mean doubled


        // TODO: Test immutable operation
        // 1. Create another copy
        // 2. Store its mean
        // 3. Call normalizeToStdevIsOne()
        // 4. Check if original copy's mean changed


        // Print findings about which methods mutate
    }
}
```

**Key Learning:** Always read documentation to know if a method is in-place or immutable!

---

## Exercise 3: Time Series Arithmetic (20 minutes)

**Goal:** Combine multiple time series using arithmetic operations.

**Task:**
Given three time series:
- Temperature readings (in Celsius)
- Humidity readings (percentage)
- Pressure readings (hPa)

Create:
1. A "comfort index" combining temperature and humidity
2. A difference series showing temperature variation from baseline (20°C)
3. A normalized composite index

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;

public class Exercise3_SeriesArithmetic {
    public static void main(String[] args) {
        int numPoints = 200;

        // Create synthetic sensor data
        TimeSeriesObject temperature = TimeSeriesObject.getGaussianDistribution(
            numPoints, 22.0, 3.0  // Mean 22°C, stddev 3
        );

        TimeSeriesObject humidity = TimeSeriesObject.getUniformDistribution(
            numPoints, 40.0, 80.0  // Range 40-80%
        );

        // TODO: Create comfort index
        // Formula: comfort = temp + (humidity / 10)
        // This is simplified; real comfort index is more complex


        // TODO: Create temperature deviation from baseline
        // Baseline = 20°C
        // deviation = temperature - 20


        // TODO: Create normalized composite
        // 1. Normalize temperature
        // 2. Normalize humidity
        // 3. Average them


        // Print statistics and save results
    }
}
```

---

## Exercise 4: Windowing and Subsetting (20 minutes)

**Goal:** Extract meaningful subsets of time series data.

**Task:**
Given a full day of sensor data (1440 minutes = 24 hours):
1. Extract morning data (6 AM - 12 PM)
2. Extract afternoon data (12 PM - 6 PM)
3. Extract evening data (6 PM - 12 AM)
4. Compare statistics across time windows
5. Find the time window with maximum variance

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsa.rng.RNGWrapper;

public class Exercise4_Windowing {
    public static void main(String[] args) {
        // Create synthetic daily data (1440 points = 1 per minute for 24 hours)
        TimeSeriesObject fullDay = new TimeSeriesObject();
        fullDay.setLabel("daily_temperature");

        RNGWrapper.init(1);  // Reproducibility

        // Add time-varying temperature pattern
        for (int minute = 0; minute < 1440; minute++) {
            double hour = minute / 60.0;

            // Realistic daily temperature pattern
            double baseTemp = 20.0;
            double dailyCycle = 5.0 * Math.sin(2 * Math.PI * (hour - 6) / 24);
            double noise = RNGWrapper.getStdRandomGaussian(0.0, 1.0);

            double temp = baseTemp + dailyCycle + noise;
            fullDay.addValuePair(minute, temp);
        }

        // TODO: Extract time windows
        // Morning: minutes 360-720 (6 AM - 12 PM)
        // Afternoon: minutes 720-1080 (12 PM - 6 PM)
        // Evening: minutes 1080-1440 (6 PM - 12 AM)
        // Hint: Use shrinkX() or manual iteration


        // TODO: Compare statistics


        // TODO: Find window with maximum variance

    }
}
```

**Expected Analysis:**
- Morning: temperatures rising
- Afternoon: peak temperatures
- Evening: temperatures falling

---

## Bonus Exercise: Rolling Statistics (Optional, 20 minutes)

**Goal:** Implement a rolling window statistical analysis.

**Task:**
Create a function that calculates rolling statistics:
1. Rolling mean with window size N
2. Rolling standard deviation with window size N
3. Rolling min/max with window size N

Apply these to a time series and visualize the smoothing effect.

**Starter Code:**
```java
package org.opentsx.exercises.swe;

import org.opentsx.data.series.TimeSeriesObject;

public class BonusExercise_RollingStats {

    /**
     * Calculate rolling mean with specified window size
     */
    public static TimeSeriesObject rollingMean(TimeSeriesObject input, int windowSize) {
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(input.getLabel() + "_rolling_mean");

        // TODO: Implement rolling mean
        // For each position i where i >= windowSize-1:
        //   Calculate mean of points [i-windowSize+1, i]

        return result;
    }

    public static void main(String[] args) {
        // Create noisy data
        TimeSeriesObject noisy = TimeSeriesObject.getGaussianDistribution(1000, 50.0, 10.0);

        // Add a trend
        for (int i = 0; i < noisy.yValues.size(); i++) {
            double trend = 0.05 * i;
            double currentValue = (Double)noisy.yValues.elementAt(i);
            noisy.yValues.setElementAt(currentValue + trend, i);
        }

        // TODO: Apply rolling statistics
        TimeSeriesObject smoothed5 = rollingMean(noisy, 5);
        TimeSeriesObject smoothed20 = rollingMean(noisy, 20);
        TimeSeriesObject smoothed50 = rollingMean(noisy, 50);

        // Compare and save
        // ...
    }
}
```

---

## Validation Checklist

After completing all exercises:

- [ ] Exercise 1: Three normalization methods implemented correctly
- [ ] Exercise 2: Clear demonstration of in-place vs. immutable operations
- [ ] Exercise 3: Arithmetic operations on multiple series working
- [ ] Exercise 4: Windowing extracts correct time ranges
- [ ] All statistics calculated accurately
- [ ] All programs compile without errors
- [ ] Output files generated and readable

## Common Pitfalls

1. **Forgetting to Copy Before In-Place Operations:**
   ```java
   // Wrong - modifies original!
   original.scaleY_2(2.0);

   // Correct - work on copy
   TimeSeriesObject scaled = original.copy();
   scaled.scaleY_2(2.0);
   ```

2. **Mixing Series with Different Lengths:**
   - Always check `ts.yValues.size()` before combining
   - Align series to same length if needed

3. **Index Out of Bounds in Windowing:**
   - Check boundaries: `i >= windowStart && i < windowEnd`

4. **Type Casting:**
   - Remember: `(Double)ts.yValues.elementAt(i)`

## Next Steps

After completing these exercises:
1. Review solutions in `exercises/solutions/swe-track/episode-03/`
2. Compare your implementation approaches
3. Try the bonus exercise for advanced practice
4. Continue to Episode 9 (TSx Track) or Episode 10 (Production Config)

## Resources

- [Data Operations Guide](../../docs/manual/data-operations/transformations.md)
- [Best Practices](../../docs/manual/best-practices/README.md)
- [API Reference](../../docs/manual/appendix/api-reference.md)

---

**Need Help?**
- Review Episode 3 demo: `./bin/episode_03_basic_operations.sh`
- Check method documentation in source code
- Look at solution files (but try independently first!)
