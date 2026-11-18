import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../workbench/FlowComposer', () => ({ default: () => null }));
import Dashboard from './Dashboard';
import { useAuthStore } from '../store/authStore';

describe('the flow composer page', () => {
  it('says why the account could not be read', async () => {
    const error = 'your account could not be read (database unavailable); reload the page to try again';
    useAuthStore.setState({ isAuthenticated: true, user: null, error });
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(error);
  });
});
