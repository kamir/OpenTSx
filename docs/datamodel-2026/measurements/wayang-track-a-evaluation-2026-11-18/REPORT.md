# Evaluate the bounded Track A baselines

Measured with OpenTSx 1365d8cf27aeb8978b8ab51eeec2d48fbc698e83 at
2026-11-18T16:56:51Z. Profile: A5 bounded baseline.

Hardware: Mac15,9 / Apple M3 Max / 51539607552. OS:
macOS-14.6.1-arm64-arm-64bit-Mach-O. JDK: openjdk version "21.0.8" 2025-07-15.

KafScale v1.6.0 source: 2809a3a20bf40ed8a2b82cd93215cb59396efb0a. Go: go version
go1.25.2 darwin/arm64. JVM heap: -Xms512m -Xmx2g.

All inputs are the complete synthetic ostsee-demo.yaml W scenario, seed 42.
Conditions run sequentially with fresh execution JVMs and in-memory brokers. No
warm-up samples are discarded. No performance threshold is applied.

Federation uses one execution JVM with 1, 3 or 10 signed site instances and one
broker per site plus the central broker. Complete turbines remain together.
Build and transient conformance subprocesses are additional setup processes. The
centralized plan executes actual received raw episodes; the raw transfer and
statistics computation clocks remain separate.

Kafka counters contain Produce request frames including the length prefix,
excluding other requests, responses and TCP/IP overhead. HTTP counters contain
aggregate bodies. TLS counters contain the encrypted client stream during
execution, including polls, status and ACKs. They are distinct protocol layers
and are not total network egress.

Streaming is a bounded backlog/catch-up profile at 1, 4 and 12 partitions. Every
input is acknowledged before activation. Track A runs one real TsxIncrement;
native clean runs VectorProcessor, native statistics runs StatsProcessor, and
native decode uses the existing SOE decoder in a plain Kafka loop. Both decode
paths include canonical re-encoding and acknowledged writes, not only decoding
CPU. VectorProcessor rejects an empty operator chain, so decode is not labeled a
VectorProcessor binding.

Activation wall time covers runtime construction, planning or deployment, input
processing, acknowledged output and checkpoint persistence. Independent
checkpoint inspection, output scans and reference validation are excluded. Rates
count input episodes and values. Per-core rates divide by the recorded available
logical processors and do not infer single-core speed. Job process CPU time
includes the in-JVM observer and harness; it excludes Go broker CPU.

Latency subtracts successful input Produce ingress from successful output
Produce ingress at the loopback observer. Aggregate outputs use their latest
contributing input; records.csv retains all contributing ids and both
acknowledgement clocks. Kafka event/envelope timestamps are not latency clocks.
Clean equivalence checks ids, keys, event times and values, without requiring
createdAt or Kafka envelope timestamp equality.

resources.csv samples RSS and cumulative CPU for the driver, owned brokers and
observed subprocesses once per second across setup, execution and independent
validation. Short-lived processes between samples can be missed. Sampled driver
CPU is the largest observed cumulative process CPU time, not the exact
CPU-at-exit total. JVM process start, main entry, activation and I/O timings are
distinct. The host is shared; no CPU reservation or remote-network result is
claimed.

Frozen test-runtime classpath size: 110368822 bytes, including dependencies and
harness classes. Broker binary size: 38766562 bytes. These are measured file
sizes, not a minimal deployment package. Their hashes are in metadata.json.

Case labels use D for decode, C for clean and S for statistics, followed by the
partition count, W for Wayang or J for native Java, and the repetition. C4J-r2
means streaming/clean-p4-java-r2. F10-r3 means federation/k10-r3. Raw directory
names retain the full chain and runtime names.

## Read federation samples

