# Twin coupling and execution rules

This page specifies the atom contract, the wiring plan and the execution rules of `opentsx-twin` (variants T and L), and the measured coupling results with the design rule they give; verified against commit `a513aa98`.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

Module: [`opentsx-twin`](../../opentsx-twin/README.md). Paths in "Implemented in" lines are relative to
`opentsx-twin/src/main/java/org/opentsx/twin/`; test paths are relative to `opentsx-twin/src/test/java/org/opentsx/twin/`.

Terms:

| Term | Meaning |
|---|---|
| atom | smallest element with its own state, parameters and balance; an instance of an atom type |
| wiring plan | YAML document that names atoms, their parameters and policies, and the wires between their ports |
| superstep `k` | one step of the run; covers the interval `[t_k, t_k + baseStep)` |
| variant T | tightly coupled execution: all atoms in one process (`TwinRunner`) |
| variant L | loosely coupled execution: every atom (or group of atoms) is a processor with its own clock (`LooseRunner`) |
| tick | one unit of simulated wall time in variant L |

<a id="1-atom-contract"></a>

## 1. Atom contract

An atom type extends `Atom` and splits into model, views, dynamics and policy (MVC+P).

| Member | Part | Rule |
|---|---|---|
| `id()` | identity | the plan id; unique in the plan, no `.` (§3) |
| `type()` | catalog | the type name the plan uses (§2) |
| `phase()` | execution | one `Phase`, fixed per type (§5) |
| `inputs()`, `outputs()` | ports | lists of `PortSpec(name, kind, unit)` |
| `controlDefaults()` | policy | controls a policy or an intervention may set, with their defaults; empty by default |
| `state()` | model | state variables in a fixed order; cumulative variables double as KPIs |
| `restore(state)` | model | sets the state back from a snapshot |
| `restingOutputs()` | view | outputs before the first superstep, origin `SYNTHETIC`; read by delayed wires and held atoms in superstep 0 |
| `step(ctx)` | dynamics | advances the state over `[t, t + dt)`; outputs written to `ctx` describe the end of the interval |
| `every()` | multirate | steps only when `k mod every = 0`; parameter `every`, default 1 |
| `defaultPolicy()` | policy | policy used when neither the run nor the plan names one: `follow` |

| Port kind | Meaning |
|---|---|
| `FLOW` | a conserved quantity exchanged between atoms, for example `kW` or `kg/h` |
| `SIGNAL` | a value without conservation, for example a price, a pressure or a setpoint |

* Stocks (state of charge, line pack, tank level) are state variables of their atom, not ports.
* Units are plain strings and are compared exactly.
* Parameters come from the plan's `params` map. A number is used as is; a string is parsed as a number where the
  atom reads a number.
* The catalog creates fresh instances for every run. A run never sees state from another run.
* `step` MUST be deterministic for the same state, inputs, controls and parameters.
* A change to the dynamics of any atom type MUST change `TwinRunner.VERSION` (now `twin-1`). The version is part of
  every run id (§8).

Implemented in: `Atom.java:17-73`, `PortSpec.java:4-13`, `PortKind.java:4-7`, `Params.java:25-56`,
`TwinRunner.java:22-23`.

<a id="2-atom-catalog"></a>

## 2. Atom catalog

`AtomCatalog.standard()` registers the atom types a plan can name. A plan with any other type is rejected with
`unknown atom type '<type>', known: [...]`. The label is the catalog number used in the source comments.

| Type | Class | Phase | Label |
|---|---|---|---|
| `generation` | `atoms.Generation` | `ASSETS` | A1/A3 (simplified) |
| `market` | `atoms.Market` | `SOURCES` | A12 |
| `h2-offtake` | `atoms.H2Offtake` | `SOURCES` | A11 |
| `electrolyzer` | `atoms.Electrolyzer` | `ASSETS` | A9 |
| `battery` | `atoms.Battery` | `ASSETS` | A8 |
| `balance` | `atoms.BalanceNode` | `BALANCE` | A6 |
| `h2-line` | `atoms.H2Line` | `FLOWS` | A10 |
| `direct-marketer` | `atoms.DirectMarketer` | `FLOWS` | A13 |
| `operating-rules` | `atoms.OperatingRules` | `SOURCES` | A16 |
| `wind-park` | `atoms.WindPark` | `ASSETS` | A22 |
| `h2-compressor` | `atoms.H2Compressor` | `ASSETS` | A17 |
| `h2-buffer` | `atoms.H2Buffer` | `FLOWS` | A18 |
| `h2-pipeline` | `atoms.H2Pipeline` | `FLOWS` | A19 |
| `h2-handover` | `atoms.H2Handover` | `ASSETS` | A20 |
| `h2-consumer` | `atoms.H2Consumer` | `SOURCES` | A21 |
| `weather` | `atoms.Weather` | `SOURCES` | A4 |
| `building` | `atoms.Building` | `FLOWS` | |
| `oil-boiler` | `atoms.OilBoiler` | `ASSETS` | |
| `wood-stove` | `atoms.WoodStove` | `ASSETS` | |
| `dhw` | `atoms.HotWater` | `SOURCES` | |
| `appliances` | `atoms.Appliances` | `SOURCES` | |
| `meter` | `atoms.ElectricityMeter` | `BALANCE` | A5/A6 (household) |
| `oil-tank` | `atoms.OilTank` | `FLOWS` | |
| `wood-store` | `atoms.WoodStore` | `FLOWS` | |
| `boat-mode` | `boat.BoatMode` | `SOURCES` | |
| `solar` | `boat.Solar` | `ASSETS` | |
| `shore-power` | `boat.ShorePower` | `ASSETS` | |
| `power-station` | `boat.PowerStation` | `BALANCE` | |
| `device` | `boat.Device` | `ASSETS` | |

