/**
 * Authentication state management with Zustand
 */

import axios from 'axios';
import { create } from 'zustand';
import { User, authAPI, errorMessage, hasAccessToken, LoginRequest, RegisterRequest } from '../services/api';

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
  isAuthenticated: hasAccessToken(),
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
      set({ error: errorMessage(error), isLoading: false });
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
      set({ error: errorMessage(error), isLoading: false });
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
        error: `signed out here; the server could not be told: ${errorMessage(error)}`,
      });
    }
  },

  fetchCurrentUser: async () => {
    if (!hasAccessToken()) {
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
        set({ isLoading: false, error: `your account could not be read (${errorMessage(error)}); reload the page to try again` });
      }
    }
  },

  clearError: () => set({ error: null }),
}));
