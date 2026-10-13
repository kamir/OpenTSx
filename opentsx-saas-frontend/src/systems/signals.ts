/**
 * The signals of a system as the retrieval server reports them (GET /v1/systems/<id>/signals,
 * opentsx.signal-status.v1), and the words the page "Signals" uses for them. A signal is a quantity the system
 * expects; its state says how far its data has come and what to do next.
 * Concept: docs/datamodel-2026/14-ENGINEERING-FLOW.md.
 */

export interface Expect {
  unit?: string;
  min?: number;
  max?: number;
  /** ISO-8601 duration. */
  interval?: string;
  kind?: string;
  origin?: string;
}

export interface GradeSource {
  kind: 'topic' | 'generator' | 'flow' | 'manual';
  origin?: string;
  topic?: string;
  metric?: string;
  tags?: Record<string, string>;
  processor?: string;
  seed?: number;
  flow?: string;
  output?: string;
  value?: number;
  table?: string;
}

export interface GradeData {
  series: string[];
  samples: number;
  missing: number;
  from?: string;
  to?: string;
  min?: number;
  max?: number;
  intervalMicros?: number;
}

export interface Grade {
  source: GradeSource;
  names?: string[];
  data?: GradeData;
}

export type GradeName = 'raw' | 'clean' | 'features';
export type SignalState = 'open' | 'bound-no-data' | 'raw-only' | 'clean-not-running' | 'clean' | 'constant';
export type NextStep = 'bind' | 'simulate' | 'generate' | 'check-source' | 'prepare' | 'start';

export interface Signal {
  id: string;
  atom: string;
  /** Asset path below the atom for a sensor channel, empty for a port. */
  group: string;
  name: string;
  title: string;
  port: boolean;
  channel?: string;
  expect: Expect;
  grades: Partial<Record<GradeName, Grade>>;
  /** topic, simulated, derived, manual or open. */
  via: string;
  origin?: string;
  state: SignalState;
  outOfRange?: { min: number; max: number };
  next: NextStep[];
  usedBy: string[];
  note?: string;
}

export interface SystemStatus {
  schema: string;
  system: string;
  title: string;
  version: number;
  domain: string;
  example: boolean;
  hash: string;
  problems: string[];
  notes: string[];
  counts: Record<string, number>;
  states: Record<string, number>;
  signals: Signal[];
}

export interface SystemEntry {
  system: string;
  title: string;
  counts: Record<string, number>;
  states: Record<string, number>;
  problems: number;
}

export const STATUS_SCHEMA = 'opentsx.signal-status.v1';

export function parseStatus(json: unknown): SystemStatus {
  const doc = json as Partial<SystemStatus> | null;
  if (!doc || doc.schema !== STATUS_SCHEMA || !Array.isArray(doc.signals)) {
    throw new Error(`the answer is no ${STATUS_SCHEMA} document`);
  }
  return doc as SystemStatus;
}

function num(v: number): string {
  return Number.isInteger(v) ? v.toLocaleString('en') : String(Number(v.toPrecision(6)));
}

/** "PT1M" as "1 min", "PT15M" as "15 min", "PT1S" as "1 s"; anything else as it is. */
export function intervalText(iso: string | undefined): string {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(iso ?? '');
  if (!m || (!m[1] && !m[2] && !m[3])) return iso ?? '';
  return [m[1] && `${m[1]} h`, m[2] && `${m[2]} min`, m[3] && `${m[3]} s`].filter(Boolean).join(' ');
}

/** The expectation in one line: "kW 0..12,000, 1 min". */
export function expectText(e: Expect): string {
  const range = e.min !== undefined && e.max !== undefined ? `${num(e.min)}..${num(e.max)}` : e.min !== undefined ? `from ${num(e.min)}` : e.max !== undefined ? `up to ${num(e.max)}` : '';
  const head = [e.unit, range].filter(Boolean).join(' ');
  return [head, intervalText(e.interval)].filter(Boolean).join(', ') || 'not stated';
}

/**
 * The origin as the list shows it. Simulated data is written in capitals so it is never taken for a measurement;
 * synthetic data from a topic (the output of a simulator) is named as such.
 */
export function originText(s: Signal): string {
  if (s.via === 'simulated') return 'SIMULATED';
  return s.origin ? s.origin.toLowerCase() : 'n/a';
}

/** True when the data of the signal is not a measurement or a report: it was generated or modelled. */
export function isSynthetic(s: Signal): boolean {
  return s.origin === 'SYNTHETIC' || s.origin === 'ESTIMATED';
}

export function stateText(s: Signal): string {
  const raw = s.grades.raw?.source;
  switch (s.state) {
    case 'open':
      return 'open';
    case 'bound-no-data':
      return raw?.kind === 'generator' ? `not generated yet (${raw.processor}, seed ${raw.seed ?? 'none'})` : 'bound, no data';
    case 'raw-only':
      return raw?.kind === 'generator' ? `raw only (${raw.processor}, seed ${raw.seed ?? 'none'})` : 'raw only';
    case 'clean-not-running':
      return 'clean declared, not running';
    case 'clean':
      return 'clean';
    case 'constant':
      return raw?.value !== undefined ? `constant ${num(raw.value)}` : `table ${raw?.table ?? ''}`;
    default:
      return s.state;
  }
}

