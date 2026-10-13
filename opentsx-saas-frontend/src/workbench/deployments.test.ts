import { describe, expect, it } from 'vitest';
import { DagState, SinkStatus, describeSink, hasProcessor } from './deployments';

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
});
