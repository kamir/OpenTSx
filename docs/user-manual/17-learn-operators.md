# Learn and verify the implemented operators

Use the versioned operator lessons with the implemented UI/DAG operators and public library algorithms;
the runtime entry points and executable checks are recorded in the learning metadata for this checkout.

You need a matching OpenTSx checkout and student-material checkout, the dependencies of the runtime used by
your lesson, and synthetic input data. UI workloads use the existing frontend test setup; Java workloads use
JDK 17 or 21 and Maven; Python workloads use the project's installed package and pytest.

## Choose the actual execution surface

Read the lesson's source, manual, SPEC and test references before choosing its runtime.

| Surface | Meaning |
| --- | --- |
| UI operator | An executable `OPS` entry in the workbench. Membership in an older streaming catalog is a separate property. |
| Java or Python library | A public numerical entry point. A library implementation alone does not supply a DAG binding or a Wayang mapping. |
| Processor DAG | An operator accepted by the current series/vector processor, with that runtime's parameters and state rules. |
| Wayang | An implemented mapping or infrastructure entry point, separately identified by the lesson. A pure Java kernel alone does not establish a mapping. |
| Rust | The shared offline assignment validator exists. Numerical operator execution and deployment remain separate delivery work. |

The [UI contracts](../learning/ui-operator-contracts.md) and
[library contracts](../learning/library-operator-contracts.md) identify the actual algorithm behavior.
The [unified Wayang plan](../devguide/integration/wayang-unified-plan.md) records deployment work and its gates.

## Work through a lesson

The lesson format follows the existing [exercise structure](../../exercises/README.md#exercise-format):

1. State the learning objective and check prerequisites.
2. Load the supplied synthetic starter input and write down the predicted axes, units, values and gaps.
3. Execute the named operator through its actual runtime entry point.
4. Compare the result with the expected checks and the linked executable regression cases.
5. Change the requested parameter or boundary condition and explain the effect.
6. Record which behavior is shared across runtimes and which remains specific to the implementation.

Keep input timestamps, NaNs, episode boundaries and random seeds exactly as the lesson states.
Do not replace an expected result with the current output merely to make a check pass.
For library algorithms, use the demonstrated public API rather than reimplementing its numerical kernel.

## Check that the student material matches

Learning metadata is maintained with OpenTSx. The student repository contains generated lessons and a source
content manifest. `<student-checkout>` below is the path to that repository's checkout.
Run from the OpenTSx root:

```bash
python3 tools/sync_operator_exercises.py --opentsx-root . --student-root <student-checkout> --check
```

The check rejects missing or edited generated lessons, broken references and source-content mismatches.
The manifest records the source revision and whether its content includes local changes.
Published material requires a published matching source revision.
Regeneration is explicit; checking does not silently repair stale material.

For the workbench lesson workloads, run from its frontend directory:

```bash
cd opentsx-saas-frontend
npx vitest run src/workbench/operator-learning.test.ts
```

The test imports the real `OPS` registry, requires matching lesson coverage and executes the supplied workloads.
Library lessons identify their Java/Python tests and applicable shared vector files.
Run the added Java library examples from the OpenTSx root:

```bash
mvn --batch-mode --no-transfer-progress --projects opentsx-core,opentsx-retrieval --also-make test -Dtest=LibraryAlgorithmsLearningTest,GeneratorLearningTest,LegacyFourierLearningTest -Dsurefire.failIfNoSpecifiedTests=false
```

Confirm that all three named test classes executed. The flag permits dependency modules without those tests.
Run the Python algorithm examples from the package directory in your installed Python environment:

```bash
cd python-package
python -m pytest -q tests/test_library_learning_algorithms.py
```

Reference checks and hashes detect structural/content drift; scientific semantics still require those executed
workloads and review of the expected results.

## Keep topic placement separate from internal positions

For orchestration lessons, a whole topic is the smallest deployable unit regardless of its partition count.
One assignment includes every partition's internal read/cut/checkpoint position. Splitting a topic's partitions
across runner nodes is invalid. A two-node lesson uses different topics or distinct complete graph stages.
If a topic exceeds a node's resource budget, reject or move the whole topic; do not teach partition splitting as
a fallback. See the [shared assignment contract](../devguide/integration/wayang-shared-contracts.md).

## Change an operator with its teaching contract

Update the source behavior, applicable manual/SPEC, learning metadata, starter input and expected tests together.
Regenerate student material from that metadata, then run synchronization, runtime and regression checks.
The durable delivery rule is in [AGENTS.md](../../AGENTS.md#14-operator-learning-and-deployment-contracts).
