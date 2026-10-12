# TSx Track Polyglot Notebooks - Week 1 POC Plan

**Start Date:** 2025-12-21
**End Date:** 2025-12-28 (7 days)
**Goal:** Validate Polyglot Notebooks approach with Episode 1 POC
**Budget:** $3,000 (Week 1 only)
**Go/No-Go Decision:** Day 5 (December 26, 2025)

---

## 🎯 Week 1 Objectives

### Primary Goal
Create a working Episode 1 notebook that demonstrates:
- ✅ R code examples work
- ✅ Python code examples work
- ✅ Java/OpenTSx code examples work
- ✅ Side-by-side comparison is clear and helpful
- ✅ Users can run it successfully

### Success Criteria
- [ ] All three kernels (R, Python, Java) functional
- [ ] Episode 1 content converted to notebook format
- [ ] 3+ users can run notebook without errors
- [ ] User feedback: "This is better than pure Java" (avg > 3.5/5)
- [ ] Technical feasibility confirmed

### Failure Criteria (No-Go Triggers)
- ❌ Cannot get all three kernels working
- ❌ OpenTSx classes not accessible from Java kernel
- ❌ Users report "Still too difficult"
- ❌ Setup takes > 30 minutes for users

---

## 📅 Day-by-Day Plan

### Day 1: Environment Setup (Today - Dec 21)

**Duration:** 4 hours
**Owner:** DevOps + You
**Goal:** Working Polyglot Notebooks environment

#### Morning (2 hours): Install Core Tools

**Task 1.1: Install VS Code Polyglot Notebooks Extension**
```bash
# Install VS Code if not already installed
# Download from: https://code.visualstudio.com/

# Install .NET SDK (required for Polyglot Notebooks)
# macOS:
brew install --cask dotnet-sdk

# Linux:
wget https://dot.net/v1/dotnet-install.sh
chmod +x dotnet-install.sh
./dotnet-install.sh --channel 8.0

# Windows:
# Download from: https://dotnet.microsoft.com/download

# Install Polyglot Notebooks extension
code --install-extension ms-dotnettools.dotnet-interactive-vscode

# Verify installation
dotnet --version  # Should show 8.0.x
```

**Task 1.2: Install Java Kernel (IJava)**
```bash
# Clone IJava repository
cd ~/workspace
git clone https://github.com/SpencerPark/IJava.git
cd IJava

# Build and install
./gradlew installKernel

# Verify installation
jupyter kernelspec list | grep java
# Should show: java    /path/to/kernels/java
```

**Task 1.3: Install Python Kernel**
```bash
# Python kernel comes with Python/Jupyter
python3 -m pip install ipykernel jupyter

# Verify
jupyter kernelspec list | grep python
# Should show: python3    /path/to/kernels/python3
```

**Task 1.4: Install R Kernel**
```bash
# Install R if not already installed
# macOS:
brew install r

# Linux:
sudo apt-get install r-base

# Install IRkernel
R -e "install.packages('IRkernel', repos='http://cran.us.r-project.org')"
R -e "IRkernel::installspec(user = TRUE)"

# Verify
jupyter kernelspec list | grep ir
# Should show: ir    /path/to/kernels/ir
```

#### Afternoon (2 hours): Test Environment

**Task 1.5: Create Test Notebook**
```bash
cd ~/workspace/OpenTSx
mkdir -p notebooks/poc
cd notebooks/poc

# Create test notebook (we'll create this file)
code test-polyglot.ipynb
```

**Test Notebook Content** (`test-polyglot.ipynb`):
```json
{
  "cells": [
    {
      "cell_type": "markdown",
      "metadata": {},
      "source": ["# Polyglot Notebooks Test\n", "Testing R, Python, and Java kernels"]
    },
    {
      "cell_type": "code",
      "execution_count": null,
      "metadata": {"dotnet_interactive": {"language": "R"}},
      "source": ["# R Test\n", "print('Hello from R')\n", "mean(c(1,2,3,4,5))"]
    },
    {
      "cell_type": "code",
      "execution_count": null,
      "metadata": {"dotnet_interactive": {"language": "python"}},
      "source": ["# Python Test\n", "print('Hello from Python')\n", "import numpy as np\n", "np.mean([1,2,3,4,5])"]
    },
    {
      "cell_type": "code",
      "execution_count": null,
      "metadata": {"dotnet_interactive": {"language": "java"}},
      "source": ["// Java Test\n", "System.out.println(\"Hello from Java\");\n", "double[] values = {1,2,3,4,5};\n", "double sum = 0;\n", "for(double v : values) sum += v;\n", "System.out.println(\"Mean: \" + (sum/values.length));"]
    }
  ],
  "metadata": {
    "kernelspec": {
      "display_name": ".NET (C#)",
      "language": "polyglot-notebook",
      "name": "polyglot-notebook"
    }
  },
  "nbformat": 4,
  "nbformat_minor": 2
}
```

