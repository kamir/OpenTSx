import { useState } from "react";
import type { ChangeEvent } from "react";
import type { FlowDoc } from "./flow";
import type { Bucket } from "./types";
import { createRunBundle, MAX_BUNDLE_BYTES, parseRunBundle } from "./runBundle";

interface Props {
  source: Bucket | null;
  result: Bucket | null;
  doc: FlowDoc;
  onImport: (source: Bucket, doc: FlowDoc) => void;
}

export default function RunBundlePanel({
  source,
  result,
  doc,
  onImport,
}: Props) {
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  async function download() {
    if (!source) return;
    setBusy(true);
    setError("");
    setStatus("");
    let url: string | undefined;
    try {
      const content = await createRunBundle(source, result, doc);
      url = URL.createObjectURL(
        new Blob([content], { type: "application/json" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = "opentsx-local-analysis.json";
      link.click();
      setStatus("Local snapshot exported with SHA-256 integrity.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Local snapshot export failed");
    } finally {
      if (url) URL.revokeObjectURL(url);
      setBusy(false);
    }
  }
  async function importFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setBusy(true);
    setError("");
    setStatus("");
    try {
      if (file.size > MAX_BUNDLE_BYTES)
        throw new Error("Bundle size limit exceeded");
      const run = await parseRunBundle(await file.text());
      onImport(run.source, run.doc);
      setStatus(
        "Verified source and graph restored locally. Captured output is retained in the file; no deployment was started.",
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Local snapshot import failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section aria-label="Local analysis snapshot">
      <h3>Local analysis snapshot</h3>
      <p>
        Capture input samples, selected output, graph, parameters and frontend
        version. Build revision is unknown. Broker offsets and catalog version
        are unavailable.
      </p>
      <p>
        Finite browser analysis only. Import replaces local work; it does not
        publish, deploy or replay Kafka offsets. SHA-256 detects changed
        content, not authorship.
      </p>
      <button disabled={!source || busy} onClick={() => void download()}>
        Download local snapshot
      </button>
      <label>
        Import local snapshot
        <input
          aria-label="Import local snapshot"
          type="file"
          accept="application/json,.json"
          disabled={busy}
          onChange={(event) => void importFile(event)}
        />
      </label>
      {status && <p role="status">{status}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
