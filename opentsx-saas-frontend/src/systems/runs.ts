/**
 * Runs of the twin of a system on request (retrieval server, `/v1/systems/<id>/scenario` and `/runs`): what can be
 * asked, a run with its identity, and two runs next to each other. The functions here are the parts of the place
 * "Simulate" that can be tested without a server.
 */

export interface ScenarioControl {
  atom: string;
  type: string;
  control: string;
  default: number;
  unit?: string;
  label?: string;
}

export interface RunDecision {
  at: string;
  clock: string;
  minute: number;
  atom: string;
  control: string;
  value: number | 'release';
  author?: string;
  reason?: string;
}

export interface ScenarioDoc {
  system: string;
  title: string;
  description: string;
  decision: string;
  start: string;
  stepSeconds: number;
  steps: number;
  reference: string;
  controls: ScenarioControl[];
  policies: { atom: string; policy: string; label: string }[];
  referenceDecisions: RunDecision[];
  compareKeys: string[];
}

export interface RunEvent {
  t: string;
  clock: string;
  step: number;
  atom: string;
  type: string;
  attrs: Record<string, string>;
}

export interface RunDoc {
  run: string;
  system: string;
  kind: 'reference' | 'what-if' | 'forecast';
  mode: string;
  label: string;
  hash: string;
  createdAt: string;
  baseline?: string;
  firstStep: number;
  steps: number;
  decisions: RunDecision[];
  policies: Record<string, string>;
  inputs: { port: string; source: string; origin?: string }[];
  status: { usability: 'usable' | 'restricted' | 'blocked'; reasons: string[] };
  events: RunEvent[];
  kpis: Record<string, number>;
}

export interface RunListEntry {
  run: string;
  kind: RunDoc['kind'];
  label: string;
  createdAt: string;
  kpis: Record<string, number>;
}

export interface KpiRow {
  key: string;
  label: string;
  unit: string;
  decimals: number;
  better: number;
  base: number;
  run: number;
  delta: number;
  verdict: 'better' | 'worse' | 'same';
}

export interface CompareDoc {
  base: { run: string; label: string };
  run: { run: string; label: string; kind: string };
  kpis: KpiRow[];
  endStocks: { key: string; base: number; run: number }[];
  start: string;
  stepSeconds: number;
  series: Record<string, { label: string; base: (number | null)[]; run: (number | null)[]; limits: { label: string; value: number }[] }>;
}

/** One change a person asks for, as the form holds it. */
export interface Ask {
  /** HH:MM of the run's day */
  at: string;
  /** "atom.control" */
  target: string;
  /** a number, or empty for "release" */
  value: string;
}

