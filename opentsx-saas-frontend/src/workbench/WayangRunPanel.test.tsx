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
