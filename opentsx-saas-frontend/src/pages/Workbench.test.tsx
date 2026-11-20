import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../workbench/FlowComposer', () => ({
  default: ({ onOpen }: { onOpen: (ids: number[]) => void }) => <button onClick={() => onOpen([3, 4, 5])}>Open in the editor</button>,
}));
import Workbench from './Workbench';
import { flowsAPI, systemsAPI } from '../services/api';
import { useFlow } from '../workbench/flow';

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
  useFlow.getState().clear();
});

function show(path = '/workbench') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Workbench />
    </MemoryRouter>
  );
}

describe('Load episodes', () => {
  it('shows the reason the retrieval server gives', async () => {
    const error = 'from: "yesterday" is not an instant; expected an ISO-8601 instant such as 2026-01-01T00:00:00Z';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error, type: 'bad_request' }), { status: 400 })));
    show();
    fireEvent.click(screen.getByRole('button', { name: 'Load episodes' }));
    fireEvent.change(screen.getByLabelText(/^query/), { target: { value: 'from=yesterday' } });
    fireEvent.click(screen.getByRole('button', { name: 'Load' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(`Episodes could not be loaded from /retrieval/v1/episodes?from=yesterday: ${error}.`);
    expect(alert).not.toHaveTextContent('Is the retrieval server running?');
  });
});

describe('persist TSB', () => {
  it('shows why the selected bucket could not be computed', async () => {
    const x = Float64Array.from([0, 60, 120]);
    useFlow.getState().loadBucket(
      { name: 'raw', xKind: 'time', xLabel: 't [s]', yLabel: 'kW', series: [{ id: 's', label: 's', x, y: Float64Array.from([1, 2, 3]) }] },
      { kind: 'test', detail: '' }
    );
    useFlow.getState().applyOp('feature.rolling', { window: '' });
    show();
    fireEvent.click(screen.getByRole('tab', { name: 'TSB' }));
    fireEvent.click(screen.getByRole('button', { name: 'persist TSB' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The bucket Roll_raw could not be saved: window: empty value; expected a number above 0.'
    );
  });
});

describe('opening a signal', () => {
  it('says that the working copy could not be read and opens the series of the system document', async () => {
    localStorage.setItem('access_token', 'access');
    const config = { url: '/systems/h2-chain/bindings', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
    vi.spyOn(systemsAPI, 'bindings').mockRejectedValue(
      new AxiosError('Request failed with status code 500', AxiosError.ERR_BAD_RESPONSE, config, null, {
        data: { error: 'internal error', type: 'internal' },
        status: 500,
        statusText: '',
        headers: {},
        config,
      })
    );
    const from = '2026-10-01T00:00:00Z';
    const to = '2026-10-01T00:02:00Z';
    const answers: Record<string, unknown> = {
      '/retrieval/v1/systems/h2-chain/signals': {
        schema: 'opentsx.signal-status.v1', system: 'h2-chain', title: 'Hydrogen chain', version: 1, domain: 'h2', hash: 'abc',
        signals: [{ id: 'demand.kg_h', title: 'Demand', expect: { unit: 'kg/h' }, grades: { raw: { data: { series: ['s1'] } } }, state: 'open', next: [], usedBy: [] }],
      },
      '/retrieval/v1/inventory': {
        schema: 'opentsx.ts-inventory.v1',
        source: { kind: 'demo', topics: { episodes: 'tsx.h2.episodes.v2' }, domain: 'h2' },
        totals: { series: 1, episodes: 1, samples: 3, from, to, scenarios: [] },
        series: [{ seriesId: 's1', metric: 'demand', tags: {}, unit: 'kg/h', episodes: 1, samples: 3, from, to, intervalMicros: 60_000_000, missing: 0 }],
      },
    };
    const episodes = { episodes: [{ seriesId: 's1', metric: 'demand', timestampsMicros: [1790812800000000, 1790812860000000, 1790812920000000], values: [1, 2, 3] }] };
    const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(answers[url] ?? episodes), { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    show('/workbench?system=h2-chain&signal=demand.kg_h&grade=raw');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'your working copy could not be read (internal error); showing the system document.'
    );
    expect(fetch.mock.calls.map(([url]) => url)).toContain('/retrieval/v1/systems/h2-chain/signals');
    expect(useFlow.getState().context?.signal).toBe('demand.kg_h');
  });

  it('gives the reason the retrieval server sends', async () => {
    const error = 'no such system or route: /v1/systems/h2-chain/signals';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error, type: 'not_found' }), { status: 404 })));
    show('/workbench?system=h2-chain&signal=demand.kg_h&grade=raw');
    expect(await screen.findByRole('alert')).toHaveTextContent(`The signal demand.kg_h could not be opened: ${error}.`);
  });
});

describe('opening flows from the Flow Composer', () => {
  it('keeps the notice about kept tabs after the first flow has loaded', async () => {
    localStorage.setItem('access_token', 'access');
    const node = (id: string, op: string, input: string | null) => ({ id, op, input, label: id, params: {} });
    vi.spyOn(flowsAPI, 'getDefinition').mockResolvedValue({
      id: 3,
      name: 'f3',
      definition: { kind: 'opentsx.workbench.v1', nodes: [node('source', 'source', null), node('n1', 'diff', 'source')] },
    });
    vi.spyOn(window, 'open').mockReturnValueOnce(null).mockReturnValueOnce({} as Window);
    show();
    fireEvent.click(screen.getByRole('button', { name: 'Flow Composer' }));
    fireEvent.click(screen.getByText('Open in the editor'));
    expect(await screen.findByText('Flow "f3" opened. Load a bucket to run it.')).toBeInTheDocument();
    expect(screen.getByText(
      'The browser kept 1 of the flows from opening in tabs of their own. Allow pop-ups for this page, or open them one at a time.'
    )).toHaveAttribute('role', 'alert');
  });
});
