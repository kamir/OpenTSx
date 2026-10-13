/** Reads series of the TS inventory from a retrieval server and makes them the bucket of the workbench. */

import {
  Inventory,
  InventorySeries,
  LoadOptions,
  bucketName,
  clock,
  episodesQuery,
  marksOf,
  parseInventory,
  relabel,
  topicOf,
  wholeRange,
} from './inventory';
import { parseEpisodes } from './loaders';
import { Bucket } from './types';

export interface InventoryLoad {
  bucket: Bucket;
  /** Topic the series were read from, when the server reads a log. */
  topic?: string;
  domain?: string;
  /** Ids of the loaded series in the source topic. */
  seriesIds: string[];
  detail: string;
}

export async function fetchInventory(root: string): Promise<Inventory> {
  const res = await fetch(`${root}/v1/inventory`);
  if (!res.ok) throw new Error(res.status === 404 ? 'the server has no /v1/inventory (older retrieval server?)' : `HTTP ${res.status}`);
  return parseInventory(await res.json());
}

export async function fetchBucket(root: string, inv: Inventory, selection: InventorySeries[], options: LoadOptions): Promise<InventoryLoad> {
  const res = await fetch(`${root}/v1/episodes?${episodesQuery(selection.map((s) => s.seriesId), options)}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const name = bucketName(selection);
  const parsed = relabel(parseEpisodes(await res.json(), name), selection, name);
  const t0Ms = parsed.t0Ms ?? Date.parse(options.from);
  const topic = topicOf(inv, selection);
  return {
    bucket: {
      ...parsed,
      marks: marksOf(selection, t0Ms, (Date.parse(options.from) - t0Ms) / 1000, (Date.parse(options.to) - t0Ms) / 1000),
    },
    topic,
    domain: inv.source.domain,
    seriesIds: selection.map((s) => s.seriesId),
    detail: `${topic ?? inv.source.kind}, ${selection.length} series, ${clock(options.from, true)} to ${clock(options.to, true)} UTC${options.resample ? `, ${options.agg} per ${options.resample}` : ''}`,
  };
}

/**
 * The series a stored flow names, over their whole range. Series the server no longer holds are left out and
 * counted in `missing`.
 */
export async function fetchFlowSeries(root: string, seriesIds: string[]): Promise<{ load: InventoryLoad; missing: number }> {
  const inv = await fetchInventory(root);
  const selection = seriesIds.map((id) => inv.series.find((s) => s.seriesId === id)).filter((s): s is InventorySeries => !!s);
  if (selection.length === 0) throw new Error('the server holds none of its series');
  const options = wholeRange(selection);
  if (!options) throw new Error('its series hold no data');
  return { load: await fetchBucket(root, inv, selection, options), missing: seriesIds.length - selection.length };
}
