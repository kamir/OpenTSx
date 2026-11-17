import { createElement } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dagsAPI, flowsAPI } from '../services/api';
import DeploymentsPanel from './DeploymentsPanel';
import FlowComposer from './FlowComposer';
import PublishClean from './PublishClean';
import { DagState, SinkStatus, describeSink, hasProcessor, readDeployments, topicConflict } from './deployments';
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

  it('keeps a flow from starting', async () => {
    vi.stubGlobal('fetch', unreadable());
    render(createElement(FlowComposer, { base: 'http://retrieval.test', currentId: null, replaces: false, onOpen: () => {}, onNew: () => {}, onDeleted: () => {} }));
    await waitFor(() => expect(dagsAPI.capabilities).toHaveBeenCalled());
    fireEvent.click(await screen.findByTitle('Starts the vector processor on this flow'));
    expect(await screen.findByText(/start failed/)).toHaveTextContent(
      `"EXP2: T01": start failed, the deployments could not be read from ${url} (Failed to fetch); try again.`
    );
    expect(deploy).not.toHaveBeenCalled();
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