/** What each next step means, and from which delivery stage it works. `live` steps work today. */
export const NEXT: Record<NextStep, { label: string; hint: string; live: boolean }> = {
  bind: { label: 'Bind', hint: 'Choose the series that carry this signal. Planned (stage L4); today the binding is written into the system document.', live: false },
  simulate: { label: 'Simulate', hint: 'Generate the signal from its expectation. Planned (stage L4).', live: false },
  generate: { label: 'Generate', hint: 'Run the generator of this signal. Planned (stage L4): no runtime writes generated series yet.', live: false },
  'check-source': { label: 'Check source', hint: 'A source is declared, but the server finds no series for it.', live: false },
  prepare: { label: 'Prepare', hint: 'Opens the raw series on the scope of the workbench, with the expected range drawn in.', live: true },
  start: { label: 'Start', hint: 'Runs the flow that produces the clean data. Planned (stage L3): the processor cannot run it yet.', live: false },
};

/** The grade to look at on the scope: clean data when there is some, else raw data; null without data. */
export function scopeGrade(s: Signal): GradeName | null {
  if ((s.grades.clean?.data?.series.length ?? 0) > 0) return 'clean';
  if ((s.grades.raw?.data?.series.length ?? 0) > 0) return 'raw';
  return null;
}

export function scopeLink(system: string, s: Signal, grade: GradeName): string {
  return `/workbench?${new URLSearchParams({ system, signal: s.id, grade })}`;
}

/** "8 signals: 2 from topics, 1 derived, 1 simulated, 2 manual, 2 open". */
export function countsText(counts: Record<string, number>): string {
  const parts = [
    counts.topic && `${counts.topic} from topics`,
    counts.derived && `${counts.derived} derived`,
    counts.simulated && `${counts.simulated} simulated`,
    counts.manual && `${counts.manual} manual`,
    counts.open && `${counts.open} open`,
  ].filter(Boolean);
  return `${counts.signals ?? 0} signals${parts.length > 0 ? `: ${parts.join(', ')}` : ''}`;
}

export interface SignalFilter {
  text: string;
  state: SignalState | null;
  via: string | null;
  /** Only signals that need something done. */
  todo: boolean;
}

export const NO_SIGNAL_FILTER: SignalFilter = { text: '', state: null, via: null, todo: false };

