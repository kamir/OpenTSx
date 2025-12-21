/**
 * API client for OpenTSx backend
 */

import axios, { AxiosInstance, AxiosError } from 'axios';

const API_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000';
const API_V1 = `${API_URL}/api/v1`;

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

// Response interceptor to handle errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response?.status === 401) {
      // Token expired, clear storage and redirect to login
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

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
    await apiClient.post('/auth/logout');
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
  },

  /**
   * Get current user
   */
  getCurrentUser: async (): Promise<User> => {
    const response = await apiClient.get('/auth/me');
    return response.data;
  },

  /**
   * Update current user
   */
  updateProfile: async (data: Partial<User>): Promise<User> => {
    const response = await apiClient.patch('/auth/me', data);
    return response.data;
  },
};

/**
 * Flows API
 */
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
   * Delete flow
   */
  delete: async (id: number): Promise<void> => {
    await apiClient.delete(`/flows/${id}`);
  },

  /**
   * Execute flow
   */
  execute: async (id: number): Promise<unknown> => {
    const response = await apiClient.post(`/flows/${id}/execute`);
    return response.data;
  },
};

/**
 * Organizations API
 */
export const organizationsAPI = {
  /**
   * List organizations
   */
  list: async () => {
    const response = await apiClient.get('/organizations');
    return response.data.organizations;
  },

  /**
   * Create organization
   */
  create: async (data: { name: string; slug: string; description?: string }) => {
    const response = await apiClient.post('/organizations', data);
    return response.data.organization;
  },
};

export default apiClient;
