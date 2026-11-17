import { Bucket } from "./types";

export default function EpisodeInspector({ bucket }: { bucket: Bucket }) {
  return (
    <section
      className="wb-evidence"
      aria-label="Episode quality and provenance"
    >
      <h3>Episode quality and provenance</h3>
      <p>
        Quality codes are retained as supplied. Code 0 means good; other codes
        are source-defined. Missing quality is unknown. Gaps are counted
        separately.
      </p>
      <p>
        Time origin:{" "}
        {bucket.t0Ms === undefined
          ? "No absolute time origin supplied"
          : new Date(bucket.t0Ms).toISOString()}
        . Axis: {bucket.xLabel}.
      </p>
      <div className="wb-table-scroll">
        <table className="wb-table">
          <thead>
            <tr>
              <th>Series id</th>
              <th>Unit</th>
              <th>Samples</th>
              <th>Gaps</th>
              <th>Flagged</th>
              <th>Quality unknown</th>
            </tr>
          </thead>
          <tbody>
            {bucket.series.map((s) => {
              let gaps = 0;
              let flagged = 0;
              let unknown = 0;
              for (let i = 0; i < s.y.length; i++) {
                if (!Number.isFinite(s.y[i])) gaps++;
                if (!s.quality || s.qualityKnown?.[i] === 0) unknown++;
                else if (s.quality[i] !== 0) flagged++;
              }
              return (
                <tr key={s.id}>
                  <td>{s.id}</td>
                  <td>{s.unit ?? "Not supplied"}</td>
                  <td>{s.y.length}</td>
                  <td>{gaps}</td>
                  <td>{flagged}</td>
                  <td>{unknown}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <details>
        <summary>
          Episode identities and lineage ({bucket.episodes?.length ?? 0})
        </summary>
        <div className="wb-table-scroll">
          <table className="wb-table">
            <thead>
              <tr>
                <th>Episode</th>
                <th>Series</th>
                <th>Range [start, end)</th>
                <th>Parent</th>
                <th>Segmentation and labels</th>
              </tr>
            </thead>
            <tbody>
              {bucket.episodes?.map((e, i) => (
                <tr key={`${e.episodeId}-${i}`}>
                  <td>{e.episodeId}</td>
                  <td>{e.seriesId}</td>
                  <td>
                    {e.start} to {e.end}
                  </td>
                  <td>{e.parent ?? "Not supplied"}</td>
                  <td>
                    <pre>
                      {JSON.stringify(
                        {
                          strategy: e.strategy,
                          segmentation: e.segmentation,
                          labels: e.labels,
                        },
                        null,
                        2,
                      )}
                    </pre>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}