| Case   | Route          | Layer bytes |
| ------ | -------------: | ----------: |
| F1-r1  |    centralized |     2903848 |
| F1-r1  | signal-cluster |       69649 |
| F1-r1  |      job-state |      604836 |
| F3-r1  |    centralized |     2880093 |
| F3-r1  | signal-cluster |      189735 |
| F3-r1  |      job-state |     1779492 |
| F10-r1 |    centralized |     2840541 |
| F10-r1 | signal-cluster |      565165 |
| F10-r1 |      job-state |     5817828 |
| F1-r2  |    centralized |     2909365 |
| F1-r2  | signal-cluster |       69626 |
| F1-r2  |      job-state |      604836 |
| F3-r2  |    centralized |     2878631 |
| F3-r2  | signal-cluster |      190155 |
| F3-r2  |      job-state |     1779492 |
| F10-r2 |    centralized |     2838990 |
| F10-r2 | signal-cluster |      564095 |
| F10-r2 |      job-state |     5817828 |
| F1-r3  |    centralized |     2905798 |
| F1-r3  | signal-cluster |       69708 |
| F1-r3  |      job-state |      604836 |
| F3-r3  |    centralized |     2878944 |
| F3-r3  | signal-cluster |      190563 |
| F3-r3  |      job-state |     1779492 |
| F10-r3 |    centralized |     2838167 |
| F10-r3 | signal-cluster |      565538 |
| F10-r3 |      job-state |     5817828 |

| Case   | Route          | Elapsed ms |
| ------ | -------------: | ---------: |
| F1-r1  |    centralized |     62.339 |
| F1-r1  | signal-cluster |  13041.790 |
| F1-r1  |      job-state |  12218.643 |
| F3-r1  |    centralized |     85.241 |
| F3-r1  | signal-cluster |  35163.535 |
| F3-r1  |      job-state |  31898.662 |
| F10-r1 |    centralized |    122.579 |
| F10-r1 | signal-cluster | 161126.130 |
| F10-r1 |      job-state | 137001.612 |
| F1-r2  |    centralized |     62.453 |
| F1-r2  | signal-cluster |  13275.759 |
| F1-r2  |      job-state |  11957.529 |
| F3-r2  |    centralized |     91.489 |
| F3-r2  | signal-cluster |  35585.661 |
| F3-r2  |      job-state |  33420.260 |
| F10-r2 |    centralized |    126.471 |
| F10-r2 | signal-cluster | 143624.249 |
| F10-r2 |      job-state | 124586.956 |
| F1-r3  |    centralized |     66.793 |
| F1-r3  | signal-cluster |  12958.147 |
| F1-r3  |      job-state |  11596.349 |
| F3-r3  |    centralized |     88.365 |
| F3-r3  | signal-cluster |  35807.567 |
| F3-r3  |      job-state |  31578.627 |
| F10-r3 |    centralized |    141.058 |
| F10-r3 | signal-cluster | 144363.843 |
| F10-r3 |      job-state | 122221.521 |

Layer bytes are Kafka Produce frames for centralized and signal-cluster rows,
HTTP bodies for job-state rows. Route milliseconds cover different work and are
not a speed comparison. Separate central plan clocks are retained in each
run.json; every byte/window sample remains in the case directory.

## Read numerical errors

| Case   | Max mean error         | Max M2 error           |
| ------ | ---------------------: | ---------------------: |
| F1-r1  | 1.7308017671758118e-14 | 2.7233841350225666e-12 |
| F3-r1  | 1.7308017671758118e-14 | 2.7235050829345894e-12 |
| F10-r1 | 1.7308017671758118e-14 | 2.7233841350225666e-12 |
| F1-r2  | 1.7308017671758118e-14 | 2.7233841350225666e-12 |
| F3-r2  | 1.7308017671758118e-14 | 2.7235050829345894e-12 |
| F10-r2 | 1.7308017671758118e-14 | 2.7233841350225666e-12 |
| F1-r3  | 1.7308017671758118e-14 | 2.7233841350225666e-12 |
| F3-r3  | 1.7308017671758118e-14 | 2.7235050829345894e-12 |
| F10-r3 | 1.7308017671758118e-14 | 2.7233841350225666e-12 |

Errors are the largest relative errors among reference-to-federated,
reference-to-central and federated-to-central comparisons. All three comparisons
retain separate raw fields and the same 3e-12 bound. Exact count, extrema and
sketch checks precede reporting.

## Read central plan clocks

