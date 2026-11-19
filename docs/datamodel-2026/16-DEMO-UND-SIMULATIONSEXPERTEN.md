# 16. Demo of the hydrogen chain and simulation experts as agents

> As of 2026-10-13. **D70 to D73 (section 6) were decided on 2026-10-13; D74 is open.** This document plans a demo
> on the system `h2-chain`, describes the roles of simulation experts as agents and lists what is missing for both.
> It builds on [14](14-ENGINEERING-FLOW.md) and [15](15-ENTSCHEIDUNGSKREIS.md). Work package:
> [TASK-014](../../EVOLUTION/TASK-014-engineering-flow.md), 014.15.
>
> No customer is named here: the repository is public. "The operator" means a customer who runs a wind farm with
> electrolysis; "specialist" means the operator's people from operations management, marketing and plant
> engineering.

## 1. What this is about

The specialists of an operator should be able to simulate the system with the help of assistants, without being
experts in optimization or in data streams. The provider delivers a product of three parts for this: KafScale for
the data streams, OpenTSx for signals, preparation and the decision map, and the twin as the simulator. The product
stands for the service behind it.

One sentence on what "handing it over to the AI" means here and what it does not mean:

> The assistants operate the simulator. They do not operate the plant. A person approves a recommendation, and a
> person carries out a measure (D60; 15, section 10.2).

This is not caution on principle. As long as the data is simulated, no result says anything about benefit (D61),
and an assistant that kept quiet about this would be the largest risk of the product.

## 2. The demo scenario

**Title:** "One night at the hydrogen chain: a fault, a decision, a record."
**Duration:** 20 minutes. **Audience:** the specialists of an operator. **Question of the demo:**

> At 01:00 the transfer station closes. By when must it be open again, what do we do with the electrolyzer until
> then, and when does the direct marketer learn of it?

The demo follows the guide ([manual, chapter 16](../user-manual/16-engineering-method.md)): from the decision to the
signals, to the simulation, to the recommendation, to the record. Each act says what stands today.

| Act | What the specialist does | What they see | State today |
|---|---|---|---|
| 1 The plant | opens the system diagram | twelve building blocks, electricity and hydrogen as connections, the assumption about the order on the hydrogen side; a signal with its state at each input from outside | **there** (place System, since S1) |
| 2 The data | opens the signals | per signal: meaning, origin, coverage; three signals are open; the wind is marked as simulated | **there** (place Signals) |
| 3 The decision | opens the decision map | which signals the decision needs and which analysis is blocked today, with the reason | **there** (place Models); the map is an example with invented evidence |
| 4 The question | asks the assistant: "What happens if the transfer closes at 01:00?" | the assistant starts a run and answers with times: warning 01:29, pressure limit 02:29, shutdown, extra feed-in; each number with a run id | **Calculation there** (twin, as a command line and precomputed in the Studio). **Missing:** the assistant, a run on request |
| 5 The alternatives | asks: "And if it opens again at 02:00? Or the electrolyzer throttles earlier?" | two alternatives against the reference: shutdowns, hydrogen produced and delivered, schedule deviation | **Calculation there** (what-if, operating mode `pressure-aware`). **Missing:** the comparison as a view and as a tool (E3) |
| 6 The reason | asks: "Why was energy not generated?" | potential, available and generated power; the difference split into restriction and dispatch command | **Calculation there** (since S1). **Missing:** the view |
| 7 The recommendation | asks for a recommendation | "Open the valve by 02:20, otherwise throttle from 01:30; inform the marketer at 01:30", with the status `restricted` and the sentence that the data is simulated | **missing** (E3); the status per analysis is there |
| 8 The record | approves or rejects | recommendation, approval, measure, later effect, as four entries | **missing** (E3); the format exists as a draft |

### 2.1 What the demo does not claim

* Nothing about money. The revenue in the twin is feed-in times price, without balancing energy, without hydrogen
  revenue, without costs.
* Nothing about a real plant. Sizes, pressures and the order of compressor, buffer and pipeline are assumptions.
* No optimization. The demo compares operating modes that someone sets; an optimizer follows later (D65).

### 2.2 Two stages of the demo

