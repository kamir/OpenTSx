/**
 * Oscilloscope view of a bucket: graticule of 10 x 8 divisions, time base and
 * vertical scale per division, cursors, trigger and measurements. It takes the
 * place of the MultiChart dialog of the Swing workbench.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { crossings, fmt, sampleInterval, stats } from './ops';
import { Bucket, Series } from './types';
import { ScopeBand, scopeBands, scopeChannelAtY, scopeDragDelta, scopeX, scopeY } from './scopeGeometry';
import './scope-instrument.css';

const CHANNEL_COLORS = ['#f2d21b', '#3fd0ee', '#ef74d8', '#63dd72', '#ff9d45', '#b9a2ff', '#ff7373', '#e9ece8'];
const HDIV = 10;
const VDIV = 8;

interface View {
  xPerDiv: number;
  xLeft: number;
  yPerDiv: number[];
  yCenter: number[];
  visible: boolean[];
  active: number;
  lockY: boolean;
  cursors: boolean;
  cx: [number, number];
  cy: [number, number];
  trigger: boolean;
  level: number;
  rising: boolean;
  /** Draw the ground-truth events of the bucket. */
  marks: boolean;
}

/** Next value of the 1-2-5 sequence above (dir > 0) or below (dir < 0) v. */
function step125(v: number, dir: number): number {
  if (!(v > 0)) return 1;
  const exp = Math.floor(Math.log10(v));
  const base = Math.pow(10, exp);
  const m = v / base;
  const seq = [1, 2, 5, 10];
  if (dir > 0) {
    const next = seq.find((s) => s > m * 1.0001) ?? 10;
    return next * base;
  }
  const prev = [...seq].reverse().find((s) => s < m * 0.9999);
  return prev ? prev * base : 0.5 * base;
}

function nice125(v: number): number {
  if (!(v > 0)) return 1;
  return step125(v * 0.9999, 1);
}

function extent(values: ArrayLike<number>): [number, number] {
  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (!Number.isFinite(v)) continue;
    if (v < min) min = v;
    if (v > max) max = v;
  }
  return min <= max ? [min, max] : [0, 1];
}

function autoset(b: Bucket, prev?: View, initialLock = true): View {
  let x0 = Infinity;
  let x1 = -Infinity;
  for (const s of b.series) {
    const [a, c] = extent(s.x);
    if (a < x0) x0 = a;
    if (c > x1) x1 = c;
  }
  if (!(x0 <= x1)) {
    x0 = 0;
    x1 = 1;
  }
  const lockY = prev?.lockY ?? initialLock;
  const visible = b.series.map((_, i) => prev?.visible[i] ?? true);
  let all: [number, number] = [Infinity, -Infinity];
  const each = b.series.map((s, i) => {
    const e = extent(s.y);
    if (visible[i]) all = [Math.min(all[0], e[0]), Math.max(all[1], e[1])];
    return e;
  });
  if (!(all[0] <= all[1])) all = [0, 1];
  const scale = (e: [number, number]) => nice125((e[1] - e[0] || Math.abs(e[1]) || 1) / (VDIV - 2));
  const span = x1 - x0 || 1;
  const active = Math.min(prev?.active ?? 0, Math.max(0, b.series.length - 1));
  const ref = lockY ? all : (each[active] ?? all);
  return {
    xPerDiv: span / HDIV,
    xLeft: x0,
    yPerDiv: each.map((e) => scale(lockY ? all : e)),
    yCenter: each.map((e) => (lockY ? (all[0] + all[1]) / 2 : (e[0] + e[1]) / 2)),
    visible,
    active,
    lockY,
    cursors: prev?.cursors ?? false,
    cx: [x0 + span * 0.3, x0 + span * 0.7],
    cy: [ref[0] + (ref[1] - ref[0]) * 0.25, ref[0] + (ref[1] - ref[0]) * 0.75],
    trigger: prev?.trigger ?? false,
    level: (ref[0] + ref[1]) / 2,
    rising: prev?.rising ?? true,
    marks: prev?.marks ?? true,
  };
}

