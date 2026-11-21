import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { CompareDoc, KpiRow, RunDoc, ScenarioDoc } from './runs';
import SimulatePlace from './SimulatePlace';

const scenario: ScenarioDoc = {
  system: 'h2-chain',
  title: 'H2 chain',
  description: 'a night of the chain',
  decision: 'When does the valve reopen?',
  start: '2025-10-01T00:00:00Z',
  stepSeconds: 60,
  steps: 360,
  reference: 'ref',
  controls: [{ atom: 'handover', type: 'h2-handover', control: 'valve', default: 1 }],
  policies: [],
  referenceDecisions: [],
  compareKeys: [],
};

function runDoc(run: string, kind: RunDoc['kind'], baseline?: string): RunDoc {
  return {
    run, system: 'h2-chain', kind, mode: 'WHAT_IF', label: `${kind} ${run}`, hash: 'h', createdAt: '2026-11-21T00:00:00Z', baseline,
    firstStep: 0, steps: 360, decisions: [], policies: {}, inputs: [], inputsFrom: 'signals',
    status: { usability: 'restricted', reasons: ['generated, not measured: offtake.demand_kgph; the run shows the mechanism, not an effect'] },
    events: [], kpis: {},
  };
}

const delivered: KpiRow = { key: 'handover.delivered_kg', label: 'delivered', unit: 'kg', decimals: 1, better: 1, base: 70, run: 140, delta: 70 };
const trips: KpiRow = { key: 'ely.trips', label: 'trips', unit: '', decimals: 0, better: -1, base: 1, run: 1, delta: 0 };

function compareDoc(reasons: string[], kpis: KpiRow[]): CompareDoc {
  return {
    base: { run: 'sig-ref', label: 'Reference on the signals' }, run: { run: 'what-if', label: 'reopen', kind: 'what-if' },
    reasons, kpis, endStocks: [], start: scenario.start, stepSeconds: 60, series: {},
  };
}

function serve(compared: CompareDoc) {
  const answers: Record<string, unknown> = {
    '/scenario': scenario,
    '/runs': { runs: [{ run: 'what-if', kind: 'what-if', label: 'reopen', createdAt: '', kpis: {} }, { run: 'ref', kind: 'reference', label: 'the scenario', createdAt: '', kpis: {} }] },
    '/runs/ref': runDoc('ref', 'reference'),
    '/runs/ref/compare': compareDoc([], [{ ...trips, verdict: 'same' }]),
    '/runs/what-if': runDoc('what-if', 'what-if', 'sig-ref'),
    '/runs/what-if/compare?base=sig-ref': compared,
  };
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = url.replace('http://retrieval.test/v1/systems/h2-chain', '');
    if (!(path in answers)) throw new Error(`unexpected request ${url}`);
    return { ok: true, json: async () => answers[path] };
  }));
}

async function showTheRun() {
  render(<SimulatePlace system="h2-chain" base="http://retrieval.test" />);
  await userEvent.click(await screen.findByRole('button', { name: 'what-if reopen' }));
  await screen.findByRole('columnheader', { name: 'difference' });
}

function difference(label: string): HTMLElement {
  return within(screen.getByRole('row', { name: new RegExp(`^${label}`) })).getAllByRole('cell')[3];
}

beforeEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('shows the difference of a run on a generated input without ranking it, and says why at the table', async () => {
  serve(compareDoc(['generated, not measured: offtake.demand_kgph; no KPI is ranked better or worse'], [delivered, trips]));
  await showTheRun();
  expect(screen.getByRole('note', { name: 'Why no figure is ranked' })).toHaveTextContent(
    'generated, not measured: offtake.demand_kgph; no KPI is ranked better or worse.',
  );
  expect(difference('delivered')).toHaveTextContent('+70.0 kg');
  expect(difference('delivered')).not.toHaveClass('better');
  expect(difference('trips').textContent).toBe('');
});

it('ranks the difference of a run on reported inputs', async () => {
  serve(compareDoc([], [{ ...delivered, verdict: 'better' }, { ...trips, verdict: 'same' }]));
  await showTheRun();
  expect(screen.queryByRole('note', { name: 'Why no figure is ranked' })).toBeNull();
  expect(difference('delivered')).toHaveTextContent('+70.0 kg');
  expect(difference('delivered')).toHaveClass('better');
  expect(difference('trips').textContent).toBe('');
});
