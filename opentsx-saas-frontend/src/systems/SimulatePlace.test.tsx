import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import SimulatePlace from './SimulatePlace';

afterEach(() => {
  vi.unstubAllGlobals();
});

const ROOT = '/retrieval/v1/systems/h2-chain';
const SCENARIO = {
  system: 'h2-chain', title: 'A night of the hydrogen chain', description: 'One night.', decision: 'When to open the valve.',
  start: '2026-10-01T00:00:00Z', stepSeconds: 60, steps: 120, reference: 'ref-1', controls: [], policies: [], referenceDecisions: [], compareKeys: [],
};
const RUN = {
  run: 'ref-1', system: 'h2-chain', kind: 'reference', mode: 'scenario', label: 'Reference', hash: 'h', createdAt: '2026-10-01T00:00:00Z',
  firstStep: 0, steps: 120, decisions: [], policies: {}, inputs: [], events: [], status: { usability: 'usable', reasons: ['invented inputs'] },
};
const COMPARE = { base: { run: 'ref-1', label: 'Reference' }, run: { run: 'ref-1', label: 'Reference', kind: 'reference' }, kpis: [], endStocks: [], start: SCENARIO.start, stepSeconds: 60, series: {} };

function twin(over: Record<string, () => Response> = {}) {
  const answers: Record<string, unknown> = {
    [`${ROOT}/scenario`]: SCENARIO,
    [`${ROOT}/runs`]: { runs: [{ run: 'ref-1', kind: 'reference', label: 'Reference', createdAt: SCENARIO.start, kpis: {} }] },
    [`${ROOT}/runs/ref-1`]: RUN,
    [`${ROOT}/runs/ref-1/compare`]: COMPARE,
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => over[url]?.() ?? new Response(JSON.stringify(answers[url]), { status: answers[url] ? 200 : 404 }))
  );
}

const failing = (status: number, error: string) => () => new Response(JSON.stringify({ error, type: 'internal' }), { status });

describe('the place Simulate', () => {
  it('says that the runs kept by the server could not be read', async () => {
    twin({ [`${ROOT}/runs`]: failing(500, 'internal error') });
    render(<SimulatePlace system="h2-chain" base="/retrieval" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('The runs kept by the server could not be read: internal error.');
  });

  it('encodes the run id in the path of the run and of its comparison', async () => {
    const id = 'night/1?x=2';
    const encoded = 'night%2F1%3Fx%3D2';
    const answers: Record<string, unknown> = {
      [`${ROOT}/scenario`]: { ...SCENARIO, reference: id },
      [`${ROOT}/runs`]: { runs: [] },
      [`${ROOT}/runs/${encoded}`]: { ...RUN, run: id },
      [`${ROOT}/runs/${encoded}/compare`]: COMPARE,
    };
    const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(answers[url]), { status: answers[url] ? 200 : 404 }));
    vi.stubGlobal('fetch', fetch);
    render(<SimulatePlace system="h2-chain" base="/retrieval" />);
    expect(await screen.findByText('What happens')).toBeInTheDocument();
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(expect.arrayContaining([`${ROOT}/runs/${encoded}`, `${ROOT}/runs/${encoded}/compare`]));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says that a run could not be shown', async () => {
    twin();
    render(<SimulatePlace system="h2-chain" base="/retrieval" />);
    await screen.findByText('Runs kept (1)');
    twin({ [`${ROOT}/runs/ref-1`]: failing(500, 'internal error') });
    fireEvent.click(screen.getByRole('button', { name: 'Show the reference' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The run ref-1 could not be shown: internal error.');
  });
});
