# 16. Engineering method: from a decision to its effect

> **Status: method described, tooling partial.** This chapter describes a way of working. Each step says what
> OpenTSx supports today and what is planned. The design behind steps 2 to 5 was signed off on 2026-10-13
> ([`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](../datamodel-2026/14-ENGINEERING-FLOW.md), German). The frame
> around them, a decision at the start and its effect at the end, is a revision after an external review, decided
> on the same day ([`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](../datamodel-2026/15-ENTSCHEIDUNGSKREIS.md),
> German). Work item: [`EVOLUTION/TASK-014-engineering-flow.md`](../../EVOLUTION/TASK-014-engineering-flow.md).
> Where this chapter and the design differ, the design is the source and this chapter follows.

## 16.1 What the method is for

Data, graphs, models and streams are means. The unit that matters is a **decision someone has to take, and that
can be checked afterwards**: what was known, what was recommended, what was done, what came of it.

The method keeps six questions apart that tend to get mixed up in practice:

1. What is decided, by whom, with which aims and limits?
2. What does the system **expect** to be measured, and what does each quantity mean?
3. What is **available**, and where does it come from?
4. How does raw data become a series that can be **used for this purpose**?
5. What may an analysis **claim**?
6. What was **done**, and what was its **effect**?

It starts from the decision and the system, not from the data that happens to be there. Starting from the data
hides what is missing; starting from the decision makes the gaps a list you can work through, and tells you which
gaps matter.

Use it when you set up a new plant or scenario, when you connect a new data source, and when a result surprises:
in that case walk the steps backwards.

## 16.2 Terms

| Term | Meaning |
|---|---|
| **Decision** | A recurring question on a system with someone who answers it: aims, levers, limits, a period. |
| **System** | The plant: its components, their ports and the connections between them. |
| **Scenario** | One case or run of a system: a period, a set of inputs, interventions. |
| **Signal** | A quantity the system expects, whether data exists for it or not. |
| **Role** | What kind of quantity a signal is: an observation (what was), an estimate (what could be), a command (what was asked for), a limit, or something from outside. The same unit does not mean the same quantity. |
| **Series** | The data of a signal. |
| **Channel** | An entry of a channel catalog: what a sensor value means (unit, range, sampling). |
| **Plausible range** | What the sensor can deliver. A value outside it puts the **measurement** in question. |
| **Operating range** | What normal operation gives. A value outside it says something about the **system**; the measurement may be perfectly right. |
| **Grade** | `raw`: as delivered or generated. `clean`: on the clock and in the unit of the system, with a verdict on every value. `features`: values computed from the clean series for models. |
| **Origin** | How data came to be: measured, reported, forecast, estimated, synthetic. For a derived series it is a path, not a rank. |
| **Flow** | The recipe that turns series into other series. A running flow publishes a Smart Topic. |
| **Analysis** | A rule, a model or an optimiser that gives a recommendation. It is released for a purpose, with a range it was checked in. |

## 16.3 The method in eight steps

The steps are an order of thinking, not a gate process. You will go back; the point is to know which step you
are in. A manually confirmed action closes the loop: nothing here needs the plant to be controlled automatically.

### Step 1: Describe the decision

* **Goal:** a decision that can be checked: who decides what, how often, for which period, with which aims,
  through which levers, within which limits.
* **Do:** write the question in one sentence. Name who decides, who approves and who carries it out. List the aims
  with the figure each is measured by. List the levers: the quantities you can actually set. List what must never
  be violated. Name the simple strategy you would follow without any analysis: it is the reference everything else
  is compared with.
