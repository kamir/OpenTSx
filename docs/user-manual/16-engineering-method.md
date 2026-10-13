# 16. Engineering method: from the system to the model

> **Status: method described, tooling partial.** This chapter describes a way of working. Each step says what
> OpenTSx supports today and what is planned. The design behind it was signed off on 2026-10-13:
> [`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](../datamodel-2026/14-ENGINEERING-FLOW.md) (German), with the
> work item [`EVOLUTION/TASK-014-engineering-flow.md`](../../EVOLUTION/TASK-014-engineering-flow.md). Its first
> delivery stage exists: the system document and the page "Signals". Where this chapter and the design differ,
> the design is the source and this chapter follows.

## 16.1 What the method is for

A model is only as good as the series it is fed, and real data never arrives as clean series. The method keeps
four questions apart that tend to get mixed up in practice:

1. What does the system **expect** to be measured?
2. What is **available**, and where does it come from?
3. How does raw data become a series a model can **trust**?
4. What may a model **use**?

It starts from the system, not from the data that happens to be there. Starting from the data hides what is
missing; starting from the system makes the gaps a list you can work through.

Use it when you set up a new plant or scenario, when you connect a new data source to an existing one, and when a
model gives results nobody can explain: in that case walk the steps backwards from the model.

## 16.2 Terms

| Term | Meaning |
|---|---|
| **System** | The plant: its components, their ports and the connections between them. |
| **Scenario** | One case or run of a system: a period, a set of inputs, interventions. |
| **Signal** | A quantity the system expects, whether data exists for it or not. |
| **Series** | The data of a signal. |
| **Channel** | An entry of a channel catalog: what a sensor value means (unit, range, sampling). |
| **Expectation** | What a signal should look like: unit, plausible range, sampling interval, kind. |
| **Grade** | `raw`: as delivered or generated. `clean`: prepared, on the clock and in the unit of the system. `features`: values computed from the clean series for models (lagged values, rolling statistics, derived quantities). |
| **Origin** | How data came to be: measured, reported, forecast, estimated, synthetic. |
| **Flow** | The recipe that turns series into other series. A running flow publishes a Smart Topic. |

## 16.3 The method in seven steps

The steps are an order of thinking, not a gate process. You will go back; the point is to know which step you
are in.

### Step 1: Define the system

* **Goal:** a picture of the plant that names every component and every quantity that crosses between two of them.
* **Do:** list the components. For each, name its inputs and outputs with a unit. Draw the connections. Mark the
  inputs that nothing inside the system produces: those are the ones you have to provide.
* **Result:** a wiring plan. In OpenTSx this is a twin plan with atoms, ports and wiring.
* **Done when:** every input is either wired to an output or marked as provided from outside.
* **Pitfall:** modelling what the data happens to offer. A port without data is information, not a defect.
* **Today:** twin plans in `opentsx-twin/src/main/resources/twin/`; the system picture of the Smart Topics Studio
  prototype (`docs/prototypes/smart-topics-studio.html`). **Planned:** the system picture as a page of the web
  application.

### Step 2: List the expected signals

* **Goal:** for every quantity, a written expectation, before looking at any data.
* **Do:** for each input from outside and each sensor channel, write down unit, plausible range, sampling interval
  and kind (analog value, status, counter), and where you expect it to come from. Take the expectation of a sensor
  channel from the channel catalog instead of writing it again.
* **Result:** the signal list of the system.
* **Done when:** every signal has an expectation a stranger could check data against.
* **Pitfall:** deriving the range from the data you have. Then nothing is ever out of range.
* **Today:** the wind farm channel catalog ([chapter 6](06-windpark.md)); port units in the twin plan; the signal
  list as part of a system document (`opentsx.system.v1`, for example
  [`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml)). The build checks every reference of the
  document: atoms and ports against the plan, channels against the catalog, assets against the inventory.

### Step 3: Take stock: expected against available

* **Goal:** know for every signal whether data exists, how much, and how good.
* **Do:** look up what the topics hold: which series, over which period, in which value range, with how many gaps.
  Put it next to the expectation. Every signal ends up in one state: open, bound without data, raw only, clean,
  stale, out of range.
