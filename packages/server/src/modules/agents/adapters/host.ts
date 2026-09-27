/**
 * Host probing for the adapters: find a binary on PATH, ask it for its version, and call
 * an HTTP endpoint with a deadline.
 *
 * Everything here uses argv arrays — no shell string is ever built (AGENTS.md hard rules),
 * so an agent name from the registry cannot become a command injection.
 */
import { execFile, spawn } from 'node:child_process';
import { accessSync, constants } from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);

/**
 * Where to look for a binary, and what a spawned agent inherits. Always injected: the
 * hub's configuration file is the only place allowed to read the environment, and a unit
 * test enforces that.
 */
export interface HostEnvironment {
  /** `PATH` as the adapters should search it; tests point it at a fixture directory. */
  pathValue: string | undefined;
  /** Windows needs the extension list; harmless elsewhere. */
  pathExt?: string | undefined;
  /** The environment a spawned agent process inherits. */
  inherited?: NodeJS.ProcessEnv | undefined;
}

/** First executable named `binary` on PATH, or null. Never throws. */
export function whichSync(binary: string, host: HostEnvironment): string | null {
  if (binary.includes('/') || binary.includes('\\')) {
    return isExecutable(binary) ? binary : null;
  }
  const extensions = (host.pathExt ?? '').split(path.delimiter).filter(Boolean);
  const candidates = extensions.length > 0 ? ['', ...extensions] : [''];
  for (const dir of (host.pathValue ?? '').split(path.delimiter).filter(Boolean)) {
    for (const extension of candidates) {
      const candidate = path.join(dir, binary + extension);
      if (isExecutable(candidate)) return candidate;
    }
  }
  return null;
}

function isExecutable(file: string): boolean {
  try {
    accessSync(file, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

export interface CommandResult {
  ok: boolean;
  stdout: string;
  stderr: string;
  error: string | null;
}

/** Runs argv with a deadline. Failure is a value, never an exception. */
export async function runCommand(
  argv: readonly string[],
  options: { timeoutMs?: number; env?: NodeJS.ProcessEnv; cwd?: string } = {},
): Promise<CommandResult> {
  const [command, ...args] = argv;
  if (!command) return { ok: false, stdout: '', stderr: '', error: 'empty command' };
  try {
    const { stdout, stderr } = await run(command, args, {
      timeout: options.timeoutMs ?? 5_000,
      maxBuffer: 1024 * 1024,
      ...(options.env ? { env: options.env } : {}),
      ...(options.cwd ? { cwd: options.cwd } : {}),
    });
    return { ok: true, stdout: String(stdout), stderr: String(stderr), error: null };
  } catch (error) {
    const failure = error as { stdout?: string; stderr?: string; message?: string };
    return {
      ok: false,
      stdout: String(failure.stdout ?? ''),
      stderr: String(failure.stderr ?? ''),
      error: failure.message ?? 'command failed',
    };
  }
}

/**
 * Pulls a version out of `--version` output: agents print anything from `1.2.3` to
 * `claude-code/2.1.0 (darwin-arm64)` and `Hermes Agent v0.21.5+3397.gd25bbd0 (2026.9.24)`.
 */
export function parseVersion(output: string): string | null {
  // A `v` glued to the number (`Hermes Agent v0.21.5`) is not a word boundary: without the
  // first branch that line read as `21.5`.
  const match = /(?:\bv(?=\d)|\b)(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)\b/.exec(output);
  return match?.[1] ?? null;
}

export interface VersionReading {
  version: string | null;
  /** Why no version was read; null when one was. */
  error: string | null;
}

/**
 * Asks a program for its version and stops listening as soon as a line carries one.
 *
 * `runCommand` waits for the program to finish, and some do more than print: `hermes
 * --version` prints its version first and then checks for updates over git and the network,
 * synchronously (`hermes_cli/_startup_fast.py`), which on a cold start can outlast any short
 * deadline — and then the version it had already printed was thrown away with the "Command
 * failed" (the desktop report of 2026-09-27). Here the first complete line with a version
 * ends the read and the process is stopped; the deadline only matters when no version comes.
 * `PYTHONUNBUFFERED=1` is added to the given environment so a Python program's lines are not
 * held in its buffer until it exits.
 */
export function readVersion(
  argv: readonly string[],
  options: { timeoutMs?: number; env?: NodeJS.ProcessEnv } = {},
): Promise<VersionReading> {
  const [command, ...args] = argv;
  if (!command) return Promise.resolve({ version: null, error: 'empty command' });
  const timeoutMs = options.timeoutMs ?? 15_000;
  const shown = argv.join(' ');
  return new Promise((resolve) => {
    let settled = false;
    // What was printed, stdout first as the version is read from there first (as before).
    const out = { text: '', pending: '' };
    const err = { text: '', pending: '' };
    const all = () => `${out.text}${out.pending}\n${err.text}${err.pending}`;
    const child = spawn(command, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      // No environment given: the child inherits the hub's as it is (nothing added).
      ...(options.env ? { env: { ...options.env, PYTHONUNBUFFERED: '1' } } : {}),
      windowsHide: true,
    });
    const finish = (reading: VersionReading) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM');
      resolve(reading);
    };
    const timer = setTimeout(() => {
      const version = parseVersion(all());
      finish({
        version,
        error: version
          ? null
          : `${shown} did not print a version within ${Math.round(timeoutMs / 1000)} s`,
      });
    }, timeoutMs);
    // A complete line on stdout with a version ends the read; stderr (warnings, a traceback)
    // is kept for the end, so a number in a warning is never taken for the version.
    const take =
      (buffer: { text: string; pending: string }, early: boolean) => (chunk: Buffer | string) => {
        buffer.pending += String(chunk);
        const lines = buffer.pending.split(/\r?\n/);
        buffer.pending = lines.pop() ?? '';
        for (const line of lines) {
          buffer.text += `${line}\n`;
          const version = early ? parseVersion(line) : null;
          if (version) {
            finish({ version, error: null });
            return;
          }
        }
      };
    child.stdout?.on('data', take(out, true));
    child.stderr?.on('data', take(err, false));
    child.once('error', (error) => finish({ version: null, error: `${shown}: ${error.message}` }));
    child.once('close', (code, signal) => {
      const version = parseVersion(all());
      if (version) return finish({ version, error: null });
      const last = all()
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)
        .at(-1);
      finish({
        version: null,
        error: `${shown} ${signal ? `stopped (${signal})` : `exited with code ${code ?? '?'}`}${
          last ? `: ${last}` : ' without printing a version'
        }`,
      });
    });
  });
}

export interface HttpProbe {
  ok: boolean;
  status: number | null;
  body: unknown;
  error: string | null;
}

/** GET with a deadline. Used to ask a Hermes gateway whether it is up. */
export async function probeHttp(
  url: string,
  options: { timeoutMs?: number; fetchImpl?: typeof fetch; headers?: Record<string, string> } = {},
): Promise<HttpProbe> {
  const doFetch = options.fetchImpl ?? globalThis.fetch;
  try {
    const response = await doFetch(url, {
      method: 'GET',
      signal: AbortSignal.timeout(options.timeoutMs ?? 2_000),
      ...(options.headers ? { headers: options.headers } : {}),
    });
    const text = await response.text();
    let body: unknown = text;
    try {
      body = JSON.parse(text) as unknown;
    } catch {
      // A non-JSON body is still a sign of life; keep the text.
    }
    return { ok: response.ok, status: response.status, body, error: null };
  } catch (error) {
    return {
      ok: false,
      status: null,
      body: null,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}
