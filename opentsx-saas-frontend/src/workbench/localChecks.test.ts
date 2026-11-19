import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

function localCommand(): string {
  const { scripts } = JSON.parse(readFileSync('package.json', 'utf8'));
  expect(
    scripts['check:local'],
    'package.json must expose check:local',
  ).toBeTypeOf('string');
  return scripts['check:local'];
}

function execute(command: string, failure = '', empty = false) {
  const tools = ['tsc', 'npm', 'vitest']
    .map(
      (tool) => `${tool}() {
  printf '%s' '${tool}'
  for arg in "$@"; do printf '\\t%s' "$arg"; done
  printf '\\n'
  if [ '${tool}' = "$LOCAL_CHECK_FAILURE" ]; then
    if [ '${tool}' != 'vitest' ] || [ "$1" = 'related' ]; then return 17; fi
  fi
  if [ '${tool}' = 'vitest' ] && [ "$LOCAL_CHECK_EMPTY" = 'yes' ]; then
    for arg in "$@"; do
      if [ "$arg" = '--passWithNoTests=false' ]; then return 1; fi
    done
  fi
  return 0
}`,
    )
    .join('\n');
  const result = spawnSync(
    '/bin/sh',
    [
      '-c',
      `${tools}\n${command} "$@"`,
      'check:local',
      'src/workbench/presentation.ts',
    ],
    {
      encoding: 'utf8',
      timeout: 10000,
      env: {
        PATH: '',
        LOCAL_CHECK_FAILURE: failure,
        LOCAL_CHECK_EMPTY: empty ? 'yes' : 'no',
      },
    },
  );
  if (result.error) throw result.error;
  expect(result.signal).toBeNull();
  const calls = result.stdout
    .trim()
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [tool, ...args] = line.split('\t');
      return { tool, args };
    });
  return { status: result.status, calls };
}

describe('local frontend checks', () => {
  it('checks types and lint before running related tests with explicit source arguments', () => {
    const command = localCommand();
    expect(command).not.toContain('||');
    expect(command).toContain('--passWithNoTests=false');
    const result = execute(command);
    expect(result.status).toBe(0);
    expect(result.calls).toEqual([
      { tool: 'tsc', args: ['--noEmit'] },
      { tool: 'npm', args: ['run', 'lint'] },
      {
        tool: 'vitest',
        args: [
          'related',
          '--run',
          '--passWithNoTests=false',
          'src/workbench/presentation.ts',
        ],
      },
    ]);
  });

  it.each(['tsc', 'npm', 'vitest'])(
    'returns the %s failure and stops before running later checks',
    (failure) => {
      const result = execute(localCommand(), failure);
      expect(result.status).toBe(17);
      const tools = ['tsc', 'npm', 'vitest'];
      expect(result.calls.map((call) => call.tool)).toEqual(
        tools.slice(0, tools.indexOf(failure) + 1),
      );
    },
  );

  it('fails when no related tests exist without replacing the result with a full run', () => {
    const result = execute(localCommand(), '', true);
    expect(result.status).toBe(1);
    expect(result.calls.filter((call) => call.tool === 'vitest')).toEqual([
      {
        tool: 'vitest',
        args: [
          'related',
          '--run',
          '--passWithNoTests=false',
          'src/workbench/presentation.ts',
        ],
      },
    ]);
  });

  it('detects removal of the no-tests guard and replacement of a failed check with a passing fallback', () => {
    const command = localCommand();
    expect(execute(command, '', true).status).toBe(1);
    expect(
      execute(command.replace('--passWithNoTests=false', ''), '', true).status,
    ).toBe(0);
    expect(execute(command, 'vitest').status).toBe(17);
    const fallback = execute(`${command} || vitest run`, 'vitest');
    expect(fallback.status).toBe(0);
    expect(fallback.calls[fallback.calls.length - 1]).toEqual({
      tool: 'vitest',
      args: ['run', 'src/workbench/presentation.ts'],
    });
    expect(execute(command.replace('&&', ';'), 'tsc').status).toBe(0);
  });
});