**Task 1.6: Run Test Notebook**
```bash
# Open in VS Code
code test-polyglot.ipynb

# Run each cell and verify:
# ✅ R cell outputs: [1] 3
# ✅ Python cell outputs: 3.0
# ✅ Java cell outputs: Mean: 3.0
```

**Task 1.7: Test OpenTSx Classes Accessible**

Add this Java cell to test notebook:
```java
// Test OpenTSx class loading
// Note: May need to add JAR to classpath
%jars /path/to/OpenTSx/opentsx-core/target/opentsx-core-3.0.0.jar

import org.opentsx.data.series.TimeSeriesObject;
System.out.println("OpenTSx classes accessible!");
```

**Deliverable End of Day 1:**
- [ ] All three kernels working
- [ ] Test notebook runs successfully
- [ ] OpenTSx classes accessible from Java kernel
- [ ] Environment setup documented

**Blockers to Resolve Today:**
- If IJava doesn't install: Try older version or alternative Java kernel
- If OpenTSx JAR not found: Build project first (`mvn clean install`)
- If kernels don't show in VS Code: Restart VS Code, check dotnet installation

---

### Day 2-3: Convert Episode 1 to Notebook (Dec 22-23)

**Duration:** 12 hours (6 hours/day)
**Owner:** Technical Writer + Data Scientist
**Goal:** Complete Episode 1 notebook with all R/Python/Java examples

#### Day 2 Morning: Structure and R Examples

**Task 2.1: Create Episode 1 Notebook Skeleton**

Create `notebooks/TSx-Episode-01-From-R-Python-to-OpenTSx.ipynb`:

```markdown
# Episode 1: From Python/R to OpenTSx

**Duration:** 90 minutes
**Focus:** Bridge from familiar tools to OpenTSx framework

## Learning Objectives
By the end of this episode, you will:
1. Map R/Python concepts to OpenTSx
2. Create time series in all three languages
3. Calculate basic statistics
4. Export/import data between environments

## Prerequisites
- R or Python experience
- Basic time series concepts
- This Polyglot Notebook environment
```

**Task 2.2: Add Rosetta Stone Table (Markdown)**

```markdown
## Rosetta Stone: R/Python/OpenTSx

| Concept | R | Python | OpenTSx (Java) |
|---------|---|--------|----------------|
| Create TS | `ts()` | `pd.Series()` | `new TimeSeriesObject()` |
| Mean | `mean(x)` | `x.mean()` | `x.getMean()` |
| Std Dev | `sd(x)` | `x.std()` | `x.getStddev()` |
| Normalize | `scale(x)` | `(x-x.mean())/x.std()` | `x.normalize()` |

Let's see these in action...
```

**Task 2.3: Add R Examples**

```r
# Cell 1: Create Time Series in R
# In R, you create a time series like this:
library(stats)

# Generate Gaussian-distributed data
set.seed(42)
ts_data <- rnorm(1000, mean=10, sd=1.5)

# Create time series object
ts_r <- ts(ts_data)

# Calculate statistics
cat("Mean:", mean(ts_r), "\n")
cat("Std Dev:", sd(ts_r), "\n")
cat("Min:", min(ts_r), "\n")
cat("Max:", max(ts_r), "\n")

# Visualize
plot(ts_r, main="Time Series in R", ylab="Value")
```

#### Day 2 Afternoon: Python Examples

**Task 2.4: Add Python Examples**

```python
# Cell 2: Same Concept in Python
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

# Generate Gaussian-distributed data
np.random.seed(42)
ts_data = np.random.normal(10, 1.5, 1000)

# Create pandas Series
ts_python = pd.Series(ts_data)

# Calculate statistics
print(f"Mean: {ts_python.mean()}")
print(f"Std Dev: {ts_python.std()}")
print(f"Min: {ts_python.min()}")
print(f"Max: {ts_python.max()}")

# Visualize
ts_python.plot(title="Time Series in Python")
plt.ylabel("Value")
plt.show()
```

