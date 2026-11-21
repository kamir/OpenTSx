import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { dagsAPI, wayangAPI, WayangDeployment } from '../services/api';
import { toDefinition, useFlow } from './flow';
import { buildWayangDag } from './wayang';
import WayangRunPanel from './WayangRunPanel';
import { SmartTopic } from '../pages/Workbench';

vi.mock('./DeploymentsPanel', () => ({ default: () => <div>Continuous processor controls</div> }));

const configuration = { inputTopics: ['tsx.test.one.v2', 'tsx.test.two.v2', 'tsx.test.three.v2'],
  seriesIds: ['0000000000000001', '0000000000000002'], nodeId: 'n1', system: 'plant', signal: 'power', outputTopic: 'tsx.test.clean.v2' };
const operators = ['convert.unit'];
const deployment: WayangDeployment = { id: 'local-one', flowId: 9, specHash: 'a'.repeat(64), planHash: 'b'.repeat(64),
  createdAtMicros: '1700000000000000', state: 'completed', error: null,
  assignments: configuration.inputTopics.map((inputTopic, index) => ({ node: `worker-${index + 1}`, inputTopic,
    requestHash: 'c'.repeat(64), specHash: 'a'.repeat(64),
    partitionCount: String(index + 1), outputTopic: configuration.outputTopic, state: 'completed',
    receipt: { inputRecords: 2, outputRecords: 2 } })) };

beforeEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  localStorage.setItem('access_token', 'fixture');
  useFlow.getState().clear();
  useFlow.setState({ track: 'test', domain: 'test', sourceTopic: configuration.inputTopics[0], wayang: configuration,
    selectedId: 'n1', sourceSeries: configuration.seriesIds, nodes: [
      { id: 'source', op: 'source', label: 'Source', input: null, params: {} },
      { id: 'n1', op: 'convert.unit', label: 'Convert', input: 'source', params: { factor: 2, offset: 1, unit: 'MW' } },
    ] });
  vi.spyOn(wayangAPI, 'capabilities').mockResolvedValue({ canRun: true, reason: null, operators,
    profiles: ['bounded', 'continuous'] });
  vi.spyOn(wayangAPI, 'list').mockResolvedValue([]);
  vi.spyOn(wayangAPI, 'deploy').mockResolvedValue(deployment);
});

it('saves the explicit selection and submits the exact reviewed graph only to the bounded API', async () => {
  const legacy = vi.spyOn(dagsAPI, 'deploy');
  const save = vi.fn(async () => { useFlow.getState().setSavedId(9); return 9; });
  render(<WayangRunPanel onSave={save} />);
  const run = screen.getByRole('button', { name: 'Run bounded with Wayang' });
  await waitFor(() => expect(run).toBeEnabled());
  fireEvent.click(run);
  await screen.findByText('Bounded deployment local-one: completed');
  expect(save).toHaveBeenCalledOnce();
  expect(wayangAPI.deploy).toHaveBeenCalledWith(9, buildWayangDag(useFlow.getState(), configuration, operators));
  expect(toDefinition(useFlow.getState()).wayang).toEqual(configuration);
  expect(legacy).not.toHaveBeenCalled();
  expect(screen.getByText(/3 internal partitions on worker-3/)).toBeInTheDocument();
});

it('requires explicit generic system and signal and retains all topics as flow settings', async () => {
  useFlow.setState({ wayang: { ...configuration, system: '', signal: '' } });
  render(<WayangRunPanel onSave={vi.fn()} />);
  await screen.findByText('Provide the output system and signal.');
  expect(screen.getByRole('button', { name: 'Run bounded with Wayang' })).toBeDisabled();
  fireEvent.change(screen.getByLabelText('Output system'), { target: { value: 'generic-system' } });
  fireEvent.change(screen.getByLabelText('Output signal'), { target: { value: 'generic-signal' } });
  expect(useFlow.getState().wayang?.system).toBe('generic-system');
  expect(useFlow.getState().wayang?.inputTopics).toEqual(configuration.inputTopics);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Run bounded with Wayang' })).toBeEnabled());
});

