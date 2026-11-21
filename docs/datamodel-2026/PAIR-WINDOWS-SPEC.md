# Pair windows and pair methods

This page specifies how OpenTSx compares two series window by window and what the pair methods `pair.xcorr`, `pair.es` and `pair.coverage` write; sections 1 to 9, 11, 13, 14 and 16 were verified against the code with the per-episode pair clock (fix of 2026-11-20), the others against commit `a513aa98`.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they
appear in capitals.

You need the data model v2 for series identity, the episode time axis and quality bytes:
[04-MODELL-V2-SPEC.md](04-MODELL-V2-SPEC.md).

Terms:

| Term | Meaning |
|---|---|
| A, B | the two series a pair method compares; A is the first of the pair |
| pair range | the time range `[start, end)` of one episode of A and one episode of B that a pair method compares |
| window | a part of a pair range that gives one result item |
| centre | the middle of a window; the time stamp of its result item |
| clock, tick | the common time grid of A and B in a window; tick `k` covers `[k*every, (k+1)*every)` seconds since the epoch |
| result item | the values of all fields of a method for one window |
| result episode | the result items of one pair range for one field |
| `TOL` | half a microsecond, `5e-7` s; two times closer than `TOL` count as equal where this page says so |

Source paths used in the "Implemented in" lines:

| Short path | Repository path |
|---|---|
| `workbench/` | `opentsx-saas-frontend/src/workbench/` |
| `wayang/` | `opentsx-wayang/src/main/java/org/opentsx/wayang/` |
| `runner/` | `rust/tsx-runner/src/` |

<a id="episode-ranges"></a>

## 1. Read episode ranges

A pair method pairs episodes by their time ranges. The web workbench keeps the range of every loaded episode in
`Bucket.episodes`, one `EpisodeRange` per episode.

| Field | Type | Value |
|---|---|---|
| `seriesId` | string | `Series.id` of the series the episode belongs to |
| `episodeId` | string | `episodeId` of the episode; `<seriesId>@<start in epoch microseconds>` when it carries none |
| `start` | number, seconds | inclusive start, seconds from `x = 0` of the bucket |
| `end` | number, seconds | exclusive end, seconds from `x = 0` of the bucket |
| `strategy` | string, optional | `segmentation.strategy` of the episode |
| `labels` | map of string, optional | labels of the episode |
| `parent` | string, optional | `parentEpisodeId` of the episode |
| `segmentation` | object, optional | `strategy`, `params` and `anchorTs` of the episode |

