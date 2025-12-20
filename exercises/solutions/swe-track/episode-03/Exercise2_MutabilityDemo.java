package org.opentsx.exercises.swe.solutions;

import org.opentsx.data.series.TimeSeriesObject;

/**
 * Solution for Exercise 2: In-Place vs. Immutable Operations
 *
 * Critical demonstration of how some methods modify the original object
 * while others return new objects, leaving the original unchanged.
 */
public class Exercise2_MutabilityDemo {

    public static void main(String[] args) {
        System.out.println("=== Demonstrating In-Place vs. Immutable Operations ===\n");

        // Create original series
        TimeSeriesObject original = TimeSeriesObject.getGaussianDistribution(100, 50.0, 10.0);
        double originalMean = original.getAvarage();

        System.out.println("Original series created:");
        System.out.println("  Mean: " + String.format("%.2f", originalMean));
        System.out.println("  StdDev: " + String.format("%.2f", original.getStddev()));
        System.out.println();

        // Test 1: In-place operation (scaleY_2)
        System.out.println("=== Test 1: In-Place Operation (scaleY_2) ===");
        TimeSeriesObject copy1 = original.copy();
        double meanBefore = copy1.getAvarage();

        System.out.println("Before scaleY_2(2.0):");
        System.out.println("  Mean: " + String.format("%.2f", meanBefore));

        copy1.scaleY_2(2.0);  // IN-PLACE modification

        double meanAfter = copy1.getAvarage();
        System.out.println("After scaleY_2(2.0):");
        System.out.println("  Mean: " + String.format("%.2f", meanAfter));
        System.out.println("  Expected: " + String.format("%.2f", meanBefore * 2.0));
        System.out.println("  ✓ Series WAS MODIFIED (in-place operation)");
        System.out.println();

        // Test 2: Immutable operation (normalizeToStdevIsOne)
        System.out.println("=== Test 2: Immutable Operation (normalizeToStdevIsOne) ===");
        TimeSeriesObject copy2 = original.copy();
        double originalCopy2Mean = copy2.getAvarage();

        System.out.println("Before normalizeToStdevIsOne():");
        System.out.println("  Mean: " + String.format("%.2f", originalCopy2Mean));

        TimeSeriesObject normalized = copy2.normalizeToStdevIsOne();  // Returns NEW object

        System.out.println("After normalizeToStdevIsOne():");
        System.out.println("  Original copy mean: " + String.format("%.2f", copy2.getAvarage()));
        System.out.println("  Normalized result mean: " + String.format("%.2f", normalized.getAvarage()));
        System.out.println("  ✓ Original copy UNCHANGED (immutable operation)");
        System.out.println();

        // Test 3: Another in-place operation (add_to_Y)
        System.out.println("=== Test 3: In-Place Operation (add_to_Y) ===");
        TimeSeriesObject copy3 = original.copy();
        double offset = 25.0;
        double meanBeforeOffset = copy3.getAvarage();

        System.out.println("Before add_to_Y(" + offset + "):");
        System.out.println("  Mean: " + String.format("%.2f", meanBeforeOffset));

        copy3.add_to_Y(offset);  // IN-PLACE modification

        System.out.println("After add_to_Y(" + offset + "):");
        System.out.println("  Mean: " + String.format("%.2f", copy3.getAvarage()));
        System.out.println("  Expected: " + String.format("%.2f", meanBeforeOffset + offset));
        System.out.println("  ✓ Series WAS MODIFIED (in-place operation)");
        System.out.println();

        // Summary
        System.out.println("=== Summary ===");
        System.out.println("In-Place Methods (modify original):");
        System.out.println("  - scaleY_2()");
        System.out.println("  - add_to_Y()");
        System.out.println("  - divide_Y_by()");
        System.out.println("  - normalize()");
        System.out.println();
        System.out.println("Immutable Methods (return new object):");
        System.out.println("  - normalizeToStdevIsOne()");
        System.out.println("  - subtractAverage()");
        System.out.println("  - copy()");
        System.out.println("  - shrinkX()");
        System.out.println();
        System.out.println("⚠️  ALWAYS check documentation before using a method!");
        System.out.println("⚠️  Use copy() before in-place operations to preserve original!");
    }
}
