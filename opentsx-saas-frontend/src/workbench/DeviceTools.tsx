import { useState } from "react";
import { capabilityOf } from "./capabilities";
import { descendants, evaluate, inputsOf, useFlow } from "./flow";
import { OPS, OP_BY_ID, defaultParams } from "./ops";
import type { Bucket, OpDef, ParamDef, Params, ParamValue } from "./types";
import { checkBrowserWork } from "./workLimits";
import "./device-tools.css";

const GROUPS = ["Prepare", "Analyze", "Compare", "Model", "Stream"];
const PREPARATION = new Set([
  "select",
  "dq.plausible-range",
  "convert.unit",
  "resample",
  "align.vector",
  "segment.threshold",
]);
const LABELS: Record<string, string> = {
  "norm.max": "Normalize by maximum",
  "norm.zscore": "Standardize",
  logreturn: "Log returns",
  diff: "Difference",
  acf: "Autocorrelation",
  "entropy.window": "Window entropy",
};
const SUMMARIES: Record<string, string> = {
  "align.vector": "Map selected signals onto a shared clock.",
  resample: "Aggregate samples into regular time windows.",
  "dq.plausible-range": "Keep values in range, with explicit gaps or clipping.",
  "convert.unit": "Apply a factor and offset with a declared output unit.",
  "segment.threshold": "Cut episodes around threshold crossings.",
  "stats.window": "Calculate one statistic per time window.",
  "feature.lag": "Use earlier samples as a lagged feature.",
  "feature.rolling": "Calculate a trailing statistic over a time window.",
  "smooth.movavg": "Smooth each signal with a trailing moving average.",
  fft: "Inspect the one-sided amplitude spectrum.",
  acf: "Measure dependence on earlier samples.",
};
const VECTOR_LABELS: Record<string, string> = {
  every: "Sample interval [s]",
  agg: "Aggregation",
  key: "Vector grouping",
  fill: "Missing samples",
  maxAge: "Maximum age [s]",
  emit: "Emit rule",
  names: "Component names",
  lateness: "Allowed lateness [s]",
};

function groupOf(op: OpDef): string {
  if (op.id.startsWith("model.")) return "Model";
  if (PREPARATION.has(op.id)) return "Prepare";
  if (op.id === "fft" || op.group === "univariate") return "Analyze";
  if (op.group === "multivariate" || op.group === "group") return "Compare";
  return op.group === "stream" ? "Stream" : "Prepare";
}

function labelOf(op: OpDef): string {
  return LABELS[op.id] ?? op.label;
}

function parameterLabel(op: OpDef, field: ParamDef): string {
  return op.id === "align.vector"
    ? (VECTOR_LABELS[field.key] ?? field.label)
    : field.label;
}

function originLabel(kind: string | undefined, synthetic: boolean): string {
  if (synthetic || kind === "generator") return "Synthetic data";
  return kind === "file"
    ? "File input"
    : kind === "inventory" || kind === "retrieval"
      ? "Catalog input"
      : "Origin not stated";
}

interface SourcesProps {
  bucket: Bucket | null;
  draftOp: string | null;
  onChooseOperator: (id: string) => void;
  onModelling: () => void;
}

