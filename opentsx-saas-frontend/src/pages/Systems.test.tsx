import { afterEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../workbench/FlowComposer', () => ({
  default: ({ onOpen }: { onOpen: (ids: number[]) => void }) => <button onClick={() => onOpen([3, 4, 5])}>Open in the editor</button>,
}));
import Systems from './Systems';
import { systemsAPI } from '../services/api';
import { useAuthStore } from '../store/authStore';

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  useAuthStore.setState({ isAuthenticated: false, user: null, error: null });
});

const STATUS = {
  schema: 'opentsx.signal-status.v1', system: 'h2-chain', title: 'Hydrogen chain', version: 1, domain: 'h2', hash: 'abcdef0123',
  problems: [], notes: [], counts: { signals: 0 }, states: {}, signals: [],
};
const PLAN = { schema: 'opentsx.system-plan.v1', system: 'h2-chain', title: 'Hydrogen chain', atoms: [], counts: { atoms: 2, inputsFromOutside: 0, inputsWithoutSignal: 0 } };
const DECISIONS = { schema: 'opentsx.decision-status.v1', system: 'h2-chain', decisions: [] };
const ROOT = '/retrieval/v1/systems';

/** Answers the retrieval server; `over` replaces an answer by "<method> <path>". */
function retrieval(over: Record<string, Response> = {}) {
  const answers: Record<string, unknown> = {
    [`GET ${ROOT}`]: { systems: [{ system: 'h2-chain', title: 'Hydrogen chain' }] },
    [`GET ${ROOT}/h2-chain/signals`]: STATUS,
    [`GET ${ROOT}/h2-chain/plan`]: PLAN,
    [`GET ${ROOT}/h2-chain/decisions`]: DECISIONS,
    [`POST ${ROOT}/h2-chain/signals`]: STATUS,
    [`POST ${ROOT}/h2-chain/plan`]: PLAN,
    [`POST ${ROOT}/h2-chain/decisions`]: DECISIONS,
  };
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${url}`;
    return over[key] ?? new Response(JSON.stringify(answers[key]), { status: answers[key] ? 200 : 404 });
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

function failed(status: number, data: unknown) {
  const config = { url: '/systems/h2-chain/bindings', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
  return new AxiosError(`Request failed with status code ${status}`, AxiosError.ERR_BAD_RESPONSE, config, null, { data, status, statusText: '', headers: {}, config });
}

function show(path = '/systems/h2-chain/signals') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/systems" element={<Systems />} />
        <Route path="/systems/:system/:place" element={<Systems />} />
      </Routes>
    </MemoryRouter>
  );
}

const binding = { signal: 'demand.kg_h', grade: 'raw', source: { kind: 'topic', topic: 'tsx.h2.episodes.v2', series: 's1' } };

describe('the systems page', () => {
  it('says why the account could not be read', async () => {
    const error = 'your account could not be read (database unavailable); reload the page to try again';
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)));
    localStorage.setItem('access_token', 'access');
    useAuthStore.setState({ isAuthenticated: true, user: null, error });
    render(
      <MemoryRouter initialEntries={['/systems']}>
        <Systems />
      </MemoryRouter>
    );
    expect(await screen.findByText(error)).toHaveAttribute('role', 'alert');
  });

  it('says that the working copy could not be read and shows the system document', async () => {
    localStorage.setItem('access_token', 'access');
    vi.spyOn(systemsAPI, 'bindings').mockRejectedValue(failed(500, { error: 'internal error', type: 'internal' }));
    const fetch = retrieval();
    show();
    expect(await screen.findByText('your working copy could not be read (internal error); showing the system document.')).toHaveAttribute('role', 'alert');
    expect(await screen.findByText('System h2-chain, version 1, hash abcdef01.')).toBeInTheDocument();
    expect(fetch.mock.calls.map(([url]) => url)).toContain(`${ROOT}/h2-chain/signals`);
  });

  it('says that the decisions could not be read', async () => {
    retrieval({ [`GET ${ROOT}/h2-chain/decisions`]: new Response(JSON.stringify({ error: 'internal error', type: 'internal' }), { status: 500 }) });
    show();
    expect(await screen.findByText('the decisions could not be read (internal error).')).toHaveAttribute('role', 'alert');
    expect(await screen.findByText('System h2-chain, version 1, hash abcdef01.')).toBeInTheDocument();
  });

  it('says when the plan of the working copy could not be read', async () => {
    localStorage.setItem('access_token', 'access');
    vi.spyOn(systemsAPI, 'bindings').mockResolvedValue([binding]);
    retrieval({ [`POST ${ROOT}/h2-chain/plan`]: new Response(JSON.stringify({ error: 'the body is not a JSON object', type: 'bad_request' }), { status: 400 }) });
    show();
    expect(
      await screen.findByText('the plan with your working copy could not be read (the body is not a JSON object); showing the plan of the system document.')
    ).toHaveAttribute('role', 'alert');
  });

  it('gives the reason the retrieval server or nginx sends', async () => {
    retrieval({ [`GET ${ROOT}/h2-chain/signals`]: new Response(JSON.stringify({ error: 'the retrieval server is not reachable', type: 'unavailable' }), { status: 502 }) });
    show();
    expect(await screen.findByText('The system could not be read: the retrieval server is not reachable.')).toHaveAttribute('role', 'alert');
  });

  it('says how many flows the browser kept from opening in tabs of their own', async () => {
    localStorage.setItem('access_token', 'access');
    vi.spyOn(systemsAPI, 'bindings').mockResolvedValue([]);
    vi.spyOn(window, 'open').mockReturnValueOnce({} as Window).mockReturnValueOnce(null);
    retrieval();
    show('/systems/h2-chain/flows');
    fireEvent.click(await screen.findByText('Open in the editor'));
    expect(
      await screen.findByText(
        'The browser kept 1 of the flows from opening in tabs of their own, so this page stayed and did not open the first one either. Allow pop-ups for this page, or open them one at a time.'
      )
    ).toHaveAttribute('role', 'alert');
    expect(screen.getByText('Open in the editor')).toBeInTheDocument();
  });

  it('lets go of the notice about kept flows when another place opens', async () => {
    localStorage.setItem('access_token', 'access');
    vi.spyOn(systemsAPI, 'bindings').mockResolvedValue([]);
    vi.spyOn(window, 'open').mockReturnValue(null);
    retrieval();
    show('/systems/h2-chain/flows');
    fireEvent.click(await screen.findByText('Open in the editor'));
    expect(await screen.findByText(/The browser kept 2 of the flows/)).toHaveAttribute('role', 'alert');
    fireEvent.click(screen.getAllByText('Signals')[0]);
    expect(screen.queryByText('Open in the editor')).toBeNull();
    expect(screen.queryByText(/The browser kept/)).toBeNull();
  });

  it('gives the reason of the systems list', async () => {
    retrieval({ [`GET ${ROOT}`]: new Response(JSON.stringify({ error: 'internal error', type: 'internal' }), { status: 500 }) });
    show('/systems');
    expect(await screen.findByText('The systems could not be read: internal error.')).toHaveAttribute('role', 'alert');
  });
});
