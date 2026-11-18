import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const mode = vi.fn();
vi.mock('../services/api', () => ({ authAPI: { mode: () => mode() } }));
import Login from './Login';

function show() {
  render(
    <MemoryRouter>
      <Login />
    </MemoryRouter>
  );
}

describe('the login page', () => {
  it('names no administrator password: the backend has no default one', async () => {
    mode.mockResolvedValue({ testLogin: false });
    show();
    await screen.findByText('Sign in');
    expect(import.meta.env.DEV).toBe(true);
    expect(screen.queryByText(/admin123/)).toBeNull();
    expect(screen.queryByText(/admin@opentsx\.com/)).toBeNull();
  });

  it('shows the test operation hint when the backend accepts admin/admin', async () => {
    mode.mockResolvedValue({ testLogin: true });
    show();
    expect(await screen.findByText(/sign in with the name admin and the password admin/)).toBeInTheDocument();
  });
});
