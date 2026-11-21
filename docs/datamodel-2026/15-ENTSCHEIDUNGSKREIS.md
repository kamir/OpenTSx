# OpenTSx: Decision, streaming contract and effect loop (revision of the plan)

> As of 2026-10-13. **Decided on 2026-10-13** with two changes (D57, D62; section 11). This document processes an
> external review briefing (engineering and complex systems) on the method guide and on the plan from
> [14](14-ENGINEERING-FLOW.md). It changes the plan from the delivery stage after L3 on and sharpens the contracts. Of
> this, the following is implemented: the correction of the H2 example; role, meaning and operating range in the
> system document and in the details of a signal; an id for clean episodes; two schema drafts with an example.
> Everything else follows in the stages, in the order from D62: first L4 and L5, then E1 to E3. A second briefing
> (the annual model of a wind farm with hydrogen) is processed in section 13 and was decided on the same day (D64 to
> D66): the system plan and the real year 2025 come before E1. Work package:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md).

## 1. Summary

The briefing says: the foundation holds (expected signals, visible gaps, traceable preparation), but for a workbench
with which one **runs** a system, three things are missing: the binding reference to a decision, reliable streaming
semantics and the closed loop from the recommendation through the action to the effect. The most important sentence:
**The central unit is a verifiable decision.** Signals, flows, models and topics give it its foundation.

I consider the briefing right in the main. It has not seen the code; in three places the state is further along than
it assumes, and in one place it found a domain error in the example that none of us had noticed. Section 2 goes
through the findings one by one.

What changes:

1. **The decision card** becomes the working object on which the domain side and the technical side maintain the
   same thing (section 3).
2. **The signal gets meaning:** a role (observation, estimate, command, limit), two separate value ranges (what the
   sensor can measure, what is normal in operation) and a state in several dimensions instead of a single word
   (section 4).
3. **`clean` no longer means "meets the expectation".** Cleaning does not remove conspicuous values, it marks them.
   Raw values stay (section 5).
4. **A streaming contract** defines time, finality, lateness, duplicates, restart and revision (section 6, worked out
   in [11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), section 7a).
5. **A model is approved for a purpose,** not by its inputs being clean (section 7).
6. **Recommendation, approval, action and outcome** become records of their own (section 8).
7. **The plan** after L3 is reordered: first decision card and domain contracts, then an end-to-end streaming and
   replay path, then the action and learning loop (section 10).

## 2. The findings of the briefing, one by one

"State" says what holds in the repository today. The briefing could not know this.

| No. | Finding | Assessment | State today | Consequence |
|---|---|---|---|---|
| B1 | The entry point is the system; the business decision is missing | correct | the shell begins with System and Signals; a decision does not exist as an object | decision card (section 3), new first step of the method |
| B2 | "Clean meets the expectation" can wipe out real events | correct, and L3 does exactly that | `dq.plausible-range` turns values outside the range into a gap or clips them; the quality byte of the v2 episode stays unused, although the channel catalog defines `IMPLAUSIBLE` as "value stays" | `clean` redefined, mode `flag`, two value ranges (sections 4 and 5) |
| B3 | An exclusive signal status hides information | correct | one state per signal, next to it only the flag "out of range" | state in five dimensions, usability per purpose (section 4.3) |
| B4 | "Worst origin" is not a ranking | correct | `Origin.worst` in the twin, `Systems.origin` computes by it | origin as a derivation graph with shares; `worst` stays only as a coarse mark "contains synthetic data" (section 4.4) |
| B5 | Plausible single values do not make a plausible system | correct | the planned generator `gen.ou` produces one series on its own, from range and clock | demo generator and scenario model become separate things (section 5.3) |
| B6 | "Follows the topic" leaves lateness, duplicates, restart and correction open | partly correct | for vectors and clean series it is defined: close of a tick, `lateness`, order per series, checkpoint, restart with possible duplicates; **not** defined are event identity, revision and the information available at the time | streaming contract (section 6). The run on the lab cluster on the same day confirmed the finding: a clean signal was missing an episode, because a refused fetch swallowed records |
| B7 | Clean inputs do not prove model quality | correct | the place "Models" says only which signals are available clean | approval per analysis with range of validity, evidence and uncertainty (section 7) |
| B8 | Showing results next to measurements does not close a loop | correct | the twin computes what-if; there is no recommendation, approval or action as an object | four records (section 8) |
| B9 | H2 example: the sum of the measured turbine power is not the "available power" | **correct, domain error** | the system document bound `park.p_avail_kw` to the sum of the measured power | corrected (section 9) |
| B10 | "One writer per topic" is too coarse | already different | in the topic of the clean series "one signal, one writer" applies; this is checked only in the frontend | responsibility per logical result into the contract; check into the server (section 6) |
| B11 | The example shows continuous preparation that does not exist | was true when the guide was written | since L3 the chain of range, unit and resampling runs for one series; the sum over turbines and everything with several inputs does not run | the guide names, per step of the example, what runs |
| B12 | "Six external inputs" against "two open of eight" | correct, and the number was wrong | the wiring plan has **nine** inputs from outside; six have a signal, three have none (`offtake.valve`, `grid.p_self_kw`, `grid.p_grid_meas_kw`) | guide and example corrected |
| B13 | No universal graph editor first | already so | the place System is a table; the graph of the workbench is for a flow, not for the system | stays a rule |
| B14 | Operation without YAML and topic names | correct as a goal | bindings are written by hand in the system document; publishing from the workbench works without either | acceptance criterion of the first version (section 10) |