* `market` is the only type with another `every` default: 15 supersteps.
* `weather` (A4) has the input ports `t_out_meas_c` (`°C`) and `ghi_meas_w_m2` (`W/m2`) and the output ports
  `t_out_c` and `ghi_w_m2`. A bound measurement wins. Without one, the atom computes a deterministic synthetic
  climate. `opentsx-dwd` binds DWD series to these two input ports, with origin `MEASURED` for observations and
  `FORECAST` for MOSMIX.

Implemented in: `AtomCatalog.java:46-90`, `atoms/Market.java:31-33`, `atoms/Weather.java:17-20,80-86`,
`opentsx-dwd/src/main/java/org/opentsx/dwd/TwinWeather.java:13-36`.

<a id="3-wiring-plan"></a>

## 3. Wiring plan

A wiring plan is a YAML document read by `TwinPlan.load`. Unknown keys are rejected.

| Key | Type | Required | Rule |
|---|---|---|---|
| `twin` | string | yes | name of the twin; first part of every run id |
| `start` | ISO-8601 instant | yes | time of superstep 0 |
| `baseStep` | ISO-8601 duration | no | default `PT1M`; MUST be positive and a whole number of microseconds |
| `steps` | int | no | number of supersteps of a run that does not set its own |
| `atoms` | list | yes | at least one entry |
| `atoms[].id` | string | yes | MUST be unique and MUST NOT contain `.` |
| `atoms[].type` | string | yes | a catalog type (§2) |
| `atoms[].params` | map | no | atom parameters, including `every` |
| `atoms[].policy` | string | no | a registered policy name (§4); default: the atom's `defaultPolicy()` |
| `wiring` | list of strings | no | wires `<atom>.<port> -> <atom>.<port>` |

Excerpt of [`twin/h2-chain.yaml`](../../opentsx-twin/src/main/resources/twin/h2-chain.yaml):

```yaml
twin: h2-chain
start: 2025-10-01T00:00:00Z
baseStep: PT1M
steps: 360
atoms:
  - id: ely
    type: electrolyzer
    params: { ratedKw: 5000, minLoad: 0.1, rampKwPerMin: 500, kwhPerKg: 55, pTripBar: 200, pResumeBar: 180, initialKw: 5000 }
wiring:
  - ely.m_h2_kgph -> compressor.m_in_kgph
  - buffer.p_bar -> ely.p_downstream_bar
```

A wire MUST connect an output port of the source atom to an input port of the target atom. Both ports MUST have
the same kind and the same unit. An input port MUST have at most one wire. The runner checks the plan when it is
constructed. A faulty plan is rejected with `IllegalArgumentException` and one of these messages; `TwinPlan.load`
wraps faults of the plan record in a Jackson `ValueInstantiationException` and reports an unknown key as
`UnrecognizedPropertyException`:

| Fault | Message |
|---|---|
| missing `twin`, `start` or atoms | `plan needs twin, start and atoms` |
| bad `baseStep` | `baseStep must be a positive whole number of microseconds` |
| atom id with `.` or without type | `atom needs an id without '.' and a type: <id>` |
| atom id used twice | `duplicate atom id <id>` |
| wire without exactly one `->` | `wire must look like 'a.port -> b.port': <wire>` |
| side without `atom.port` | `port reference must be 'atom.port' in wire <wire>` |
| unknown atom | `unknown atom in wire <wire>` |
| unknown port | `<id> (<type>) has no <input or output> port '<port>' in wire <wire>` |
| kind or unit differ | `wire <wire> connects <PortSpec> to <PortSpec>` |
| second wire into one input | `input <atom>.<port> has more than one wire` |
| unknown policy | `unknown policy '<name>', known: [...]` |

A wire is **delayed** when the phase of its source atom is not earlier than the phase of its target atom. A delayed
wire delivers the value of the previous superstep (§5). The plan is not checked for cycles: every cycle contains at
least one delayed wire, so every feedback loop crosses the superstep boundary.

`TwinPlan.hash()` is the lower-case hex XxHash64 of the plan serialized as JSON with map keys sorted and doubles in
their shortest decimal form (`2e23` as `2.0E23` on every JVM). It is part of every run id (§8).

Implemented in: `TwinPlan.java:33-131,141-146,160-172`, `TwinRunner.java:65-73`, `Policies.java:31-37`; tests:
`TwinPlanTest.java:28-47,65-79`.

<a id="4-policies-and-interventions"></a>

## 4. Policies and interventions