export function DeviceSources({
  bucket,
  draftOp,
  onChooseOperator,
  onModelling,
}: SourcesProps) {
  const state = useFlow();
  const [query, setQuery] = useState("");
  const [expanded, setExpanded] = useState<string[]>(["Prepare"]);
  const search = query.trim().toLowerCase();
  const operators = OPS.filter((op) =>
    `${op.id} ${op.label} ${labelOf(op)} ${op.hint}`
      .toLowerCase()
      .includes(search),
  );
  const forecasting =
    !search || "statistical forecasting arima sarima sarimax".includes(search);
  const selected = state.nodes.find((node) => node.id === state.selectedId);
  const synthetic =
    state.context?.synthetic === true || state.sourceInfo?.kind === "generator";
  const input = state.source ?? bucket;

  return (
    <aside className="device-sources" aria-label="Sources and operators">
      <div className="device-tool-heading">
        <h2>Sources</h2>
        <span aria-hidden="true">⌁</span>
      </div>
      <div className="device-source-card">
        <strong>{input?.name ?? "No source loaded"}</strong>
        <code>
          {state.sourceInfo?.kind === "inventory" ||
          state.sourceInfo?.kind === "retrieval"
            ? state.sourceTopic
            : (state.sourceInfo?.kind ?? "Choose a source to begin")}
        </code>
        <span className={`device-origin-badge${synthetic ? " synthetic" : ""}`}>
          {originLabel(state.sourceInfo?.kind, synthetic)}
        </span>
      </div>
      {input ? (
        <ul className="device-source-signals" aria-label="Source signals">
          {input.series.map((series, index) => (
            <li key={`${series.id}-${index}`}>
              <span>{series.label}</span>
              <small>{series.unit ?? "Unit not supplied"}</small>
            </li>
          ))}
        </ul>
      ) : (
        <p className="device-source-origin">
          Load episodes, open a file or choose a sample from the toolbar.
        </p>
      )}
      {state.sourceInfo && (
        <p className="device-source-origin">{state.sourceInfo.detail}</p>
      )}
      <section className="device-tools-section" aria-label="Operator library">
        <div className="device-tool-heading">
          <h2>Operators</h2>
          <small>{OPS.length}</small>
        </div>
        <label className="device-operator-search">
          Find an operator
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search name, method or purpose"
          />
        </label>
        <div className="device-operator-groups">
          {GROUPS.map((group) => {
            const entries = operators.filter((op) => groupOf(op) === group);
            const hasForecasting = group === "Model" && forecasting;
            if (entries.length === 0 && !hasForecasting) return null;
            const open = !!search || expanded.includes(group);
            return (
              <section className="device-operator-group" key={group}>
                <button
                  className="device-group-toggle"
                  aria-expanded={open}
                  onClick={() =>
                    setExpanded((current) =>
                      current.includes(group)
                        ? current.filter((value) => value !== group)
                        : [...current, group],
                    )
                  }
                >
                  <span>
                    {open ? "⌄" : "›"} {group}
                  </span>
                  <small>{entries.length + (hasForecasting ? 1 : 0)}</small>
                </button>
                <div hidden={!open}>
                  {entries.map((op) => (
                    <button
                      key={op.id}
                      className="device-operator-item"
                      aria-label={`Configure ${op.label}`}
                      aria-pressed={(draftOp ?? selected?.op) === op.id}
                      title={SUMMARIES[op.id] ?? labelOf(op)}
                      onClick={() => onChooseOperator(op.id)}
                    >
                      {labelOf(op)}
                      <small>{op.id}</small>
                    </button>
                  ))}
                  {hasForecasting && (
                    <button
                      className="device-operator-item"
                      aria-label="Open statistical forecasting"
                      onClick={onModelling}
                    >
                      Statistical forecasting
                      <small>ARIMA / SARIMA / SARIMAX</small>
                    </button>
                  )}
                </div>
              </section>
            );
          })}
          {operators.length === 0 && !forecasting && (
            <p className="device-tool-empty" role="status">
              No operators match this search.
            </p>
          )}
        </div>
      </section>
      {state.nodes.length > 0 && (
        <section className="device-tools-section" aria-label="Processing steps">
          <div className="device-tool-heading">
            <h2>Processing steps</h2>
            <small>{state.nodes.length}</small>
          </div>
          <ol className="device-flow-list">
            {state.nodes.map((node, index) => (
              <li key={node.id}>
                <button
                  aria-current={
                    node.id === state.selectedId ? "step" : undefined
                  }
                  onClick={() => state.select(node.id)}
                >
                  {String(index + 1).padStart(2, "0")}{" "}
                  {node.op === "source"
                    ? "Source episodes"
                    : OP_BY_ID[node.op]
                      ? labelOf(OP_BY_ID[node.op])
                      : `Unsupported: ${node.op}`}
                  <small>{node.label}</small>
                </button>
              </li>
            ))}
          </ol>
        </section>
      )}
    </aside>
  );
}

function ParameterField({
  op,
  field,
  value,
  onChange,
}: {
  op: OpDef;
  field: ParamDef;
  value: ParamValue;
  onChange: (value: ParamValue) => void;
}) {
  const label = parameterLabel(op, field);
  return (
    <label className="device-parameter-field">
      {label}
      {field.type === "select" ? (
        <select
          value={String(value)}
          onChange={(event) => onChange(event.target.value)}
        >
          {(field.options ?? []).map((option) => (
            <option key={option} value={option}>
              {op.id === "align.vector" && field.key === "fill"
                ? option === "none"
                  ? "Leave gaps"
                  : "As-of fill"
                : option}
            </option>
          ))}
        </select>
      ) : (
        <input
          type={field.type === "number" ? "number" : "text"}
          value={value}
          min={field.min}
          step={field.type === "number" ? "any" : undefined}
          onChange={(event) =>
            onChange(
              field.type === "number" && event.target.value !== ""
                ? Number(event.target.value)
                : event.target.value,
            )
          }
        />
      )}
    </label>
  );
}

