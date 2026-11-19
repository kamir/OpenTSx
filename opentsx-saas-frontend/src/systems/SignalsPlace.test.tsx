import { afterEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import SignalsPlace from './SignalsPlace';
import { systemsAPI } from '../services/api';

afterEach(() => {
  vi.restoreAllMocks();
});

const signal = {
  id: 'demand.kg_h', atom: 'demand', group: '', name: 'kg_h', title: 'Demand', port: true,
  expect: { unit: 'kg/h', min: 0, max: 120, interval: 'PT1M' },
  grades: { raw: { source: { kind: 'topic', topic: 'tsx.h2.episodes.v2', workingCopy: true } } },
  via: 'topic', state: 'bound-no-data', next: [], usedBy: [],
};
const status = {
  schema: 'opentsx.signal-status.v1', system: 'h2-chain', title: 'H2', version: 1, domain: 'h2', example: true, hash: 'h',
  problems: [], notes: [], counts: { signals: 1 }, states: {}, signals: [signal],
};

describe('the place Signals', () => {
  it('gives the reason the backend refuses to remove a source', async () => {
    const config = { url: '/systems/h2-chain/bindings', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
    const data = { error: 'market.price_eur_mwh@raw: not in your working copy; nothing was removed', type: 'not_found' };
    vi.spyOn(systemsAPI, 'unbind').mockRejectedValue(
      new AxiosError('Request failed with status code 404', AxiosError.ERR_BAD_REQUEST, config, null, { data, status: 404, statusText: '', headers: {}, config })
    );
    render(
      <MemoryRouter>
        <SignalsPlace status={status as never} focusId="demand.kg_h" published={null} onReload={() => {}} base="/retrieval" bindings={[]} />
      </MemoryRouter>
    );
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('The source could not be removed: market.price_eur_mwh@raw: not in your working copy; nothing was removed.')).toBeInTheDocument();
  });
});
