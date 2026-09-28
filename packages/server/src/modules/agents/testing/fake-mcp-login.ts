/**
 * Hermes's `hermes mcp login <server>` played in memory (DECISIONS §122), for the route tests and
 * the e2e hub — what the real command does, observed against v2026.9.14 (`mcp-oauth.real.test.ts`
 * runs the real one): it reads the server's block from the profile's `config.yaml` (refusing one
 * without `auth: oauth`), prints the provider's page, waits on `127.0.0.1:<oauth.redirect_port>`
 * for the browser's callback, and — the code and the `state` accepted — writes the tokens into
 * the profile's `mcp-tokens/` and says "Authenticated — N tool(s) available".
 *
 * `authorize` builds the provider's page from the redirect URI and the state; the e2e hub's
 * "provider" is the redirect itself, so a browser opening it lands on the hub's callback at once.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import path from 'node:path';
import { parse } from 'yaml';
import type { McpLoginProcess, McpLoginSpawner } from '../mcp-oauth.js';

export interface FakeMcpLoginOptions {
  authorize?: (redirectUri: string, state: string) => string;
  /** The code the callback must carry. */
  code?: string;
  /** What Hermes says when the exchange fails, instead of signing in. */
  failWith?: string;
  /** How many tools the signed-in server lists. */
  tools?: number;
  /** Token values written (for the no-leak test to look for). */
  accessToken?: string;
  refreshToken?: string;
  /** Every login started: its argv and the query its listener received. */
  calls?: Array<{ home: string; argv: string[]; query?: string }>;
}

export function fakeMcpLogin(options: FakeMcpLoginOptions = {}): McpLoginSpawner {
  let started = 0;
  return (home, argv) => {
    started += 1;
    const call: { home: string; argv: string[]; query?: string } = { home, argv };
    options.calls?.push(call);
    let output = '';
    let alive = true;
    let finish: () => void = () => undefined;
    const exited = new Promise<void>((resolve) => {
      finish = () => {
        alive = false;
        resolve();
      };
    });
    const say = (line: string) => {
      output += `${line}\n`;
    };
    const name = argv[2] ?? '';
    const config = (parse(readFileSync(path.join(home, 'config.yaml'), 'utf8')) ?? {}) as {
      mcp_servers?: Record<
        string,
        { auth?: string; oauth?: { redirect_uri?: string; redirect_port?: number } }
      >;
    };
    const block = config.mcp_servers?.[name];
    if (!block || block.auth !== 'oauth') {
      say(`✗ Server '${name}' is not configured for OAuth (auth=${block?.auth ?? 'None'})`);
      finish();
      return { output: () => output, running: () => alive, exited, kill: () => undefined };
    }
    const redirect = block.oauth?.redirect_uri ?? '';
    const port = Number(block.oauth?.redirect_port ?? 0);
    const state = `state-${started}`;
    const code = options.code ?? 'code-1';
    const server = createServer((request, response) => {
      const url = new URL(request.url ?? '/', 'http://127.0.0.1');
      call.query = url.search.slice(1);
      response.writeHead(200, { 'content-type': 'text/html' });
      response.end('<h2>Authorization Successful</h2>');
      server.close();
      const q = url.searchParams;
      if (q.get('error'))
        say(`✗ Authentication failed: OAuth authorization failed: ${q.get('error')}`);
      else if (q.get('state') !== state || q.get('code') !== code) {
        say('✗ Authentication failed: OAuth callback state mismatch');
      } else if (options.failWith) say(`✗ Authentication failed: ${options.failWith}`);
      else {
        const dir = path.join(home, 'mcp-tokens');
        mkdirSync(dir, { recursive: true });
        writeFileSync(
          path.join(dir, `${name.replace(/[^\w-]/g, '_')}.json`),
          JSON.stringify({
            access_token: options.accessToken ?? 'fake-access',
            refresh_token: options.refreshToken ?? 'fake-refresh',
            token_type: 'Bearer',
            expires_at: Date.now() / 1000 + 3600,
          }),
        );
        say(`✓ Authenticated — ${options.tools ?? 3} tool(s) available`);
      }
      finish();
    });
    server.listen(port, '127.0.0.1', () => {
      const page = options.authorize
        ? options.authorize(redirect, state)
        : `https://auth.example/authorize?client_id=c1&redirect_uri=${encodeURIComponent(redirect)}&state=${state}`;
      say('  MCP OAuth: authorization required.');
      say('  Open this URL in your browser:');
      say('');
      say(`    ${page}`);
    });
    const process: McpLoginProcess = {
      output: () => output,
      running: () => alive,
      exited,
      kill: () => {
        if (!alive) return;
        server.close();
        finish();
      },
    };
    return process;
  };
}