it('does not submit an unsupported operator or fall back when the bounded target is unavailable', async () => {
  vi.mocked(wayangAPI.capabilities).mockResolvedValue({ canRun: true, reason: null, operators: ['resample'] });
  render(<WayangRunPanel onSave={vi.fn()} />);
  await screen.findByText('Operator convert.unit is not available on the bounded Rust target.');
  expect(screen.getByRole('button', { name: 'Run bounded with Wayang' })).toBeDisabled();
  expect(wayangAPI.deploy).not.toHaveBeenCalled();
});

it('renders terminal per-worker topic inventories and receipts from the durable backend', async () => {
  vi.mocked(wayangAPI.list).mockResolvedValue([deployment]);
  render(<WayangRunPanel onSave={vi.fn()} />);
  await screen.findByText('Bounded deployment local-one: completed');
  expect(screen.getByText(/1 internal partitions on worker-1/)).toBeInTheDocument();
  expect(screen.getByText(/2 internal partitions on worker-2/)).toBeInTheDocument();
  expect(screen.getAllByText(/"inputRecords": 2/)).toHaveLength(3);
  expect(screen.getByText(deployment.specHash)).toBeInTheDocument();
  expect(screen.getByText(deployment.planHash)).toBeInTheDocument();
  expect(screen.getByText(deployment.createdAtMicros)).toBeInTheDocument();
  expect(screen.getAllByText('2 input records; 2 output records')).toHaveLength(3);
  expect(screen.getByText('Run audit').closest('details')).not.toHaveAttribute('open');
  expect(screen.getAllByText('Worker receipt').every((summary) => !summary.closest('details')?.open)).toBe(true);
  expect(screen.getByLabelText('Whole input topics')).toHaveAttribute('rows', '4');
});

it('defaults to continuous controls and isolates the explicitly selected bounded target', async () => {
  render(<SmartTopic node={useFlow.getState().nodes[1]} base="/retrieval" onSave={vi.fn()} />);
  const target = screen.getByLabelText('Deployment target');
  expect(target).toHaveValue('processor');
  expect(screen.getByText('Continuous processor controls')).toBeInTheDocument();
  expect(screen.queryByRole('group', { name: 'Bounded Wayang on Rust' })).not.toBeInTheDocument();
  fireEvent.change(target, { target: { value: 'wayang-rust' } });
  expect(screen.queryByText('Continuous processor controls')).not.toBeInTheDocument();
  expect(screen.queryByText('Published topics')).not.toBeInTheDocument();
  expect(screen.queryByText(/That processor is/)).not.toBeInTheDocument();
  expect(screen.getByRole('group', { name: 'Bounded Wayang on Rust' })).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Run bounded with Wayang' })).toBeEnabled());
  fireEvent.change(target, { target: { value: 'processor' } });
  expect(screen.queryByRole('group', { name: 'Bounded Wayang on Rust' })).not.toBeInTheDocument();
  expect(screen.getByText('Continuous processor controls')).toBeInTheDocument();
});

it('shows settings restored from another saved flow without changing continuous configuration', async () => {
  render(<WayangRunPanel onSave={vi.fn()} />);
  act(() => useFlow.getState().setWayang({ ...configuration, system: 'restored-system' }));
  await waitFor(() => expect(screen.getByLabelText('Output system')).toHaveValue('restored-system'));
  expect(useFlow.getState().sourceTopic).toBe(configuration.inputTopics[0]);
});

