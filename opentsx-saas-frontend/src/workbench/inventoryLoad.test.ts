import { createElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import InventoryDialog from './InventoryDialog';
import { fetchBucket, fetchInventory } from './inventoryLoad';
import { Inventory, parseInventory } from './inventory';

afterEach(() => {
  vi.unstubAllGlobals();
});

const FROM = '2026-10-01T00:00:00Z';
const TO = '2026-10-01T06:00:00Z';
const INVENTORY = {
  schema: 'opentsx.ts-inventory.v1',
  source: { kind: 'demo', topics: { episodes: 'tsx.wind.episodes.v2' }, domain: 'wind' },
  totals: { series: 1, episodes: 36, samples: 21600, from: FROM, to: TO, scenarios: [] },
  series: [{ seriesId: 's1', metric: 'power_active', tags: { turbine: 'T01' }, unit: 'kW', episodes: 36, samples: 21600, from: FROM, to: TO, intervalMicros: 1_000_000, missing: 0 }],
};
const REFUSED = { error: 'selection: more than 20000 episodes match; narrow it with metric, tags, series, from or to', type: 'bad_request' };

describe('reading the TS inventory', () => {
  it('gives the reason the retrieval server sends', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'internal error', type: 'internal' }), { status: 500 })));
    await expect(fetchInventory('/retrieval')).rejects.toThrow(new Error('internal error'));
  });

  it('gives the reason the retrieval server refuses series', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(REFUSED), { status: 400 })));
    const inv: Inventory = parseInventory(INVENTORY);
    await expect(fetchBucket('/retrieval', inv, inv.series, { from: FROM, to: TO, resample: '', agg: 'MEAN' })).rejects.toThrow(new Error(REFUSED.error));
  });

  it('shows that reason in the dialog without a guess', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url === '/retrieval/v1/inventory' ? new Response(JSON.stringify(INVENTORY), { status: 200 }) : new Response(JSON.stringify(REFUSED), { status: 400 })
      )
    );
    render(createElement(InventoryDialog, { base: '/retrieval', onBase: () => {}, onLoad: () => {}, onClose: () => {} }));
    fireEvent.click(await screen.findByRole('checkbox', { name: 'power_active T01' }));
    fireEvent.click(screen.getByRole('button', { name: 'Load series' }));
    expect(await screen.findByText(`The series could not be loaded: ${REFUSED.error}.`)).toBeInTheDocument();
  });
});
