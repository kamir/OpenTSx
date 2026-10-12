/**
 * Oscilloscope view of a bucket: graticule of 10 x 8 divisions, time base and
 * vertical scale per division, cursors, trigger and measurements. It takes the
 * place of the MultiChart dialog of the Swing workbench.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { crossings, fmt, stats } from './ops';
import { Bucket, Series } from './types';

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

function autoset(b: Bucket, prev?: View): View {
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
  const lockY = prev?.lockY ?? true;
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
  /** Called when the trigger settings are taken over as a segmenter node. */
  onUseTrigger: (level: number, rising: boolean) => void;
}

export default function Scope({ bucket, showLabels, onUseTrigger }: ScopeProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 800, h: 420 });
  const [view, setView] = useState<View>(() => autoset(bucket));
  const [running, setRunning] = useState(false);
  const viewRef = useRef(view);
  viewRef.current = view;
  const isTime = bucket.xKind === 'time';

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
  }, [bucket, view, size, running, showLabels, active, xRight, isTime]);

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
      else zoomX(e.deltaY > 0 ? 1.25 : 0.8, e.offsetX / el.clientWidth);
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [zoomX, scaleY]);

  const drag = useRef<{ kind: string; x: number; y: number; start: View } | null>(null);

  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const v = viewRef.current;
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    const py = e.clientY - r.top;
    const dx = size.w / HDIV;
    const dy = size.h / VDIV;
    const X = (x: number) => ((x - v.xLeft) / v.xPerDiv) * dx;
    const Y = (y: number) => size.h / 2 - ((y - v.yCenter[v.active]) / v.yPerDiv[v.active]) * dy;
    let kind = 'pan';
    if (v.cursors) {
      if (Math.abs(px - X(v.cx[0])) < 7) kind = 'cx0';
      else if (Math.abs(px - X(v.cx[1])) < 7) kind = 'cx1';
      else if (Math.abs(py - Y(v.cy[0])) < 7) kind = 'cy0';
      else if (Math.abs(py - Y(v.cy[1])) < 7) kind = 'cy1';
    }
    if (kind === 'pan' && v.trigger && Math.abs(py - Y(v.level)) < 7) kind = 'level';
    drag.current = { kind, x: px, y: py, start: v };
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const d = drag.current;
    if (!d) return;
    const r = e.currentTarget.getBoundingClientRect();
    const mx = ((e.clientX - r.left - d.x) / (size.w / HDIV)) * d.start.xPerDiv;
    const c = d.start.active;
    const my = (-(e.clientY - r.top - d.y) / (size.h / VDIV)) * (d.start.yPerDiv[c] ?? 1);
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
          const divs = (e.clientY - r.top - d.y) / (size.h / VDIV);
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
    <div className="wb-scope">
      <div className="wb-screen" ref={wrapRef}>
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

      <div className="wb-knobs">
        <fieldset>
          <legend>Acquire</legend>
          <div className="wb-row">
            <button className={running ? 'wb-run on' : 'wb-run'} onClick={() => setRunning((r) => !r)}>
              {running ? 'Stop' : 'Run'}
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
