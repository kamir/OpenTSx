import { createElement } from 'react';
import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dagsAPI, flowsAPI } from '../services/api';
import DeploymentsPanel from './DeploymentsPanel';
import FlowComposer from './FlowComposer';
import PublishClean from './PublishClean';
import { DagState, SinkStatus, deployFlow, describeSink, hasProcessor, readDeployments, topicConflict } from './deployments';
import { cleanTopic, toDefinition, useFlow } from './flow';

const sink = (over: Partial<SinkStatus>): SinkStatus => ({
  vector: 'turbine-state',
  topic: 'tsx.wind.smart.turbine-state.vectors.v1',
  state: 'running',
  records: 3600,
  at: '2026-10-13T10:00:30Z',
  ...over,
});

describe('deployments', () => {
  it('says in plain words what a sink does', () => {
    expect(describeSink(sink({ watermark: '2026-10-01T06:00:00Z' }))).toBe('running: 3,600 measuring points written, data up to 2026-10-01 06:00 UTC');
    expect(describeSink(sink({ state: 'waiting', waitingForCount: 2 }))).toBe('waiting for 2 series that have not sent data yet');
    expect(describeSink(sink({ state: 'failed', error: 'the broker did not confirm every measuring point' }))).toBe(
      'failed, trying again: the broker did not confirm every measuring point'
    );
    expect(describeSink(sink({ state: 'stopped' }))).toBe('stopped after 3,600 measuring points');
  });

  it('notices a deployment nobody works on', () => {
    const dag: DagState = { dag: 'wind-exp2-smart', desired: 'deployed', by: 'admin', at: '2026-10-13T09:00:00Z', sinks: [sink({})] };
    expect(hasProcessor(dag, '2026-10-13T10:01:00Z')).toBe(true);
    expect(hasProcessor(dag, '2026-10-13T10:10:00Z')).toBe(false);
    // just deployed, no report yet: give the processor its two minutes
    expect(hasProcessor({ ...dag, at: '2026-10-13T10:09:30Z', sinks: [] }, '2026-10-13T10:10:00Z')).toBe(true);
    expect(hasProcessor({ ...dag, sinks: [] }, '2026-10-13T10:10:00Z')).toBe(false);
  });

  it('keeps two deployments from writing one topic', () => {
    const topic = 'tsx.wind.smart.turbine-state.vectors.v1';
    const other: DagState = { dag: 'wind-exp2-smart', desired: 'deployed', by: 'a', at: '2026-10-13T10:00:00Z', sinks: [sink({})] };
    expect(topicConflict([other], 'wind-flow-7', [topic])).toMatch(/already written by the deployment wind-exp2-smart/);
    expect(topicConflict([other], 'wind-exp2-smart', [topic])).toBeNull();
    expect(topicConflict([{ ...other, desired: 'stopped' }], 'wind-flow-7', [topic])).toBeNull();
    expect(topicConflict([other], 'wind-flow-7', ['tsx.wind.smart.other.vectors.v1'])).toBeNull();
    // right after a deployment the processor has not reported yet: the flow says what it writes
    const fresh: DagState = { dag: 'wind-flow-8', desired: 'deployed', by: 'a', at: '2026-10-13T10:00:00Z', sinks: [] };
    expect(topicConflict([fresh], 'wind-flow-7', [topic])).toBeNull();
    expect(topicConflict([fresh], 'wind-flow-7', [topic], [{ dag: 'wind-flow-8', published: [topic] }])).toMatch(/wind-flow-8/);
    // and before the deployment is listed at all, the active flow does
    const active = { dag: 'wind-flow-9', published: [topic], status: 'active', runnable: true };
    expect(topicConflict([], 'wind-flow-7', [topic], [active])).toMatch(/wind-flow-9/);
    expect(topicConflict([], 'wind-flow-9', [topic], [active])).toBeNull();
    expect(topicConflict([], 'wind-flow-7', [topic], [{ ...active, status: 'paused' }])).toBeNull();
    expect(topicConflict([], 'wind-flow-7', [topic], [{ ...active, runnable: false }])).toBeNull();
  });
});

