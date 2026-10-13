import { describe, expect, it } from 'vitest';
import {
  Candidate,
  bindingsYaml,
  dataExtent,
  guessOrigin,
  presetGenerator,
  suggestSeries,
  topicSource,
  NO_SIGNAL_FILTER,
  Signal,
  countsText,
  dataText,
  expectText,
  feedOf,
  filterSignals,
  guideSteps,
  DecisionStatus,
  PlanAtom,
  PlanDoc,
  PlanPort,
  carrierOf,
  durationText,
  planGraph,
  usability,
  railCounts,
  SystemStatus,
  groupSignals,
  intervalText,
  isSynthetic,
  operatingText,
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

  it('keeps what the sensor can deliver apart from what operation gives', () => {
    expect(operatingText({ unit: 'm/s', min: 0, max: 60, operating: { min: 0, max: 25 } })).toBe('0..25 m/s');
    expect(operatingText({ unit: 'kW', operating: { max: 3300 } })).toBe('up to 3,300 kW');
    expect(operatingText({ unit: 'kW', min: 0, max: 60 })).toBe('');
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
    expect(sourceText(price.grades.raw)).toBe('demo generator gen.ou, seed 7');
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

describe('the shell around a system', () => {
  const status = (signals: Signal[]): SystemStatus => ({
    schema: 'opentsx.signal-status.v1',
    system: 'h2-chain',
    title: 'H2',
    version: 1,
    domain: 'wind',
    example: true,
    hash: 'abc',
    problems: [],
    notes: [],
    counts: { signals: signals.length },
    states: { open: signals.filter((s) => s.state === 'open').length },
    signals,
  });
  const plan: PlanDoc = { schema: 'opentsx.system-plan.v1', system: 'h2-chain', title: 'H2', atoms: [], counts: { atoms: 9, inputsFromOutside: 6, inputsWithoutSignal: 0 } };
  const all = [base, avail, price, setpoint, demand];

  it('says for every step of the method how far the system is', () => {
    const steps = guideSteps(status(all), plan, 0);
    expect(steps.map((s) => `${s.n} ${s.title}: ${s.state} (${s.mark})`)).toEqual([
      '1 System: 9 atoms (done)',
      '2 Signals: 5 expected (done)',
      '3 Stock: 1 without data (todo)',
      '4 Bind: 1 open (todo)',
      '5 Prepare: 1 of 3 prepared (todo)',
      '6 Run: 0 running, 0 clean (todo)',
      '7 Model: needs clean signals (later)',
    ]);
    expect(steps.map((s) => s.place)).toEqual(['system', 'signals', 'signals', 'signals', 'signals', 'flows', 'models']);
  });

  it('names what is wrong before anything else', () => {
    const broken = { ...status(all), problems: ['park.x: no such port'] };
    expect(guideSteps(broken, { ...plan, counts: { ...plan.counts, inputsWithoutSignal: 2 } }, null).slice(0, 2).map((s) => `${s.state} (${s.mark})`)).toEqual([
      '9 atoms, 1 problems (todo)',
      '5 expected, 2 inputs without a signal (todo)',
    ]);
    expect(guideSteps(null, null, null)[0]).toMatchObject({ state: 'no plan', mark: 'todo' });
  });

  it('gives each place of the rail its number', () => {
    expect(railCounts(status(all), plan, 2)).toEqual({ system: '9 atoms', signals: '1 open, 4 to do', flows: '2 flows', models: 'none yet' });
    expect(railCounts(status([setpoint]), plan, null)).toMatchObject({ signals: '1 signals', flows: 'sign in' });
  });

  it('says what feeds an input of the plan', () => {
    expect(feedOf({ name: 'limit_kw', kind: 'SIGNAL', from: 'rules.limit_kw' })).toEqual({ kind: 'wired', text: 'rules.limit_kw' });
    expect(feedOf({ name: 'p_avail_kw', kind: 'FLOW', signal: 'park.p_avail_kw', state: 'clean-not-running' })).toEqual({ kind: 'signal', text: 'clean not running' });
    expect(feedOf({ name: 'x', kind: 'FLOW' })).toEqual({ kind: 'missing', text: 'no signal declared' });
  });
});

describe('binding and simulating a signal', () => {
  const cand = (seriesId: string, metric: string, unit: string, min: number, max: number, tags: Record<string, string> = {}, labels: Record<string, string[]> = {}): Candidate => ({
    seriesId, metric, unit, min, max, tags, labels, samples: 100, from: '2026-10-01T00:00:00Z', to: '2026-10-01T06:00:00Z',
  });
  const series = [
    cand('a', 'wind.met.wind_speed', 'm/s', 2, 14, { turbine: 'T02' }, { scenario: ['ostsee-demo'] }),
    cand('b', 'h2.demand', 'kg/h', 0, 80, { customer: 'c1' }),
    cand('c', 'h2.flow', 'kg/h', 0, 900, { line: 'l1' }),
    cand('d', 'wind.met.wind_speed', 'm/s', 2, 14, { turbine: 'T02', system: 'h2-chain', signal: 'park/T02/WMET.HorWdSpd' }),
  ];
  const open: Signal = { ...demand, expect: { unit: 'kg/h', min: 0, max: 120, interval: 'PT1M' } };

  it('suggests series by unit and range, and leaves out what was written for signals', () => {
    // same unit and inside the plausible range first, then same unit, then the rest
    expect(suggestSeries(open, series).map((c) => c.seriesId)).toEqual(['b', 'c', 'a']);
    expect(suggestSeries(open, series, 'wind t02').map((c) => c.seriesId)).toEqual(['a']);
  });

  it('builds the source of a binding and guesses where the data comes from', () => {
    expect(guessOrigin(series[0])).toBe('SYNTHETIC');
    expect(guessOrigin(series[1])).toBe('');
    expect(topicSource('tsx.wind.episodes.v2', series[1], 'REPORTED')).toEqual({
      kind: 'topic', topic: 'tsx.wind.episodes.v2', origin: 'REPORTED', select: { metric: 'h2.demand', tags: { customer: 'c1' } },
    });
  });

  it('presets a demo generator from what the signal should look like', () => {
    expect(presetGenerator('gen.ou', { min: 0, max: 120 })).toEqual({ mean: 60, sigma: 4, reversion: 0.1 });
    // the operating range says more about normal values than the plausible range of the sensor
    expect(presetGenerator('gen.ou', { min: -500, max: 3000, operating: { min: 0, max: 250 } })).toMatchObject({ mean: 125 });
    expect(presetGenerator('gen.sine', { min: 0, max: 120 })).toEqual({ mean: 60, amplitude: 30, period: 'P1D', sigma: 3 });
  });

  it('takes the period the system has data for', () => {
    const status = { signals: [base, { ...base, grades: { raw: { source: base.grades.raw!.source, data: { series: ['x'], samples: 1, missing: 0, from: '2026-10-01T00:00:00Z', to: '2026-10-01T06:00:00Z' } } } }, demand] } as never;
    expect(dataExtent(status)).toEqual({ from: '2026-10-01T00:00:00Z', to: '2026-10-01T06:00:00Z' });
    expect(dataExtent({ signals: [demand] } as never)).toBeNull();
  });

  it('writes the working copy as the lines for the system document', () => {
    expect(bindingsYaml([
      { signal: 'offtake.demand_kgph', grade: 'raw', source: { kind: 'generator', processor: 'gen.sine', seed: 1, params: { mean: 60, period: 'P1D' }, workingCopy: true } },
      { signal: 'market.price_eur_mwh', grade: 'raw', source: { kind: 'manual', value: 62.8, origin: 'REPORTED' } },
    ])).toBe(
      '# Working copy: put each source under the grade of its signal in the system document.\n' +
        '- id: market.price_eur_mwh\n  raw:\n    source: { kind: manual, value: 62.8, origin: REPORTED }\n' +
        '- id: offtake.demand_kgph\n  raw:\n    source: { kind: generator, processor: gen.sine, seed: 1, params: { mean: 60, period: P1D } }\n'
    );
  });
});

describe('plan as a picture and decisions', () => {
  const port = (name: string, from?: string, signal?: string, state?: string) => ({ name, kind: 'ANALOG', from, signal, state }) as PlanPort;
  const atom = (id: string, inputs: PlanPort[]): PlanAtom => ({ id, type: id, inputs, outputs: [], channels: 0 });
  const loop: PlanDoc = {
    schema: 'opentsx.system-plan.v1',
    system: 'x',
    title: 'x',
    counts: { atoms: 4, inputsFromOutside: 3, inputsWithoutSignal: 1 },
    atoms: [
      atom('offtake', [port('demand', undefined, 'offtake.demand', 'open'), port('valve')]),
      atom('park', [port('p_avail', undefined, 'park.p_avail', 'clean')]),
      atom('ely', [port('p_bar', 'line.p_bar'), port('m_offtake', 'offtake.m_out'), port('p_in', 'park.p_gen')]),
      atom('line', [port('m_in', 'ely.m_h2')]),
    ],
  };

  it('puts an atom one column right of what feeds it and leaves a wire back where it is', () => {
    const g = planGraph(loop);
    expect(g.nodes.map((n) => `${n.id} ${n.layer}/${n.row} ${n.mark}`)).toEqual(['offtake 0/0 missing', 'park 0/1 ok', 'ely 1/0 ok', 'line 2/0 ok']);
    expect(g.edges.filter((e) => e.back).map((e) => `${e.from}>${e.to}`)).toEqual(['line>ely']);
    expect(g.edges.find((e) => e.to === 'line')!.label).toBe('m_h2 > m_in');
    expect(g.edges.find((e) => e.back)!.label).toBe('p_bar');
  });

  it('puts the atoms in the phases of a time step when the plan names them, and tells the carriers apart', () => {
    const out = (name: string, unit: string, kind = 'FLOW') => ({ name, unit, kind }) as PlanPort;
    const phased: PlanDoc = {
      ...loop,
      phases: ['SOURCES', 'ASSETS', 'FLOWS'],
      atoms: [
        { id: 'offtake', type: 'h2-consumer', phase: 'SOURCES', channels: 0, outputs: [out('request', 'kg/h', 'SIGNAL')], inputs: [port('demand', undefined, 'offtake.demand', 'raw-only')] },
        { id: 'ely', type: 'electrolyzer', phase: 'ASSETS', channels: 0, outputs: [out('p_el', 'kW'), out('m_h2', 'kg/h')], inputs: [{ ...port('p_bar', 'buffer.p_bar'), delayed: true }] },
        { id: 'handover', type: 'h2-handover', phase: 'ASSETS', channels: 0, outputs: [out('m_out', 'kg/h')], inputs: [port('request', 'offtake.request'), port('valve', undefined, 'handover.valve', 'constant')] },
        { id: 'buffer', type: 'h2-buffer', phase: 'FLOWS', channels: 0, outputs: [out('p_bar', 'bar', 'SIGNAL')], inputs: [port('m_in', 'ely.m_h2'), port('p_meas', undefined, 'buffer.p_meas', 'open')] },
      ],
    };
    const g = planGraph(phased);
    expect(g.columns).toEqual(['Sources', 'Assets', 'Stocks']);
    expect(g.nodes.map((n) => `${n.id} ${n.layer}/${n.row} ${n.mark}`)).toEqual(['offtake 0/0 todo', 'ely 1/0 ok', 'handover 1/1 ok', 'buffer 2/0 open']);
    expect(g.edges.map((e) => `${e.from}>${e.to} ${e.carrier}${e.back ? ' late' : ''}`)).toEqual(['buffer>ely info late', 'offtake>handover info', 'ely>buffer hydrogen']);
    expect(carrierOf({ kind: 'FLOW', unit: 'kW' })).toBe('power');
    expect(carrierOf(undefined)).toBe('info');
    // a plan without phases keeps the layout by what feeds what
    expect(planGraph(loop).columns).toEqual([]);
  });

  it('counts what the analyses may claim, for the rail and the guide', () => {
    const analysis = (id: string, status: 'usable' | 'restricted' | 'blocked') => ({ id, title: id, status, reasons: [], validity: [] });
    const doc: DecisionStatus = {
      schema: 'opentsx.decision-status.v1',
      system: 'x',
      decisions: [{ id: 'd', title: 'd', needs: [], alternatives: [], analyses: [analysis('a', 'restricted'), analysis('b', 'blocked')] }],
    };
    expect(usability(doc)).toEqual({ usable: 0, restricted: 1, blocked: 1, analyses: 2 });
    expect(usability(null).analyses).toBe(0);
    expect(railCounts(null, null, null, doc).models).toBe('1 of 2 blocked');
    expect(guideSteps(null, null, null, doc)[6]).toMatchObject({ state: '0 usable, 1 restricted, 1 blocked', mark: 'todo' });
    expect(guideSteps(null, null, null)[6]).toMatchObject({ state: 'needs clean signals', mark: 'later' });
    expect(durationText('PT15M')).toBe('15 min');
    expect(durationText('PT1H30M')).toBe('1 h 30 min');
    expect(durationText('P1D')).toBe('P1D');
  });
});