| Sites | Rep | Transfer | Plan     | CPU      | Fetch   | Statistics |
| ----- | --: | -------: | -------: | -------: | ------: | ---------: |
| 1     |   1 |   62.339 | 2064.695 | 1244.863 | 144.582 |   2026.141 |
| 3     |   1 |   85.241 | 2077.425 | 1769.322 | 165.203 |   2040.995 |
| 10    |   1 |  122.579 | 2089.656 | 1397.161 | 176.712 |   2046.501 |
| 1     |   2 |   62.453 | 2085.289 | 1241.448 | 158.248 |   2044.158 |
| 3     |   2 |   91.489 | 2047.076 | 1104.160 | 141.019 |   2008.357 |
| 10    |   2 |  126.471 | 2097.192 | 1286.865 | 161.720 |   2050.899 |
| 1     |   3 |   66.793 | 2063.869 | 1190.507 | 140.121 |   2020.574 |
| 3     |   3 |   88.365 | 2068.034 | 1227.306 | 148.804 |   2025.946 |
| 10    |   3 |  141.058 | 2059.416 | 1263.839 | 165.541 |   2017.677 |

Fetch and first-statistics durations start at central plan invocation.
Statistics is the first native callback before grouping, not an output Produce
clock. Plan time includes compilation, native execution and grouping, excluding
transfer and independent correctness checks.

## Read streaming rates

| Case    | Episodes/s | Values/s    | Ep/s/core | Val/s/core |
| ------- | ---------: | ----------: | --------: | ---------: |
| D1W-r1  |   3584.550 | 2150729.995 |   224.034 | 134420.625 |
| D1J-r1  |   5107.116 | 3064269.325 |   319.195 | 191516.833 |
| C1W-r1  |   3757.271 | 2254362.739 |   234.829 | 140897.671 |
| C1J-r1  |   4395.128 | 2637076.874 |   274.696 | 164817.305 |
| S1W-r1  |   2927.404 | 1756442.482 |   182.963 | 109777.655 |
| S1J-r1  |   3525.738 | 2115442.867 |   220.359 | 132215.179 |
| D4W-r1  |   1493.929 |  896357.226 |    93.371 |  56022.327 |
| D4J-r1  |   1614.493 |  968695.676 |   100.906 |  60543.480 |
| C4W-r1  |   1449.694 |  869816.317 |    90.606 |  54363.520 |
| C4J-r1  |    751.280 |  450767.820 |    46.955 |  28172.989 |
| S4W-r1  |   1338.699 |  803219.448 |    83.669 |  50201.215 |
| S4J-r1  |    903.825 |  542294.896 |    56.489 |  33893.431 |
| D12W-r1 |    559.220 |  335531.748 |    34.951 |  20970.734 |
| D12J-r1 |    582.785 |  349670.762 |    36.424 |  21854.423 |
| C12W-r1 |    556.478 |  333886.819 |    34.780 |  20867.926 |
| C12J-r1 |    243.289 |  145973.186 |    15.206 |   9123.324 |
| S12W-r1 |    535.185 |  321111.110 |    33.449 |  20069.444 |
| S12J-r1 |    220.004 |  132002.564 |    13.750 |   8250.160 |
| D1W-r2  |   3836.354 | 2301812.561 |   239.772 | 143863.285 |
| D1J-r2  |   5074.231 | 3044538.533 |   317.139 | 190283.658 |
| C1W-r2  |   3761.800 | 2257079.825 |   235.112 | 141067.489 |
| C1J-r2  |   4650.926 | 2790555.414 |   290.683 | 174409.713 |
| S1W-r2  |   3079.115 | 1847468.720 |   192.445 | 115466.795 |
| S1J-r2  |   3523.019 | 2113811.113 |   220.189 | 132113.195 |
| D4W-r2  |   1489.644 |  893786.153 |    93.103 |  55861.635 |
| D4J-r2  |   1617.880 |  970727.964 |   101.117 |  60670.498 |
| C4W-r2  |   1458.832 |  875299.208 |    91.177 |  54706.201 |
| C4J-r2  |    955.647 |  573387.976 |    59.728 |  35836.748 |
| S4W-r2  |   1344.109 |  806465.351 |    84.007 |  50404.084 |
| S4J-r2  |    911.517 |  546910.003 |    56.970 |  34181.875 |
| D12W-r2 |    557.229 |  334337.561 |    34.827 |  20896.098 |
| D12J-r2 |    582.019 |  349211.574 |    36.376 |  21825.723 |
| C12W-r2 |    548.636 |  329181.358 |    34.290 |  20573.835 |
| C12J-r2 |    235.837 |  141502.203 |    14.740 |   8843.888 |
| S12W-r2 |    537.696 |  322617.445 |    33.606 |  20163.590 |
| S12J-r2 |    219.148 |  131488.751 |    13.697 |   8218.047 |
| D1W-r3  |   3940.529 | 2364317.199 |   246.283 | 147769.825 |
| D1J-r3  |   5056.405 | 3033842.973 |   316.025 | 189615.186 |
| C1W-r3  |   3858.500 | 2315099.719 |   241.156 | 144693.732 |
| C1J-r3  |   4700.123 | 2820074.051 |   293.758 | 176254.628 |
| S1W-r3  |   3073.675 | 1844204.821 |   192.105 | 115262.801 |
| S1J-r3  |   3788.175 | 2272904.843 |   236.761 | 142056.553 |
| D4W-r3  |   1487.519 |  892511.641 |    92.970 |  55781.978 |
| D4J-r3  |   1602.414 |  961448.547 |   100.151 |  60090.534 |
| C4W-r3  |   1444.858 |  866915.016 |    90.304 |  54182.189 |
| C4J-r3  |    752.330 |  451398.057 |    47.021 |  28212.379 |
| S4W-r3  |   1328.668 |  797200.510 |    83.042 |  49825.032 |
| S4J-r3  |    910.252 |  546151.135 |    56.891 |  34134.446 |
| D12W-r3 |    558.775 |  335264.927 |    34.923 |  20954.058 |
| D12J-r3 |    581.780 |  349068.280 |    36.361 |  21816.767 |
| C12W-r3 |    556.338 |  333802.887 |    34.771 |  20862.680 |
| C12J-r3 |    228.137 |  136882.284 |    14.259 |   8555.143 |
| S12W-r3 |    534.365 |  320618.876 |    33.398 |  20038.680 |
| S12J-r3 |    213.046 |  127827.799 |    13.315 |   7989.237 |

