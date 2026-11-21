# 17. Notebook workplace: load, view and check series

> As of 2026-10-14. **D80 to D82 were decided on 2026-10-13 (section 7); stage N1 is built and hooked into the stack
> of the demo (section 8).** This document plans
> a subsystem: notebooks as a further OpenTSx application, in which someone loads the series of the system directly
> and works with them by hand. It builds on [16](16-DEMO-UND-SIMULATIONSEXPERTEN.md). Work package:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md), 014.18.
>
> The name of a customer, computers and addresses are not in here: the repository is public. "The stack" means the
> installation in which OpenTSx runs next to the broker and which comes with a notebook.

## 1. What for

The web application shows what is provided for: signals, flows, runs. Whoever wants to win an operator also needs
a place for what is not provided for: upload a series of the operator, put it next to what is expected, ask a
question that nobody has built as a view yet. That is customer-specific work, and it is the moment in which an
operator sees their own data in the system.

Three activities, in this order of importance:

1. **Viewing.** What is there? The time series inventory as a table, a series as a curve, coverage and gaps.
2. **Checking.** Does a new series fit what the system expects? Unit, interval, value range, gaps, comparison with a
   related series. The result is a verdict and a proposal for what the series should be bound to.
3. **Computing.** Let the twin compute a question and process the result further yourself, for example over many
   variants.

## 2. What exists

Looked at on 2026-10-13, read-only.

| Part | State |
|---|---|
| Notebook in the stack | JupyterLab 4 with Python 3.13, pandas, numpy, scipy, matplotlib, plotly and pyarrow; two Kafka clients; a directory of its own per installation that survives restarts; examples are added at start from an image of their own. The notebook belongs to the stack, not to OpenTSx |
| Way to the broker | **open:** the notebook may read and write through the proxy of the stack; the address is in its environment |
| Way to the retrieval server | **closed:** the namespace of the notebook allows only approved targets, and OpenTSx is not among them. Neither the service nor the web application can be reached from there |
| Python package `opentsx` | reads and writes the v2 model byte-identical to Java, has a client for the retrieval server (episodes, pattern search, segments) and converts episodes into pandas. **Missing:** inventory, systems and signals, runs of the twin; the package is not installed in the notebook of the stack |
| Data | inventory with over 150 series, since today including the year 2025 for wind, temperature, air pressure (DWD, measured) and price (reported) |
| Runs | run service with a register (16, section 4, points 1 and 2) |

## 3. The subsystem

```
                    +--------------------+        reads         +---------------------+
  domain expert --> | notebook of the    | -------------------> | retrieval server    |  inventory, episodes,
                    | stack              |   (way A, REST)      | (systems, runs)     |  signals, runs
                    | + package opentsx  |                      +---------------------+
                    | + OpenTSx notebooks| -------------------> +---------------------+
                    +--------------------+   (way B, topic)     | broker (proxy)      |  raw episodes
                             |                                  +---------------------+
                             | writes only into its own directory (stages N1 and N2)
                             v
                    notebooks, evaluations, binding proposals as files
```

Two ways to the data, with different purposes:

| Way | What for | State | Cost |
|---|---|---|---|
| **A, through the retrieval server** | everything OpenTSx has already put in order: inventory, signals with state, decision status, runs | closed: needs an approval in the namespace of the notebook | one rule in the stack, set by its operator; the server has no accounts, so the approval applies to everyone who can open the notebook |
| **B, directly from the topic** | the raw episodes, as the flows read them; independent of what the server knows at the moment | open | the package `opentsx` must go into the notebook; a reader that reads a topic from the start puts load on the broker |

Both are needed. A is the normal case and the gentle one. B is the direct access that the task names, and the proof
that the data belongs to the operator and does not sit behind a user interface.

### 3.1 What OpenTSx contributes

