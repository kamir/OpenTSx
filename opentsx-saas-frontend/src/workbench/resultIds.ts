/**
 * Result identity of the v2 model in the browser, the same as python-package/opentsx/model/analysis.py:
 * canonical form `escape(method)@escape(methodVersion)(k1=v1,k2=v2,...)[e1,e2,...]{d1,d2,...}` with the parameter keys
 * sorted by their UTF-8 bytes, the input episode ids in their given order and `\ { } , = ( ) [ ] @` escaped with a
 * backslash. The digest section holds the content digest of each input; records written before v2.2 have none and
 * leave it out.
 * A bigint is a long `l<decimal>`, a number a double `d<16 hex digits of the IEEE 754 bits, big-endian>`, a string
 * `s<escaped>`, a number[] an array of doubles `a<hex>;<hex>`. The result id is xxHash64 over the UTF-8 bytes.
 * Shared test vectors: opentsx-model/src/test/resources/testvectors/result-id.json.
 */

import { compareUtf8, xxh64Hex } from './seriesKeys';

export type ResultParam = bigint | number | string | number[];

const LONG_MIN = -(1n << 63n);
const LONG_MAX = (1n << 63n) - 1n;

const utf8 = new TextEncoder();
const DIGEST = /^[0-9a-f]{64}$/;

function escape(s: string): string {
  return s.replace(/[\\{},=()[\]@]/g, (c) => `\\${c}`);
}

function doubleHex(v: unknown): string {
  if (typeof v !== 'number') throw new Error(`An array parameter holds doubles only, not ${typeof v}.`);
  if (Number.isNaN(v)) throw new Error('NaN is not allowed in analysis parameters.');
  const view = new DataView(new ArrayBuffer(8));
  view.setFloat64(0, v, false);
  return view.getBigUint64(0, false).toString(16).padStart(16, '0');
}

function canonicalValue(v: ResultParam): string {
  if (typeof v === 'bigint') {
    if (v < LONG_MIN || v > LONG_MAX) throw new Error(`The long parameter ${v} is out of the signed 64-bit range.`);
    return `l${v}`;
  }
  if (typeof v === 'number') return `d${doubleHex(v)}`;
  if (typeof v === 'string') return `s${escape(v)}`;
  if (Array.isArray(v)) return `a${v.map(doubleHex).join(';')}`;
  throw new Error(`Parameters of type ${typeof v} are not supported. Use bigint, number, string or number[].`);
}

export function resultCanonical(
  method: string,
  methodVersion: string,
  params: Record<string, ResultParam>,
  inputEpisodeIds: readonly string[],
  inputDigests: readonly string[],
): string {
  if (!method) throw new Error('An analysis result needs a method.');
  if (!methodVersion) throw new Error('An analysis result needs a methodVersion.');
  if (inputEpisodeIds.length === 0) throw new Error('An analysis result needs at least one input episode.');
  const parts = Object.keys(params).sort(compareUtf8).map((k) => {
    if (!k) throw new Error('Parameter names must not be empty.');
    return `${escape(k)}=${canonicalValue(params[k])}`;
  });
  if (inputEpisodeIds.some((e) => !e)) throw new Error('Input episode ids must not be empty.');
  const out = `${escape(method)}@${escape(methodVersion)}(${parts.join(',')})[${inputEpisodeIds.map(escape).join(',')}]`;
  if (inputDigests.length === 0) return out;
  if (inputDigests.length !== inputEpisodeIds.length)
    throw new Error(`${inputDigests.length} inputDigests for ${inputEpisodeIds.length} inputEpisodeIds.`);
  inputDigests.forEach((d, i) => {
    if (!DIGEST.test(d)) throw new Error(`Input digest ${i} is not 64 lower-case hex characters.`);
  });
  return `${out}{${inputDigests.join(',')}}`;
}

export function resultId(
  method: string,
  methodVersion: string,
  params: Record<string, ResultParam>,
  inputEpisodeIds: readonly string[],
  inputDigests: readonly string[],
): string {
  return xxh64Hex(utf8.encode(resultCanonical(method, methodVersion, params, inputEpisodeIds, inputDigests)));
}
