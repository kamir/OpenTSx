/**
 * Time series workbench: the web version of the Swing bucket analyser
 * (MacroTrackerFrame, MultiChart, TSOperationControlerPanel).
 *
 * Load a bucket, apply operations to it, watch every result on the scope, and
 * hand the recorded flow over as a Smart Topic definition (opentsx.dag.v1).
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import FlowGraph from '../workbench/FlowGraph';
import InventoryDialog, { InventoryLoad } from '../workbench/InventoryDialog';
import Scope from '../workbench/Scope';
import {
  SOURCE_ID,
  defaultSinkTopic,
  evaluate,
  toDagYaml,
  toDefinition,
  toJavaCode,
  toPfdJson,
  useFlow,
  validate,
} from '../workbench/flow';
import { bucketToCsv, generateSineMix, parseBucketText, parseEpisodes } from '../workbench/loaders';
import axios from 'axios';
import { flowsAPI } from '../services/api';
import { NOT_PORTED, OPS, OP_BY_ID, defaultParams, fmt, stats } from '../workbench/ops';
import { FlowNode, OpDef, OpGroup, ParamDef, Params, ParamValue } from '../workbench/types';
import '../workbench/workbench.css';

function download(name: string, text: string, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

// ---------------------------------------------------------------- track tree

function TrackTree() {
  const nodes = useFlow((s) => s.nodes);
  const track = useFlow((s) => s.track);
  const selectedId = useFlow((s) => s.selectedId);
  const select = useFlow((s) => s.select);

  const branch = (parent: string | null, depth: number): JSX.Element[] =>
    nodes
      .filter((n) => n.input === parent)
      .flatMap((n) => [
        <li key={n.id} role="none">
          <button
            role="treeitem"
            aria-selected={n.id === selectedId}
            className={n.id === selectedId ? 'selected' : ''}
            style={{ paddingLeft: 8 + depth * 14 }}
            title={n.op === 'source' ? `SourceTSB{name=${n.label}}` : `Transform{${OP_BY_ID[n.op]?.label}}`}
            onClick={() => select(n.id)}
          >
            <i className={`wb-dot wb-g-${OP_BY_ID[n.op]?.group ?? 'source'}`} />
            {n.op === 'source' ? n.label : `${OP_BY_ID[n.op]?.label ?? n.op}`}
            {n.sinkTopic && <span className="wb-tag">topic</span>}
          </button>
        </li>,
        ...branch(n.id, depth + 1),
      ]);

  return (
    <nav className="wb-tree" aria-label="Transformation track">
      <div className="wb-tree-root">TSB-Transformation-TRACK</div>
      <div className="wb-tree-track">Track: {track}</div>
      {nodes.length === 0 ? (
        <p className="wb-muted">No bucket loaded.</p>
      ) : (
        <ul role="tree">{branch(null, 1)}</ul>
      )}
    </nav>
  );
}

// ---------------------------------------------------------------- operation panel

function ParamInput({ def, value, onChange }: { def: ParamDef; value: ParamValue; onChange: (v: ParamValue) => void }) {
  if (def.type === 'select') {
    return (
      <label className="wb-param">
        {def.label}
        <select value={String(value)} onChange={(e) => onChange(e.target.value)}>
          {(def.options ?? []).map((o) => (
            <option key={o}>{o}</option>
          ))}
        </select>
      </label>
    );
  }
  if (def.type === 'text') {
    return (
      <label className="wb-param">
        {def.label}
        <input type="text" value={String(value)} onChange={(e) => onChange(e.target.value)} />
      </label>
    );
  }
  return (
    <label className="wb-param">
      {def.label}
      <input
        type="number"
        value={value}
        min={def.min}
        step="any"
        onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
      />
    </label>
  );
}

const TABS: { id: string; title: string; group?: OpGroup; border?: string }[] = [
  { id: 'tsb', title: 'TSB' },
  { id: 'transform', title: 'Transformations', group: 'transform', border: 'Single Time Series Transformations' },
  { id: 'univariate', title: 'Uni-variate Analysis', group: 'univariate', border: 'Univariate Analysis' },
  { id: 'group', title: 'Group Operations', group: 'group', border: 'Group Operations' },
  { id: 'model', title: 'TS-Model', border: 'Models' },
  { id: 'stream', title: 'Filter and conversion', group: 'stream', border: 'Stream operators for Smart Topics' },
];

function OperationPanel({ showLabels, setShowLabels }: { showLabels: boolean; setShowLabels: (v: boolean) => void }) {
  const [tab, setTab] = useState('transform');
  const [params, setParams] = useState<Record<string, Params>>(() =>
    Object.fromEntries(OPS.map((o) => [o.id, defaultParams(o)]))
  );
  const applyOp = useFlow((s) => s.applyOp);
  const nodes = useFlow((s) => s.nodes);
  const source = useFlow((s) => s.source);
  const selectedId = useFlow((s) => s.selectedId);
  const selected = nodes.find((n) => n.id === selectedId);
  const current = TABS.find((t) => t.id === tab)!;
  const ready = !!selected;

  const opButton = (op: OpDef) => (
    <span key={op.id} className="wb-op">
      <button disabled={!ready} title={op.hint} onClick={() => applyOp(op.id, params[op.id])}>
        {op.label}
      </button>
      {op.params.map((d) => (
        <ParamInput
          key={d.key}
          def={d}
          value={params[op.id][d.key]}
          onChange={(v) => setParams((p) => ({ ...p, [op.id]: { ...p[op.id], [d.key]: v } }))}
        />
      ))}
    </span>
  );

  const persist = () => {
    const b = selected && evaluate(source, nodes, selected.id);
    if (b) download(`${b.name}.csv`, bucketToCsv(b), 'text/csv');
  };

  const savePng = () => {
    const canvas = document.querySelector<HTMLCanvasElement>('.wb-screen canvas');
    if (!canvas || !selected) return;
    const a = document.createElement('a');
    a.href = canvas.toDataURL('image/png');
    a.download = `${selected.label}.png`;
    a.click();
  };

  return (
    <section className="wb-ops" aria-label="Operations">
      <div className="wb-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={t.id === tab} onClick={() => setTab(t.id)}>
            {t.title}
          </button>
        ))}
      </div>
      <div className={`wb-tabpanel wb-g-${tab}`} role="tabpanel">
        {tab === 'tsb' && (
          <div className="wb-oprow">
            <input className="wb-chartlabel" readOnly aria-label="Bucket name" value={selected?.label ?? ''} placeholder="ChartLabel" />
            <button disabled={!ready} onClick={persist} title="Downloads the selected bucket as CSV (series_id, x, y)">
              persist TSB
            </button>
            <button disabled={!ready} onClick={savePng} title="Downloads the scope screen as PNG">
              save chart
            </button>
            <label className="wb-check">
              <input type="checkbox" checked={showLabels} onChange={(e) => setShowLabels(e.target.checked)} />
              show labels
            </label>
          </div>
        )}
        {current.border && (
          <fieldset>
            <legend>{current.border}</legend>
            <div className="wb-oprow">
              {current.group && OPS.filter((o) => o.group === current.group).map(opButton)}
              {NOT_PORTED.filter((n) => n.group === tab).map((n) => (
                <span key={n.label} className="wb-op">
                  <button disabled title={n.reason}>{n.label}</button>
                </span>
              ))}
            </div>
            {tab === 'model' && <p className="wb-muted">These buttons have no action in the Swing workbench either. Models belong to the Smart Topic training (Python, MLflow).</p>}
          </fieldset>
        )}
        {!ready && <p className="wb-muted">Load a bucket first, then every operation applies to the bucket selected in the track.</p>}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------- inspector

function CodeBlock({ text, file, mime }: { text: string; file: string; mime?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="wb-code">
      <div className="wb-code-bar">
        <button
          onClick={async () => {
            await navigator.clipboard.writeText(text);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
        <button onClick={() => download(file, text, mime)}>Download {file}</button>
      </div>
      <pre tabIndex={0}>{text}</pre>
    </div>
  );
}

function Properties({ node }: { node: FlowNode }) {
  const nodes = useFlow((s) => s.nodes);
  const source = useFlow((s) => s.source);
  const sourceInfo = useFlow((s) => s.sourceInfo);
  const updateParams = useFlow((s) => s.updateParams);
  const removeNode = useFlow((s) => s.removeNode);
  const op = OP_BY_ID[node.op];
  const parent = nodes.find((n) => n.id === node.input);
  const bucket = useMemo(() => evaluate(source, nodes, node.id), [source, nodes, node.id]);

  return (
    <div className="wb-props">
      <dl className="wb-readout wide">
        <dt>Bucket</dt><dd>{node.label}</dd>
        {op ? (
          <>
            <dt>Transform</dt><dd>{node.label} = {op.label}( {parent?.label} )</dd>
            <dt>Operator</dt><dd>{op.dagOp ?? op.id}{op.inCatalog ? ' (stream operator catalog)' : ' (workbench only)'}</dd>
          </>
        ) : (
          <>
            <dt>Source</dt><dd>{sourceInfo?.kind}: {sourceInfo?.detail}</dd>
          </>
        )}
        <dt>Axes</dt><dd>{bucket?.xLabel} / {bucket?.yLabel}</dd>
      </dl>
      {op && <p className="wb-muted">{op.hint}</p>}
      {op && op.params.length > 0 && (
        <fieldset>
          <legend>Parameters</legend>
          <div className="wb-oprow">
            {op.params.map((d) => (
              <ParamInput key={d.key} def={d} value={node.params[d.key]} onChange={(v) => updateParams(node.id, { [d.key]: v })} />
            ))}
          </div>
        </fieldset>
      )}
      {bucket?.note && <pre className="wb-note">{bucket.note}</pre>}
      {bucket && (
        <table className="wb-table">
          <thead>
            <tr><th>Series</th><th>n</th><th>mean</th><th>std</th><th>min</th><th>max</th></tr>
          </thead>
          <tbody>
            {bucket.series.map((s, i) => {
              const st = stats(s.y);
              return (
                <tr key={s.id + i}>
                  <td title={s.label}>{s.label}</td>
                  <td>{st.n}</td><td>{fmt(st.mean)}</td><td>{fmt(st.std)}</td><td>{fmt(st.min)}</td><td>{fmt(st.max)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {bucket && bucket.series.length === 0 && <p className="wb-muted">This bucket has no series. Check the parameters.</p>}
      {node.id !== SOURCE_ID && (
        <button className="wb-danger" onClick={() => removeNode(node.id)}>
          Remove this node and everything after it
        </button>
      )}
    </div>
  );
}

function SmartTopic({ node }: { node: FlowNode }) {
  const state = useFlow();
  const sinks = state.nodes.filter((n) => n.sinkTopic);
  const findings = validate(state.nodes);
  const blocked = findings.some((f) => f.level === 'error');
  const yaml = toDagYaml(state);

  return (
    <div className="wb-props">
      <fieldset>
        <legend>Source topic</legend>
        <div className="wb-oprow">
          <label className="wb-param">domain<input type="text" value={state.domain} onChange={(e) => state.setDomain(e.target.value)} /></label>
          <label className="wb-param grow">reads from<input type="text" value={state.sourceTopic} onChange={(e) => state.setSourceTopic(e.target.value)} /></label>
        </div>
      </fieldset>
      <fieldset>
        <legend>Selected bucket: {node.label}</legend>
        <label className="wb-check">
          <input
            type="checkbox"
            checked={!!node.sinkTopic}
            onChange={(e) => state.setSink(node.id, e.target.checked ? defaultSinkTopic(state.domain, node.label) : null)}
          />
          Publish as Smart Topic
        </label>
        {node.sinkTopic !== undefined && (
          <label className="wb-param grow">
            topic
            <input type="text" value={node.sinkTopic} onChange={(e) => state.setSink(node.id, e.target.value)} />
          </label>
        )}
      </fieldset>
      <fieldset>
        <legend>Published topics</legend>
        {sinks.length === 0 ? (
          <p className="wb-muted">None yet.</p>
        ) : (
          <ul className="wb-sinks">
            {sinks.map((s) => (
              <li key={s.id}>
                <button onClick={() => state.select(s.id)}>{s.label}</button>
                <code>{s.sinkTopic}</code>
              </li>
            ))}
          </ul>
        )}
      </fieldset>
      <ul className="wb-findings">
        {findings.map((f, i) => (
          <li key={i} className={f.level}>
            <b>{f.level === 'error' ? 'Missing' : f.level === 'warning' ? 'Check' : 'Note'}</b> {f.text}
          </li>
        ))}
        {findings.length === 0 && <li className="ok"><b>Ready</b> Every published branch uses catalog operators only.</li>}
      </ul>
      <button className="wb-primary" disabled={blocked} onClick={() => download(`${state.domain}-${state.track}.dag.yaml`, yaml, 'text/yaml')}>
        Download Smart Topic definition
      </button>
      <p className="wb-muted">
        The definition is an opentsx.dag.v1 file for the processor described in docs/datamodel-2026/11. That processor is
        a proposal; nothing in this repository runs the file yet, so this step hands it over and does not deploy.
      </p>
    </div>
  );
}

function Inspector() {
  const [tab, setTab] = useState('props');
  const state = useFlow();
  const node = state.nodes.find((n) => n.id === state.selectedId);
  const tabs = [
    ['props', 'Properties'],
    ['code', 'Code'],
    ['pfd', 'Flow (JSON)'],
    ['dag', 'DAG (YAML)'],
    ['topic', 'Smart Topic'],
  ];
  return (
    <aside className="wb-inspector" aria-label="Inspector">
      <div className="wb-tabs" role="tablist">
        {tabs.map(([id, title]) => (
          <button key={id} role="tab" aria-selected={id === tab} onClick={() => setTab(id)}>
            {title}
          </button>
        ))}
      </div>
      <div className="wb-tabpanel" role="tabpanel">
        {!node ? (
          <p className="wb-muted">Select a bucket in the track or in the graph.</p>
        ) : tab === 'props' ? (
          <Properties node={node} />
        ) : tab === 'code' ? (
          <CodeBlock text={toJavaCode(state)} file={`${state.track}.java.txt`} />
        ) : tab === 'pfd' ? (
          <CodeBlock text={toPfdJson(state, node.id)} file={`${node.label}.pfd.json`} mime="application/json" />
        ) : tab === 'dag' ? (
          <CodeBlock text={toDagYaml(state)} file={`${state.domain}-${state.track}.dag.yaml`} mime="text/yaml" />
        ) : (
          <SmartTopic node={node} />
        )}
      </div>
    </aside>
  );
}

// ---------------------------------------------------------------- page

export default function Workbench() {
  const state = useFlow();
  const { source, nodes, selectedId, loadBucket } = state;
  const [showLabels, setShowLabels] = useState(true);
  const [status, setStatus] = useState('Ready');
  const [error, setError] = useState<string | null>(null);
  const [remote, setRemote] = useState({ open: false, base: '/retrieval', query: 'limit=50' });
  const [inventoryOpen, setInventoryOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const signedIn = !!localStorage.getItem('access_token');
  const flowParam = searchParams.get('flow');

  // /workbench?flow=<id> opens a flow stored in the account.
  useEffect(() => {
    const id = Number(flowParam);
    if (!flowParam || !Number.isInteger(id) || useFlow.getState().savedId === id) return;
    let cancelled = false;
    flowsAPI
      .getDefinition(id)
      .then((flow) => {
        if (cancelled) return;
        if (useFlow.getState().restore(flow.definition, flow.id)) {
          setError(null);
          setStatus(`Flow "${flow.name}" opened. Load a bucket to run it.`);
        } else {
          setError(`Flow ${id} was not saved from the workbench and cannot be opened here.`);
        }
      })
      .catch((e) => !cancelled && setError(`Flow ${id} could not be opened: ${e instanceof Error ? e.message : e}`));
    return () => {
      cancelled = true;
    };
  }, [flowParam]);

  const saveFlow = async () => {
    const s = useFlow.getState();
    const published = s.nodes.filter((n) => n.sinkTopic).length;
    try {
      const flow = await flowsAPI.save(s.savedId, {
        name: `${s.track}: ${s.nodes[0]?.label ?? 'flow'}`,
        description: `Workbench flow, ${s.nodes.length - 1} operators, ${published} published topics`,
        definition: toDefinition(s),
      });
      s.setSavedId(flow.id);
      setSearchParams({ flow: String(flow.id) }, { replace: true });
      setError(null);
      setStatus(`Saved to your account as flow ${flow.id}`);
    } catch (e) {
      const expired = axios.isAxiosError(e) && e.response?.status === 401;
      setError(
        expired
          ? 'Your session has expired. Sign in again (the link opens a new tab), then save: the flow stays here.'
          : `The flow could not be saved: ${e instanceof Error ? e.message : e}`
      );
    }
  };

  const selected = nodes.find((n) => n.id === selectedId);
  const result = useMemo(() => {
    if (!selected) return { bucket: null, error: null as string | null };
    try {
      return { bucket: evaluate(source, nodes, selected.id), error: null };
    } catch (e) {
      return { bucket: null, error: e instanceof Error ? e.message : String(e) };
    }
  }, [source, nodes, selected]);
  const bucket = result.bucket;

  useEffect(() => {
    if (bucket) {
      const points = bucket.series.reduce((n, s) => n + s.y.length, 0);
      setStatus(`${bucket.name}: ${bucket.series.length} series, ${points} points`);
    }
  }, [bucket]);

  const loadSample = () => {
    setError(null);
    loadBucket(generateSineMix(), { kind: 'generator', detail: 'sine mix of MacroRecorder, track EXP2 (synthetic)' });
  };

  const loadFile = async (file: File | undefined) => {
    if (!file) return;
    try {
      setError(null);
      loadBucket(parseBucketText(await file.text(), file.name), { kind: 'file', detail: file.name });
    } catch (e) {
      setError(`${file.name} could not be read: ${e instanceof Error ? e.message : e}`);
    }
  };

  const loadRemote = async () => {
    const url = `${remote.base.replace(/\/$/, '')}/v1/episodes${remote.query ? `?${remote.query}` : ''}`;
    try {
      setError(null);
      setStatus(`Loading ${url}`);
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      loadBucket(parseEpisodes(await res.json(), 'Episodes'), { kind: 'retrieval', detail: url });
      setRemote((r) => ({ ...r, open: false }));
    } catch (e) {
      setStatus('Ready');
      setError(`Episodes could not be loaded from ${url}: ${e instanceof Error ? e.message : e}. Is the retrieval server running?`);
    }
  };

  const loadInventory = (l: InventoryLoad) => {
    const s = useFlow.getState();
    // A flow opened from the account keeps its own domain and source topic.
    if (!(s.source === null && s.nodes.length > 1)) {
      if (l.domain) s.setDomain(l.domain);
      if (l.topic) s.setSourceTopic(l.topic);
    }
    setError(null);
    loadBucket(l.bucket, { kind: 'inventory', detail: l.detail });
    setInventoryOpen(false);
  };

  const useTrigger = (level: number, rising: boolean) => {
    state.applyOp('segment.threshold', { enter: Number(level.toPrecision(6)), direction: rising ? 'ABOVE' : 'BELOW' });
  };

  return (
    <div className="wb">
      <header className="wb-toolbar">
        <h1>OpenTSx Workbench</h1>
        <label className="wb-param">
          Track:
          <input type="text" value={state.track} onChange={(e) => state.setTrack(e.target.value)} />
        </label>
        <span className="wb-sep" />
        <button onClick={loadSample}>Load sample bucket</button>
        <button onClick={() => fileRef.current?.click()}>Open bucket file</button>
        <input
          ref={fileRef}
          type="file"
          hidden
          accept=".csv,.tsv,.txt,.json"
          onChange={(e) => {
            loadFile(e.target.files?.[0]);
            e.target.value = '';
          }}
        />
        <button onClick={() => setInventoryOpen(true)} title="Browse the series of the Smart Topics by scenario, turbine, channel and event">
          TS Inventory
        </button>
        <button aria-expanded={remote.open} onClick={() => setRemote((r) => ({ ...r, open: !r.open }))}>
          Load episodes
        </button>
        <span className="wb-grow" />
        {signedIn ? (
          <button disabled={nodes.length === 0} onClick={saveFlow} title="Stores the flow in your account; it appears on the dashboard">
            {state.savedId === null ? 'Save flow' : `Save flow ${state.savedId}`}
          </button>
        ) : (
          <Link to="/login" target="_blank" rel="noopener">Sign in to save flows</Link>
        )}
        <Link to="/dashboard">Dashboard</Link>
      </header>

      {remote.open && (
        <div className="wb-remote">
          <label className="wb-param">retrieval server<input type="text" value={remote.base} onChange={(e) => setRemote({ ...remote, base: e.target.value })} /></label>
          <label className="wb-param grow">query (metric, series, from, to, limit, resample)<input type="text" value={remote.query} onChange={(e) => setRemote({ ...remote, query: e.target.value })} /></label>
          <button onClick={loadRemote}>Load</button>
        </div>
      )}
      {inventoryOpen && (
        <InventoryDialog
          base={remote.base}
          onBase={(base) => setRemote((r) => ({ ...r, base }))}
          onLoad={loadInventory}
          onClose={() => setInventoryOpen(false)}
        />
      )}
      {(error || result.error) && <div className="wb-error" role="alert">{error ?? `The operation failed: ${result.error}`}</div>}

      <main className="wb-main">
        <TrackTree />

        <div className="wb-center">
          <section
            className="wb-scopewrap"
            aria-label="Scope"
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              loadFile(e.dataTransfer.files[0]);
            }}
          >
            {bucket && bucket.series.length > 0 ? (
              <Scope bucket={bucket} showLabels={showLabels} onUseTrigger={useTrigger} />
            ) : (
              <div className="wb-empty">
                {source ? (
                  <p>This bucket has no series to draw. Change the parameters in the Properties tab or select another bucket.</p>
                ) : nodes.length > 0 ? (
                  <>
                    <p>This flow has no data yet.</p>
                    <p>Load a bucket: it becomes the source of the flow, and every node is computed on it.</p>
                    <button className="wb-primary" onClick={loadSample}>Load sample bucket</button>
                  </>
                ) : (
                  <>
                    <p>No bucket loaded.</p>
                    <p>
                      Pick series from the TS inventory, load the sample bucket, open a CSV, TSV or episode JSON file,
                      or drop a file here. Then apply operations and watch each result on the scope.
                    </p>
                    <button className="wb-primary" onClick={() => setInventoryOpen(true)}>Open TS inventory</button>{' '}
                    <button onClick={loadSample}>Load sample bucket</button>
                  </>
                )}
              </div>
            )}
          </section>
          <OperationPanel showLabels={showLabels} setShowLabels={setShowLabels} />
          <section className="wb-graph" aria-label="Processing flow graph">
            {nodes.length > 0 ? <FlowGraph /> : <p className="wb-muted pad">The processing flow appears here once a bucket is loaded.</p>}
          </section>
        </div>

        <Inspector />
      </main>

      <footer className="wb-status">
        <span>{status}</span>
        <span>Wheel: time base. Shift + wheel: vertical scale. Drag: move. Double click: auto set.</span>
      </footer>
    </div>
  );
}