A policy sets the controls of an atom before its dynamics run. `Policies.standard()` registers three names:

| Name | Behavior |
|---|---|
| `follow` | every control takes the value of the input port with the same name, else its default |
| `hold` | every control takes its default |
| `pressure-aware` | electrolyzer only: follows the setpoint but limits hydrogen production to the offtake plus the buffer headroom spread over `policy.horizonMin` (default 60) minutes |

The policy of an atom is, in this order: the run's policy override for the atom id, the plan's `policy`, the atom's
`defaultPolicy()`. A run override for an atom id that is not in the plan is rejected with
`policy override for unknown atom <id>`.

An `Intervention(tMicros, atom, control, value, author, reason)` is a decision that overrides one control.

* It MUST name an atom of the plan and one of its `controlDefaults()` keys. Otherwise the run is rejected with
  `intervention on unknown control <atom>.<control>`.
* It applies from the superstep whose interval contains `tMicros`, or from the first superstep after it.
* It stays in force until an intervention on the same control with `value` null releases it.
* It overrides the policy's value for that control.
* It is recorded as an event of type `intervention` at `max(tMicros, t_k)` with the attributes `control`, `value`
  (`release` for a release), `author` (default `unknown`) and `reason` (default empty).
* Interventions apply in time order.

Implemented in: `Policy.java:4-8`, `Policies.java:13-24`, `atoms/Electrolyzer.java:53-58`, `Intervention.java:13-33`,
`RunSetup.java:62-78,107-109,127-152`; tests: `TwinPlanTest.java:49-63`, `TwinModesTest.java:43-61,82-101`.

<a id="5-execution-variant-t"></a>

## 5. Execution variant T

`TwinRunner` runs all atoms in one process in layered bulk-synchronous supersteps. Superstep `k` starts at
`t_k = t_0 + (k − k_0) · baseStep`, where `k_0` and `t_0` are 0 and `start`, or the values of the snapshot the run
continues from.

Phases, in execution order:

| Phase | Meaning |
|---|---|
| `SOURCES` | exogenous values |
| `POLICY` | standalone policy atoms; no catalog type has this phase |
| `ASSETS` | controllable assets decide their operating point |
| `BALANCE` | balance at a connection point, solved within the superstep |
| `FLOWS` | stocks and accounts integrate |
| `VIEWS` | derived views only; no catalog type has this phase |

Within a phase, atoms run in plan order. Each superstep runs these steps. The order matters.

1. Apply every pending intervention with `tMicros < t_k + baseStep` (§4).
2. For each atom in execution order:
   1. When the phase changes, emit the phase barrier of the previous phase (§7).
   2. If the atom is held (`k mod every ≠ 0`), repeat its previous outputs. It emits no message and its state does
      not change.
   3. Resolve each input port. A wire that is not delayed delivers the source's output of superstep `k`. A delayed
      wire delivers the source's output of superstep `k − 1`, or the resting output (snapshot output) in the first
      superstep. A port without a wire takes the bound input value at `t_k`. A missing or NaN value leaves the port
      empty.
   4. Run the policy, then apply the interventions in force, then run `step(ctx)` with
      `dt = baseStep · every`.
   5. Emit one port message per output (§7).
3. Emit the phase barrier of the last phase.
4. Record states, outputs and interventions in force, extend the run hash and emit the commit barrier (§8).

* A value at superstep `k` describes the end of the interval `[t_k, t_k + dt)`.
* An event carries the exact time it happened, which may lie inside the interval, and the superstep `k` that
  detected it. Events of a run are sorted by time, then by `k`.
* Input bindings (`Inputs`) MUST name an existing input port without a wire. Otherwise the run is rejected with
  `binding to unknown input port <atom.port>` or `binding to wired input port <atom.port>`.
* The binding decides the operating mode, not the atoms (§9).

Implemented in: `Phase.java:8-21`, `TwinRunner.java:87-162`, `RunSetup.java:52-54,80-105,117-124,143-152,181-193`,
`Inputs.java:10-19`, `SeriesInputs.java:61-72`; tests: `TwinPlanTest.java:41-47`, `TwinShadowTest.java:21-75`.

<a id="6-origin-propagation"></a>

## 6. Origin propagation

Every port value carries an `Origin`, best first:

| Origin | Meaning |
|---|---|
| `MEASURED` | measured by a meter or sensor |
| `REPORTED` | reported by a partner or a plan (schedule, operator log, operating rule) |
| `FORECAST` | a forecast |
| `ESTIMATED` | estimated by a learned model (Smart Topic) |
| `SYNTHETIC` | a parameter, profile, default or simulator value |

* An output gets the worst origin of the inputs the atom read in the same superstep before writing it.
* Values over delayed (feedback) wires carry model state and do not pass their origin on.
* A default used for a missing input (`inOr`) counts as `SYNTHETIC`.
* An output written with an explicit origin gets the worse of that origin and the inputs read.
* An output written without an explicit origin by an atom that has read no input is `SYNTHETIC`.
* Resting outputs are `SYNTHETIC`.

Implemented in: `Origin.java:7-27`, `StepContext.java:58-100`, `RunSetup.java:86-90`; tests:
`TwinShadowTest.java:77-90`, `TwinPlanTest.java:115`.