#### Day 3 Morning: Java/OpenTSx Examples

**Task 2.5: Add Java/OpenTSx Examples**

```java
// Cell 3: Same Concept in OpenTSx (Java)
// Load OpenTSx JAR
%jars /path/to/OpenTSx/opentsx-core/target/opentsx-core-3.0.0.jar

import org.opentsx.data.series.TimeSeriesObject;

// Generate Gaussian-distributed data
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(
    1000,    // length
    10.0,    // mean (mu)
    1.5      // std dev (sigma)
);

// Calculate statistics
System.out.println("Mean: " + ts.getMean());
System.out.println("Std Dev: " + ts.getStddev());
System.out.println("Min: " + ts.getMin());
System.out.println("Max: " + ts.getMax());
System.out.println("Length: " + ts.getLength());

// Note: Visualization requires MacroRecorder GUI
// Export for visualization in R/Python
ts.writeToFile("/tmp/ts_data.csv", ",");
```

**Task 2.6: Add Comparison Markdown**

```markdown
## 💡 Key Insight

**See the pattern?**
- **R**: `mean(ts_r)` → **OpenTSx**: `ts.getMean()`
- **Python**: `ts_python.std()` → **OpenTSx**: `ts.getStddev()`
- **R**: `ts(data)` → **OpenTSx**: `new TimeSeriesObject()`

Same concepts, slightly different syntax. OpenTSx adds:
- ✅ Distributed processing (Spark, Flink)
- ✅ Real-time streaming (Kafka)
- ✅ Production deployment
- ✅ Scalability to TB-scale datasets
```

#### Day 3 Afternoon: Exercises

**Task 2.7: Add Hands-On Exercises**

```markdown
## 🏋️ Hands-On Exercises

Try these exercises in your preferred language first, then OpenTSx:

### Exercise 1: Manual Creation (15 min)

**In R:**
```r
# Create a time series with values [1, 2, 3, 4, 5]
manual_ts <- ts(c(1, 2, 3, 4, 5))
```

**In Python:**
```python
# Create a pandas Series with same values
manual_ts = pd.Series([1, 2, 3, 4, 5])
```

**In OpenTSx (You Try!):**
```java
// Hint: Use TimeSeriesObject constructor or addValue()
// Your code here...
```

### Exercise 2: Statistics (15 min)

Calculate mean, median, variance for a dataset:

**Your Task:** Generate 5000 random values (mean=100, sd=15) and:
1. Calculate all three statistics in R/Python
2. Calculate same in OpenTSx
3. Compare results (should be similar!)
```

**Task 2.8: Add Validation Checklist**

```markdown
## ✅ Validation Checkpoint

By the end of this episode, you should be able to:

- [ ] Run R code in notebook cells
- [ ] Run Python code in notebook cells
- [ ] Run Java/OpenTSx code in notebook cells
- [ ] Create TimeSeriesObject in OpenTSx
- [ ] Calculate statistics (mean, std, min, max)
- [ ] Understand the R/Python → OpenTSx mapping
- [ ] Export OpenTSx data for R/Python visualization

**Self-Assessment:**
- If you checked 5-7 items: ✅ Ready for Episode 2
- If you checked 3-4 items: ⚠️ Review the Rosetta Stone
- If you checked 0-2 items: ❌ Repeat this episode

## 🎯 Next Steps

**Episode 2:** Core Time Series Operations
- Moving averages, autocorrelation, detrending
- R/Python equivalents for each operation
- When to use OpenTSx vs R/Python
```

**Deliverable End of Day 3:**
- [ ] Complete Episode 1 notebook (~30-40 cells)
- [ ] All code examples tested and working
- [ ] Exercises with hints provided
- [ ] Validation checklist included

---

### Day 4: Testing and Refinement (Dec 24)

**Duration:** 4 hours
**Owner:** You + QA Tester
**Goal:** Ensure POC notebook is robust and user-friendly

**Task 4.1: End-to-End Test**
```bash
# Start fresh environment
# Run notebook from top to bottom
# Verify all cells execute without errors
# Check output is as expected
```

**Task 4.2: Cross-Platform Testing**
- [ ] Test on macOS
- [ ] Test on Linux (Ubuntu 22.04)
- [ ] Test on Windows 11

**Task 4.3: Error Handling**

