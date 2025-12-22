# TSx Track POC - Quick Start Guide

**🎯 You're Committed to TSx Track! Here's What to Do Next:**

---

## ⚡ Start RIGHT NOW (Next 30 Minutes)

### Step 1: Verify Prerequisites (10 min)

```bash
# Check what you have installed
java -version        # Need Java 8+
python3 --version    # Need Python 3.9+
R --version          # Need R 4.0+
code --version       # Need VS Code
```

**Missing something?** Install now:
- **Java:** `brew install openjdk@11` (macOS) or download from java.com
- **Python:** `brew install python@3.11` or python.org
- **R:** `brew install r` or r-project.org
- **VS Code:** Download from code.visualstudio.com

---

### Step 2: Install .NET SDK (10 min)

Polyglot Notebooks requires .NET SDK 8.0:

```bash
# macOS
brew install --cask dotnet-sdk

# Linux
wget https://dot.net/v1/dotnet-install.sh
chmod +x dotnet-install.sh
./dotnet-install.sh --channel 8.0

# Windows
# Download from: https://dotnet.microsoft.com/download

# Verify
dotnet --version  # Should show 8.0.x
```

---

### Step 3: Install VS Code Extension (5 min)

```bash
# Install Polyglot Notebooks extension
code --install-extension ms-dotnettools.dotnet-interactive-vscode

# Verify
code --list-extensions | grep dotnet-interactive
```

---

### Step 4: Quick Test (5 min)

```bash
# Create test directory
mkdir -p ~/opentsx-poc
cd ~/opentsx-poc

# Create simple test notebook
cat > test.ipynb << 'EOF'
{
  "cells": [
    {
      "cell_type": "markdown",
      "metadata": {},
      "source": ["# Test Polyglot Notebooks"]
    },
    {
      "cell_type": "code",
      "execution_count": null,
      "metadata": {},
      "source": ["Console.WriteLine(\"Hello from .NET!\");"]
    }
  ],
  "metadata": {
    "kernelspec": {
      "display_name": ".NET (C#)",
      "language": "polyglot-notebook",
      "name": "polyglot-notebook"
    }
  }
}
EOF

# Open in VS Code
code test.ipynb

# Click "Run All" - should see "Hello from .NET!"
```

✅ **If this works, you're ready for Day 1 full setup!**

---

## 📅 Week 1 Schedule (Days 1-5)

| Day | Date | Tasks | Hours | Owner |
|-----|------|-------|-------|-------|
| **1** | Dec 21 (TODAY) | Install kernels, test environment | 4h | DevOps + You |
| **2** | Dec 22 | Convert Episode 1 (R examples) | 6h | Tech Writer |
| **3** | Dec 23 | Convert Episode 1 (Python/Java) | 6h | Tech Writer + Data Scientist |
| **4** | Dec 24 | Testing, refinement, docs | 4h | QA + You |
| **5** | Dec 26 | User testing, Go/No-Go decision | 6h | Product Owner + You |

**Total:** 26 hours, $3,000 budget

---

## 🚀 Day 1 Full Tasks (Today - 4 Hours)

### Morning Session (2 hours): Install Kernels

**Task 1: Install IJava (Java kernel)** - 45 min
```bash
cd ~/workspace
git clone https://github.com/SpencerPark/IJava.git
cd IJava
./gradlew installKernel

# Verify
jupyter kernelspec list | grep java
```

**Task 2: Install Python kernel** - 15 min
```bash
pip3 install ipykernel jupyter
jupyter kernelspec list | grep python
```

**Task 3: Install R kernel** - 30 min
```bash
# Install IRkernel package
R -e "install.packages('IRkernel', repos='http://cran.us.r-project.org')"

# Install kernel spec
R -e "IRkernel::installspec(user = TRUE)"

# Verify
jupyter kernelspec list | grep ir
```

**Task 4: Coffee Break** - 30 min ☕

---

### Afternoon Session (2 hours): Test Everything

**Task 5: Build OpenTSx** - 30 min
```bash
cd ~/path/to/OpenTSx
mvn clean install -DskipTests

# Verify JAR exists
ls -lh opentsx-core/target/opentsx-core-*.jar
```

**Task 6: Create test notebook** - 45 min

See full example in: `EVOLUTION/POC-TSx-Polyglot-Notebooks-Week1.md` (Day 1, Task 1.5)

**Task 7: Test OpenTSx classes** - 30 min
```java
// In Java cell of test notebook
%jars /full/path/to/opentsx-core/target/opentsx-core-3.0.0.jar

import org.opentsx.data.series.TimeSeriesObject;
TimeSeriesObject ts = TimeSeriesObject.getGaussianDistribution(100, 10.0, 1.0);
System.out.println("Mean: " + ts.getMean());
```

**Task 8: Document setup** - 15 min

Create `SETUP-LOG.md` documenting:
- What worked
- What didn't work
- Workarounds needed
- Time spent on each step