export function filterSignals(signals: Signal[], f: SignalFilter): Signal[] {
  const words = f.text.toLowerCase().split(/\s+/).filter(Boolean);
  return signals.filter((s) => {
    if (f.state && s.state !== f.state) return false;
    if (f.via && s.via !== f.via) return false;
    if (f.todo && s.next.length === 0 && !s.outOfRange) return false;
    if (words.length === 0) return true;
    const hay = `${s.id} ${s.title} ${s.expect.unit ?? ''} ${s.via} ${s.origin ?? ''} ${s.state} ${s.usedBy.join(' ')}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  });
}

export interface SignalGroup {
  /** Atom, and the asset below it for sensor channels: "park" or "park / T02". */
  title: string;
  signals: Signal[];
}

/** Signals by atom and asset, in the order of the document: the ports of an atom first, then each asset. */
export function groupSignals(signals: Signal[]): SignalGroup[] {
  const groups = new Map<string, Signal[]>();
  for (const s of signals) {
    const key = s.group ? `${s.atom} / ${s.group}` : s.atom;
    groups.set(key, [...(groups.get(key) ?? []), s]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => a.split(' / ')[0].localeCompare(b.split(' / ')[0]) || a.length - b.length || a.localeCompare(b))
    .map(([title, list]) => ({ title, signals: list }));
}

/** One line about the source of a grade, in the words of a person. */
export function sourceText(g: Grade | undefined): string {
  if (!g) return 'none';
  const s = g.source;
  switch (s.kind) {
    case 'topic':
      return `topic ${s.topic}: ${s.metric ?? ''} ${Object.entries(s.tags ?? {}).map(([k, v]) => `${k}=${v}`).join(' ')}`.trim();
    case 'generator':
      return `generator ${s.processor}, seed ${s.seed ?? 'none'}`;
    case 'flow':
      return `flow ${s.flow}${s.output ? `, output ${s.output}` : ''}${g.names ? `: ${g.names.join(', ')}` : ''}`;
    case 'manual':
      return s.value !== undefined ? `set by hand: ${num(s.value)}` : `table ${s.table}`;
    default:
      return '';
  }
}

/** What the server found for a grade: "1 series, 21,600 samples, 0.5 % missing, 0..2,950". */
export function dataText(d: GradeData | undefined): string {
  if (!d) return 'no data found';
  const share = d.samples > 0 ? (100 * d.missing) / d.samples : 0;
  const range = d.min !== undefined && d.max !== undefined ? `, ${num(d.min)}..${num(d.max)}` : '';
  return `${d.series.length} series, ${d.samples.toLocaleString('en')} samples, ${Number(share.toFixed(1))} % missing${range}`;
}

// ---------------------------------------------------------------- plan of a system (GET /v1/systems/<id>/plan)

export interface PlanPort {
  name: string;
  unit?: string;
  kind: string;
  /** Output of another atom this input is wired to. */
  from?: string;
  /** Signal declared for this input, with its state. */
  signal?: string;
  state?: SignalState;
  origin?: string;
  via?: string;
}

export interface PlanAtom {
  id: string;
  type: string;
  inputs: PlanPort[];
  outputs: PlanPort[];
  /** Sensor channels declared below the atom. */
  channels: number;
}

export interface PlanDoc {
  schema: string;
  system: string;
  title: string;
  atoms: PlanAtom[];
  counts: { atoms: number; inputsFromOutside: number; inputsWithoutSignal: number };
}

/** What feeds an input, in one word and a hint: wired inside the plan, a signal, or nothing. */
export function feedOf(p: PlanPort): { kind: 'wired' | 'signal' | 'missing'; text: string } {
  if (p.from) return { kind: 'wired', text: p.from };
  if (p.signal) return { kind: 'signal', text: (p.state ?? '').replace(/-/g, ' ') };
  return { kind: 'missing', text: 'no signal declared' };
}

// ---------------------------------------------------------------- guided mode

export type Place = 'system' | 'signals' | 'flows' | 'models';

export interface GuideStep {
  n: number;
  title: string;
  /** How far the system is in this step, in a few words. */
  state: string;
  /** done: nothing left; todo: something to do; later: cannot be done before an earlier step or a later stage. */
  mark: 'done' | 'todo' | 'later';
  place: Place;
}

/**
 * The seven steps of the engineering method (user manual, chapter 16) with the state of this system in each. The
 * guide shows the next sensible step; it does not enforce an order.
 */
export function guideSteps(status: SystemStatus | null, plan: PlanDoc | null, running: number | null): GuideStep[] {
  const signals = status?.signals ?? [];
  const n = signals.length;
  const open = signals.filter((s) => s.state === 'open').length;
  const noData = signals.filter((s) => s.state === 'bound-no-data').length;
  const withRaw = signals.filter((s) => s.grades.raw && s.state !== 'constant').length;
  const prepared = signals.filter((s) => s.grades.clean).length;
  const needClean = signals.filter((s) => s.state !== 'constant' && s.state !== 'open').length;
  const clean = signals.filter((s) => s.state === 'clean').length;
  const problems = status?.problems.length ?? 0;
  const uncovered = plan?.counts.inputsWithoutSignal ?? 0;
  return [
    { n: 1, title: 'System', place: 'system', state: plan ? `${plan.counts.atoms} atoms${problems > 0 ? `, ${problems} problems` : ''}` : 'no plan', mark: plan && problems === 0 ? 'done' : 'todo' },
    { n: 2, title: 'Signals', place: 'signals', state: uncovered > 0 ? `${n} expected, ${uncovered} inputs without a signal` : `${n} expected`, mark: n > 0 && uncovered === 0 ? 'done' : 'todo' },
    { n: 3, title: 'Stock', place: 'signals', state: noData > 0 ? `${noData} without data` : 'all looked up', mark: noData > 0 ? 'todo' : 'done' },
    { n: 4, title: 'Bind', place: 'signals', state: open > 0 ? `${open} open` : 'none open', mark: open > 0 ? 'todo' : 'done' },
    { n: 5, title: 'Prepare', place: 'signals', state: `${prepared} of ${needClean} prepared`, mark: needClean > 0 && prepared >= needClean ? 'done' : withRaw > 0 ? 'todo' : 'later' },
    { n: 6, title: 'Run', place: 'flows', state: running === null ? `${clean} clean` : `${running} running, ${clean} clean`, mark: prepared === 0 ? 'later' : clean >= prepared ? 'done' : 'todo' },
    { n: 7, title: 'Model', place: 'models', state: clean === 0 ? 'needs clean signals' : 'none declared', mark: 'later' },
  ];
}

/** The number each place shows in the rail: what there is, or what is left to do. */
export function railCounts(status: SystemStatus | null, plan: PlanDoc | null, flows: number | null | undefined): Record<Place, string> {
  const open = status?.states.open ?? 0;
  const todo = (status?.signals ?? []).filter((s) => s.next.length > 0).length;
  return {
    system: plan ? `${plan.counts.atoms} atoms` : '',
    signals: status ? (todo > 0 ? `${open} open, ${todo} to do` : `${status.signals.length} signals`) : '',
    // null: not signed in; undefined: signed in, not counted yet
    flows: flows === null ? 'sign in' : flows === undefined ? '' : `${flows} flows`,
    models: 'none yet',
  };
}
