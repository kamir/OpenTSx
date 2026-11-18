export interface ScopeBand {
  channel: number;
  left: number;
  top: number;
  width: number;
  height: number;
}

export function scopeBands(
  width: number,
  height: number,
  visible: boolean[],
  instrument: boolean,
): ScopeBand[] {
  if (
    !Number.isFinite(width) ||
    !Number.isFinite(height) ||
    width <= 0 ||
    height <= 0
  )
    throw new RangeError("Scope dimensions must be positive finite numbers.");
  const channels = visible.flatMap((shown, channel) =>
    shown ? [channel] : [],
  );
  return channels.map((channel, index) => ({
    channel,
    left: instrument ? 64 : 0,
    top: instrument ? (height * index) / channels.length + 22 : 0,
    width: instrument ? Math.max(1, width - 80) : width,
    height: instrument ? Math.max(1, height / channels.length - 40) : height,
  }));
}

export function scopeY(
  value: number,
  center: number,
  perDivision: number,
  band: ScopeBand,
): number {
  return (
    band.top +
    band.height / 2 -
    ((value - center) / perDivision) * (band.height / 8)
  );
}

export function scopeValueAtY(
  pixel: number,
  center: number,
  perDivision: number,
  band: ScopeBand,
): number {
  return (
    center +
    ((band.top + band.height / 2 - pixel) / (band.height / 8)) * perDivision
  );
}

export function scopeX(
  value: number,
  left: number,
  perDivision: number,
  band: ScopeBand,
): number {
  return band.left + ((value - left) / perDivision) * (band.width / 10);
}

export function scopeChannelAtY(
  pixel: number,
  bands: ScopeBand[],
): number | null {
  return (
    bands.find((band) => pixel >= band.top && pixel <= band.top + band.height)
      ?.channel ?? null
  );
}

export function scopeDragDelta(
  dx: number,
  dy: number,
  xPerDivision: number,
  yPerDivision: number,
  band: ScopeBand,
): { x: number; y: number } {
  return {
    x: (dx / (band.width / 10)) * xPerDivision,
    y: (-dy / (band.height / 8)) * yPerDivision,
  };
}