I adopt two points with a reservation:

* For the purpose binding of credibility the briefing refers to a NIST publication, and for watermarks and triggers
  to the documentation of Apache Beam. Both references are **not checked** here. The statements that follow from them
  stand in this document as definitions of our own, not as a quotation.
* "A better development after a recommendation does not prove its effect." This is right and has an uncomfortable
  consequence: with the data we have (a simulator scenario of six hours), no effectiveness can be shown, only the
  mechanism. Section 8.3 says what a basis of comparison would be.

## 3. The decision as the central unit

### 3.1 The decision card

A decision is a recurring question with someone who answers it. Example: "Which schedule do the electrolyzer and the
battery run in the next hour?"

Schema: [`schemas/opentsx.decision.v1.schema.json`](schemas/opentsx.decision.v1.schema.json), example:
[`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml).

| Part | Content |
|---|---|
| Question | what is decided, in one sentence |
| Responsibility | who decides, who approves, who carries out |
| Cadence and horizon | how often it is decided, for which period, by when the decision must stand |
| Aims | what is to get better, with key figure and direction |
| Levers | the ports of the system through which action is taken |
| Limits | what must never be violated (pressure, state of charge, delivery obligation) |
| Needs | which signals the decision needs, per signal with grade, maximum age and allowed origin |
| Analyses | which models or rules deliver a recommendation, each with range of validity and evidence (section 7) |
| Alternatives | the strategies that are compared, always including a simple reference |

The card is the yardstick for everything else. Whether a signal is "good enough" is answered not by the signal but by
the needs of the decision: the state of charge of the battery may be an hour old for a trend analysis, but not for the
schedule of the next quarter of an hour.

### 3.2 Three views of the same object

| View | Shows | For whom |
|---|---|---|
| **Decision** | question, aims, limits, alternatives with uncertainty, recommendation, approval | domain side |
| **System** | building blocks, states, flows, levers, delays | both |
| **Evidence** | sources, state of the data, preparation, model version, assumptions, runtime state | technical side |

The real function is the path between them: from "delivery target at risk" one click leads to the affected storage,
to the assumption and to the data source. The other way round, a technical change shows which decisions it touches.
The places of the shell stay; they are arranged under these views: System and Signals are the view "System" and part
of "Evidence", Flows is "Evidence", Models becomes "Decision".

### 3.3 Four kinds of relation

A graph that connects everything with lines claims more than it knows. The kinds stay separate, in the data and in
the picture:

| Kind | Means | Lives in |
|---|---|---|
| physical | energy or material flows from A to B | wiring plan (`wiring`) |
| data lineage | B was computed from A | Flows, provenance of the episodes |
| assumed effect | an intervention at A changes B, with a delay | model (dynamics of the building blocks), marked as an assumption |
| responsibility | who decides, approves, carries out | decision card |

A data dependency proves no effect. That the forecast reads the anemometer does not mean that the wind determines the
generation while curtailment is in place.

## 4. The signal, sharpened

### 4.1 Role

The same unit does not mean the same quantity. Every signal gets a role:

| `role` | Meaning | Example |
|---|---|---|
| `observation` | what was: measured or counted | actual generation |
| `estimate` | what could be or is assumed: computed from other quantities | available generation potential |
| `command` | what was requested: setpoint, schedule, curtailment | setpoint of the electrolyzer |
| `limit` | what must not be exceeded | grid limit, maximum pressure |
| `exogenous` | what comes from outside and is not influenced | wind, price, demand |

In addition, a sentence `meaning` that says what the quantity is. Proposals for a binding may use metric and unit;
the connection is made only when a person has confirmed the meaning.

### 4.2 Two value ranges

The first version had one range per signal and called it "expected". These are two different questions:

| Range | Question | Violated means | Source |
|---|---|---|---|
| `min`, `max` (plausibility) | Can the sensor deliver this value? | the **measurement** is in question | channel catalog |
| `operating` (operation) | Is this value to be expected in normal operation? | the **system** is in a special state; the measurement can be entirely correct | system document |

A gust of 28 m/s lies inside the plausible range of the anemometer (0 to 60) and outside the operating range. It is
the most valuable information of the day and must not become a gap.

### 4.3 State in dimensions

Instead of one word, five items, each computed on its own:

| Dimension | Values | From |
|---|---|---|
| Availability | open, bound without data, data there | system document, TS inventory |
| Validity of the measurement | share of questionable values (outside the plausibility, marked by the sensor) | quality byte of the episodes |
| Operating state | in the operating range, outside since when | value range `operating` |
| Freshness | age of the last value against the clock | TS inventory, running flows |
| Preparation | raw, clean (running or stopped), features | topics `clean`, `features`, state of the flows |

What a person sees is one sentence per purpose, derived from the needs of a decision: "Usable for the trend analysis.
Blocked for the schedule: last state of charge 40 minutes old, 5 are allowed." The Signals list still shows a next
step per row; it follows from the first dimension that does not fit.

### 4.4 Origin as derivation

`Origin.worst` turns a sum of nine measurements and one estimate into an "estimate", and a faulty measurement into a
"measurement". In future:

* The origin of a derived signal is the **path**: which signals, which flow in which version, which models. For this
  the episodes already carry `flow` and `specHash`; the inputs are missing.
* Three separate items are shown: the **share by origin** of the inputs (for example "9 of 10 inputs measured, 1
  estimated"), **quality** (dimensions from 4.3) and **uncertainty**, where a model states one.
* One mark stays, because it must never be missing: "contains synthetic data" as soon as any input is generated. It
  is a warning, not a rating.

## 5. What "clean" means, and what simulation achieves

### 5.1 Cleaning marks, it does not remove

New definition:

> A clean series lies on the clock and in the unit of the system, and every value carries a verdict on the
> measurement. It is not made clean by fitting into the expected range.

Consequences:

* The operator for the plausibility range gets the mode `flag`, and it becomes the default: the value stays, the
  quality byte becomes `IMPLAUSIBLE` (the channel catalog has always said so). `gap` and `clip` stay selectable and
  are named in the user interface after what they do: "Discard values", "Clip values".
* Resampling counts per window how many values were questionable and passes this on in the quality byte. A one-minute
  mean of three good and 57 questionable values is not a good one-minute mean.
* The operating range never triggers a correction, only an event.
* The raw series stays unchanged in the raw topic. This is already true today.

### 5.2 A clock is a view, not a law of nature

One-minute means, meter readings, price intervals and switching operations need different preparation. The clock
therefore belongs to the grade `clean` **of a signal for a purpose**, and the kind of the signal (`ANALOG`, `STATUS`,
`COUNTER`) decides which aggregation is allowed: mean for analog values, last state or duration per state for states,
difference for counters. The processor refuses a mean over a counter.

### 5.3 Demo generator and scenario model

| | Demo generator | Scenario model |
|---|---|---|
| Produces | one series on its own, from range and clock | several quantities together, from assumptions and dependencies |
| Knows | nothing of the rest of the system | couplings (wind, price, demand), storage dynamics, balance boundaries |
| Good for | try out the user interface, test the wiring | compare strategies |
| Statement | "this is what data would look like" | "under these assumptions the system behaves like this" |

A seed proves repeatability, not realism. The twin is already a scenario model for the plant side (balances, ramps,
pressure). One is missing for the outside influences; `gen.ou` for the price is a demo generator and is named as such. A
result that rests on a demo generator is blocked for the comparison of strategies.

## 6. The streaming contract

The definitions are given in detail in [11](11-SMART-TOPIC-PROZESSOR-DEVOPS.md), section 7a, with the state per point.
Here are the principles:

1. **Four times.** Event time (when it happened), arrival time (when it arrived), and for forecasts issue time and
   target time. A historical decision sees only what had arrived at that time.
2. **Provisional and final.** Every result says whether it is final. If data arrives after the close, a **revision**
   with a reference to the earlier result is created. Nothing is overwritten silently.
3. **Identity.** Every result has an id made from what it logically is (signal, grade, period, version of the flow).
   If the same thing is written again after a restart, the reader recognizes it by the id.
4. **Responsibility per logical result,** not per topic: a signal in one grade has one flow that writes it. The
   server checks this at start; the user interface is not the only place that checks it.
5. **Replay is fully described:** input data with offsets, starting state, parameters, version of flow and system,
   the information available at the time. Seed and version number alone are not enough.
6. **A new version first runs alongside.** It writes into the shadow, is compared, then switched over; the way back
   stays.
7. **Same meaning in preview, replay and operation.** Workbench and processor may be built differently; under the
   same conditions their results agree within a stated tolerance. For `align.vector` and the three cleaning operators
   this is shown by shared test vectors.

## 7. Approval of a model for a purpose

An analysis (model, rule, optimizer) declares on the decision card:

| Item | Content |
|---|---|
| Inputs | signals with grade, maximum age, allowed origin |
| Range of validity | in which range of the inputs it is checked (for example wind 3 to 25 m/s, no curtailment) |
| Evidence | what it was checked with: period, key figure, comparison with the reference, date |
| Uncertainty | how it is stated, and from which width on a result no longer holds |
| Version | version and hash |

From this follows a **usability status** per result: usable, restricted (with a reason), blocked. Blocking is
targeted: if the hydrogen demand is missing, the delivery planning is blocked, the analysis of the wind power is not.
A result outside the range of validity is shown and marked as not supported, not hidden.

Complete origin of the inputs is necessary for this, but it is no proof of quality.

## 8. The loop: recommendation, approval, action, outcome

### 8.1 Four records

Schema: [`schemas/opentsx.decision-record.v1.schema.json`](schemas/opentsx.decision-record.v1.schema.json). Every
record is an event in one topic per decision and refers to its predecessor.

| `kind` | Says | Carries |
|---|---|---|
| `recommendation` | what the analysis proposes | alternatives with key figures, chosen alternative, period of validity, information state, uncertainty, usability, versions |
| `approval` | what a person made of it | accepted, changed or rejected, by whom, with a reason |
| `action` | what was actually carried out | lever, value, time, who or what carried it out; may differ from the approval |
| `outcome` | what was observed afterwards | key figures in the period of validity, against expectation and against reference |

A manually confirmed action closes the loop. This needs no control of the plant; OpenTSx executes nothing. Fast
control loops and protection functions stay where they are.

### 8.2 Keep three causes apart

If the effect deviates from the expectation, the model may have been wrong, the action may have been carried out
differently from the recommendation, or the outside world has changed. The records allow these to be separated:
`action` against `approval` shows the deviation of the execution, the outside signals (role `exogenous`) against
their forecast show the outside world, the rest is model error.

### 8.3 What counts as evidence

"After the recommendation it was better" is not evidence. A statement about benefit needs a basis of comparison: the
same situation with the reference strategy. In the twin this works (the same inputs, two strategies). On the real
system it works only over many periods and with caution. Synthetic data can show that the loop works, **not** that a
strategy has an effect. The user interface says so at the place where the number is shown.

### 8.4 Shadow operation

Before a recommendation is adopted, the analysis runs along in operation without anyone acting on it. The
`recommendation` records are created, `approval` and `action` are not; `outcome` compares the recommendation with
what happened without it. Only when this holds over an agreed period is the approval offered.

## 9. The correction of the H2 example

The system document bound `park.p_avail_kw` (available power of the park) to the sum of the measured active power of
the turbines. Under curtailment this is wrong: if the command lowers the feed-in, the measured power drops, but the
potential does not. An optimizer that reads its own curtailment as a dropping wind curtails itself.

In the wiring plan the quantities are already separate: the building block `park` takes `p_avail_kw` and `limit_kw`
and gives `p_gen_kw`. Only the binding was wrong. Now:

| Signal | Role | Source |
|---|---|---|
| `park.p_gen_kw` | observation: actual generation | sum of the clean active power of the turbines (flow `park-power`) |
| `park.p_avail_kw` | estimate: generation potential | from the clean wind speed through the power curve (flow `park-potential`), origin `ESTIMATED` |
| `rules.limit_kw` | command: curtailment | wired in the wiring plan, no signal from outside |
| `park/T02/WTUR.TurSt` | observation: operating state of the turbine | bound to the topic, raw; needed for the technical availability, used by no flow |

The actual generation is thus used for the comparison with what the twin computes, not as its input. The processor
for the power curve (`model.power-curve`) and the one for the sum (`agg.sum`) are to be built; both flows are in the
document and do not run. The grid limit is missing from the wiring plan entirely and is noted as an open point.

## 10. The revised plan

L1 to L3 from [14](14-ENGINEERING-FLOW.md) are implemented. **Decided (D62): first L4 (generators, Bind and Simulate)
and L5 (Models) are finished. After that the work is replanned, and it continues with E1, E2 and E3.** The table
describes the stages after that; what L4 and L5 already deliver of them (binding in the user interface, demo
generators, a first model with a range of validity) is dropped there. Their order follows the priority of the
briefing: **one complete end-to-end slice for one decision** instead of more breadth.

| Stage | Content | Accepted when |
|---|---|---|
| **E1 Decision card and domain contracts** | read and show the decision card (three views, read-only at first); role, meaning and operating range on the signal; state in dimensions; usability per decision; bind signals in the user interface instead of in YAML; the guided mode follows the eight steps | a domain expert sees for the example decision which statements are blocked and why, and gets from there to the source; a binding is created without a file |
| **E2 End-to-end streaming and replay path** | mode `flag` and quality byte through the chain; event id and flags provisional, final, revised in the outputs; responsibility checked in the server; replay description; new version in the shadow; multiple inputs (`agg.sum`), power curve; scenario model for the outside influences | duplicates, late data and restarts cause neither double counting nor loss, shown by tests that feed in exactly that; a run can be repeated with the information available at the time; a real limit violation stays visible in the clean series |
| **E3 Action and learning loop** | the four records; compare two alternatives against the reference in the twin; confirm a recommendation; record an action; effect against expectation and reference; shadow operation | the end-to-end slice from section 10.1 runs from start to end |

### 10.1 The end-to-end slice

> A domain expert connects an example data stream, describes an operating goal, compares two alternative actions,
> confirms a recommendation and can then trace its implementation and effect.

Acceptance criteria of the first version, each with the stage that meets it:

| Criterion | Stage |
|---|---|
| Operation without YAML and without topic names | E1 |
| Every essential result number can be traced back to inputs and assumptions | E1 (evidence), E3 (result numbers) |
| Missing inputs block exactly the statements that depend on them | E1 |
| Duplicate and late events and restarts without unnoticed double counting or loss | E2 |
| Historical decisions can be reconstructed with the knowledge available at the time | E2 |
| Real limit violations stay visible | E2 |
| Curtailment, storage balances and execution delay handled correctly | E2 (curtailment in the signals), E3 (delay between approval and action) |
| A new strategy is compared against a simple reference under the same scenarios | E3 |

### 10.2 What is explicitly not built

* No universal graph editor for systems.
* No correction that removes anything conspicuous so that something turns green.
* No connection of signal and series by name and unit alone.
* No universal quality value, no ranking of origin.
* No statement about effectiveness on synthetic data.
* No control of the plant. OpenTSx recommends and logs.
* No completeness of the system as a precondition: what is missing blocks only what depends on it.
* Nothing planned is shown as something that can be played through today.

### 10.3 State after L4 and L5, replanning before E1

As of 2026-10-13. L4 and L5 are built (14, section 11). With that, a part of E1 and a piece of E2 are already there.
For each content item of the stages, the table says what is in place and what remains. **The cuts below are a
proposal; none of it is decided.**

| Stage | Already there through L4 and L5 | Remains |
|---|---|---|
| E1 | decision card is read and shown in the place Models (one view, read-only); role, meaning and operating range on the signal; usability per analysis with reasons and a jump to the signal; binding in the user interface as a working copy | state of a signal in five dimensions (D55); the two further views of the card; derivation path with shares instead of only `madeOf` (D56); the guided mode with eight steps; schema of the card checked in the build; "one signal, one writer" checked in the server; write the working copy into the file |
| E2 | power curve; id of the episodes that survives a restart | two variants of `clean` (D57) with the quality byte through the chain; flags provisional, final, revised; replay with the state at the time; new version in the shadow; multiple inputs (`agg.sum`); scenario model; generator that writes continuously; the evidence for the correction of reading after a refusal, on the lab broker |
| E3 | nothing | everything; in addition: an analysis with a model running behind it (`price-following` is only a card today), and the check of the range of validity per point in time instead of over the whole series |

Proposed cuts, one pull request each against `master`, one after the other:

| Cut | Content | Accepted when |
|---|---|---|
| E1a | state in five dimensions in the server and on the Signals page; schema of the card in the build; responsibility checked in the server | the Signals page shows per signal what is missing, separated by dimension; a second writer is refused by the server, shown by a test |
| E1b | card in three views; guided mode with eight steps; derivation path | the first acceptance criterion of E1 from the table above |
| E2a | `clean` in two variants with the quality byte; `agg.sum` | a real limit violation stays visible in `marked` and is missing in `filtered`; `park.p_gen_kw` is created from the turbines |
| E2b | flags and replay; shadow | the acceptance of E2 from the table above |
| E2c | scenario model for wind, price and demand | the three outside signals are connected in the scenario; results on it are named as scenario, not as demo |
| E3a | the four records as events; `price-following` as a running model in the twin | a recommendation is created, confirmed and is in the log |
| E3b | comparison of two alternatives against the reference; effect against expectation | the end-to-end slice from 10.1 |

Open for the owner:

1. Order. Proposal: E1a, E2a, then E1b. Reason: the dimensions and the two variants change what is in the topics and
   what the server reports; the views build on that and would otherwise have to be touched twice.
2. Scenario model (E2c) before or after E3a. Without it every comparison in E3 stays blocked (D61), because price and
   demand exist only as a demo. Proposal: E2c before E3b; E3a may run earlier.
3. Real data. As long as only simulator data exists, no analysis can reach the status `usable`. This is intended
   (10.2), but it means: the end-to-end slice ends at `restricted` until measurement data is bound.

## 11. Decisions

Decided by the owner on 2026-10-13.

| No. | Topic | Decision | State |
|---|---|---|---|
| D52 | Central unit | the decision; the decision card (`opentsx.decision.v1`) is the working object, the places of the shell are arranged under it as three views | as proposed |
| D53 | Role and meaning | every signal has `role` and `meaning`; a connection is made only after the meaning is confirmed | as proposed |
| D54 | Two value ranges | plausibility (measurement) and operation (system) separate; only the plausibility affects the validity of the measurement | as proposed |
| D55 | State | five dimensions instead of one state; what is shown is the usability per decision | as proposed |
| D56 | Origin | derivation path with shares; `worst` is dropped as a rating, the mark "contains synthetic data" stays | as proposed |
| D57 | Meaning of `clean` | **both are written:** one grade `clean` in two variants in the same topic, distinguished by the tag `variant`. `marked`: the values stay, each with a verdict. `filtered`: questionable values are removed. The default for readers is `marked`. No fourth grade | **changed** (the proposal was: marking as the default, removing selectable) |
| D58 | Streaming contract | the seven principles from section 6, worked out in 11, section 7a | as proposed |
| D59 | Model approval | per analysis inputs, range of validity, evidence, uncertainty; from these a usability status per result | as proposed |
| D60 | Loop | four records (`opentsx.decision-record.v1`); OpenTSx executes nothing | as proposed |
| D61 | Simulation | L4 builds demo generators per signal and names them as such; results on them are blocked for strategy comparisons. A scenario model follows in E2 | as proposed |
| D62 | Plan | **first finish L4 and L5, then replan and continue with E1, E2 and E3.** So L4 and L5 are not dropped; section 10 applies to the time after that and is reviewed once more before E1 | **changed** (the proposal was: E1 to E3 replace L4 and L5) |
| D63 | Example decision | "Schedule for electrolyzer and battery in the next hour" on the system `h2-chain` | as proposed |

**Consequences of the two changes.**

* D57: section 5.1 applies with one addition. The processor writes two series per clean signal. The rule for the
  series (14, section 6.1) gets the tag `variant`; the id of an episode includes it. The Signals page shows the
  variant `marked`; a flow or a model that wants the filtered series says so. This costs twice the space in the topic
  of the clean series. It is built with E2, not with L4 or L5.
* D62: L4 and L5 are built with what is already decided. For L4 this means: demo generators, named as such (D61), binding
  only after the meaning is confirmed (D53). For L5: a model gets its range of validity and evidence from the start
  (D59), even though the decision card as a user interface comes only with E1.

## 12. What stays open

* **The data.** Six hours of a simulator scenario carry the mechanism, not a statement about benefit.
* **The grid limit** and the technical availability are missing from the wiring plan of `h2-chain`.
* **The guided mode** of the user interface shows the seven steps of the earlier version of the guide until E1.
* **The cause of the lost episode** is inferred from the behavior, not from the source code of the Kafka client, and
  the evidence of the correction on the lab cluster is still outstanding.
* **Who may change a decision card,** and how it is versioned, follows D42 (file in the repository, working copy per
  account) and is as open there as for the system document.

## 13. Second briefing: the annual model

A second external briefing (2026-10-13) describes the goal as a coupled operating model of a wind farm that computes
for a whole year how wind power, battery, electrolysis, hydrogen storage and marketing work together. It is checked
against the state of the repository. The sources it names (PyPSA, windpowerlib, DWD, KNE) are **not looked up**; what
is said about them here is the statement of the briefing.

### 13.1 What matches

| Statement of the briefing | State here |
|---|---|
| The preparation is a DAG; feedback belongs in the simulator | built this way: the processor computes DAGs, the twin has the loop between electrolyzer and line |
| Every number says whether it is measured, given, estimated or simulated; simulation overwrites no measurement channels | D56; generated data has its own topic; `origin` and `madeOf` on every series |
| What is missing blocks only what depends on it | built since L5 (section 7) |
| Rolling planning is honestly free of future knowledge only if its inputs are | section 6 and E2: replay with the state at the time |
| History and live operation through the same channel contract, without a gap and without double counting | section 6; the id of the episodes survives a restart |
| A new strategy against a simple reference | D63, E3 |

Already there, and unknown to the briefing: `opentsx-market` (day-ahead prices, reBAP, market values, archive with
manifest and quality report, twins on real history), `opentsx-dwd` (station data and MOSMIX), the twin `h2-chain`
with nine building blocks, among them operating rules and direct marketing.

### 13.2 What is adopted

* **Three power quantities.** Weather-driven potential before restrictions, permitted available power after technical
  and environmental restrictions, power actually run. Today the second (`park.p_avail_kw`) and the third
  (`park.p_gen_kw`) exist; the first is added. Only with all three can one say why energy was not generated.
* **Topology of the hydrogen side.** Buffer, compressor and handover station as building blocks of their own; `p_bar`
  is split into assigned inlet and outlet pressures; `headroom_kg` belongs to a named storage; the offtaker is
  physically connected to the handover, its demand is an information connection of its own; market contract and
  physical grid export are separate. The order of compressor, buffer and line is a property of the plant, not of the
  presentation. As long as no real plant exists, the chosen order is an assumption and is in the plan as such.
* **Overlapping shutdowns** are not counted as a loss more than once: a stated allocation rule, and the overlap
  visible separately.
* **Ids of a run.** Every result carries run and scenario next to signal, unit, version and check status. Today flow,
  origin and the hash of the specification are on the series; `run` and `scenario` come with E3.
* **Fair starting and ending stocks** when runs are compared: a run must not look better because it empties battery
  and storage at the end.
* **Units for hydrogen.** kg and kg/h in the domain model; the conversion to an energy basis happens in one place,
  with a named heating value basis.

### 13.3 Objections

1. **The scope.** Five decisions, an optimizer, pressure physics, rules for bats and field work are a program, not a
   delivery stage. The end-to-end slice for one decision therefore stays the measure (D64).
2. **The optimizer.** Start times, minimum run times and standby of the electrolyzer make the problem integer; over
   35,040 steps with perfect foresight this is hard. A part-load efficiency needs a piecewise approximation. It would
   be a second computing core in Python next to the twin in Java.
3. **The loop** of schedule, pressure check and recalculation has no termination rule in the briefing. It needs an
   upper bound on the iterations and a fixed rule for how limits are tightened; otherwise it can oscillate.
4. **The year 2025 has a break.** The day-ahead price is hourly up to the delivery day 2025-09-30 and quarter-hourly
   after that. The archive keeps the original resolution; an hourly value does not become a quarter-hour measurement
   by repetition, and the origin says so.
5. **Historical forecasts.** Rolling planning needs the forecasts of that time. Whether MOSMIX for 2025 can be
   obtained retroactively is not checked. Otherwise simulated forecasts with a named error behavior remain.
6. **The power curve of L5 is a demo model.** It computes from the wind speed at the nacelle, without hub height, air
   density and park losses. For the wind of a DWD station it is not enough.
7. **Without data of the plant** every result stays `restricted`. Real weather and real prices with assumed plant
   values give a weather-driven simulation, not a reconstruction of the operation. Field work and bat protection
   would be pure scenarios.
8. **The broker in the lab** carries a year in quarter hours (35,040 values per signal), not a year in seconds.

### 13.4 Decisions

Decided by the owner on 2026-10-13.

| No. | Topic | Decision | State |
|---|---|---|---|
| D64 | Goal | **both:** the end-to-end slice for one decision stays (D52, D62). The real year 2025 is brought forward as data and comes before E1, so that the further stages run on real outside data | **changed** (the proposal was: the year as a scenario model in E2c) |
| D65 | Optimizer | later, as a further analysis of the decision card after E3, with an export of the system (network as NetCDF, plus a manifest with mapping, units, data states and versions). Until then the twin computes. The pressure check stays in the twin | as proposed |
| D66 | Order | **the system plan first:** topology and third power signal from 13.2 before everything else; after that E1a | **changed** (the proposal was: E1a, E2a, E1b) |

**Consequences.** D64 and D66 put two stages before E1. Their order among themselves is not decided on its own; the
choice is: first the plan, then the data, because the plan defines which signals exist and what the year is bound to.

For S1 the owner decided three things on the same day:

| No. | Topic | Decision |
|---|---|---|
| D67 | Order on the hydrogen side | electrolyzer (30 bar), compressor, buffer (50 to 200 bar), line (20 to 40 bar), handover station (at least 20 bar), offtaker. An assumption as long as no real plant exists; it is in the plan as such |
| D68 | Depth of the physics in S1 | simple and named: mass balance and ideal gas for buffer and line, compressor power from pressure ratio and efficiency, pressure loss as a simple function of the flow rate. Every simplification is stated in the building block. Real gas and performance maps follow with the check model after E3 |
| D69 | The previous plan | is replaced and is kept as a file for the tests of the twin |

| Stage | Content | Accepted when |
|---|---|---|
| **S1 System plan** (**implemented** 2026-10-13, with limits: [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md), 014.13) | twin and system document `h2-chain` in a new version: buffer, compressor, handover station; separate pressures; `headroom_kg` at the storage; demand as an information connection; market contract separate from the grid export; signal for the weather-driven potential; the assumption about the order noted in the plan | the plan has no input from outside without a signal; the twin runs through its tests with the new plan; the system diagram shows the new building blocks |
| **J1 The year 2025 as data** | prices and weather of the year 2025 from the archive as series in the topic, bound to the signals of the system, with origin `REPORTED` and `MEASURED`; the break of the resolution in the price and the days of the clock change checked; the source of the wind named (station, height) and marked as not transferred to hub height | `market.price_eur_mwh` has data for 2025 with origin `REPORTED`; the Signals page shows coverage and gaps of the year; no value is invented to close a gap |
| after that | E1a, E2a, E1b, E2b, E2c, E3a, E3b from 10.3. E2c shrinks to what J1 does not deliver (demand of the offtaker, connection of the outside quantities) | as in 10.3 |

### 13.5 J1, first step: the year 2025 in the archive

Loaded on 2026-10-13 with the existing tools (`bin/opentsx market weather-backfill` and `backfill`) into an archive
outside the repository. None of it is in the repository and none of it is in the broker.

| Series | Station | Values | Coverage | Gaps | Origin |
|---|---|---|---|---|---|
| Wind speed, hourly mean | Greifswalder Oie 01759 (wind farm, 23 km, measuring height 10 m) | 8727 | 99.62 % | 2 (33 h) | `MEASURED` |
| Air temperature, hourly | Greifswalder Oie 01759 | 8726 | 99.61 % | 3 (34 h) | `MEASURED` |
| Global radiation, 10 minutes | Arkona 00183 (42 km) | 52546 | 99.97 % | 2 (2.3 h) | `MEASURED` |
| Wind and temperature | Marienberg 03166 (house) | 8753 each | 99.92 % | 1 (7 h) | `MEASURED` |
| Global radiation | Fichtelberg 01358 (house) | 52493 | 99.87 % | 4 (11.2 h) | `MEASURED` |
| Wind and temperature | Kiel-Holtenau 02564 (boat) | 8757 and 8756 | 99.97 % and 99.95 % | 1 (3 h) and 2 (4 h) | `MEASURED` |
| Global radiation | Dörnick 06163 (boat) | 52541 | 99.96 % | 3 (3.2 h) | `MEASURED` |
| Day-ahead price DE-LU (SMARD) | n/a | 15390 | 100 % | 0 | `REPORTED` |

The gaps are shown and not filled. The price has the expected break: 6550 hourly values up to the delivery day
2025-09-30, then 8840 quarter-hourly values. Both days of the clock change are complete.

**A run over the year** is possible with the twin on history
(`bin/opentsx twin --history <archive> --year 2025 --wind dwd`): 365 days in quarter-hour steps, wind of the station
01759 through the wind model of `opentsx-market` (height transfer from 10 m to 120 m with the exponent of the site,
then power curve), price from the archive. The park reaches 36,078 MWh, which is 34 percent of its rated power as an
annual mean. The run computes with the plan in the **first** version (without compressor, buffer and handover station)
and a dispatch rule by price limit; it carries the line "Modelled, not proven" itself.

**What is still missing for J1.**

1. **The series in the broker and on the signals.** **Done (D76):** `bin/opentsx market publish` writes archived
   series as raw episodes into the episode topic of a domain, in batches with a pause, and stops at the first batch
   the broker does not acknowledge. On 2026-10-13, 1463 episodes of the year 2025 went into the lab broker (wind in
   10-minute resolution, temperature and air pressure of the station 01759, day-ahead price); two batches needed a
   second attempt, all were acknowledged. The system document (version 4) binds the price to this series (origin
   `REPORTED`) and lists the three weather series as signals of the site. The six further weather quantities are in
   the archive and not in the broker; they come when a rule reads them.
2. **The period.** **Done (D75):** the scenario takes place on 2025-10-01, the first delivery day with a quarter-hourly
   price. The series of the turbines in the broker are still those of the simulator from 2026-10-01. So that this
   does not make every decision stale, the approval now measures the age of an input against the newest data of what
   the decision needs, and no longer against all series of the system. The inputs of the scenario itself are still
   invented; a run does not bind to the series of the signals yet.
3. **The quantities.** At first three were loaded: temperature, wind speed as an hourly mean, global radiation.
   **Added (D77):** the fetch now also knows wind direction, air pressure, precipitation, humidity, gusts (highest
   gust of the hour) and wind in 10-minute resolution. For the station of the wind farm (01759) they are loaded for
   2025, coverage 99.60 to 99.74 percent, 2 to 4 gaps per series. The daily fetches in the cluster still get only the
   first three quantities; the new ones are requested with `--stations <id>:<quantity>`.
4. **The plan.** The year run computes with the first version of the plan. The second version (S1) computes in
   minutes over six hours; a year in minutes is 525,600 steps. **Built (D78, 2026-10-14):** the year run computes the
   second version in quarter-hour steps (35,040 steps). Two parameters differ from the minute plan: the electrolyzer
   may jump by its rated power in one step, and the controller of the buffer has half the gain (10 kg/h per bar),
   because the line pressure comes back a quarter of an hour later. Result 2025 with the wind of the station and the
   dispatch rule "always": no protective shutdown, the buffer between 93 and 119 bar, the line between 27.0 and 31.7
   bar, no shortfall, 271 MWh for the compressor, 434 t of hydrogen. The dispatch rule and all plant sizes are
   assumptions.
6. **The potential from the wind of the station.** **Built (2026-10-14):** the operator `model.wind-shear` transfers
   the wind of a measuring height to the hub height by the power law, in the processor and in the workbench. Two
   flows run on the lab system: the clean wind of the station (52,360 values) and from it the potential of the park
   (height transfer from 10 m to 120 m with the exponent 0.11, then the power curve of four turbines with 3 MW each).
   The potential of the year 2025 ranges from 0 to 12,000 kW and is called estimated, made from measured data.
7. **One series, two origins.** The earlier flow for the potential (from the simulated wind of one turbine from
   2026-10-01) and the new one write the same series, because the identity of a derived series follows only from
   system and signal. The 350 values of the old flow stay in the log and are appended after the end of the year 2025;
   `madeOf` names both origins. Consequence: the potential ends on 2026-10-01, the price on 2025-12-31, and the approval
   reports the price as stale. **Decided on 2026-10-14 (D84): the flow belongs to the identity of a derived series.**
   Every series that a flow writes carries the tag `flow`; the view of a signal shows the series of the running flow
   and names the others. The series from before (without the tag) stays in the log and is no longer counted as soon
   as a flow of the signal runs with the tag.
8. **The same with generators.** A demo generator writes every run into the same raw series of its signal. The demand
   of the offtaker is therefore in one series with values from 2026-10-01 and, since 2026-10-14, from October 2025. A
   flow that cleans it skips the values from 2025, because they come after those from 2026 in the log. Consequence:
   the approval still measures the age against the demand of 2026 and considers price and potential stale.
   **Built (D85, 2026-10-14):** the run of a generator belongs to the identity of its series (tag `run`, from signal,
   generator, seed, parameters and period). The same run replaces itself, a different period gives a series of its
   own. The view of a signal counts the run that was generated last (label `generatedAt`) and names the others.
5. **Historical forecasts do not exist.** The DWD keeps only the latest MOSMIX runs on its server; the archive of the
   raw files starts on 2026-10-12. Rolling planning for 2025 can therefore compute only with simulated forecasts whose
   error behavior is named.

**Decided by the owner on 2026-10-13.**

| No. | Topic | Decision | State |
|---|---|---|---|
| D75 | Period | the scenario of the hydrogen chain is moved to a day in the year 2025, so that demo, weather and price fit together | as proposed |
| D76 | Path of the data | the series of the year 2025 are **written into the lab broker** and bound to the signals | **changed** (the proposal was: runs directly from the archive, the broker stays untouched) |
| D77 | Weather quantities | the six missing quantities are added **now** | **changed** (the proposal was: with the rules that need them) |
| D78 | Plan for the year | the second version of the plan (S1) computes the year in quarter-hour steps; before that it is checked whether controllers and ramps stay stable at this step | as proposed |
| D79 | Language | the user interfaces stay as they are: web application English, Studio German | decided |

Open after this briefing: site and coordinates, characteristic values of the plants, pressure ranges of the hydrogen
side, the offtake profile, the conditions for import, export and marketing. Without them the values of the plan are
assumptions and are kept as such.
