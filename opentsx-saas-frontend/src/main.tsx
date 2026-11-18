/**
 * React application entry point
 */

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { errorMessage } from './services/api';
import './index.css';

/** A page that fails to render shows why, instead of unmounting the whole application. */
class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { message: string | null }> {
  state: { message: string | null } = { message: null };

  static getDerivedStateFromError(error: unknown) {
    return { message: errorMessage(error) };
  }

  render() {
    if (this.state.message === null) return this.props.children;
    return (
      <div className="p-8" role="alert">
        <p className="text-sm text-red-800">This page failed: {this.state.message}</p>
        <button className="mt-4 px-4 py-2 border rounded-md" onClick={() => window.location.reload()}>
          Reload
        </button>
      </div>
    );
  }
}

// Mount React app
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>
);