| Stage | Content | Needs |
|---|---|---|
| **Demo A, with what is there** | Acts 1 to 3 in the web application, acts 4 to 6 in the Studio with the precomputed runs, acts 7 and 8 as an outlook on paper | S1 rolled out on the lab system, the Studio image rebuilt; nothing else |
| **Demo B, with an assistant** | all eight acts in one window; the assistant calculates on request | section 4, points 1 to 5 |

## 3. Simulation experts as agents

A simulation expert here is a role with four things: a task, the tools it may use, what it refuses, and the form of
its answer. Five roles, because five different mistakes have to be prevented.

| Role | Task | May | Refuses | Prevents |
|---|---|---|---|---|
| **Scenario assistant** | translates the question of a specialist into a run: plan, inputs, interventions, operating modes, period | read systems, signals and plans; describe and start a run | to invent a value that is missing. If a parameter is missing, it asks or names the assumption | the question that is calculated differently from how it was meant |
| **Data checker** | checks the inputs before each run: coverage, origin, age, value range, usability for this decision | read signal state and decision status; read series | to release a run whose inputs are blocked for the question | the calculation on wrong or missing data |
| **Model checker** | checks the physics after each run: balances for electricity and hydrogen, limits, range of validity, named simplifications | read the results of a run; calculate checks | to call a result executable that breaks a limit or leaves the range of validity | the plausible looking, wrong result |
| **Operations strategist** | proposes alternatives and compares them against a simple reference, with the same inputs | start and compare runs | to rate an alternative without a reference; to present a comparison on demo data as an effect | the strategy that only looks good |
| **Reporter** | explains the result in the language of the specialist, traces each number to run, inputs and assumptions, writes the recommendation into the record | read runs and comparisons; propose a record entry | to name a number that comes from no run; to leave out the status | the number without origin |

For the operation of the platform the role exists already: the agent for KafScale (diagnosis and runbooks at the
broker). It is not one of the simulation experts and stays with the provider.

### 3.1 Rules that hold for all five

1. **Numbers come from runs.** Each number in an answer carries the id of the run it comes from. An assistant does
   not calculate in its head.
2. **The status is in every answer.** `usable`, `restricted` or `blocked`, with the reason. With `blocked` there is
   no recommendation, but the way to the missing source.
3. **Simulation means simulation.** Measured, reported, estimated, simulated: the assistant uses the word that is on
   the series.
4. **Assumptions are named, not hidden.** The simplifications of a building block belong in the answer when the
   result depends on them.
5. **The person approves.** The assistant recommends and records. It writes no setpoint to a plant, and there is no
   tool with which it could.
6. **Everything is recorded.** Question, run, answer, approval: traceable who could know what and when.
7. **When in doubt, ask.** A question back is cheaper than a run on the wrong assumption.

### 3.2 The tools the roles stand on

An agent is as good as its tools. The table is the tool contract and the list of gaps at the same time.

| Tool | Purpose | State |
|---|---|---|
| Read systems, signals, plan | know what exists and in which state | **there** (`GET /v1/systems`, `/signals`, `/plan`) |
| Read decision status | know what is blocked and why | **there** (`GET /v1/systems/<id>/decisions`) |
| Read series | look at inputs and results | **there** (`/v1/inventory`, `/v1/episodes`) |
| Bind a signal, run a generator | connect a source | **there**, as a working copy per account; generators only for administrators |
| **Start a run** | what-if and forecast with interventions and operating modes | **there** since 014.17 (`POST /v1/systems/<id>/runs`); since 2026-10-14 optionally on the series of the signals (`inputs: "signals"`), with origin and coverage per input; a forecast on the signals is still missing |
| **Read a run** | series, events, key figures of a run by id | **there** (`GET /runs/<run>`, `/series`); the register lives in memory and forgets on restart |
| **Compare runs** | alternative against reference, with the same inputs | **there** (`GET /runs/<run>/compare`), with the final levels of the storages next to the key figures |
| **Check balances** | electricity and hydrogen, limits, validity | **missing** as a tool; the checks are in the tests |
| **Write a record entry** | recommendation, approval, measure, effect | **missing** (E3); format as a draft |

### 3.3 Two places for the roles

