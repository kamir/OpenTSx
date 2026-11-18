/**
 * API client for OpenTSx backend
 */

import axios, { AxiosInstance, AxiosError } from 'axios';

// An empty VITE_API_BASE_URL means same origin: the nginx image and the dev server forward /api to the backend.
const API_URL = import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV ? 'http://localhost:8000' : '');
const API_V1 = `${API_URL}/api/v1`;

/** The retrieval server as the page reaches it: the nginx image and the dev server forward /retrieval. */
export const RETRIEVAL_BASE = '/retrieval';

// Create axios instance
const apiClient: AxiosInstance = axios.create({
  baseURL: API_V1,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor to add auth token
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('access_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// A 401 from the sign-in itself is a wrong password, not an expired session: the form reports it.
const signingIn = (url: string | undefined) => /\/auth\/(login|register)$/.test(url ?? '');

// Response interceptor to handle errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response?.status === 401 && !signingIn(error.config?.url)) {
      // Token expired, clear storage and redirect to login
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      // The workbench keeps its flow in memory: it reports the expired session instead of leaving the page.
      const path = window.location.pathname;
      if (!path.startsWith('/workbench') && path !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

/**
 * The reason in an error body: `error` of the retrieval server, the event bridge and nginx, `detail` of the backend
 * as a string or as the field list of a refused request. Null when the body carries none.
 */
export function bodyReason(data: unknown): string | null {
  if (typeof data !== 'object' || data === null) return null;
  const { error, detail } = data as { error?: unknown; detail?: unknown };
  if (typeof error === 'string') return error;
  if (typeof detail === 'string') return detail;
  if (!Array.isArray(detail)) return null;
  const fields = detail.flatMap((d) => {
    if (typeof d?.msg !== 'string') return [];
    const loc = Array.isArray(d.loc) ? d.loc : [];
    return [loc.length > 0 ? `${loc[loc.length - 1]}: ${d.msg}` : d.msg];
  });
  return fields.length > 0 ? fields.join('; ') : null;
}

/** What went wrong with a request or anything else, in the words of the server when it sent any. */
export function errorMessage(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const url = axios.getUri({ baseURL: e.config?.baseURL, url: e.config?.url });
    if (!e.response) return `the backend at ${url} cannot be reached`;
    if (e.response.status === 401 && !signingIn(e.config?.url)) return 'your session has expired, sign in again';
    return bodyReason(e.response.data) ?? `HTTP ${e.response.status} from ${url}`;
  }
  return e instanceof Error ? e.message : String(e);
}

/** fetch for JSON: a request without an answer or an answer that is not ok throws an Error with the reason. */
export async function fetchJson(url: string, init?: RequestInit): Promise<unknown> {
  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw Object.assign(new Error(`the backend at ${url} cannot be reached`), { cause: e });
  }
  if (!res.ok) throw new Error(bodyReason(await res.json().catch(() => null)) ?? `HTTP ${res.status} from ${url}`);
  return res.json();
}

// ==================== Types ====================

export interface User {
  id: number;
  email: string;
  full_name: string;
  is_verified: boolean;
  created_at: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  full_name: string;
}

export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  user: User;
}

export interface Flow {
  id: number;
  name: string;
  description: string;
  definition: FlowDefinition;
  status: string;
  created_at: string;
  updated_at: string;
}

export interface FlowDefinition {
  /** 'opentsx.workbench.v1' for flows saved from the workbench. */
  kind?: string;
  nodes: Node[];
  edges: Edge[];
}

export interface Node {
  id: string;
  type: string;
  label: string;
  position: { x: number; y: number };
  config: Record<string, unknown>;
}

export interface Edge {
  id: string;
  source: string;
  target: string;
}

// ==================== API Functions ====================

/**
 * Auth API
 */
export const authAPI = {
  /**
   * Whether the installation runs in test operation (sign in with admin/admin)
   */
  mode: async (): Promise<{ testLogin: boolean }> => {
    const response = await apiClient.get('/auth/mode');
    return response.data;
  },

  /**
   * Register new user
   */
  register: async (data: RegisterRequest): Promise<User> => {
    const response = await apiClient.post('/auth/register', data);
    return response.data;
  },

  /**
   * Login user
   */
  login: async (data: LoginRequest): Promise<LoginResponse> => {
    const response = await apiClient.post('/auth/login', data);
    const { access_token, refresh_token } = response.data;

    // Store tokens
    localStorage.setItem('access_token', access_token);
    localStorage.setItem('refresh_token', refresh_token);

    return response.data;
  },

  /**
   * Logout user
   */
  logout: async (): Promise<void> => {
    try {
      await apiClient.post('/auth/logout');
    } finally {
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
    }
  },

  /**
   * Get current user
   */
  getCurrentUser: async (): Promise<User> => {
    const response = await apiClient.get('/auth/me');
    return response.data;
  },
};

/**
 * Flows API
 */
/** One source of the working copy of a system document, as the backend keeps it per user. */
export interface SignalBindingDoc {
  signal: string;
  grade: string;
  source: Record<string, unknown>;
  meaningConfirmed?: boolean;
  baseHash?: string | null;
}

export const systemsAPI = {
  /** The working copy of the signed-in user for a system. */
  bindings: async (system: string): Promise<SignalBindingDoc[]> => {
    const response = await apiClient.get(`/systems/${encodeURIComponent(system)}/bindings`);
    return response.data.bindings;
  },

  /** Sets the source of one grade of one signal; an earlier one is replaced. */
  bind: async (system: string, binding: SignalBindingDoc): Promise<void> => {
    await apiClient.put(`/systems/${encodeURIComponent(system)}/bindings`, binding);
  },

  /** Takes a source out of the working copy; the system document applies again. */
  unbind: async (system: string, signal: string, grade = 'raw'): Promise<void> => {
    await apiClient.delete(`/systems/${encodeURIComponent(system)}/bindings`, { params: { signal, grade } });
  },

  /** Runs the demo generator a signal is bound to over a period. Administrators only. */
  generate: async (system: string, signal: string, from: string, to: string): Promise<{ samples: number; episodes: number; topic: string }> => {
    const response = await apiClient.post(`/systems/${encodeURIComponent(system)}/generate`, { signal, from, to });
    return response.data;
  },
};

export const dagsAPI = {
  /** Whether the signed-in user may deploy and stop DAGs on this installation, and if not, why. */
  capabilities: async (): Promise<{ canRun: boolean; reason: string | null }> => {
    const response = await apiClient.get('/dags/capabilities');
    return response.data;
  },

  /** Hands an opentsx.dag.v1 document to the vector processor. A DAG of the same name is replaced. */
  deploy: async (yaml: string): Promise<{ dag: string; state: string }> => {
    const response = await apiClient.post('/dags/deploy', yaml, { headers: { 'Content-Type': 'application/yaml' } });
    return response.data;
  },

  /** Stops the DAG of this name. */
  stop: async (name: string): Promise<{ dag: string; state: string }> => {
    const response = await apiClient.post('/dags/stop', null, { params: { name } });
    return response.data;
  },
};

export const flowsAPI = {
  /**
   * List all flows
   */
  list: async (): Promise<Flow[]> => {
    const response = await apiClient.get('/flows');
    return response.data.flows;
  },

  /**
   * Get flow by ID
   */
  get: async (id: number): Promise<Flow> => {
    const response = await apiClient.get(`/flows/${id}`);
    return response.data;
  },

  /**
   * Create new flow
   */
  create: async (data: Partial<Flow>): Promise<Flow> => {
    const response = await apiClient.post('/flows', data);
    return response.data.flow;
  },

  /**
   * Update flow
   */
  update: async (id: number, data: Partial<Flow>): Promise<Flow> => {
    const response = await apiClient.put(`/flows/${id}`, data);
    return response.data.flow;
  },

  /**
   * Create or update a flow with a free-form definition (used by the workbench)
   */
  save: async (
    id: number | null,
    data: { name?: string; description?: string; definition: Record<string, unknown> }
  ): Promise<{ id: number; name: string }> => {
    const response = id === null ? await apiClient.post('/flows', data) : await apiClient.put(`/flows/${id}`, data);
    return response.data.flow;
  },

  /**
   * Get the raw definition of a flow
   */
  getDefinition: async (id: number): Promise<{ id: number; name: string; definition: Record<string, unknown> }> => {
    const response = await apiClient.get(`/flows/${id}`);
    return response.data;
  },

  /**
   * Change name, description or status (draft, active, paused, archived) of a flow
   */
  patch: async (id: number, data: { name?: string; description?: string; status?: string }): Promise<void> => {
    await apiClient.put(`/flows/${id}`, data);
  },

  /**
   * Delete flow
   */
  delete: async (id: number): Promise<void> => {
    await apiClient.delete(`/flows/${id}`);
  },
};
