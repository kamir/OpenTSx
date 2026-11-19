# opentsx-modbus: generic Modbus data access

This module reads meters and other devices over Modbus. It decodes the values with a register map in YAML and
turns them into v2 episodes ([`opentsx-model`](../opentsx-model)). It belongs to stage A of P9 (data access,
[TASK-011](../EVOLUTION/TASK-011-datamodel-refresher-2026.md)). The first use case is the grid operator's meters
at the wind farm / electrolyzer site, which are read through a converter at the fibre termination.

The Modbus client, framing and simulator use **only the JDK**: sockets, no Modbus library. The extra dependencies
are Jackson YAML, which the other modules use for their YAML files too, `opentsx-conf` for the command line and the
SLF4J API.

> **The real register sheets are not available yet.** The shipped map
> [`example-grid-meter.yaml`](src/main/resources/modbus/example-grid-meter.yaml) is an **example**. It contains
> typical points with made-up addresses and is marked with `example: true`. Episodes made from it carry the label
> `registermap.example=true`. Replace it with the vendor's sheet when it arrives.

## Try it

```bash
mvn -q install -DskipTests
bin/opentsx modbus check    --map example                       # validate, list points, show the read plan
bin/opentsx modbus read     --map example --simulate            # one cycle against an in-JVM simulated meter
bin/opentsx modbus simulate --map example --port 1502 &         # serve the simulated meter on 127.0.0.1:1502
bin/opentsx modbus read     --map example --host 127.0.0.1 --port 1502
bin/opentsx modbus poll     --map example --host 127.0.0.1 --port 1502 --interval 1s --count 10            # CSV
bin/opentsx modbus poll     --map example --host 127.0.0.1 --port 1502 --out episodes --episode PT1M \
                            --file episodes.jsonl                                                         # v2 episodes
```

`--map` takes a file or the word `example`. Every option is a key of the configuration registry, so it can also come
from `--conf <key>=<value>`, an `OPENTSX_MODBUS_...` environment variable or `--properties-file <path>`.
`bin/opentsx modbus <command> --help` lists the keys of a command with their defaults, and `bin/opentsx modbus --help`
lists every command of `bin/opentsx`, among them `modbus check`, `modbus poll`, `modbus read` and `modbus simulate`.
`help` is not a command: `bin/opentsx modbus help` exits with code 2. The CLI options are:

| Command | Options |
|---------|---------|
| `check` | validates the map, prints the points and the read plan (requests per cycle) |
| `read` | `[--host h] [--port p] [--unit u] [--simulate [--seed 42]]`: one poll cycle as a table with value, unit, OBIS and quality. The exit code is 1 if a block failed |
| `poll` | `[--count n]` (0 = until Ctrl-C), `[--interval 5s]`, `[--out csv\|episodes]`, `[--file f]`, `[--episode PT1M]`, `[--simulate [--seed 42]]`. CSV columns: `timestamp,device,point,metric,value,unit,obis,origin,quality`. With `episodes`, a summary is printed per closed window, and with `--file` each episode is written as one line of Avro JSON. The exit code is 1 if a block failed in any cycle |
| `simulate` | `[--port 1502] [--bind 127.0.0.1] [--seed 42] [--seconds n]`: serves the map's points with the profiles from their `sim:` blocks. The default `127.0.0.1` serves this host only; `--bind 0.0.0.0` serves every interface |

`--interval` and `--episode` take ISO-8601 (`PT5S`) or a whole number with the unit `ms`, `s`, `m`, `min`, `h` or `d`
(`500ms`, `5s`); `1.5s` is refused. The `pollInterval` of the register map also takes decimals. `--port` takes 1 to
65535, `--unit` 0 to 255.

`--simulate` is honest about where the data comes from: the episodes and CSV lines carry `origin=SYNTHETIC`, not
the map's `MEASURED`.

`--seed` needs `--simulate`. `--simulate` refuses `--host` and `--port`, because the simulated meter listens on a free
loopback port. Both exit with code 2.

## Read the errors

The options and the map are checked before the first connection. A usage or configuration error prints one line and
exits with code 2, for example:

```text
opentsx: --prot: unknown option of modbus check; did you mean --port?
opentsx: --port: "abc" is not an int; expected a whole number from -2147483648 to 2147483647
opentsx: /x.yaml: no such file; give an existing register map file or example
opentsx: --count: set twice on the command line (also by --count); set each key once
opentsx: opentsx.modbus.simulator.seed: needs opentsx.modbus.simulate; give --simulate with --seed
opentsx: opentsx.modbus.host: cannot be combined with opentsx.modbus.simulate; give --host or --simulate, not both
```

A map with problems prints `opentsx: register map <file> is invalid:` and its problems on the same line, separated by
`; `, also with code 2, for example
`opentsx: register map bad.yaml is invalid: device: missing (id, host, port, unitId, pollInterval); points: at least one point is required`.
Any other failure prints `opentsx: <message>` with its causes and exits with code 1.

