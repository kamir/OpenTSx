import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../workbench/FlowComposer', () => ({
  default: ({ onOpen }: { onOpen: (ids: number[]) => void }) => <button onClick={() => onOpen([3, 4, 5])}>Open in the editor</button>,
}));
import Dashboard from './Dashboard';
import { useAuthStore } from '../store/authStore';
import { useFlow } from '../workbench/flow';

afterEach(() => {
  vi.restoreAllMocks();
  useAuthStore.setState({ isAuthenticated: false, user: null, error: null });
  useFlow.getState().clear();
});

function show() {
  useAuthStore.setState({ isAuthenticated: true, user: null, error: null });
  render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/workbench" element={<p>workbench page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('the flow composer page', () => {
  it('opens the first flow in the workbench when the browser opened the others', async () => {
    vi.spyOn(window, 'open').mockReturnValue({} as Window);
    show();
    fireEvent.click(screen.getByText('Open in the editor'));
    expect(await screen.findByText('workbench page')).toBeInTheDocument();
  });

  it('says how many flows the browser kept from opening in tabs of their own', async () => {
    vi.spyOn(window, 'open').mockReturnValueOnce(null).mockReturnValueOnce({} as Window);
    useFlow.getState().setSavedId(3);
    show();
    fireEvent.click(screen.getByText('Open in the editor'));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The browser kept 1 of the flows from opening in tabs of their own, so this page stayed and did not open the first one either. Allow pop-ups for this page, or open them one at a time.'
    );
    expect(screen.queryByText('workbench page')).toBeNull();
    expect(useFlow.getState().savedId).toBe(3);
  });

  it('says why the account could not be read', async () => {
    const error = 'your account could not be read (database unavailable); reload the page to try again';
    useAuthStore.setState({ isAuthenticated: true, user: null, error });
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(error);
  });
});
