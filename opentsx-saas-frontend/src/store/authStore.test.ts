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
    reply(500, { error: 'internal error', type: 'internal' });
    await useAuthStore.getState().logout();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({
      isAuthenticated: false,
      error: 'signed out here; the server could not be told: internal error',
    });
  });

  it('removes both tokens and says so when the backend does not answer', async () => {
    unreachable();
    await useAuthStore.getState().logout();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('refresh_token')).toBeNull();
    expect(useAuthStore.getState().error).toBe(`signed out here; the server could not be told: the backend at ${backend.seen[0].baseURL}/auth/logout cannot be reached`);
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

describe('the sign-in error', () => {
  const login = () => useAuthStore.getState().login({ email: 'a@example.com', password: 'wrong' }).catch(() => undefined);
  const register = () => useAuthStore.getState().register({ email: 'ops@localhost', password: 'password1', full_name: 'Ops' }).catch(() => undefined);
  const at = (path: string) => `${backend.seen[0].baseURL}${path}`;

  it('is the reason the backend gives', async () => {
    reply(401, { error: 'email or password: not correct; check both and sign in again', type: 'unauthorized' });
    await login();
    expect(useAuthStore.getState().error).toBe('email or password: not correct; check both and sign in again');
  });

  it('is text when the backend refuses a field', async () => {
    reply(422, { error: 'email: value is not a valid email address: The part after the @-sign is not valid. It should have a period', type: 'bad_request' });
    await register();
    expect(useAuthStore.getState().error).toBe('email: value is not a valid email address: The part after the @-sign is not valid. It should have a period');
  });

  it('names status and address when a proxy answers with its own page', async () => {
    reply(502, '<html><body><center><h1>502 Bad Gateway</h1></center></body></html>');
    await login();
    expect(useAuthStore.getState().error).toBe(`HTTP 502 from ${at('/auth/login')}`);
  });

  it('names the address when the backend does not answer', async () => {
    unreachable();
    await register();
    expect(useAuthStore.getState().error).toBe(`the backend at ${at('/auth/register')} cannot be reached`);
  });
});

describe('fetchCurrentUser', () => {
  it('keeps the session and sets the error when /auth/me fails with 500', async () => {
    reply(500, { error: 'internal error', type: 'internal' });
    await useAuthStore.getState().fetchCurrentUser();
    expect(localStorage.getItem('access_token')).toBe('access');
    expect(useAuthStore.getState()).toMatchObject({
      isAuthenticated: true,
      isLoading: false,
      error: 'your account could not be read (internal error); reload the page to try again',
    });
  });

  it('signs out when /auth/me answers 401', async () => {
    reply(401, { error: 'token: not valid or expired; sign in again', type: 'unauthorized' });
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
    reply(500, { error: 'internal error', type: 'internal' });
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