describe('an unknown list of deployments', () => {
  const url = 'http://retrieval.test/v1/dags';
  const topic = 'tsx.wind.smart.t01.vectors.v1';
  const flow = {
    id: 7,
    name: 'EXP2: T01',
    description: '',
    updated_at: '2026-10-13T10:02:11.123456',
    definition: toDefinition({
      track: 'EXP2',
      domain: 'wind',
      sourceTopic: 'tsx.wind.episodes.v2',
      sourceSeries: ['a', 'b'],
      nodes: [
        { id: 'source', op: 'source', label: 'T01', params: {}, input: null },
        { id: 'n1', op: 'align.vector', label: 'Vector_T01', params: {}, input: 'source', sinkTopic: topic },
      ],
    }),
  };
  const unreadable = () => vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
  let deploy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.setItem('access_token', 'test');
    vi.spyOn(dagsAPI, 'capabilities').mockResolvedValue({ canRun: true, reason: null });
    vi.spyOn(flowsAPI, 'list').mockResolvedValue([flow] as never);
    deploy = vi.spyOn(dagsAPI, 'deploy').mockResolvedValue({ dag: 'wind-flow-7', state: 'deployed' });
    vi.spyOn(flowsAPI, 'patch').mockResolvedValue(flow as never);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    useFlow.getState().clear();
  });

  it('is an error that names the server and the reason', async () => {
    vi.stubGlobal('fetch', unreadable());
    await expect(readDeployments(url)).rejects.toThrow(`the deployments could not be read from ${url} (Failed to fetch); try again`);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('busy', { status: 503 })));
    await expect(readDeployments(url)).rejects.toThrow(`the deployments could not be read from ${url} (HTTP 503); try again`);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })));
    await expect(readDeployments(url)).rejects.toThrow(`the deployments could not be read from ${url} (the answer has no list of deployments); try again`);
    const list = { now: '2026-10-13T10:00:00Z', writes: true, dags: [] };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(list), { status: 200 })));
    await expect(readDeployments(url)).resolves.toEqual(list);
  });

  it('keeps Deploy off', async () => {
    const panel = () =>
      render(
        createElement(MemoryRouter, null, createElement(DeploymentsPanel, { base: 'http://retrieval.test', blocker: null, checks: [], onSave: async () => 7 }))
      );
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})));
    const reading = panel();
    await screen.findByText('You are signed in as an administrator.');
    expect(screen.getByText(`Reading the state of the deployments from ${url}.`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Deploy' })).toBeDisabled();
    reading.unmount();
    vi.stubGlobal('fetch', unreadable());
    const { unmount } = panel();
    await screen.findByText(`Deploy stays off: the deployments could not be read from ${url} (Failed to fetch); try again. Another deployment may write the same topic.`);
    expect(screen.getByRole('button', { name: 'Deploy' })).toBeDisabled();
    expect(deploy).not.toHaveBeenCalled();
    unmount();
    const list = { now: '2026-10-13T10:00:00Z', writes: true, dags: [] };
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(list), { status: 200 })));
    panel();
    await screen.findByText('Nothing is deployed in this domain.');
    expect(screen.getByRole('button', { name: 'Deploy' })).toBeEnabled();
  });

  it('keeps a flow from starting when the list cannot be read at the click', async () => {
    const list = { now: '2026-10-13T10:00:00Z', writes: true, dags: [] };
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(list), { status: 200 })));
    render(createElement(FlowComposer, { base: 'http://retrieval.test', currentId: null, replaces: false, onOpen: () => {}, onNew: () => {}, onDeleted: () => {} }));
    await waitFor(() => expect(dagsAPI.capabilities).toHaveBeenCalled());
    const start = await screen.findByTitle('Starts the vector processor on this flow');
    await waitFor(() => expect(start).toBeEnabled());
    vi.stubGlobal('fetch', unreadable());
    fireEvent.click(start);
    expect(await screen.findByText(/start failed/)).toHaveTextContent(
      `"EXP2: T01": start failed, the deployments could not be read from ${url} (Failed to fetch); try again.`
    );
    expect(deploy).not.toHaveBeenCalled();
  });

  it('keeps Start, Stop and Delete of a flow off and says why', async () => {
    vi.stubGlobal('fetch', unreadable());
    const count = vi.fn();
    render(
      createElement(FlowComposer, { base: 'http://retrieval.test', currentId: null, replaces: false, onOpen: () => {}, onNew: () => {}, onDeleted: () => {}, onCount: count })
    );
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Flow 7 EXP2: T01' }));
    expect(
      await screen.findByText(`Start, Stop and Delete stay off: the deployments could not be read from ${url} (Failed to fetch); try again.`)
    ).toBeInTheDocument();
    const footer = within(screen.getByText('Smart Topic:').parentElement!);
    expect(footer.getByRole('button', { name: 'Delete' })).toBeDisabled();
    expect(footer.getByRole('button', { name: 'Start' })).toBeDisabled();
    expect(footer.getByRole('button', { name: 'Stop' })).toBeDisabled();
    expect(screen.getByTitle('Starts the vector processor on this flow')).toBeDisabled();
    fireEvent.click(footer.getByRole('button', { name: 'Delete' }));
    expect(screen.queryByText(/for good\?/)).toBeNull();
    expect(screen.getByText(/1 flows in your account, how many run is not known\./)).toBeInTheDocument();
    expect(count).toHaveBeenLastCalledWith(1, null);
  });

  it('counts the flows that run when the list can be read', async () => {
    const list = { now: '2026-10-13T10:00:00Z', writes: true, dags: [{ dag: 'wind-flow-7', desired: 'deployed', by: 'admin', at: '2026-10-13T10:00:00Z', sinks: [] }] };
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(list), { status: 200 })));
    const count = vi.fn();
    render(
      createElement(FlowComposer, { base: 'http://retrieval.test', currentId: null, replaces: false, onOpen: () => {}, onNew: () => {}, onDeleted: () => {}, onCount: count })
    );
    expect(await screen.findByText(/1 flows in your account, 1 running\./)).toBeInTheDocument();
    expect(count).toHaveBeenLastCalledWith(1, 1);
  });

  it('keeps Deploy from deploying when the list cannot be read at the click', async () => {
    const list = { now: '2026-10-13T10:00:00Z', writes: true, dags: [] };
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(list), { status: 200 })));
    render(
      createElement(MemoryRouter, null, createElement(DeploymentsPanel, { base: 'http://retrieval.test', blocker: null, checks: [], onSave: async () => 7 }))
    );
    const button = screen.getByRole('button', { name: 'Deploy' });
    await waitFor(() => expect(button).toBeEnabled());
    vi.stubGlobal('fetch', unreadable());
    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `Deploy failed: the deployments could not be read from ${url} (Failed to fetch); try again`
    );
    expect(deploy).not.toHaveBeenCalled();
  });

  it('keeps a clean signal from starting', async () => {
    vi.stubGlobal('fetch', unreadable());
    useFlow.setState({
      track: 'EXP2',
      domain: 'wind',
      sourceTopic: 'tsx.wind.episodes.v2',
      sourceSeries: ['abc123'],
      nodes: [
        { id: 'source', op: 'source', label: 'power_active_T02', params: {}, input: null },
        { id: 'n1', op: 'resample', label: 'Resample_power', params: { every: 60, agg: 'MEAN' }, input: 'source', sinkTopic: cleanTopic('wind') },
      ],
      selectedId: 'n1',
      context: {
        system: 'h2-chain',
        systemTitle: 'Hydrogen chain',
        signal: 'park/T02/WTUR.W',
        grade: 'raw',
        expect: { unit: 'kW', min: -100, max: 10000, interval: 'PT1S' },
        origin: 'SYNTHETIC',
        synthetic: true,
      },
    });
    render(createElement(PublishClean, { base: 'http://retrieval.test', onSave: async () => 7, onDone: () => {}, onClose: () => {} }));
    const start = screen.getByRole('button', { name: 'Publish and start' });
    await waitFor(() => expect(start).toBeEnabled());
    fireEvent.click(start);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `Publishing failed: the deployments could not be read from ${url} (Failed to fetch); try again.`
    );
    expect(deploy).not.toHaveBeenCalled();
  });
});