| Part | Content |
|---|---|
| **Package** | `opentsx` is extended by three things: `inventory()` (the inventory as a table), `systems()`, `signals(system)` and `decisions(system)`, and `runs` (read a scenario, start a run, run and comparison as tables). In addition a reader for way B: episodes of a topic by metric and period, with an upper limit that it does not exceed silently |
| **Notebooks** | four notebooks that run as they are delivered (section 4) |
| **Delivery** | the notebooks and the package as a small image that the stack hooks in when the notebook starts, the same way it does with its own examples. OpenTSx does not change the notebook of the stack |
| **Link** | in the web application at the place Signals and at the place Simulate: "Open in notebook", with the name of the series or of the run |

### 3.2 Rules

1. **Reading is free, writing is not.** In stages N1 and N2 a notebook writes nothing into the broker and
   nothing into the system. What it produces stays in its directory.
2. **Origin stays visible.** Every table that the package delivers carries the origin of the series as a column.
   What is simulated is called simulated in the notebook.
3. **Numbers from runs carry the id of the run** (16, section 3.1). A table from a run has it as a column.
4. **Gaps are not filled.** A gap is `NaN` and a row in the gap list.
5. **The broker is spared.** The reader for way B takes a period and an upper limit; "everything from the start" is
   an explicit setting, not a default.
6. **Data of the operator stays in the stack.** A notebook uploads nothing to the outside; the package has no call
   for that.

## 4. The notebooks

| Notebook | Question | Shows | Way |
|---|---|---|---|
| **1 Inventory** | What is there? | the inventory as a table (metric, tags, period, values, gaps, origin), filterable; a chosen series as a curve; coverage per day as a map; the gaps as a list | A; B as a fallback while A is closed |
| **2 A signal** | What does the system expect, and what is there? | a signal of `h2-chain` with expectation and operating range, raw and clean on top of each other, the values outside the range marked | A |
| **3 Check** | Does my series fit? | a CSV file of the operator against the expectation of a signal: unit, interval, range, gaps, time zone; next to it a related series (for example the wind of the station against the wind at the nacelle); at the end a verdict and the lines for the binding | A, file from the directory |
| **4 Ask the twin** | What if? | read the scenario, compute a question as a run, key figures and curves against the reference; then the same question over twenty times of day as a table | A |

Notebook 1 is the first one to be built: it needs the least, and it is what someone wants to see first.

## 5. What is missing

| No. | What is missing | Size | Who |
|---|---|---|---|
| 1 | Package `opentsx`: inventory, systems, signals, decisions, runs; reader for way B with an upper limit | medium | OpenTSx |
| 2 | The four notebooks, with tests that run them end to end against an in-memory server | medium | OpenTSx |
| 3 | The image with package and notebooks, and the lines with which a stack hooks it in | small | OpenTSx, hooked in by the operator of the stack |
| 4 | **Approval for way A** in the namespace of the notebook | one rule | operator of the stack; not in this repository |
| 5 | **Who may see what.** The retrieval server has no accounts. As long as that is so, everyone who can open the notebook sees all series of the server | decision, building later | the owner |
| 6 | Link from the web application into the notebook | small | OpenTSx |
| 7 | Writing from the notebook: a checked series as a candidate into the system, with account and origin | medium, deliberately later | OpenTSx |
| 8 | Several people: today one notebook, one directory, one login | decision | operator of the stack |
| 9 | Load on the broker from readers that read from the start | check before stage N2 | broker operations |

## 6. Stages

| Stage | Content | Accepted when |
|---|---|---|
| **N1 Inventory in the notebook** | package with `inventory()` and the reader for way B; notebook 1; image; hooked into the stack of the demo | a person opens the notebook of the stack, runs notebook 1 and sees the inventory as a table and a series of the year 2025 as a curve, with gaps and origin; nothing was written |
| **N2 Signal and checking** | package with systems and signals; notebooks 2 and 3; approval for way A is set | a CSV file is checked against a signal, and the notebook says what it should be bound to and, if not, why not |
| **N3 Twin and way back** | package with runs; notebook 4; link from the web application; writing a candidate with an account | a question is computed in the notebook over many variants; a checked series appears in the web application as a candidate |