---

## ✅ Day 1 Success Criteria

By end of today, you must have:

- [ ] ✅ All three kernels (Java, Python, R) showing in `jupyter kernelspec list`
- [ ] ✅ Test notebook runs with all three kernels
- [ ] ✅ OpenTSx JAR loads in Java kernel
- [ ] ✅ Can create TimeSeriesObject and call methods
- [ ] ✅ Setup documented for replication
- [ ] ✅ Environment ready for Day 2 content creation

**If ANY of these fail:** Stop and troubleshoot before Day 2!

---

## 🆘 Common Issues & Solutions

### Issue 1: IJava won't build
**Error:** `./gradlew installKernel` fails
**Solution:**
```bash
# Check Java version
java -version  # Must be 8+

# Try with Java 11 specifically
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew installKernel

# If still fails, try pre-built release
wget https://github.com/SpencerPark/IJava/releases/download/v1.3.0/ijava-1.3.0.zip
unzip ijava-1.3.0.zip
python3 install.py --sys-prefix
```

### Issue 2: Kernels don't show in VS Code
**Error:** Only .NET kernel appears
**Solution:**
```bash
# Restart VS Code
# Check kernels are installed
jupyter kernelspec list

# If not showing, reinstall
python3 -m ipykernel install --user
R -e "IRkernel::installspec(user = TRUE)"
```

### Issue 3: OpenTSx classes not found
**Error:** `ClassNotFoundException: org.opentsx.data.series.TimeSeriesObject`
**Solution:**
```bash
# Rebuild OpenTSx
cd OpenTSx
mvn clean install

# Use full absolute path in %jars
%jars /Users/yourname/workspace/OpenTSx/opentsx-core/target/opentsx-core-3.0.0.jar

# Or add to IJava kernel config
echo "classpath=/path/to/opentsx-core-3.0.0.jar" >> ~/.ijava/kernel.json
```

### Issue 4: Can't install R packages
**Error:** `IRkernel` package won't install
**Solution:**
```bash
# Install system dependencies first
# macOS:
brew install libgit2

# Linux:
sudo apt-get install libcurl4-openssl-dev libssl-dev

# Then retry
R -e "install.packages('IRkernel')"
```

---

## 📞 Who to Ask for Help

**Can't install kernels?** → DevOps team
**OpenTSx JAR issues?** → Java dev team
**R/Python examples?** → Data scientist team
**Notebook structure?** → Technical writer
**General questions?** → Post in #opentsx-poc Slack channel

---

## 📊 Progress Tracking

Update this daily:

| Day | Planned | Actual | Status | Blockers |
|-----|---------|--------|--------|----------|
| 1 (Dec 21) | 4h | ___h | ⬜ Not Started / ⏳ In Progress / ✅ Done | ___ |
| 2 (Dec 22) | 6h | ___h | ⬜ | ___ |
| 3 (Dec 23) | 6h | ___h | ⬜ | ___ |
| 4 (Dec 24) | 4h | ___h | ⬜ | ___ |
| 5 (Dec 26) | 6h | ___h | ⬜ | ___ |

---

## 🎯 Go/No-Go Decision (Day 5)

**You'll decide based on:**

| Metric | Target | Your Result |
|--------|--------|-------------|
| User rating (avg) | 3.5+ / 5 | ___ / 5 |
| Users who'd continue | 60%+ | ___% |
| Setup time (avg) | <60 min | ___ min |
| Technical issues | <3 major | ___ |

**Decision:**
- ✅ **GO:** If 3+ targets met → Approve $12K for Weeks 2-4
- ⚠️ **PIVOT:** If 2 targets met → Adjust approach, retry
- ❌ **NO-GO:** If <2 targets met → Defer TSx track

---

## 📚 Full Documentation

**Detailed plan:** `EVOLUTION/POC-TSx-Polyglot-Notebooks-Week1.md` (863 lines)
**Decision analysis:** `EVOLUTION/DECISION-TSx-Track-Polyglot-Notebooks.md` (545 lines)
**Overall review:** `EVOLUTION/TASK-006-onboarding-path-review.md`

---

## 🚦 START NOW!

```bash
# 1. Open terminal
cd ~/workspace/OpenTSx

# 2. Check prerequisites
java -version && python3 --version && R --version

# 3. Install .NET SDK
brew install --cask dotnet-sdk  # or download

# 4. Install VS Code extension
code --install-extension ms-dotnettools.dotnet-interactive-vscode

# 5. You're ready! Start Day 1 tasks above ⬆️
```

**Questions?** Read full plan: `EVOLUTION/POC-TSx-Polyglot-Notebooks-Week1.md`

**Let's build this!** 🚀

---

**Created:** 2025-12-21
**Owner:** You!
**First Milestone:** End of Day 1 (Tonight)
**Final Decision:** Day 5 (Dec 26)
