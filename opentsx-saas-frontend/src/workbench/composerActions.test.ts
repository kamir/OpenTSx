import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetFlows, newFlow, openFlows } from './composerActions';
import { useFlow } from './flow';
import { Bucket } from './types';

const bucket: Bucket = {
  name: 'B',
  xKind: 'index',
  xLabel: 'i',
  yLabel: 'y',
  series: [{ id: 's0', label: 's0', x: Float64Array.from([0, 1, 2]), y: Float64Array.from([1, 2, 4]) }],
};

beforeEach(() => {
  useFlow.getState().loadBucket(bucket, { kind: 'test', detail: '' });
  useFlow.getState().applyOp('diff');
  useFlow.getState().setSavedId(7);
});

afterEach(() => {
  vi.restoreAllMocks();
  useFlow.getState().clear();
});

describe('opening flows from the composer', () => {
  it('opens the first flow here and the others in tabs of their own', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue({} as Window);
    const here = vi.fn();
    expect(openFlows([3, 4, 5], here)).toBe(0);
    expect(here).toHaveBeenCalledWith(3);
    expect(open.mock.calls).toEqual([
      ['/workbench?flow=4', '_blank'],
      ['/workbench?flow=5', '_blank'],
    ]);
    expect(useFlow.getState().savedId).toBe(7);
  });

  it('counts the tabs the browser kept from opening', () => {
    vi.spyOn(window, 'open').mockReturnValueOnce(null).mockReturnValueOnce({} as Window);
    expect(openFlows([3, 4, 5], () => undefined)).toBe(1);
  });

  it('lets the stored flow replace what the workbench holds of it', () => {
    openFlows([7], () => undefined);
    expect(useFlow.getState().savedId).toBeNull();
    expect(useFlow.getState().nodes).toHaveLength(2);
  });
});

describe('a new flow', () => {
  it('empties the workbench', () => {
    newFlow();
    expect(useFlow.getState().nodes).toEqual([]);
    expect(useFlow.getState().savedId).toBeNull();
  });
});

describe('deleted flows', () => {
  it('leave the workbench alone when it holds none of them', () => {
    expect(forgetFlows([3, 4], false)).toBeNull();
    expect(forgetFlows([3, 4], true)).toBeNull();
    expect(useFlow.getState().savedId).toBe(7);
    expect(useFlow.getState().nodes).toHaveLength(2);
  });

  it('keep the nodes as an unsaved flow when asked to', () => {
    expect(forgetFlows([7], true)).toBe(7);
    expect(useFlow.getState().savedId).toBeNull();
    expect(useFlow.getState().nodes).toHaveLength(2);
  });

  it('empty the workbench otherwise', () => {
    expect(forgetFlows([3, 7], false)).toBe(7);
    expect(useFlow.getState().savedId).toBeNull();
    expect(useFlow.getState().nodes).toEqual([]);
  });
});