* **Result:** a decision card.
* **Done when:** a stranger could say, for a given hour, whether the decision was taken and what it was.
* **Pitfall:** modelling the plant first and looking for a use afterwards.
* **Today:** a draft format (`opentsx.decision.v1`) and one example,
  [`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml). No code reads
  it. **Planned:** the decision card as the working object of the web application, with three connected views:
  decision, system, evidence.

### Step 2: Mark out the system that matters

* **Goal:** a picture of the part of the plant the decision depends on: components, states, levers, outside
  influences.
* **Do:** list the components. For each, name its inputs and outputs with a unit. Draw the connections. Mark the
  inputs that nothing inside the system produces: those are the ones you have to provide. Leave out what the
  decision does not depend on.
* **Result:** a wiring plan. In OpenTSx this is a twin plan with atoms, ports and wiring.
* **Done when:** every input is either wired to an output or marked as coming from outside, and every lever and
  every limit of the decision is a port of the plan.
* **Pitfall:** asking for the whole plant before anything is useful. What is missing blocks only what depends on it.
* **Today:** twin plans in `opentsx-twin/src/main/resources/twin/`; the place "System" of the web application
  (`/systems`) lists every atom with its ports and says what feeds each input: another atom, a signal with its
  state, or nothing. **Planned:** the picture with the connections in the web application; today it is the Smart
  Topics Studio prototype.

### Step 3: Define the signals by what they mean

* **Goal:** for every quantity, its meaning and its expectation, written down before looking at any data.
* **Do:** for each input from outside and each sensor channel, say in one sentence what the quantity is, and give
  it a role. Write down unit, sampling interval and kind (analog value, status, counter). Give two ranges: what the
  sensor can deliver, and what normal operation gives. Take the sensor's side from the channel catalog.
* **Result:** the signal list of the system.
* **Done when:** two quantities with the same unit can no longer be mistaken for each other, and a stranger could
  check data against the expectation.
* **Pitfalls:** deriving the range from the data you have, so nothing is ever out of range. And treating what was
  measured as what was possible: the power a wind farm fed in is not the power the wind offered while the farm was
  curtailed.
* **Today:** the wind farm channel catalog ([chapter 6](06-windpark.md)); the signal list as part of a system
  document (`opentsx.system.v1`, for example
  [`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml)) with role, meaning and both ranges. The
  build checks every reference of the document: atoms and ports against the plan, channels against the catalog,
  assets against the inventory. **Planned:** editing a signal in the web application instead of in the file.

### Step 4: Connect the sources

* **Goal:** every signal has a source that says honestly what kind of data it is, or is knowingly open.
* **Do:** look up what the topics hold: which series, over which period, in which value range, with how many
  gaps. Bind a signal to the series that carry it, after confirming that they mean the same; a matching name and
  unit are a suggestion, not a proof. Where no data exists, decide: estimate it from other signals, set it by hand
  (a constant or a schedule), generate it, or leave it open. Record the origin with the source.
* **Result:** each signal has one source per grade, or is on the list of open signals.
* **Done when:** no signal has an unknown state, and every generated or hand-set signal is marked as such.
* **Pitfalls:** a generated series that looks like a measurement two steps later. And taking a generator for a
  model of the world: plausible single values do not make a plausible system. Wind, price and demand depend on
  each other; a seed proves that a run can be repeated, not that it is realistic.
* **Today:** the TS inventory of the retrieval server (`GET /v1/inventory`, [chapter 9](09-retrieval.md)) lists
  every series with period, range, missing values and the ground truth of the scenario. The page "Signals" puts
  expectation and data side by side: for every signal its origin, a state computed from the system document and
  the inventory, and the next step; a signal whose data leaves the plausible range is flagged. Bindings are written
  into the system document by hand. **Planned:** binding in the web application; a state in several dimensions
  (availability, validity of the measurement, operating state, freshness, preparation) instead of one word; a
  scenario model for the outside influences.

### Step 5: Check the preparation and the analysis

* **Goal:** series on the clock and in the unit of the system that can be used for this decision, and an analysis
  whose limits are known.
* **Do:** open the raw series on the scope with its ranges in view. Apply operators one at a time and look at each
  result. Play known disturbances through: a correctly measured disturbance is the most valuable information you
  have, so the preparation must keep it. Judge three things separately: is the measurement valid, is the system in
  a normal state, is the series fit for this analysis. For the analysis, write down the inputs it needs, the range
  it was checked in, what it was checked with, and how it states its uncertainty.
* **Result:** a flow whose output is the clean grade of the signal, and an analysis with a declared range.
* **Done when:** you can say what happened to every value that was questionable, and for every result whether it
  lies inside the range the analysis was checked in.
* **Pitfalls:** cleaning until everything is green. Tuning the preparation on one stretch of data. And taking clean
  inputs for a good model: where the data comes from says nothing about whether the analysis is right.
* **Today:** the workbench ([chapter 14](14-saas-service-layer.md)). "Prepare" on the page "Signals" opens it for
  one signal: its raw series, a line that names system, signal, expectation and origin, the plausible range on the
  screen, and the cleaning operators preset from the expectation. Ground truth of the scenario shows as bands.
  The plausible-range operator today removes or clips what lies outside. **Planned:** every clean signal is written
  in two variants, one that keeps each value with a verdict on it and one with the questionable values removed;
  the operating range on the screen; release of an analysis for a purpose.