it('requires explicit selection of the continuous Rust target and submits its profile with the same original graph', async () => {
  const save = vi.fn(async () => { useFlow.getState().setSavedId(9); return 9; });
  render(<SmartTopic node={useFlow.getState().nodes[1]} base="/retrieval" onSave={save} />);
  fireEvent.change(screen.getByLabelText('Deployment target'), { target: { value: 'wayang-rust-continuous' } });
  expect(screen.getByRole('group', { name: 'Continuous Wayang on Rust' })).toBeInTheDocument();
  expect(screen.queryByText('Continuous processor controls')).not.toBeInTheDocument();
  const run = screen.getByRole('button', { name: 'Run continuously with Wayang' });
  await waitFor(() => expect(run).toBeEnabled());
  fireEvent.click(run);
  await waitFor(() => expect(wayangAPI.deploy).toHaveBeenCalledWith(9,
    buildWayangDag(useFlow.getState(), configuration, operators), 'continuous'));
  expect(toDefinition(useFlow.getState()).wayang).toEqual({ ...configuration, profile: 'continuous' });
});

it('keeps idle continuous progress visible and stops through the durable backend without another deploy', async () => {
  const continuous: WayangDeployment = { ...deployment, profile: 'continuous', state: 'idle', currentCycle: '2',
    completedCycles: '2', stopRequested: false, inputRecords: '24', outputRecords: '12',
    assignments: deployment.assignments.map((item, index) => ({ ...item, cumulativeInputRecords: String(4 * (index + 1)),
      cumulativeOutputRecords: String(2 * (index + 1)), positions: Array.from({ length: index + 1 }, (_, partition) =>
        ({ partition: String(partition), next: '4' })) })) };
  vi.mocked(wayangAPI.list).mockResolvedValue([continuous]);
  const stop = vi.spyOn(wayangAPI, 'stop').mockResolvedValue({ ...continuous, state: 'stopping', stopRequested: true });
  render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />);
  await screen.findByText('Continuous deployment local-one: idle');
  expect(screen.getByText('Cycle 2; 2 completed cycles')).toBeInTheDocument();
  expect(screen.getByText('24 total input records; 12 total output records')).toBeInTheDocument();
  expect(screen.getByText('12 cumulative input records; 6 cumulative output records')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Stop deployment local-one' }));
  await screen.findByText('Continuous deployment local-one: stopping');
  expect(stop).toHaveBeenCalledWith('local-one');
  expect(wayangAPI.deploy).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Stop deployment local-one' })).toBeDisabled();
});

it('refuses a continuous start when only bounded capabilities are advertised', async () => {
  vi.mocked(wayangAPI.capabilities).mockResolvedValue({ canRun: true, reason: null, operators, profiles: ['bounded'] });
  render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />);
  await screen.findByText('This installation does not advertise the continuous Rust profile.');
  expect(screen.getByRole('button', { name: 'Run continuously with Wayang' })).toBeDisabled();
  expect(wayangAPI.deploy).not.toHaveBeenCalled();
});

it('continues polling an idle deployment for new positions without submitting another deployment', async () => {
  vi.useFakeTimers();
  try {
    const idle: WayangDeployment = { ...deployment, profile: 'continuous', state: 'idle', currentCycle: '1',
      completedCycles: '1', stopRequested: false, inputRecords: '12', outputRecords: '6' };
    vi.mocked(wayangAPI.list).mockResolvedValueOnce([idle]).mockResolvedValue([{ ...idle, currentCycle: '2',
      completedCycles: '2', inputRecords: '24', outputRecords: '12' }]);
    await act(async () => { render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />); });
    expect(screen.getByText('12 total input records; 6 total output records')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(screen.getByText('24 total input records; 12 total output records')).toBeInTheDocument();
    expect(wayangAPI.list).toHaveBeenCalledTimes(2);
    expect(wayangAPI.deploy).not.toHaveBeenCalled();
  } finally {
    vi.useRealTimers();
  }
});

