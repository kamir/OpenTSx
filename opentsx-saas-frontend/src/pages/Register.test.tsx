import { describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const backend = vi.hoisted(() => ({
  answer: (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => Promise.reject(new Error(`no answer for ${config.url}`)),
}));

vi.mock('axios', async (importOriginal) => {
  const real = await importOriginal<typeof import('axios')>();
  real.default.defaults.adapter = (config) => backend.answer(config);
  return { ...real };
});

import Register from './Register';

describe('the register page', () => {
  it('shows the fields the backend refuses', async () => {
    backend.answer = async (config) => {
      const data = { error: 'email: value is not a valid email address: The part after the @-sign is not valid. It should have a period', type: 'bad_request' };
      throw new AxiosError('Request failed with status code 422', AxiosError.ERR_BAD_REQUEST, config, null, { data, status: 422, statusText: '', headers: {}, config });
    };
    render(
      <MemoryRouter>
        <Register />
      </MemoryRouter>
    );
    fireEvent.change(screen.getByLabelText('Full Name'), { target: { value: 'Ops' } });
    fireEvent.change(screen.getByLabelText('Email address'), { target: { value: 'ops@localhost' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign up' }));
    expect(await screen.findByText('email: value is not a valid email address: The part after the @-sign is not valid. It should have a period')).toBeInTheDocument();
  });
});
