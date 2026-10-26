/**
 * Deterministic PRNG, so a recorded flow gives the same result on every run. The Python package draws the same
 * numbers (`Mulberry32` in python-package/opentsx/algorithms/event_sync.py), so surrogates agree across both.
 */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
