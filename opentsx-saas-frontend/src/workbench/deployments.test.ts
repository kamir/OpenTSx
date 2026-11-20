import { describe, expect, it } from 'vitest';
import { DagState, JobStatus, SinkStatus, describeJob, describeSink, hasProcessor, jobIsStale, reportAge, topicConflict } from './deployments';

const sink = (over: Partial<SinkStatus>): SinkStatus => ({
  vector: 'turbine-state',
  topic: 'tsx.wind.smart.turbine-state.vectors.v1',
  state: 'running',
  records: 3600,
  at: '2026-10-13T10:00:30Z',
  ...over,
});

const job = (over: Partial<JobStatus>): JobStatus => ({
  dag: 'edge-pass',
  job: 'edge-pass',
  track: 'B',
  planVersion: 3,
  state: 'running',
  records: 3600,
  watermark: null,
  waitingFor: [],
  skippedBlocks: 0,
  at: '2026-10-13T10:00:30Z',
  site: 'site-1',
  topic: 'tsx.wind.episodes.v2',
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

  it('says in plain words what a job reads', () => {
    expect(describeJob(job({}))).toBe('running: 3,600 input records read');
    expect(describeJob(job({ state: 'idle' }))).toBe('idle, no new input: 3,600 input records read');
    expect(describeJob(job({ state: 'stopped' }))).toBe('stopped after 3,600 input records');
    expect(describeJob(job({ state: 'failed', error: 'record 7 does not decode' }))).toBe(
      'failed after 3,600 input records: record 7 does not decode'
    );
    expect(describeJob(job({ state: 'failed' }))).toBe('failed after 3,600 input records: no reason given');
  });

  it('notices a job that has gone quiet while it should still report', () => {
    expect(jobIsStale(job({}), '2026-10-13T10:02:30Z')).toBe(false);
    expect(jobIsStale(job({}), '2026-10-13T10:02:31Z')).toBe(true);
    expect(jobIsStale(job({ state: 'idle' }), '2026-10-13T10:10:00Z')).toBe(true);
    // stopped and failed are the last reports of a job: their age says nothing about it
    expect(jobIsStale(job({ state: 'stopped' }), '2026-10-13T12:00:00Z')).toBe(false);
    expect(jobIsStale(job({ state: 'failed' }), '2026-10-13T12:00:00Z')).toBe(false);
  });

  it('says how old a report is', () => {
    const at = '2026-10-13T10:00:30Z';
    expect(reportAge(at, '2026-10-13T10:01:00Z')).toBe('30 s ago');
    expect(reportAge('2026-10-13T10:00:30.123456789Z', '2026-10-13T10:01:00.5Z')).toBe('30 s ago');
    expect(reportAge(at, '2026-10-13T10:03:00Z')).toBe('2 min ago');
    expect(reportAge(at, '2026-10-13T13:00:30Z')).toBe('3 h ago');
    expect(reportAge(at, '2026-10-16T10:00:30Z')).toBe('3 d ago');
    expect(reportAge('2026-10-13T10:01:05Z', '2026-10-13T10:01:00Z')).toBe('5 s ahead of the server clock');
  });
});
