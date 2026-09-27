/**
 * Hermes's Python, however Hermes was installed (`hermes-python.ts`): the image's venv, an
 * older installer's launcher, and the current installer's, which only Hermes itself can say
 * how to run (`hermes --print-runtime-command`). Real files and a real `python3`.
 */
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { hermesPythonRunner } from './hermes-pending-writes.js';
import {
  printedRuntimeCommand,
  resolveHermesPython,
  venvPythonOf,
  wrapRuntimeCommand,
} from './hermes-python.js';

const dirs: string[] = [];
function tempDir(): string {
  const dir = realpathSync(mkdtempSync(path.join(tmpdir(), 'corehub-hermes-python-')));
  dirs.push(dir);
  return dir;
}
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function executable(file: string, text: string): string {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, text);
  chmodSync(file, 0o755);
  return file;
}

/** A venv: `bin/python`, `bin/hermes` and `pyvenv.cfg`. */
function venv(root: string): { python: string; hermes: string } {
  mkdirSync(root, { recursive: true });
  writeFileSync(path.join(root, 'pyvenv.cfg'), 'home = /usr/bin\n');
  return {
    python: executable(path.join(root, 'bin', 'python'), '#!/bin/sh\n'),
    hermes: executable(path.join(root, 'bin', 'hermes'), '#!/bin/sh\n'),
  };
}

describe("Hermes's Python beside a venv", () => {
  it('is `python` beside the program (the image)', () => {
    const { python, hermes } = venv(tempDir());
    expect(venvPythonOf(hermes)).toBe(python);
  });

  it('is found through a launcher that links to the venv', () => {
    const { python, hermes } = venv(tempDir());
    const link = path.join(tempDir(), 'hermes');
    symlinkSync(hermes, link);
    expect(venvPythonOf(link)).toBe(python);
  });

  it("is the venv an older installer's shell launcher runs", () => {
    const { python } = venv(path.join(tempDir(), 'hermes-agent', 'venv'));
    const launcher = executable(
      path.join(tempDir(), '.local', 'bin', 'hermes'),
      `#!/usr/bin/env bash\nunset PYTHONPATH\nunset PYTHONHOME\nexec "${python}" "/x/hermes" "$@"\n`,
    );
    expect(venvPythonOf(launcher)).toBe(python);
  });

  it("is not a bare interpreter a launcher runs (the current installer's has no packages in it)", () => {
    const store = executable(
      path.join(tempDir(), 'tools', 'python-3.14', 'bin', 'python3'),
      '#!/bin/sh\n',
    );
    const launcher = executable(
      path.join(tempDir(), 'hermes'),
      `#!/bin/sh\nexec ${store} -I -c 'import hermes_bootstrap' "$@"\n`,
    );
    expect(venvPythonOf(launcher)).toBeNull();
  });
});

describe("Hermes's Python as Hermes itself runs it", () => {
  const printed = (bootstrapDir: string) =>
    JSON.stringify([
      'python3',
      '-I',
      '-c',
      `import os, sys, runpy; sys.path.insert(0, ${JSON.stringify(bootstrapDir)}); os.environ['HERMES_HOME'] = os.environ.get('HERMES_HOME') or '/x'; import hermes_bootstrap; runpy.run_module('hermes_cli.main', run_name='__main__', alter_sys=True)`,
    ]);

  it("keeps Hermes's prefix and runs the program in place of Hermes's module", () => {
    expect(
      wrapRuntimeCommand(['py', '-I', '-c', 'a; import hermes_bootstrap; runpy.run_module(x)']),
    ).toEqual({
      command: 'py',
      args: [
        '-I',
        '-c',
        "a; import hermes_bootstrap; import sys as _corehub_sys; _corehub_code = _corehub_sys.argv.pop(1); exec(compile(_corehub_code, '<corehub>', 'exec'), {'__name__': '__main__'})",
      ],
    });
    expect(wrapRuntimeCommand(['py', '-c', 'x'])).toBeNull();
    expect(wrapRuntimeCommand(['py', '-I', '-c', 'no bootstrap here'])).toBeNull();
    expect(wrapRuntimeCommand('not a list')).toBeNull();
  });

  it("runs a program with its arguments after Hermes's bootstrap, in the home asked for", async () => {
    const bootstrap = tempDir();
    writeFileSync(
      path.join(bootstrap, 'hermes_bootstrap.py'),
      'import os\nos.environ["BOOTED"] = "yes"\n',
    );
    const answerFile = path.join(bootstrap, 'printed.json');
    writeFileSync(answerFile, `${printed(bootstrap)}\n`);
    const hermes = executable(
      path.join(tempDir(), 'hermes'),
      `#!/bin/sh\n[ "$1" = "--print-runtime-command" ] || exit 2\ncat '${answerFile}'\n`,
    );
    const python = await resolveHermesPython(hermes, { PATH: '/usr/bin:/bin' });
    expect(python).not.toBeNull();
    const run = hermesPythonRunner({ python: python!, env: () => ({ PATH: '/usr/bin:/bin' }) });
    const home = tempDir();
    const answer = await run(home, [
      'import os, sys; print(os.environ["BOOTED"], os.environ["HERMES_HOME"] == os.getcwd(), sys.argv[1:])',
      'first',
      'second',
    ]);
    expect(answer.code).toBe(0);
    expect(answer.stdout.trim()).toBe("yes True ['first', 'second']");
  });

  it('is nothing for an older Hermes that does not know the flag', async () => {
    const hermes = executable(
      path.join(tempDir(), 'hermes'),
      '#!/bin/sh\necho "unknown option" >&2\nexit 2\n',
    );
    expect(await printedRuntimeCommand(hermes, { PATH: '/usr/bin:/bin' })).toBeNull();
  });
});
