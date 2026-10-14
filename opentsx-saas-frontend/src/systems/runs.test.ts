import { describe, expect, it } from 'vitest';
import { chartLines, clockOf, deltaText, eventText, kpiText, minuteOf, runRequest } from './runs';

const scenario = {
  steps: 360,
  controls: [
    { atom: 'handover', type: 'h2-handover', control: 'valve', default: 1 },
    { atom: 'ely', type: 'electrolyzer', control: 'setpoint_kw', default: 0 },
  ],
};

describe('asking the twin', () => {
  it('reads and writes the clock of a run', () => {
    expect(clockOf(149)).toBe('02:29');
    expect(minuteOf('02:29')).toBe(149);
    expect(minuteOf('2:05')).toBe(125);
    expect(Number.isNaN(minuteOf('02:75'))).toBe(true);
    expect(Number.isNaN(minuteOf('soon'))).toBe(true);
  });

  it('turns the form into the request, and an empty value into a release', () => {
    expect(runRequest(scenario, [{ at: '02:00', target: 'handover.valve', value: '1' }, { at: '', target: '', value: '' }], 'follow', '', true)).toEqual({
      body: { base: 'reference', interventions: [{ at: '02:00', atom: 'handover', control: 'valve', value: 1 }] },
    });
    expect(runRequest(scenario, [{ at: '03:00', target: 'ely.setpoint_kw', value: '' }], 'pressure-aware', '01:10', false).body).toEqual({
      base: 'empty',
      interventions: [{ at: '03:00', atom: 'ely', control: 'setpoint_kw', value: 'release' }],
      policies: { ely: 'pressure-aware' },
      from: 70,
    });
  });

  it('asks for the signals of the system as inputs', () => {
    expect(runRequest(scenario, [], 'follow', '', true, 'signals').body).toEqual({ base: 'reference', interventions: [], inputs: 'signals' });
    expect(runRequest(scenario, [], 'follow', '01:10', true, 'signals').error).toContain('not offered yet');
  });

  it('says why a question cannot be asked', () => {
    expect(runRequest(scenario, [{ at: '07:00', target: 'handover.valve', value: '1' }], '', '', true).error).toContain('no time of the run');
    expect(runRequest(scenario, [{ at: '02:00', target: '', value: '1' }], '', '', true).error).toBe('Choose what to set.');
    expect(runRequest(scenario, [{ at: '02:00', target: 'handover.valve', value: 'open' }], '', '', true).error).toContain('no number');
    expect(runRequest(scenario, [{ at: '00:30', target: 'handover.valve', value: '1' }], '', '01:10', true).error).toContain('cannot change what was decided');
    expect(runRequest(scenario, [], '', '09:00', true).error).toContain('inside the run');
  });

  it('writes figures, differences and events for a person', () => {
    expect(kpiText(17446.4, 0, 'kWh')).toBe('17,446 kWh');
    expect(kpiText(NaN, 1, 'kg')).toBe('n/a');
    expect(deltaText({ delta: -1, decimals: 0, unit: '', verdict: 'better' })).toBe('-1');
    expect(deltaText({ delta: 312.9, decimals: 1, unit: 'kg', verdict: 'better' })).toBe('+312.9 kg');
    expect(deltaText({ delta: 0, decimals: 1, unit: 'kg', verdict: 'same' })).toBe('');
    expect(eventText({ type: 'protective_shutdown', atom: 'ely', attrs: { p_bar: '200.084' } })).toBe('the electrolyser shuts down to protect itself (200 bar)');
    expect(eventText({ type: 'intervention', atom: 'handover', attrs: { control: 'valve', value: '0.0', reason: 'valve closed' } })).toBe('handover.valve set to 0.0 (valve closed)');
    expect(eventText({ type: 'something_new', atom: 'x', attrs: {} })).toBe('something new');
  });

  it('draws lines with gaps, inside the range of values and limits', () => {
    const c = chartLines([[0, 10, null, 10, 20], [null, null, 5, 5, 5]], [30], 100, 50);
    expect(c.paths[0]).toHaveLength(2);
    expect(c.paths[1]).toHaveLength(1);
    expect(c.max).toBeGreaterThan(30);
    expect(c.min).toBeLessThan(0);
    expect(c.y(30)).toBeLessThan(c.y(0));
    expect(chartLines([[null]], [], 10, 10).paths[0]).toEqual([]);
  });
});
