import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

const sent: unknown[] = [];
vi.mock('../services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  systemsAPI: { bind: vi.fn(async (_system: string, body: unknown) => { sent.push(body); }) },
}));
import SourcePanel from './SourcePanel';

const signal = {
  id: 'demand.kg_h', atom: 'demand', group: '', name: 'kg_h', title: 'Demand', port: true,
  expect: { unit: 'kg/h', min: 0, max: 120, interval: 'PT1M' }, grades: {}, via: 'open', state: 'open', next: [], usedBy: [],
};
const status = {
  schema: 's', system: 'h2-chain', title: 'H2', version: 1, domain: 'h2', example: true, hash: 'h',
  problems: [], notes: [], counts: {}, states: {}, signals: [signal],
};

function simulate() {
  render(<SourcePanel mode="simulate" status={status as never} signal={signal as never} base="http://r" onChanged={() => {}} onClose={() => {}} />);
}

function type(label: string, ...keys: string[]) {
  for (const value of keys) fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function params() {
  return (sent[0] as { source: { params: unknown } }).source.params;
}

describe('the Simulate form', () => {
  beforeEach(() => {
    sent.length = 0;
    localStorage.setItem('access_token', 'x');
  });

  it('binds numbers after a field was cleared and typed key by key', async () => {
    simulate();
    type('reversion', '', '0', '0.', '0.2');
    type('mean', '', '6', '60');
    fireEvent.click(screen.getByText('Bind to this generator'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(params()).toEqual({ mean: 60, sigma: 4, reversion: 0.2 });
  });

  it('leaves an emptied field out so the server default applies', async () => {
    simulate();
    type('reversion', '');
    fireEvent.click(screen.getByText('Bind to this generator'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(params()).toEqual({ mean: 60, sigma: 4 });
  });

  it('refuses text that is no number before binding', async () => {
    simulate();
    type('sigma', '4,5');
    fireEvent.click(screen.getByText('Bind to this generator'));
    await screen.findByText(/sigma: "4,5" is not a number; expected a number such as 0.1/);
    expect(sent.length).toBe(0);
  });
});

describe('the Bind form', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('gives the reason the TS inventory could not be read', async () => {
    localStorage.setItem('access_token', 'x');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'internal error', type: 'internal' }), { status: 500 })));
    render(<SourcePanel mode="bind" status={status as never} signal={signal as never} base="http://r" onChanged={() => {}} onClose={() => {}} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('The TS inventory could not be read: internal error.');
  });
});