| Place | For whom | Form | When |
|---|---|---|---|
| **at the provider** | the provider's own people, who set up the system for an operator and play through questions beforehand | role definitions, kept locally by those who use them, that use the command line and the interfaces | possible at once, with the tools that are there |
| **in the product** | the specialists of the operator | an assistant in the web application next to the system diagram and the decision map; the same roles behind one interface | after section 4, points 1 to 5 |

The first place is the test bench for the second: what the roles at the provider need in tools and rules, the
assistant in the product needs too.

## 4. What is missing

Ordered by what holds up demo B and the product most.

| No. | What is missing | Why it counts | Size |
|---|---|---|---|
| 1 | **The simulator as a service:** a run on request through an interface, with plan, inputs, interventions and operating modes; the answer carries a run id | without it no assistant can calculate; today only the command line calculates, and the Studio shows precomputed results | medium |
| 2 | **A register of runs:** run, scenario, data state of the inputs, version of plan and building blocks; series, events and key figures retrievable; comparison of two runs | without it no number is traceable, and the comparison is missing (E3) | medium |
| 3 | **The tool contract for assistants:** the tools from 3.2 as one described interface, with rights per role | without it an agent is a text generator with an opinion | small once 1 and 2 stand |
| 4 | **The guardrails, tested:** a set of questions with expected answers and runs; tests that an assistant names no number without a run, does not recommend with `blocked` and names the status | without tests each rule from 3.1 is a hope | medium |
| 5 | **The assistant in the user interface:** a field next to the system diagram and the map; the answer shows run, status and assumptions | the specialist should not have to open a second tool | medium |
| 6 | **Real data:** the year 2025 for price and weather (J1); from the operator: site, plant ratings, topology and pressure ranges of the hydrogen side, offtake profile, conditions for grid and marketing | until then every result stays `restricted`; the demo shows the mechanism, not the benefit | large, and not in our hands alone |
| 7 | **The weather model:** from the wind at a station to the potential of the park (hub height, air density, power curve, park losses) | the power curve of L5 is a demo model | medium |
| 8 | **The comparison as a view** and the four record entries (E3) | acts 5, 7 and 8 of the demo | large |
| 9 | **Economic assessment:** balancing energy, hydrogen revenue, costs, fair start and end levels | without it "better" only has a technical meaning | medium |
| 10 | **The optimizer** (D65) | at some point a specialist wants the proposal, not only the comparison | large, later on purpose |
| 11 | **Operation as a product:** tenants kept apart, real sign-in instead of the test account, installation per operator, backup, monitoring | today a lab system runs with one test account | large |
| 12 | **KafScale under load:** the broker in the lab refuses reads and writes at times; replay, retention and time queries have to be checked against the version in use | an assistant waiting for a run that never arrives is no demo | open |
| 13 | **Where the language model runs:** provider, contract, which data of the operator may leave the house, cost per question | a question of the operator that has to be answered before the first demo B | decision |
| 14 | **Licenses of the data sources:** intraday prices are licensed and are not collected; market data must not be passed on without a check | otherwise the product delivers data it must not deliver | check |
| 15 | **The first real question** of a specialist, with an acceptance criterion | without it the demo optimizes for ourselves | conversation |

## 5. Proposed order

1. **Prepare demo A:** roll out S1 and the Studio on the lab system, play through the sequence from section 2 once
   and note where it gets stuck.
2. **Set up the five roles at the provider** (3.3, first place) and try them on demo A: where is a tool missing,
   where a rule?
3. **Run service and run register** (points 1 and 2). This is also the core of E3.
4. **Tool contract and guardrail tests** (points 3 and 4).
5. **Assistant in the user interface** (point 5): demo B.
6. J1 and the data of the operator run alongside as soon as they are there.

This shifts the order from [15](15-ENTSCHEIDUNGSKREIS.md), section 13.4: there the year 2025 (J1) follows S1, and
then E1a. Whether the run service and the roles come before J1 is to be decided.

## 6. Decisions

Decided by the client on 2026-10-13.