Add these helper cells at the start:

```markdown
## 🛠️ Setup Verification

Run this cell first to verify your environment:
```

```java
// Environment Check Cell
try {
    // Test OpenTSx JAR loaded
    Class.forName("org.opentsx.data.series.TimeSeriesObject");
    System.out.println("✅ OpenTSx classes accessible");
} catch (ClassNotFoundException e) {
    System.out.println("❌ OpenTSx JAR not loaded. Run:");
    System.out.println("   %jars /path/to/opentsx-core-3.0.0.jar");
}

// Test Java version
System.out.println("Java version: " + System.getProperty("java.version"));
```

**Task 4.4: Documentation**

Create `notebooks/POC-SETUP-GUIDE.md`:

```markdown
# TSx Episode 1 POC - Setup Guide

## Prerequisites
- VS Code installed
- Java 8+ JDK
- Python 3.9+
- R 4.0+

## Quick Start

1. **Install Polyglot Notebooks:**
   ```bash
   # Install .NET SDK 8.0
   # Install VS Code extension: ms-dotnettools.dotnet-interactive-vscode
   ```

2. **Install Kernels:**
   ```bash
   # Java
   cd IJava && ./gradlew installKernel

   # Python
   pip install ipykernel

   # R
   R -e "IRkernel::installspec()"
   ```

3. **Build OpenTSx:**
   ```bash
   cd OpenTSx
   mvn clean install -DskipTests
   ```

4. **Open Notebook:**
   ```bash
   code notebooks/TSx-Episode-01-From-R-Python-to-OpenTSx.ipynb
   ```

5. **Update JAR Path:**
   Edit the `%jars` line in the first Java cell to point to your OpenTSx JAR.

## Troubleshooting

**Issue:** Kernel not found
- **Solution:** Restart VS Code, run `jupyter kernelspec list`

**Issue:** OpenTSx classes not found
- **Solution:** Build project, verify JAR path, check Java version

**Issue:** Python/R packages missing
- **Solution:** Install required packages (`numpy`, `pandas`, `matplotlib` for Python)
```

**Deliverable End of Day 4:**
- [ ] Tested on 3 platforms
- [ ] Setup guide created
- [ ] Known issues documented
- [ ] Ready for user testing

---

### Day 5: User Testing and Go/No-Go (Dec 26)

**Duration:** 6 hours
**Owner:** Product Owner + You
**Goal:** Validate approach with real users, make decision

#### Morning: User Testing (4 hours)

**Task 5.1: Recruit Test Users**

Find 3-5 users with this profile:
- Strong R or Python background
- Time series analysis experience
- Minimal Java experience
- Willing to spend 1-2 hours testing

**Suggested Sources:**
- Internal data science team
- University collaborators
- OpenTSx GitHub stargazers with .R or .py in repos
- LinkedIn reach-out to data scientists

**Task 5.2: Provide Testing Package**

Send users:
```
Subject: OpenTSx TSx Track - Notebook POC Testing (2 hours)

Hi [Name],

Would you help us test a new interactive notebook for learning OpenTSx?

**What:** Episode 1 of TSx Track (R/Python → OpenTSx bridge)
**Time:** 1-2 hours
**Compensation:** Early access + name in credits

**Setup:**
1. Follow setup guide (attached)
2. Open notebook in VS Code
3. Work through Episode 1
4. Fill out feedback form (15 min)

**Files:**
- TSx-Episode-01-From-R-Python-to-OpenTSx.ipynb
- POC-SETUP-GUIDE.md
- FEEDBACK-FORM.md

Thanks!
```

**Task 5.3: Create Feedback Form**

`FEEDBACK-FORM.md`:
```markdown
# TSx Episode 1 POC - Feedback Form

**Your Background:**
- [ ] R user (years: ___)
- [ ] Python user (years: ___)
- [ ] Java experience (years: ___)

**Setup Experience:**
1. Setup time: ___ minutes
2. Setup difficulty: ☐ Easy ☐ Medium ☐ Hard ☐ Couldn't complete
3. Issues encountered: ___

**Notebook Experience:**
4. Was side-by-side R/Python/Java helpful? ☐ Very ☐ Somewhat ☐ Not really
5. Did you prefer seeing R/Python first? ☐ Yes ☐ No ☐ Neutral
6. Could you run all cells successfully? ☐ Yes ☐ Some ☐ No
7. Notebook clarity: ☐ Clear ☐ Mostly clear ☐ Confusing

**Comparison to Pure Java:**
8. Is this easier than pure Java tutorial? ☐ Much easier ☐ Easier ☐ Same ☐ Harder
9. Would you continue to Episode 2? ☐ Definitely ☐ Probably ☐ Maybe ☐ No

**Overall:**
10. Rate this approach (1-5): ☐ 1 ☐ 2 ☐ 3 ☐ 4 ☐ 5
11. Suggestions for improvement: ___

**Time Spent:**
- Setup: ___ min
- Episode 1: ___ min
- Total: ___ min
```