function validateParameters(op: OpDef, params: Params): void {
  if (op.id === "select") {
    try {
      RegExp(String(params.pattern), "i");
    } catch {
      throw new Error("Pattern is not a valid regular expression.");
    }
  }
  for (const field of op.params) {
    const value = params[field.key];
    const label = parameterLabel(op, field);
    if (field.type === "number") {
      if (typeof value !== "number" || !Number.isFinite(value))
        throw new Error(`${label} requires a finite number.`);
      if (field.min !== undefined && value < field.min)
        throw new Error(`${label} must be at least ${field.min}.`);
    } else if (
      typeof value !== "string" ||
      (field.type === "select" && !field.options?.includes(value))
    ) {
      throw new Error(`${label} has an unsupported value.`);
    }
  }
}

export function DeviceInspector({
  draftOp,
  onApplied,
}: {
  draftOp: string | null;
  onApplied: () => void;
}) {
  const state = useFlow();
  const [drafts, setDrafts] = useState<Record<string, Params>>({});
  const [edits, setEdits] = useState<
    Record<
      string,
      { baseline: string; source: Bucket | null; op: string; params: Params }
    >
  >({});
  const [failure, setFailure] = useState<{
    key: string;
    message: string;
  } | null>(null);
  const [notice, setNotice] = useState("");
  const node = state.nodes.find((item) => item.id === state.selectedId);
  const op = draftOp ? OP_BY_ID[draftOp] : node && OP_BY_ID[node.op];
  const unknown =
    draftOp && !op
      ? draftOp
      : !draftOp && node && node.op !== "source" && !op
        ? node.op
        : null;
  const key = draftOp ? `draft:${draftOp}` : (node?.id ?? "source");
  const baseline = JSON.stringify(node?.params ?? {});
  const params = op
    ? draftOp
      ? (drafts[op.id] ?? defaultParams(op))
      : node &&
          edits[node.id]?.baseline === baseline &&
          edits[node.id].source === state.source &&
          edits[node.id].op === node.op
        ? edits[node.id].params
        : (node?.params ?? {})
    : {};
  const error = failure?.key === key ? failure.message : null;
  const inputIds = draftOp
    ? node
      ? [node.id]
      : []
    : node
      ? inputsOf(node)
      : [];
  const inputNodes = inputIds.map((id) =>
    state.nodes.find((item) => item.id === id),
  );
  const prohibited = node
    ? descendants(state.nodes, node.id, true)
    : new Set<string>();
  const full = op?.maxInputs !== undefined && inputIds.length >= op.maxInputs;
  const candidates =
    op?.multiInput && !draftOp && !full
      ? state.nodes.filter(
          (item) => !prohibited.has(item.id) && !inputIds.includes(item.id),
        )
      : [];
  const primary =
    op?.id === "align.vector"
      ? op.params.filter((field) =>
          ["every", "agg", "key", "fill"].includes(field.key),
        )
      : (op?.params.slice(0, 4) ?? []);
  const advanced = op?.params.filter((field) => !primary.includes(field)) ?? [];

  function change(field: string, value: ParamValue) {
    if (!op) return;
    setFailure(null);
    setNotice("");
    if (draftOp)
      setDrafts((current) => ({
        ...current,
        [op.id]: { ...params, [field]: value },
      }));
    else if (node)
      setEdits((current) => ({
        ...current,
        [node.id]: {
          baseline,
          source: state.source,
          op: node.op,
          params: { ...params, [field]: value },
        },
      }));
  }

  function apply() {
    if (!op) return;
    setNotice("");
    try {
      validateParameters(op, params);
      if (!state.source || !node || inputIds.length === 0)
        throw new Error(
          "Load a source and select an input before applying an operator.",
        );
      const inputs = inputIds.map((id) => {
        const input = evaluate(state.source, state.nodes, id);
        const inputNode = state.nodes.find((item) => item.id === id);
        if (!input || !inputNode)
          throw new Error(`Input ${id} has no available data.`);
        return { ...input, name: inputNode.label };
      });
      checkBrowserWork(op.id, inputs, params);
      op.apply(inputs[0], params, inputs.slice(1));
      if (draftOp) {
        if (!state.applyOp(op.id, params))
          throw new Error(
            "The operator could not be added to the selected input.",
          );
        onApplied();
      } else {
        state.updateParams(node.id, params);
      }
      setFailure(null);
      setNotice("Parameters applied to the local preview.");
    } catch (caught) {
      setFailure({
        key,
        message: caught instanceof Error ? caught.message : String(caught),
      });
    }
  }

  return (
    <aside className="device-inspector" aria-label="Operator inspector">
      <div className="device-tool-heading">
        <h2>Inspector</h2>
        <span aria-hidden="true">⛭</span>
      </div>
      <div className="device-inspector-content">
        {unknown ? (
          <p className="device-tool-error" role="alert">
            Unknown operator: {unknown}
          </p>
        ) : op ? (
          <>
            <p className="device-inspector-eyebrow">
              {draftOp ? "New operator" : "Selected step"} / {groupOf(op)}
            </p>
            <h3>{labelOf(op)}</h3>
            <p className="device-inspector-hint">
              {SUMMARIES[op.id] ?? labelOf(op)}
            </p>
            {inputNodes.length > 0 && (
              <section aria-label="Operator inputs">
                <h4>Inputs</h4>
                <ol className="device-inputs">
                  {inputNodes.map((input, index) => (
                    <li key={input?.id ?? index}>
                      <span>
                        {index + 1}. {input?.label ?? "Input unavailable"}
                      </span>
                      {!draftOp && node && index > 0 && input && (
                        <button
                          aria-label={`Remove input ${index + 1}`}
                          onClick={() => state.removeInput(node.id, input.id)}
                        >
                          Remove
                        </button>
                      )}
                    </li>
                  ))}
                </ol>
                {op.multiInput && !draftOp && node && (
                  <label className="device-parameter-field">
                    Add input
                    <select
                      value=""
                      disabled={candidates.length === 0}
                      onChange={(event) => {
                        if (!event.target.value) return;
                        if (!state.addInput(node.id, event.target.value))
                          setFailure({
                            key,
                            message:
                              "This input cannot be added. Check the input limit and graph dependencies.",
                          });
                        else setFailure(null);
                      }}
                    >
                      <option value="">
                        {full
                          ? "Input limit reached"
                          : candidates.length === 0
                            ? "No eligible inputs"
                            : "Choose an input"}
                      </option>
                      {candidates.map((input) => (
                        <option key={input.id} value={input.id}>
                          {input.label}
                        </option>
                      ))}
                    </select>
                  </label>
                )}
              </section>
            )}
            <div className="device-parameter-fields">
              {primary.map((field) => (
                <ParameterField
                  key={field.key}
                  op={op}
                  field={field}
                  value={params[field.key]}
                  onChange={(value) => change(field.key, value)}
                />
              ))}
            </div>
            {advanced.length > 0 && (
              <details className="device-advanced">
                <summary>Advanced settings ({advanced.length})</summary>
                <div className="device-parameter-fields">
                  {advanced.map((field) => (
                    <ParameterField
                      key={field.key}
                      op={op}
                      field={field}
                      value={params[field.key]}
                      onChange={(value) => change(field.key, value)}
                    />
                  ))}
                </div>
              </details>
            )}
            {error && (
              <p className="device-tool-error" role="alert">
                {error}
              </p>
            )}
            <button
              className="device-apply"
              disabled={!state.source || !node}
              onClick={apply}
            >
              {draftOp ? "Add operator" : "Apply parameters"}
            </button>
            {notice && (
              <p className="device-tool-success" role="status">
                {notice}
              </p>
            )}
            <details className="device-advanced">
              <summary>Method details</summary>
              <p className="device-inspector-hint">{op.hint}</p>
            </details>
            <details className="device-advanced">
              <summary>Execution availability</summary>
              <dl className="device-capabilities">
                <dt>Browser preview</dt>
                <dd>{capabilityOf(op.id).browser}</dd>
                <dt>Java processor</dt>
                <dd data-testid="device-processor-capability">
                  {capabilityOf(op.id).processor}
                </dd>
                <dt>Wayang</dt>
                <dd>{capabilityOf(op.id).wayang}</dd>
              </dl>
              <p className="device-inspector-hint">
                {capabilityOf(op.id).reason}
              </p>
            </details>
          </>
        ) : (
          <>
            <p className="device-inspector-eyebrow">Source evidence</p>
            <h3>{state.source?.name ?? "Choose a source"}</h3>
            <p className="device-inspector-hint">
              Select an operator to configure a step, or select a graph node to
              inspect its parameters.
            </p>
            <dl className="device-source-evidence">
              <dt>Input kind</dt>
              <dd>{state.sourceInfo?.kind ?? "Not supplied"}</dd>
              <dt>Source</dt>
              <dd>{state.sourceInfo?.detail ?? "Not supplied"}</dd>
              <dt>Coordinate kind</dt>
              <dd>{state.source?.xKind ?? "Not supplied"}</dd>
              <dt>UTC origin</dt>
              <dd>
                {state.source?.t0Ms !== undefined
                  ? new Date(state.source.t0Ms).toISOString()
                  : "Not supplied"}
              </dd>
              <dt>Episodes</dt>
              <dd>{state.source?.episodes?.length ?? 0}</dd>
            </dl>
          </>
        )}
      </div>
    </aside>
  );
}