| No. | Question | Decision | State |
|---|---|---|---|
| D70 | What does the demo start with? | **Demo A first:** with what is there | as proposed |
| D71 | Where are the roles created first? | at the provider, as role definitions | as proposed |
| D72 | How many roles? | five: scenario assistant, data checker, model checker, operations strategist, reporter. In the product later one assistant as the face, the checkers behind it | as proposed |
| D73 | What comes after S1? | **first roll out demo A** and play it through; the next stage is decided after that | **changed** (the proposal was: run service, J1 alongside) |
| D74 | Where does the language model run, and what may leave the house of the operator? | open; to be settled with the operator before demo B, until then only example data | open |

The five role definitions are not part of the repository; they stay locally with those who use them. They work with
the command line and the interfaces that exist, and they say when a question needs a tool that is missing
(section 3.2).

## 7. First run of demo A

On 2026-10-13 on the lab system, after S1 was rolled out (retrieval server and Studio with the new plan). Acts 1 to
4 were played through; acts 5 and 6 only as far as the Studio shows them precomputed.

**What holds.** The system diagram shows twelve building blocks and reports "9 inputs from outside, each with a
signal". The Signals page shows thirteen signals with origin and next step. The decision map names, per analysis,
what is missing. The Studio tells the new chain: valve closed at 01:00, warning 01:29, pressure limit in the buffer
02:29, protective shutdown, extra feed-in.

**Where it gets stuck.**

| No. | Finding | Consequence for the demo |
|---|---|---|
| 1 | The system diagram of the web application is hard to read with twelve building blocks: the path of the hydrogen cannot be seen as a chain, labels overlap, electricity, gas and information look the same. The assumption about the order is only in the plan, not in the diagram | show act 1 in the Studio (there the columns are the phases and the flows have colors) until the diagram of the web application catches up |
| 2 | The Studio cannot be reached from outside (service only in the cluster) and is not linked from the web application. Two windows, two addresses | set up a forwarding before the demo; in the long run a way from the place System into the Studio |
| 3 | The web application speaks English, the Studio German | announce it in the sequence for a German audience; one language per audience is a decision |
| 4 | Both analyses are `blocked`: the price is bound but not generated; the demand has only raw data; the potential of the park is not published | three steps before the demo: generate the price, clean the demand, publish the potential from the wind speed. **Addendum:** the three steps are done, and it stays `blocked`, with a different and correct reason: price and demand come from demo generators, and the decision accepts only reported values or forecasts (D61). The expectation "after that it says `restricted`" was wrong. It becomes `restricted` only with a reported price series (J1) and a demand of the operator |
| 5 | The series that was published as `park.p_avail_kw` before S1 no longer counts: the signal is now called `park.p_potential_kw`, and the running flow still writes under the old name | switch the flow to the new signal and restart it; a renamed signal needs a described way |
| 6 | The Studio run has a new id with the new plan. The event topic of this run is not published | publish the run to the topic once, otherwise the view "Event topic" stays empty |
| 7 | The Studio shows only precomputed alternatives (valve open again at nine fixed times, two operating modes). A question in between cannot be asked | put the questions of the demo on the precomputed ones; this is gap 1 from section 4 |
| 8 | Act 6 has nothing to show: in the demo run nothing is curtailed, so potential, available and generated power are equal | give the scenario a window with a restriction and a dispatch command, so that the three powers diverge |
| 9 | The offtaker stands on "to do" in the system diagram of the web application although its demand is bound: the mark does not tell "raw data there" from "open" | map the mark on the building block to the state of the signal, not only to "cleaned or not" |

**State of the findings (2026-10-13, work package 014.16).** Findings 1 (diagram in phases, three colors for
electricity, hydrogen and information, assumptions next to the diagram), 2 (the Studio hangs under `/studio/` of the
web application and is linked from the place System), 8 (a restriction from 04:00 to 05:00 and a dispatch command
from 05:00 to 05:30 in the scenario) and 9 (the diagram follows the working copy and tells "open" from "not yet
cleaned") are dealt with. Finding 7 is dealt with by the run service (work package 014.17): the place "Simulate"
calculates a question on request. Finding 3 is resolved: the Studio speaks English, like the web application.

Findings 4, 5 and 6 are preparation and need no code. Findings 1, 2, 8 and 9 are small pieces of work on the user
interface and the scenario. Finding 7 is the run service.