## Read streaming latency

| Case    | p50 ms   | p95 ms    | p99 ms    |
| ------- | -------: | --------: | --------: |
| D1W-r1  | 1029.606 |  1039.680 |  1042.131 |
| D1J-r1  |  231.449 |   252.846 |   258.340 |
| C1W-r1  | 1015.950 |  1119.341 |  1135.696 |
| C1J-r1  |  365.797 |   399.364 |   404.895 |
| S1W-r1  | 1317.815 |  1512.186 |  1543.728 |
| S1J-r1  |  496.641 |   602.402 |   607.142 |
| D4W-r1  | 2439.548 |  2484.239 |  2494.727 |
| D4J-r1  |  304.695 |   360.988 |   376.749 |
| C4W-r1  | 2576.193 |  2676.633 |  2676.633 |
| C4J-r1  |  337.069 |  1360.689 |  1360.689 |
| S4W-r1  | 2740.270 |  2829.660 |  2829.660 |
| S4J-r1  |  408.276 |   532.905 |  2016.729 |
| D12W-r1 | 6468.095 |  6515.559 |  6515.559 |
| D12J-r1 |  246.241 |  3279.204 |  5217.366 |
| C12W-r1 | 6541.888 |  6630.114 |  6630.114 |
| C12J-r1 |  335.384 |  5372.027 |  9818.974 |
| S12W-r1 | 6783.806 |  6836.320 |  6836.320 |
| S12J-r1 |  377.819 | 10416.914 | 10416.914 |
| D1W-r2  |  958.555 |  1000.371 |  1003.941 |
| D1J-r2  |  239.923 |   259.704 |   262.629 |
| C1W-r2  | 1019.964 |  1112.713 |  1127.485 |
| C1J-r2  |  327.388 |   369.314 |   377.250 |
| S1W-r2  | 1236.340 |  1342.063 |  1368.123 |
| S1J-r2  |  431.677 |   562.532 |   570.801 |
| D4W-r2  | 2439.704 |  2463.839 |  2477.082 |
| D4J-r2  |  255.992 |   318.145 |   318.145 |
| C4W-r2  | 2523.173 |  2599.715 |  2599.715 |
| C4J-r2  |  316.404 |   345.605 |   349.710 |
| S4W-r2  | 2732.275 |  2807.319 |  2807.319 |
| S4J-r2  |  386.521 |   522.012 |  1987.560 |
| D12W-r2 | 6484.638 |  6532.876 |  6532.876 |
| D12J-r2 |  215.440 |  3275.998 |  4716.177 |
| C12W-r2 | 6615.184 |  6675.092 |  6675.092 |
| C12J-r2 |  321.199 |  5840.977 | 10286.353 |
| S12W-r2 | 6735.411 |  6792.557 |  6792.557 |
| S12J-r2 |  416.972 | 10477.245 | 10477.245 |
| D1W-r3  |  930.240 |   977.045 |   982.575 |
| D1J-r3  |  236.448 |   269.843 |   270.113 |
| C1W-r3  | 1026.666 |  1121.718 |  1137.419 |
| C1J-r3  |  320.482 |   364.311 |   378.026 |
| S1W-r3  | 1267.599 |  1364.582 |  1387.174 |
| S1J-r3  |  430.094 |   485.989 |   499.187 |
| D4W-r3  | 2451.984 |  2492.501 |  2511.037 |
| D4J-r3  |  265.623 |   322.033 |   322.033 |
| C4W-r3  | 2545.604 |  2659.963 |  2659.963 |
| C4J-r3  |  331.241 |  1354.726 |  2806.010 |
| S4W-r3  | 2764.939 |  2890.702 |  2890.702 |
| S4J-r3  |  387.112 |   531.626 |  2001.779 |
| D12W-r3 | 6453.464 |  6487.067 |  6489.257 |
| D12J-r3 |  216.891 |  3282.792 |  4732.888 |
| C12W-r3 | 6518.913 |  6572.870 |  6572.870 |
| C12J-r3 |  322.864 |  5861.083 | 10807.838 |
| S12W-r3 | 6905.389 |  6990.257 |  6990.257 |
| S12J-r3 |  410.999 | 10948.603 | 10948.603 |

