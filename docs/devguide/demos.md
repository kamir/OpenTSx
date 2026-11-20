# Choose a reproducible demo

Choose a local journey for OpenTSx 3.0.1 and the Python/frontend packages
1.0.0, with checked inputs, expected results and retained evidence.

You need the tools listed for your chosen journey and registry access for its
initial dependency installation. Each guide separates local edit checks from
browser or package acceptance.

## Explore a thermal signal

Inspect synthetic temperature and heater signals, apply Diffs and export a
local analysis snapshot.

- **Needs:** Node 24.22.2, npm 11.19.1 and a browser.
- **Start:** Follow the [thermal Workbench guide](standalone-workbench.md).
- **Expected result:** Two signals, eight episodes and an interval with
  missing temperature samples. Snapshots include inputs, graph, output and an
  integrity digest. Switching display modes preserves the graph and result.
- **Check:** Run the guide's [local edit checks][thermal-checks] and
  [browser acceptance][thermal-browser], which checks every heater
  difference and the downloaded snapshot.
- **Limits:** The fixture is synthetic. Forecast fitting uses the finite
  service in the next journey; connected functions have their own services.

## Fit and validate a forecast

Import a voltage episode, fit ARIMA(0,1,0), inspect the forecast and download
the actual numerical result.

- **Needs:** Git, Python 3.10 or newer, Docker with `linux/arm64` support and
  Compose with `up --wait`. Recording also needs Node 24.22.2, Playwright
  1.62.1 and Chromium.
- **Start:** Follow the [standalone analysis guide](standalone-analysis.md).
- **Expected result:** Four forecasts equal 16 V within absolute tolerance
  `1e-8`. Two held-out folds give naive MAE 2.5.
- **Check:** Run the guide's [browser acceptance][finite-browser], then
  [verify and replay the recording][finite-replay]. A retained recording is
  linked below.
- **Limits:** This is a finite synthetic fit and backtest. Broker replay
  positions, connected registration and continuous scoring use their
  documented connected workflows.

## Detect and replay underperformance

Simulate three turbines, detect underperformance, persist their episodes in
Iceberg and recompute the analysis from the closed store.

- **Needs:** JDK 17 or newer, Maven 3.9.8 and Python 3.
- **Start:** Follow the [Windpark guide](windpark-replay-demo.md).
- **Expected result:** 90 episodes and 900 values. T02 and T03 each have one
  detected period `[00:10, 00:20)` UTC; healthy T01 has none. The stored
  report and replay have identical bytes, including all episode payload hashes.
- **Check:** Use the guide's [replay from the closed store][windpark-replay] and
  [focused offline regressions][windpark-checks] after the initial build.
- **Limits:** Detection identifies underperformance, not its cause. The
  synthetic fixture is not a benchmark calibrated for a real site.

## Inspect the recorded forecast

Download the `standalone-analysis` [recording artifact][recording] from
[CI run 44753159855][recording-run]. It contains the actual Workbench video,
interactive trace, screenshot, input, numerical export and checks, plus
`recording.json` with source/runtime identity and evidence hashes.

The captured source is [revision `0884233e`][recorded-source]. Use the
[guide at that revision][recorded-guide] for the exact recorded source.

You need to [sign in to GitHub with repository read access][artifact-help]
to download the artifact. Its recorded expiration is **2027-02-18 at
14:24:31 UTC**. To generate fresh evidence, follow the current package
guide's capture and verification steps from a clean checkout with a newly
built package and a fresh output directory.

This recording covers the finite forecast journey. Video and trace timing
can differ between runs; source identity, inputs and numerical assertions
define the reproducible contract.

## Inspect distributed worker recovery

Continue with the
[three-worker stack](integration/wayang-rust-compose.md) and its
[continuous Wayang guide](integration/wayang-rust-continuous.md)
for persisted increments, worker restart
and durable Stop acceptance. This journey uses the authenticated
stack and retained broker and worker state described in those
guides. Whole topics remain the placement unit. Its documented operator and
publication limits apply.

[thermal-checks]: standalone-workbench.md#run-the-local-edit-checks
[thermal-browser]: standalone-workbench.md#verify-the-real-browser-journey
[finite-browser]: standalone-analysis.md#verify-the-real-browser-and-exported-result
[finite-replay]: standalone-analysis.md#verify-and-replay-the-recording
[windpark-replay]: windpark-replay-demo.md#replay-the-closed-store
[windpark-checks]: windpark-replay-demo.md#run-the-local-edit-checks
[recording]: https://github.com/kamir/OpenTSx/actions/runs/44753159855/artifacts/14637250737
[recording-run]: https://github.com/kamir/OpenTSx/actions/runs/44753159855
[recorded-source]: https://github.com/kamir/OpenTSx/commit/0884233ed0daf44d4aff4ecddcf0926fd47c4cd2
[recorded-guide]: https://github.com/kamir/OpenTSx/blob/0884233ed0daf44d4aff4ecddcf0926fd47c4cd2/docs/devguide/standalone-analysis.md
[artifact-help]: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts
