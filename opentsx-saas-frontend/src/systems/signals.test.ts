import { describe, expect, it } from 'vitest';
import {
  NO_SIGNAL_FILTER,
  Signal,
  countsText,
  dataText,
  expectText,
  filterSignals,
  groupSignals,
  intervalText,
  isSynthetic,
  originText,
  parseStatus,
  scopeGrade,
  scopeLink,
  sourceText,
  stateText,
} from './signals';

const base: Signal = {
  id: 'park/T02/WTUR.W',
  atom: 'park',
  group: 'T02',
  name: 'WTUR.W',
  title: 'Wirkleistung WEA T02',
  port: false,
  expect: { unit: 'kW', min: -100, max: 10000, interval: 'PT1S', kind: 'ANALOG' },
  grades: {
    raw: {
      source: { kind: 'topic', topic: 'tsx.wind.episodes.v2', metric: 'wind.turbine.power_active', tags: { turbine: 'T02' }, origin: 'SYNTHETIC' },
      data: { series: ['aaa'], samples: 21600, missing: 108, min: -20, max: 2950 },
    },
  },
  via: 'topic',
  origin: 'SYNTHETIC',
  state: 'raw-only',
  next: ['prepare'],
  usedBy: [],
};
const price: Signal = {
  ...base,
  id: 'market.price_eur_mwh',
  atom: 'market',
  group: '',
  name: 'price_eur_mwh',
  port: true,
  expect: { unit: 'EUR/MWh', interval: 'PT15M' },
  grades: { raw: { source: { kind: 'generator', processor: 'gen.ou', seed: 7, origin: 'SYNTHETIC' } } },
  via: 'simulated',
  state: 'bound-no-data',
  next: ['generate'],
};
const setpoint: Signal = {
  ...base,
  id: 'ely.setpoint_kw',
  atom: 'ely',
  group: '',
  name: 'setpoint_kw',
  port: true,
  grades: { raw: { source: { kind: 'manual', value: 5000, origin: 'REPORTED' } } },
  via: 'manual',
  origin: 'REPORTED',
  state: 'constant',
  next: [],
};
const demand: Signal = { ...base, id: 'offtake.demand_kgph', atom: 'offtake', group: '', name: 'demand_kgph', port: true, grades: {}, via: 'open', origin: undefined, state: 'open', next: ['bind', 'simulate'] };
const avail: Signal = { ...base, id: 'park.p_avail_kw', group: '', name: 'p_avail_kw', port: true, grades: { clean: { source: { kind: 'flow', flow: 'park-power', output: 'sum' } } }, via: 'derived', state: 'clean-not-running', next: ['start'] };

describe('words for a signal', () => {
  it('writes the expectation in one line', () => {
    expect(expectText(base.expect)).toBe('kW -100..10,000, 1 s');
    expect(expectText({ unit: 'EUR/MWh', interval: 'PT15M' })).toBe('EUR/MWh, 15 min');
    expect(expectText({ min: 0 })).toBe('from 0');
    expect(expectText({})).toBe('not stated');
    expect(intervalText('PT1H30M')).toBe('1 h 30 min');
    expect(intervalText('P1D')).toBe('P1D');
  });

  it('never lets simulated data look like a measurement', () => {
    expect(originText(price)).toBe('SIMULATED');
    expect(originText(base)).toBe('synthetic');
    expect(originText(setpoint)).toBe('reported');
    expect(originText(demand)).toBe('n/a');
    expect(isSynthetic(base)).toBe(true);
    expect(isSynthetic(setpoint)).toBe(false);
  });

  it('says the state so that the next step is clear', () => {
    expect(stateText(base)).toBe('raw only');
    expect(stateText(price)).toBe('not generated yet (gen.ou, seed 7)');
    expect(stateText(setpoint)).toBe('constant 5,000');
    expect(stateText(demand)).toBe('open');
    expect(stateText(avail)).toBe('clean declared, not running');
  });

  it('describes source and data of a grade', () => {
    expect(sourceText(base.grades.raw)).toBe('topic tsx.wind.episodes.v2: wind.turbine.power_active turbine=T02');
    expect(sourceText(price.grades.raw)).toBe('generator gen.ou, seed 7');
    expect(sourceText(avail.grades.clean)).toBe('flow park-power, output sum');
    expect(sourceText(setpoint.grades.raw)).toBe('set by hand: 5,000');
    expect(sourceText(undefined)).toBe('none');
    expect(dataText(base.grades.raw?.data)).toBe('1 series, 21,600 samples, 0.5 % missing, -20..2,950');
    expect(dataText(undefined)).toBe('no data found');
  });
});

describe('the list of signals', () => {
  const all = [base, avail, price, setpoint, demand];

  it('counts by where the signals come from', () => {
    expect(countsText({ signals: 8, topic: 2, derived: 1, simulated: 1, manual: 2, open: 2 })).toBe('8 signals: 2 from topics, 1 derived, 1 simulated, 2 manual, 2 open');
    expect(countsText({ signals: 0 })).toBe('0 signals');
  });

  it('groups by atom, ports before the assets below it', () => {
    expect(groupSignals(all).map((g) => `${g.title}: ${g.signals.map((s) => s.name).join(' ')}`)).toEqual([
      'ely: setpoint_kw',
      'market: price_eur_mwh',
      'offtake: demand_kgph',
      'park: p_avail_kw',
      'park / T02: WTUR.W',
    ]);
  });

  it('filters by words, state, source and what needs doing', () => {
    expect(filterSignals(all, NO_SIGNAL_FILTER)).toHaveLength(5);
    expect(filterSignals(all, { ...NO_SIGNAL_FILTER, text: 'park kw' }).map((s) => s.id)).toEqual(['park/T02/WTUR.W', 'park.p_avail_kw']);
    expect(filterSignals(all, { ...NO_SIGNAL_FILTER, state: 'open' }).map((s) => s.id)).toEqual(['offtake.demand_kgph']);
    expect(filterSignals(all, { ...NO_SIGNAL_FILTER, via: 'simulated' }).map((s) => s.id)).toEqual(['market.price_eur_mwh']);
    expect(filterSignals(all, { ...NO_SIGNAL_FILTER, todo: true }).map((s) => s.id)).not.toContain('ely.setpoint_kw');
    expect(filterSignals([{ ...setpoint, outOfRange: { min: -1, max: 6000 } }], { ...NO_SIGNAL_FILTER, todo: true })).toHaveLength(1);
  });

  it('opens the best grade that has data on the scope', () => {
    expect(scopeGrade(base)).toBe('raw');
    expect(scopeGrade(price)).toBeNull();
    const withClean = { ...base, grades: { ...base.grades, clean: { source: { kind: 'flow' as const, flow: 'f' }, data: { series: ['ddd'], samples: 1, missing: 0 } } } };
    expect(scopeGrade(withClean)).toBe('clean');
    expect(scopeLink('h2-chain', base, 'raw')).toBe('/workbench?system=h2-chain&signal=park%2FT02%2FWTUR.W&grade=raw');
  });

  it('refuses an answer that is no status document', () => {
    expect(() => parseStatus({ schema: 'something.else', signals: [] })).toThrow(/opentsx.signal-status.v1/);
    expect(parseStatus({ schema: 'opentsx.signal-status.v1', signals: [] }).signals).toEqual([]);
  });
});
