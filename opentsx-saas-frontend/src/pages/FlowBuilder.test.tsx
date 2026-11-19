import { afterEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('reactflow', () => ({
  default: () => null,
  Controls: () => null,
  Background: () => null,
  Panel: () => null,
  addEdge: vi.fn(),
  useNodesState: (nodes: unknown[]) => [nodes, vi.fn(), vi.fn()],
  useEdgesState: (edges: unknown[]) => [edges, vi.fn(), vi.fn()],
}));
import FlowBuilder from './FlowBuilder';
import { flowsAPI } from '../services/api';
import { useAuthStore } from '../store/authStore';

afterEach(() => {
  vi.restoreAllMocks();
  useAuthStore.setState({ isAuthenticated: false, user: null, error: null });
});

describe('the flow builder page', () => {
  it('says why the account could not be read', async () => {
    const error = 'your account could not be read (database unavailable); reload the page to try again';
    useAuthStore.setState({ isAuthenticated: true, user: null, error });
    render(
      <MemoryRouter>
        <FlowBuilder />
      </MemoryRouter>
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(error);
  });

  it('says why the flow could not be saved', async () => {
    const config = { url: '/flows', baseURL: '/api/v1', headers: {} } as InternalAxiosRequestConfig;
    const data = { error: 'name: String should have at most 200 characters', type: 'bad_request' };
    vi.spyOn(flowsAPI, 'create').mockRejectedValue(
      new AxiosError('Request failed with status code 422', AxiosError.ERR_BAD_REQUEST, config, null, { data, status: 422, statusText: '', headers: {}, config })
    );
    const alert = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(
      <MemoryRouter>
        <FlowBuilder />
      </MemoryRouter>
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save Flow' }));
    await waitFor(() => expect(alert).toHaveBeenCalledWith('The flow could not be saved: name: String should have at most 200 characters.'));
  });
});

