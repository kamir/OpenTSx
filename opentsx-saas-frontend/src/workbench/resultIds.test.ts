import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ResultParam, resultCanonical, resultId } from './resultIds';

type VectorParam = { long: string } | { double: number | string } | { string: string } | { array: number[] };

interface ResultVector {
  method: string;
  methodVersion: string;
  params: Record<string, VectorParam>;
  inputEpisodeIds: string[];
  inputDigests: string[];
  canonical: string;
  resultId: string;
}

function paramOf(p: VectorParam): ResultParam {
  if ('long' in p) return BigInt(p.long);
  if ('double' in p) return typeof p.double === 'number' ? p.double : Number(p.double);
  if ('string' in p) return p.string;
  return p.array;
}

const EPISODES = ['01HF7Z1Y4X0000000000000000'];
const NONE: string[] = [];

describe('result ids', () => {
  it('give the canonical forms and ids of the shared test vectors', () => {
    const file = resolve(process.cwd(), '../opentsx-model/src/test/resources/testvectors/result-id.json');
    const vectors: ResultVector[] = JSON.parse(readFileSync(file, 'utf8'));
    expect(vectors.length).toBe(12);
    for (const v of vectors) {
      const params = Object.fromEntries(Object.entries(v.params).map(([k, p]) => [k, paramOf(p)]));
      expect(resultCanonical(v.method, v.methodVersion, params, v.inputEpisodeIds, v.inputDigests)).toBe(v.canonical);
      expect(resultId(v.method, v.methodVersion, params, v.inputEpisodeIds, v.inputDigests)).toBe(v.resultId);
    }
    expect(vectors[9].inputEpisodeIds).toEqual(vectors[0].inputEpisodeIds);
    expect(vectors[9].canonical).toBe(`${vectors[0].canonical}{${vectors[9].inputDigests[0]}}`);
    expect(new Set([vectors[0].resultId, vectors[9].resultId, vectors[10].resultId]).size).toBe(3);
  });

  it('fail on digests of the wrong count or form', () => {
    const d = 'a'.repeat(64);
    expect(() => resultId('dfa', '1.0.0', {}, ['e1', 'e2'], [d])).toThrow('1 inputDigests for 2 inputEpisodeIds.');
    expect(() => resultId('dfa', '1.0.0', {}, ['e1', 'e2'], [d, 'A'.repeat(64)])).toThrow(
      'Input digest 1 is not 64 lower-case hex characters.',
    );
    expect(() => resultId('dfa', '1.0.0', {}, ['e1', 'e2'], ['a'.repeat(63), d])).toThrow(
      'Input digest 0 is not 64 lower-case hex characters.',
    );
  });

  it('keep the sign of zero and the bits of infinities', () => {
    expect(resultCanonical('m', '1', { a: -0, b: -Infinity, c: [Infinity, -0] }, ['e'], NONE)).toBe(
      'm@1(a=d8000000000000000,b=dfff0000000000000,c=a7ff0000000000000;8000000000000000)[e]',
    );
  });

  it('accept the limits of a signed 64-bit long', () => {
    expect(resultCanonical('m', '1', { lo: -(1n << 63n), hi: (1n << 63n) - 1n }, ['e'], NONE)).toBe(
      'm@1(hi=l9223372036854775807,lo=l-9223372036854775808)[e]',
    );
  });

  it('fail on NaN, booleans, longs out of range and other types', () => {
    expect(() => resultId('dfa', '1.0.0', { fitFrom: NaN }, EPISODES, NONE)).toThrow(/NaN/);
    expect(() => resultId('dfa', '1.0.0', { scales: [16, NaN] }, EPISODES, NONE)).toThrow(/NaN/);
    expect(() => resultId('dfa', '1.0.0', { detrend: true as unknown as ResultParam }, EPISODES, NONE)).toThrow(/boolean/);
    expect(() => resultId('dfa', '1.0.0', { scales: [16, '32'] as unknown as ResultParam }, EPISODES, NONE)).toThrow(/string/);
    expect(() => resultId('dfa', '1.0.0', { order: null as unknown as ResultParam }, EPISODES, NONE)).toThrow(/object/);
    expect(() => resultId('dfa', '1.0.0', { order: 1n << 63n }, EPISODES, NONE)).toThrow(/out of the signed 64-bit range/);
    expect(() => resultId('dfa', '1.0.0', { order: -(1n << 63n) - 1n }, EPISODES, NONE)).toThrow(/out of the signed 64-bit range/);
  });

  it('fail on an empty method, version, parameter name, input list or input id', () => {
    expect(() => resultId('', '1.0.0', {}, EPISODES, NONE)).toThrow(/method/);
    expect(() => resultId('dfa', '', {}, EPISODES, NONE)).toThrow(/methodVersion/);
    expect(() => resultId('dfa', '1.0.0', {}, [], NONE)).toThrow(/at least one input episode/);
    expect(() => resultId('dfa', '1.0.0', { '': 1n }, EPISODES, NONE)).toThrow(/Parameter names/);
    expect(() => resultId('dfa', '1.0.0', {}, ['e', ''], NONE)).toThrow(/Input episode ids/);
  });
});
