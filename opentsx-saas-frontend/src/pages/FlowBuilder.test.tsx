import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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
import { useAuthStore } from '../store/authStore';

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
});
