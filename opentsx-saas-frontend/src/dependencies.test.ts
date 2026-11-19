import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = process.cwd();

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return sources(path);
    return /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) ? [path] : [];
  });
}

describe('the runtime dependencies', () => {
  it('are each imported by the application', () => {
    const pkg = JSON.parse(readFileSync(resolve(ROOT, 'package.json'), 'utf8')) as { dependencies: Record<string, string> };
    const code = sources(resolve(ROOT, 'src')).map((f) => readFileSync(f, 'utf8')).join('\n');
    const unused = Object.keys(pkg.dependencies).filter((name) => {
      const quoted = name.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
      return !new RegExp(`(from |import )'${quoted}(/[^']*)?'`).test(code);
    });
    expect(unused).toEqual([]);
  });
});

describe('the access token', () => {
  it('is read from localStorage by services/api.ts only', () => {
    const readers = sources(resolve(ROOT, 'src'))
      .filter((f) => readFileSync(f, 'utf8').includes("localStorage.getItem('access_token')"))
      .map((f) => f.slice(ROOT.length + 1));
    expect(readers).toEqual(['src/services/api.ts']);
  });
});
