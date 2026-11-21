# Decision cards and decision records

This page specifies the decision card (`opentsx.decision.v1`), the decision status the retrieval server derives from
it (`opentsx.decision-status.v1`), the decision record (`opentsx.decision-record.v1`) and the signal rules they rely
on, verified against commit `a513aa98`.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

The "Implemented in" lines cite these files by short name:

| Short name | Path |
|---|---|
| `Decisions.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/system/Decisions.java` |
| `SystemDoc.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/system/SystemDoc.java` |
| `Systems.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/system/Systems.java` |
| `RetrievalServer.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/RetrievalServer.java` |
| `KafScaleFeed.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/server/KafScaleFeed.java` |
| `TopicLayout.java` | `opentsx-kafka-v2/src/main/java/org/opentsx/kafka/TopicLayout.java` |
| `Generators.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/generate/Generators.java` |
| `SignalInputs.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/runs/SignalInputs.java` |
| `Runs.java` | `opentsx-retrieval/src/main/java/org/opentsx/retrieval/runs/Runs.java` |
| `SystemDocTest.java` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/system/SystemDocTest.java` |
| `SystemsTest.java` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/system/SystemsTest.java` |
| `DecisionsTest.java` | `opentsx-retrieval/src/test/java/org/opentsx/retrieval/system/DecisionsTest.java` |
| `WindPark.java` | `opentsx-twin/src/main/java/org/opentsx/twin/atoms/WindPark.java` |
| `h2-chain.yaml` | `opentsx-twin/src/main/resources/twin/h2-chain.yaml` (the twin plan) |
| `h2-chain.system.yaml` | [`systems/h2-chain.system.yaml`](../../systems/h2-chain.system.yaml) (the system document) |
| `systems.py` | `opentsx-saas-backend/app/api/systems.py` |
| `signals.ts` | `opentsx-saas-frontend/src/systems/signals.ts` |
| `ModelsPlace.tsx` | `opentsx-saas-frontend/src/systems/ModelsPlace.tsx` |
| `decision.v1` | [`schemas/opentsx.decision.v1.schema.json`](schemas/opentsx.decision.v1.schema.json) |
| `decision-record.v1` | [`schemas/opentsx.decision-record.v1.schema.json`](schemas/opentsx.decision-record.v1.schema.json) |
| `system.v1` | [`schemas/opentsx.system.v1.schema.json`](schemas/opentsx.system.v1.schema.json) |
| `processor.v1` | [`schemas/opentsx.processor.v1.schema.json`](schemas/opentsx.processor.v1.schema.json) |

Example files:

* [`systems/decisions/h2-dispatch.decision.yaml`](../../systems/decisions/h2-dispatch.decision.yaml): a card.
* [`systems/decisions/h2-dispatch.records.example.yaml`](../../systems/decisions/h2-dispatch.records.example.yaml):
  four records of one period.

Persons, figures and evidence in both are invented.

<a id="1-decision-card"></a>

## 1. Decision card

A decision card describes one recurring decision on one system: the question, who answers it, how often, what it
aims at, what it may change, what it must respect, which signals it needs, which analyses give a recommendation and
which alternatives are compared. Both schemas of this page are drafts; the build validates the example files against
them.

<a id="11-register-a-card-with-a-system"></a>

### 1.1 Register a card with a system

* A system document (`opentsx.system.v1`) lists its cards under `decisions`, each as `{id, ref}`. `id` is a slug
  (`^[a-z0-9][a-z0-9-]*$`). `ref` is a path relative to the repository root.
* `ref` MUST name a regular file below the root. Otherwise the system document has the problem
  `decision <id>: <ref> does not exist below <root>`. Without `ref` the problem is `decision <id>: ref is missing`.
* The card's `system` MUST equal the `system` of the document that lists it. Otherwise the problem is
  `decision <id> is a card of the system <system>`.
* The server keys a card by the `id` in the system document and does not compare it with the card's `decision`.
  The two SHOULD be equal.
* The text of the card file is part of the hash of the system document. A change to the card changes `hash` in
  `opentsx.signal-status.v1`.
* The server reads the card as it stands in its file and does not validate it against `decision.v1`. The build
  validates `h2-dispatch.decision.yaml` against `decision.v1`, checks that every `signal` under `levers`,
  `constraints` and `needs` is a signal of the system or a port of its plan, and checks that every alternative names
  an analysis of the card.