N1 needs no change to the stack if notebook 1 reads through way B. That is the reason to build it first.

## 7. Decisions

| No. | Question | Decision | State |
|---|---|---|---|
| D80 | What does the subsystem start with? | with N1: inventory in the notebook | as proposed |
| D81 | How do package and notebooks get into the notebook? | **right away as an image of its own**, which the stack hooks in at start | **changed** (the proposal was: put into the directory by hand for the first demo) |
| D82 | Is way A opened although the server has no accounts? | **now**, with a network rule in the namespace of the notebook | **changed** (the proposal was: with N2) |
| D83 | May a notebook write? | open; proposal: in N1 and N2 no, in N3 only through the web application with an account, never directly into the topic | open |

## 8. State of N1

Built and hooked into the stack of the demo on 2026-10-14.

* **Package `opentsx`:** `RetrievalClient` has `inventory()`, `systems()`, `signals()`, `decisions()`,
  `scenario()`, `run()`, `runs()`, `run_series()` and `compare()`. `TopicReader` reads the episodes of a topic
  once from the start to the end, without a consumer group and without a commit, filters by metric, tags and period,
  keeps each episode once and stops before reading if the topic has more records than it may read.
* **Notebook 1** (`python-package/notebooks/01-zeitreihen-inventar.ipynb`): inventory as a table, the measured and
  reported series, a series as a curve with a daily mean, coverage per day as a map, gap list, the price of 2025
  with the change of resolution. Chooses way A and falls back to way B.
* **Image** `opentsx/notebook-content` (`deploy/docker/notebook-content.Dockerfile`): package with fastavro and
  xxhash for Python 3.13 and the notebooks; an init container copies them to `~/opentsx` at every start.
* **In the stack** (`deploy/k3s/notebook/`): patch of the notebook deployment and a network rule that additionally
  allows only the retrieval server. The previous deployment is backed up; going back works with `rollout undo` and
  deleting the rule.
* **Checked in the notebook of the stack:** notebook 1 runs to the end through way A (154 series; wind of the station
  2025 with 52,360 values and two gaps in June, 200 missing values in total; 724 intervals with a negative price).
  Way B reaches the broker and stops at a limit of 10 records without reading.

**Limits.** The retrieval server has no accounts: whoever can open the notebook reads every series. The directory
`~/opentsx` is replaced at every start.

**Notebooks 2 and 4 (2026-10-14).**

* **Notebook 2 "A signal"** (`02-ein-signal.ipynb`): the signals of the hydrogen chain with expectation and
  operating range; for a chosen signal (preselected: the wind of the DWD station) raw and clean series on top of
  each other, the operating range as a band, values outside the plausible range marked, what the cleaning changed;
  finally the state of the decision with its inputs.
* **Notebook 4 "Ask the twin"** (`04-den-zwilling-fragen.ipynb`): the scenario, a question on the series of the
  signals ("valve open again at 02:00") with key figures and curves against the reference on the same inputs, then
  the same question from 01:10 to 03:00 in steps of ten minutes. Result on the lab system: it works without a
  protective shutdown if the valve is open again by 01:50. Every run is marked `restricted`, because the demand is a
  demo generator and the schedule and the grid meter are missing.
* A run is computed on the server and kept there in memory; the notebooks write nothing into the broker.
* **Notebook 3 "Check"** (`03-verproben.ipynb`, 2026-10-14): a CSV file against a signal. It reads local time with
  clock change, decimal comma and two time columns, checks unit, interval, time zone, meaning of the timestamp,
  duplicate timestamps, plausible and operating range and gaps, puts the file next to the series of the signal
  (correlation, mean deviation) and reaches a verdict. It writes a prepared copy in UTC and in the unit of the signal
  into its own directory and names the commands for the archive and the topic and the lines of the binding; they must
  be run by someone who has the right to do so. Shipped with it is `beispiel-windmessung.csv`: wind of the DWD station
  in October 2025 in km/h, local time, with one outlier and one gap. The notebook finds all peculiarities and says
  "bindable, with warnings".
