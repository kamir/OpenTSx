import { parseNodes, SOURCE_ID, toDefinition, useFlow } from "./flow";
import type { FlowDoc } from "./flow";
import type { Bucket } from "./types";

export function restoreLocalRun(source: Bucket, doc: FlowDoc): void {
  const nodes = parseNodes(doc.nodes);
  if (!nodes) throw new Error("The snapshot flow cannot be restored.");
  const definition = toDefinition({ ...doc, nodes, savedId: null });
  useFlow.getState().clear();
  if (!useFlow.getState().restore(definition, 0))
    throw new Error("The snapshot flow cannot be restored.");
  useFlow.getState().loadBucket(source, {
    kind: "snapshot",
    detail: "Verified finite local snapshot",
  });
  useFlow.setState({
    nodes,
    selectedId: SOURCE_ID,
    savedId: null,
    sourceSeries: doc.sourceSeries ?? [],
    context: doc.context ?? null,
  });
}
