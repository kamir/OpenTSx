# Qualify bounded mixed W3 statistics

Executed qualification evidence for OpenTSx `e9d9a80c413bf8d6c3858f80fcbc68b2c7f3570a`.

Execution host: Mac OS X (aarch64). Collection host: macOS-14.6.1-arm64-arm-64bit. Hardware: Mac15,9 / Apple M3 Max / 51539607552.

This is local lab qualification. It does not establish physical Linux board acceptance. ARMv7 hardware acceptance remains open.

The bounded signal-cluster plan uses Java at s1 and s2 and the actual signed Rust runner at s3. All three sites supply accepted conformance reports before inventory admission.

| Evidence | Executed count |
|---|---:|
| Input episodes | 3600 |
| Input samples | 2160000 |
| Source series | 100 |
| Site partials | 1260 |
| Unique final windows | 420 |
| Site conformance checks | 30 |
| Arrival orders per window | 6 |
| Exact merge byte comparisons | 2520 |
| Numerical qualification cases | 58 |
| Numerical merge comparisons | 348 |

Maximum relative mean error: 1.7308017671758118e-14. Maximum relative M2 error: 2.7235050829345894e-12. Retained tolerance: 3.0000000000000001e-12.

Maximum W3 quantile relative error outside the zero bucket: 0.0099999999999998979. Retained alpha: 0.01. Actual and reference W3 stores remain below 4096 bins.

Numerical boundary sketch differences observed: 0. Collapsed-tail cases: 4. Collapsed low-magnitude tails retain their explicit accuracy exception.

Read [windows.csv](mixed/windows.csv) for actual and centralized moments, raw lower quantiles, accuracy domains and bin spans. Read [partials.csv](mixed/partials.csv) for all source identities, statistics and sketch bytes.

Read [mixed execution](mixed/execution.json) for authenticated status histories, runtime pins, signed-command hashes and actual run/replay outcomes. Read [numerical results](sketch/result.json), [requests](sketch/input.json) and [Rust output](sketch/rust-output.json) for all cases.

[test-evidence.json](test-evidence.json) records exact successful JUnit methods and zero-skip counts, the explicit source-commit property and actual executable hashes. This records the owner-run source association and is not an attestation of the build process.

[metadata.json](metadata.json) records source hashes, collection hardware, executed host identity, toolchain commands and the KafScale fixture source pin. [manifest.json](manifest.json) pins every copied raw file and generated report. KafScale binary hashes are present only for supplied existing runtime paths.
