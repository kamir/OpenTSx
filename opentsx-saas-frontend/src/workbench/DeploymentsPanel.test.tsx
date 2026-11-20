import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { dagsAPI } from '../services/api';
import { DagList } from './deployments';
import DeploymentsPanel from './DeploymentsPanel';
import { useFlow } from './flow';

const listed: DagList = {
  now: '2026-10-13T10:01:00Z',
  writes: true,
  dags: [
    {
      dag: 'a2-clean',
      desired: 'deployed',
      by: 'admin',
      at: '2026-10-13T10:00:00Z',
      sinks: [{ vector: 'clean', topic: 'tsx.wind.smart.clean.vectors.v1', state: 'running', records: 60, at: '2026-10-13T10:00:20Z' }],
    },
  ],
  jobs: [
    {
      dag: 'a2-clean', job: 'a2-clean', track: 'A', planVersion: 1, state: 'running', records: 3600, watermark: null,
      waitingFor: [], skippedBlocks: 0, at: '2026-10-13T10:00:30Z', topic: 'tsx.wind.episodes.v2',
    },
    {
      dag: 'edge-pass', job: 'edge-pass', track: 'B', planVersion: 2, state: 'running', records: 120, watermark: null,
      waitingFor: [], skippedBlocks: 0, at: '2026-10-13T09:58:00Z', site: 'site-1', topic: 'tsx.wind.episodes.v2',
    },
  ],
};

function serve(body: unknown) {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => body })));
}

function panel() {
  render(
    <MemoryRouter>
      <DeploymentsPanel base="http://retrieval.test" blocker={null} checks={[]} onSave={vi.fn(async () => null)} />
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
  localStorage.setItem('access_token', 'fixture');
  useFlow.getState().clear();
  vi.spyOn(dagsAPI, 'capabilities').mockResolvedValue({ canRun: true, reason: null });
});

it('lists Track A and B jobs apart from the deployments and offers no Stop for them', async () => {
  serve(listed);
  panel();
  const jobs = await screen.findByRole('list', { name: 'Track A and B jobs' });
  const [trackA, trackB] = within(jobs).getAllByRole('listitem');
  expect(within(jobs).getAllByRole('listitem')).toHaveLength(2);
  expect(trackA).toHaveTextContent('a2-clean');
  expect(trackA).toHaveTextContent('Track A, reported 30 s ago');
  expect(trackA).toHaveTextContent('running: 3,600 input records read');
  expect(trackB).toHaveTextContent('edge-pass');
  expect(trackB).toHaveTextContent('Track B, site site-1, reported 3 min ago');
  expect(trackB).toHaveTextContent('This job has not reported for two minutes. Is it still running?');
  expect(trackB).toHaveTextContent('running: 120 input records read');
  expect(trackA).not.toHaveTextContent('has not reported');
  expect(within(jobs).queryAllByRole('button')).toHaveLength(0);

  const deployed = screen.getByRole('list', { name: 'Deployed flows' });
  expect(within(deployed).getAllByRole('listitem')).toHaveLength(1);
  expect(within(deployed).getAllByRole('button', { name: 'Stop' })).toHaveLength(1);
  expect(deployed).toHaveTextContent('tsx.wind.smart.clean.vectors.v1 running: 60 measuring points written');
  expect(deployed).not.toHaveTextContent('input records');
  expect(deployed).not.toHaveTextContent('tsx.wind.episodes.v2');
});

it('says when the retrieval server does not list jobs', async () => {
  serve({ now: listed.now, writes: listed.writes, dags: listed.dags });
  panel();
  await screen.findByRole('list', { name: 'Deployed flows' });
  expect(screen.getByText('This retrieval server does not list Track A and B jobs.')).toBeInTheDocument();
  expect(screen.queryByRole('list', { name: 'Track A and B jobs' })).toBeNull();
});