<a id="7-message-contract"></a>

## 7. Message contract

Both variants emit port messages and barriers to the `MessageSink` of the run. The id of a message is deterministic,
so a reader can drop duplicates without an idempotent producer.

`PortMessage`:

| Field | Type | Rule |
|---|---|---|
| `runId` | string | run id (§8) |
| `k` | int | superstep |
| `phase` | `Phase` | phase of the atom |
| `atom` | string | atom id |
| `port` | string | output port |
| `tMicros` | long, epoch µs | `t_k` |
| `value` | double | output value |
| `origin` | `Origin` | §6 |

The message id is `<runId>/<k>/<phase>/<atom>/<port>`.

`Barrier(runId, k, phase, count, stateHash)`:

| Kind | `phase` | `count` | `stateHash` |
|---|---|---|---|
| phase barrier | the completed phase | messages of that phase in superstep `k` | null |
| commit barrier | null | 0 | hex run hash after superstep `k` (§8) |

* Variant T emits a phase barrier for every phase that has at least one atom in the plan, also when all its atoms
  are held (count 0), then one commit barrier per superstep.
* Variant L emits no phase barriers. It emits one commit barrier per superstep, in superstep order, after the run.
* The commit barrier of the last superstep equals `RunResult.hash()`.
* `twin/avro/` holds the Avro form of the envelopes `PortMessage`, `Barrier` and `Intervention` in namespace
  `org.opentsx.twin.v1`. The schemas name no topic.
* `opentsx-twin` ships the in-memory `MessageLog` as the only sink. It has no KafScale binding for port messages and
  barriers. No topic carries port messages or barriers, and no topic carries the `Intervention` record. An applied
  intervention reaches the event topic of `opentsx-twin-live` only as an event of type `intervention` (§4).

Implemented in: `PortMessage.java:8-13`, `Barrier.java:7-11`, `MessageSink.java:4-18`, `MessageLog.java:8-29`,
`TwinRunner.java:107-113,140-146,158`, `LooseRunner.java:319-322,374`, `schema/AtomSchemas.java:35,60-100`,
`RunSetup.java:134-136`, `EventPublisher.java:51,53,66`; tests: `TwinShadowTest.java:103-131`,
`LooseRunnerTest.java:41-45,70-106,224-233,274-277`, `schema/AtomSchemasTest.java:90-95`,
`EventTopicLiveTest.java:98-116`.

<a id="8-run-identity-and-run-record"></a>

## 8. Run identity and run record

The run id is `<twin>-<hex>`, where `<hex>` is the XxHash64 of this string:

```text
twin-1|<plan hash>|<mode>|<inputs fingerprint>|<steps>[|<intervention>...][|policy:<atom>=<name>...][|from:<source run id>@<k>][|variant:<coupling>]
```

* `<intervention>` is `<tMicros>|<atom>|<control>|<value bits or release>|<author>|<reason>`, in the order given.
* Policy overrides are sorted by atom id.
* `from` is present for a run that continues from a snapshot.
* `variant` is present only for a variant L run whose coupling can change the result (§10).

The run hash chains every superstep: the seed is the XxHash64 of the run id; each superstep hashes the previous
value, `k`, and for every atom in plan order its id, its state variables (name, double bits) and its outputs (name,
double bits, origin). Two runs with the same run id and the same code are bit-identical and have the same
`RunResult.hash()` on every conforming JVM. A conforming JVM implements Java SE 17 or later; Java SE fixes the result
of `StrictMath` and of the double arithmetic to the bit, so the hash does not depend on the vendor, the processor, the
compiler or the JVM's libm intrinsics. This was checked with OpenJDK 17 and 21 on x86_64: interpreted, with C1 only
and with C2, each with and without the libm intrinsics. Other vendors and processors were not run. The Studio export
carries the run hash of every run.

The run hash is portable only if every value it chains is. Code whose results reach a hashed value, the order of
hashed values or the run id (atoms, policies, runners, `TwinPlan`, `SeriesInputs`, and the plans, inputs and
interventions that `opentsx-twin` builds) is hash-relevant. All main sources of `opentsx-twin` and `opentsx-model` are
treated as hash-relevant. Hash-relevant code:

