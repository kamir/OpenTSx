# 16. Engineering method: from a decision to its effect

> **Status: method described, tooling partial.** This chapter describes a way of working. Each step says what
> OpenTSx supports today and what is planned. The design behind steps 2 to 5 is
> [`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](../datamodel-2026/14-ENGINEERING-FLOW.md) (German). The frame
> around them, a decision at the start and its effect at the end, is
> [`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](../datamodel-2026/15-ENTSCHEIDUNGSKREIS.md) (German). Work item:
> [`EVOLUTION/TASK-014-engineering-flow.md`](../../EVOLUTION/TASK-014-engineering-flow.md). Where this chapter and
> the design differ, the design is the source and this chapter follows.

## 16.1 Decide when to use the method

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

## 16.2 Use the terms of the method

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
| **Grade** | `raw`: as delivered or generated. `clean`: on the clock and in the unit of the system. Today the plausible-range operator removes or clips questionable values; a verdict on every value is planned. `features`: values computed from the clean series for models. |
| **Origin** | How data came to be: measured, reported, forecast, estimated, synthetic, best first. Today a derived signal gets the worst origin of the signals its flow reads. A series written as another signal names its input in the label `madeOf`. Origin as a path with shares is planned. |
| **Flow** | The recipe that turns series into other series. A running flow publishes a Smart Topic. |
| **Analysis** | A rule, a model or an optimiser that gives a recommendation. It is released for a purpose, with a range it was checked in. |

## 16.3 Work through the eight steps

