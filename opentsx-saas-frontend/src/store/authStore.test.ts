import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';

const backend = vi.hoisted(() => ({
  answer: (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => Promise.reject(new Error(`no answer for ${config.url}`)),
  seen: [] as InternalAxiosRequestConfig[],
}));

vi.mock('axios', async (importOriginal) => {
  const real = await importOriginal<typeof import('axios')>();
  real.default.defaults.adapter = (config) => {
    backend.seen.push(config);
    return backend.answer(config);
  };
  return { ...real };
});

import { useAuthStore } from './authStore';

function reply(status: number, data: unknown) {
  backend.answer = async (config) => {
    const response = { data, status, statusText: '', headers: {}, config };
    if (status < 400) return response;
    throw new AxiosError(`Request failed with status code ${status}`, AxiosError.ERR_BAD_RESPONSE, config, null, response);
  };
}

function unreachable() {
  backend.answer = async (config) => {
    throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config);
  };
}

function signedIn() {
  localStorage.setItem('access_token', 'access');
  localStorage.setItem('refresh_token', 'refresh');
  useAuthStore.setState({ user: null, isAuthenticated: true, isLoading: false, error: null });
}

const user = { id: 1, email: 'a@example.com', full_name: 'A', is_verified: true, created_at: '2026-01-01T00:00:00Z' };

beforeEach(() => {
  backend.seen.length = 0;
  // The 401 handler of the API client stays on /workbench; on other pages it navigates, which jsdom cannot.
  window.history.replaceState(null, '', '/workbench');
  signedIn();
});

afterEach(() => {
  localStorage.clear();
  vi.unstubAllEnvs();
});

describe('logout', () => {
  it('removes both tokens and says so when the backend answers with an error', async () => {
    reply(500, { detail: 'database unavailable' });
    await useAuthStore.getState().logout();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({
      isAuthenticated: false,
      error: 'signed out here; the server could not be told: database unavailable',
    });
  });

  it('removes both tokens and says so when the backend does not answer', async () => {
    unreachable();
    await useAuthStore.getState().logout();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(useAuthStore.getState().error).toBe('signed out here; the server could not be told: Network Error');
  });

  it('removes both tokens without an error when the backend confirms', async () => {
    reply(200, { message: 'Successfully logged out' });
    await useAuthStore.getState().logout();
    expect(backend.seen.map((c) => c.url)).toEqual(['/auth/logout']);
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({ isAuthenticated: false, error: null });
  });
});

describe('fetchCurrentUser', () => {
  it('keeps the session and sets the error when /auth/me fails with 500', async () => {
    reply(500, { detail: 'database unavailable' });
    await useAuthStore.getState().fetchCurrentUser();
    expect(localStorage.getItem('access_token')).toBe('access');
    expect(useAuthStore.getState()).toMatchObject({
      isAuthenticated: true,
      isLoading: false,
      error: 'your account could not be read (database unavailable); reload the page to try again',
    });
  });

  it('signs out when /auth/me answers 401', async () => {
    reply(401, { detail: 'Could not validate credentials' });
    await useAuthStore.getState().fetchCurrentUser();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({ user: null, isAuthenticated: false, isLoading: false });
  });

  it('keeps the user it read', async () => {
    reply(200, user);
    await useAuthStore.getState().fetchCurrentUser();
    expect(useAuthStore.getState()).toMatchObject({ user, isAuthenticated: true, isLoading: false, error: null });
  });

  it('drops the account error once /auth/me answers', async () => {
    reply(500, { detail: 'database unavailable' });
    await useAuthStore.getState().fetchCurrentUser();
    reply(200, user);
    await useAuthStore.getState().fetchCurrentUser();
    expect(useAuthStore.getState()).toMatchObject({ user, isAuthenticated: true, error: null });
  });
});

describe('the backend address of the session requests', () => {
  async function logoutBaseUrl(): Promise<string | undefined> {
    vi.resetModules();
    const { useAuthStore: store } = await import('./authStore');
    reply(200, {});
    await store.getState().logout();
    return backend.seen[0]?.baseURL;
  }

  it('is the page origin in a production build without VITE_API_BASE_URL', async () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('PROD', true);
    vi.stubEnv('MODE', 'production');
    vi.stubEnv('VITE_API_BASE_URL', undefined);
    expect(await logoutBaseUrl()).toBe('/api/v1');
  });

  it('is the page origin when VITE_API_BASE_URL is empty', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '');
    expect(await logoutBaseUrl()).toBe('/api/v1');
  });

  it('is http://localhost:8000 under npm run dev without VITE_API_BASE_URL', async () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_API_BASE_URL', undefined);
    expect(await logoutBaseUrl()).toBe('http://localhost:8000/api/v1');
  });
});
