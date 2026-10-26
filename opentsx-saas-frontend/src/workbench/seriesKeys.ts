/**
 * Series identity of the v2 model in the browser, the same as opentsx-model/src/main/java/org/opentsx/model/SeriesKeys.java:
 * canonical form `escape(metric){k1=v1,k2=v2,...}` with the keys sorted by their UTF-8 bytes and `\ { } , =` escaped
 * with a backslash; the series id is the lower-case hex of xxHash64 (seed 0) over the UTF-8 bytes of that form.
 * Shared test vectors: opentsx-model/src/test/resources/testvectors/series-id.json.
 */

const MASK = (1n << 64n) - 1n;
const P1 = 0x9e3779b185ebca87n;
const P2 = 0xc2b2ae3d27d4eb4fn;
const P3 = 0x165667b19e3779f9n;
const P4 = 0x85ebca77c2b2ae63n;
const P5 = 0x27d4eb2f165667c5n;

const utf8 = new TextEncoder();

function rotl(x: bigint, r: bigint): bigint {
  return ((x << r) | (x >> (64n - r))) & MASK;
}

function round(acc: bigint, lane: bigint): bigint {
  return (rotl((acc + lane * P2) & MASK, 31n) * P1) & MASK;
}

function merge(h: bigint, v: bigint): bigint {
  return (((h ^ round(0n, v)) * P1) + P4) & MASK;
}

function u64(b: Uint8Array, i: number): bigint {
  let v = 0n;
  for (let k = 7; k >= 0; k--) v = (v << 8n) | BigInt(b[i + k]);
  return v;
}

function u32(b: Uint8Array, i: number): bigint {
  return BigInt((b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)) >>> 0);
}

/** xxHash64 with seed 0, as 16 lower-case hex characters. */
export function xxh64Hex(data: Uint8Array): string {
  const n = data.length;
  let i = 0;
  let h: bigint;
  if (n >= 32) {
    let v1 = (P1 + P2) & MASK;
    let v2 = P2;
    let v3 = 0n;
    let v4 = (-P1) & MASK;
    for (; i + 32 <= n; i += 32) {
      v1 = round(v1, u64(data, i));
      v2 = round(v2, u64(data, i + 8));
      v3 = round(v3, u64(data, i + 16));
      v4 = round(v4, u64(data, i + 24));
    }
    h = (rotl(v1, 1n) + rotl(v2, 7n) + rotl(v3, 12n) + rotl(v4, 18n)) & MASK;
    h = merge(merge(merge(merge(h, v1), v2), v3), v4);
  } else {
    h = P5;
  }
  h = (h + BigInt(n)) & MASK;
  for (; i + 8 <= n; i += 8) h = ((rotl(h ^ round(0n, u64(data, i)), 27n) * P1) + P4) & MASK;
  if (i + 4 <= n) {
    h = ((rotl(h ^ ((u32(data, i) * P1) & MASK), 23n) * P2) + P3) & MASK;
    i += 4;
  }
  for (; i < n; i++) h = (rotl(h ^ ((BigInt(data[i]) * P5) & MASK), 11n) * P1) & MASK;
  h = ((h ^ (h >> 33n)) * P2) & MASK;
  h = ((h ^ (h >> 29n)) * P3) & MASK;
  h ^= h >> 32n;
  return h.toString(16).padStart(16, '0');
}

function escape(s: string): string {
  return s.replace(/[\\{},=]/g, (c) => `\\${c}`);
}

function compareUtf8(a: string, b: string): number {
  const x = utf8.encode(a);
  const y = utf8.encode(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i];
  return x.length - y.length;
}

export function canonicalKey(metric: string, tags: Record<string, string> = {}): string {
  if (!metric) throw new Error('A series key needs a metric.');
  const keys = Object.keys(tags).sort(compareUtf8);
  return `${escape(metric)}{${keys.map((k) => `${escape(k)}=${escape(tags[k])}`).join(',')}}`;
}

export function seriesIdOf(metric: string, tags: Record<string, string> = {}): string {
  return xxh64Hex(utf8.encode(canonicalKey(metric, tags)));
}