## Read streaming activation clocks

| Case    | Wall ms   | CPU ms   | Fetch ms | Output ms |
| ------- | --------: | -------: | -------: | --------: |
| D1W-r1  |  1004.310 | 1792.141 |  156.325 |   768.323 |
| D1J-r1  |   704.899 | 1262.562 |   13.320 |    31.540 |
| C1W-r1  |   958.142 | 1587.076 |  163.440 |   901.013 |
| C1J-r1  |   819.089 | 1219.324 |  103.724 |   161.167 |
| S1W-r1  |  1229.758 | 2151.483 |  172.135 |  1182.526 |
| S1J-r1  |  1021.063 | 1383.100 |   27.525 |    88.073 |
| D4W-r1  |  2409.754 | 1506.193 |  154.256 |  2277.101 |
| D4J-r1  |  2229.802 |  896.748 |   13.906 |    46.138 |
| C4W-r1  |  2483.283 | 1441.790 |  158.297 |  2436.007 |
| C4J-r1  |  4791.824 | 1377.975 |   91.643 |   154.932 |
| S4W-r1  |  2689.178 | 1906.754 |  141.606 |  2654.478 |
| S4J-r1  |  3983.073 | 1519.650 |   16.653 |    74.769 |
| D12W-r1 |  6437.543 | 1454.023 |  148.049 |  6305.806 |
| D12J-r1 |  6177.239 | 1385.236 |   15.253 |    56.447 |
| C12W-r1 |  6469.258 | 1386.771 |  156.868 |  6429.477 |
| C12J-r1 | 14797.238 | 1631.965 |   89.680 |   154.012 |
| S12W-r1 |  6726.644 | 2213.409 |  171.695 |  6691.595 |
| S12J-r1 | 16363.319 | 1953.523 |   22.270 |    87.027 |
| D1W-r2  |   938.391 | 2129.728 |  160.638 |   774.405 |
| D1J-r2  |   709.467 | 1233.647 |   13.820 |    31.896 |
| C1W-r2  |   956.989 | 2066.318 |  190.652 |   905.635 |
| C1J-r2  |   774.039 | 1349.753 |   95.339 |   152.603 |
| S1W-r2  |  1169.167 | 2166.823 |  159.568 |  1129.972 |
| S1J-r2  |  1021.851 | 1452.444 |   15.001 |    73.329 |
| D4W-r2  |  2416.685 | 1768.691 |  159.565 |  2281.176 |
| D4J-r2  |  2225.134 | 1190.679 |   25.631 |    59.181 |
| C4W-r2  |  2467.728 | 2353.612 |  185.449 |  2423.180 |
| C4J-r2  |  3767.083 | 1654.662 |   93.461 |   151.460 |
| S4W-r2  |  2678.354 | 2061.420 |  154.506 |  2646.949 |
| S4J-r2  |  3949.461 | 1888.557 |   18.030 |    77.478 |
| D12W-r2 |  6460.536 | 1609.223 |  157.401 |  6312.757 |
| D12J-r2 |  6185.362 | 1218.277 |   16.724 |    66.606 |
| C12W-r2 |  6561.732 | 1827.986 |  222.685 |  6523.068 |
| C12J-r2 | 15264.780 | 1877.319 |   94.564 |   158.144 |
| S12W-r2 |  6695.236 | 1873.366 |  144.937 |  6644.810 |
| S12J-r2 | 16427.261 | 2039.786 |   17.281 |    81.045 |
| D1W-r3  |   913.583 | 2287.950 |  160.617 |   758.343 |
| D1J-r3  |   711.968 | 1050.575 |   12.603 |    29.804 |
| C1W-r3  |   933.005 | 1909.747 |  171.335 |   880.235 |
| C1J-r3  |   765.937 | 1363.076 |   93.225 |   149.230 |
| S1W-r3  |  1171.236 | 2231.066 |  159.703 |  1125.328 |
| S1J-r3  |   950.326 | 1705.914 |   17.580 |    76.851 |
| D4W-r3  |  2420.137 | 1437.001 |  143.626 |  2255.466 |
| D4J-r3  |  2246.610 | 1237.382 |   13.345 |    49.778 |
| C4W-r3  |  2491.594 | 1496.647 |  187.535 |  2448.099 |
| C4J-r3  |  4785.134 | 1305.718 |   89.998 |   146.655 |
| S4W-r3  |  2709.481 | 1773.050 |  158.958 |  2666.731 |
| S4J-r3  |  3954.949 | 2080.006 |   20.361 |    81.127 |
| D12W-r3 |  6442.666 | 1764.576 |  156.033 |  6317.189 |
| D12J-r3 |  6187.901 | 1276.173 |   16.416 |    68.843 |
| C12W-r3 |  6470.885 | 2307.079 |  186.838 |  6428.127 |
| C12J-r3 | 15779.982 | 1934.560 |   96.037 |   160.566 |
| S12W-r3 |  6736.971 | 1962.188 |  171.512 |  6701.103 |
| S12J-r3 | 16897.733 | 1993.034 |   17.992 |    80.678 |