it('keeps stopped deployments terminal without offering a resume or replacement action', async () => {
  useFlow.getState().setSavedId(9);
  vi.mocked(wayangAPI.list).mockResolvedValue([{ ...deployment, profile: 'continuous', state: 'stopped',
    currentCycle: '2', completedCycles: '2', stopRequested: true, inputRecords: '24', outputRecords: '12' }]);
  render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />);
  await screen.findByText('Continuous deployment local-one: stopped');
  expect(screen.queryByRole('button', { name: 'Run continuously with Wayang' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Stop deployment local-one' })).not.toBeInTheDocument();
  expect(screen.getByText('This deployment is stopped. Reset disposable worker state before deploying a replacement.')).toBeInTheDocument();
});

function fencedDeployment(): WayangDeployment {
  return { ...deployment, profile: 'continuous', state: 'idle', currentCycle: '2', completedCycles: '2',
    stopRequested: false, inputRecords: '24', outputRecords: '12', publicationEpoch: '7', publicationRetired: false };
}

function advertiseFencing() {
  useFlow.getState().setSavedId(9);
  vi.mocked(wayangAPI.capabilities).mockResolvedValue({ canRun: true, reason: null, operators,
    profiles: ['bounded', 'continuous'], publicationFencing: true, switchModes: ['resume', 'restart'] });
}

it('resumes the current deployment with its actual epoch without saving or redeploying the DAG', async () => {
  advertiseFencing();
  const current = fencedDeployment();
  vi.mocked(wayangAPI.list).mockResolvedValue([current]);
  const replace = vi.spyOn(wayangAPI, 'replace').mockResolvedValue({ ...current, state: 'switching',
    switch: { operation: 'switch-one', mode: 'resume', state: 'pending', expectedEpoch: '7',
      deployment: current.id, publicationEpoch: null, error: null } });
  const save = vi.fn();
  render(<WayangRunPanel profile="continuous" onSave={save} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Resume deployment local-one with fencing' }));
  await screen.findByText('Continuous deployment local-one: switching');
  expect(replace).toHaveBeenCalledWith('local-one', { expectedEpoch: '7', mode: 'resume' });
  expect(save).not.toHaveBeenCalled();
  expect(wayangAPI.deploy).not.toHaveBeenCalled();
  expect(screen.getByText('Publication epoch 7')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Stop deployment local-one' })).toBeDisabled();
});

it('requires a changed output and saves the exact changed DAG before requesting a fresh restart', async () => {
  advertiseFencing();
  const current = fencedDeployment();
  vi.mocked(wayangAPI.list).mockResolvedValue([current]);
  const replace = vi.spyOn(wayangAPI, 'replace').mockResolvedValue({ ...current, state: 'switching',
    switch: { operation: 'switch-one', mode: 'restart', state: 'pending', expectedEpoch: '7',
      deployment: 'local-next', publicationEpoch: null, error: null } });
  const save = vi.fn(async () => 9);
  render(<WayangRunPanel profile="continuous" onSave={save} />);
  const restart = await screen.findByRole('button', { name: 'Restart changed DAG for deployment local-one' });
  expect(restart).toBeDisabled();
  fireEvent.change(screen.getByLabelText('Rust output topic'), { target: { value: 'tsx.fresh.clean.v2' } });
  expect(restart).toBeEnabled();
  expect(screen.getByText('Restart replays all selected topics from the start into a fresh output topic.')).toBeInTheDocument();
  fireEvent.click(restart);
  await waitFor(() => expect(replace).toHaveBeenCalledWith('local-one', { expectedEpoch: '7', mode: 'restart', flowId: 9,
    dagText: buildWayangDag(useFlow.getState(), { ...configuration, outputTopic: 'tsx.fresh.clean.v2' }, operators) }));
  expect(save).toHaveBeenCalledOnce();
  expect(wayangAPI.deploy).not.toHaveBeenCalled();
  expect(screen.getByText('Replacement target: local-next')).toBeInTheDocument();
});

it('polls pending switches and keeps publication uncertainty visible without allowing Stop', async () => {
  vi.useFakeTimers();
  try {
    advertiseFencing();
    const pending: WayangDeployment = { ...fencedDeployment(), state: 'switching', error: 'Publication outcome remains uncertain.',
      switch: { operation: 'switch-one', mode: 'resume', state: 'pending', expectedEpoch: '7',
        deployment: 'local-one', publicationEpoch: null, error: 'Publication outcome remains uncertain.' } };
    vi.mocked(wayangAPI.list).mockResolvedValueOnce([pending]).mockResolvedValue([{ ...pending, state: 'idle', error: null,
      publicationEpoch: '8', switch: { ...pending.switch!, state: 'active', publicationEpoch: '8', error: null } }]);
    await act(async () => { render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />); });
    expect(screen.getByRole('button', { name: 'Stop deployment local-one' })).toBeDisabled();
    expect(screen.getAllByText('Publication outcome remains uncertain.').length).toBeGreaterThan(0);
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(screen.getByText('Publication epoch 8')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resume deployment local-one with fencing' })).toBeEnabled();
    expect(wayangAPI.deploy).not.toHaveBeenCalled();
  } finally {
    vi.useRealTimers();
  }
});

it('does not offer switching for unallocated epochs, other selected flows or legacy capabilities', async () => {
  advertiseFencing();
  vi.mocked(wayangAPI.list).mockResolvedValue([{ ...fencedDeployment(), publicationEpoch: '0' },
    { ...fencedDeployment(), id: 'another-flow', flowId: 10 }]);
  render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />);
  await screen.findByText('Continuous deployment local-one: idle');
  expect(screen.queryByRole('button', { name: /with fencing/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Restart changed DAG/ })).not.toBeInTheDocument();
});

it('keeps a retired deployment terminal when its output topic changes', async () => {
  advertiseFencing();
  vi.mocked(wayangAPI.list).mockResolvedValue([{ ...fencedDeployment(), state: 'stopped', stopRequested: true,
    publicationRetired: true, publicationEpoch: '8' }]);
  render(<WayangRunPanel profile="continuous" onSave={vi.fn()} />);
  await screen.findByText('Continuous deployment local-one: stopped');
  expect(screen.queryByRole('button', { name: /with fencing/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Restart changed DAG/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Run continuously with Wayang' })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Rust output topic'), { target: { value: 'tsx.fresh.clean.v2' } });
  expect(screen.queryByRole('button', { name: 'Run continuously with Wayang' })).not.toBeInTheDocument();
  expect(screen.getByText('This deployment is retired. Replacement is unavailable after Stop.')).toBeInTheDocument();
});

it('shows a public located refusal with its original node and field without exposing transport details', async () => {
  vi.mocked(wayangAPI.deploy).mockRejectedValue({ isAxiosError: true, response: { data: { detail: {
    schema: 'opentsx.plan-refusal.v1', code: 'deployment.incarnation', node: 'n1',
    field: '/ownership/0/incarnation', message: 'Physical deployment refused: deployment.incarnation.',
  }, error: 'http://private-worker/input' } } });
  render(<WayangRunPanel onSave={vi.fn(async () => 9)} />);
  const run = screen.getByRole('button', { name: 'Run bounded with Wayang' });
  await waitFor(() => expect(run).toBeEnabled());
  fireEvent.click(run);
  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Physical deployment refused: deployment.incarnation.');
  expect(alert).toHaveTextContent('n1');
  expect(alert).toHaveTextContent('/ownership/0/incarnation');
  expect(alert).not.toHaveTextContent('private-worker');
});

it('rejects malformed located refusals instead of displaying untrusted message text', async () => {
  vi.mocked(wayangAPI.deploy).mockRejectedValue({ isAxiosError: true, response: { data: { detail: {
    schema: 'opentsx.plan-refusal.v1', code: 'deployment.incarnation', node: 'n1',
    field: '/ownership', message: 'http://private-worker/input',
  } } } });
  render(<WayangRunPanel onSave={vi.fn(async () => 9)} />);
  const run = screen.getByRole('button', { name: 'Run bounded with Wayang' });
  await waitFor(() => expect(run).toBeEnabled());
  fireEvent.click(run);
  expect(await screen.findByRole('alert')).toHaveTextContent('The deployment service returned an invalid located refusal.');
  expect(screen.getByRole('alert')).not.toHaveTextContent('private-worker');
});
