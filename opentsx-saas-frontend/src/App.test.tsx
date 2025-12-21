import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

describe('App', () => {
    it('should pass a basic sanity check', () => {
        render(<div data-testid="test">test</div>);
        expect(screen.getByTestId('test')).toBeInTheDocument();
    });
});
