/**
 * Authentication state management with Zustand
 */

import axios from 'axios';
import { create } from 'zustand';
import { User, authAPI, LoginRequest, RegisterRequest } from '../services/api';

function reasonOf(error: unknown): string {
  const detail = axios.isAxiosError(error) ? error.response?.data?.detail : undefined;
  if (typeof detail === 'string') return detail;
  return error instanceof Error ? error.message : String(error);
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;

  // Actions
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: !!localStorage.getItem('access_token'),
  isLoading: false,
  error: null,

  login: async (data: LoginRequest) => {
    set({ isLoading: true, error: null });
    try {
      const response = await authAPI.login(data);
      set({
        user: response.user,
        isAuthenticated: true,
        isLoading: false,
      });
    } catch (error) {
      if (axios.isAxiosError(error) && error.response) {
        set({
          error: error.response.data?.detail || 'Login failed',
          isLoading: false,
        });
      } else {
        set({
          error: 'Login failed',
          isLoading: false,
        });
      }
      throw error;
    }
  },

  register: async (data: RegisterRequest) => {
    set({ isLoading: true, error: null });
    try {
      await authAPI.register(data);
      // Auto-login after registration
      await authAPI.login({ email: data.email, password: data.password });
      const user = await authAPI.getCurrentUser();
      set({
        user,
        isAuthenticated: true,
        isLoading: false,
      });
    } catch (error) {
      if (axios.isAxiosError(error) && error.response) {
        set({
          error: error.response.data?.detail || 'Registration failed',
          isLoading: false,
        });
      } else {
        set({
          error: 'Registration failed',
          isLoading: false
        });
      }
      throw error;
    }
  },

  logout: async () => {
    try {
      await authAPI.logout();
      set({ user: null, isAuthenticated: false, error: null });
    } catch (error) {
      set({
        user: null,
        isAuthenticated: false,
        error: `signed out here; the server could not be told: ${reasonOf(error)}`,
      });
    }
  },

  fetchCurrentUser: async () => {
    if (!localStorage.getItem('access_token')) {
      return;
    }

    set({ isLoading: true });
    try {
      const user = await authAPI.getCurrentUser();
      set({
        user,
        isAuthenticated: true,
        isLoading: false,
        error: null,
      });
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 401) {
        set({ user: null, isAuthenticated: false, isLoading: false });
      } else {
        set({ isLoading: false, error: `your account could not be read (${reasonOf(error)}); reload the page to try again` });
      }
    }
  },

  clearError: () => set({ error: null }),
}));