#### Afternoon: Decision Meeting (2 hours)

**Task 5.4: Analyze Feedback**

Create summary spreadsheet:

| User | Background | Setup | Helpful? | Continue? | Rating | Issues |
|------|-----------|-------|----------|-----------|--------|--------|
| User A | R (10y) | 25 min | Very | Definitely | 5/5 | None |
| User B | Python (5y) | 45 min | Somewhat | Probably | 4/5 | Kernel install |
| User C | R/Python | 60 min | Very | Definitely | 4/5 | JAR path |
| **AVG** | - | **43 min** | - | - | **4.3/5** | - |

**Task 5.5: Go/No-Go Decision Criteria**

✅ **GO Criteria (Proceed to Full Implementation):**
- 60%+ users rate 4-5 stars
- 60%+ users say "Definitely" or "Probably" continue
- Setup time < 60 minutes average
- Majority say "easier than pure Java"
- No critical technical blockers

⚠️ **PIVOT Criteria (Adjust Approach):**
- 40-60% users rate 4-5 stars
- Setup issues but notebook content good
- Try Jupyter instead of VS Code
- Simplify kernel setup

❌ **NO-GO Criteria (Defer TSx Track):**
- <40% users rate 4-5 stars
- Majority won't continue to Episode 2
- Setup time > 90 minutes average
- Users say "still too hard"
- Critical technical issues

**Task 5.6: Decision Meeting Agenda**

```markdown
# TSx Polyglot Notebooks POC - Go/No-Go Meeting

**Date:** December 26, 2025
**Duration:** 2 hours
**Attendees:** Product Owner, Technical Lead, Budget Owner, You

## Agenda

1. **POC Results Review (30 min)**
   - User feedback summary
   - Technical feasibility assessment
   - Setup experience analysis

2. **Cost/Benefit Analysis (20 min)**
   - Week 1 actual cost vs budget
   - Full implementation estimate
   - ROI recalculation

3. **Decision Discussion (40 min)**
   - GO: Approve $12K for Weeks 2-4
   - PIVOT: What changes needed?
   - NO-GO: Defer TSx track rationale

4. **Next Steps (30 min)**
   - If GO: Assign team, set milestones
   - If PIVOT: Define new approach
   - If NO-GO: Communication plan

## Decision Document

Record decision in: `EVOLUTION/DECISION-TSx-POC-Results.md`
```

**Deliverable End of Day 5:**
- [ ] 3+ users tested POC
- [ ] Feedback analyzed
- [ ] Go/No-Go decision made
- [ ] Next steps documented

---

## 📊 Success Metrics

### Quantitative Targets

| Metric | Target | Stretch Goal |
|--------|--------|--------------|
| **User Rating** | 3.5+ / 5.0 | 4.0+ / 5.0 |
| **Setup Time** | <60 min avg | <30 min avg |
| **Completion Rate** | 80%+ | 100% |
| **Continue Rate** | 60%+ | 80%+ |
| **Technical Issues** | <3 major | 0 major |

### Qualitative Targets

- [ ] Users say "This is better than pure Java"
- [ ] Users understand R/Python → OpenTSx mapping
- [ ] Users feel empowered to continue learning
- [ ] Setup process is documented and reproducible
- [ ] Technical approach is feasible for Episodes 2-6

---

## 🚨 Risk Management

### High-Risk Items

| Risk | Impact | Mitigation | Owner |
|------|--------|------------|-------|
| Kernels don't install | CRITICAL | Test on 3 platforms, have backup plan (Jupyter) | DevOps |
| OpenTSx JAR not accessible | CRITICAL | Test %jars command early, document classpath setup | You |
| Users can't complete setup | HIGH | Create video walkthrough, offer live support | Tech Writer |
| Feedback is negative | HIGH | Have pivot plan ready (Jupyter, simplified approach) | Product Owner |