Implemented in: `system.v1:106-112`, `SystemDoc.java:233-242`, `SystemDoc.java:356-371`,
`SystemDocTest.java:407-430`.

<a id="12-fields-of-a-card"></a>

### 1.2 Fields of a card

No field outside this table is allowed, at the top level or in a nested object (`additionalProperties: false`).
A duration is ISO 8601 with days, hours, minutes and seconds only, for example `PT15M` or `P1D`. Years, months and
weeks are not allowed.

| Field | Type | Required | Rule |
|---|---|---|---|
| `schema` | string | yes | `opentsx.decision.v1` |
| `decision` | slug | yes | id of the decision |
| `version` | integer, at least 1 | yes | version of the card |
| `example` | boolean | no | `true`: persons, figures and evidence are invented. The place "Models" shows the mark `example` |
| `system` | slug | yes | the system the decision is taken on |
| `title` | string | no | |
| `question` | string | yes | what is decided, in one sentence |
| `responsibility` | object | yes | `decides` (required), `approves`, `executes`, each a string. `executes` names who or what carries the action out. OpenTSx does not |
| `cadence` | object | yes | `every` (how often the decision is taken) and `horizon` (the period a decision covers) are required durations. `leadTime` (how long before the period the decision must stand) is an optional duration |
| `objectives` | array, at least 1 | yes | `id` (slug), `title` and `direction` (`min`, `max`, `target`) required. `kpi` (the figure it is measured by), `unit` and `target` (number) optional |
| `levers` | array, at least 1 | yes | the ports of the system the decision acts through. `signal` required. `title`, `min`, `max` and `delay` (duration from an approved action to its effect on the plant) optional |
| `constraints` | array | no | what must never be violated. `id` (slug) and `title` required. `signal`, `min`, `max` and `note` optional |
| `needs` | array, at least 1 | yes | [section 1.3](#13-needs) |
| `analyses` | array | no | [section 1.4](#14-analyses) |
| `alternatives` | array, at least 2 | yes | [section 1.5](#15-alternatives) |

Implemented in: `decision.v1:6-254`, `ModelsPlace.tsx:67-80`.

<a id="13-needs"></a>

### 1.3 Needs

A need names one signal the decision needs and what makes its data fit for this decision. Whether a signal is good
enough is a property of the need, not of the signal.

| Field | Type | Required | Rule |
|---|---|---|---|
| `signal` | string | yes | id of a signal of the system |
| `grade` | `raw`, `clean`, `features` | yes | grade whose data the decision reads. The server reads `clean` when the field is absent |
| `maxAge` | duration | no | the longest time the data of this signal may end before the newest data among all signals the decision needs ([section 2.2](#22-evaluate-a-need)) |
| `maxQuestionable` | number, 0 to 1 | no | share of values with a questionable measurement that is still acceptable. The server does not evaluate it |
| `origins` | array of `MEASURED`, `REPORTED`, `FORECAST`, `ESTIMATED`, `SYNTHETIC` | no | origins the decision accepts. Without the field every origin is accepted |
| `for` | array of analysis ids | no | the analyses that need the signal. Without the field every analysis of the card needs it |
| `optional` | boolean | no | `true`: the need is evaluated and shown, but it never blocks or restricts an analysis |

Implemented in: `decision.v1:255-308`, `Decisions.java:104-152`, `Decisions.java:164-174`.

<a id="14-analyses"></a>

### 1.4 Analyses

An analysis is a rule, a model, an optimizer or a twin that gives a recommendation. It declares the range of its
inputs it was checked in and what it was checked with.

| Field | Type | Required | Rule |
|---|---|---|---|
| `id` | slug | yes | |
| `title` | string | yes | |
| `kind` | `rule`, `model`, `optimizer`, `twin` | yes | |
| `ref` | string | no | the file or class behind the analysis |
| `version` | string | no | |
| `validity` | array | yes | each `{signal, min, max, note}`, `signal` required: the range of an input in which the analysis was checked. An empty array states no range |
| `evidence` | array | yes | each `{what, result}` required, `period`, `against` (the reference it was compared with), `date` and `synthetic` optional. An empty array means: not validated |
| `uncertainty` | object | no | `method`, `blockAbove` (width of the interval above which a result does not carry the decision), `unit`. The server does not evaluate it |

`synthetic: true` on an evidence entry means the check ran on generated data. It shows that the mechanism works, not
that the analysis is right.

Implemented in: `decision.v1:309-416`, `Decisions.java:158-215`.

<a id="15-alternatives"></a>

### 1.5 Alternatives

* A card has at least two alternatives. Exactly one MUST have `reference: true`: the simple strategy every other
  one is compared with. The schema enforces this (`minContains: 1`, `maxContains: 1`).
* Fields: `id` (slug) and `title` required. `reference` (boolean), `analysis` (slug) and `note` optional.
* An alternative SHOULD name an analysis of its card in `analysis`. The build checks this for the example card. At
  run time an alternative that names no analysis of its card has the status `blocked`.

Implemented in: `decision.v1:201-244`, `Decisions.java:95-100`, `SystemDocTest.java:427-431`.

<a id="2-decision-status"></a>

## 2. Decision status

The retrieval server puts the state of the signals of the system next to each card and gives every analysis a
status: `usable`, `restricted` or `blocked`. A model is released for a decision, not in general. Clean inputs are a
condition, not a proof: an analysis without evidence is `restricted` however good its inputs are.

<a id="21-request-the-status"></a>

### 2.1 Request the status

`<id>` is the id of a system document the server holds.

| Request | Body | Answer |
|---|---|---|
| `GET /v1/systems/<id>/decisions` | none | `opentsx.decision-status.v1` for the system document as it stands |
| `POST /v1/systems/<id>/decisions` | JSON `{bindings: [{signal, grade, source}]}`, at most 262144 bytes | the same, with the bindings of a working copy laid over the document |

| Status | Error | Cause |
|---|---|---|
| 404 | `this server has no system documents (it needs --systems)` | the server was started without `--systems` (GET) |
| 404 | `no such system or route: <path>` | unknown system or route |
| 413 | `the request is larger than 262144 bytes` | POST body too large |

Implemented in: `RetrievalServer.java:178-181`, `RetrievalServer.java:425-444`, `RetrievalServer.java:479-500`,
`Systems.java:104-110`, `Systems.java:386-390`.

<a id="22-evaluate-a-need"></a>

### 2.2 Evaluate a need

The server first sets `asOf` of a decision: the latest `to` among the data of all its needs, optional needs
included, each in the grade of its need. Needs without data do not count. Age is measured against `asOf`, not
against the clock and not against series of the system the decision does not use: the question is whether an input
lags behind the others.

Then it evaluates every need. The order matters.

1. If the signal is not a signal of the system, the reason is `<signal> is no signal of the system`. Evaluation of
   this need stops.
2. The data of the need is the data of its grade in `opentsx.signal-status.v1`. A `raw` grade whose source is
   `manual` is a constant and counts as present without data.
3. Without data, the reason is `<signal> has no <grade> data (<state>)`, with `<state>` the state of the signal and
   hyphens written as spaces. Evaluation of this need stops.
4. Origin: the `origin` of the data, else the `origin` of the signal. When `origins` is set, an origin is known and
   it is not in `origins`, the reason is `<signal> is <origin>; the decision accepts <origins>`, in lower case and
   comma separated.
5. Age: when `maxAge` is set, the data has `to` and the decision has `asOf`, then `ageSeconds` is `asOf` minus `to`
   in seconds. Above `maxAge` the reason is
   `<signal> ends <age> before the newest data the decision needs; allowed are <maxAge>`. A time under 120 s is
   written in seconds (`90 s`), a longer one in whole minutes (`60 min`).
6. `containsSynthetic` is true when the origin of the signal is `SYNTHETIC`, the `madeOf` of the data contains
   `SYNTHETIC`, or the `fidelity` of the data is `demo`.
7. `ok` is true when the need has no reason.

The origin of a signal whose clean grade a flow writes, and that has no raw origin of its own, is the origin that
comes last in the order `MEASURED`, `REPORTED`, `FORECAST`, `ESTIMATED`, `SYNTHETIC` among its `expect.origin` and
the origins of the signals the flow reads
([SYSTEM-DOCUMENT-SPEC.md, section 1.4](SYSTEM-DOCUMENT-SPEC.md#s14-origin-of-a-signal)). `park.p_potential_kw` is
`ESTIMATED`, so its need in `h2-dispatch.decision.yaml`, with `origins: [ESTIMATED, FORECAST]`, accepts data that
has no `origin` label.

Implemented in: `Decisions.java:50-66`, `Decisions.java:104-156`, `Systems.java:270-273`, `Systems.java:410-426`,
`DecisionsTest.java:95-126`.

<a id="23-evaluate-an-analysis"></a>

### 2.3 Evaluate an analysis

A need applies to an analysis when it has no `for`, or when its `for` names the analysis. An optional need never
applies. What is missing blocks exactly the analyses that need it.

| Status | When |
|---|---|
| `blocked` | an applying need has a reason |
| `restricted` | not blocked, and at least one of: an applying need has `containsSynthetic`; an input lies outside a `validity` range; `evidence` is missing or empty; every evidence entry has `synthetic: true` |
| `usable` | neither |

`reasons` lists the reasons of the applying needs first, then these, in this order:

| Condition | Reason |
|---|---|
| an applying need has `containsSynthetic` | `an input is generated or made of generated data: the result shows the mechanism, not an effect` |
| an input lies outside a `validity` range | `<signal> holds values from <min> to <max>, outside the range the analysis was checked in` |
| `evidence` is missing or empty | `the analysis has no evidence: it was not checked against anything` |
| every evidence entry has `synthetic: true` | `the analysis was checked on generated data only` |

Validity check, for each `validity` entry:

* The server takes the data of the best grade of the signal: `clean` when it has data, else `raw`. This is
  independent of the grade the need names.
* When that data has `min` and `max`, they are reported as `observedMin` and `observedMax`. `inside` is false when
  `observedMin` is below the entry's `min` or `observedMax` is above its `max`; a bound the entry does not state is
  not checked.
* Without such data `inside` is true.
* The check covers all data the server knows for the signal, not each point in time.

An alternative takes the status of the analysis it names.

Implemented in: `Decisions.java:88-100`, `Decisions.java:158-221`, `DecisionsTest.java:51-93`,
`DecisionsTest.java:128-138`.

<a id="24-fields-of-the-status"></a>

### 2.4 Fields of the status

| Field | Rule |
|---|---|
| `schema` | `opentsx.decision-status.v1` |
| `system`, `title` | of the system document |
| `asOf` | the latest `asOf` over all decisions. Absent when no need has data |
| `decisions[]` | one entry per card, in the order of the system document |
| `decisions[].id` | the `id` in the system document |
| `decisions[].title`, `question`, `version`, `example`, `responsibility`, `cadence`, `objectives`, `levers`, `constraints` | copied from the card when present |
| `decisions[].asOf` | as in [section 2.2](#22-evaluate-a-need). Absent when no need has data |
| `decisions[].needs[]` | the need as written, plus `found` (`state` of the signal, `data`, `origin`, `ageSeconds`), `ok`, `containsSynthetic` and `reasons` |
| `decisions[].analyses[]` | the analysis as written, with each `validity` entry extended by `observedMin`, `observedMax` and `inside`, plus `status` and `reasons` |
| `decisions[].alternatives[]` | the alternative as written, plus `status` |

`found.data` has the form of the data of a grade in `opentsx.signal-status.v1`. `found.ageSeconds` is present only
when the age was computed.

The place "Models" of the web application shows this answer and does not edit the card. It shows per card the
needs, the analyses with status and reasons, and the alternatives with their status. Comparing alternatives over a
period is not implemented.

Implemented in: `Decisions.java:43-102`, `Decisions.java:104-152`, `ModelsPlace.tsx:67-114`.

<a id="3-decision-records"></a>

## 3. Decision records

A decision record is one event in the life of a decision: a recommendation, what a person made of it, what was
carried out, and what was observed afterwards. OpenTSx recommends and records. It carries out nothing.

The record format is the schema `decision-record.v1`. No component of OpenTSx writes, reads or stores records at
run time. The build validates the four records of the example file against the schema, checks that each record's
`follows` is the `id` of the record before it, and checks that no approval or action follows a recommendation in
shadow operation ([section 3.3](#33-chain-revision-and-shadow-operation)).

Implemented in: `decision-record.v1:1-375`, `SystemDocTest.java:432-441`, `SystemDocTest.java:444-454`.

<a id="31-kinds"></a>

### 3.1 Kinds

Every record MUST carry `schema` (`opentsx.decision-record.v1`), `kind`, `id`, `decision`, `period` and `at`.

| `kind` | Says | MUST also carry |
|---|---|---|
| `recommendation` | what the analysis proposes | `informationState`, `usability`, `alternatives`, `recommended` |
| `approval` | what a person made of a recommendation: accepted, changed or rejected | `follows`, `verdict`, `by` |
| `action` | what was carried out. It MAY differ from the approval | `follows`, `setpoints`, `by` |
| `outcome` | what was observed in the period | `follows`, `observed`, `final` |

Implemented in: `decision-record.v1:7-27`, `decision-record.v1:302-368`.

<a id="32-fields-of-a-record"></a>

### 3.2 Fields of a record

No field outside this table is allowed (`additionalProperties: false`). A timestamp is UTC with seconds and an
optional fraction: `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$`.

| Field | Type | Rule |
|---|---|---|
| `id` | string, at least 8 characters | identity of the record. The same logical record written twice MUST have the same `id` |
| `decision` | string | the `decision` of the card |
| `decisionVersion` | integer | the `version` of the card |
| `period` | object | `from` and `to` (timestamps), both required: the period the decision covers |
| `at` | timestamp | when the record was made |
| `follows` | string | `id` of the record this one answers: an approval answers a recommendation, an action an approval, an outcome an action or, in shadow operation, a recommendation |
| `revises` | string | `id` of an earlier record of the same kind that this one replaces because data arrived later |
| `final` | boolean | `false` while data for the period may still arrive |
| `shadow` | boolean | `true` in shadow operation: nobody acts on the recommendation |
| `informationState` | object | what was known when the record was made, enough to compute it again: `asOf` (arrival time up to which data was taken into account), `systemHash`, `inputs`, `analysis` |
| `informationState.inputs[]` | object | `signal` and `grade` (`raw`, `clean`, `features`) required. `upTo` (timestamp), `topic`, `offsets` (map of string to integer), `origin`, `flow` and `specHash` optional |
| `informationState.analysis` | object | `id`, `version`, `seed` (integer), `initialState` (reference to the state the analysis started from) |
| `usability` | object | `status` (`usable`, `restricted`, `blocked`) required. `reasons` (array of strings) and `synthetic` (true when any input is generated) optional |
| `alternatives[]` | object | `id` and `kpis` (map of name to number) required. `reference` (boolean), `uncertainty` (map of name to an array of exactly two numbers) and `violates` (array of strings) optional |
| `recommended` | string | `id` of the alternative the analysis recommends |
| `setpoints[]` | object | what is recommended, approved or carried out, per lever. `signal` and `value` (number) required. `from` and `to` (timestamps) optional |
| `verdict` | `accepted`, `changed`, `rejected` | |
| `by` | string | who approved, or who or what carried the action out |
| `reason` | string | |
| `observed` | map of name to number | figures observed in the period |
| `expected` | map of name to number | what the recommendation expected for the same figures |
| `reference` | map of name to number | what the reference strategy gives for the same period, where that can be computed |
| `attribution` | object | `execution`, `environment`, `model` (strings): why observed and expected differ, as far as it can be told apart |

Implemented in: `decision-record.v1:28-301`, `decision-record.v1:369-374`.

<a id="33-chain-revision-and-shadow-operation"></a>

### 3.3 Chain, revision and shadow operation

* The records of one period form a chain through `follows`: recommendation, approval, action, outcome. The first
  record of a chain has no `follows`.
* Data that arrives after a record was made MUST NOT change that record. A record with `revises` replaces it, and
  the earlier record stays.
* `attribution` keeps three causes apart. `execution`: the action differs from the approval. `environment`: the
  signals with the role `exogenous` differ from their forecast. `model`: what remains.
* A claim about benefit needs the reference strategy over the same period. `reference` holds its figures. A
  better figure after a recommendation alone is no evidence of an effect. When `usability.synthetic` is true the
  record shows the mechanism, not an effect.
* In shadow operation the analysis runs without anyone acting on it: recommendations carry `shadow: true`, no
  approval or action SHOULD follow them, and the outcome `follows` the recommendation and compares it with what
  happened without it. The build refuses the example file when an approval or an action follows a recommendation
  with `shadow: true`, directly or later in its chain. The example file does not show shadow operation: its
  recommendation has no `shadow`, and an approval, an action and an outcome follow it.

Implemented in: `decision-record.v1:60-75`, `decision-record.v1:153-178`, `decision-record.v1:264-300`,
`SystemDocTest.java:432-441`, `SystemDocTest.java:444-454`, `SystemDocTest.java:456-471`.

<a id="4-signal-role-meaning-and-ranges"></a>

## 4. Signal role, meaning and ranges

A decision judges the signals it needs. The system document (`opentsx.system.v1`) gives each signal what that
judgement relies on: a role, a meaning and two ranges.

<a id="41-role"></a>

### 4.1 Role

The same unit does not mean the same quantity. `role` says what kind of quantity a signal is.

| `role` | Meaning | Signals with this role in `h2-chain.system.yaml` |
|---|---|---|
| `observation` | what was: measured or counted | `park/T02/WTUR.W`, `park.p_gen_kw` |
| `estimate` | what could be or is assumed: computed from other quantities | `park.p_potential_kw` |
| `command` | what was asked for: setpoint, schedule, curtailment | `park.dispatch_kw`, `ely.setpoint_kw` |
| `limit` | what must not be exceeded | none |
| `exogenous` | what comes from outside and is not influenced | `market.price_eur_mwh`, `offtake.demand_kgph` |

* `role` is optional. When present it MUST be one of the five values. Otherwise the system document has the problem
  `<id>: role must be one of [command, estimate, exogenous, limit, observation]`.
* The server returns `role` and `meaning` per signal in `opentsx.signal-status.v1`.

Implemented in: `system.v1:188-197`, `SystemDoc.java:42`, `SystemDoc.java:395-398`, `Systems.java:234-235`,
`SystemDocTest.java:363-367`, `SystemDocTest.java:395`, `SystemsTest.java:99-101`.

<a id="42-confirm-the-meaning-of-a-binding"></a>

### 4.2 Confirm the meaning of a binding

* `meaning` is one sentence that says what the quantity is. A binding is confirmed against it, not against a name.
* The web application suggests series for a signal: the same unit first, then a value range inside the plausible
  range. A suggestion is not a binding.
* A binding of a `topic` source MUST carry `meaningConfirmed: true`
  ([SYSTEM-DOCUMENT-SPEC.md, section 7.1](SYSTEM-DOCUMENT-SPEC.md#s71-bindings)). Otherwise the backend answers 422
  with `Confirm that the series is the quantity the signal means before binding it.`
* A `generator` or `manual` source needs no confirmation.

Implemented in: `system.v1:198-201`, `systems.py:38-46`, `systems.py:138-157`, `systems.py:178-185`,
`signals.ts:568-588`.

<a id="43-plausible-range-and-operating-range"></a>

### 4.3 Plausible range and operating range

Two ranges answer two questions.

| Range | Fields | Question | A value outside means | Source |
|---|---|---|---|---|
| plausible | `expect.min`, `expect.max` | Can the sensor deliver this value? | the measurement is in question | the system document, else the channel catalog of the signal's channel |
| operating | `expect.operating.min`, `expect.operating.max` | Is the value expected in normal operation? | the system is in a special state; the measurement can be right | the system document only |

* `expect.min` MUST NOT be above `expect.max`. Otherwise the problem is `<id>: min is above max`.
* `expect.operating` MUST state at least one bound. Its `min` MUST NOT be above its `max`. Otherwise the problem is
  `<id>: the operating range has its min above its max`.
* The server reports `outOfRange` (`min` and `max` of the data) when the data of the best grade of the signal (`clean`
  when it has data, else `raw`) reaches outside the plausible range. The operating range does not produce
  `outOfRange`.
* A value outside the operating range is an event about the system. It MUST NOT be corrected or removed for that
  reason. The server returns the operating range under `expect.operating` and changes no value.

Example: for `park/T02/WTUR.W` the plausible range is -100 to 10000 kW from the channel catalog and the operating
range is 0 to 3300 kW from the system document.

Implemented in: `system.v1:223-266`, `SystemDoc.java:45-52`, `SystemDoc.java:399-433`, `SystemDoc.java:594-599`,
`Systems.java:229-233`, `Systems.java:277-282`, `Systems.java:452-456`, `SystemDocTest.java:354-362`,
`SystemDocTest.java:396`.

<a id="5-powers-of-a-wind-park"></a>

## 5. Powers of a wind park

Generated power is not available power. Under curtailment the measured power falls and the potential does not. A
signal that measures generated power MUST NOT stand for available power or for the potential. The atom `wind-park`
of the twin has three powers that say why energy was not generated:

| Power | Port of `wind-park` | Signal in `h2-chain.system.yaml` | Role | Rule |
|---|---|---|---|---|
| potential | `p_potential_kw` (input) | `park.p_potential_kw` | `estimate` | what the weather gives before any restriction or dispatch, clipped to 0 to `ratedKw` |
| available | `p_avail_kw` (output) | none | | `max(0, min(potential, limit_kw))`: what may be generated after the operating rules |
| generated | `p_gen_kw` (output) | `park.p_gen_kw` | `observation` | `max(0, min(available, dispatch_kw))`: what runs after the dispatch |

* `limit_kw` is the restriction of the operating rules, wired from `rules.limit_kw`. `dispatch_kw` is the command of
  the operator, signal `park.dispatch_kw` with the role `command`. Without a value both are `ratedKw`.
* The atom sums the energy not generated per cause: `curtailed_rules_kwh` (potential minus available) and
  `curtailed_dispatch_kwh` (available minus generated). A kilowatt hour lost to a restriction is never also counted
  as dispatched away.
* `park.p_potential_kw` is written by the flow `park-potential` from the clean wind speed of the DWD station near the
  park (`model.wind-shear`, then `model.power-curve`). Its `expect.origin` is `ESTIMATED`, so its origin is
  `ESTIMATED` and not the `MEASURED` of the wind
  ([SYSTEM-DOCUMENT-SPEC.md, section 1.4](SYSTEM-DOCUMENT-SPEC.md#s14-origin-of-a-signal)).
* `park.p_gen_kw` is the sum of the clean active power of the turbines (flow `park-power`, node `agg.sum`). It is an
  output of the plan: the measurement is compared with what the twin computes and is not an input of the twin. No
  processor implements `agg.sum`.

Implemented in: `WindPark.java:13-23`, `WindPark.java:49-59`, `WindPark.java:61-68`, `WindPark.java:88-101`,
`h2-chain.yaml:26-28`, `h2-chain.yaml:55-56`, `h2-chain.system.yaml:116-136`,
[`systems/flows/park-power.flow.yaml`](../../systems/flows/park-power.flow.yaml),
[`systems/flows/park-potential.flow.yaml`](../../systems/flows/park-potential.flow.yaml),
`SystemDocTest.java:365-367`, `SystemsTest.java:99-101`, `SystemsTest.java:103-108`.

<a id="6-demo-generators-and-scenario-models"></a>

## 6. Demo generators and scenario models

A generator writes values for a signal nobody delivers data for. Its processor description (`opentsx.processor.v1`)
states its `fidelity`:

| `fidelity` | Generates | Knows | Fit for |
|---|---|---|---|
| `demo` | one series for one signal, from its expectation | nothing of the rest of the system: no coupling between wind, price and demand, no balance, no dynamics of a storage | trying the surface and the pipes |
| `scenario` | several quantities together, from assumptions and dependencies | couplings, dynamics of storages, balance limits | comparing strategies |

* `gen.ou` and `gen.sine` have `fidelity: demo`. No processor in `systems/processors` has `fidelity: scenario`.
* A seed proves that a run repeats, not that it is realistic.
* A demo generator clips its values to the plausible range of the signal.
* Its episodes carry the labels `grade: raw`, `origin: SYNTHETIC`, `generator`, `seed`, `fidelity: demo`,
  `systemHash` and `generatedAt`. They go to the topic of generated series of the domain
  (`TopicLayout.synthetic()`, `tsx.<domain>.synthetic.v2`) and to no other topic.
* A result that rests on a demo generator MUST NOT be presented as an effect or used to rank strategies. The
  decision status marks a need whose data has `fidelity: demo` as `containsSynthetic`, and every analysis that needs
  it is at best `restricted` ([section 2.3](#23-evaluate-an-analysis)). A twin run on the series of the signals takes
  such an input as `SYNTHETIC`, keeps the run `restricted` and gives the reason
  `generated, not measured: <ports>; the run shows the mechanism, not an effect`.

Implemented in: `processor.v1:172-178`, `systems/processors/gen.ou.yaml:6`, `systems/processors/gen.sine.yaml:6`,
`Generators.java:17-22`, `Generators.java:64-71`, `Generators.java:178-186`, `TopicLayout.java:173-176`,
`TopicLayout.java:204-206`, `KafScaleFeed.java:171-172`, `Decisions.java:144-145`, `Decisions.java:175-177`,
`SignalInputs.java:98-99`, `Runs.java:360`, `Runs.java:376`.