`poll` prints the first failed read of a block on stderr, then, while reads keep failing, one count per minute, a
count of the failures not printed yet when it ends, and one line when reads succeed again. A meter that is switched off
writes one line per minute, not one per block and cycle. Output of 20 cycles against a closed port:

```text
2026-11-19T02:55:47.500Z read failed: discrete 0..0 (1, 1 points): connection to tcp://127.0.0.1:55051 failed: Connection refused (attempt 2)
2026-11-19T02:56:08.400Z read failed 99 more times since 2026-11-19T02:55:47.500Z; last: input 20..23 (4, 2 points): connection to tcp://127.0.0.1:55051 failed: Connection refused (attempt 2)
```

## Register map (`opentsx.modbus.registermap.v1`)

```yaml
schema: opentsx.modbus.registermap.v1
name: example-grid-meter
version: 0.1.0
example: true                       # template, not a real sheet
device:
  id: grid-meter-01                 # becomes tag device=...
  host: 192.0.2.10
  port: 502
  unitId: 1
  framing: tcp                      # tcp (MBAP) | rtu-over-tcp
  pollInterval: PT5S                # ISO-8601 or 500ms / 5s / 1m
  timeout: PT2S
  retries: 1
  maxRegistersPerRequest: 125       # protocol maximum
  maxGap: 0                         # unused registers a merged read may span
tags: { site: example-site, meter: grid-connection }
defaults: { function: input, wordOrder: big, byteSwap: false, origin: MEASURED, addressBase: 0, metricPrefix: grid.meter }
points:
  - name: active_power_net
    function: holding               # holding | input | coil | discrete (or 1-4)
    address: 100                    # protocol offset (addressBase 1 = 1-based sheet numbering)
    type: int32                     # bool uint16 int16 uint32 int32 uint64 int64 float32 float64
    wordOrder: little               # big = high word first ("ABCD"), little = low word first ("CDAB")
    byteSwap: false                 # swap the two bytes of each word ("BADC" / "DCBA")
    scale: 0.001                    # value = raw * scale + offset
    offset: 0
    unit: kW
    obis: "1-0:16.7.0"
    origin: MEASURED                # MEASURED | REPORTED | FORECAST | ESTIMATED | SYNTHETIC
    kind: gauge                     # gauge | counter (monotonic; wrap/reset detection)
    min: -30000                     # plausibility band (flag, not drop)
    max: 30000
    sim: { profile: sine, mean: -3500, amplitude: 6000, period: PT30M, noise: 50 }   # simulator only
```

**Validation** reports every problem in one `RegisterMapException`, each with its location. Examples:
`points 'a' (holding 10..11, float32) and 'b' (holding 11..11, uint16) overlap`,
`points[0] 'a'.type: unknown type 'float16' (...)`, `function coil returns bits, so type must be bool`,
`obis '1.8.0' is not an OBIS code like 1-0:1.8.0`, `address 400001 outside 0..65535 (a 3xxxx/4xxxx register
number? ...)`, unknown fields (`adress`), and unknown origins. Sim references are checked too.

**Simulator profiles** (`sim:`): `constant` (`value`); `sine` (`mean`, `amplitude`, `period`, `phase`, `noise`,
`min`/`max`); `clamp` (the positive or negated negative part of another point, e.g. import/export power from net
power); `integrate` (`start + factor * ∫source dt`, an energy counter that wraps like its register type).

## Pipeline

```
register map ──► BlockPlanner ──► Poller ──► RawReading ──► MeterNormalizer ──► v2 Episode (+ quality bytes)
                 (merged reads)   (ModbusClient)  (device, point, ts,   (series key, counters,
                                                   value, raw)           plausibility, labels)
```

| Part | Class | Notes |
|------|-------|-------|
| Framing | `Framing.Mbap`, `Framing.Rtu`, `Crc16` | MBAP header with transaction id, protocol 0, length and unit. RTU: unit + PDU + CRC-16/MODBUS; the length is derived from the function code and byte count |
| PDUs | `Pdu`, `FunctionCode` | read functions 0x01–0x04 with their limits (125 registers / 2000 bits); request, response and exception response |
| Errors | `ModbusExceptionResponse`, `ModbusTimeoutException`, `ModbusProtocolException` (all `ModbusException extends IOException`) | exception codes with name and hint, e.g. `ILLEGAL_DATA_ADDRESS: address range not served (check ... 0- vs. 1-based addresses, gaps in merged blocks)` |
| Transport | `TcpTransport` | one request in flight; transaction ids 1..65535. Late MBAP answers with older ids are skipped. After a timeout or I/O error the socket is closed, and the next attempt reconnects (`retries`, `reconnectDelay`). An exception response keeps the connection |
| Serial RTU | none | **not supported**: the JDK has no serial API. Use a converter with `rtu-over-tcp` or Modbus TCP |
| Client | `ModbusClient` | `readHoldingRegisters`, `readInputRegisters`, `readCoils`, `readDiscreteInputs` |
| Codec | `RegisterCodec`, `DataType`, `WordOrder` | byte swap per word → word order → big-endian decode; `encode` for the simulator |
| Read plan | `BlockPlanner` | per function, sorted by address. Points are merged while the gap is ≤ `maxGap` and the block is ≤ `maxRegistersPerRequest` (≤ 125). A point is never split. The example map needs 5 requests per cycle |
| Poller | `Poller` | one `RawReading` per point and cycle, with a shared timestamp (start of the cycle). A failing block gives NaN plus an error for its points; the other blocks still count. `run()` aligns cycles to multiples of the interval |
| Normalizer | `MeterNormalizer`, `CounterTracker`, `Quality` | see below |
| Simulator | `RegisterImage`, `MeterProfile`, `ModbusTcpServer`, `MeterSimulator` | in-JVM server (MBAP or RTU over TCP) that serves only the defined addresses (strict like real meters). Test hooks: response delay, forced exception responses, dropped connections |