* **Result:** a coverage list. It is the work list for the next steps.
* **Done when:** no signal has an unknown state.
* **Pitfall:** counting a series as available because its name matches. Check unit and range.
* **Today:** the TS inventory of the retrieval server (`GET /v1/inventory`) and the "TS Inventory" dialog of the
  workbench list every series with period, range, missing values and the ground truth of the scenario
  ([chapter 9](09-retrieval.md)). The page "Signals" of the web application (`/systems`) puts the two side by
  side: for every signal of a system the expectation, the origin, a state computed from the system document and
  the inventory, and the next step. A signal whose data leaves the expected range is flagged. **Planned:** the
  states "running" and "stale", which need flows that run.

### Step 4: Bind or simulate

* **Goal:** every signal has a source, and the source says honestly what kind of data it is.
* **Do:** bind a signal to the series that carry it. Where no data exists, decide: simulate it from the
  expectation (a generator with a seed), set it by hand (a constant or a schedule), or leave it open. Record the
  origin with the source.
* **Result:** each signal has one source per grade, or is knowingly open.
* **Done when:** the open signals are a deliberate list, and every simulated signal is marked as synthetic.
* **Pitfall:** a simulated series that looks like a measurement two steps later. The origin must travel with the
  data.
* **Today:** the twin binds series to input ports per run and carries the origin (`MEASURED`, `REPORTED`,
  `FORECAST`, `ESTIMATED`, `SYNTHETIC`); the wind farm simulator and `opentsx-market` provide data; series loaded
  into the workbench from the inventory keep the labels of their scenario; bindings are declared in the system
  document, written by hand. **Planned:** binding and simulating as actions of the page "Signals"; generators
  described as processors and preset from the expectation; a runtime that writes generated series to a topic.

### Step 5: Prepare: from raw to clean

* **Goal:** a series on the clock and in the unit of the system, with implausible values and gaps dealt with in a
  way you can state.
* **Do:** open the raw series on the scope with the expected range in view. Apply operators one at a time and look
  at each result: plausibility check, unit conversion, resampling, smoothing. Compare against the ground truth
  where the scenario has one. Keep the chain of operators as a flow.
* **Result:** a flow whose output is the clean grade of the signal.
* **Done when:** the clean series meets the expectation over the whole period, and you can say what happened to
  every value that did not.
* **Pitfall:** tuning the preparation on one stretch of data. Check a second period and the known events.
* **Today:** the workbench ([chapter 14](14-saas-service-layer.md)): load series from the TS inventory, apply
  operators, inspect on the oscilloscope with cursors, measurements and ground-truth bands, save the flow. This
  works interactively in the browser. "Prepare" on the page "Signals" opens the workbench for one signal: its raw
  series, a line that names system, signal, expectation and origin, and the expected range drawn on the screen.
  **Planned:** "Publish as clean signal", which makes the flow the source of the clean grade.

### Step 6: Run

* **Goal:** the preparation keeps up with the data instead of being a one-off.
* **Do:** start the flow so it follows the topic. Watch its state and how far its data reaches. Stop it before you
  change it.
* **Result:** a Smart Topic that is written continuously.
* **Done when:** the clean series is as fresh as the raw one, and a stop or a failure is visible.
* **Pitfall:** two running flows writing the same topic. One signal, one source.
* **Today:** the Flow Composer starts and stops flows; the vector processor runs a flow that aligns series loaded
  from the inventory to a common clock and writes a vector topic. Other operators do not run continuously yet.
  **Planned:** transformers in the processor and a topic for clean series.

### Step 7: Model, and feed the result back

* **Goal:** predictions, training and optimisation on data whose quality is known.
* **Do:** give a model clean signals only, or features computed from them. Compute a feature that several models
  need once, at the signal, and give it a name; keep a feature only one model needs with that model. Record which
  signals and features a model uses and their origin. Put its result where
  the system picture can show it, next to what it predicts. When a result surprises, walk back: model, clean
  series, flow, raw series, source, expectation.
* **Result:** a model whose inputs can be named, and results placed in the system.
* **Done when:** for every input of the model you can say where it comes from and what was done to it.
* **Pitfall:** a model trained on synthetic data and reported as if on measurements.
* **Today:** the twins compute what-if and forecast runs on bound inputs and show the origin of every series
  (`opentsx twin`, [appendix A](appendix-a-cli.md)). **Planned:** models on clean signals as Smart Topics
  ([`05-SMART-TOPICS.md`](../datamodel-2026/05-SMART-TOPICS.md), German draft).

