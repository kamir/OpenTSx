import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

export type Mark = 'line' | 'point' | 'step' | 'bar';

function text(s: string): string {
  if (s.includes(';') || s.includes('\n')) throw new Error(`chart text holds no ';' or line break: ${s}`);
  return s;
}

/**
 * Chart data of an operator example, written as <id>.csv into OPENTSX_EXAMPLES_DIR when that is set.
 * tools/operator_charts.py draws docs/user-manual/img/operators/<id>.png from it; the file format is described there.
 */
export class ExampleChart {
  private readonly head: string[];
  private readonly rows: string[] = [];
  private panels = 0;

  constructor(private readonly id: string, title: string) {
    this.head = [`#title=${text(title)}`];
  }

  panel(title: string, x: string, y: string, log: '' | 'x' | 'y' | 'xy' = ''): this {
    this.panels += 1;
    this.head.push(`#panel=${this.panels};title=${text(title)};x=${text(x)};y=${text(y)};log=${log}`);
    return this;
  }

  note(s: string): this {
    this.head.push(`#note=${this.panels};${text(s)}`);
    return this;
  }

  add(series: string, mark: Mark, x: ArrayLike<number>, y: ArrayLike<number>): this {
    if (this.panels === 0) throw new Error('add a panel first');
    if (series.includes(',')) throw new Error(`a series name holds no comma: ${series}`);
    if (x.length !== y.length) throw new Error(`${series}: ${x.length} x values but ${y.length} y values`);
    for (let i = 0; i < x.length; i++) this.rows.push(`${this.panels},${series},${mark},${x[i]},${y[i]}`);
    return this;
  }

  write(): void {
    const dir = process.env.OPENTSX_EXAMPLES_DIR;
    if (!dir) return;
    writeFileSync(join(dir, `${this.id}.csv`), [...this.head, 'panel,series,mark,x,y', ...this.rows].join('\n') + '\n', 'utf8');
  }
}
