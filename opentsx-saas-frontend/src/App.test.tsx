import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('./pages/Login', () => ({ default: () => <p>login page</p> }));
vi.mock('./pages/Register', () => ({ default: () => <p>register page</p> }));
vi.mock('./pages/Dashboard', () => ({ default: () => <p>flow composer page</p> }));
vi.mock('./pages/FlowBuilder', () => ({ default: () => <p>flow builder page</p> }));
vi.mock('./pages/Workbench', () => ({ default: () => <p>workbench page</p> }));
vi.mock('./pages/Systems', () => ({ default: () => <p>systems page</p> }));
import App from './App';
import { useAuthStore } from './store/authStore';

const signedIn = () =>
  useAuthStore.setState({
    isAuthenticated: true,
    isLoading: false,
    error: null,
    user: { id: 1, email: 'a@example.org', full_name: 'A', is_verified: true, created_at: '2026-01-01T00:00:00Z' },
  });

afterEach(() => {
  cleanup();
  useAuthStore.setState({ isAuthenticated: false, user: null, error: null });
  window.history.pushState({}, '', '/');
});

describe('the routes of the application', () => {
  it('opens the flow builder at /flow-builder', () => {
    signedIn();
    window.history.pushState({}, '', '/flow-builder');
    render(<App />);
    expect(screen.getByText('flow builder page')).toBeInTheDocument();
  });

  it('has no route for a stored flow in the flow builder', () => {
    signedIn();
    window.history.pushState({}, '', '/flow-builder/7');
    render(<App />);
    expect(screen.queryByText('flow builder page')).not.toBeInTheDocument();
    expect(screen.getByText('flow composer page')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
  });
});
