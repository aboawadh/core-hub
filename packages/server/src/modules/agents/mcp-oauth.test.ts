/**
 * The pure half of MCP OAuth (DECISIONS §122): what the token file's metadata says, what the
 * hub deletes, where the browser is sent back to, and how Hermes's flow reads in the contract.
 */
import { mkdirSync, mkdtempSync, readdirSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { HubError } from '../../lib/errors.js';
import { HermesDashboardRefusal, HermesDashboardUnavailable } from './hermes-dashboard.js';
import type { HermesApiCall } from './hermes-tools.js';
import {
  McpOAuthFlows,
  FLOW_TTL_MS,
  callbackPage,
  callbackUri,
  hubBaseOf,
  isHubCallback,
  oauthStateOf,
  relayCallback,
  removeOAuthTokens,
  tokenFileStem,
  viewOf,
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

describe("Hermes's flow in the contract's words", () => {
  it('maps each state', () => {
    expect(viewOf({ status: 'starting' }).status).toBe('pending');
    expect(
      viewOf({ status: 'authorization_required', authorization_url: 'https://a/x?state=s' }),
    ).toEqual({
      status: 'pending',
      authorization_url: 'https://a/x?state=s',
      error: null,
      tools: [],
    });
    expect(
      viewOf({ status: 'approved', tools: [{ name: 'get_tasks', description: '' }, { bad: 1 }] }),
    ).toMatchObject({ status: 'approved', tools: [{ name: 'get_tasks', description: null }] });
    expect(viewOf({ status: 'error', error: 'Cancelled by user' }).status).toBe('cancelled');
    expect(viewOf({ status: 'error', error: 'invalid_client' })).toMatchObject({
      status: 'failed',
      error: 'invalid_client',
    });
    expect(viewOf({ status: 'something-new' }).status).toBe('failed');
  });

  it('keeps a flow only for the agent, profile and server it was started in, and for as long as Hermes does', () => {
    let now = 0;
    const flows = new McpOAuthFlows(() => now);
    flows.add({
      id: 'F1',
      agentId: 'A',
      profile: 'default',
      serverName: 'clickup',
      hermesFlowId: 'h',
      redirectUri: 'https://hub.example/cb',
      createdAt: 0,
      last: { status: 'pending', authorization_url: null, error: null, tools: [] },
    });
    expect(flows.get('F1', 'A', 'default', 'clickup')).not.toBeNull();
    expect(flows.get('F1', 'A', 'work', 'clickup')).toBeNull();
    expect(flows.get('F1', 'A', 'default', 'other')).toBeNull();
    now = FLOW_TTL_MS + 1;
    expect(flows.get('F1', 'A', 'default', 'clickup')).toBeNull();
  });
});

describe('the callback', () => {
  it('hands the query to Hermes as it came and reads the answer', async () => {
    const calls: string[] = [];
    const api: HermesApiCall = async <T>(method: string, route: string): Promise<T> => {
      calls.push(`${method} ${route}`);
      return '<h1>ok</h1>' as T;
    };
    expect(await relayCallback(api, 'click.up', 'code=c1&state=s1')).toBe('received');
    expect(calls).toEqual(['GET /api/mcp/oauth/callback/click.up?code=c1&state=s1']);
    expect(await relayCallback(api, 'x', 'error=access_denied&state=s1')).toBe('declined');
  });

  it("says the flow ended, was refused or that there is no Hermes, without Hermes's error", async () => {
    const refuse =
      (status: number): HermesApiCall =>
      async () => {
        throw new HermesDashboardRefusal('GET /api/mcp/oauth/callback/x?code=c1', status, 'no');
      };
    expect(await relayCallback(refuse(404), 'x', 'code=c1&state=s')).toBe('expired');
    expect(await relayCallback(refuse(409), 'x', 'code=c1&state=s')).toBe('expired');
    expect(await relayCallback(refuse(400), 'x', 'code=c1&state=s')).toBe('declined');
    const gone: HermesApiCall = async () => {
      throw new HermesDashboardUnavailable('down');
    };
    expect(await relayCallback(gone, 'x', 'code=c1')).toBe('unavailable');
    expect(await relayCallback(null, 'x', 'code=c1')).toBe('unavailable');
  });

  it('answers a page in the language asked, naming the server safely', () => {
    const ar = callbackPage('received', 'clickup', 'ar');
    expect(ar).toContain('dir="rtl"');
    expect(ar).toContain('كور هب');
    expect(ar).toContain('data-outcome="received"');
    const en = callbackPage('declined', '<b>', 'en');
    expect(en).toContain('dir="ltr"');
    expect(en).not.toContain('<b>');
    expect(en).toContain('&#60;b&#62;');
    expect(en).toContain('Core Hub');
  });
});
