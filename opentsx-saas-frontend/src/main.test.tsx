import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, screen } from '@testing-library/react';

vi.mock('./App', () => ({
  default: function App(): never {
    throw new Error('Cannot read properties of undefined (reading "signals")');
  },
}));

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('the application root', () => {
  it('shows a page that failed to render instead of unmounting', async () => {
    // React reports the caught render error on the console; the page shows it
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    document.body.innerHTML = '<div id="root"></div>';
    await act(async () => {
      await import('./main');
    });
    expect(screen.getByRole('alert')).toHaveTextContent('This page failed: Cannot read properties of undefined (reading "signals")');
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
  });
});
