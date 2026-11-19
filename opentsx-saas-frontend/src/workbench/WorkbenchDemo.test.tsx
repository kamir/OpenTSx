import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Workbench from '../pages/Workbench';
import { flowsAPI } from '../services/api';
import { thermalDemo } from './deviceSample';
import { evaluate, useFlow } from './flow';

vi.mock('./Scope', () => ({ default: () => <div>Scope preview</div> }));
vi.mock('./FlowGraph', () => ({ default: () => <div>Flow preview</div> }));

const demoPath = '/workbench?mode=engineer&demo=thermal';

function mount(path = demoPath) {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={[path]}>
        <Workbench />
      </MemoryRouter>
    </StrictMode>,
  );
}

function addDifference() {
  fireEvent.change(screen.getByLabelText('Find an operator'), {
    target: { value: 'diff' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Configure Diffs' }));
  fireEvent.click(
    within(
      screen.getByRole('complementary', { name: 'Operator inspector' }),
    ).getByRole('button', { name: 'Add operator' }),
  );
}

function existingWorkspace() {
  useFlow.getState().loadBucket(thermalDemo(), {
    kind: 'file',
    detail: 'existing-analysis.json',
  });
  useFlow.getState().applyOp('smooth.movavg', { window: 7 });
  return useFlow.getState();
}

beforeEach(() => {
  localStorage.clear();
  useFlow.getState().clear();
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.reject(new Error('Network is unavailable'))),
  );
  vi.spyOn(flowsAPI, 'getDefinition').mockRejectedValue(
    new Error('Network is unavailable'),
  );
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('standalone thermal workbench demo', () => {
  it('loads one synthetic thermal fixture without services, including StrictMode setup', () => {
    const loaded = vi.fn();
    const unsubscribe = useFlow.subscribe((state, previous) => {
      if (state.source !== previous.source) loaded();
    });
    mount();
    unsubscribe();

    const state = useFlow.getState();
    expect(state.source).not.toBeNull();
    expect(loaded).toHaveBeenCalledTimes(1);
    expect(state.sourceInfo).toEqual({
      kind: 'generator',
      detail: 'Thermal bench v1 (synthetic)',
    });
    expect(state.source?.series.map((series) => series.y.length)).toEqual([
      512, 512,
    ]);
    expect(state.source?.series.map((series) => series.unit)).toEqual([
      '°C',
      '%',
    ]);
    expect(state.source?.episodes).toHaveLength(8);
    expect(
      state.source?.episodes?.every(
        (episode) => episode.labels?.origin === 'SYNTHETIC',
      ),
    ).toBe(true);
    expect(
      Array.from(state.source!.series[0].y).filter(
        (value) => !Number.isFinite(value),
      ),
    ).toHaveLength(20);
    expect(screen.getByText('Synthetic data')).toBeVisible();
    expect(
      screen.getByRole('list', { name: 'Source signals' }),
    ).toHaveTextContent('Heater schedule');
    expect(fetch).not.toHaveBeenCalled();
    expect(flowsAPI.getDefinition).not.toHaveBeenCalled();
  });

  it('computes the heater differences through operator controls and retains the analysis across mode changes', () => {
    mount();
    expect(useFlow.getState().source).not.toBeNull();
    addDifference();

    const state = useFlow.getState();
    expect(state.nodes).toHaveLength(2);
    expect(state.nodes[1].op).toBe('diff');
    const result = evaluate(state.source, state.nodes, state.selectedId!);
    const heater = result!.series.find(
      (series) => series.id === 'thermal-heater',
    )!;
    expect(heater.y).toHaveLength(512);
    expect(Array.from(heater.y).filter((value) => value !== 0)).toEqual([
      40, 40, -60, -20, 40, 60, -40,
    ]);
    expect(
      [64, 128, 192, 256, 320, 384, 448].map((index) => heater.y[index]),
    ).toEqual([40, 40, -60, -20, 40, 60, -40]);

    fireEvent.click(screen.getByRole('button', { name: 'Data Worker' }));
    fireEvent.click(screen.getByRole('button', { name: 'Legacy' }));
    fireEvent.click(screen.getByRole('button', { name: 'Signal Engineer' }));
    expect(useFlow.getState().source).toBe(state.source);
    expect(useFlow.getState().nodes).toBe(state.nodes);
    expect(useFlow.getState().selectedId).toBe(state.selectedId);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('rejects an unknown demo without replacing existing analysis', () => {
    const state = existingWorkspace();
    mount('/workbench?mode=engineer&demo=unknown');
    expect(screen.getByRole('alert')).toHaveTextContent(
      /unknown|unsupported|invalid/i,
    );
    expect(useFlow.getState().source).toBe(state.source);
    expect(useFlow.getState().nodes).toBe(state.nodes);
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each(['flow=7', 'system=house', 'signal=power'])(
    'rejects a demo combined with %s before importing or replacing analysis',
    (query) => {
      const state = existingWorkspace();
      mount(`${demoPath}&${query}`);
      expect(screen.getByRole('alert')).toHaveTextContent(
        /demo.*(?:flow|system|signal)|(?:flow|system|signal).*demo/i,
      );
      expect(useFlow.getState().source).toBe(state.source);
      expect(useFlow.getState().nodes).toBe(state.nodes);
      expect(fetch).not.toHaveBeenCalled();
      expect(flowsAPI.getDefinition).not.toHaveBeenCalled();
    },
  );

  it('preserves a nonempty workspace when an explicit demo link is opened again', () => {
    const state = existingWorkspace();
    mount();
    expect(screen.getByRole('alert')).toHaveTextContent(
      /existing|working|workspace/i,
    );
    expect(useFlow.getState().source).toBe(state.source);
    expect(useFlow.getState().nodes).toBe(state.nodes);
    expect(useFlow.getState().selectedId).toBe(state.selectedId);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('keeps the ordinary workbench empty until a source is chosen', () => {
    mount('/workbench?mode=engineer');
    expect(useFlow.getState().source).toBeNull();
    expect(useFlow.getState().nodes).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
  });
});