## 16.4 Rules that hold across the steps

1. **Expectation before data.** Write down what a signal should be before you look at what it is.
2. **One truth per question.** Components and ports live in the plan, the meaning of a channel in the catalog,
   the device in the inventory, the source of a signal in one place.
3. **Origin is always visible.** Simulated or modelled values never look like measurements, in a list, on a
   screen, or in a model report. A derived series carries the worst origin of its inputs.
4. **Open is a state.** A signal without a source is recorded as open, not left out.
5. **Clean is a contract.** A clean series is on the clock and in the unit of the system and meets the
   expectation. If it does not, it is not clean. Features are computed from clean series only.
6. **One signal, one source per grade.** Two writers on one topic produce every point twice.
7. **Reproducible.** A generator has a seed; a flow, a plan and a system description have a version. What produced
   a series can be named afterwards.

## 16.5 A worked example

The system `h2-chain`: a wind farm feeds an electrolyser, a hydrogen line with an offtaker, a battery, the grid
connection and a direct marketer. The example document is
[`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml).

| Step | In the example |
|---|---|
| 1 System | Nine components. Six inputs come from outside: available wind power, electricity price, hydrogen demand, electrolyser setpoint, battery schedule, marketing schedule. |
| 2 Signals | Each of the six gets an expectation, for example available power: kW, 0 to 12,000, every minute. The turbine channels take theirs from the catalog (active power: kW, -100 to 10,000, every second). |
| 3 Stock | The topic `tsx.wind.episodes.v2` holds active power and wind speed per turbine. There is no price, no demand, no schedule. |
| 4 Bind or simulate | Turbine channels are bound to the topic; their origin is synthetic, because the topic holds simulator output. The price is simulated with a mean-reverting generator and a seed. The setpoint and the battery schedule are constants set by hand. Demand and marketing schedule stay open. |
| 5 Prepare | Active power of a turbine: plausibility check against the expected range, then the mean per minute. |
| 6 Run | The preparation runs as a flow; a second flow adds the turbines up to the available power of the farm; a third computes four features of the turbine power (two lags, mean and standard deviation over an hour). |
| 7 Model | The twin runs on the available power, the price and the setpoints. Its report says which inputs are synthetic. |

The example ends with two open signals out of eight. That is a valid state: it says exactly what is still missing
before the hydrogen side of the model means anything.

## 16.6 Checklist

Before a model result leaves the team:

- [ ] Every input of the model is a named signal of the system.
- [ ] Every signal has a written expectation that was not derived from its own data.
- [ ] Every signal is bound, simulated, set by hand, or recorded as open.
- [ ] Every simulated or hand-set signal is marked with its origin wherever it appears.
- [ ] Every clean series was checked against its expectation over the whole period, and on a second period.
- [ ] The preparation is a saved flow, not a sequence of clicks nobody can repeat.
- [ ] What runs continuously has one writer per topic and a visible state.
- [ ] The report names the origin of the inputs and the versions of plan, flows and model.

## 16.7 Where to do each step today

| Step | Tool today | Reference |
|---|---|---|
| 1 Define the system | twin plan (YAML), Smart Topics Studio prototype | `opentsx-twin/README.md`, `docs/prototypes/smart-topics-studio.html` |
| 2 Expected signals | channel catalog; port units in the plan | [chapter 6](06-windpark.md) |
| 3 Take stock | page "Signals" (`/systems`); TS inventory (REST and workbench dialog); streaming statistics and data quality | [appendix B](appendix-b-rest.md), [chapter 9](09-retrieval.md), [chapter 7](07-stats-quality.md) |
| 4 Bind or simulate | twin inputs per run; wind farm simulator; `opentsx-market` | [chapter 6](06-windpark.md), [appendix A](appendix-a-cli.md) |
| 5 Prepare | workbench: operators and oscilloscope | [chapter 14](14-saas-service-layer.md) |
| 6 Run | Flow Composer: start and stop; vector processor | [chapter 14](14-saas-service-layer.md) |
| 7 Model | twins: shadow, what-if, forecast | [appendix A](appendix-a-cli.md) |

What does not exist yet: binding and simulating from the page, generators that write continuously, preparation
flows that run on the processor, and models fed from clean series. Those are the remaining delivery stages of the
design named at the top of this chapter.