describe('deployFlow', () => {
  const base = 'http://retrieval.test/';
  const url = 'http://retrieval.test/v1/dags';
  const topic = 'tsx.wind.smart.t01.vectors.v1';
  let deploy: ReturnType<typeof vi.spyOn>;
  let patch: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.spyOn(flowsAPI, 'list').mockResolvedValue([]);
    deploy = vi.spyOn(dagsAPI, 'deploy').mockResolvedValue({ dag: 'wind-flow-7', state: 'deployed' });
    patch = vi.spyOn(flowsAPI, 'patch').mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('throws when the list of deployments cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(deployFlow(base, 7, 'wind-flow-7', 'dag: yaml', [topic])).rejects.toThrow(
      `the deployments could not be read from ${url} (Failed to fetch); try again`
    );
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'this server has no DAGs (it needs --kafscale)', type: 'not_found' }), { status: 404 })));
    await expect(deployFlow(base, 7, 'wind-flow-7', 'dag: yaml', [topic])).rejects.toThrow(
      `the deployments could not be read from ${url} (this server has no DAGs (it needs --kafscale)); try again`
    );
    expect(deploy).not.toHaveBeenCalled();
    expect(patch).not.toHaveBeenCalled();
  });

  it('refuses a topic another deployment writes', async () => {
    const other: DagState = { dag: 'wind-exp2-smart', desired: 'deployed', by: 'a', at: '2026-10-13T10:00:00Z', sinks: [sink({ topic })] };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ now: '2026-10-13T10:00:00Z', writes: true, dags: [other] }), { status: 200 })));
    await expect(deployFlow(base, 7, 'wind-flow-7', 'dag: yaml', [topic])).rejects.toThrow(`${topic} is already written by the deployment wind-exp2-smart`);
    expect(deploy).not.toHaveBeenCalled();
  });

  it('deploys the flow and marks it active', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ now: '2026-10-13T10:00:00Z', writes: true, dags: [] }), { status: 200 })));
    await expect(deployFlow(base, 7, 'wind-flow-7', 'dag: yaml', [topic])).resolves.toEqual({ dag: 'wind-flow-7', state: 'deployed' });
    expect(deploy).toHaveBeenCalledWith('dag: yaml');
    expect(patch).toHaveBeenCalledWith(7, { status: 'active' });
  });
});

