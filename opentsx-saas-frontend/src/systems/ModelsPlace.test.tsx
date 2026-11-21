import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import ModelsPlace from './ModelsPlace';
import { DecisionStatus } from './signals';

const limited: DecisionStatus = {
  schema: 'opentsx.decision-status.v1',
  system: 'h2-chain',
  decisions: [
    {
      id: 'h2-dispatch',
      title: 'Electrolyser dispatch',
      needs: [{ signal: 'park.p_potential_kw', ok: true, containsSynthetic: false, reasons: [] }],
      analyses: [
        {
          id: 'hold',
          title: 'Hold the setpoint',
          status: 'restricted',
          reasons: [
            'park.p_potential_kw: the share of questionable values is not evaluated by this server',
            'the width of the uncertainty is not evaluated by this server',
          ],
          validity: [],
          evidence: [{ what: 'Compared with the setpoints of the operator', result: 'same setpoint' }],
        },
      ],
      alternatives: [],
    },
  ],
};

describe('ModelsPlace', () => {
  it('names a limit the server does not evaluate as a cause of restricted', () => {
    render(
      <MemoryRouter>
        <ModelsPlace status={null} decisions={limited} />
      </MemoryRouter>,
    );
    expect(screen.getByText('Hold the setpoint').closest('li')).toHaveTextContent('restricted');
    expect(screen.getByText(/Restricted: it runs/)).toHaveTextContent(
      'or its card states a limit the server does not evaluate.',
    );
  });
});
