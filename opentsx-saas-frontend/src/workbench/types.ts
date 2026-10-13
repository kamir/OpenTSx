/**
 * Data model of the web workbench.
 *
 * A Bucket is what the Swing workbench passed around as Vector<TimeSeriesObject>:
 * a named set of series that share one kind of x axis.
 */

export type AxisKind = 'time' | 'index' | 'frequency' | 'lag' | 'value' | 'scale';

export interface Series {
  id: string;
  label: string;
  unit?: string;
  /** Metric name when the source carried one. */
  metric?: string;
  /** Tags of the series when the source carried them (episodes of a retrieval server). */
  tags?: Record<string, string>;
  /** In a vector bucket: the series id the vector belongs to, and the name of the component. */
  key?: string;
  component?: string;
  x: Float64Array;
  y: Float64Array;
}

export interface Bucket {
  name: string;
  xKind: AxisKind;
  xLabel: string;
  yLabel: string;
  series: Series[];
  /** Epoch milliseconds of x = 0 when xKind is 'time' and the source carried absolute timestamps. */
  t0Ms?: number;
  /** Free text shown in the properties tab, for example the fitted DFA exponents. */
  note?: string;
  /** Set by align.vector: clock in seconds and the component names in vector order. */
  vector?: { clock: number; components: string[]; keyBy: string };
}

export type ParamValue = number | string;
export type Params = Record<string, ParamValue>;

export interface ParamDef {
  key: string;
  label: string;
  type: 'number' | 'select' | 'text';
  default: ParamValue;
  options?: string[];
  min?: number;
  step?: number;
}

/** Tabs of TSOperationControlerPanel, plus the stream operators of opentsx.dag.v1. */
export type OpGroup = 'transform' | 'univariate' | 'group' | 'stream';

export interface OpDef {
  /** Operator id as written into the DAG (`op:`). */
  id: string;
  /** Name in the stream operator catalog when it differs from the id. */
  dagOp?: string;
  /** Button text, taken from the Swing panel where the operation exists there. */
  label: string;
  group: OpGroup;
  /** Prefix of the derived bucket name, as in the Swing macro recorder ("FFT_", "Diff_"). */
  prefix: string;
  /** Operation name written into the Processing Flow Descriptor (TASK-005). */
  pfdOperation: string;
  /**
   * True when the operator is listed in the stream operator catalog of
   * docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 6.
   */
  inCatalog: boolean;
  hint: string;
  params: ParamDef[];
  /** True when the operator reads further inputs besides its first one (`FlowNode.inputs`). */
  multiInput?: boolean;
  apply: (input: Bucket, params: Params, others?: Bucket[]) => Bucket;
}

export interface FlowNode {
  id: string;
  /** 'source' or an OpDef id. */
  op: string;
  params: Params;
  /** Upstream node. Null for the source. The track tree and the bucket names follow this input. */
  input: string | null;
  /** Further inputs of a multi-input operator, in order after `input`. */
  inputs?: string[];
  /** Name of the bucket this node produces (the "target" of the Swing macro recorder). */
  label: string;
  /** Position in the graph view, set once the user drags the node. */
  pos?: { x: number; y: number };
  /** Output topic when this node is published as a Smart Topic. */
  sinkTopic?: string;
}

export interface SourceInfo {
  /** 'generator' | 'file' | 'retrieval' | 'inventory' */
  kind: string;
  detail: string;
}