* `start` MUST be `tStart` and `end` MUST be `tEnd`, both read to the microsecond.
* Without `tStart`, `start` is the first sample. Without `tEnd`, `end` is the last sample plus `intervalMicros`, or
  plus 1 microsecond when the episode has no `intervalMicros`. This is the default `tEnd` of
  [04-MODELL-V2-SPEC.md, section 3](04-MODELL-V2-SPEC.md#3-time-axis-of-an-episode).
* `x = 0` of the bucket is the earliest sample or range start of all loaded episodes. `Bucket.t0Ms` holds it in epoch
  milliseconds.
* `Bucket.episodes` is sorted by `seriesId`, then by `start`. An episode without samples gives no range.
* A series without episode ranges (a generated or file bucket) counts as one range from its first sample to its last
  sample plus one sampling interval, taken on its x values ([section 6](#alignment)). Its episode id is
  `<Series.id>@<first x>`.
* Operators that keep the time axis hand `Bucket.episodes` on. Operators that build a new axis drop them.

Implemented in: `workbench/loaders.ts:128-219`, `workbench/types.ts:49-62`, `workbench/episodes.ts:9-11`,
`workbench/pairs.ts:99-114`.

<a id="parameters"></a>

## 2. Set pair parameters

Every pair method reads the same pair parameters. The method parameters are in sections 11 to 13.

| Parameter | Type | Allowed values | Operator default | Value when absent | Effect |
|---|---|---|---|---|---|
| `pairing` | text | `same`, `intersect` | `same` | `same` | how episodes of A and B form pair ranges ([section 4](#pairing)) |
| `window` | text | `episode`, empty, or a width in seconds greater than 0 | `episode` | `episode` | empty means `episode` |
| `step` | number, seconds | any number | `0` | width | a value of 0 or less sets the step to the width; ignored for `window: episode` |
| `every` | number, seconds | any number | `0` | the clock of each pair range | a value of 0 or less selects, per pair range, the larger sampling interval of its two episodes ([section 6](#alignment)) |
| `agg` | text | `MEAN`, `LAST`, `FIRST`, `MIN`, `MAX`, `SUM`, `COUNT` | `MEAN` | `MEAN` | aggregate of the samples in one tick |
| `fill` | text | `none`, `as-of` | `none` | `none` | any other value is refused with `pair fill must be none or as-of` |
| `maxAge` | number, seconds | any number | `60` | `0` | used with `fill: as-of` only; negative or non-numeric values become 0 |
| `minCoverage` | number | `[0, 1]` | `0.8`; `0` for `pair.coverage` | `0.8` | non-numeric values become 0.8 |

* An implementation MUST refuse an unknown `pairing`, an unknown `agg`, a `fill` other than `none` or `as-of`, a
  `window` that is neither `episode` nor a width greater than 0, and a `minCoverage` outside `[0, 1]`. The workbench,
  Wayang and the Rust runner refuse each of them. The messages are in [section 14](#limits-errors).
* "Operator default" is the value the workbench form starts with. "Value when absent" is what the parser takes when
  the key is missing.

Implemented in: `workbench/pairs.ts:75-91`, `workbench/ops.ts:764-775`, `wayang/ops/TsxPairXcorr.java:29-60`,
`runner/pairs.rs:741-847`.

<a id="pairs"></a>

## 3. Choose the pairs

* A pair method reads one or two inputs with a time axis. The first input gives A, the second input gives B.
* One input: every pair of its series in bucket order. For series `i < j`, series `i` is A and series `j` is B.
* Two inputs: each series of the first input with each series of the second input.
* B is put on the time axis of A: its times are shifted by `(t0Ms of B)-(t0Ms of A)`, in seconds. When B carries no
  `t0Ms`, the shift is 0.
* The workbench MUST NOT compare more than 200 pairs in one run (`MAX_PAIRS`). Above that it refuses the run and asks
  for a selection of series first.
* The workbench MUST refuse an input series whose times are out of order: a sample after the first whose time is not
  greater than or equal to the time before it, which includes a time that is not a number. Equal times are allowed.
  Wayang and the Rust runner read episodes, whose timestamps are strictly increasing
  ([04-MODELL-V2-SPEC.md, section 3](04-MODELL-V2-SPEC.md#3-time-axis-of-an-episode)).
* The Wayang operator `TsxPair` pairs every series of input `a` with every series of input `b`. With one input it
  pairs the declared series in their declared order.

Implemented in: `workbench/pairs.ts:233-252`, `workbench/pairs.ts:258-265`, `workbench/workLimits.ts:103`,
`wayang/JavaTsxPair.java:76-104`.

<a id="pairing"></a>

## 4. Pair the episodes

A pair range is the time range that one episode of A and one episode of B both cover.

| `pairing` | Pair range | Dropped |
|---|---|---|
| `same` | the episode of B with the same start and the same end as the episode of A; the range of A | an episode without a counterpart |
| `intersect` | each overlapping episode of B, on the intersection `[max(starts), min(ends))` | an intersection of `TOL` or less, or shorter than the window width minus `TOL` |

* `same` fits two series cut by the same `FIXED_DURATION` segmentation, which gives both series the same ranges.
* `same` compares start and end after rounding each to whole microseconds (workbench, Wayang). The Rust runner
  compares the integer microseconds of `tStart` and `tEnd`.
* For `intersect` with `window: episode` the minimum length is `TOL`.
* The `intersect` rule assumes that the episodes of one series do not overlap each other. The workbench walks both
  lists in start order and advances the one whose current episode ends first; with overlapping episodes in one
  series it can skip pairs.
* The pair ranges of one pair are sorted by start.

Implemented in: `workbench/pairs.ts:127-150`, `wayang/ops/TsxPairWindows.java:145-155`, `runner/pairs.rs:1399-1415`.

<a id="windows"></a>

## 5. Cut windows and stamp centres

`start` and `end` are the bounds of the pair range, `L = end-start`, `W` the window width and `S` the step.

```text
window k  = [start+k*S, start+k*S+W)
centre k  = start+k*S+W/2
n         = floor((L-W)/S + 1e-9) + 1     when L >= W-TOL, otherwise 0
```

| `window` | Windows per pair range | Centre of window `k` | Result per pair range |
|---|---|---|---|
| `episode` (`W = L`) | 1 when `L > TOL`, otherwise 0 | `start+L/2` | one item |
| width `W`, step `S` | `n` | `start+k*S+W/2` | `n` items, `S` apart |

* A window that would end after `end` MUST NOT be computed.
* With `S < W` the windows overlap. With `S = W` they lie side by side. With `S > W` they leave gaps.
* The time of a result item MUST be the centre of its window, not a sample time of A or B.
* A pair range shorter than the window gives no item. The workbench counts these ranges in the note of the result
  bucket (`<n> pair range(s) shorter than the window`).

Implemented in: `workbench/pairs.ts:159-168`, `wayang/ops/TsxPairXcorr.java:115-137`, `runner/pairs.rs:982-1014`.

<a id="alignment"></a>

## 6. Align A and B

A and B are put on one clock by the rules of `align.vector`
([11-SMART-TOPIC-PROZESSOR-DEVOPS.md](VECTOR-AND-DAG-SPEC.md), section 5a).

* Clock: `every` when it is greater than 0. Otherwise each pair range takes its own clock: the larger of the sampling
  intervals of its episode of A and its episode of B. The pair ranges of one pair can run on different clocks.
* Sampling interval of an episode: the median spacing of the consecutive times of its samples, element `floor(m/2)`
  of the `m` sorted spacings. A sample time is its time in epoch microseconds divided by `1e6`, a binary64 number of
  seconds. The interval is 1 s when the episode has fewer than two samples or the median is not greater than 0.
* The workbench, Wayang and the Rust runner MUST get the same clock to the bit. Wayang and the Rust runner take the
  timestamps of the episode. The workbench takes the samples of the series whose time on the axis of A lies in
  `[start-TOL, end-TOL)` of the episode range, as epoch microseconds `round(x*1e6)+round(t0Ms*1000)`, with `x` in
  seconds on the axis of A and `t0Ms` of A.
* The work limits of the workbench ([section 14](#limits-errors)) count the ticks of each pair range on its clock.
* The note of the workbench result bucket names the clocks of the pair ranges that have a window, to 6 significant
  digits: `clock <c> s` when they all show the same value, `clock <smallest> to <largest> s` otherwise. Without such a
  pair range the note names no clock.
* Ticks are multiples of `every` seconds since the epoch. Tick `k` covers `[k*every, (k+1)*every)`.
* Value of a tick: the aggregate `agg` of the finite samples of the series in the tick. A tick without a finite sample
  has no value (NaN).
* Fill: with `fill: as-of`, a tick without a value takes the last value when that value is at most
  `floor(maxAge/every)` ticks old. Without fill it stays NaN.
* Ticks of a pair range: from the first tick that starts at or after `start` to the last tick that ends at or before
  `end`, with tolerance `TOL`. The fill MUST NOT reach back across `start`: a pair range is computed from its own
  samples.
* Ticks of a window: only the ticks of the pair range that lie entirely inside the window count.
* A method receives two arrays of equal length, one value per tick of the window for A and for B, NaN where a side
  has no value, and the clock `every`.

Implemented in: `workbench/pairs.ts:99-106`, `workbench/pairs.ts:180-223`, `workbench/pairs.ts:269-288`,
`workbench/pairs.ts:306-328`, `workbench/pairs.ts:378-390`, `workbench/workLimits.ts:46-62`,
`workbench/vector.ts:32-93`, `wayang/JavaTsxPair.java:131-139`, `wayang/ops/TsxPairWindows.java:158-169`,
`wayang/ops/TsxPairWindows.java:180-267`, `runner/pairs.rs:1112-1176`, `runner/pairs.rs:1418-1431`.

<a id="coverage"></a>

## 7. Measure coverage and set quality

* Coverage of a window: the number of ticks in which both A and B have a value, divided by the number of ticks in the
  window. A window without ticks has coverage 0.
* A result item MUST be computed when the window holds at least one tick and its coverage is at least `minCoverage`
  (compared with a tolerance of `1e-12`).
* Otherwise the item MUST be kept with the value NaN in every field and the quality byte 1, so the time index of the
  result stays regular.
* A computed item has the quality byte 0. This holds also when the method itself gives NaN, for example `pair.xcorr`
  on a window in which A does not vary.
* The quality bytes are one byte per value, 0 = ok, as `Episode.quality`
  ([04-MODELL-V2-SPEC.md, section 4](04-MODELL-V2-SPEC.md#4-values-quality-summary)).
* In the workbench the quality bytes are `Series.quality`. They belong to the x values they were computed for. An
  operator that builds a new x array (resample, FFT, histogram) drops the quality bytes of its input.

Implemented in: `workbench/pairs.ts:326-341`, `workbench/flow.ts:426-434`, `workbench/types.ts:23-27`,
`wayang/ops/TsxPairWindows.java:264-284`.

<a id="result-series"></a>

## 8. Name the result series

A pair method gives one or more fields per window. Each field of each pair is a series of its own.

| Part | Value |
|---|---|
| metric | `pair.<method>.<field>`, for example `pair.xcorr.lag` |
| tag `a` | series id of A |
| tag `b` | series id of B |
| tag `window` | `PT<W>S` with `W` in seconds to at most 12 significant digits, for example `PT120S`; `episode` for `window: episode` |
| tag `step` | `PT<S>S`; `episode` when `window` is `episode` |
| series id | lower-case hex of xxHash64 over the canonical form of metric and tags, as for any series ([04-MODELL-V2-SPEC.md, section 2](04-MODELL-V2-SPEC.md#2-series-identity)) |

* The values of a result series are the items of all pair ranges of the pair, in the order of the pair ranges.
* The workbench computes the series id in the browser (`seriesKeys.ts`), checked against the shared vectors
  `opentsx-model/src/test/resources/testvectors/series-id.json`.
* The workbench gives each result series the label `<field>(<label of A>, <label of B>)`.
* The workbench result bucket has the name `<prefix><name of the first input>`, a time axis, and `t0Ms` and `marks`
  of the first input. The prefixes are `Coverage_`, `XCorr_` and `ES_`.

Implemented in: `workbench/pairs.ts:93-96`, `workbench/pairs.ts:350-393`, `workbench/seriesKeys.ts:84-92`,
`wayang/ops/TsxPairWindows.java:172-178`, `wayang/JavaTsxPair.java:146-154`, `runner/pairs.rs:1178`,
`runner/pairs.rs:1487-1503`.

<a id="result-episodes"></a>

## 9. Write result episodes and lineage

Each pair range with at least one window MUST give one result episode per field, in both window settings.

| `window` | `tStart` | `tEnd` | `timeEncoding` | Values |
|---|---|---|---|---|
| width `W`, step `S` | first centre | first centre plus `n*S` | `REGULAR`, `intervalMicros` = `S` in microseconds | `n` |
| `episode` | the centre | the centre plus 1 microsecond | `IRREGULAR_DELTA` | 1 |

* Times are written in microseconds, rounded from seconds. The workbench keeps the same `[tStart, tEnd)` in seconds
  in `EpisodeRange.start` and `EpisodeRange.end`; it has no time encoding.
* The quality bytes of the episode are those of [section 7](#coverage).

Labels of a result episode:

| Label | Value |
|---|---|
| `pair.a` | episode id of the episode of A |
| `pair.b` | episode id of the episode of B |
| `pair.method` | `pair.coverage`, `pair.xcorr` or `pair.es` |
| `pair.window` | the tag `window` of [section 8](#result-series) |
| `pair.step` | the tag `step` of [section 8](#result-series) |
| `pair.<setting>` | each method setting: `pair.lags` for `pair.xcorr`; `pair.sigma`, `pair.tauMax`, `pair.shuffles` and `pair.seed` for `pair.es` |
| `specHash` | Wayang and Rust runner only: the `specHash` of the DAG document |

Lineage:

* The parent of a result episode MUST be the episode of A: `Provenance.parentEpisodeId` in Wayang and the Rust runner,
  `EpisodeRange.parent` in the workbench. `Provenance.parentEpisodeId` holds one id, so B is named by the label
  `pair.b` only.
* Episode id in the workbench: `<series id>@<tStart in seconds from x = 0>`.
* Episode id in Wayang and the Rust runner: `p` followed by the lower-case hex of xxHash64 over the UTF-8 bytes of
  `<series id>|<episode id of A>|<episode id of B>|<specHash>`. `Provenance.source` is `opentsx-wayang`.

Implemented in: `workbench/pairs.ts:342-374`, `wayang/JavaTsxPair.java:145-172`, `runner/pairs.rs:1503-1583`.

<a id="lag-sign"></a>

## 10. Read the lag sign

* A lag `tau > 0` MUST mean that B follows A by `tau`: what happens in A at `t` shows in B at `t+tau`.
* `pair.xcorr`: `CC(k)` pairs A at `t` with B at `t-k`, so B following A by `d` ticks peaks at `k = -d`. The field
  `lag` is `-k*every` in seconds. It is computed as `(lags-index)*every`, so lag 0 is written as `0`, never as `-0`.
* `pair.es`: `q_ab` counts events of B after events of A. `q_ab > q_ba` is the direction that `pair.xcorr` reports
  as `lag > 0`.

Implemented in: `workbench/xcorr.ts:107-113`, `workbench/es.ts:63-92`, `wayang/ops/TsxPairWindows.java:52-58`.

<a id="pair-xcorr"></a>

## 11. Compute pair.xcorr

`pair.xcorr` gives the lagged cross-correlation of A and B in each window, its peak and the link strength.

| Parameter | Type | Allowed values | Default | Label |
|---|---|---|---|---|
| `lags` | integer, clock ticks | 0 or more; at most 1073741823 in Wayang and the Rust runner | 10 | `pair.lags` |

| Field | Unit | Value |
|---|---|---|
| `max` | none | `CC` at the peak, with its sign |
| `lag` | seconds | `-k*every` for the peak shift `k`; `> 0` when B follows A |
| `strength` | none | `(abs(max)-mean(CC))/std(CC)` over the `2*lags+1` values of `CC` |

The order matters. `N` is the number of ticks of the window, gaps included.

1. Mean and sample standard deviation of A and of B over their finite values. The standard deviation is NaN below
   two finite values.
2. When the standard deviation of A or of B is not greater than 0, every `CC(k)` is NaN and all three fields are NaN.
3. `x[t] = A[t]-mean(A)` and `y[t] = B[t]-mean(B)`. A tick without a value counts as 0, the mean of its series.
4. For `k = -lags..lags`: `CC(k) = sum(x[t]*y[t-k] for t in [max(0,k), min(N,N+k))) / ((N-1)*std(A)*std(B))`.
   `CC(0)` is the Pearson correlation of a window without gaps.
5. Peak: `hi` is the first index of the largest `CC`, `lo` the first index of the smallest. The peak is `lo` when
   `abs(CC[hi]) < abs(CC[lo])`, otherwise `hi`.
6. `max = CC[peak]`, `lag = (lags-peak)*every` with `peak` as an index into `CC` (`k+lags`), and
   `strength = (abs(max)-mean(CC))/std(CC)` with the sample standard deviation. With `lags: 0`, `strength` is NaN.

Origin: `KreuzKorrelation.calcKRCoeffizient`, `KreuzKorrelation.calcKR_SLIDINGWINDOW` and
`CCFunction.calcStrength_VERSION_A` of kamir/WikiExplorer.NG at commit `7b56e3d` (Apache License 2.0).

* `max` and `lag` belong to the extreme of `abs(CC)` that `strength` measures. The original `t_max` looks at
  positive values only and is not used.
* Every window that fits is computed. With `lags: 0`, a step of one tick and the same width, the first `n-1` values
  equal the output of `calcKR_SLIDINGWINDOW`, which stops one window early.

Implemented in: `workbench/xcorr.ts:35-114`, `wayang/ops/TsxPairXcorr.java:62-113`,
`wayang/ops/TsxPairWindows.java:37-61`, `runner/pairs.rs:752-767`, `runner/pairs.rs:908-980`.

<a id="pair-es"></a>

## 12. Compute pair.es

`pair.es` gives the event synchronisation of A and B in each window after Quiroga, Kreuz and Grassberger
(Phys. Rev. E 66, 041904, 2002), with a measured baseline against chance.

| Parameter | Type | Allowed values | Default | Label |
|---|---|---|---|---|
| `sigma` | number | finite | 2 | `pair.sigma` |
| `tauMax` | number, seconds | 0 or more; 0 means no cap | 0 | `pair.tauMax`: `PT<tauMax>S`, or `none` for 0 |
| `shuffles` | integer | 1 or more; at most 2147483647 in Wayang | 10 | `pair.shuffles` |
| `seed` | integer | any whole number | 1 | `pair.seed` |

| Field | Value |
|---|---|
| `q_total` | `(c(B\|A)+c(A\|B))/sqrt(mA*mB)`, in `[0, 1]` |
| `q_ab` | `c(B\|A)/sqrt(mA*mB)`: B after A |
| `q_ba` | `c(A\|B)/sqrt(mA*mB)`: A after B |
| `q_rand` | mean `q_total` of A against `shuffles` surrogates of B |

The order matters. `N` is the number of ticks of the window.

1. Events of a side: tick indices `i` with `0 < i < N-1`, `v[i] > mean+sigma*std`, `v[i] > v[i-1]` and
   `v[i] > v[i+1]`. Mean and population standard deviation are taken over the finite values of the window. A tick
   without a value is never an event, and a tick next to such a tick is none either. The first and the last tick are
   never events.
2. `mA` and `mB` are the numbers of distinct events. When A or B has no event, all four fields MUST be 0.
3. Local window of an event: half the smaller distance to the neighbouring event of the same side, infinite for a
   single event. For an event pair, `tau` is the smaller of the two local windows, capped at `tauMax/every` ticks
   when `tauMax > 0`.
4. For each event of A, only the first event of B at or after it and the event of B before that are compared. With
   `d` = time of the event of B minus time of the event of A: `d = 0` adds 0.5 to `c(B|A)` and 0.5 to `c(A|B)`;
   `0 < d < tau` adds 1 to `c(B|A)`; `0 < -d < tau` adds 1 to `c(A|B)`.
5. Surrogate of B: the sorted distinct events of B with the intervals between them shuffled by Fisher-Yates from the
   last interval down (`j = floor(r*(i+1))`), rebuilt from the first event by the running sum of the intervals. A side
   with fewer than three events stays unchanged. Rates and intervals stay; the timing relative to A goes.
6. `r` comes from one `mulberry32(seed)` stream, drawn in turn for all surrogates of a window. The state starts at the
   seed modulo `2^32` as an unsigned 32-bit integer.

* `q_rand` is measured per window. The closed form for random events is not used.
* Strictly periodic events keep their timing under any shuffle. There `q_rand` equals `q_total`, and the pair cannot
  be told from chance.
* The Python package is the reference: `EventSynchronization.detect_events`, `calculate_synchronization`,
  `surrogate_sync`, `isi_shuffle` and `Mulberry32` in `python-package/opentsx/algorithms/event_sync.py` draw the same
  numbers and the same surrogates.

Implemented in: `workbench/es.ts:26-165`, `workbench/random.ts:5-14`, `wayang/ops/TsxPairEs.java:12-127`,
`wayang/ops/Mulberry32.java`, `wayang/ops/TsxPairWindows.java:64-104`,
`python-package/opentsx/algorithms/event_sync.py:8-41`, `python-package/opentsx/algorithms/event_sync.py:73-261`.

<a id="pair-coverage"></a>

## 13. Compute pair.coverage

`pair.coverage` shows what the other pair methods see. It has no parameters of its own.

| Field | Value |
|---|---|
| `coverage` | the coverage of the window ([section 7](#coverage)) |
| `ticks` | the number of ticks in the window |

* The workbench operator "Pair coverage" starts with `minCoverage: 0`, so every window with at least one tick gives
  a value.
* `pair.coverage` runs in the workbench only.

Implemented in: `workbench/pairs.ts:397-403`, `workbench/ops.ts:831-833`.

<a id="limits-errors"></a>

## 14. Handle limits and errors

Browser limits of the workbench. A run above a limit is refused before it starts. The limits count the ticks of each
pair range on its clock ([section 6](#alignment)).

| Limit | Value |
|---|---|
| pairs per run (`MAX_PAIRS`) | 200 |
| samples of all inputs | 1,000,000 |
| aligned cells, two per tick of every pair range | 1,000,000 |
| pair ranges of one pair | 100,000 |
| windows of all pairs | 100,000 |
| lag range `2*lags+1` | 1,000,000 |
| `shuffles` | 100,000,000 |
| estimated products of all windows | 100,000,000 |

Errors:

| Message | Fix |
|---|---|
| `<op>: browser work limit exceeded. Select fewer series, a shorter range or fewer lags/shuffles; use a larger clock or window step.` | Reduce the pairs, the range, `lags` or `shuffles`, or raise `every` or `step`. |
| `<n> pairs, more than 200. Select fewer series first ("Select series").` | Put "Select series" before the pair operator. |
| `<name> has <n> series. A pair needs two: add a second input or load more series.` | Add a second input or load more series. |
| `One of the two inputs has no series.` | Load series into both inputs. |
| `A pair method reads two inputs at most: A and B.` | Remove the third input. |
| `<name> has a <kind> axis. A pair method needs inputs with a time axis.` | Use inputs with a time axis. |
| `<label> has times out of order. A pair method needs them in increasing order.` | Put the samples of the series `<label>` in increasing time order. |
| `Pairing "<value>" is unknown. Use same or intersect.` | Set `pairing` to `same` or `intersect`. |
| `The window "<value>" is neither episode nor a width in seconds greater than 0.` | Set `window` to `episode` or a positive number of seconds. |
| `Aggregate "<value>" is unknown.` | Use one of the `agg` values of [section 2](#parameters). |
| `pair fill must be none or as-of` | Set `fill` to `none` or `as-of`. |
| `The minimum coverage is a share between 0 and 1.` | Set `minCoverage` in `[0, 1]`. |
| `lags "<value>" is not a whole number of clock ticks, 0 or more.` | Set `lags` to a whole number of 0 or more. |
| `sigma "<value>" is not a number.` | Set `sigma` to a finite number. |
| `tauMax "<value>" is not a duration in seconds, 0 or more.` | Set `tauMax` to 0 or more seconds. |
| `shuffles "<value>" is not a whole number of 1 or more.` | Set `shuffles` to a whole number of 1 or more. |
| `seed "<value>" is not a whole number.` | Set `seed` to a whole number. |

Implemented in: `workbench/workLimits.ts:6-13`, `workbench/workLimits.ts:27-68`, `workbench/workLimits.ts:100-105`,
`workbench/pairs.ts:75-91`, `workbench/pairs.ts:233-265`, `workbench/xcorr.ts:95-99`, `workbench/es.ts:131-141`,
`wayang/ops/TsxPairXcorr.java:48-51`, `runner/pairs.rs:816-819`.

<a id="runtimes"></a>

## 15. Run pair operators

| Runtime | Methods | Entry |
|---|---|---|
| web workbench | `pair.coverage`, `pair.xcorr`, `pair.es` | tab "Multi-variate Analysis": "Pair coverage", "Cross-correlation", "Event synchronisation" |
| Wayang, Java platform | `pair.xcorr`, `pair.es` | `TsxPair` executed by `JavaTsxPair` ([20-WAYANG-TS-OPERATORS-SPEC.md, S1.7](20-WAYANG-TS-OPERATORS-SPEC.md#s17-catalog-1)) |
| Rust runner `tsx-runner` | `pair.xcorr` | bounded profile of [20-WAYANG-TS-OPERATORS-SPEC.md, S1.13](20-WAYANG-TS-OPERATORS-SPEC.md#s113-execute-the-isolated-local-rust-b2-catalog); `pair.es` is rejected |

* The workbench operators have `group: 'multivariate'`, `multiInput: true` and `maxInputs: 2`.
* Catalog 1 lists `pair.xcorr` and `pair.es`, not `pair.coverage`
  ([VECTOR-AND-DAG-SPEC.md, section 4](VECTOR-AND-DAG-SPEC.md#4-stream-operator-catalog)). Of the pair nodes, the
  DAG export writes `# not in the stream operator catalog` after the `op` of `pair.coverage` only. It writes the
  line `experimental: true` under every pair node.
* The vector processor does not run pair nodes. The deploy checks refuse a published pair node with
  `<label> (<op>): Runs in the browser on this path. Native Java Wayang mappings are available. This workbench
  deployment path does not execute pair operators. This output is not a supported processor sink.`
* Every runtime MUST follow sections 3 to 12 for the methods it runs.

Implemented in: `workbench/ops.ts:831-847`, `workbench/ops.ts:935-946`, `workbench/flow.ts:650-651`,
`workbench/flow.ts:726-727`, `workbench/capabilities.ts:12-26`, `wayang/JavaTsxPair.java:45-53`,
`systems/processors/pair.xcorr.yaml`, `systems/processors/pair.es.yaml`.

<a id="test-vectors"></a>

## 16. Check against the test vectors

| File | Cases | Written by | Read by | Tolerance |
|---|---|---|---|---|
| [`testvectors/pair-xcorr.v1.json`](testvectors/pair-xcorr.v1.json) | 5 | `tools/pair_xcorr_vectors.sh`, from the original code of kamir/WikiExplorer.NG at `7b56e3d` | `workbench/xcorr.test.ts`, `TsxPairXcorrVectorsTest`, `rust/tsx-runner/tests/pair_vector_vectors.rs` | `5e-13` |
| [`testvectors/pair-es.v1.json`](testvectors/pair-es.v1.json) | 8 | `tools/pair_es_vectors.py`, from the Python reference | `workbench/es.test.ts`, `python-package/tests/test_event_sync.py`, `TsxPairEsVectorsTest` | `5e-13`; the Python test uses `1e-12` |
| [`testvectors/pair-clock.v1.json`](testvectors/pair-clock.v1.json) | 3, and 2 refusals | `tools/pair_clock_vectors.sh`, from the Wayang kernel `TsxPairWindows` | `workbench/pairs.test.ts`, `TsxPairClockVectorsTest`, `rust/tsx-runner/tests/pair_vector_vectors.rs` | `5e-13` for values; clocks and centres exactly |

* `tools/pair_xcorr_vectors.sh` compiles `KreuzKorrelation` and `CCFunction` unchanged except for the import of
  `TimeSeriesObject`, which it takes from `opentsx-core`. The chart, FFT and shuffle classes they import are stubbed
  and not called.
* An implementation of `pair.xcorr` or `pair.es` MUST reproduce every case of its file within the tolerance.
* `pair-clock.v1.json` holds episodes without `every` whose sampling interval changes from 1 s to 10 s between
  episodes or whose samples lie up to 0.2 s off the middle of each second, with the clock of each pair range
  ([section 6](#alignment)), the `pair.xcorr` and `pair.es` values of each pair range, and two `fill` values that
  every runtime refuses with `pair fill must be none or as-of`.
* `workbench/pairs.test.ts` checks the clock of each pair range, the `pair.xcorr` and `pair.es` values, the clocks in
  the note of `pair.coverage`, and the refusals for `pair.coverage`, `pair.xcorr` and `pair.es`.
  `TsxPairClockVectorsTest` checks the clock of each pair range, the `pair.xcorr` and `pair.es` values through a
  Wayang plan, and the refusals. `pair_vector_vectors.rs` checks the `pair.xcorr` values and the refusals; the Rust
  runner has no `pair.es`. `pair.coverage` runs in the workbench only.
* The rules of sections 3 to 9 are tested in `workbench/pairs.test.ts`.

Implemented in: `tools/pair_xcorr_vectors.sh`, `tools/pair_es_vectors.py`, `workbench/xcorr.test.ts:60-95`,
`workbench/es.test.ts:50-68`, `opentsx-wayang/src/test/java/org/opentsx/wayang/ops/TsxPairXcorrVectorsTest.java`,
`opentsx-wayang/src/test/java/org/opentsx/wayang/ops/TsxPairEsVectorsTest.java`, `tools/pair_clock_vectors.sh`,
`workbench/pairs.test.ts:304-367`, `opentsx-wayang/src/test/java/org/opentsx/wayang/TsxPairClockVectorsTest.java`,
`rust/tsx-runner/tests/pair_vector_vectors.rs:613-751`.
