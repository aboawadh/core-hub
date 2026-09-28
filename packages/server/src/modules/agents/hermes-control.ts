/**
 * Hermes's gateway control socket, the one verb the hub sends: `rescan-profiles`.
 *
 * A Hermes that runs one gateway per host (v2026.9.21, `0.21.4`, and later; `hermes-gateways.ts`)
 * serves every profile from the gateway started in the root home, and keeps that set in step with
 * `profiles/` itself: every 30 s it rebuilds a served profile whose `config.yaml` or `.env` changed
 * and starts serving a profile that appeared (`gateway/run_profile_reconcile.py`). Its control
 * socket takes `rescan-profiles` to do that at once — what `hermes profile create` sends — so a
 * channel switched on in a named profile answers now rather than half a minute later.
 *
 * Read from Hermes's MIT source (`gateway/control_socket.py`, v2026.9.24): one JSON line in, one
 * JSON line out. POSIX: `<home>/gateway.sock`, or the path in `<home>/gateway.sock.path` when the
 * home's path is too long for a socket; Windows: the named pipe `\\.\pipe\hermes-gateway-<hash>`,
 * the hash being the first 16 hex digits of SHA-256 over the home's resolved, case-folded path.
 *
 * Best effort by design: no socket, a timeout, an older Hermes without the verb — all answer
 * `null`, and Hermes's own rescan still follows within half a minute. Never throws.
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { connect } from 'node:net';
import path from 'node:path';

const SOCKET_FILE = 'gateway.sock';
const POINTER_FILE = 'gateway.sock.path';
const MAX_RESPONSE_BYTES = 512 * 1024;

/** Where a client reaches the gateway of `home`, or `null` when nothing listens there. */
export function controlSocketPath(
  home: string,
  platform: NodeJS.Platform = process.platform,
): string | null {
  if (platform === 'win32') {
    const normalized = path.win32.resolve(home).toLowerCase();
    const hash = createHash('sha256').update(normalized, 'utf8').digest('hex').slice(0, 16);
    return `\\\\.\\pipe\\hermes-gateway-${hash}`;
  }
  const direct = path.join(home, SOCKET_FILE);
  if (existsSync(direct)) return direct;
  try {
    const target = readFileSync(path.join(home, POINTER_FILE), 'utf8').trim();
    if (target && existsSync(target)) return target;
  } catch {
    // No pointer: no socket.
  }
  return null;
}

/** Sends one control verb to the gateway serving `home`; its `result`, or `null`. */
export function queryGatewayControl(
  home: string,
  verb: string,
  timeoutMs = 8_000,
): Promise<Record<string, unknown> | null> {
  const address = controlSocketPath(home);
  if (!address) return Promise.resolve(null);
  return new Promise((resolve) => {
    let settled = false;
    let received = '';
    const finish = (value: Record<string, unknown> | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      resolve(value);
    };
    const socket = connect(address);
    const timer = setTimeout(() => finish(null), timeoutMs);
    timer.unref?.();
    socket.setEncoding('utf8');
    socket.on('error', () => finish(null));
    socket.on('connect', () => {
      socket.write(`${JSON.stringify({ verb, id: 1, protocol: 1 })}\n`);
    });
    socket.on('data', (chunk: string) => {
      received += chunk;
      if (received.length > MAX_RESPONSE_BYTES) return finish(null);
      const newline = received.indexOf('\n');
      if (newline >= 0) finish(parseAnswer(received.slice(0, newline)));
    });
    socket.on('end', () => finish(parseAnswer(received)));
  });
}

function parseAnswer(line: string): Record<string, unknown> | null {
  try {
    const answer = JSON.parse(line) as { ok?: unknown; result?: unknown };
    return answer?.ok === true && answer.result && typeof answer.result === 'object'
      ? (answer.result as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/** Asks the gateway of `home` to reconcile its served profiles now (`rescan-profiles`). */
export function rescanGatewayProfiles(home: string): Promise<Record<string, unknown> | null> {
  return queryGatewayControl(home, 'rescan-profiles');
}
