/**
 * The pure half of MCP OAuth (DECISIONS §122): what the token file's metadata says, what the
 * hub deletes, where the browser is sent back to, and how Hermes's flow reads in the contract.
 */
import { mkdirSync, mkdtempSync, readdirSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { HubError } from '../../lib/errors.js';
import {
  McpOAuthFlows,
  FLOW_TTL_MS,
  authorizationUrlIn,
  callbackPage,
  callbackUri,
  failureIn,
  hubBaseOf,
  isHubCallback,
  oauthStateOf,
  removeOAuthTokens,
  signedInIn,
  stateOf,
  tokenFileStem,
  viewOfLogin,
  type McpLoginProcess,
  type McpOAuthFlowRecord,
} from './mcp-oauth.js';

const homes: string[] = [];
function home(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'corehub-mcp-oauth-'));
  homes.push(dir);
  return dir;
}
afterEach(() => {
  for (const dir of homes.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function tokens(dir: string, stem: string, data: unknown, suffix = '.json'): string {
  mkdirSync(path.join(dir, 'mcp-tokens'), { recursive: true });
  const file = path.join(dir, 'mcp-tokens', `${stem}${suffix}`);
  writeFileSync(file, typeof data === 'string' ? data : JSON.stringify(data));
  return file;
}

const NOW = Date.parse('2026-09-27T12:00:00Z');

describe('the file name Hermes gives a sign-in', () => {
  it('replaces what is not a word character or a dash, and trims the underscores', () => {
    expect(tokenFileStem('clickup')).toBe('clickup');
    expect(tokenFileStem('click.up')).toBe('click_up');
    expect(tokenFileStem('.hidden.')).toBe('hidden');
    expect(tokenFileStem('...')).toBe('default');
    expect(tokenFileStem('a'.repeat(200))).toHaveLength(128);
  });
});

describe('how a sign-in stands, from the metadata only', () => {
  it('is not connected without a token file', () => {
    expect(oauthStateOf(home(), 'clickup', true, NOW)).toEqual({
      required: true,
      status: 'not_connected',
      expires_at: null,
    });
  });

  it('is connected while the token has not run out, and says until when', () => {
    const dir = home();
    tokens(dir, 'clickup', {
      access_token: 'at-secret',
      token_type: 'Bearer',
      expires_at: NOW / 1000 + 3600,
    });
    expect(oauthStateOf(dir, 'clickup', false, NOW)).toEqual({
      required: false,
      status: 'connected',
      expires_at: '2026-09-27T13:00:00.000Z',
    });
  });

  it('is expired once the token ran out with nothing to renew it, connected when it can renew', () => {
    const dir = home();
    tokens(dir, 'old', { access_token: 'at-secret', expires_at: NOW / 1000 - 60 });
    tokens(dir, 'renews', {
      access_token: 'at-secret',
      refresh_token: 'rt-secret',
      expires_at: NOW / 1000 - 60,
    });
    expect(oauthStateOf(dir, 'old', true, NOW).status).toBe('expired');
    expect(oauthStateOf(dir, 'renews', true, NOW).status).toBe('connected');
  });

  it('reads `expires_in` from the time the file was written when there is no `expires_at`', () => {
    const dir = home();
    const file = tokens(dir, 'legacy', { access_token: 'at-secret', expires_in: 600 });
    const written = new Date(NOW - 3600_000);
    utimesSync(file, written, written);
    expect(oauthStateOf(dir, 'legacy', true, NOW)).toEqual({
      required: true,
      status: 'expired',
      expires_at: new Date(NOW - 3000_000).toISOString(),
    });
  });

  it('is an error when the file cannot be read as a sign-in', () => {
    const dir = home();
    tokens(dir, 'broken', '{not json');
    tokens(dir, 'empty', { token_type: 'Bearer' });
    expect(oauthStateOf(dir, 'broken', true, NOW).status).toBe('error');
    expect(oauthStateOf(dir, 'empty', true, NOW).status).toBe('error');
  });

  it('never carries a value of the file', () => {
    const dir = home();
    tokens(dir, 'clickup', {
      access_token: 'at-secret',
      refresh_token: 'rt-secret',
      expires_at: NOW / 1000 + 60,
    });
    expect(JSON.stringify(oauthStateOf(dir, 'clickup', true, NOW))).not.toMatch(/secret/);
  });
});

describe('forgetting a sign-in', () => {
  it("deletes that server's four files and nothing else", () => {
    const dir = home();
    for (const suffix of ['.json', '.client.json', '.meta.json', '.cimd-off']) {
      tokens(dir, 'click_up', {}, suffix);
    }
    tokens(dir, 'other', {});
    expect(removeOAuthTokens(dir, 'click.up')).toBe(true);
    expect(readdirSync(path.join(dir, 'mcp-tokens'))).toEqual(['other.json']);
    expect(removeOAuthTokens(dir, 'click.up')).toBe(false);
  });
});

describe('where the browser comes back to', () => {
  it("prefers the address the client reaches the hub on, else the request's", () => {
    expect(hubBaseOf('https://hub.example/', 'http://127.0.0.1:8080')).toBe('https://hub.example');
    expect(hubBaseOf('https://tunnel.example/hub/', 'http://x')).toBe('https://tunnel.example/hub');
    expect(hubBaseOf(undefined, 'https://proxy.example')).toBe('https://proxy.example');
    expect(hubBaseOf('  ', 'https://proxy.example/')).toBe('https://proxy.example');
  });

  it('refuses an address that is not http(s) or carries credentials', () => {
    for (const bad of [
      'javascript:alert(1)',
      'ftp://hub.example',
      'https://u:p@hub.example',
      'x',
    ]) {
      expect(() => hubBaseOf(bad, 'http://x')).toThrow(HubError);
    }
  });

  it('builds the callback under the API base and recognises its own on any host', () => {
    const uri = callbackUri('https://hub.example', '/api/v1', 'click.up');
    expect(uri).toBe('https://hub.example/api/v1/mcp-oauth/callback/click.up');
    expect(
      isHubCallback(
        'http://192.168.1.5:8080/api/v1/mcp-oauth/callback/click.up',
        '/api/v1',
        'click.up',
      ),
    ).toBe(true);
    expect(isHubCallback('https://my-proxy.example/oauth/callback', '/api/v1', 'click.up')).toBe(
      false,
    );
    expect(
      isHubCallback('https://hub.example/api/v1/mcp-oauth/callback/other', '/api/v1', 'click.up'),
    ).toBe(false);
  });
});

/** What `hermes mcp login` printed against a real OAuth server (v2026.9.14), abridged. */
const PRINTED = `
  Starting OAuth flow for 'clickup'...

  MCP OAuth: authorization required.
  Open this URL in your browser:

    http://127.0.0.1:45619/authorize?response_type=code&client_id=client-1&redirect_uri=https%3A%2F%2Fhub.example%2Fapi%2Fv1%2Fmcp-oauth%2Fcallback%2Fclickup&state=TirSYaqL2Af&code_challenge=pbft&code_challenge_method=S256

  (Headless environment detected — open the URL manually.)

  Or paste the redirect URL here (or the \`\`?code=...&state=...\`\` portion) and press Enter.
`;

function fakeProcess(output: string, running = false): McpLoginProcess {
  return {
    output: () => output,
    running: () => running,
    exited: Promise.resolve(),
    kill: () => undefined,
  };
}

describe("reading Hermes's login", () => {
  it('finds the provider’s page, the sign-in and the reason it failed', () => {
    expect(authorizationUrlIn(PRINTED)).toMatch(
      /^http:\/\/127\.0\.0\.1:45619\/authorize\?.*state=TirSYaqL2Af/,
    );
    expect(authorizationUrlIn('  Starting OAuth flow...')).toBeNull();
    expect(signedInIn(`${PRINTED}\n  ✓ Authenticated — 3 tool(s) available\n`)).toEqual({
      tools: 3,
    });
    expect(signedInIn('  ✓ Authenticated (server reported no tools)')).toEqual({ tools: null });
    expect(signedInIn(PRINTED)).toBeNull();
    expect(
      failureIn(
        `${PRINTED}\n  ✗ Authentication failed: Token exchange failed (400): invalid_grant\n`,
      ),
    ).toBe('Authentication failed: Token exchange failed (400): invalid_grant');
    expect(failureIn('')).toBe('Hermes ended the sign-in without a token');
  });

  it('says approved only with Hermes’s word and a token in the profile’s home', () => {
    const dir = home();
    const record = (output: string, running = false): McpOAuthFlowRecord => ({
      id: 'F1',
      agentId: 'A',
      profile: 'default',
      home: dir,
      serverName: 'clickup',
      port: 1,
      redirectUri: 'https://hub.example/cb',
      createdAt: 0,
      state: 's',
      authorizationUrl: 'u',
      process: fakeProcess(output, running),
      cancelled: false,
      tools: [],
      toolsAsked: null,
    });
    expect(viewOfLogin(record(PRINTED, true)).status).toBe('pending');
    const signed = `${PRINTED}\n  ✓ Authenticated — 3 tool(s) available`;
    // Hermes's word without a token file is not a sign-in.
    expect(viewOfLogin(record(signed)).status).toBe('failed');
    tokens(dir, 'clickup', { access_token: 'at-secret', expires_at: NOW / 1000 + 36000000 });
    expect(viewOfLogin(record(signed)).status).toBe('approved');
    expect(
      viewOfLogin(record('  ✗ OAuth callback timed out — no authorization code received.')),
    ).toMatchObject({
      status: 'expired',
    });
    expect(viewOfLogin({ ...record(PRINTED, true), cancelled: true }).status).toBe('cancelled');
  });

  it('keeps a flow only for the agent, profile and server it was started in, for as long as Hermes waits', () => {
    let now = 0;
    const flows = new McpOAuthFlows(() => now);
    let killed = 0;
    const running = { ...fakeProcess('', true), kill: () => void (killed += 1) };
    flows.add({
      id: 'F1',
      agentId: 'A',
      profile: 'default',
      home: '/h',
      serverName: 'clickup',
      port: 1,
      redirectUri: 'https://hub.example/cb',
      createdAt: 0,
      state: 's-1',
      authorizationUrl: null,
      process: running,
      cancelled: false,
      tools: [],
      toolsAsked: null,
    });
    expect(flows.get('F1', 'A', 'default', 'clickup')).not.toBeNull();
    expect(flows.get('F1', 'A', 'work', 'clickup')).toBeNull();
    expect(flows.get('F1', 'A', 'default', 'other')).toBeNull();
    expect(flows.byState('clickup', 's-1')?.id).toBe('F1');
    expect(flows.byState('clickup', 's-2')).toBeNull();
    expect(flows.byState('other', 's-1')).toBeNull();
    now = FLOW_TTL_MS + 1;
    expect(flows.get('F1', 'A', 'default', 'clickup')).toBeNull();
    expect(killed).toBe(1);
  });

  it('extracts the state of the provider’s page', () => {
    expect(stateOf('https://a.example/authorize?client_id=c&state=s-1')).toBe('s-1');
    expect(stateOf(null)).toBeNull();
  });
});

describe('the callback', () => {
  it('answers a page in the language asked, naming the server safely', () => {
    const ar = callbackPage('connected', 'clickup', 'ar', { tools: 3 });
    expect(ar).toContain('dir="rtl"');
    expect(ar).toContain('كور هب');
    expect(ar).toContain('data-outcome="connected"');
    expect(ar).toContain('عدد الأدوات التي يعرضها: 3');
    const failed = callbackPage('failed', 'clickup', 'en', { error: 'invalid_grant <x>' });
    expect(failed).toContain('Sign-in did not finish');
    expect(failed).toContain('invalid_grant &#60;x&#62;');
    // Nothing on any page says the sign-in worked unless it is `connected`.
    for (const outcome of ['failed', 'pending', 'declined', 'expired'] as const) {
      expect(callbackPage(outcome, 'clickup', 'en')).not.toMatch(/Connected|received/i);
    }
    const en = callbackPage('declined', '<b>', 'en');
    expect(en).toContain('dir="ltr"');
    expect(en).not.toContain('<b>');
    expect(en).toContain('&#60;b&#62;');
    expect(en).toContain('Core Hub');
  });
});