### Step 6: Run in the shadow

* **Goal:** the preparation and the analysis keep up with the data, and you have seen them do so before anyone
  acts on them.
* **Do:** publish the flow and start it so it follows the topic. Watch its state and how far its data reaches.
  Let the analysis produce recommendations without acting on them, and compare them with what happened.
* **Result:** clean series that are written continuously, and recommendations nobody has followed yet.
* **Done when:** the clean series is as fresh as the raw one, a stop or a failure is visible, and the
  recommendations have held for a period you agreed on beforehand.
* **Pitfalls:** two writers for one result. Late data, duplicates and restarts that count something twice or lose
  it without anyone noticing. A new version of a flow that replaces the running one at once.
* **Today:** "Publish as clean signal" in the workbench checks, saves and starts in one step; the processor then
  runs a chain of plausible range, unit conversion and resampling on one raw series and writes the clean series,
  and the page "Signals" shows the signal as clean. The Flow Composer and the place "Flows" start and stop flows.
  A piece computed again after a restart carries the same identity, so a reader counts it once. Anything with
  several inputs (a sum over turbines) and every other operator run in the workbench only. **Planned:** results
  marked as provisional, final or revised; late data as a revision instead of a skipped block; a new version in
  the shadow first; recommendations in shadow operation.

### Step 7: Compare the options and act

* **Goal:** a decision taken with its reasons on record.
* **Do:** compare the alternatives under the same conditions, always including the reference strategy. Look at
  the uncertainty, and at what each alternative would violate. Approve, change or reject the recommendation and
  say why. Record what was actually carried out: it may differ from what was approved, and it happens later.
* **Result:** a recommendation, an approval and an action, each a record of its own, each pointing to the one
  before, each with what was known at the time.
* **Done when:** the decision of a given hour can be rebuilt with the information of that hour.
* **Pitfall:** an optimisation on generated data sold as proof that a strategy works.
* **Today:** the twins compute what-if and forecast runs on bound inputs and show the origin of every series
  (`opentsx twin`, [appendix A](appendix-a-cli.md)). A draft format for the records
  (`opentsx.decision-record.v1`) with an example,
  [`systems/decisions/h2-dispatch.records.example.yaml`](../../systems/decisions/h2-dispatch.records.example.yaml).
  **Planned:** all of it in the web application. OpenTSx recommends and records; it does not control the plant.

### Step 8: Judge the effect

* **Goal:** know what the decision brought, and why it differed from what was expected.
* **Do:** compare what was expected with what was observed. Tell three causes apart: the analysis was wrong, the
  action was carried out differently, the outside world changed. Compare with the reference strategy for the same
  period, where that can be computed.
* **Result:** an outcome record, and a correction to the analysis, the preparation or the expectation.
* **Done when:** you can say which of the three causes explains the difference, or that it cannot be told yet.
* **Pitfall:** "it went better after the recommendation" taken as proof. A claim about benefit needs a basis of
  comparison: the same situation under the reference strategy. A prediction and an actual value alone show no cause.
* **Today:** nothing beyond the draft format. **Planned:** the last stage of the design.

## 16.4 Rules that hold across the steps

1. **The decision sets the measure.** Whether a series is good enough is answered by what the decision needs of
   it, not by the series.
2. **Expectation before data.** Write down what a signal means and should be before you look at what it is.
3. **One truth per question.** Components and ports live in the plan, the meaning of a channel in the catalog,
   the device in the inventory, the source of a signal in the system document.
4. **Preparation marks before it removes.** The raw series stays. The clean series that is read by default keeps
   every value with a verdict on it; a variant without the questionable values exists next to it for those who ask
   for it. A real excursion stays visible.
5. **A measurement and a state are two things.** Out of the plausible range puts the measurement in question; out
   of the operating range is an event.
6. **Origin is a path, and always visible.** Generated or modelled values never look like measurements, in a
   list, on a screen, or in a report. A derived series says what it was made of; there is no ranking of origins
   and no single quality number.
7. **Open is a state.** A signal without a source is recorded as open. It blocks what depends on it and nothing
   else.
8. **One result, one writer.** One signal in one grade is written by one flow.
9. **Nothing is rewritten silently.** A result says whether it is final; later data makes a revision.
10. **Reproducible means all of it.** Input data, starting state, parameters, versions and what was known at the
    time. A seed and a version number are not enough.
