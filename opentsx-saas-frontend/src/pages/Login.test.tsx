import { describe, expect, it, vi } from 'vitest';
import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const mode = vi.fn();
vi.mock('../services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../services/api')>()),
  authAPI: { mode: () => mode() },
}));
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

  it('says why the sign-in mode could not be read', async () => {
    mode.mockRejectedValue(new Error('Network Error'));
    show();
    expect(
      await screen.findByText('the sign-in mode could not be read from the backend (Network Error); reload the page to try again')
    ).toBeInTheDocument();
  });

  it('gives the reason the server sends', async () => {
    const config = { url: '/auth/mode', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
    const data = { error: 'the backend is not reachable', type: 'unavailable' };
    mode.mockRejectedValue(new AxiosError('Request failed with status code 502', AxiosError.ERR_BAD_RESPONSE, config, null, { data, status: 502, statusText: '', headers: {}, config }));
    show();
    expect(
      await screen.findByText('the sign-in mode could not be read from the backend (the backend is not reachable); reload the page to try again')
    ).toBeInTheDocument();
  });
});