The steps are an order of thinking, not a gate process. You go back to earlier steps. The point is to know which
step you are in. A manually confirmed action closes the loop: nothing here needs the plant to be controlled automatically.

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
  [`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml). The retrieval
  server reads it from the system document (`decisions:`) and gives every analysis a status
  (`GET /v1/systems/<id>/decisions`, schema `opentsx.decision-status.v1`, [appendix B](appendix-b-rest.md)). The
  place "Models" shows it. **Planned:** the decision card as the working object of the web application, with three
  connected views: decision, system, evidence.

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
* **Today:** twin plans in `opentsx-twin/src/main/resources/twin/`. The place "System" of the web application
  (`/systems/<system>/system`, where `<system>` is the id of the system, for example `h2-chain`) draws the atoms
  in the phases of a time step with their wires: power, hydrogen and information in three colours. Below the
  picture it lists every atom with its ports and says what feeds each input: another atom, a signal with its
  state, or nothing. "Open in the Studio" opens the Smart Topics Studio prototype (`/studio/`). `/systems` alone
  opens the place "Signals" of the first system. **Planned:** an editable node graph in the Studio
  ([`EVOLUTION/TASK-016-smart-topics.md`](../../EVOLUTION/TASK-016-smart-topics.md)).

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
  every series with period, range, missing values and the ground truth of the scenario. The place "Signals" puts
  expectation and data side by side: for every signal its origin, a state computed from the system document and
  the inventory, and the next step; a signal whose data leaves the plausible range is flagged. "Bind" suggests
  series by unit and value range and binds one after you confirmed that it is the quantity the signal means.
  "Simulate" binds a signal to a demo generator, `gen.ou` or `gen.sine` ([chapter 17](17-operators.md)), preset
  from its expectation, and "Generate" runs it over a period; the result is marked as generated and as a demo.
  Both go into a working copy of your account that lies over the system document; the place gives you the lines to
  put into the file. **Planned:** a state in several dimensions
  (availability, validity of the measurement, operating state, freshness, preparation) instead of one word; a
  scenario model for the outside influences; generators that keep writing.

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
* **Today:** the workbench ([chapter 14](14-saas-service-layer.md)). "Prepare" on the place "Signals" opens it for
  one signal: its raw series, a line that names system, signal, expectation and origin, the plausible range on the
  screen, and the cleaning operators preset from the expectation. Ground truth of the scenario shows as bands.
  The plausible-range operator (`dq.plausible-range`) removes or clips what lies outside. [Chapter 17](17-operators.md)
  lists every operator and where it runs. The place "Models" shows for every analysis of a decision card whether it
  is usable, restricted or blocked, with the reasons. It checks the range an analysis was checked in against the
  minimum and maximum of the whole series, not per time point. **Planned:** every clean signal is written in two
  variants, one that keeps each value with a verdict on it and one with the questionable values removed; the
  operating range on the screen.

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
* **Today:** "Publish as clean signal" in the workbench checks, saves and starts in one step. The processor then
  runs a chain on the series of one signal: `dq.checks` with the check `PLAUSIBLE_RANGE` (the workbench operator
  `dq.plausible-range`), `convert.unit`, `resample`, `model.wind-shear`, `model.power-curve`, `feature.lag` and
  `feature.rolling`. It writes the clean series, and the place "Signals" shows the signal as clean. On a clean
  series, "Publish as another signal" writes a signal the flow computes, for example the potential of the park.
  The processor also runs `align.vector`, which joins several series into one vector topic. Publishing refuses a
  chain with any other operator; those run in the workbench only. No runtime adds several series into one
  (`agg.sum`, the sum over turbines). The Flow Composer and the place "Flows" start and stop flows. A piece
  computed again after a restart carries the same identity, so a reader counts it once. **Planned:** results
  marked as provisional, final or revised; late data as a revision instead of a skipped block; a new version in
  the shadow first; recommendations in shadow operation; `agg.sum` on the processor.

### Step 7: Compare the options and act

* **Goal:** a decision taken with its reasons on record.
* **Do:** compare the alternatives under the same conditions, always including the reference strategy. Look at
  the uncertainty, and at what each alternative would violate. Approve, change or reject the recommendation and
  say why. Record what was actually carried out: it may differ from what was approved, and it happens later.
* **Result:** a recommendation, an approval and an action, each a record of its own, each pointing to the one
  before, each with what was known at the time.
* **Done when:** the decision of a given hour can be rebuilt with the information of that hour.
* **Pitfall:** an optimisation on generated data sold as proof that a strategy works.
* **Today:** the place "Simulate" (`/systems/<system>/simulate`) asks the twin a question: a control set at a
  time, a policy for the electrolyser, or a forecast from a time on. It shows the run next to the reference, on
  the inputs of the scenario or on the series of the signals, with the origin of every input
  ([appendix B](appendix-b-rest.md), `POST /v1/systems/<id>/runs`). The place "Models" shows for every alternative
  of a decision card the status of its analysis. `bin/opentsx twin` runs scenario S1 in shadow, what-if and forecast
  mode on invented inputs and prints events and KPIs. `bin/opentsx twin --history <dir>` runs it on the price and
  wind archive in `<dir>` and prints the origin of each input ([appendix A](appendix-a-cli.md)). A draft format
  for the records (`opentsx.decision-record.v1`) with an example,
  [`systems/decisions/h2-dispatch.records.example.yaml`](../../systems/decisions/h2-dispatch.records.example.yaml).
  **Planned:** the records in the web application; a comparison of the alternatives over a period. OpenTSx
  recommends and records; it does not control the plant.

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

## 16.4 Follow the rules in every step

1. **The decision sets the measure.** Whether a series is good enough is answered by what the decision needs of
   it, not by the series.
2. **Expectation before data.** Write down what a signal means and should be before you look at what it is.
3. **One truth per question.** Components and ports live in the plan, the meaning of a channel in the catalog,
   the device in the inventory, the source of a signal in the system document.
4. **Preparation marks before it removes.** The raw series stays. A real excursion stays visible. The target: the
   clean series that is read by default keeps every value with a verdict on it, and a variant without the
   questionable values sits next to it. Today the plausible-range operator removes or clips (step 5).
5. **A measurement and a state are two things.** Out of the plausible range puts the measurement in question; out
   of the operating range is an event.
6. **Origin is a path, and always visible.** Generated or modelled values never look like measurements, in a
   list, on a screen, or in a report. A derived series says what it was made of. The target is a path without a
   ranking of origins and without a single quality number. Today a derived signal gets the worst origin of the
   signals its flow reads. So the potential of the park, computed by two models from the measured station wind, is
   listed as measured; the details of the signal show the expected origin, estimated, next to it.
7. **Open is a state.** A signal without a source is recorded as open. It blocks what depends on it and nothing
   else.
8. **One result, one writer.** One signal in one grade is written by one flow.
9. **Nothing is rewritten silently.** The target: a result says whether it is final, and later data makes a
   revision. Today neither exists (step 6).
10. **Reproducible means all of it.** Input data, starting state, parameters, versions and what was known at the
    time. A seed and a version number are not enough.
11. **A dependency is not a cause.** That a result was computed from a series does not mean the one drives the
    other.

## 16.5 Walk through the h2-chain example

The system `h2-chain` has twelve components: a wind farm under operating rules, an electrolyser, a compressor, a
high-pressure buffer, a pipeline with a handover station and an offtaker, a battery, the grid connection, the
market and a direct marketer. The documents are
[`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml) and
[`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml). The third
column says what of each step you can do today.

| Step | In the example | Today |
|---|---|---|
| 1 Decision | Which setpoint does the electrolyser run at, and does the battery charge or discharge, in the next hour? Aims: deliver the hydrogen, low energy cost, keep the announced feed-in. Limits: pressure of the hydrogen buffer, pressure at the handover station, state of charge of the battery. Reference: hold the setpoint. | as a file; status on the place "Models" |
| 2 System | Twelve components. Nine inputs come from outside. | on the place "System" |
| 3 Signals | Sixteen signals. Each of the nine outside inputs has a signal; two of these signals have no source. What the park generated and what it could have generated are two signals: an observation and an estimate. | on the place "Signals" |
| 4 Sources | The topic `tsx.wind.episodes.v2` holds power, wind speed and state per turbine; they are bound with the origin synthetic, because the topic holds simulator output. The same topic holds the 2025 day-ahead price (origin reported) and three weather series of the DWD station Greifswalder Oie (origin measured). The demand of the offtaker is bound to the demo generator `gen.sine`. Four commands are constants set by hand. The meter at the grid connection point and the marketing schedule are open. | on the place "Signals", with Bind, Simulate and Generate |
| 5 Preparation | Wind speed of a turbine: plausible range, then the mean per minute. | in the workbench |
| 6 Shadow | Five of the six flows use only operators the processor runs: clean power and clean wind speed of T02, the features of the power, the clean station wind, and the potential of the park from the station wind over wind shear and power curve (`park-potential`). The sum of the turbine power (`park-power`, actual generation) is declared and does not run: no runtime runs the operator `agg.sum`. No code runs the flow files in `systems/flows/`; the retrieval server only checks what they read and write. You build the same chain in the workbench and publish it. | five of six flows can run |
| 7 Options | Hold the setpoint against following the price. Both analyses are blocked: the decision needs the demand clean and reported or forecast, and the demand comes from the demo generator `gen.sine` (synthetic) with no flow that cleans it. The price-following analysis also has no evidence, which alone restricts it. | on the place "Models" (status of each alternative); the records as a file |
| 8 Effect | The approved action was carried out three minutes late and without the battery; one period cannot separate that from a model error. | as a file |

The example is blocked at step 7, and that is its point: it says exactly what is missing before the hydrogen side
of the decision means anything, while the wind side already works.

## 16.6 Check a result before it leaves the team

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

## 16.7 Find the tool for each step

| Step | Tool today | Reference |
|---|---|---|
| 1 Describe the decision | a file in the draft format, listed in the system document; shown on the place "Models" | [`15-ENTSCHEIDUNGSKREIS.md`](../datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 3 |
| 2 Mark out the system | twin plan (YAML); place "System" (`/systems/<system>/system`); Smart Topics Studio prototype (`/studio/`) | `opentsx-twin/README.md`, [appendix B](appendix-b-rest.md) |
| 3 Define the signals | system document; channel catalog | [chapter 6](06-windpark.md) |
| 4 Connect the sources | place "Signals"; TS inventory; wind farm simulator; demo generators; `opentsx-market` | [appendix B](appendix-b-rest.md), [chapter 9](09-retrieval.md), [chapter 6](06-windpark.md), [chapter 17](17-operators.md) |
| 5 Check preparation and analysis | workbench: operators and oscilloscope; place "Models": what each analysis may claim today; twins | [chapter 14](14-saas-service-layer.md), [chapter 17](17-operators.md), [appendix A](appendix-a-cli.md) |
| 6 Run in the shadow | "Publish as clean signal"; "Publish as another signal"; place "Flows"; vector processor | [chapter 14](14-saas-service-layer.md), [chapter 17](17-operators.md) |
| 7 Compare and act | place "Simulate"; place "Models"; twins: shadow, what-if, forecast; records as a file | [appendix B](appendix-b-rest.md), [appendix A](appendix-a-cli.md) |
| 8 Judge the effect | records as a file | [`15-ENTSCHEIDUNGSKREIS.md`](../datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 8 |

What does not exist: editing a decision card in the web application, a state of a signal in several dimensions,
values that are marked instead of removed, operators on the processor that combine several series into one series
(`agg.sum`, the pair methods; `align.vector` runs there), revisions for late data, the four records as events, and
any comparison of strategies outside the twin. The guide in the web application (the switch "Guide") shows seven
steps: System, Signals, Stock, Bind, Prepare, Run and Model. It has no step for the decision, for comparing the
options or for judging the effect.
