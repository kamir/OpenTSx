import { Params } from './types';

/**
 * A number parameter checked as the processor checks it (CleanChain): an absent one takes `absent`, an emptied field
 * takes `emptied`, and either is refused when that is NaN; anything but a finite number for which `ok` holds is
 * refused. The refusal names the parameter.
 */
export function checked(p: Params, key: string, ok: (v: number) => boolean, allowed: string, absent = NaN, emptied = absent): number {
  const raw = p[key];
  const v = raw === undefined || raw === null ? absent : String(raw).trim() === '' ? emptied : raw;
  if (typeof v === 'number' && Number.isFinite(v) && ok(v)) return v;
  const problem = raw === undefined || raw === null ? 'missing' : String(raw).trim() === '' ? 'empty value' : typeof raw === 'number' ? `"${raw}" is out of range` : `"${raw.trim()}" is not a number`;
  throw new Error(`${key}: ${problem}; expected ${allowed}`);
}