11. **A dependency is not a cause.** That a result was computed from a series does not mean the one drives the
    other.

## 16.5 A worked example

The system `h2-chain`: a wind farm feeds an electrolyser, a hydrogen line with an offtaker, a battery, the grid
connection and a direct marketer. The documents are
[`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml) and
[`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml). The third
column says what of each step you can do today.

| Step | In the example | Today |
|---|---|---|
| 1 Decision | Which setpoint does the electrolyser run at, and does the battery charge or discharge, in the next hour? Aims: deliver the hydrogen, low energy cost, keep the announced feed-in. Limits: line pressure, state of charge. Reference: hold the setpoint. | as a file |
| 2 System | Nine components. Nine inputs come from outside. | on the page "System" |
| 3 Signals | Ten signals. Six of the nine outside inputs have one; three do not. What the park generated and what it could have generated are two signals: an observation and an estimate. | on the page "Signals" |
| 4 Sources | The topic `tsx.wind.episodes.v2` holds power, wind speed and state per turbine; they are bound, with the origin synthetic, because the topic holds simulator output. The price is generated. Two commands are constants set by hand. Demand and marketing schedule are open. | on the page "Signals"; bindings by hand in the file |
| 5 Preparation | Wind speed of a turbine: plausible range, then the mean per minute. | in the workbench |
| 6 Shadow | That flow runs on the processor and writes the clean wind speed. The sum of the turbine power (actual generation) and the estimate of the available power over the power curve are declared and do not run: their operators do not exist yet. | the one flow runs |
| 7 Options | Hold the setpoint against following the price. The recommendation is blocked: the demand has no source, the price is generated, the price-following analysis has no evidence. | as a file |
| 8 Effect | The approved action was carried out three minutes late and without the battery; one period cannot separate that from a model error. | as a file |

The example is blocked at step 7, and that is its point: it says exactly what is missing before the hydrogen side
of the decision means anything, while the wind side already works.

## 16.6 Checklist

Before a result leaves the team:

- [ ] The decision it serves is written down, with a reference strategy.
- [ ] Every input of the analysis is a named signal with a meaning and a role.
- [ ] Every signal has an expectation that was not derived from its own data.
- [ ] Every signal is bound, estimated, set by hand, generated, or recorded as open.
- [ ] Every generated or hand-set signal is marked wherever it appears, and so is everything computed from it.
- [ ] Known disturbances are still visible in the clean series.
- [ ] The preparation is a saved flow, not a sequence of clicks nobody can repeat.
- [ ] What runs continuously has one writer per result and a visible state.
- [ ] The result lies inside the range the analysis was checked in, or says that it does not.
- [ ] The comparison includes the reference strategy under the same conditions.
- [ ] The report names what was known at the time, the origin of the inputs and the versions used.
- [ ] No claim about benefit rests on generated data.

## 16.7 Where to do each step today

| Step | Tool today | Reference |
|---|---|---|
| 1 Describe the decision | a file in the draft format | [`15-ENTSCHEIDUNGSKREIS.md`](../datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 3 |
| 2 Mark out the system | twin plan (YAML); page "System" (`/systems`); Smart Topics Studio prototype | `opentsx-twin/README.md`, [appendix B](appendix-b-rest.md) |
| 3 Define the signals | system document; channel catalog | [chapter 6](06-windpark.md) |
| 4 Connect the sources | page "Signals"; TS inventory; wind farm simulator; `opentsx-market` | [appendix B](appendix-b-rest.md), [chapter 9](09-retrieval.md), [chapter 6](06-windpark.md) |
| 5 Check preparation and analysis | workbench: operators and oscilloscope; twins | [chapter 14](14-saas-service-layer.md), [appendix A](appendix-a-cli.md) |
| 6 Run in the shadow | "Publish as clean signal"; place "Flows"; vector processor | [chapter 14](14-saas-service-layer.md) |
| 7 Compare and act | twins: shadow, what-if, forecast; records as a file | [appendix A](appendix-a-cli.md) |
| 8 Judge the effect | records as a file | [`15-ENTSCHEIDUNGSKREIS.md`](../datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 8 |

What does not exist yet: the decision card in the web application, binding from the page, a state of a signal in
several dimensions, values that are marked instead of removed, operators with several inputs on the processor,
revisions for late data, the four records as events, and any comparison of strategies outside the twin. The guide
in the web application (the switch "Guide") still shows the seven steps of the earlier version of this chapter.