describe('a backend that cannot be asked', () => {
  const config = { url: '/dags/capabilities', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
  const down = () => new AxiosError('Request failed with status code 502', AxiosError.ERR_BAD_RESPONSE, config, null, {
    data: { error: 'the backend is not reachable', type: 'unavailable' }, status: 502, statusText: '', headers: {}, config,
  });
  const list = { now: '2026-10-13T10:00:00Z', writes: true, dags: [] };

  beforeEach(() => {
    localStorage.setItem('access_token', 'test');
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(list), { status: 200 })));
    vi.spyOn(dagsAPI, 'capabilities').mockRejectedValue(down());
    vi.spyOn(flowsAPI, 'list').mockResolvedValue([]);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    useFlow.getState().clear();
  });

  it('is named in the deploy panel', async () => {
    render(createElement(MemoryRouter, null, createElement(DeploymentsPanel, { base: 'http://retrieval.test', blocker: null, checks: [], onSave: async () => 7 })));
    expect(await screen.findByText('Whether this account may deploy could not be read: the backend is not reachable.')).toBeInTheDocument();
  });

  it('is named in the Flow Composer', async () => {
    render(createElement(FlowComposer, { base: 'http://retrieval.test', currentId: null, replaces: false, onOpen: () => {}, onNew: () => {}, onDeleted: () => {} }));
    expect(
      await screen.findByText(/Deploying is not open to this account: Whether this account may deploy could not be read: the backend is not reachable\./)
    ).toBeInTheDocument();
  });
});

describe('Stop in the deploy panel', () => {
  const deployed: DagState = { dag: 'wind-flow-7', desired: 'deployed', by: 'admin', at: '2026-10-13T10:00:00Z', sinks: [] };

  beforeEach(() => {
    localStorage.setItem('access_token', 'test');
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify({ now: '2026-10-13T10:00:00Z', writes: true, dags: [deployed] }), { status: 200 })));
    vi.spyOn(dagsAPI, 'capabilities').mockResolvedValue({ canRun: true, reason: null });
    vi.spyOn(dagsAPI, 'stop').mockResolvedValue({ dag: 'wind-flow-7', state: 'stopped' });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('says when the status of the flow could not be set', async () => {
    const config = { url: '/flows/7', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
    vi.spyOn(flowsAPI, 'patch').mockRejectedValue(
      new AxiosError('Request failed with status code 404', AxiosError.ERR_BAD_REQUEST, config, null, { data: { error: 'flow 7: not found; use the id of one of your flows', type: 'not_found' }, status: 404, statusText: '', headers: {}, config })
    );
    render(createElement(MemoryRouter, null, createElement(DeploymentsPanel, { base: 'http://retrieval.test', blocker: null, checks: [], onSave: async () => 7 })));
    const stop = await screen.findByRole('button', { name: 'Stop' });
    await waitFor(() => expect(stop).toBeEnabled());
    fireEvent.click(stop);
    expect(await screen.findByRole('status')).toHaveTextContent('wind-flow-7 is stopped. The status of flow 7 stays as it was: flow 7: not found; use the id of one of your flows.');
  });
});

describe('the other signals of the system in Publish', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    useFlow.getState().clear();
  });

  it('say why they could not be read', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'internal error', type: 'internal' }), { status: 500 })));
    useFlow.setState({
      track: 'EXP2',
      domain: 'h2',
      sourceTopic: 'tsx.h2.clean.v2',
      sourceSeries: ['abc123'],
      nodes: [
        { id: 'source', op: 'source', label: 'demand', params: {}, input: null },
        { id: 'n1', op: 'resample', label: 'Resample_demand', params: { every: 60, agg: 'MEAN' }, input: 'source' },
      ],
      selectedId: 'n1',
      context: { system: 'h2-chain', systemTitle: 'Hydrogen chain', signal: 'demand.kg_h', grade: 'clean', expect: { unit: 'kg/h' }, synthetic: false },
    });
    render(createElement(PublishClean, { base: 'http://retrieval.test', onSave: async () => 7, onDone: () => {}, onClose: () => {} }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The other signals of h2-chain could not be read: internal error.'
    );
  });
});

