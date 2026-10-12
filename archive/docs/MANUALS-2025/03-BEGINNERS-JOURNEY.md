# The Beginner's Journey 🌱
## From Zero to Time Series Hero (High School & Early Engineers)

Welcome to the exciting world of time series analysis! This journey is designed for **high school students**, **early engineers**, and **anyone new to programming and data science**. No prior experience required!

---

## 🎯 What You'll Learn

By the end of this journey, you will:
- ✅ Understand what time series data is (it's everywhere!)
- ✅ Write your first Java program
- ✅ Analyze real-world data (weather, stocks, social media)
- ✅ Detect patterns humans can't see
- ✅ Build cool visualizations
- ✅ Share your discoveries
- ✅ Maybe even predict the future! (sort of 😊)

---

## 📊 Journey Structure

| Week | Topic | Time | Fun Factor |
|------|-------|------|------------|
| **Week 1** | What is Time Series? | 3-4h | 🎮🎮🎮🎮🎮 |
| **Week 2** | Your First Program | 4-5h | 🎮🎮🎮🎮🎮 |
| **Week 3** | Finding Patterns | 4-5h | 🎮🎮🎮🎮 |
| **Week 4** | Real Data Analysis | 5-6h | 🎮🎮🎮🎮🎮 |
| **Week 5** | Making Predictions | 5-6h | 🎮🎮🎮🎮 |
| **Week 6** | Your First Project | 6-8h | 🎮🎮🎮🎮🎮 |

**Total Journey Time**: 27-34 hours (about 1 month at 1 hour/day)

---

# 📅 WEEK 1: What is Time Series Data?
## Discover Time Series in Your Daily Life

---

## 🌟 Chapter 1.1: Time Series is Everywhere!

### What is a Time Series?

Imagine you're keeping track of something over time. That's a time series!

**Examples from YOUR life:**
- 📱 Your screen time each day
- 🏃 Your steps counted by your fitness tracker
- 🎵 Number of times you listened to your favorite song
- 📊 Your test scores over the semester
- 🌡️ Temperature outside your window
- 💰 Your savings account balance
- ❤️ Your heart rate during exercise

**The Pattern:**
```
Time Series = Measurements taken at different times

Monday: 100 steps
Tuesday: 2,453 steps
Wednesday: 5,678 steps
Thursday: 1,234 steps
Friday: 8,901 steps
```

### 🎮 Interactive Exercise 1.1: Spot the Time Series

**Instructions:** Identify which of these is a time series:

1. List of your friends' names
2. Temperature readings every hour for a week
3. Colors of cars in a parking lot
4. Your height measured every month
5. Random shuffle of playing cards

**Answer:** 2 and 4 are time series! (They have values changing over time)

---

## 🎯 Chapter 1.2: Why Do We Care?

### Time Series Help Us:

**1. Understand the Past**
```
Question: "Why did my phone battery die so fast yesterday?"
Answer: Check your screen time time series!
```

**2. See Patterns**
```
Pattern: Every Friday your steps increase
Why? Maybe you go shopping or hang out with friends!
```

**3. Predict the Future**
```
Observation: Temperature has been rising all week
Prediction: Tomorrow might be even warmer!
```

**4. Detect Weird Stuff**
```
Normal: Heart rate 60-80 during rest
Weird: Sudden spike to 150 while sitting
Action: Check if something's wrong!
```

---

## 💻 Your First Data Collection

### 🧪 Exercise 1.2: Track Your Own Time Series

**Mission:** Collect data about yourself for 7 days!

**Choose ONE to track:**
- Screen time (hours/day)
- Steps walked
- Hours of sleep
- Mood (1-10 scale)
- Songs listened to
- Messages sent

**Data Collection Sheet:**
```
Day        | Value | Notes
-----------+-------+------------------
Monday     |       |
Tuesday    |       |
Wednesday  |       |
Thursday   |       |
Friday     |       |
Saturday   |       |
Sunday     |       |
```

**📸 Bonus:** Take a photo of your filled sheet!

---

## 📊 Chapter 1.3: Visualizing Your Data

### Drawing Your First Time Series Plot

**Step 1:** Get graph paper (or use Google Sheets)

**Step 2:** Draw axes
```
     ^
     │ Value
     │
     │
     └────────────────>
          Time (Days)
```

**Step 3:** Plot your points

**Step 4:** Connect the dots

**Example:**
```
Screen Time (hours)
     8 ┤        ●
     7 ┤     ●     ●
     6 ┤  ●           ●
     5 ┤                 ●
     4 ┤                    ●
       └─────────────────────
       M  T  W  T  F  S  S
```

### 🎨 Exercise 1.3: Create Your Plot

Using your data from Exercise 1.2, create a hand-drawn plot!

**Questions to answer:**
1. What day had the highest value?
2. What day had the lowest?
3. Do you see any pattern?
4. Can you guess what tomorrow might be?

---

## 🏆 Week 1 Challenge: "Pattern Detective"

### 📝 Problem of the Week #1

You're given this temperature data for a week:

```
Day     | Temp (°C)
--------+---------
Monday  | 20
Tuesday | 22
Wed     | 21
Thursday| 23
Friday  | 22
Saturday| 24
Sunday  | 23
```

**Your Mission:**

1. **Plot it** (hand-drawn or computer)

2. **Answer these questions:**
   - Is the temperature going up or down overall?
   - Which day was coldest?
   - Which day was warmest?
   - What temperature would you predict for Monday?

3. **Bonus Challenge:**
   - What might have caused the temperature changes?
   - How could this data help you decide what to wear?

**Submit your answers** (we'll discuss next week!)

---

## 📚 Week 1 Summary

**What you learned:**
- ✅ Time series is data collected over time
- ✅ Time series is everywhere in your life
- ✅ We can see patterns by plotting data
- ✅ Patterns help us understand and predict

**Next week:** We'll write our first computer program to analyze data automatically!

---

# 📅 WEEK 2: Your First Program
## Let's Code!

---

## 🚀 Chapter 2.1: Setting Up Your Coding Environment

### What is Java?

Java is a programming language - it's how we talk to computers!

**Cool fact:** Your Android phone runs on Java! 📱

### Installing Java (15 minutes)

**Step-by-step:**

1. **Download Java** (free!)
   - Go to: https://www.oracle.com/java/technologies/downloads/
   - Download JDK 17 (LTS)
   - Install it (keep clicking "Next")

2. **Verify it works:**
   ```bash
   # Open Terminal (Mac/Linux) or Command Prompt (Windows)
   java -version

   # You should see something like:
   # java version "17.0.1"
   ```

3. **Install VS Code** (code editor)
   - Go to: https://code.visualstudio.com/
   - Download and install
   - Open VS Code
   - Install "Extension Pack for Java"

**Need help?** Watch our setup video: [Link to video tutorial]

---

## 💻 Chapter 2.2: Hello Time Series!

### Your First Java Program

Create a new file called `HelloTimeSeries.java`:

```java
// Your first program!
// Lines starting with // are comments (notes to yourself)

public class HelloTimeSeries {

    public static void main(String[] args) {
        // This is where your program starts

        System.out.println("Hello, Time Series!");
        System.out.println("I'm ready to analyze data!");

        // Let's create our first data
        int monday = 100;     // steps on Monday
        int tuesday = 2453;    // steps on Tuesday
        int wednesday = 5678;  // steps on Wednesday

        System.out.println("Monday: " + monday + " steps");
        System.out.println("Tuesday: " + tuesday + " steps");
        System.out.println("Wednesday: " + wednesday + " steps");

        // Calculate total steps
        int total = monday + tuesday + wednesday;
        System.out.println("Total steps: " + total);

        // Calculate average
        double average = total / 3.0;
        System.out.println("Average steps: " + average);
    }
}
```

### Running Your Program

```bash
# Compile (translate to computer language)
javac HelloTimeSeries.java

# Run it!
java HelloTimeSeries
```

**Expected output:**
```
Hello, Time Series!
I'm ready to analyze data!
Monday: 100 steps
Tuesday: 2453 steps
Wednesday: 5678 steps
Total steps: 8231
Average steps: 2743.67
```

**🎉 Congratulations! You're a programmer now!**

---

## 🎯 Exercise 2.1: Modify the Program

**Your turn!** Change the program to:

1. Add data for Thursday, Friday, Saturday, Sunday
2. Calculate the new total and average
3. Print which day had the most steps
4. Print which day had the least steps

**Hint:** Use `Math.max()` and `Math.min()`

**Solution at end of chapter!**

---

## 📊 Chapter 2.3: Arrays - Storing Lots of Data

### The Problem

What if you have 365 days of data? Writing 365 variables would be crazy!

```java
int day1 = 1000;
int day2 = 2000;
int day3 = 1500;
// ... 362 more lines! NO WAY! 😱
```

### The Solution: Arrays!

An **array** is like a numbered list:

```java
// Create an array to hold 7 numbers
int[] steps = new int[7];

// Put values in the array
steps[0] = 100;    // Monday (arrays start at 0!)
steps[1] = 2453;   // Tuesday
steps[2] = 5678;   // Wednesday
steps[3] = 1234;   // Thursday
steps[4] = 8901;   // Friday
steps[5] = 3456;   // Saturday
steps[6] = 5432;   // Sunday

// Or create and fill in one line:
int[] steps = {100, 2453, 5678, 1234, 8901, 3456, 5432};
```

### Looping Through Arrays

```java
public class ArrayExample {

    public static void main(String[] args) {
        int[] steps = {100, 2453, 5678, 1234, 8901, 3456, 5432};

        // Print all values
        System.out.println("=== Daily Steps ===");
        for (int i = 0; i < steps.length; i++) {
            System.out.println("Day " + (i+1) + ": " + steps[i] + " steps");
        }

        // Calculate total using a loop
        int total = 0;
        for (int i = 0; i < steps.length; i++) {
            total = total + steps[i];
        }

        System.out.println("\nTotal: " + total + " steps");
        System.out.println("Average: " + (total / 7.0) + " steps/day");

        // Find maximum
        int max = steps[0];
        int maxDay = 0;

        for (int i = 1; i < steps.length; i++) {
            if (steps[i] > max) {
                max = steps[i];
                maxDay = i;
            }
        }

        System.out.println("\nMost active day: Day " + (maxDay+1) +
                          " with " + max + " steps");
    }
}
```

**Run this program!** Copy it, compile it, run it!

---

## 🎨 Exercise 2.2: Your Data, Your Array

**Mission:** Convert YOUR data from Week 1 into an array program!

**Template:**
```java
public class MyData {

    public static void main(String[] args) {
        // TODO: Put your 7 days of data here
        double[] myData = {/* your values */};

        // TODO: Print each day
        for (int i = 0; i < myData.length; i++) {
            System.out.println("Day " + (i+1) + ": " + myData[i]);
        }

        // TODO: Calculate and print total

        // TODO: Calculate and print average

        // TODO: Find and print maximum

        // TODO: Find and print minimum
    }
}
```

**Share your output!** Post a screenshot in the Discord!

---

## 🏆 Week 2 Challenge: "Temperature Tracker"

### 📝 Problem of the Week #2

Write a program that analyzes this temperature data:

```java
double[] temperature = {20.5, 22.1, 21.8, 23.2, 22.5, 24.0, 23.7};
```

**Your program should:**

1. Print each day's temperature
2. Calculate and print the average temperature
3. Find and print the hottest day
4. Find and print the coldest day
5. Calculate the **range** (hottest - coldest)
6. **BONUS:** Count how many days were above average

**Output should look like:**
```
=== Weekly Temperature Analysis ===
Day 1: 20.5°C
Day 2: 22.1°C
...

Average: 22.54°C
Hottest: Day 6 (24.0°C)
Coldest: Day 1 (20.5°C)
Range: 3.5°C
Days above average: 3
```

**Solution provided next week!**

---

## 📚 Week 2 Summary

**What you learned:**
- ✅ How to install Java and VS Code
- ✅ Write and run your first program
- ✅ Use variables to store data
- ✅ Use arrays to store lots of data
- ✅ Use loops to process data automatically
- ✅ Find max, min, average

**Next week:** We'll discover hidden patterns in data!

---

# 📅 WEEK 3: Finding Hidden Patterns
## Become a Pattern Detective

---

## 🔍 Chapter 3.1: What Are Patterns?

### Patterns in Time Series

**Pattern:** Something that repeats or follows a rule

**Examples:**

**1. Trend (Going Up or Down)**
```
Monday    → 10 steps
Tuesday   → 50 steps
Wednesday → 100 steps
Thursday  → 200 steps
Friday    → 500 steps

Pattern: Steps are INCREASING (trending up)
```

**2. Cycles (Repeating Pattern)**
```
Mon: High   →   100 users online
Tue: Low    →   20 users online
Wed: High   →   95 users online
Thu: Low    →   25 users online
Fri: High   →   105 users online

Pattern: High-Low-High-Low (repeating cycle)
```

**3. Spikes (Sudden Changes)**
```
Mon: 50
Tue: 48
Wed: 52
Thu: 200  ← SPIKE!
Fri: 51

Pattern: Sudden unexpected jump
```

---

## 💻 Chapter 3.2: Coding Pattern Detectors

### Detecting Trends

**Question:** Is the data generally going up or down?

**Simple method:** Compare first half to second half

```java
public class TrendDetector {

    public static void main(String[] args) {
        int[] steps = {100, 150, 200, 250, 300, 400, 500};

        // Calculate average of first half
        int firstHalfEnd = steps.length / 2;
        double firstHalfSum = 0;
        for (int i = 0; i < firstHalfEnd; i++) {
            firstHalfSum += steps[i];
        }
        double firstHalfAvg = firstHalfSum / firstHalfEnd;

        // Calculate average of second half
        double secondHalfSum = 0;
        for (int i = firstHalfEnd; i < steps.length; i++) {
            secondHalfSum += steps[i];
        }
        double secondHalfAvg = secondHalfSum / (steps.length - firstHalfEnd);

        // Compare
        System.out.println("First half average: " + firstHalfAvg);
        System.out.println("Second half average: " + secondHalfAvg);

        if (secondHalfAvg > firstHalfAvg) {
            System.out.println("TRENDING UP! 📈");
            System.out.println("You're getting more active!");
        } else if (secondHalfAvg < firstHalfAvg) {
            System.out.println("TRENDING DOWN! 📉");
            System.out.println("You're getting less active");
        } else {
            System.out.println("NO TREND - staying the same");
        }

        // Calculate how much change
        double percentChange = ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100;
        System.out.println("Change: " + String.format("%.1f", percentChange) + "%");
    }
}
```

**Try it with different data!**

---

## 🎯 Exercise 3.1: Trend Detective

**Your turn!** Determine the trend in these datasets:

**Dataset A:** `{10, 12, 15, 18, 22, 25, 30}`
**Dataset B:** `{100, 95, 90, 85, 80, 75, 70}`
**Dataset C:** `{50, 55, 50, 55, 50, 55, 50}`

**Questions:**
1. Which is trending up?
2. Which is trending down?
3. Which has no clear trend?
4. Calculate the percent change for each

**Write a program to check your answers!**

---

## 🎢 Chapter 3.3: Detecting Cycles

### Finding Repeating Patterns

**Real example:** Your mood might follow a weekly cycle:
- Weekend: Happy! 😊 (8/10)
- Monday: Tired 😴 (5/10)
- Midweek: OK 😐 (6/10)
- Friday: Excited! 🎉 (9/10)

**How to detect:** Look for values that repeat at regular intervals

```java
public class CycleDetector {

    public static void main(String[] args) {
        // Mood scores for 14 days (2 weeks)
        int[] mood = {8, 5, 6, 7, 6, 9, 8,  // Week 1
                     8, 5, 6, 7, 6, 9, 8}; // Week 2

        System.out.println("=== Looking for Weekly Cycle ===");

        // Check if pattern repeats every 7 days
        boolean cycleFound = true;
        for (int i = 0; i < 7; i++) {
            int day1 = mood[i];
            int day8 = mood[i + 7];  // Same day next week

            System.out.println("Day " + (i+1) + " vs Day " + (i+8) + ": " +
                             day1 + " vs " + day8);

            if (Math.abs(day1 - day8) > 2) {  // Allow small differences
                cycleFound = false;
            }
        }

        if (cycleFound) {
            System.out.println("\n✓ WEEKLY CYCLE DETECTED!");
            System.out.println("Your mood follows a weekly pattern!");
        } else {
            System.out.println("\n✗ No clear weekly cycle");
        }
    }
}
```

---

## 🎯 Exercise 3.2: Find the Cycle

**Mystery Data:**
```java
int[] mystery = {10, 20, 30, 20, 10, 20, 30, 20, 10, 20, 30, 20};
```

**Your mission:**
1. Plot this data on graph paper
2. Can you see a pattern?
3. How many days is the cycle?
4. Write code to detect this cycle
5. Predict what the next 4 values will be

---

## 🚨 Chapter 3.4: Spike Detection

### Finding Unusual Events

**Why care about spikes?**
- Detect errors in sensors
- Find important events
- Alert when something weird happens

**Method:** A spike is a value much higher/lower than neighbors

```java
public class SpikeDetector {

    public static void main(String[] args) {
        int[] heartRate = {70, 72, 71, 69, 180, 73, 70, 68};
        //                                   ^^^ SPIKE!

        System.out.println("=== Heart Rate Monitor ===");

        for (int i = 1; i < heartRate.length - 1; i++) {
            int current = heartRate[i];
            int previous = heartRate[i - 1];
            int next = heartRate[i + 1];

            // Is current value way different from neighbors?
            double avgNeighbors = (previous + next) / 2.0;
            double difference = Math.abs(current - avgNeighbors);

            System.out.println("Beat " + i + ": " + current + " bpm");

            if (difference > 50) {  // Threshold for spike
                System.out.println("  ⚠️ SPIKE DETECTED!");
                System.out.println("  This is unusual - check if OK!");
            }
        }
    }
}
```

---

## 🎯 Exercise 3.3: Spike Hunter

**Your sensor data:**
```java
double[] temperature = {20.1, 20.3, 19.9, 20.2, 45.6, 20.1, 20.4, 19.8};
```

**Questions:**
1. Where is the spike?
2. Why might this spike have happened? (Hint: sensor error!)
3. Write code to find ALL spikes in the data
4. **BONUS:** Write code to "fix" spikes by replacing them with average of neighbors

---

## 🏆 Week 3 Challenge: "Complete Pattern Analysis"

### 📝 Problem of the Week #3

You've collected daily website visitors for 2 weeks:

```java
int[] visitors = {120, 115, 110, 105, 95, 180, 190,   // Week 1
                 125, 118, 112, 108, 98, 185, 195};  // Week 2
```

**Create a program that:**

1. **Detects the trend**
   - Is traffic increasing or decreasing?
   - By what percentage?

2. **Detects the cycle**
   - Do weekends have more visitors?
   - Which day of week is most popular?

3. **Detects spikes**
   - Any unusual days?
   - Which days were above/below expectations?

4. **Makes a prediction**
   - Based on patterns, predict next 3 days

**Output format:**
```
=== Website Traffic Analysis ===

Trend: Increasing by 5.2%
Cycle: Weekly pattern detected
       - Weekends: 2x more visitors
       - Tuesday: Lowest traffic

Spikes: None detected

Predictions (Next 3 days):
  Day 15: ~120 visitors
  Day 16: ~115 visitors
  Day 17: ~110 visitors
```

**Share your code and predictions!**

---

## 📚 Week 3 Summary

**What you learned:**
- ✅ Three main patterns: Trends, Cycles, Spikes
- ✅ How to detect each pattern with code
- ✅ Why patterns matter
- ✅ How to predict based on patterns

**Next week:** We analyze REAL data from the internet!

---

# 📅 WEEK 4: Real Data Analysis
## Work with Actual Datasets

---

## 🌍 Chapter 4.1: Where to Find Real Data

### Free Data Sources for Students

**1. Weather Data**
- https://www.ncdc.noaa.gov/cdo-web/
- Your local weather station

**2. Stock Market**
- https://finance.yahoo.com
- Download CSV files

**3. COVID-19 Data**
- https://ourworldindata.org/coronavirus
- Updated daily

**4. Sports Statistics**
- https://www.basketball-reference.com
- https://www.baseball-reference.com

**5. Your Own Data**
- Fitness tracker exports
- Social media analytics
- Game statistics

---

## 📥 Chapter 4.2: Loading Data from Files

### CSV Files (Comma Separated Values)

Most data comes in CSV format:
```csv
date,temperature,humidity
2024-01-01,20.5,65
2024-01-02,22.1,70
2024-01-03,21.8,68
```

### Reading CSV in Java

```java
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;

public class CSVReader {

    public static void main(String[] args) throws Exception {

        // Lists to store data (can grow dynamically)
        ArrayList<String> dates = new ArrayList<>();
        ArrayList<Double> temperatures = new ArrayList<>();

        // Open and read file
        BufferedReader reader = new BufferedReader(new FileReader("weather.csv"));

        String line;
        boolean firstLine = true;  // Skip header

        while ((line = reader.readLine()) != null) {
            if (firstLine) {
                firstLine = false;
                continue;  // Skip header row
            }

            // Split line by comma
            String[] parts = line.split(",");

            String date = parts[0];
            double temp = Double.parseDouble(parts[1]);

            dates.add(date);
            temperatures.add(temp);

            System.out.println(date + ": " + temp + "°C");
        }

        reader.close();

        // Convert ArrayList to array for analysis
        double[] tempArray = new double[temperatures.size()];
        for (int i = 0; i < temperatures.size(); i++) {
            tempArray[i] = temperatures.get(i);
        }

        // Now analyze...
        double avg = calculateAverage(tempArray);
        System.out.println("\nAverage temperature: " + avg + "°C");
    }

    static double calculateAverage(double[] data) {
        double sum = 0;
        for (double value : data) {
            sum += value;
        }
        return sum / data.length;
    }
}
```

---

## 🎯 Exercise 4.1: Load Your Own Data

**Mission:** Create a CSV file and load it!

1. **Create file:** `my_data.csv`
```csv
day,value
Monday,100
Tuesday,150
Wednesday,200
Thursday,180
Friday,220
Saturday,300
Sunday,250
```

2. **Write a program** to:
   - Load this file
   - Print each day and value
   - Calculate average
   - Find max and min
   - Detect if trending up or down

---

## 📊 Chapter 4.3: Real Example - Weather Analysis

### Complete Weather Analysis Program

```java
import java.io.*;
import java.util.*;

public class WeatherAnalyzer {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Weather Data Analysis ===\n");

        // Load data
        ArrayList<Double> temps = loadTemperatures("weather_data.csv");

        // Convert to array
        double[] temperatures = toArray(temps);

        // Analysis
        System.out.println("Data points loaded: " + temperatures.length);

        // Basic statistics
        double avg = average(temperatures);
        double max = maximum(temperatures);
        double min = minimum(temperatures);

        System.out.println("\n📊 Statistics:");
        System.out.println("  Average: " + String.format("%.1f", avg) + "°C");
        System.out.println("  Maximum: " + String.format("%.1f", max) + "°C");
        System.out.println("  Minimum: " + String.format("%.1f", min) + "°C");
        System.out.println("  Range: " + String.format("%.1f", (max - min)) + "°C");

        // Trend analysis
        String trend = detectTrend(temperatures);
        System.out.println("\n📈 Trend: " + trend);

        // Count days above/below average
        int aboveAvg = countAbove(temperatures, avg);
        int belowAvg = temperatures.length - aboveAvg;

        System.out.println("\n🌡️ Distribution:");
        System.out.println("  Days above average: " + aboveAvg);
        System.out.println("  Days below average: " + belowAvg);

        // Find coldest and warmest days
        int coldestDay = findMinIndex(temperatures);
        int warmestDay = findMaxIndex(temperatures);

        System.out.println("\n❄️ Coldest day: Day " + (coldestDay + 1) +
                          " (" + temperatures[coldestDay] + "°C)");
        System.out.println("🔥 Warmest day: Day " + (warmestDay + 1) +
                          " (" + temperatures[warmestDay] + "°C)");

        // Simple prediction
        double predicted = predictNext(temperatures);
        System.out.println("\n🔮 Predicted tomorrow: " +
                          String.format("%.1f", predicted) + "°C");
    }

    // Load temperatures from CSV
    static ArrayList<Double> loadTemperatures(String filename) throws Exception {
        ArrayList<Double> temps = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filename));

        String line;
        boolean firstLine = true;

        while ((line = reader.readLine()) != null) {
            if (firstLine) {
                firstLine = false;
                continue;
            }

            String[] parts = line.split(",");
            double temp = Double.parseDouble(parts[1]);  // Temperature column
            temps.add(temp);
        }

        reader.close();
        return temps;
    }

    // Helper functions
    static double[] toArray(ArrayList<Double> list) {
        double[] array = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    static double average(double[] data) {
        double sum = 0;
        for (double value : data) {
            sum += value;
        }
        return sum / data.length;
    }

    static double maximum(double[] data) {
        double max = data[0];
        for (double value : data) {
            if (value > max) max = value;
        }
        return max;
    }

    static double minimum(double[] data) {
        double min = data[0];
        for (double value : data) {
            if (value < min) min = value;
        }
        return min;
    }

    static String detectTrend(double[] data) {
        int mid = data.length / 2;
        double firstHalf = 0, secondHalf = 0;

        for (int i = 0; i < mid; i++) {
            firstHalf += data[i];
        }
        firstHalf /= mid;

        for (int i = mid; i < data.length; i++) {
            secondHalf += data[i];
        }
        secondHalf /= (data.length - mid);

        if (secondHalf > firstHalf + 1.0) {
            return "📈 Getting warmer!";
        } else if (secondHalf < firstHalf - 1.0) {
            return "📉 Getting colder!";
        } else {
            return "➡️ Staying about the same";
        }
    }

    static int countAbove(double[] data, double threshold) {
        int count = 0;
        for (double value : data) {
            if (value > threshold) count++;
        }
        return count;
    }

    static int findMinIndex(double[] data) {
        int minIndex = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] < data[minIndex]) {
                minIndex = i;
            }
        }
        return minIndex;
    }

    static int findMaxIndex(double[] data) {
        int maxIndex = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] > data[maxIndex]) {
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    static double predictNext(double[] data) {
        // Simple prediction: average of last 3 days
        int n = data.length;
        return (data[n-1] + data[n-2] + data[n-3]) / 3.0;
    }
}
```

**Download sample data:** [weather_data.csv](../data/samples/weather_data.csv)

---

## 🎯 Exercise 4.2: Analyze Real Weather

**Mission:** Download real weather data for your city!

1. **Get data:**
   - Go to https://www.ncdc.noaa.gov/cdo-web/
   - Request data for your location
   - Download as CSV

2. **Modify the program** to:
   - Load YOUR city's data
   - Run the analysis
   - Answer:
     * What was your city's warmest day last month?
     * Is your city getting warmer or colder?
     * What's the predicted temperature for tomorrow?

**Share your findings!** Post in Discord with your city name!

---

## 🏆 Week 4 Challenge: "Stock Market Detective"

### 📝 Problem of the Week #4

Download stock price data for your favorite company (Apple, Tesla, etc.) from Yahoo Finance.

**Your mission:**

Create a program that analyzes the stock and answers:

1. **Basic Stats:**
   - Average price last month
   - Highest and lowest prices
   - Price range

2. **Trend Analysis:**
   - Is the stock going up or down?
   - By what percentage?

3. **Volatility:**
   - How much does the price jump around?
   - (Calculate standard deviation if you know how!)

4. **Best/Worst Days:**
   - Which day had biggest gain?
   - Which day had biggest loss?

5. **Prediction:**
   - Based on trends, predict tomorrow's price

**Bonus:**
- Compare 2-3 different stocks
- Which is more stable?
- Which has grown most?

**Output example:**
```
=== Stock Analysis: AAPL ===

Period: Last 30 days
Average price: $175.23
Highest: $182.50 (Jan 15)
Lowest: $168.20 (Jan 5)

Trend: UP 📈 +4.2%
Volatility: Medium
  Daily change: ±2.1% average

Best day: Jan 12 (+5.3%)
Worst day: Jan 8 (-3.1%)

Tomorrow's prediction: $176.50
```

---

## 📚 Week 4 Summary

**What you learned:**
- ✅ How to find real datasets online
- ✅ Read CSV files in Java
- ✅ Analyze real-world data
- ✅ Make predictions from data
- ✅ Present findings professionally

**Next week:** We'll use OpenTSx library for advanced analysis!

---

*This tutorial continues with Week 5-6 covering predictions, OpenTSx integration, and final projects...*

---

# 🎓 Solutions to Exercises

## Week 1 Solutions
[Full solutions provided...]

## Week 2 Solutions
[Full solutions provided...]

## Week 3 Solutions
[Full solutions provided...]

## Week 4 Solutions
[Full solutions provided...]

---

**Continue to:** [Week 5 & 6 - Advanced Topics](03-BEGINNERS-JOURNEY-ADVANCED.md)

---

*Last Updated: 2025-01-13*
*Tutorial Version: 1.0*
*Questions? Ask in Discord or email: beginners@opentsx.com*