The central plan and streaming activation clock tables use milliseconds for
every duration column.

## Read process samples

| Case    | Driver MiB | Brokers MiB | Driver CPU s |
| ------- | ---------: | ----------: | -----------: |
| F1-r1   |    947.609 |      87.906 |       54.670 |
| F3-r1   |   1005.734 |     136.281 |      101.480 |
| F10-r1  |    998.109 |     252.250 |      288.450 |
| D1W-r1  |    807.203 |      40.344 |        7.690 |
| D1J-r1  |    801.859 |      42.703 |        7.590 |
| C1W-r1  |    785.906 |      34.406 |        8.780 |
| C1J-r1  |    800.734 |      34.250 |       10.100 |
| S1W-r1  |    830.578 |      33.516 |        6.940 |
| S1J-r1  |    732.859 |      32.141 |        8.500 |
| D4W-r1  |    872.016 |      45.531 |        7.790 |
| D4J-r1  |    798.625 |      43.734 |        6.730 |
| C4W-r1  |    728.812 |      36.203 |        8.730 |
| C4J-r1  |    790.609 |      36.031 |        8.800 |
| S4W-r1  |    769.219 |      35.766 |        7.870 |
| S4J-r1  |    781.453 |      35.531 |        8.280 |
| D12W-r1 |    846.594 |      48.422 |        7.650 |
| D12J-r1 |    753.641 |      41.219 |        7.620 |
| C12W-r1 |    862.109 |      36.203 |        8.150 |
| C12J-r1 |    789.703 |      37.266 |        9.760 |
| S12W-r1 |    861.031 |      37.750 |        9.090 |
| S12J-r1 |    735.719 |      37.594 |        7.310 |
| F1-r2   |    836.406 |      67.547 |       53.640 |
| F3-r2   |    857.234 |     115.031 |      100.950 |
| F10-r2  |   1038.859 |     219.234 |      290.390 |
| D1W-r2  |    820.734 |      40.500 |        8.870 |
| D1J-r2  |    846.375 |      42.562 |        8.220 |
| C1W-r2  |    859.734 |      33.688 |       10.480 |
| C1J-r2  |    758.531 |      33.781 |        9.240 |
| S1W-r2  |    797.688 |      33.875 |        7.810 |
| S1J-r2  |    706.969 |      32.359 |        7.360 |
| D4W-r2  |    879.781 |      44.328 |        8.030 |
| D4J-r2  |    825.281 |      43.375 |        6.480 |
| C4W-r2  |    831.781 |      35.141 |       11.500 |
| C4J-r2  |    794.266 |      36.250 |       10.830 |
| S4W-r2  |    795.734 |      35.891 |        8.840 |
| S4J-r2  |    793.922 |      34.422 |        8.470 |
| D12W-r2 |    863.422 |      49.453 |        7.240 |
| D12J-r2 |    844.719 |      46.875 |        8.110 |
| C12W-r2 |    868.172 |      36.812 |       11.790 |
| C12J-r2 |    836.609 |      35.953 |       10.250 |
| S12W-r2 |    811.281 |      37.625 |        8.870 |
| S12J-r2 |    809.281 |      36.750 |        8.620 |
| F1-r3   |    920.109 |      78.547 |       53.150 |
| F3-r3   |    896.875 |     114.688 |      101.460 |
| F10-r3  |   1075.312 |     245.422 |      297.090 |
| D1W-r3  |    782.500 |      40.688 |        8.430 |
| D1J-r3  |    813.891 |      41.984 |        7.720 |
| C1W-r3  |    801.875 |      32.594 |        9.910 |
| C1J-r3  |    802.578 |      33.750 |        8.870 |
| S1W-r3  |    741.109 |      32.891 |        7.580 |
| S1J-r3  |    773.734 |      33.891 |        7.170 |
| D4W-r3  |    882.812 |      46.188 |        6.810 |
| D4J-r3  |    873.625 |      43.328 |        8.060 |
| C4W-r3  |    801.344 |      36.203 |        9.650 |
| C4J-r3  |    796.469 |      36.109 |        8.550 |
| S4W-r3  |    843.406 |      36.406 |        7.980 |
| S4J-r3  |    718.641 |      35.297 |        7.640 |
| D12W-r3 |    890.094 |      48.266 |        8.020 |
| D12J-r3 |    729.328 |      42.359 |        6.810 |
| C12W-r3 |    864.531 |      37.172 |       10.780 |
| C12J-r3 |    706.578 |      36.281 |        9.940 |
| S12W-r3 |    797.109 |      36.766 |        7.540 |
| S12J-r3 |    772.328 |      36.703 |        7.720 |

Driver and broker columns are the largest sampled RSS values. Broker RSS sums
simultaneously observed owned brokers. Driver CPU is cumulative since process
start, including input setup and reference validation, and remains distinct from
activation CPU above.

## Repeat the measurement

You need a clean source checkout, JDK 17 or 21, Maven 3.9+, Python 3.10+, Git
and Go with go1.25.2 available. metadata.json records the exact invocation,
commands, heap, classpath and hashes. Use a new output directory.

```bash
python3 tools/wayang_track_a_evaluation.py --help
```

The default matrix runs three repetitions, 1/3/10 federation sites and both
runtimes for every chain and partition count. --pilot labels any reduced matrix
as excluded from A5. The paced 5/30/60-second increment measurements are
separate A1.7 evidence; this report does not claim paced statistics/partition
coverage, the 30-kill recovery protocol, Track B parity or the section 14.2
small-device matrix.

Completed conditions: 63. Raw files and their hashes are listed in
metadata.json. Private logs, certificates and runtime directories are excluded
from the public artifact list.
