# Inspect and preserve a workbench analysis

Use the analysis views in the frontend package version 1.0.0 to inspect local
results and preserve a finite run. You need a current frontend build and a
browser on localhost or HTTPS for SHA-256 snapshot operations. Browser
computation requires no account. Continuous deployment still requires the
account backend, an administrator, retrieval access and the supported Java
processor.

## Inspect episodes and quality

Open `/workbench`, load episode JSON or select data in the TS inventory, and
select **Episodes and quality**. The view lists stable series ids, units, sample
counts, gaps, flagged samples and unknown quality. Expand episode identities to
inspect ranges, labels, parent ids and supplied segmentation settings.

The loader keeps quality bytes in sample order. If only some episodes supply
quality, a separate availability mask keeps the others unknown. Code 0 means
good; other codes keep their source-defined meaning. A missing quality array is
not a clean-data declaration. An operator that creates a new time axis drops
inherited quality unless it computes a new quality array. The view then reports
unknown quality.

The API JSON includes episode labels and a parent id, but not every field of the
full provenance record. The UI does not invent missing provenance. Plausibility
controls use the signal's expected range; they do not infer an operating
constraint from observed values.

## Compare pair measurements

Apply **Pair coverage**, **Cross-correlation** or **Event synchronisation**
under **Multi-variate Analysis**. Select the resulting node and open **Pair
results**.

Choose a field and a window centre. Filter and sort pairs in the table; select a
row to inspect its input episode ids and measured fields. The matrix shows only
computed directions. An absent cell is not zero. **All windows** shows a table
instead of combining distinct windows into one matrix.

Positive cross-correlation lag means B follows A. Event synchronisation shows
both directions and the measured surrogate baseline `q_rand`. A baseline
comparison is not a p-value or proof of causality. This view displays the
existing measurements; it does not calculate a reconstructed network or
introduce another numerical method.

## Inspect typed analysis results

Open **Analysis results** to paste or load a JSON representation of an
`AnalysisResult`. The view checks the shared result identity before displaying
scalar values, standard errors, fit ranges, non-time curves, matrices, input
episodes and provenance. Download the inspected JSON from the view.

The import uses the
[shared result contract](../datamodel-2026/04-MODELL-V2-SPEC.md#8-analysis-results).
Parameter types matter to identity. Encode long, double, string and double-array
parameters explicitly as `{ "long": "7" }`, `{ "double": 7 }`,
`{ "string": "text" }` and `{ "array": [1, 2] }`. Bare strings are accepted.
Bare numeric parameters are rejected because JSON cannot preserve an Avro
long-versus-double distinction. Nonfinite result values use explicit JSON
strings such as `"NaN"`; bare NaN is not JSON.

This is local JSON inspection. It does not read an Avro stream or call a new
server endpoint. Browser DFA output is still a bucket with its curve and fit
information; importing a shared typed result is a separate action.

## Preserve a finite local run

Select **Snapshot**, then **Download local snapshot**. The file
`opentsx-local-analysis.json` preserves:

- Actual input samples, absolute time origin, identities, units, quality and
  episode metadata.
- The graph, parameters, seeds in those parameters and signal context.
- The selected output at export time.
- Frontend package version, finite-browser execution mode and a SHA-256
  integrity digest.

The build revision, broker offsets and catalog version are explicitly
unavailable in this snapshot format. The snapshot is an embedded local input,
not a Kafka offset replay or a signed research artifact. SHA-256 detects changed
content; it does not authenticate authorship.

Import validates the schema, integrity, graph and data limits before replacing
local work. It opens the input source as an unsaved local flow. Review a step
before selecting it. Import does not publish, deploy or write a stored flow
under an imported account id. Captured output remains archived in the JSON;
selecting a graph node recalculates it from the restored input.

The JSON limit is 20 MiB, with at most 128 series per bucket, 500,000 samples
per series, 2,000,000 encoded typed-array values and 500 graph nodes. Invalid
identity references, mismatched quality lengths, unknown operators, cycles,
malformed arrays and tampered content are rejected. Use **Export sample CSV**
only for sample exchange. It keeps stable series ids but does not preserve
units, absolute time, quality or the graph; ids containing CSV separators
require the snapshot format.

Browser evaluation also refuses analyses beyond its work limits: 1,000,000 input
or aligned/output cells, 100,000 pair windows, 100,000,000 estimated operations
and 200 pairs. It asks for a shorter range, fewer series/lags/shuffles, or a
larger clock/step. These are conservative browser work estimates, not a measured
performance promise.

## Check deployment capability

The Properties tab shows browser preview, Java processor and Wayang capability
separately. An available numerical kernel does not make a Wayang DAG deployable.

The Smart Topic preflight checks every published sink and its upstream path. A
valid vector branch cannot make an unsupported pair branch runnable. Unsupported
branches remain usable in the browser; pair nodes in an exported draft carry
`experimental: true`. Statistics preview parameters do not count as deployable
catalog parameters.

Download exports a draft; Deploy is a separate action. The server remains the
final validation and permission boundary. This UI change adds no new continuous
operator or Wayang plan service.