* MUST use `StrictMath` for `exp`, `log`, `log10`, `log1p`, `expm1`, `pow`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`,
  `atan2`, `sinh`, `cosh`, `tanh`, `cbrt` and `hypot`. `Math` may return another double within one ulp; HotSpot on
  x86_64 returns other doubles with its libm intrinsics than without them (`-XX:-UseLibmIntrinsic`). `StrictMath`
  returns the result of fdlibm 5.3 on every JVM. `Math.abs`, `min`, `max`, `floor`, `ceil`, `rint`, `round`, `sqrt`,
  `fma`, `floorDiv`, `floorMod` and the arithmetic operators are exact or correctly rounded on every JVM since Java 17
  and MAY be used.
* MUST NOT use `Math.toRadians` or `Math.toDegrees`; the JDK does not fix their result. Multiply by the constant
  `Math.PI / 180` instead.
* MUST NOT draw from a seeded `java.util.SplittableRandom`, whose values the JDK fixes only within one program.
  `SplitMix64` gives the values of OpenJDK's `SplittableRandom` on every JVM. `java.util.Random` is specified to the bit
  and MAY be used.
* MUST NOT reduce doubles with `DoubleStream.sum`, `average` or `summaryStatistics`, or with `Collectors.summingDouble`
  or `averagingDouble`: their order of additions is not specified. Add in a fixed order.
* MUST hash state variables and outputs in insertion order: `Atom.state()` returns its variables in a fixed order and
  `StepContext` keeps the outputs in the order they are written. The iteration order of a `HashMap`, `HashSet`,
  `Map.of` or `Set.of` MUST NOT reach a hashed value or the order of hashed values.
* MUST NOT build text of the run id with `Double.toString` or string concatenation of a double: Java 17 writes some
  doubles with other digits than Java 21 (`2e23` as `1.9999999999999998E23` instead of `2.0E23`). `TwinPlan.hash()`
  writes doubles with Jackson's shortest-decimal writer (`StreamWriteFeature.USE_FAST_DOUBLE_WRITER`), and the
  reasons built by `HouseScenario` and `BoatDemo` use `NumberOutput.toString(value, true)`.
* MUST NOT read the wall clock or an unseeded random source, and MUST NOT run work in parallel whose results are
  combined in the order the threads finish.
* MUST NOT let the default locale, charset or time zone change a value or text. `String.format` and `printf` get
  `Locale.ROOT`; `formatted`, `toUpperCase()` and `toLowerCase()` are not used, because they read the default locale.
  Local time uses a named zone; its rules come from the time-zone database of the JVM, so a run over a period whose
  rules differ between two database versions gives different hashes on JVMs with these versions.

Inputs computed outside `opentsx-twin` are data: the hash is equal for equal input values. A module that computes
input values with `Math` functions can bind other values, and so get another run id and run hash, on another JVM.

`PortableRunHashTest` fails when code outside the comments of a main source of `opentsx-twin` or `opentsx-model`
calls one of these `Math` functions, `SplittableRandom`, a floating-point stream reduction, `String.format` or `printf`
without a `Locale`, `formatted`, `toUpperCase()` or `toLowerCase()`; when a run of `twin/run-hashes.v1.json` (the
reference run of every Studio scenario, a what-if, a forecast and a variant L run with jitter) gives another run id
or run hash; when a child JVM started with `-XX:-UseLibmIntrinsic` gives other run hashes than the JVM of the test;
and when the plan hash or a delivery reason writes `2e23` with other digits than `2.0E23`. `BoatDemoTest` checks the
same for the reason of a `BoatDemo` lever. `StudioPageTest` compares the committed Studio page, with the run hashes of
all its runs, with the export, and checks that the names of the H2 chain alternatives and forecasts do not change
with the default locale. CI runs these tests on Java 17 and 21.

`RunResult`:

* Series keys are `<atom>.<port>` for outputs and `<atom>.state.<var>` for state variables.
* `kpis()` is the final value of every state variable, key `<atom>.<var>`.
* `snapshotAfter(k)` returns the `Snapshot` to continue at `k + 1`: states, last outputs and interventions in force.
* `toEpisodes()` exports every series as a v2 episode: metric `twin.<type>.<var>`, tags `twin`, `atom`, `run`,
  labels `twin.mode`, `twin.kind` (`state` or `output`) and, for outputs, `twin.origin` (worst origin of the run).

Implemented in: `RunSetup.java:110,163-179,195-233`, `RunResult.java:61-64,159-212`, `Snapshot.java:9-10`,
`TwinPlan.java:117-131`, `SplitMix64.java:8-52`, `LooseRunner.java:548-553`, `atoms/Weather.java:29,61,152-193`,
`atoms/Appliances.java:126`, `atoms/H2Compressor.java:53`, `atoms/BalanceNode.java:90-93`, `boat/Solar.java:33,141-157`,
`demo/H2ChainDemo.java:56`, `demo/BoatDemo.java:410-412`, `house/HouseScenario.java:44-60`,
`house/BillCalibration.java:308,567-575,659-668`, `house/SimulatedBills.java:79,100`,
`studio/H2ChainScenario.java:64-65,75`, `studio/StudioExport.java:51-59,151-156,204`; tests:
`TwinShadowTest.java:92-101,133-147`, `PortableRunHashTest.java:30-190`, `RunHashVectors.java:12-68`,
`SplitMix64Test.java:8-22`, `demo/BoatDemoTest.java:9-16`, `studio/StudioPageTest.java:25-65`,
`opentsx-twin/src/test/resources/twin/run-hashes.v1.json`.

<a id="9-operating-modes"></a>

## 9. Operating modes

The three modes run the same atoms. Only the binding of inputs, the intervention log and the policies differ.

| Mode | Call | Inputs | Result |
|---|---|---|---|
| `SHADOW` | `Twin.shadow(observed, log)` | observations and the real decision log | estimate of what is not measured |
| `WHAT_IF` | `Twin.whatIf(history, baseline, alternative)` | the same history with two intervention logs | two runs; `kpiDelta()` is alternative minus baseline |
| `FORECAST` | `Twin.forecast(snapshot, forecast, horizonSteps, policies, planned)` | forecasts from a snapshot, planned interventions, policies replaceable per atom | continuation from the snapshot's superstep |

A forecast from a shadow snapshot with the same input values reproduces the rest of the shadow run exactly.
Interventions in force at the snapshot stay in force.

Implemented in: `Mode.java:4-10`, `Twin.java:23-56`; tests: `TwinModesTest.java:19-80`.

<a id="10-execution-variant-l"></a>

## 10. Execution variant L

`LooseRunner` runs the same plan, atoms, policies and interventions as variant T (`RunSetup`). Every atom is a
processor with its own superstep clock. Atoms couple only through port messages; there is no global barrier. Wall
time is simulated in ticks. A `Coupling` sets how loose the coupling is and how the transport behaves.

| Field | Type | `bsp()` | Rule |
|---|---|---|---|
| `staleness` | int | 0 | `0` bulk-synchronous; `s > 0` stale-synchronous; `Coupling.ASYNC` (`-1`) never waits; MUST be `>= -1` |
| `defaultLag` | int, ticks | 0 | transport latency of every atom's outputs; MUST be `>= 0` |
| `lag` | map atom → ticks | empty | latency of one atom's outputs (`withLag(atom, ticks)`) |
| `jitter` | int, ticks | 0 | extra latency per message, uniform in `0..jitter`; MUST be `>= 0` |
| `seed` | long | 0 | seed of the jitter |
| `stepTicks` | map atom → ticks | empty (1) | ticks one real step of the atom takes; MUST be `>= 1` |
| `outages` | map atom → list of `[from, to)` | empty | ticks in which the atom's processor is down |
| `groups` | map atom → name | empty | atoms of one group run in one processor (`withGroup`) |
| `colocateCycles` | boolean | false | `colocatingCycles()`: every strongly connected component of the wire graph runs in one processor |

Factories: `Coupling.bsp()`, `Coupling.ssp(s)` (`s >= 0`), `Coupling.async()`. Invalid values are rejected with
`staleness must be >= 0 or ASYNC`, `lag and jitter must be >= 0`, `s must be >= 0` or `step ticks must be >= 1`.

Rules:

* **Target value.** For step `i` of an atom, a wire that is not delayed targets the source's step `i`; a delayed wire
  targets step `i − 1`. Step `-1` is the resting output or the snapshot output.
* **Same processor.** If source and target are in one processor, the target reads exactly the targeted value,
  without latency and without staleness. It waits until the source has produced that step.
* **Other processor.** The target reads the newest source step `j` that is visible now, with
  `j >= target − staleness`. With staleness 0 this is exactly the targeted value. With `ASYNC` any `j >= -1` is
  allowed, so the atom never waits. If no such step is visible, the atom waits this tick.
* **Visibility.** Output of step `j` produced in tick `w` becomes visible in tick `w + 1 + lag + jitter(atom, j)`.
  Jitter is `nextInt(jitter + 1)` of a `SplitMix64` seeded by `seed`, atom index and step, so visibility does not
  depend on the order in which atoms are visited and is the same on every JVM (§8).
* **Cost.** In one tick an atom that is neither down nor busy runs its held (multirate) steps, which cost no tick and
  emit no messages, and at most one real step. A real step keeps the processor busy for `stepTicks` ticks.
* **Interventions.** The rule of §4 applies per atom.
* **Groups.** Processors are, in this order of precedence: explicit groups, then the strongly connected components
  of the wire graph (iterative Tarjan) if `colocateCycles` is set, else one processor per atom. A group member that
  is not an atom is rejected with `group member is not an atom: <id>`.
* **Unknown atoms.** An entry of `lag`, `stepTicks` or `outages` whose key is not an atom of the plan is not
  rejected. It slows no atom. With staleness other than 0 it is still part of the run id.
* **Deadlock.** If no atom can progress and nothing is busy, down or about to become visible, the run fails with
  `variant L deadlock at tick <tick> (plan has a same-step cycle?)`.
* **Record.** States, outputs and events are assembled in variant T order, and the run hash is computed as in §8.
* **Run id.** With staleness 0 the coupling is not part of the run id: the run has the run id of variant T for any
  lag, jitter, step ticks, outages or groups. With staleness other than 0 the run id contains every coupling
  parameter.

`LooseRun.stats()` reports `ticks` (simulated wall time), `waitTicks` and `downTicks` per atom, and per wire `reads`,
`staleReads` (age > 0), `meanAge` and `maxAge`, where age is the targeted step minus the step read.

Implemented in: `LooseRunner.java:38-196,212-388,394-457,463-553`, `SplitMix64.java:8-52`, `RunSetup.java:20-26`;
tests: `LooseRunnerTest.java:28-277`.

<a id="11-coupling-results-and-design-rule"></a>

## 11. Coupling results and design rule

<a id="111-measurement-program"></a>

### 11.1 Measurement program

`bin/opentsx twin-coupling` compares variant L against variant T for every scenario. You need a build of the
repository: `mvn -q install -DskipTests` (`bin/opentsx:28`).

```bash
bin/opentsx twin-coupling --csv coupling.csv
```

| Option | Value | Effect |
|---|---|---|
| `--csv` | `<file>` | also writes the rows as CSV to `<file>` |
| `--scenario` | `h2-chain`, `haus` or `boot` | runs one scenario only |

Any other option fails with `unknown option <option>`. The program prints a Markdown table to stdout.

| Scenario id | Plan | Supersteps |
|---|---|---|
| `h2-chain` | `twin/h2-chain.yaml` | 360 × `PT1M` |
| `haus` | `twin/haus-erzgebirge.yaml` | 8760 × `PT1H` |
| `boot` | `twin/boot-etap22.yaml` | 1344 × `PT15M` |

For each scenario the reference is a `SHADOW` run with the scenario's observed inputs and reference interventions
under variant T. The grid is:

| Lag | Jitter | Staleness | Cycles colocated |
|---|---|---|---|
| 0 | 0 | 0, 1, 2, 4, 8, async | no |
| 2 | 0 | 0, 1, 2, 4, 8, async | no |
| 2 | 2 (seed 17) | 0, 1, 2, 4, 8, async | no |
| 2 | 0 | 0, 2, async | yes |

| Column | Definition |
|---|---|
| `ticks` | simulated wall time of the L run |
| `speed-up vs BSP` | ticks of the staleness 0 run without colocation at the same lag and jitter, divided by `ticks` |
| `stale reads` | share of wire reads with age > 0 |
| `max age` | largest age of a read, in supersteps |
| `max NRMSE (series)` | largest RMS deviation from T over the scenario's compare series, divided by the range of the T series (the RMS itself if the range is 0) |
| `max KPI dev.` | largest `abs(L − T) / max(abs(T), 1e-9)` over the scenario's KPIs |
| `events differing` | events (by atom and type, interventions excluded) that occur in one run and not the other |

Implemented in: `demo/CouplingStudy.java:41-171`, `studio/Scenarios.java:11-13`, `bin/opentsx:70`.

<a id="112-conformance"></a>

### 11.2 Conformance

Variant L with staleness 0 is bit-identical to variant T in the modes `SHADOW`, `WHAT_IF` and `FORECAST`
([§9](#9-operating-modes)): same run id, same run hash, same events, same KPIs, the same set of port messages
(compared by id, value bits and origin), the same commit barriers and no stale read. The transport changes only the
wall time.

The conformance test stresses one slow atom and one remote atom per scenario:

| Scenario | Slow atom | Remote atom |
|---|---|---|
| `h2-chain` | `grid` | `ely` |
| `haus` | `building` | `boiler` |
| `boot` | `boat` | `boat` |

`boot` is a pipeline. Its slow processor absorbs an outage of any other atom, so its slow atom is also its remote
atom.

The equality holds for all three scenarios in `SHADOW` mode under each of these transports:

| Transport | Lag | Jitter | Step ticks | Outage |
|---|---|---|---|---|
| `bsp()` | 0 | 0 | 1 | none |
| lag | 3 | 0 | 1 | none |
| late remote atom | 7 on the remote atom | 5 (seed 42) | 1 | none |
| slow processor | 0 | 0 | 3 on the slow atom | none |
| outage | 0 | 0 | 1 | remote atom in ticks `[10, 60)` |
| combined | 2 | 3 (seed 7) | 10 on the slow atom | remote atom in ticks `[10, 60)` |

Under the combined transport it also holds for all three scenarios in `WHAT_IF` mode, for every alternative of the
scenario, and in `FORECAST` mode, for a 60-step forecast from the snapshot after the middle superstep of the
reference run and for every forecast of the scenario. `h2-chain` has eight forecasts; `haus` and `boot` have none.
The scenario forecasts run as the Studio export runs them: inputs labelled as forecasts, from the snapshot after
step `nowStep - 1`, to the end of the reference run.

Every stressor of these transports names an atom of the plan and costs wall time. Each transport other than `bsp()`
takes more ticks than `bsp()`, and more ticks than the same transport without any one of its stressors: the lag, the
jitter, the lag of one atom, the step ticks of one atom or the outages of one atom. A step of 10 ticks takes longer
than a superstep of `h2-chain` or `haus` under lag 2 and jitter 3 alone, so the slow processor of the combined
transport still costs ticks.

Implemented in: `LooseRunner.java:142-157,160-163,278-286,323-326,409-430`, `studio/StudioExport.java:107-111`;
tests: `LooseRunnerTest.java:28-143,224-277`.

<a id="113-throughput"></a>

### 11.3 Throughput

Feedback loops limit throughput; the depth of the chain does not. Without a loop the atoms work as a pipeline:
`boot` takes 1346 ticks for 1344 supersteps under staleness 0 and lag 0. With one tick per step, no groups and no
jitter, the measured ticks per superstep are

```text
ticks per superstep = max(1, max over all wire cycles of  Σ(1 + lag) / Σ(d + s))
```

where the sums run over the wires of the cycle, `d = 1` for a delayed wire and `d = 0` otherwise, and `s` is the
staleness. Example `haus`: every cycle has two wires, one of them delayed (building and boiler, boiler and tank,
building and stove).

| Lag | Staleness | Formula | Measured ticks for 8760 supersteps |
|---|---|---|---|
| 0 | 0 | 2/1 = 2 | 17521 |
| 2 | 0 | 6/1 = 6 | 52561 |
| 2 | 1 | 6/3 = 2 | 17521 |
| 2 | 2 | 6/5 = 1.2 | 10513 |
| 2 | 4 | 6/9, so 1 | 8760 |

Staleness acts like extra delay on the loose wires. Running each cycle in one processor removes the cycles from the
loose part of the plan and keeps the result of variant T.

<a id="114-deviation-under-staleness"></a>

### 11.4 Deviation under staleness

At lag 2 without jitter, staleness buys up to a factor of 6 in wall time and pays with deviation from variant T. Rows of
`bin/opentsx twin-coupling` at commit `a513aa98`, JDK 21, lag 2, no jitter:

| scenario | staleness | cycles colocated | lag | jitter | ticks | speed-up vs BSP | stale reads | max age | max NRMSE (series) | max KPI dev. | events differing |
|---|---|---|---|---|---|---|---|---|---|---|---|
| h2-chain | 0 | no | 2 | 0 | 2164 | 1.00 | 0.0 % | 0 | 0.0000 (-) | 0.00 % (-) | 0 |
| h2-chain | 2 | no | 2 | 0 | 433 | 5.00 | 85.2 % | 2 | 0.1722 (marketer.deviation_kw) | 4.44 % (park.curtailed_dispatch_kwh) | 2 |
| h2-chain | async | no | 2 | 0 | 360 | 6.01 | 94.9 % | 3 | 0.2152 (marketer.deviation_kw) | 6.67 % (park.curtailed_dispatch_kwh) | 2 |
| h2-chain | 0 | yes | 2 | 0 | 369 | 5.86 | 0.0 % | 0 | 0.0000 (-) | 0.00 % (-) | 0 |
| h2-chain | 2 | yes | 2 | 0 | 363 | 5.96 | 30.0 % | 2 | 0.1561 (marketer.deviation_kw) | 4.44 % (park.curtailed_dispatch_kwh) | 2 |
| h2-chain | async | yes | 2 | 0 | 360 | 6.01 | 30.1 % | 3 | 0.2029 (marketer.deviation_kw) | 6.67 % (park.curtailed_dispatch_kwh) | 2 |
| haus | 0 | no | 2 | 0 | 52561 | 1.00 | 0.0 % | 0 | 0.0000 (-) | 0.00 % (-) | 0 |
| haus | 2 | no | 2 | 0 | 10513 | 5.00 | 64.3 % | 2 | 0.2788 (boiler.fuel_lph) | 386.23 % (kpi.under_h) | 0 |
| haus | async | no | 2 | 0 | 8760 | 6.00 | 100.0 % | 3 | 0.3162 (building.t_in_c) | 460.87 % (kpi.under_h) | 0 |
| haus | 0 | yes | 2 | 0 | 8766 | 6.00 | 0.0 % | 0 | 0.0000 (-) | 0.00 % (-) | 0 |
| boot | 0 | no | 2 | 0 | 1350 | 1.00 | 0.0 % | 0 | 0.0000 (-) | 0.00 % (-) | 0 |
| boot | async | no | 2 | 0 | 1344 | 1.00 | 100.0 % | 3 | 0.1594 (station.shore_used_w) | 11.67 % (kpi.laptop1_wh_per_day) | 0 |

* Every staleness 0 row of the full grid has zero deviation, at every lag and jitter.
* `boot` has no feedback loop and its pipeline is already full. Staleness gains nothing there and costs 11.67 % KPI
  deviation.
* With cycles colocated, staleness 2 or async saves at most 9 ticks (`h2-chain`: 369 to 360) and adds deviation.

<a id="115-limits"></a>

### 11.5 Limits of the measurement

* Wall time is simulated in ticks. No real processors and no KafScale transport take part.
* Every atom takes one tick per real step.
* Deviations are measured against variant T, not against measured data.
* The program does not count balance errors of conserved quantities or violations of causal order.
* `kpi.under_h` of `haus` is small, so its relative deviation is large.

<a id="116-design-rule"></a>

### 11.6 Design rule

Feedback loops belong in one processor; between processors, bulk-synchronous coupling is enough.

* A plan run as variant L SHOULD colocate every feedback cycle in one processor (`Coupling.colocatingCycles()` or
  explicit groups) and SHOULD couple the processors with staleness 0. The result is then bit-identical to variant T
  at pipeline throughput (`h2-chain` 5.86 and `haus` 6.00 times faster than plain staleness 0 at lag 2).
* Staleness `s > 0` SHOULD NOT be used for throughput once cycles are colocated. It MAY be used to tolerate outages
  and owner boundaries, for example an atom run by another owner that delivers late.
* The cut between tight and loose coupling follows from the wiring plan: the strongly connected components are the
  tight parts.

Implemented in: `LooseRunner.java:134-140,463-489`; tests: `LooseRunnerTest.java:173-198`.
