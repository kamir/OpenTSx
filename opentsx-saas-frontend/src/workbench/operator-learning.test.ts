import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { defaultParams, mulberry32, OPS } from './ops';
import { AxisKind, Bucket, Params } from './types';

interface LessonBucket {
  name: string;
  xKind: AxisKind;
  xLabel: string;
  yLabel: string;
  t0Ms?: number;
  series: { id: string; label: string; unit?: string; x: number[]; y: (number | null)[] }[];
}

interface Lesson {
  id: string;
  operator: string;
  title: string;
  surface: string;
  objectives: string[];
  prerequisites: string[];
  tasks: string[];
  expected: string[];
  pitfalls: string[];
  parameterKeys: string[];
  starter: {
    bucket: LessonBucket;
    params: Params;
    noise?: { seed: number; count: number };
    others?: LessonBucket[];
  };
  expectedOutput: {
    xKind: AxisKind;
    seriesCount: number;
    noteIncludes?: string;
    alphaRange?: [number, number];
    vector?: { clock: number; components: string[]; keyBy: string };
    series: {
      index: number;
      metric?: string;
      x?: number[];
      y?: (number | null)[];
      sortedY?: number[];
      differsFromInput?: boolean;
      unit?: string;
      minLength?: number;
      allFinite?: boolean;
      increasingX?: boolean;
    }[];
  };
  references: Record<'manual' | 'spec' | 'source' | 'tests' | 'vectors', string[]>;
}

const ROOT = resolve(process.cwd(), '..');
const FILE = resolve(ROOT, 'docs/learning/ui-operators.v1.json');
const read = (): { schema: string; lessons: Lesson[] } => JSON.parse(readFileSync(FILE, 'utf8'));

function bucketOf(input: LessonBucket): Bucket {
  return {
    ...input,
    series: input.series.map((s) => ({ ...s, x: Float64Array.from(s.x), y: Float64Array.from(s.y, (v) => v ?? NaN) })),
  };
}

function inputOf(lesson: Lesson): Bucket {
  const bucket = bucketOf(lesson.starter.bucket);
  if (lesson.starter.noise) {
    const { seed, count } = lesson.starter.noise;
    const random = mulberry32(seed);
    bucket.series = [{ id: 'noise', label: 'noise', x: Float64Array.from({ length: count }, (_, i) => i), y: Float64Array.from({ length: count }, () => random() - 0.5) }];
  }
  return bucket;
}

function expectValues(actual: ArrayLike<number>, expected: (number | null)[]): void {
  expect(actual).toHaveLength(expected.length);
  expected.forEach((value, i) => {
    if (value === null) expect(Number.isNaN(actual[i]), `gap at ${i}`).toBe(true);
    else expect(actual[i], `value at ${i}`).toBeCloseTo(value, 10);
  });
}