/** HH:MM of a minute after the start of the run. */
export function clockOf(minute: number): string {
  const m = Math.max(0, Math.round(minute));
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/** Minutes after the start for HH:MM, or NaN when the text is no time. */
export function minuteOf(clock: string): number {
  const m = /^(\d{1,2}):(\d{2})$/.exec(clock.trim());
  if (!m || Number(m[2]) > 59) return NaN;
  return Number(m[1]) * 60 + Number(m[2]);
}

/**
 * The body of `POST /runs` for what the form holds, or the reason it cannot be asked. A forecast (`from`) cannot
 * change what was decided before it begins.
 */
export function runRequest(
  scenario: Pick<ScenarioDoc, 'steps' | 'controls'>,
  asks: Ask[],
  policy: string,
  from: string,
  onReference: boolean,
): { body?: Record<string, unknown>; error?: string } {
  const interventions: Record<string, unknown>[] = [];
  const start = from.trim() === '' ? null : minuteOf(from);
  if (start !== null && (Number.isNaN(start) || start < 1 || start >= scenario.steps)) {
    return { error: `"From" is a time inside the run, 00:01 to ${clockOf(scenario.steps - 1)}.` };
  }
  for (const a of asks) {
    if (a.target === '' && a.at.trim() === '' && a.value.trim() === '') continue;
    const minute = minuteOf(a.at);
    if (Number.isNaN(minute) || minute >= scenario.steps) return { error: `"${a.at}" is no time of the run (00:00 to ${clockOf(scenario.steps - 1)}).` };
    const [atom, control] = a.target.split('.');
    if (!scenario.controls.some((c) => c.atom === atom && c.control === control)) return { error: 'Choose what to set.' };
    const release = a.value.trim() === '';
    const value = Number(a.value);
    if (!release && !Number.isFinite(value)) return { error: `"${a.value}" is no number. Leave the value empty to release the control.` };
    if (start !== null && minute < start) return { error: `A forecast from ${clockOf(start)} cannot change what was decided at ${a.at}.` };
    interventions.push({ at: clockOf(minute), atom, control, value: release ? 'release' : value });
  }
  const body: Record<string, unknown> = { base: onReference ? 'reference' : 'empty', interventions };
  if (policy !== '' && policy !== 'follow') body.policies = { ely: policy };
  if (start !== null) body.from = start;
  return { body };
}

/** A KPI value as a person reads it. */
export function kpiText(value: number, decimals: number, unit: string): string {
  if (!Number.isFinite(value)) return 'n/a';
  return `${value.toLocaleString('en', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}${unit ? ` ${unit}` : ''}`;
}

/** The difference of a KPI with its sign; nothing when the two runs agree. */
export function deltaText(row: Pick<KpiRow, 'delta' | 'decimals' | 'unit' | 'verdict'>): string {
  if (row.verdict === 'same' && Math.abs(row.delta) < 1e-9) return '';
  const text = kpiText(Math.abs(row.delta), row.decimals, row.unit);
  return `${row.delta > 0 ? '+' : '-'}${text}`;
}

/** What an event of a run means, in words; the type itself when it has no sentence. */
export function eventText(e: Pick<RunEvent, 'type' | 'attrs' | 'atom'>): string {
  const a = e.attrs;
  switch (e.type) {
    case 'intervention':
      return `${e.atom}.${a.control} set to ${a.value === 'release' ? 'its default again' : a.value}${a.reason ? ` (${a.reason})` : ''}`;
    case 'time_to_limit_warning':
      return `less than an hour until the pressure limit (${Number(a.ttl_min).toFixed(0)} min at ${Number(a.p_bar).toFixed(0)} bar)`;
    case 'pressure_limit':
      return `pressure limit of ${Number(a.p_max_bar).toFixed(0)} bar reached`;
    case 'protective_shutdown':
      return `the electrolyser shuts down to protect itself (${Number(a.p_bar).toFixed(0)} bar)`;
    case 'protection_released':
      return 'the electrolyser may run again';
    case 'schedule_deviation':
      return `feed-in leaves the schedule by ${Number(a.deviation_kw).toFixed(0)} kW`;
    case 'schedule_back':
      return 'feed-in is back on the schedule';
    case 'restriction_start':
      return `operating rule limits the park to ${Number(a.limit_kw).toFixed(0)} kW (${a.reason})`;
    case 'restriction_end':
      return 'operating rule ends';
    case 'delivery_interrupted':
      return 'delivery interrupted: pressure too low';
    case 'buffer_low':
      return 'the buffer is at its lowest pressure';
    default:
      return e.type.replace(/_/g, ' ');
  }
}

/**
 * The points of a line for an SVG polyline: one point per value, gaps split the line. Returns the pieces, and the
 * range the values and the limits span.
 */
export function chartLines(series: (number | null)[][], limits: number[], width: number, height: number): { paths: string[][]; min: number; max: number; y: (v: number) => number } {
  let min = Infinity;
  let max = -Infinity;
  for (const s of series) for (const v of s) if (v !== null) { min = Math.min(min, v); max = Math.max(max, v); }
  for (const l of limits) { min = Math.min(min, l); max = Math.max(max, l); }
  if (!Number.isFinite(min)) { min = 0; max = 1; }
  if (max === min) max = min + 1;
  const pad = (max - min) * 0.06;
  min -= pad;
  max += pad;
  const y = (v: number) => height - ((v - min) / (max - min)) * height;
  const paths = series.map((s) => {
    const n = Math.max(1, s.length - 1);
    const pieces: string[] = [];
    let cur: string[] = [];
    s.forEach((v, i) => {
      if (v === null) {
        if (cur.length > 1) pieces.push(cur.join(' '));
        cur = [];
      } else {
        cur.push(`${((i / n) * width).toFixed(1)},${y(v).toFixed(1)}`);
      }
    });
    if (cur.length > 1) pieces.push(cur.join(' '));
    return pieces;
  });
  return { paths, min, max, y };
}
