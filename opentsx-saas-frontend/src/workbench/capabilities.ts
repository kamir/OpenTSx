import { OP_BY_ID } from './ops';

export const PROCESSOR_SERIES_OPS = ['dq.plausible-range', 'convert.unit', 'resample', 'model.wind-shear', 'model.power-curve', 'feature.lag', 'feature.rolling'];

export interface OperatorCapability {
  browser: 'Available' | 'Unavailable';
  processor: 'Available' | 'Unavailable';
  wayang: 'Kernel only' | 'Not deployable';
  reason: string;
}

export function capabilityOf(opId: string): OperatorCapability {
  const browser = OP_BY_ID[opId] ? 'Available' : 'Unavailable';
  const processor = opId === 'align.vector' || PROCESSOR_SERIES_OPS.includes(opId) ? 'Available' : 'Unavailable';
  const wayang = opId === 'pair.xcorr' || opId === 'pair.es' ? 'Kernel only' : 'Not deployable';
  const reason = browser === 'Unavailable'
    ? 'This operator is not available in the workbench.'
    : processor === 'Available'
      ? 'The Java processor supports this operator. Deployment also requires a supported source, path and sink.'
      : opId === 'stats.window'
        ? 'Runs in the browser only. The runtime catalog parameters are not translated and no statistics DAG sink is available.'
        : wayang === 'Kernel only'
          ? 'Runs in the browser only. The Java numerical kernel has no Wayang operator mapping or DAG deployment.'
          : 'Runs in the browser only. No continuous processor deployment is available.';
  return { browser, processor, wayang, reason };
}