### Normalization

`MeterNormalizer` has the same shape as the wind farm's `Normalizer`: fixed windows aligned to the epoch,
`FIXED_DURATION` segmentation, REGULAR encoding when the spacing is regular, and quality bytes only when a value is
flagged. The wind-farm normalizer cannot be reused. It is turbine-specific: it resolves channels through the
IEC 61400-25 catalog and checks turbines against the park inventory, and the module pulls in Kafka and Iceberg.
Here the register map plays the catalog's role, so a small generic equivalent is used.

* **Series**: `metric` of the point. Tags are the map tags, plus `device`, plus the point tags. The unit is the
  point's unit.
* **Labels**: `registermap` (name@version), `source.point`, `modbus.function`, `modbus.address`, `modbus.type`,
  `obis`, `origin`, `kind`, and `registermap.example=true` for template maps. Provenance source is
  `modbus:<device>` and the pipeline is `modbus-normalizer`.
* **Quality**: codes 0–4 as in the wind-farm catalog (`GOOD`, `IMPLAUSIBLE`, `MISSING`, `SUBSTITUTED`,
  `QUESTIONABLE`), plus **5 `COUNTER_WRAP`** and **6 `COUNTER_RESET`**.
* **Counters** (`kind: counter`) are judged on the raw register value and tracked across batches:
  * If the value goes up or stays the same, it is `GOOD`.
  * If an integer counter falls from the top quarter of its range into the bottom quarter, it is `COUNTER_WRAP`
    (overflow; `CounterTracker.increment` adds `2^bits`).
  * Any other decrease is `COUNTER_RESET` (meter reset or exchange; the increment is unknown).
  * The value itself is never altered.

The episodes are ordinary v2 episodes. Write them to KafScale with `BucketWriter`
([`opentsx-kafka-v2`](../opentsx-kafka-v2)) or to any `SeriesStore`. The CLI writes them to stdout or a file
only, so this module stays free of Kafka dependencies.

## When the real register sheets arrive

1. Copy the example map. Set `example: false`, the converter's `host`/`port`, the `unitId`, and `framing`. Many
   converters at a fibre termination are serial device servers: use `rtu-over-tcp` if they forward raw RTU frames,
   and `tcp` if they act as a Modbus gateway.
2. Enter the points from the sheet. Check the numbering: sheets often say `30001`/`40001`. Use `addressBase: 1`
   with the plain register number, or subtract to get the offset. Check the word order of 32/64-bit values and the
   scaling of the energy registers.
3. `bin/opentsx modbus check --map site.yaml`, then `read` against the real device. Compare a few values with the
   meter display, especially the OBIS 1.8.0 / 2.8.0 counters.
4. If the meter rejects long reads (`ILLEGAL_DATA_VALUE`) or gaps (`ILLEGAL_DATA_ADDRESS`), lower
   `maxRegistersPerRequest` or keep `maxGap: 0`.

## Tests

`mvn -q install -pl opentsx-modbus -am`. The tests cover:

* codec round trips for every type × word order × byte swap, plus known layouts
* CRC-16 vectors, including the example from the serial-line spec
* MBAP/RTU frame parsing, exception responses and malformed frames
* block merging (adjacency, gaps, the 125 limit, functions kept apart)
* every validation error at once
* client against the in-JVM server: all four functions, exception codes, gateway unit id, timeout with retries,
  reconnect, connection refused, stale transaction ids, RTU over TCP
* end-to-end poll of the example map against the simulator, with the decoded values equal to the simulated ones
* episodes and labels, counter wrap/reset, implausible and missing values
* CLI `check`, `read` and `poll`, the options, the help and the failure lines of `poll`
* the Job `modbus-poll` and the simulator Deployment load their configuration in the namespace of `deploy/k3s/base`,
  and a `--count` appended to the Job command, as `40-demo.sh modbus -- --count 300` does, overrides its 30 cycles
