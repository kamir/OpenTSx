# Measure bounded W3 federation traffic

Measured with OpenTSx bf310c8274aadd21be2c9f5cbea8acb6579d70fe at
2026-11-17T20:49:57Z.

Topology: 3 site-agent instances within 1 JVM process and 4 Go broker processes.
This is the bounded local execution profile.

Execution topology excludes build and transient conformance-check subprocesses.

KafScale v1.6.0 source: 2809a3a20bf40ed8a2b82cd93215cb59396efb0a. JDK: openjdk
version "21.0.8" 2025-07-15.

OS: macOS-14.6.1-arm64-arm-64bit-Mach-O. Hardware: Mac15,9 / Apple M3 Max /
51539607552.

Kafka bytes are complete PRODUCE request frames observed before forwarding,
including the four-byte length prefix. Record counts include every request
attempt. Failed-record acknowledgements are zero.

HTTP body bytes count only aggregate-delivery application bodies. TLS client
bytes count the actual encrypted client-to-server TCP stream measured by the
transparent proxy. These counters describe separate layers. Inapplicable
transport cells remain blank.

TLS client bytes include plan polls, lifecycle status and delivery
acknowledgements during the execution interval.

Elapsed time is measured once per route and repeated on its three site rows.
Each table displays that route clock once.

Centralized elapsed time covers raw-record transfer and acknowledgements.
Signal-cluster and job-state elapsed time also includes site computation and
central merging. These clocks measure different work and do not establish a
latency or speed comparison.

| Route          | Kafka bytes | Records | Elapsed ms |
| -------------- | ----------: | ------: | ---------: |
| centralized    |     2878653 |    3600 |     89.077 |
| signal-cluster |      189920 |    1260 |  39115.159 |
| job-state      |             |         |  33523.043 |

| Route          | HTTP body bytes | HTTP records | TLS client bytes |
| -------------- | --------------: | -----------: | ---------------: |
| centralized    |                 |              |                  |
| signal-cluster |                 |              |                  |
| job-state      |         1779492 |         1260 |          1794307 |

Correctness checks passed for 3600 episodes, 2160000 values, 100 raw series, 420
windows, 1260 partials and 2520 comparisons.

Maximum relative mean error: 1.7308017671758118e-14. Maximum relative M2 error:
2.7235050829345894e-12. Recorded D-T2 tolerance: 3.0000000000000001e-12.

Reference ordering: scenario generator episode order, reconstructed from
broker-received episode ids. Both signal-cluster adapters equal the job-state
route.

Source specHash:
28f8ab3f6e5c36a16cb06aefbb40d3ca699f9c2aa23a6924cb3d983954dd97f3. Scenario
SHA256: ecf1cae174fe07c668afaa221281ac8cb583f4435fe1c9feaabdf1523603268b.

Read bytes.csv for every measured site and transport counter, windows.csv for
numerical comparisons, and run.json for executed workload and topology evidence.
metadata.json records source hashes, pinned broker source, hardware, commands
and the SHA256 manifest of the private runtime snapshot.
