# Read the bounded Track A evidence

Read the A5 measurements taken on 2026-11-18 with OpenTSx
`1365d8cf27aeb8978b8ab51eeec2d48fbc698e83`.

The [generated report](REPORT.md) contains the results and clock definitions.
[Metadata](metadata.json) records the source, commands, runtime hashes,
hardware, versions and raw-file hashes. The complete run started at
16:56:51 UTC and finished at 17:45:49 UTC.

The dataset contains 63 sequential conditions: nine federation conditions
and 54 bounded streaming catch-up conditions, with three repetitions each.
Each condition uses the complete synthetic W scenario. Federation covers
1, 3 and 10 logical sites in one execution JVM. Streaming covers decode,
clean and statistics at 1, 4 and 12 partitions on both Java paths.

The public files contain 3,780 federation window comparisons and 207,000
streaming output latency records. Every manifest hash and the generated
report are checked by the deterministic committed-results regression.
Percentiles use nearest rank; the CSV files retain the recorded precision.

Resource CSV files retain their captured CRLF line endings. The repository
[format declaration](../../../../.gitattributes) prevents conversion and
keeps the standard whitespace checks, including rejection of real trailing
spaces. All raw-file hashes remain those of the original run.

Kafka Produce frames, HTTP aggregate bodies and encrypted TLS client-stream
bytes are distinct protocol layers. Central raw transfer, central plan
execution and federated route clocks cover different work. The report
does not derive a speed comparison from those clocks.

## Read the earlier attempt

An earlier attempt at `999fd2d6987bfa35fdc54f08f24b5e4375b74759` stopped
after two of 63 conditions during ten-site route-J conformance, after four
sites had been admitted. It is excluded from this dataset and its tables.

The client exposed only `plan service refused request`. The original HTTP
status and server-side cause were not retained, so the cause is unknown.
The test harness now records the status, site and route and preserves the
original exception without retrying the failed request. The separate
ten-site diagnostic pilot and this complete run passed. The earlier failure
was not reproduced, and no production fix is claimed.

## Apply the measured scope

These results qualify the bounded local A5 profile. The paced A1.7 interval
measurements, thirty-kill recovery protocol, remote-network measurements,
Track B hardware comparison and production fleet operation are separate
evidence. The mean/M2 bound remains `3e-12` for the measured W cases in one
runtime with uncollapsed sketches.

Private logs, checkpoints, certificates and runtime directories are not
part of the public dataset. See [PR #289](https://github.com/kamir/OpenTSx/pull/289)
for the implementation and validation evidence.
