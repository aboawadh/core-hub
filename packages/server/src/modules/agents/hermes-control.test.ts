/**
 * The one control verb the hub sends a Hermes gateway (`hermes-control.ts`): a socket in the
 * home, one JSON line each way, and `null` for anything that is not a clean answer.
 */
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { controlSocketPath, rescanGatewayProfiles } from './hermes-control.js';

const dirs: string[] = [];
const servers: Server[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => new Promise((done) => server.close(done))));
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function home(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'hc-'));
  dirs.push(dir);
  return dir;
}

/** A gateway's control socket answering each request line with `answer(request)`. */
async function listen(socketPath: string, answer: (request: Record<string, unknown>) => string) {
  const requests: Array<Record<string, unknown>> = [];
  const server = createServer((socket) => {
    let buffer = '';
    socket.on('data', (chunk) => {
      buffer += chunk.toString();
      const newline = buffer.indexOf('\n');
      if (newline < 0) return;
      const request = JSON.parse(buffer.slice(0, newline)) as Record<string, unknown>;
      requests.push(request);
      socket.end(answer(request));
    });
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(socketPath, resolve));
  return requests;
}

describe.skipIf(process.platform === 'win32')("Hermes's gateway control socket", () => {
  it('sends rescan-profiles as one JSON line and returns the result', async () => {
    const dir = home();
    const requests = await listen(
      path.join(dir, 'gateway.sock'),
      () =>
        `${JSON.stringify({ ok: true, protocol: 1, id: 1, result: { multiplex: true, served_profiles: ['default', 'sales'] } })}\n`,
    );
    expect(await rescanGatewayProfiles(dir)).toEqual({
      multiplex: true,
      served_profiles: ['default', 'sales'],
    });
    expect(requests).toEqual([{ verb: 'rescan-profiles', id: 1, protocol: 1 }]);
  });

  it('follows the pointer file of a home whose path is too long for a socket', async () => {
    const dir = home();
    const elsewhere = path.join(home(), 'gw.sock');
    writeFileSync(path.join(dir, 'gateway.sock.path'), `${elsewhere}\n`);
    await listen(elsewhere, () => `${JSON.stringify({ ok: true, result: { pending: true } })}\n`);
    expect(await rescanGatewayProfiles(dir)).toEqual({ pending: true });
  });

  it('answers null with no socket, an error, or an older gateway without the verb', async () => {
    expect(await rescanGatewayProfiles(home())).toBeNull();
    const dir = home();
    await listen(
      path.join(dir, 'gateway.sock'),
      () => `${JSON.stringify({ ok: false, error: "unknown verb: 'rescan-profiles'" })}\n`,
    );
    expect(await rescanGatewayProfiles(dir)).toBeNull();
  });
});

describe("the Windows gateway's named pipe", () => {
  it("is named by Hermes's hash of the home", () => {
    const dir = 'C:\\Users\\T\\AppData\\Roaming\\Core Hub\\hermes';
    const hash = createHash('sha256').update(dir.toLowerCase()).digest('hex').slice(0, 16);
    expect(controlSocketPath(dir, 'win32')).toBe(`\\\\.\\pipe\\hermes-gateway-${hash}`);
  });
});
