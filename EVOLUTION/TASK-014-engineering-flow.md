# TASK-014: Engineering flow from scenario to model

## Task Metadata
- **Task ID**: TASK-014-engineering-flow
- **Created**: 2026-10-13
- **Status**: L1 to L5, S1, 014.16 and 014.17 implemented; plan decided after the review briefing on 2026-10-13;
  014.10, 014.11, 014.12, 014.14, 014.15 and 014.18 open
- **Priority**: High
- **Epic**: P7e Personal Service Layer / Studio
- **Delivery branch**: `opentsx/2026-task014-notebook-3` (notebook 3, check a file, 014.18), worktree
  `../OpenTSx-worktrees/notebook-3`
- **Earlier delivery branches** (merged): `opentsx/2026-task014-notebooks-2-4` (notebooks 2 and 4, PR #194, squashed), `opentsx/2026-task014-generator-identity` (D85, runs on the signals, PR #192, squashed), `opentsx/2026-task014-flow-identity` (D84, D78, PR #191), `opentsx/2026-task014-notebook` (N1 and the potential from the station wind, PR #190), `opentsx/2026-task014-dwd-2025` (J1, PR #189), `opentsx/2026-task014-demo-ui` (demo made showable, run service, PR #188), `opentsx/2026-task014-system-plan` (S1, demo draft, PR #187), `opentsx/2026-task014-clean-signals` (L3, L4, L5, replanning, PR #186), `opentsx/2026-task014-engineering-flow` (concept, approval, L1, PR #182),
  `opentsx/2026-task014-shell` (L2, PR #185)
- **Concept**: [`docs/datamodel-2026/14-ENGINEERING-FLOW.md`](../docs/datamodel-2026/14-ENGINEERING-FLOW.md)

## Objective
Studio (the system from above), workbench (series from below) and Flow Composer (operation) become places of one
flow: define the system, expect measured quantities, connect or simulate, clean raw data, operate, model. At the
end there is a declarative description of the whole system (`opentsx.system.v1`) and of the time series processors
(`opentsx.processor.v1`: generators, transformers, models), connected to the user interface and the workbench.

## Approach
First concept and draft, then review (engineering, UX), then approval by the client, then implementation in the
delivery stages L1 to L5 of the concept. The approval was given on 2026-10-13.

## Subtasks
- [x] 014.1 Concept and draft (as of 2026-10-13): the signal as the key, two schemas, examples for `h2-chain`
      (system, three processors, two flows)
- [x] 014.2 Review engineering and UX (as of 2026-10-13), findings worked in: second version of the concept
- [x] 014.2a Method guide in the user manual (as of 2026-10-13):
      [`docs/user-manual/16-engineering-method.md`](../docs/user-manual/16-engineering-method.md), in English like
      the manual. Seven steps, rules, example, checklist; per step, what works today and what is planned.
      Chapter 14 brought up to date along the way (Flow Composer, workbench, CI)
- [x] 014.3 Approval of the decisions D40 to D50 (2026-10-13): nine as proposed; **D43 changed** to three
      grades (`raw`, `clean`, `features`); **D50 extended** by an optional guided mode with numbered
      steps. Consequence: D51, one topic for features per domain (not decided separately). Concept, schema, example
      and guide are updated to match
- [x] 014.4 L1 (as of 2026-10-13)
      - System documents live under `systems/` (`h2-chain.system.yaml`, `flows/`, `processors/`), no longer as
        examples under `docs/`
      - `opentsx-retrieval`, package `system`: `SystemDoc` loads the document with wiring plan, catalogs and
        inventory and collects errors (port missing, unit differs, channel or asset unknown, flow does not write the
        grade, duplicate id and more); `Systems` computes per signal the source, origin, state and next step from
        the TS inventory
      - Routes `GET /v1/systems` and `/v1/systems/<id>/signals` (`opentsx.signal-status.v1`); flags `--systems`
        and `--systems-root`; the image brings the documents along (`deploy/docker/stage.sh`)
      - Check in the Maven build: both JSON schemas against the documents, 15 targeted error cases, states, routes
      - User interface: page "Signals" (`/systems`), grouped by building block and asset, with filters and details;
        "Prepare" and "Open on scope" open the workbench with a context line and the expected value range on the
        screen; a question before an unsaved flow is replaced
      - Limits: the states "running" and "stale" do not exist yet (nothing runs); Bind, Simulate, Generate
        and Start are visible but locked, with a hint at the delivery stage; the binding is written into the
        document by hand; write form of the flows (`opentsx.flow.v1`) without a schema
- [x] 014.5 L2 (as of 2026-10-13)
      - Shell under `/systems/<id>/<place>`: a rail with the four places and the workbench as a tool, one number per
        place; header with the choice of system; status line with version and hash
      - Guided mode (switch "Guide", remembered per browser): the seven steps of the method with the state of the
        system per step, the next sensible step highlighted
      - Place System: route `GET /v1/systems/<id>/plan`; per building block the inputs with what feeds them. For
        `h2-chain` this shows a real finding: of nine inputs from outside, three have no signal
        (`offtake.valve`, `grid.p_self_kw`, `grid.p_grid_meas_kw`)
      - Place Flows: the Flow Composer, filtered to the system. A flow that is built from a signal remembers
        system and signal; flows without a system count in through their domain
      - Place Models: preview of which signals are present in clean form and which are missing
      - Labels: "Workbench" and "Flows" instead of "Editor" and "Composer page", "steps" instead of "buckets", one
        state column in the Composer, "Align series to a common clock"
      - Deviations from the draft: place System as a table instead of an embedded prototype; "Input bucket" and the
        tab "TS-Model" stay (concept, sections 8.4 and 8.5)
- [x] 014.6 L3 (as of 2026-10-13)
      - `TopicLayout`: topics `clean`, `synthetic`, `features` per domain
      - `opentsx-retrieval`, package `clean`: `CleanChain` (plausibility range as a gap or clamped, unit
        conversion, resampling to windows on the epoch grid) and `SeriesStream` (per raw series one clean series
        with the tags `system` and `signal`, labels `grade`, `origin`, `flow`, `specHash`; state for the restart)
      - `opentsx.dag.v2`: sink for series (`schema: opentsx.episode.v2`, `signal: {system, id, grade}`).
        `SeriesJob` and `DagPlan` read it; the processor runs vector and series sinks with the same runner
        (checkpoint, status, own producer per sink)
      - The retrieval server also reads the topic of the clean series; `Systems` finds a clean series by its tags,
        even when the system document does not declare it
      - Workbench: context line with "Publish as clean signal" (one step instead of three: check, save, start),
        operators prefilled from the expectation of the signal, windows on the epoch grid as in the processor; a
        saved flow brings its signal back along
      - Shared test vectors `docs/datamodel-2026/testvectors/clean-ops.v1.json` for Java and TypeScript, as a whole
        and as a stream in arbitrary pieces
      - **Finding during the run on master2:** the clean signal lacked the first of 36 episodes. Very likely
        cause: when the broker refuses a partition, the Kafka client throws the refusal from the middle of a fetch
        and loses the records already fetched from the other partitions, whose read positions have already moved
        on. The source code of the client does not prove this; only the behavior does. Fixed in `Patience`: after
        a refusal, back to the positions before the fetch; this holds for the processor, the retrieval feed and
        the statistics. The proof on master2 is outstanding, because the broker then refused reading and writing
        for minutes
      - Limits: before a series sink the processor runs exactly three operators; one chain, no merging of several
        series (so the sum over turbines does not run); "Publish" writes nothing into the system document (D42
        open); the lock "one signal, one writer" is checked by the retrieval server (409 conflict), `vectors deploy`
        and the vector processor, and by the workbench before it deploys
- [x] 014.6a Review briefing processed (as of 2026-10-13): external review (engineering and complex systems) of the
      guide and the plan. The result is
      [`docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md`](../docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md): the
      decision as the central unit, the signal with a role and two value ranges, `clean` as marking instead of
      removing, a streaming contract, model approval per purpose, four records for the loop, a new plan.
      Findings and what became of each: below, section "Review briefing"
      - implemented at once: correction of the H2 example (actual generation and available power are two
        signals; system document version 2 with ten signals and five flows); `role`, `meaning` and `operating` in
        the schema, in the server and in the details of a signal; an id for clean episodes, so that a restart does
        not count twice; schema drafts `opentsx.decision.v1` and `opentsx.decision-record.v1` with an example,
        checked in the build; streaming contract in 11, section 7a, with "Today" and "Target" per point; model
        approval in 05, section 5a; guide (chapter 16) rewritten to eight steps
- [x] 014.7 Decisions D52 to D63 (2026-10-13): ten as proposed. **D57 changed:** `clean` is written in two
      variants, `marked` and `filtered`, in the same topic. **D62 changed:** first finish L4 and L5, then replan
      and continue with E1 to E3
- [x] 014.8 L4 (as of 2026-10-13)
      - Demo generators `gen.ou` and `gen.sine` (`opentsx-retrieval`, package `generate`): the value at a point in
        time depends only on the start value, the parameters and that point in time; episodes on a fixed grid with
        an id, so that the same period, generated again, replaces itself. Labels `origin: SYNTHETIC`,
        `fidelity: demo`
      - `POST /v1/systems/<id>/generate` in the retrieval server (write route, needs the run token; writes only to
        `tsx.<domain>.synthetic.v2`); the feed also reads the topic
      - Working copy (D42): table `signal_bindings` in the SaaS backend, per account and system; routes
        `GET/PUT/DELETE /api/v1/systems/<id>/bindings` and `POST .../generate` (administrators only, passes the
        working copy along). The retrieval server lays bindings from the body over the document
        (`POST /v1/systems/<id>/signals`) and marks them
      - Page Signals: Bind (proposals from the TS inventory by unit and value range, choose the origin, confirm the
        meaning, D53), Simulate (generator prefilled from the expectation, named as a demo, D61), Generate (period
        prefilled with the data period of the system); a hint at the working copy with "Copy as YAML"
      - Series written by rule now carry the tag `grade`
      - Limits: generators run once over a period, not continuously; the working copy is not written into the
        file; the lock "results on demo generators are not fit for strategy comparisons" exists as a label
        (`fidelity: demo`) and is checked only once there are comparisons (E3); the SQL store of the working copy
        has no test of its own (the routes are tested with an in-memory store)
- [x] 014.9 L5 Models (2026-10-13, with limits)
      - Processor: operators `model.power-curve` (power curve as support points, linear in between),
        `feature.lag` and `feature.rolling` in the chain for series; a sink may read the clean series of a signal
        and write into another signal (`signal.metric`, `origin`, `madeOf`), or features into
        `tsx.<domain>.features.v2` (tag `feature`); shared test vectors for Java and TypeScript (14 cases)
      - Workbench: "Publish as another signal" for a context with a clean series; the three operators in the
        toolbox
      - Model approval per purpose (D59): the server reads the decision cards of the system document
        (`decisions:`) and returns per analysis `usable`, `restricted` or `blocked` with reasons
        (`GET` and `POST /v1/systems/<id>/decisions`, schema `opentsx.decision-status.v1`); it checks grade, age
        against the newest data of the system, admitted origin, range of validity and evidence
      - Place Models: the cards with the needed signals, analyses and alternatives; rail and guide count along
      - Place System: the system diagram as a graph (reactflow) above the table; feedback loops dashed
      - Limits: the comparison of alternatives over a period is not built (E3); the analysis `price-following` is
        a card without an executable model behind it; the range of validity is checked against minimum and maximum
        of the whole series, not per point in time; the age against the newest data of the system, not against the
        clock; the card `h2-dispatch` is an example with invented evidence; a card has no schema in the build, only
        its draft
- [ ] 014.10 Replan, then E1 decision card and domain contracts. Replanning as a proposal in 15, section 10.3
      (2026-10-13): what L4 and L5 already deliver of E1 and E2, seven cuts, three open questions; the decision of
      the client is outstanding
      - Decided on 2026-10-13 after a second briefing (15, section 13; D64 to D66): the end-to-end slice stays,
        the system plan (S1) and the real year 2025 as data (J1) come before E1; the optimizer follows after E3 as
        an analysis
- [x] 014.13 S1 System plan `h2-chain` in a new version (15, section 13.4; 2026-10-13, with limits)
      - Decided: high-pressure buffer behind the compressor as an assumption; physics simple and named; the
        previous plan stays as a test case (`opentsx-twin/src/test/resources/twin/h2-chain-1.yaml`)
      - Twin: building blocks `wind-park` (three powers, curtailment counted per cause), `h2-compressor`,
        `h2-buffer` (with a pressure regulator towards the pipeline), `h2-pipeline` (pressure at the start and at
        the end), `h2-handover` (control valve, minimum delivery pressure, shortfall), `h2-consumer` (demand as
        information); plan with 12 building blocks and 20 connections; tests `H2ChainTest` (steady state, causal
        chain with analytic time, balance for electricity and hydrogen, compressor power, what-if, operating mode,
        curtailment per cause)
      - Studio: scenario, explanatory texts (`h2-chain.guide.yaml`) and page generated again
      - System document version 3: 13 signals, each of the nine inputs from outside has a signal (three of them
        open); `park.p_potential_kw` replaces `park.p_avail_kw` as an input, plus `park.dispatch_kw`,
        `handover.valve`, `grid.p_grid_meas_kw`; decision card version 2 with the limits at buffer and handover
      - Limits: ideal gas without a real-gas factor; single-stage compressor without intercooling and without a
        performance map; pressure loss of the pipeline as the square of the flow; the handover station delivers
        nothing below the minimum pressure; water treatment and further auxiliary units are missing; transfers
        between building blocks of the same phase arrive one step later (mass is conserved); `opentsx-market`
        still calculates with its own plan in the first version (to be switched with J1); the available power
        after restrictions is an output of the twin and no longer a signal
- [ ] 014.14 J1 The year 2025 as data (15, section 13.4)
      - 2026-10-13, first step: DWD observations 2025 of the six stations of the sites and the day-ahead price 2025
        in the archive outside the repository; coverage above 99.6 percent, gaps shown; a year run with the twin on
        history works (plan in the first version). State and five open points in 15, section 13.5. Decided the
        same day: the languages of the user interfaces stay as they are (web application English, Studio German)
      - 2026-10-13, second step (D75 to D77): scenario moved to 2025-10-01; six more weather quantities in the
        fetch and loaded for the station of the wind farm; `market publish` built and 1463 episodes written into
        the broker of the lab; system document version 4 with the price from the topic and three weather signals;
        age of a decision against its own inputs
      - open: the plan of the second version in quarter-hour steps over the year (D78); the potential from the wind
        of the station (operator for the height transfer); a run on the series of the signals
      - 2026-10-14 (D84): the flow belongs to the identity of a derived series; the view of a signal shows the
        series of the running flow
      - 2026-10-14 (D78): year run with the second version of the plan in quarter-hour steps; finding: a generator
        writes each run into the same series (15, section 13.5, point 8)
      - 2026-10-14 (D85): the run of a generator belongs to the identity of its series
      - 2026-10-14: runs on the series of the signals (`inputs: "signals"`), with their own reference on the same
        inputs; a forecast on the signals is still missing
- [ ] 014.18 Notebook workplace: draft in [17](../docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md)
      (2026-10-13): three activities, two ways to the data, four notebooks, stages N1 to N3; decided D80 to D82
      - N1 built and hooked into the stack of the demo (2026-10-14): package with inventory, systems, signals, runs
        and topic reader; notebook 1; image `opentsx/notebook-content`; patch and network rule under
        `deploy/k3s/notebook/`. Open: notebooks 2 to 4, accounts at the retrieval server, D83
      - 2026-10-14: notebooks 2 (a signal) and 4 (ask the twin) built, run against the lab system and hooked into
        the notebook of the stack (image `3.0.0-notebook-2`)
      - 2026-10-14: notebook 3 (check a file) with an example file; image `3.0.0-notebook-3`
      - J1 continued on the same branch (2026-10-14): operator `model.wind-shear`; system document version 5 with
        the flow `site-wind-clean` and the potential from the wind of the station; both flows started on the lab
        system. Finding: two flows write the same derived series (15, section 13.5, point 7)
- [ ] 014.15 Demo of the hydrogen chain and simulation experts as agents: draft in
      [16](../docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md) (2026-10-13): demo in eight acts, five roles,
      tool contract, 15 gaps. Decided (D70 to D73): demo A first, five roles defined at the provider, after S1
      first roll out demo A and play it through.
      First run on 2026-10-13 with nine findings (16, section 7); the next stage is to be decided after that
- [x] 014.16 Make demo A showable (2026-10-13): findings 1, 2, 4, 5, 6, 8 and 9 from 16, section 7
      - Place System: diagram in the phases of a time step, electricity, hydrogen and information in three colors,
        assumptions of the system document (`assumptions`), working copy in the diagram, link into the Studio
      - Studio under `/studio/` in the frontend image; scenario with a restriction (04:00 to 05:00) and a dispatch
        command
      - found and fixed: a signal from the working copy could not be opened in the workbench; a generated series
        (topic `synthetic`) could not be cleaned
      - Lab system prepared: price generated, demand cleaned, potential published, events of the new run in the
        topic. Both analyses stay `blocked`, because price and demand come from demo generators and the decision
        accepts only reported values or forecasts (D61)
- [x] 014.17 Run service and run register (16, section 4, points 1 and 2; 2026-10-13, with limits)
      - Server: `Runs` (read the scenario, compute a run, keep, read, compare), routes under
        `/v1/systems/<id>/scenario` and `/runs`; tests `RunsTest`
      - User interface: place "Simulate" (question on the left, run next to the reference on the right, events,
        origin of the numbers)
      - Limits: a run calculates with the inputs of the scenario, not with the series of the signals, and is
        therefore always `restricted`; the register lives in memory (a hundred runs, gone after a restart);
        operating modes exist only for the electrolyser; no record entry; the roles use the service through its
        interface, a tool contract of their own with rights is missing
- [ ] 014.11 E2 End-to-end streaming and replay path (including the two variants of `clean`)
- [ ] 014.12 E3 Action and learning loop

## Review briefing (external, 2026-10-13)

A review from the point of view of engineering and of complex systems, based on the guide, without access to the
code. Core statement: the central unit should be a verifiable decision. The full table is in
[15](../docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md), section 2; here the short form.

| Finding | Assessment | What became of it |
|---|---|---|
| The reference to a decision is missing; the entry point is the system | correct | decision card, new first step (D52, E1) |
| "Clean meets the expectation" can wipe out events | correct, L3 does this | `clean` redefined, mode `flag`, two value ranges (D54, D57, E2) |
| One exclusive signal status | correct | five dimensions, usability per decision (D55, E1) |
| "Worst origin" is not a ranking | correct | derivation path with shares (D56, E1) |
| Plausible single values do not make a plausible system | correct | demo generator and scenario model separated (D61, E2) |
| Streaming semantics open | partly correct; confirmed the same day by a lost episode | contract with "Today" and "Target" (D58, E2); id for episodes at once |
| Clean inputs prove no model quality | correct | approval per analysis (D59, E3) |
| No closed action loop | correct | four records (D60, E3) |
| H2 example: the measured power is not the available power | correct, a domain error | corrected at once |
| "One writer per topic" too coarse | already different: one signal, one writer | the check belongs in the server (E2) |
| The example shows flows that do not run; numbers contradict each other | correct; the number of inputs from outside was wrong (nine, not six) | the guide names per step what runs |

Not checked: the two source references of the briefing (NIST, Apache Beam).

## State of the check
* The example documents are checked against both schemas, and so are the references in the system document:
  building blocks in the wiring plan, channels in the catalog, assets in the inventory, flows in the list and with a
  matching output, generators with a description and required parameters. Result since the correction of the
  example: 10 signals, 2 open, 3 processors, 5 flows, 1 decision card with 4 records, no error.
* One note remains and is intended: the inventory does not name the wind speed series for the asset
  `h2-chain/park/T02`; the system document binds it. This is the case that D47 decides.
* 13 counter-checks (each one change that the schema must refuse) are refused.
* Since L1 this check runs in the Maven build (`SystemDocTest`); it replaces the script from the draft phase. For
  `opentsx.flow.v1` there is no schema yet, only the three flows under `systems/flows/`.
* Since L1 `SystemDoc` checks ports and their units against the wiring plan (`AtomCatalog`).

## Review

Two independent reviews of the first version, both with the result "the idea holds, not ready for approval as it
is". The factual claims were checked against the code before they were worked in.

### Engineering

| Finding | Severity | What became of it |
|---|---|---|
| `VectorJob` cannot read the example flows: signal names instead of series ids, node `decode` missing, sinks must be vector topics, only the episode topic is a source | blocker | section 6: write form `opentsx.flow.v1` with signals, a translation step to the DAG, three named extensions as `opentsx.dag.v2` (D49) |
| Where the data of a grade lives could not be expressed; the state could not be computed for generator and flow | blocker | rule 6.1: one topic `synthetic` and one topic `clean` per domain, series from metric and the tags `system`, `signal` (D48) |
| `op` is not the processor id (`dq.plausible-range` becomes `dq.checks`); the example description contradicted the operator | should | field `dag` in the description; example aligned with `ops.ts` |
| `align.vector`: wrong values for `fill`, `names` missing, duration against seconds; `ui.group` not from `OpGroup` | should | example corrected; parameter type `list`, `editAs: seconds`, `ui.group` as an enumeration |
| Not describable: operators with an axis other than time (FFT, histogram), operators that need the whole series | should | output `xy`; `semantics.needs: point, window, series` |
| Vocabulary: `STATUS` instead of `STATE`, `sampling`, origin `SYNTHETIC` for simulator data in a topic, `manual` is not synthetic, `_` in the domain | should | schema and text aligned; the wind farm data of the example now carry `SYNTHETIC` |
| Two truths: `id` against `port`, binding also in the inventory, asset T05 not in the inventory, generator as a source and as a node | should | `port` is dropped (the id is the port); example on T02; generators only as a source; D47 |
| Gaps in the schema (empty expectation, topic without a selection, `PT`, enumeration without values) | should | closed, with counter-checks; the tool checks uniqueness and references |
| Order: all operator descriptions before any use | should | delivery stages redone: L1 is the smallest state from start to end; descriptions stage by stage |
| Risks: the hash does not cover references, working copy without a rule, write right of the generator runtime, the Smart Topic specification knows no signals | consider | hash over the contents of the references; section 9 names the open points |

### UX

| Finding | Severity | What became of it |
|---|---|---|
| Six numbered stages are four places and a tool; numbers claim an order | blocker | sections 3.2 and 8.1: System, Signals, Flows, Models, Workbench; Bind and Simulate as actions in Signals (D50) |
| The progress ladder is not linear, the row does not say what to do | blocker | sections 4 and 8.2: one state per signal and one next step; grouping by building block and asset |
| Handover to the workbench: a global flow is replaced, publishing takes three steps today | blocker | section 8.3: a question on unsaved work, context line, one dialog, the way back to the row |
| Simulated must be unmistakable; `manual` looked like simulation; a second origin vocabulary | should | one origin (`Origin`), `manual` with the default `REPORTED`, mark and dashed curve for `SYNTHETIC`, inherited |
| Terms: system against scenario, signal against series against channel, "Run", "stage" three times | should | section 2; "Run" is called "Flows"; grade, place, delivery stage |
| Existing labels ("Editor", "Composer page", "buckets", "Input bucket", two state columns, tab "TS-Model") | should | section 8.4, part of L2 |
| Shell: a link to the German prototype looks like a change of product | should | sections 8.1 and 8.5: structure of the Studio in React, prototype embedded without its own rails |
| The first delivery shows nothing one can act on | should | L1 contains "Open on scope" with a context line and the expected value range |
| Numbers in the head of the sketch did not match the example | consider | aligned |

Not adopted: a clickable static prototype of the whole path **before** the approval (UX). It would already be
implementation; the approval should come first. L1 delivers the first usable state.