describe('operator learning contract', () => {
  it('covers exactly the implemented workbench operators with runnable lessons', () => {
    const document = read();
    expect(document.schema).toBe('opentsx.learning.v1');
    expect(document.lessons.map((lesson) => lesson.operator).sort()).toEqual(OPS.map((op) => op.id).sort());
    expect(new Set(document.lessons.map((lesson) => lesson.id)).size).toBe(document.lessons.length);
    for (const lesson of document.lessons) {
      expect(lesson.id).toBe(`ui.${lesson.operator}`);
      expect(lesson.surface).toBe('ui');
      expect(lesson.title.trim().length).toBeGreaterThan(0);
      for (const values of [lesson.objectives, lesson.prerequisites, lesson.tasks, lesson.expected, lesson.pitfalls]) {
        expect(values.length, lesson.id).toBeGreaterThan(0);
        expect(values.every((value) => value.trim().length > 0), lesson.id).toBe(true);
      }
      const op = OPS.find((candidate) => candidate.id === lesson.operator)!;
      expect(lesson.parameterKeys.slice().sort(), lesson.id).toEqual(op.params.map((param) => param.key).sort());
      expect(Object.keys(lesson.starter.params).sort(), lesson.id).toEqual(lesson.parameterKeys.slice().sort());
      const defaults = defaultParams(op);
      for (const param of op.params) {
        const value = lesson.starter.params[param.key];
        expect(typeof value, `${lesson.id}.${param.key}`).toBe(typeof defaults[param.key]);
        if (param.type === 'number') expect(Number.isFinite(value), `${lesson.id}.${param.key}`).toBe(true);
        if (param.options) expect(param.options, `${lesson.id}.${param.key}`).toContain(value);
      }
      for (const category of ['manual', 'spec', 'source', 'tests', 'vectors'] as const) {
        const references = lesson.references[category];
        if (category !== 'vectors') expect(references.length, `${lesson.id}.${category}`).toBeGreaterThan(0);
        for (const reference of references) {
          const [path, fragment] = reference.split('#');
          expect(path.startsWith('/') || path.split('/').includes('..'), reference).toBe(false);
          const content = readFileSync(resolve(ROOT, path), 'utf8');
          if (fragment?.startsWith('L')) {
            expect(Number(fragment.slice(1)), reference).toBeGreaterThan(0);
            expect(Number(fragment.slice(1)), reference).toBeLessThanOrEqual(content.split('\n').length);
          } else if (fragment && !path.endsWith('.md')) {
            expect(content, reference).toContain(fragment);
          } else if (fragment && path.endsWith('.md')) {
            expect(content, reference).toContain(`<a id="${fragment}"></a>`);
            if (path === 'docs/learning/ui-operator-contracts.md') {
              const section = content.split(`<a id="${fragment}"></a>`)[1].split('<a id="')[0];
              expect(section, lesson.id).toContain(`\`${lesson.operator}\``);
              for (const param of op.params) expect(section, `${lesson.id}.${param.key}`).toContain(`\`${param.key}=${JSON.stringify(param.default)}\``);
            }
          }
          if (category === 'vectors') expect(JSON.parse(content).schema, reference).toMatch(/^opentsx\.testvectors\./);
        }
      }
    }
  });

  for (const op of OPS) {
    it(`${op.id} produces the lesson predictions using its real apply function`, () => {
      const lesson = read().lessons.find((candidate) => candidate.operator === op.id)!;
      expect(lesson, op.id).toBeDefined();
      const input = inputOf(lesson);
      const others = lesson.starter.others?.map(bucketOf);
      const out = op.apply(input, lesson.starter.params, others);
      const expected = lesson.expectedOutput;
      expect(out.xKind).toBe(expected.xKind);
      expect(out.series).toHaveLength(expected.seriesCount);
      if (expected.noteIncludes) expect(out.note).toContain(expected.noteIncludes);
      if (expected.vector) expect(out.vector).toEqual(expected.vector);
      if (expected.alphaRange) {
        const alpha = Number(out.note?.match(/alpha = ([^\n]+)/)?.[1]);
        expect(alpha).toBeGreaterThan(expected.alphaRange[0]);
        expect(alpha).toBeLessThan(expected.alphaRange[1]);
      }
      expect(expected.series.length, lesson.id).toBeGreaterThan(0);
      for (const prediction of expected.series) {
        const series = out.series[prediction.index];
        expect(series).toBeDefined();
        expect(series.x.length).toBe(series.y.length);
        if (prediction.metric) expect(series.metric).toBe(prediction.metric);
        if (prediction.x) expectValues(series.x, prediction.x);
        if (prediction.y) expectValues(series.y, prediction.y);
        if (prediction.unit) expect(series.unit).toBe(prediction.unit);
        if (prediction.sortedY) expect(Array.from(series.y).sort((a, b) => a - b)).toEqual(prediction.sortedY);
        if (prediction.differsFromInput) expect(Array.from(series.y)).not.toEqual(Array.from(input.series[prediction.index].y));
        if (prediction.minLength) expect(series.y.length).toBeGreaterThanOrEqual(prediction.minLength);
        if (prediction.allFinite) expect(Array.from(series.y).every(Number.isFinite)).toBe(true);
        if (prediction.increasingX) expect(Array.from(series.x).every((x, i) => i === 0 || x > series.x[i - 1])).toBe(true);
      }
      const again = op.apply(inputOf(lesson), lesson.starter.params, lesson.starter.others?.map(bucketOf));
      expect(again).toEqual(out);
    });
  }
});
