import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import Systems from './Systems';
import { useAuthStore } from '../store/authStore';

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe('the systems page', () => {
  it('says why the account could not be read', async () => {
    const error = 'your account could not be read (database unavailable); reload the page to try again';
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)));
    localStorage.setItem('access_token', 'access');
    useAuthStore.setState({ isAuthenticated: true, user: null, error });
    render(
      <MemoryRouter initialEntries={['/systems']}>
        <Systems />
      </MemoryRouter>
    );
    expect(await screen.findByText(error)).toHaveAttribute('role', 'alert');
  });
});
