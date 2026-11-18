import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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

import { authAPI, errorMessage, fetchJson, systemsAPI } from './api';

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

async function failure(call: () => Promise<unknown>): Promise<unknown> {
  return call().then(
    () => {
      throw new Error('the request did not fail');
    },
    (e) => e
  );
}

const register = () => authAPI.register({ email: 'ops@localhost', password: 'password1', full_name: 'Ops' });
const at = (path: string) => `${backend.seen[0].baseURL}${path}`;
const NGINX_502 = '<html>\r\n<head><title>502 Bad Gateway</title></head>\r\n<body>\r\n<center><h1>502 Bad Gateway</h1></center>\r\n</body>\r\n</html>\r\n';

beforeEach(() => {
  backend.seen.length = 0;
  // The 401 handler of the API client stays on /workbench; on other pages it navigates, which jsdom cannot.
  window.history.replaceState(null, '', '/workbench');
});

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe('errorMessage', () => {
  it('gives a string detail of the backend as it is', async () => {
    reply(400, { detail: 'Email already registered' });
    expect(errorMessage(await failure(register))).toBe('Email already registered');
  });

  it('gives a list detail of the backend as field and message', async () => {
    reply(422, {
      detail: [
        { type: 'value_error', loc: ['body', 'email'], msg: 'value is not a valid email address: The part after the @-sign is not valid.', input: 'ops@localhost' },
        { type: 'string_too_short', loc: ['body', 'password'], msg: 'String should have at least 8 characters' },
      ],
    });
    expect(errorMessage(await failure(register))).toBe(
      'email: value is not a valid email address: The part after the @-sign is not valid.; password: String should have at least 8 characters'
    );
  });

  it('gives the error of the retrieval server, the event bridge and nginx', async () => {
    reply(400, { error: 'from: "yesterday" is not an instant; expected an ISO-8601 instant such as 2026-01-01T00:00:00Z', type: 'bad_request' });
    expect(errorMessage(await failure(register))).toBe('from: "yesterday" is not an instant; expected an ISO-8601 instant such as 2026-01-01T00:00:00Z');
  });

  it('gives the error nginx answers when the backend is down', async () => {
    reply(502, { error: 'the backend is not reachable', type: 'unavailable' });
    expect(errorMessage(await failure(register))).toBe('the backend is not reachable');
  });

  it('names status and address for an answer without a reason', async () => {
    reply(502, NGINX_502);
    expect(errorMessage(await failure(register))).toBe(`HTTP 502 from ${at('/auth/register')}`);
  });

  it('names the address when no answer came', async () => {
    unreachable();
    expect(errorMessage(await failure(register))).toBe(`the backend at ${at('/auth/register')} cannot be reached`);
  });

  it('says the session expired on a 401 outside the sign-in', async () => {
    reply(401, { detail: 'Could not validate credentials' });
    expect(errorMessage(await failure(() => systemsAPI.bindings('h2-chain')))).toBe('your session has expired, sign in again');
  });

  it('keeps the reason of a refused sign-in', async () => {
    reply(401, { detail: 'Incorrect email or password' });
    expect(errorMessage(await failure(() => authAPI.login({ email: 'a@example.com', password: 'wrong' })))).toBe('Incorrect email or password');
  });

  it('gives the message of a plain Error and the text of anything else', () => {
    expect(errorMessage(new Error('the flow could not be saved to your account'))).toBe('the flow could not be saved to your account');
    expect(errorMessage('stopped')).toBe('stopped');
  });
});

describe('fetchJson', () => {
  const url = '/retrieval/v1/episodes?from=yesterday';

  it('gives the answer of an ok request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"episodes":[]}', { status: 200 })));
    await expect(fetchJson(url)).resolves.toEqual({ episodes: [] });
  });

  it('throws the error of the retrieval server', async () => {
    const error = 'from: "yesterday" is not an instant; expected an ISO-8601 instant such as 2026-01-01T00:00:00Z';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error, type: 'bad_request' }), { status: 400 })));
    await expect(fetchJson(url)).rejects.toThrow(new Error(error));
  });

  it('throws the error nginx answers when the retrieval server is down', async () => {
    const body = { error: 'the retrieval server is not reachable', type: 'unavailable' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 502 })));
    await expect(fetchJson(url)).rejects.toThrow(new Error('the retrieval server is not reachable'));
  });

  it('names status and address for an answer without a reason', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(NGINX_502, { status: 502, headers: { 'Content-Type': 'text/html' } })));
    await expect(fetchJson(url)).rejects.toThrow(new Error(`HTTP 502 from ${url}`));
  });

  it('names the address when no answer came', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(fetchJson(url)).rejects.toMatchObject({ message: `the backend at ${url} cannot be reached`, cause: new TypeError('Failed to fetch') });
  });

  it('sends the request it is given', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    const init = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"bindings":[]}' };
    await fetchJson('/retrieval/v1/systems/h2-chain/signals', init);
    expect(fetch).toHaveBeenCalledWith('/retrieval/v1/systems/h2-chain/signals', init);
  });
});

describe('systemsAPI', () => {
  it('encodes the system id in every path', async () => {
    reply(200, { bindings: [] });
    await systemsAPI.bindings('a/b');
    await systemsAPI.bind('a/b', { signal: 's', grade: 'raw', source: {} });
    await systemsAPI.unbind('a/b', 's');
    await systemsAPI.generate('a/b', 's', '2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z');
    expect(backend.seen.map((c) => c.url)).toEqual([
      '/systems/a%2Fb/bindings',
      '/systems/a%2Fb/bindings',
      '/systems/a%2Fb/bindings',
      '/systems/a%2Fb/generate',
    ]);
  });
});

describe('the nginx site of the frontend image', () => {
  const conf = readFileSync(resolve(process.cwd(), 'nginx.conf'), 'utf8');
  /** The body of each location, by its name or path; a block of the server ends at its indented closing brace. */
  const locations = new Map([...conf.matchAll(/location\s+(?:[=~]\s+)?(\S+)\s*\{\n([\s\S]*?)\n {4}\}/g)].map((m) => [m[1], m[2]]));

  it.each([
    ['/retrieval/', 502, 'the retrieval server is not reachable'],
    ['/retrieval/', 504, 'the retrieval server did not answer in time'],
    ['^/api/v1/(dags|systems)/', 502, 'the backend is not reachable'],
    ['^/api/v1/(dags|systems)/', 504, 'the backend did not answer in time'],
    ['/api/', 502, 'the backend is not reachable'],
    ['/api/', 504, 'the backend did not answer in time'],
  ])('answers %s with %i in the error body the helper reads', async (path, status, text) => {
    const page = new RegExp(`error_page\\s+${status}\\s+(@\\w+);`).exec(locations.get(path) ?? '')?.[1];
    expect(page, `error_page ${status} in location ${path}`).toBeDefined();
    const named = locations.get(page!) ?? '';
    expect(named).toContain('default_type application/json;');
    const body = new RegExp(`return\\s+${status}\\s+'([^']*)';`).exec(named)?.[1];
    expect(JSON.parse(body ?? 'null')).toEqual({ error: text, type: 'unavailable' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status })));
    await expect(fetchJson('/retrieval/v1/inventory')).rejects.toThrow(new Error(text));
    reply(status, JSON.parse(body!));
    expect(errorMessage(await failure(register))).toBe(text);
  });
});