/** First index with x[i] >= v. The x axis is ascending. */
function lowerBound(x: Float64Array, v: number): number {
  let lo = 0;
  let hi = x.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (x[mid] < v) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

function span(v: number, isTime: boolean): string {
  if (!isTime) return fmt(v);
  const a = Math.abs(v);
  if (a === 0) return '0 s';
  if (a < 1e-6) return `${fmt(v * 1e9)} ns`;
  if (a < 1e-3) return `${fmt(v * 1e6)} us`;
  if (a < 1) return `${fmt(v * 1e3)} ms`;
  if (a < 120) return `${fmt(v)} s`;
  if (a < 7200) return `${fmt(v / 60)} min`;
  if (a < 172800) return `${fmt(v / 3600)} h`;
  return `${fmt(v / 86400)} d`;
}

interface Measure {
  n: number;
  min: number;
  max: number;
  mean: number;
  rms: number;
  freq: number;
}

function measure(s: Series | undefined, from: number, to: number): Measure | null {
  if (!s) return null;
  const a = lowerBound(s.x, from);
  const b = lowerBound(s.x, to);
  if (b - a < 1) return null;
  const y = s.y.subarray(a, b);
  const st = stats(y);
  let sq = 0;
  for (let i = 0; i < y.length; i++) if (Number.isFinite(y[i])) sq += y[i] * y[i];
  const up = crossings(y, st.mean, true);
  const freq = up.length >= 2 ? (up.length - 1) / (s.x[a + up[up.length - 1]] - s.x[a + up[0]]) : NaN;
  return { n: st.n, min: st.min, max: st.max, mean: st.mean, rms: st.n ? Math.sqrt(sq / st.n) : NaN, freq };
}

interface ScopeProps {
  bucket: Bucket;
  showLabels: boolean;
  instrument?: boolean;
  /** Called when the trigger settings are taken over as a segmenter node. */
  onUseTrigger: (level: number, rising: boolean) => void;
  /** The range the signal on the screen should stay in; drawn as two lines on the scale of the active channel. */
  expected?: { min?: number; max?: number; unit?: string };
}

export default function Scope({ bucket, showLabels, onUseTrigger, expected, instrument = false }: ScopeProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 800, h: 420 });
  const [view, setView] = useState<View>(() => autoset(bucket, undefined, !instrument));
  const [running, setRunning] = useState(false);
  const viewRef = useRef(view);
  viewRef.current = view;
  const isTime = bucket.xKind === 'time';
  // Ground truth belongs to the time axis; a spectrum or a histogram has none.
  const marks = useMemo(() => (isTime ? (bucket.marks ?? []) : []), [bucket, isTime]);
  // Clock time (UTC) where the data has absolute time, otherwise the offset on the x axis.
  const when = useCallback((x: number) =>
    bucket.t0Ms === undefined ? span(x, true) : `${new Date(bucket.t0Ms + x * 1000).toISOString().slice(11, 19)} UTC`, [bucket.t0Ms]);
  const channelInfo = useMemo(() => bucket.series.map((series) => ({
    interval: sampleInterval(series.x),
    missing: series.y.some((value) => !Number.isFinite(value)),
    flagged: series.quality?.some((quality, index) => quality !== 0 && series.qualityKnown?.[index] !== 0) ?? false,
    unknownQuality: !series.quality || (series.qualityKnown?.some((known) => known === 0) ?? false),
  })), [bucket]);

  // A new bucket gets a fresh auto set; channel visibility is kept by position.
  useEffect(() => {
    setView((v) => autoset(bucket, v));
    setRunning(false);
  }, [bucket]);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSize({ w: Math.max(200, el.clientWidth), h: Math.max(160, el.clientHeight) }));
    ro.observe(el);
    setSize({ w: Math.max(200, el.clientWidth), h: Math.max(160, el.clientHeight) });
    return () => ro.disconnect();
  }, []);

  const record = useMemo(() => {
    let a = Infinity;
    let b = -Infinity;
    for (const s of bucket.series) {
      const e = extent(s.x);
      a = Math.min(a, e[0]);
      b = Math.max(b, e[1]);
    }
    return a <= b ? { from: a, to: b } : { from: 0, to: 1 };
  }, [bucket]);

  // Run: sweep through the record, one screen in four seconds.
  useEffect(() => {
    if (!running) return;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = (now - last) / 1000;
      last = now;
      setView((v) => {
        const width = v.xPerDiv * HDIV;
        let xLeft = v.xLeft + (width * dt) / 4;
        if (xLeft > record.to - width * 0.1) xLeft = record.from;
        return { ...v, xLeft };
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [running, record]);

  const active = bucket.series[view.active];
  const episodeRail = useMemo(() => {
    if (!active) return [];
    return [...(bucket.episodes ?? [])]
      .filter((episode) => episode.seriesId === active.id)
      .sort((a, b) => a.start - b.start)
      .map((episode) => {
        const first = lowerBound(active.x, episode.start);
        const last = lowerBound(active.x, episode.end);
        let missing = false;
        let flagged = false;
        let unknown = !active.quality;
        for (let index = first; index < last; index++) {
          if (!Number.isFinite(active.y[index])) missing = true;
          if (active.quality && active.quality[index] !== 0 && active.qualityKnown?.[index] !== 0) flagged = true;
          if (active.qualityKnown?.[index] === 0) unknown = true;
        }
        const invalid = !Number.isFinite(episode.start) || !Number.isFinite(episode.end) || episode.end <= episode.start;
        return {
          episode,
          invalid,
          quality: invalid ? 'Invalid range' : missing ? 'Missing samples' : flagged ? 'Flagged samples' : unknown ? 'Quality not declared' : 'Unflagged',
        };
      });
  }, [active, bucket.episodes]);
  const xRight = view.xLeft + view.xPerDiv * HDIV;
  const m = useMemo(() => measure(active, view.xLeft, xRight), [active, view.xLeft, xRight]);

  // ---------------------------------------------------------------- drawing

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const { w, h } = size;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    const g = canvas.getContext('2d');
    if (!g) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (instrument) {
      g.fillStyle = '#101820';
      g.fillRect(0, 0, w, h);
      const bands = scopeBands(w, h, view.visible, true);
      g.font = '11px ui-monospace, Menlo, Consolas, monospace';
      for (const band of bands) {
        const c = band.channel;
        const s = bucket.series[c];
        if (!s) continue;
        const color = ['#25d9e5', '#b693f7', '#ebc46b', '#71d7ad'][c % 4];
        const X = (value: number) => scopeX(value, view.xLeft, view.xPerDiv, band);
        const Y = (value: number) => scopeY(value, view.yCenter[c], view.yPerDiv[c], band);
        const right = band.left + band.width;
        const bottom = band.top + band.height;
        g.strokeStyle = '#293641';
        g.lineWidth = 1;
        g.beginPath();
        for (let column = 0; column <= HDIV; column++) {
          const x = band.left + (column * band.width) / HDIV;
          g.moveTo(x, band.top);
          g.lineTo(x, bottom);
        }
        for (let row = 0; row <= VDIV; row += 2) {
          const y = band.top + (row * band.height) / VDIV;
          g.moveTo(band.left, y);
          g.lineTo(right, y);
        }
        g.stroke();
        g.fillStyle = color;
        g.textAlign = 'left';
        g.textBaseline = 'top';
        if (showLabels) g.fillText(`CH${c + 1} ${s.label} [${s.unit ?? 'unit not declared'}]`, band.left, band.top - 18);
        const info = channelInfo[c];
        if (info.missing || info.unknownQuality || info.flagged) {
          g.fillStyle = info.missing || info.flagged ? '#ebc46b' : '#b8c7d2';
          g.textAlign = 'right';
          g.fillText(info.missing ? 'Missing samples' : info.flagged ? 'Flagged samples present' : 'Quality not fully declared', right, band.top - 18);
        }
        g.save();
        g.beginPath();
        g.rect(band.left, band.top, band.width, band.height);
        g.clip();
        if (view.marks) {
          for (const mark of marks) {
            const first = Math.max(band.left, X(mark.from));
            const last = Math.min(right, X(mark.to));
            if (last <= first) continue;
            g.fillStyle = mark.kind === 'sensor_fault' ? '#9e552b33' : '#e8d66b18';
            g.fillRect(first, band.top, last - first, band.height);
          }
        }
        const first = Math.max(0, lowerBound(s.x, view.xLeft) - 1);
        const last = Math.min(s.x.length, lowerBound(s.x, xRight) + 1);
        if (info.missing || info.flagged) {
          g.fillStyle = '#ebc46b24';
          for (let index = first; index < last; index++) {
            if (Number.isFinite(s.y[index]) && (!s.quality || s.quality[index] === 0 || s.qualityKnown?.[index] === 0)) continue;
            const x = X(s.x[index] - info.interval / 2);
            const width = Math.max(1, X(s.x[index] + info.interval / 2) - x);
            g.fillRect(x, band.top, width, band.height);
          }
        }
        g.strokeStyle = color;
        g.lineWidth = c === view.active ? 1.7 : 1.3;
        g.beginPath();
        if (last - first > band.width * 3) {
          let index = first;
          for (let pixel = 0; pixel < band.width && index < last; pixel++) {
            const limit = view.xLeft + ((pixel + 1) / band.width) * view.xPerDiv * HDIV;
            let low = Infinity;
            let high = -Infinity;
            while (index < last && s.x[index] < limit) {
              const value = s.y[index++];
              if (!Number.isFinite(value)) continue;
              low = Math.min(low, value);
              high = Math.max(high, value);
            }
            if (low <= high) {
              g.moveTo(band.left + pixel + 0.5, Y(high));
              g.lineTo(band.left + pixel + 0.5, Y(low) + 1);
            }
          }
        } else {
          let pen = false;
          for (let index = first; index < last; index++) {
            if (!Number.isFinite(s.y[index])) {
              pen = false;
              continue;
            }
            if (index > first && s.x[index] - s.x[index - 1] > info.interval * 1.5) pen = false;
            if (pen) g.lineTo(X(s.x[index]), Y(s.y[index]));
            else g.moveTo(X(s.x[index]), Y(s.y[index]));
            pen = true;
          }
        }
        g.stroke();
        if (expected && isTime && c === view.active) {
          g.strokeStyle = '#77c9f8';
          g.setLineDash([2, 5]);
          for (const limit of [expected.min, expected.max]) {
            if (limit === undefined) continue;
            g.beginPath();
            g.moveTo(band.left, Y(limit));
            g.lineTo(right, Y(limit));
            g.stroke();
          }
          g.setLineDash([]);
        }
        if (view.trigger && c === view.active) {
          g.strokeStyle = '#ebc46b';
          g.setLineDash([6, 4]);
          g.beginPath();
          g.moveTo(band.left, Y(view.level));
          g.lineTo(right, Y(view.level));
          g.stroke();
          g.setLineDash([]);
        }
        if (view.cursors) {
          g.strokeStyle = '#dce7ef';
          g.setLineDash([3, 4]);
          g.beginPath();
          for (const x of view.cx) {
            g.moveTo(X(x), band.top);
            g.lineTo(X(x), bottom);
          }
          if (c === view.active) {
            for (const y of view.cy) {
              g.moveTo(band.left, Y(y));
              g.lineTo(right, Y(y));
            }
          }
          g.stroke();
          g.setLineDash([]);
        }
        g.restore();
        g.fillStyle = '#bdccd6';
        g.textAlign = 'right';
        g.textBaseline = 'middle';
        for (let row = 0; row <= VDIV; row += 2) {
          const value = view.yCenter[c] + (VDIV / 2 - row) * view.yPerDiv[c];
          g.fillText(fmt(value), band.left - 8, band.top + (row * band.height) / VDIV);
        }
        g.textBaseline = 'top';
        const ticks = Math.max(1, Math.min(5, Math.floor(band.width / 125)));
        for (let column = 0; column <= ticks; column++) {
          const value = view.xLeft + (column / ticks) * view.xPerDiv * HDIV;
          g.textAlign = column === 0 ? 'left' : column === ticks ? 'right' : 'center';
          g.fillText(isTime ? when(value) : fmt(value), band.left + (column / ticks) * band.width, bottom + 5);
        }
      }
      if (bands.length === 0) {
        g.fillStyle = '#dce7ef';
        g.fillText('Select a channel to inspect its signal.', 16, 24);
      }
      return;
    }
    g.fillStyle = '#0b1513';
    g.fillRect(0, 0, w, h);

    const dx = w / HDIV;
    const dy = h / VDIV;
    const X = (x: number) => ((x - view.xLeft) / view.xPerDiv) * dx;
    const Y = (y: number, c: number) => h / 2 - ((y - view.yCenter[c]) / view.yPerDiv[c]) * dy;

    // Graticule: division lines, stronger centre cross with tick marks.
    g.lineWidth = 1;
    g.strokeStyle = 'rgba(160, 215, 195, 0.16)';
    g.beginPath();
    for (let i = 1; i < HDIV; i++) {
      g.moveTo(Math.round(i * dx) + 0.5, 0);
      g.lineTo(Math.round(i * dx) + 0.5, h);
    }
    for (let j = 1; j < VDIV; j++) {
      g.moveTo(0, Math.round(j * dy) + 0.5);
      g.lineTo(w, Math.round(j * dy) + 0.5);
    }
    g.stroke();
    g.strokeStyle = 'rgba(160, 215, 195, 0.42)';
    g.beginPath();
    g.moveTo(Math.round(w / 2) + 0.5, 0);
    g.lineTo(Math.round(w / 2) + 0.5, h);
    g.moveTo(0, Math.round(h / 2) + 0.5);
    g.lineTo(w, Math.round(h / 2) + 0.5);
    for (let i = 0; i <= HDIV * 5; i++) {
      const x = Math.round((i * dx) / 5) + 0.5;
      g.moveTo(x, h / 2 - 3);
      g.lineTo(x, h / 2 + 3);
    }
    for (let j = 0; j <= VDIV * 5; j++) {
      const y = Math.round((j * dy) / 5) + 0.5;
      g.moveTo(w / 2 - 3, y);
      g.lineTo(w / 2 + 3, y);
    }
    g.stroke();

    // Ground truth behind the traces: a band over the full height, its name on a lane at the bottom.
    if (view.marks && marks.length > 0) {
      g.font = '11px ui-monospace, Menlo, Consolas, monospace';
      g.textBaseline = 'top';
      const laneEnd: number[] = [];
      for (const m of marks) {
        const x0 = Math.max(0, X(m.from));
        const x1 = Math.min(w, X(m.to));
        if (x1 <= 0 || x0 >= w || x1 <= x0) continue;
        const fault = m.kind === 'sensor_fault';
        g.fillStyle = fault ? 'rgba(239, 143, 143, 0.16)' : 'rgba(232, 214, 107, 0.11)';
        g.fillRect(x0, 17, x1 - x0, h - 17);
        const text = `${m.type}${m.scope ? ' ' + m.scope : ''}`;
        const width = Math.max(x1 - x0, g.measureText(text).width + 6);
        let lane = laneEnd.findIndex((end) => end <= x0);
        if (lane < 0) lane = laneEnd.length;
        laneEnd[lane] = x0 + width + 4;
        const y = h - 16 - lane * 15;
        g.fillStyle = fault ? '#ef8f8f' : '#e8d66b';
        g.fillRect(x0, y + 12, x1 - x0, 2);
        g.fillText(text, x0 + 2, y);
      }
    }

    // Traces. Dense records are drawn as one min/max column per pixel.
    bucket.series.forEach((s, c) => {
      if (!view.visible[c] || s.x.length === 0) return;
      const color = CHANNEL_COLORS[c % CHANNEL_COLORS.length];
      const from = Math.max(0, lowerBound(s.x, view.xLeft) - 1);
      const to = Math.min(s.x.length, lowerBound(s.x, xRight) + 1);
      g.strokeStyle = color;
      g.lineWidth = c === view.active ? 1.6 : 1.1;
      g.globalAlpha = c === view.active ? 1 : 0.82;
      g.shadowColor = color;
      g.shadowBlur = c === view.active ? 5 : 0;
      g.beginPath();
      if (to - from > w * 3) {
        let i = from;
        for (let px = 0; px < w && i < to; px++) {
          const limit = view.xLeft + ((px + 1) / dx) * view.xPerDiv;
          let lo = Infinity;
          let hi = -Infinity;
          while (i < to && s.x[i] < limit) {
            const v = s.y[i++];
            if (Number.isFinite(v)) {
              if (v < lo) lo = v;
              if (v > hi) hi = v;
            }
          }
          if (lo <= hi) {
            g.moveTo(px + 0.5, Y(hi, c));
            g.lineTo(px + 0.5, Y(lo, c) + 1);
          }
        }
      } else {
        let pen = false;
        for (let i = from; i < to; i++) {
          if (!Number.isFinite(s.y[i])) {
            pen = false;
            continue;
          }
          if (pen) g.lineTo(X(s.x[i]), Y(s.y[i], c));
          else g.moveTo(X(s.x[i]), Y(s.y[i], c));
          pen = true;
        }
        if (to - from <= w / 6) {
          for (let i = from; i < to; i++) {
            if (!Number.isFinite(s.y[i])) continue;
            g.moveTo(X(s.x[i]) + 2, Y(s.y[i], c));
            g.arc(X(s.x[i]), Y(s.y[i], c), 2, 0, 2 * Math.PI);
          }
        }
      }
      g.stroke();
      g.shadowBlur = 0;
      g.globalAlpha = 1;

      // Ground marker at the left edge: where this channel has y = 0.
      const y0 = Y(0, c);
      if (y0 > 4 && y0 < h - 4) {
        g.fillStyle = color;
        g.beginPath();
        g.moveTo(0, y0 - 5);
        g.lineTo(8, y0);
        g.lineTo(0, y0 + 5);
        g.fill();
      }
    });

    g.font = '11px ui-monospace, Menlo, Consolas, monospace';
    g.textBaseline = 'top';

    // The expected range of the signal: what lies outside these lines is not plausible.
    if (expected && isTime && active) {
      g.strokeStyle = '#7fd0ff';
      g.fillStyle = '#7fd0ff';
      g.setLineDash([2, 5]);
      for (const [name, value] of [['max', expected.max], ['min', expected.min]] as const) {
        if (value === undefined) continue;
        const y = Y(value, view.active);
        const text = `expected ${name} ${fmt(value)}${expected.unit ? ' ' + expected.unit : ''}`;
        if (y < 0 || y > h) {
          // outside the screen: say on which side it lies, so the range is never silently out of view
          g.fillText(`${text}, ${y < 0 ? 'above' : 'below'} the screen`, 6, y < 0 ? 18 : h - 30);
          continue;
        }
        g.beginPath();
        g.moveTo(0, Math.round(y) + 0.5);
        g.lineTo(w, Math.round(y) + 0.5);
        g.stroke();
        g.fillText(text, 6, name === 'max' ? y + 3 : y - 14);
      }
      g.setLineDash([]);
    }

    if (view.trigger && active) {
      const y = Y(view.level, view.active);
      g.strokeStyle = '#ff9d45';
      g.setLineDash([6, 4]);
      g.beginPath();
      g.moveTo(0, y);
      g.lineTo(w, y);
      g.stroke();
      g.setLineDash([]);
      g.fillStyle = '#ff9d45';
      g.fillText(`T ${view.rising ? 'rising' : 'falling'} ${fmt(view.level)}`, w - 150, Math.min(h - 16, Math.max(2, y + 3)));
    }

    if (view.cursors) {
      g.strokeStyle = '#e9ece8';
      g.setLineDash([2, 3]);
      g.beginPath();
      view.cx.forEach((x) => {
        g.moveTo(X(x), 0);
        g.lineTo(X(x), h);
      });
      view.cy.forEach((y) => {
        g.moveTo(0, Y(y, view.active));
        g.lineTo(w, Y(y, view.active));
      });
      g.stroke();
      g.setLineDash([]);
      g.fillStyle = '#e9ece8';
      view.cx.forEach((x, i) => g.fillText(`X${i + 1}`, X(x) + 3, h - 14));
      view.cy.forEach((y, i) => g.fillText(`Y${i + 1}`, 12, Y(y, view.active) + 2));
    }

    // On-screen readout, as on an instrument.
    g.fillStyle = 'rgba(11, 21, 19, 0.78)';
    g.fillRect(0, 0, w, 17);
    g.fillStyle = '#e9ece8';
    g.fillText(`H ${span(view.xPerDiv, isTime)}/div`, 8, 3);
    g.fillText(`${isTime ? 'start' : bucket.xLabel} ${span(view.xLeft, isTime)}`, 150, 3);
    if (active) {
      g.fillStyle = CHANNEL_COLORS[view.active % CHANNEL_COLORS.length];
      g.fillText(`CH${view.active + 1} ${fmt(view.yPerDiv[view.active])}${active.unit ? ' ' + active.unit : ''}/div`, 330, 3);
    }
    g.fillStyle = running ? '#63dd72' : '#ff7373';
    g.fillText(running ? 'RUN' : 'STOP', w - 44, 3);

    if (showLabels) {
      let row = 0;
      bucket.series.forEach((s, c) => {
        if (!view.visible[c]) return;
        g.fillStyle = CHANNEL_COLORS[c % CHANNEL_COLORS.length];
        g.fillText(`CH${c + 1} ${s.label}`.slice(0, 64), 14, 22 + row * 14);
        row++;
      });
    }
  }, [bucket, view, size, running, showLabels, active, xRight, isTime, marks, expected, instrument, channelInfo, when]);

  // ---------------------------------------------------------------- interaction

  const zoomX = useCallback((factor: number, anchor = 0.5) => {
    setView((v) => {
      const at = v.xLeft + v.xPerDiv * HDIV * anchor;
      const xPerDiv = v.xPerDiv * factor;
      return { ...v, xPerDiv, xLeft: at - xPerDiv * HDIV * anchor };
    });
  }, []);

  const scaleY = useCallback((dir: number) => {
    setView((v) => ({
      ...v,
      yPerDiv: v.yPerDiv.map((p, c) => (v.lockY || c === v.active ? step125(p, dir) : p)),
    }));
  }, []);

  const moveY = useCallback((divs: number) => {
    setView((v) => ({
      ...v,
      yCenter: v.yCenter.map((p, c) => (v.lockY || c === v.active ? p - divs * v.yPerDiv[c] : p)),
    }));
  }, []);

  useEffect(() => {
    const el = canvasRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (e.shiftKey) scaleY(e.deltaY > 0 ? 1 : -1);
      else {
        const visibility = instrument ? viewRef.current.visible : bucket.series.map(() => true);
        const band = scopeBands(size.w, size.h, visibility, instrument)[0];
        if (!band) return;
        const anchor = Math.min(1, Math.max(0, (e.offsetX - band.left) / band.width));
        zoomX(e.deltaY > 0 ? 1.25 : 0.8, anchor);
      }
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [zoomX, scaleY, instrument, size, bucket]);

  const drag = useRef<{ kind: string; x: number; y: number; start: View; band: ScopeBand } | null>(null);

  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    let v = viewRef.current;
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    const py = e.clientY - r.top;
    const bands = scopeBands(size.w, size.h, instrument ? v.visible : bucket.series.map(() => true), instrument);
    const channel = instrument ? scopeChannelAtY(py, bands) : v.active;
    if (channel === null) return;
    const band = bands.find((candidate) => candidate.channel === channel);
    if (!band) return;
    if (channel !== v.active) {
      v = { ...v, active: channel };
      setView(v);
    }
    const X = (x: number) => scopeX(x, v.xLeft, v.xPerDiv, band);
    const Y = (y: number) => scopeY(y, v.yCenter[v.active], v.yPerDiv[v.active], band);
    let kind = 'pan';
    if (v.cursors) {
      if (Math.abs(px - X(v.cx[0])) < 7) kind = 'cx0';
      else if (Math.abs(px - X(v.cx[1])) < 7) kind = 'cx1';
      else if (Math.abs(py - Y(v.cy[0])) < 7) kind = 'cy0';
      else if (Math.abs(py - Y(v.cy[1])) < 7) kind = 'cy1';
    }
    if (kind === 'pan' && v.trigger && Math.abs(py - Y(v.level)) < 7) kind = 'level';
    drag.current = { kind, x: px, y: py, start: v, band };
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const d = drag.current;
    if (!d) return;
    const r = e.currentTarget.getBoundingClientRect();
    const c = d.start.active;
    const delta = scopeDragDelta(e.clientX - r.left - d.x, e.clientY - r.top - d.y, d.start.xPerDiv, d.start.yPerDiv[c] ?? 1, d.band);
    const mx = delta.x;
    const my = delta.y;
    setView((v) => {
      switch (d.kind) {
        case 'cx0':
          return { ...v, cx: [d.start.cx[0] + mx, v.cx[1]] };
        case 'cx1':
          return { ...v, cx: [v.cx[0], d.start.cx[1] + mx] };
        case 'cy0':
          return { ...v, cy: [d.start.cy[0] + my, v.cy[1]] };
        case 'cy1':
          return { ...v, cy: [v.cy[0], d.start.cy[1] + my] };
        case 'level':
          return { ...v, level: d.start.level + my };
        default: {
          const divs = (e.clientY - r.top - d.y) / (d.band.height / VDIV);
          return {
            ...v,
            xLeft: d.start.xLeft - mx,
            yCenter: d.start.yCenter.map((p, k) => (v.lockY || k === c ? p + divs * d.start.yPerDiv[k] : p)),
          };
        }
      }
    });
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const map: Record<string, () => void> = {
      ArrowLeft: () => setView((v) => ({ ...v, xLeft: v.xLeft - v.xPerDiv })),
      ArrowRight: () => setView((v) => ({ ...v, xLeft: v.xLeft + v.xPerDiv })),
      ArrowUp: () => moveY(0.5),
      ArrowDown: () => moveY(-0.5),
      '+': () => zoomX(0.5),
      '=': () => zoomX(0.5),
      '-': () => zoomX(2),
    };
    if (map[e.key]) {
      e.preventDefault();
      map[e.key]();
    }
  };

  /** Moves the time base so that the next or previous trigger event sits on the centre line. */
  const jumpTrigger = (dir: number) => {
    if (!active) return;
    const centre = view.xLeft + (view.xPerDiv * HDIV) / 2;
    const at = crossings(active.y, view.level, view.rising).map((i) => active.x[i]);
    const eps = view.xPerDiv * 0.01;
    const target = dir > 0 ? at.find((x) => x > centre + eps) : [...at].reverse().find((x) => x < centre - eps);
    if (target !== undefined) setView((v) => ({ ...v, xLeft: target - (v.xPerDiv * HDIV) / 2 }));
  };

  const events = useMemo(
    () => (view.trigger && active ? crossings(active.y, view.level, view.rising).length : 0),
    [view.trigger, view.level, view.rising, active]
  );

  const dX = view.cx[1] - view.cx[0];
  const dY = view.cy[1] - view.cy[0];
  const unit = active?.unit ? ` ${active.unit}` : '';

  return (
    <div className={instrument ? 'wb-scope wb-scope-instrument' : 'wb-scope'}>
      <div className="wb-screen" ref={wrapRef} style={instrument ? { minHeight: Math.max(240, view.visible.filter(Boolean).length * 120) } : undefined}>
        <canvas
          ref={canvasRef}
          style={{ width: size.w, height: size.h }}
          tabIndex={0}
          aria-label={`Oscilloscope view of ${bucket.name}. Arrow keys move, plus and minus change the time base.`}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={() => (drag.current = null)}
          onDoubleClick={() => setView((v) => autoset(bucket, v))}
          onKeyDown={onKeyDown}
        />
      </div>

      {instrument && (
        <section className="wb-episode-rail" aria-label="Episode timeline">
          <div className="wb-episode-heading"><strong>Episodes</strong><span>{active?.label ?? 'No active channel'}</span></div>
          {episodeRail.length === 0 ? <p>Episode identities not supplied.</p> : (
            <div className="wb-episode-items">
              {episodeRail.map(({ episode, invalid, quality }, index) => {
                const previous = episodeRail[index - 1]?.episode;
                const coordinate = (value: number) => Number.isFinite(value) ? (isTime ? when(value) : `${fmt(value)} ${bucket.xLabel}`) : 'Invalid coordinate';
                return (
                  <div className="wb-episode-item" key={episode.episodeId}>
                    {previous && previous.end < episode.start && (
                      <div className="wb-episode-gap" title={`${coordinate(previous.end)} to ${coordinate(episode.start)}`}>
                        <strong>Gap</strong><small>{coordinate(previous.end)} to {coordinate(episode.start)}</small>
                      </div>
                    )}
                    <button
                      type="button"
                      aria-label={`Focus episode ${episode.episodeId}`}
                      disabled={invalid}
                      title={`${episode.episodeId}: ${coordinate(episode.start)} to ${coordinate(episode.end)}; ${quality}`}
                      onClick={() => {
                        if (invalid) return;
                        setView((v) => ({ ...v, xLeft: episode.start, xPerDiv: (episode.end - episode.start) / HDIV }));
                      }}
                    >
                      <strong>{episode.episodeId}</strong>
                      <small>{coordinate(episode.start)} to {coordinate(episode.end)}</small>
                      <span>{quality}</span>
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}

      <div className="wb-knobs">
        <fieldset>
          <legend>Acquire</legend>
          <div className="wb-row">
            <button className={running ? 'wb-run on' : 'wb-run'} onClick={() => setRunning((r) => !r)}>
              {instrument ? (running ? 'Pause sweep' : 'Sweep snapshot') : (running ? 'Stop' : 'Run')}
            </button>
            <button onClick={() => setView((v) => autoset(bucket, v))}>Auto set</button>
          </div>
        </fieldset>

        <fieldset>
          <legend>Horizontal</legend>
          <div className="wb-row">
            <span className="wb-lbl">Scale</span>
            <button aria-label="Shorter time base" onClick={() => setView((v) => ({ ...v, xPerDiv: step125(v.xPerDiv, -1) }))}>-</button>
            <output>{span(view.xPerDiv, isTime)}/div</output>
            <button aria-label="Longer time base" onClick={() => setView((v) => ({ ...v, xPerDiv: step125(v.xPerDiv, 1) }))}>+</button>
          </div>
          <div className="wb-row">
            <span className="wb-lbl">Position</span>
            <button aria-label="Move left" onClick={() => setView((v) => ({ ...v, xLeft: v.xLeft - v.xPerDiv }))}>&lt;</button>
            <output>{span(view.xLeft, isTime)}</output>
            <button aria-label="Move right" onClick={() => setView((v) => ({ ...v, xLeft: v.xLeft + v.xPerDiv }))}>&gt;</button>
          </div>
        </fieldset>

        <fieldset>
          <legend>Vertical</legend>
          <div className="wb-row">
            <span className="wb-lbl">Scale</span>
            <button aria-label="Finer vertical scale" onClick={() => scaleY(-1)}>-</button>
            <output>{fmt(view.yPerDiv[view.active] ?? 1)}{unit}/div</output>
            <button aria-label="Coarser vertical scale" onClick={() => scaleY(1)}>+</button>
          </div>
          <div className="wb-row">
            <span className="wb-lbl">Position</span>
            <button aria-label="Move trace down" onClick={() => moveY(-0.5)}>v</button>
            <output>{fmt(view.yCenter[view.active] ?? 0)}</output>
            <button aria-label="Move trace up" onClick={() => moveY(0.5)}>^</button>
          </div>
          <label className="wb-check">
            <input type="checkbox" checked={view.lockY} onChange={(e) => setView((v) => autoset(bucket, { ...v, lockY: e.target.checked }))} />
            One scale for all channels
          </label>
          <ul className="wb-channels">
            {bucket.series.map((s, c) => (
              <li key={s.id + c} className={c === view.active ? 'active' : ''}>
                <input
                  type="checkbox"
                  aria-label={`Show channel ${c + 1}`}
                  checked={view.visible[c] ?? true}
                  onChange={(e) => setView((v) => ({ ...v, visible: v.visible.map((x, k) => (k === c ? e.target.checked : x)) }))}
                />
                <button title={s.label} onClick={() => setView((v) => ({ ...v, active: c }))}>
                  <i style={{ background: CHANNEL_COLORS[c % CHANNEL_COLORS.length] }} />
                  CH{c + 1} {s.label}
                </button>
              </li>
            ))}
          </ul>
        </fieldset>

        <fieldset>
          <legend>Cursors</legend>
          <label className="wb-check">
            <input type="checkbox" checked={view.cursors} onChange={(e) => setView((v) => ({ ...v, cursors: e.target.checked }))} />
            Show, drag on the screen
          </label>
          {view.cursors && (
            <dl className="wb-readout">
              <dt>dX</dt><dd>{span(dX, isTime)}</dd>
              {isTime && <><dt>1/dX</dt><dd>{dX !== 0 ? `${fmt(1 / Math.abs(dX))} Hz` : 'n/a'}</dd></>}
              <dt>dY</dt><dd>{fmt(dY)}{unit}</dd>
              <dt>dY/dX</dt><dd>{dX !== 0 ? fmt(dY / dX) : 'n/a'}</dd>
            </dl>
          )}
        </fieldset>

        {marks.length > 0 && (
          <fieldset>
            <legend>Ground truth</legend>
            <label className="wb-check">
              <input type="checkbox" checked={view.marks} onChange={(e) => setView((v) => ({ ...v, marks: e.target.checked }))} />
              Show scenario events
            </label>
            <ul className="wb-marks">
              {marks.map((m, i) => (
                <li key={i}>
                  <button
                    title="Brings the event onto the screen"
                    onClick={() => setView((v) => ({ ...v, marks: true, xPerDiv: ((m.to - m.from) * 1.6) / HDIV, xLeft: m.from - (m.to - m.from) * 0.3 }))}
                  >
                    <i className={m.kind} />
                    {m.type}
                    {m.scope ? ` ${m.scope}` : ''}
                  </button>
                  <span>{when(m.from)} to {when(m.to)}</span>
                </li>
              ))}
            </ul>
          </fieldset>
        )}

        <fieldset>
          <legend>Trigger</legend>
          <label className="wb-check">
            <input type="checkbox" checked={view.trigger} onChange={(e) => setView((v) => ({ ...v, trigger: e.target.checked }))} />
            On CH{view.active + 1}
          </label>
          {view.trigger && (
            <>
              <div className="wb-row">
                <span className="wb-lbl">Level</span>
                <input
                  type="number"
                  aria-label="Trigger level"
                  value={Number(view.level.toPrecision(6))}
                  step={(view.yPerDiv[view.active] ?? 1) / 10}
                  onChange={(e) => setView((v) => ({ ...v, level: Number(e.target.value) }))}
                />
                <select aria-label="Trigger edge" value={view.rising ? 'rising' : 'falling'} onChange={(e) => setView((v) => ({ ...v, rising: e.target.value === 'rising' }))}>
                  <option value="rising">rising</option>
                  <option value="falling">falling</option>
                </select>
              </div>
              <div className="wb-row">
                <button onClick={() => jumpTrigger(-1)}>&lt; Previous</button>
                <output>{events} events</output>
                <button onClick={() => jumpTrigger(1)}>Next &gt;</button>
              </div>
              <button className="wb-wide" onClick={() => onUseTrigger(view.level, view.rising)} title="Adds a segment.threshold node with this level and edge">
                Segment at this trigger
              </button>
            </>
          )}
        </fieldset>

        <fieldset>
          <legend>Measure CH{view.active + 1}, visible window</legend>
          {m ? (
            <dl className="wb-readout">
              <dt>Pk-Pk</dt><dd>{fmt(m.max - m.min)}{unit}</dd>
              <dt>Max</dt><dd>{fmt(m.max)}</dd>
              <dt>Min</dt><dd>{fmt(m.min)}</dd>
              <dt>Mean</dt><dd>{fmt(m.mean)}</dd>
              <dt>RMS</dt><dd>{fmt(m.rms)}</dd>
              {isTime && <><dt>Freq</dt><dd>{Number.isFinite(m.freq) ? `${fmt(m.freq)} Hz` : 'n/a'}</dd></>}
              <dt>Points</dt><dd>{m.n}</dd>
            </dl>
          ) : (
            <p className="wb-muted">No points in the window. Use Auto set.</p>
          )}
        </fieldset>
      </div>
    </div>
  );
}