### Contingency Plans

**If IJava doesn't work:**
- Plan B: Use JShell kernel
- Plan C: Use Jupyter with BeakerX
- Plan D: Use separate notebooks per language

**If setup is too complex:**
- Plan B: Provide Docker image with everything pre-installed
- Plan C: Use cloud-based notebooks (Binder, Colab)
- Plan D: Simplify to Episode 1 only, rest as Markdown

**If users prefer Jupyter:**
- Plan B: Switch to Jupyter + BeakerX for Weeks 2-4
- Adjust timeline: +1 week for BeakerX setup

---

## 💰 Budget Tracking

### Week 1 Budget: $3,000

| Item | Hours | Rate | Cost |
|------|-------|------|------|
| DevOps (environment setup) | 8 | $150/hr | $1,200 |
| Technical Writer (notebook creation) | 10 | $100/hr | $1,000 |
| Data Scientist (R/Python examples) | 6 | $130/hr | $780 |
| Your Time (coordination, testing) | - | - | - |
| **TOTAL** | **24 hours** | - | **$2,980** |

**Buffer:** $20

---

## 📋 Deliverables Checklist

### By End of Week 1

- [ ] Working Polyglot Notebooks environment
- [ ] Test notebook demonstrating all 3 kernels
- [ ] Complete Episode 1 notebook (30-40 cells)
- [ ] Setup guide documentation
- [ ] Troubleshooting guide
- [ ] Feedback form
- [ ] 3+ user test sessions completed
- [ ] Feedback analysis
- [ ] Go/No-Go decision made
- [ ] Decision documented

---

## 🎯 Go/No-Go Decision Matrix

|  | GO ✅ | PIVOT ⚠️ | NO-GO ❌ |
|---|---|---|---|
| **User Rating** | 4.0+ | 3.0-3.9 | <3.0 |
| **Continue Rate** | 60%+ | 40-59% | <40% |
| **Setup Time** | <60 min | 60-90 min | >90 min |
| **Technical Issues** | 0-2 minor | 3-5 minor or 1 major | 2+ major |
| **Budget** | On track | +20% ok | Over budget |

**Decision:**
- **3+ GO criteria met** → ✅ Proceed to Weeks 2-4
- **3+ PIVOT criteria met** → ⚠️ Adjust approach, retry Week 1
- **3+ NO-GO criteria met** → ❌ Defer TSx track

---

## 📞 Key Contacts

**Decision Makers:**
- Product Owner: [Name] - Final Go/No-Go
- Budget Owner: [Name] - Budget approval
- Technical Lead: [Name] - Technical feasibility

**Implementation Team:**
- DevOps: [Name] - Environment setup
- Technical Writer: [Name] - Content creation
- Data Scientist: [Name] - R/Python examples
- QA Tester: [Name] - Testing

**Test Users:**
- User A (R expert): [Name/Email]
- User B (Python expert): [Name/Email]
- User C (Both): [Name/Email]

---

## 🔄 Daily Standup Questions

**Each Day at 9 AM:**
1. What did we complete yesterday?
2. What are we working on today?
3. Any blockers?
4. Are we on track for Day 5 decision?

**Standup Log:**

**Day 1 (Dec 21):**
- Completed: ___
- Today: ___
- Blockers: ___
- On track: ☐ Yes ☐ At risk ☐ No

**Day 2 (Dec 22):**
- Completed: ___
- Today: ___
- Blockers: ___
- On track: ☐ Yes ☐ At risk ☐ No

[Continue for Days 3-5]

---

## 📚 Reference Documents

**Background:**
- `EVOLUTION/DECISION-TSx-Track-Polyglot-Notebooks.md` - Full decision analysis
- `EVOLUTION/TASK-006-onboarding-path-review.md` - Overall review
- `docs/onboarding/reviews/REVIEW-TSx-Track.md` - TSx track review
- `docs/onboarding/ONBOARDING-PATH-TSx.md` - Episode 1 source content

**Technical:**
- Polyglot Notebooks docs: https://github.com/dotnet/interactive
- IJava kernel: https://github.com/SpencerPark/IJava
- BeakerX (backup): http://beakerx.com/

---

**POC Plan Version:** 1.0
**Created:** 2025-12-21
**Owner:** OpenTSx Core Team
**Next Review:** 2025-12-26 (Day 5 - Go/No-Go Decision)
