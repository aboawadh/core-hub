/**
 * How the hub runs a short Python program on Hermes's own interpreter and packages — the
 * live model lists, a signed-in provider's list, the pending-write approvals (`HermesPython`).
 *
 * The image's Hermes is a venv with `python` beside `hermes`, and that was the only place
 * looked. A Hermes somebody installed with Hermes's installer is not laid out so (checked on
 * a real install, 2026-09-27):
 *
 *   older installers   ~/.local/bin/hermes is a shell launcher: `exec "<venv>/bin/python" …`
 *                      (older still: a symlink to `<venv>/bin/hermes`)
 *   current installer  ~/.local/bin/hermes → ~/.hermes/hermes-agent/.hermes/bin/hermes, which
 *                      runs Hermes's own Python with `-I` and its bootstrap; the packages live
 *                      in a dependency environment Hermes selects at start (`pm/`), not in a
 *                      venv beside the program
 *
 * so the hub refused those features on a desktop in local mode. In order, the interpreter is:
 *   1. `python` beside the program (the image);
 *   2. `python` beside the program the launcher links to;
 *   3. the venv `python` a shell launcher `exec`s (a folder with `pyvenv.cfg` above it only);
 *   4. what Hermes itself answers to `hermes --print-runtime-command` — its documented way for a
 *      program holding the launcher to run Hermes's Python the way Hermes does — with the
 *      program to run in place of Hermes's own module, after Hermes's bootstrap.
 * None of these: `null`, and the features say so as before.
 */
import { spawn } from 'node:child_process';
import { existsSync, readFileSync, realpathSync, statSync } from 'node:fs';
import path from 'node:path';

/** `spawn(command, [...args, program, ...argv])`: the program text, then its arguments. */
export interface PythonCommand {
  command: string;
  args: string[];
}

function isFile(file: string): boolean {
  try {
    return statSync(file).isFile();
  } catch {
    return false;
  }
}

/** A `python` in a venv's `bin` (or `Scripts`): `pyvenv.cfg` one folder up. */
function inVenv(python: string): boolean {
  return isFile(python) && isFile(path.join(path.dirname(path.dirname(python)), 'pyvenv.cfg'));
}

/** Steps 1–3: an interpreter file that carries Hermes's packages itself. */
export function venvPythonOf(hermes: string): string | null {
  const beside = path.join(path.dirname(hermes), 'python');
  if (existsSync(beside)) return beside;
  let real: string | null;
  try {
    real = realpathSync(hermes);
  } catch {
    real = null;
  }
  if (real && real !== hermes) {
    const linked = path.join(path.dirname(real), 'python');
    if (existsSync(linked)) return linked;
  }
  // A shell launcher is a few lines; anything bigger is not one.
  try {
    const file = real ?? hermes;
    if (statSync(file).size > 16_384) return null;
    const text = readFileSync(file, 'utf8');
    if (!text.startsWith('#!')) return null;
    for (const match of text.matchAll(/exec\s+"?([^"\s]+\/python[0-9.]*)"?/g)) {
      if (inVenv(match[1]!)) return match[1]!;
    }
  } catch {
    return null;
  }
  return null;
}

const BOOTSTRAP = 'import hermes_bootstrap; ';

/**
 * Step 4's answer made into a way to run any program: Hermes's own interpreter and its prefix
 * (the environment clean-up, the checkout on `sys.path`, `hermes_bootstrap`, which selects the
 * packages), then the program from `sys.argv[1]` in place of Hermes's module. Null for anything
 * that is not the shape Hermes prints.
 */
export function wrapRuntimeCommand(printed: unknown): PythonCommand | null {
  if (!Array.isArray(printed) || printed.length < 4) return null;
  if (!printed.every((part) => typeof part === 'string')) return null;
  const [python, isolated, dashC, code] = printed as string[];
  if (isolated !== '-I' || dashC !== '-c' || !python || !code) return null;
  const at = code.indexOf(BOOTSTRAP);
  if (at < 0) return null;
  const prefix = code.slice(0, at + BOOTSTRAP.length);
  const run =
    'import sys as _corehub_sys; _corehub_code = _corehub_sys.argv.pop(1); ' +
    "exec(compile(_corehub_code, '<corehub>', 'exec'), {'__name__': '__main__'})";
  return { command: python, args: ['-I', '-c', `${prefix}${run}`] };
}

/** Asks `hermes --print-runtime-command`; null when it cannot say (an older Hermes). */
export function printedRuntimeCommand(
  hermes: string,
  env: NodeJS.ProcessEnv,
  timeoutMs = 15_000,
): Promise<PythonCommand | null> {
  return new Promise((resolve) => {
    let stdout = '';
    const child = spawn(hermes, ['--print-runtime-command'], {
      env,
      stdio: ['ignore', 'pipe', 'ignore'],
      windowsHide: true,
    });
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    timer.unref?.();
    child.stdout?.on('data', (chunk: Buffer) => {
      if (stdout.length < 100_000) stdout += chunk.toString('utf8');
    });
    child.once('error', () => {
      clearTimeout(timer);
      resolve(null);
    });
    child.once('close', (code) => {
      clearTimeout(timer);
      if (code !== 0) return resolve(null);
      const line = stdout.trim().split('\n').at(-1) ?? '';
      try {
        resolve(wrapRuntimeCommand(JSON.parse(line) as unknown));
      } catch {
        resolve(null);
      }
    });
  });
}

/** Steps 1–4. Never throws. */
export async function resolveHermesPython(
  hermes: string,
  env: NodeJS.ProcessEnv,
): Promise<PythonCommand | null> {
  const venv = venvPythonOf(hermes);
  if (venv) return { command: venv, args: ['-c'] };
  return printedRuntimeCommand(hermes, env);
}
