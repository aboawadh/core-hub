/**
 * Settings → Secrets (DECISIONS §125), over the real routes:
 *
 * - only the owner: an admin is refused the step-up, the list and the reveal (`403`);
 * - the password every time: no grant, a wrong grant, an ended grant, an expired grant or a grant
 *   of another sign-in session opens nothing (`403 step_up_required`); a new step-up ends the
 *   one before it; `auth.endStepUp` ends it at once;
 * - a wrong password is `401`, counts on the sign-in lockout of the address, and the sixth try
 *   is `429` — even with the right password;
 * - the list names a provider key, a channel variable, an MCP credential, an incoming webhook
 *   secret and an outgoing webhook's signing secret, never a value; a reveal answers one value;
 * - every step-up, list and reveal is an audit row, and no row and no log line holds a value;
 *   the answers are `Cache-Control: no-store`.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { like, or } from 'drizzle-orm';
import { afterEach, describe, expect, it } from 'vitest';
import { requireSqlite } from '../../src/lib/db.js';
import { auditEvents } from '../../src/modules/audit/schema.js';
import { overrideNotify } from '../../src/modules/notify/index.js';
import {
  TEST_ADMIN_PASSWORD,
  authed,
  capturingLogger,
  signedInHub,
  type TestHub,
} from './helpers.js';

type Hub = TestHub & { token: string; userId: string };

const PROVIDER_KEY = 'sk-openai-SECRETS-VIEW-0001';
const BOT_TOKEN = '123456789:AAHsecretsViewTelegramToken000000000';
const MCP_TOKEN = 'ghp_secretsViewMcpToken0001';
const ROUTE_SECRET = 'route-secret-secrets-view';
const SIGNING = 'whsec_secretsViewSigning0001';
const VALUES = [PROVIDER_KEY, BOT_TOKEN, MCP_TOKEN, ROUTE_SECRET, SIGNING, TEST_ADMIN_PASSWORD];

const cleanups: Array<() => Promise<void> | void> = [];
afterEach(async () => {
  while (cleanups.length > 0) await cleanups.pop()!();
});

async function setup(): Promise<{ h: Hub; lines: Record<string, unknown>[] }> {
  const { logger, lines } = capturingLogger();
  const h = await signedInHub(
    {},
    {
      logger,
      agents: {
        adapterOptions: {
          hermes: {
            // A gateway that answers its probe: the runtime is `external` and has a home.
            fetchImpl: async () =>
              new Response('{"status":"ok"}', {
                status: 200,
                headers: { 'content-type': 'application/json' },
              }),
            ensureProfile: async () => undefined,
          },
        },
      },
    },
  );
  cleanups.push(() => h.close());
  const home = path.join(h.dataDir, 'hermes');
  mkdirSync(home, { recursive: true });
  writeFileSync(
    path.join(home, '.env'),
    `TELEGRAM_BOT_TOKEN=${BOT_TOKEN}\nTELEGRAM_ALLOWED_USERS=42\nOPENAI_API_KEY=not-a-channel\n`,
  );
  writeFileSync(
    path.join(home, 'config.yaml'),
    [
      'mcp_servers:',
      '  github:',
      '    command: npx',
      '    env:',
      `      GITHUB_TOKEN: ${MCP_TOKEN}`,
      '      LOG_LEVEL: info',
      'platforms:',
      '  webhook:',
      '    enabled: false',
      '    extra:',
      '      routes:',
      '        deploys:',
      `          secret: ${ROUTE_SECRET}`,
      '          prompt: Deploy {x}',
      '',
    ].join('\n'),
  );
  // Asking for the agents settles the runtime, and with it Hermes's home.
  await authed(h, h.token, { method: 'GET', url: '/api/v1/agents' });
  const provider = await authed(h, h.token, {
    method: 'POST',
    url: '/api/v1/models/providers',
    payload: { preset: 'openai', label: 'OpenAI', kind: 'llm', api_key: PROVIDER_KEY },
  });
  expect(provider.statusCode, provider.body).toBe(201);
  overrideNotify({ resolveHost: async () => ['93.184.216.34'] });
  cleanups.push(() => overrideNotify({}));
  const hook = await authed(h, h.token, {
    method: 'POST',
    url: '/api/v1/notify/webhooks',
    payload: {
      name: 'CI',
      url: 'https://example.com/hook',
      events: ['run.failed'],
      profiles: [],
      enabled: true,
      secret: SIGNING,
      include_content: false,
      allow_private_network: false,
      max_retries: 3,
    },
  });
  expect(hook.statusCode, hook.body).toBe(201);
  return { h, lines };
}

const stepUp = (h: Hub, token: string, password: string) =>
  authed(h, token, {
    method: 'POST',
    url: '/api/v1/auth/step-up',
    payload: { password, purpose: 'secrets' },
  });
const list = (h: Hub, token: string, grant: string) =>
  authed(h, token, { method: 'POST', url: '/api/v1/secrets/list', payload: { grant } });
const reveal = (h: Hub, token: string, grant: string, id: string) =>
  authed(h, token, { method: 'POST', url: '/api/v1/secrets/reveal', payload: { grant, id } });

async function grantOf(h: Hub, token = h.token): Promise<string> {
  const res = await stepUp(h, token, TEST_ADMIN_PASSWORD);
  expect(res.statusCode, res.body).toBe(200);
  return (res.json() as { grant: string }).grant;
}

async function signIn(h: Hub, username: string, password: string): Promise<string> {
  const res = await h.app.inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    payload: { username, password },
  });
  expect(res.statusCode, res.body).toBe(200);
  return (res.json() as { access_token: string }).access_token;
}

function auditRows(h: Hub) {
  return requireSqlite(h.app.hub.database)
    .select()
    .from(auditEvents)
    .where(or(like(auditEvents.action, 'secrets.%'), like(auditEvents.action, 'auth.step_up%')))
    .all();
}

describe('Settings → Secrets (§125)', () => {
  it('lists every kind by name and reveals one value, with the password asked again', async () => {
    const { h } = await setup();
    const step = await stepUp(h, h.token, TEST_ADMIN_PASSWORD);
    expect(step.statusCode, step.body).toBe(200);
    expect(step.headers['cache-control']).toBe('no-store');
    const granted = step.json() as { grant: string; purpose: string; ttl_seconds: number };
    expect(granted).toMatchObject({ purpose: 'secrets', ttl_seconds: 300 });
    expect(granted.grant).toMatch(/^su_/);

    const listed = await list(h, h.token, granted.grant);
    expect(listed.statusCode, listed.body).toBe(200);
    expect(listed.headers['cache-control']).toBe('no-store');
    for (const value of VALUES) expect(listed.body).not.toContain(value);
    const items = (listed.json() as { items: Array<Record<string, string | null>> }).items;
    expect(items.map((item) => [item.kind, item.profile, item.label, item.name])).toEqual([
      // One key for the family: the chat, speech-to-text and text-to-speech rows share it.
      [
        'provider_key',
        null,
        'OpenAI · OpenAI — speech to text · OpenAI — text to speech',
        'openai',
      ],
      ['channel', 'default', 'Telegram', 'TELEGRAM_BOT_TOKEN'],
      ['mcp', 'default', 'github', 'env.GITHUB_TOKEN'],
      ['webhook_out', null, 'CI', 'signing_secret'],
      ['webhook_in', 'default', 'deploys', 'secret'],
    ]);

    const expected: Record<string, string> = {
      provider_key: PROVIDER_KEY,
      channel: BOT_TOKEN,
      mcp: MCP_TOKEN,
      webhook_out: SIGNING,
      webhook_in: ROUTE_SECRET,
    };
    for (const item of items) {
      const shown = await reveal(h, h.token, granted.grant, item.id!);
      expect(shown.statusCode, shown.body).toBe(200);
      expect(shown.headers['cache-control']).toBe('no-store');
      expect(shown.json()).toEqual({ id: item.id, value: expected[item.kind!] });
    }
    const unknown = await reveal(h, h.token, granted.grant, 'not-a-secret');
    expect(unknown.statusCode).toBe(404);
  });

  it('is the owner’s alone, from a web sign-in', async () => {
    const { h } = await setup();
    const made = await authed(h, h.token, {
      method: 'POST',
      url: '/api/v1/auth/users',
      payload: {
        username: 'amal',
        password: 'amal-password-1',
        role: 'admin',
        profiles: ['default'],
      },
    });
    expect(made.statusCode, made.body).toBe(201);
    const admin = await signIn(h, 'amal', 'amal-password-1');
    const refused = await stepUp(h, admin, 'amal-password-1');
    expect(refused.statusCode).toBe(403);
    expect(refused.json()).toMatchObject({
      code: 'forbidden',
      details: { required_role: 'owner' },
    });
    // The owner's grant does not open anything for the admin either.
    const grant = await grantOf(h);
    expect((await list(h, admin, grant)).statusCode).toBe(403);
    const items = (await list(h, h.token, grant)).json() as { items: Array<{ id: string }> };
    expect((await reveal(h, admin, grant, items.items[0]!.id)).statusCode).toBe(403);
    // An app token (a paired phone, an integration) is never a way in.
    const token = await authed(h, h.token, {
      method: 'POST',
      url: '/api/v1/auth/app-tokens',
      payload: { name: 'script', scopes: ['admin'] },
    });
    expect(token.statusCode, token.body).toBe(201);
    const plain = (token.json() as { token: string }).token;
    const viaToken = await stepUp(h, plain, TEST_ADMIN_PASSWORD);
    expect(viaToken.statusCode).toBe(403);
    expect(viaToken.json()).toMatchObject({ details: { reason: 'web_session_required' } });
  });

  it('needs a live grant of this session each time: new, ended and other grants open nothing', async () => {
    const { h } = await setup();
    const none = await list(h, h.token, 'su_made-up-grant-000000000000');
    expect(none.statusCode).toBe(403);
    expect(none.json()).toMatchObject({ details: { reason: 'step_up_required' } });

    const first = await grantOf(h);
    const second = await grantOf(h);
    // A new step-up ends the one before it.
    expect((await list(h, h.token, first)).statusCode).toBe(403);
    expect((await list(h, h.token, second)).statusCode).toBe(200);

    // Another sign-in of the same owner is another session: the grant is not its.
    const other = await signIn(h, 'admin', TEST_ADMIN_PASSWORD);
    expect((await list(h, other, second)).statusCode).toBe(403);

    // Leaving the page ends it at once.
    const ended = await authed(h, h.token, { method: 'DELETE', url: '/api/v1/auth/step-up' });
    expect(ended.statusCode).toBe(204);
    expect((await list(h, h.token, second)).statusCode).toBe(403);
  });

  it('ends a grant after five minutes', async () => {
    const { h } = await setup();
    const realNow = Date.now;
    cleanups.push(() => {
      Date.now = realNow;
    });
    const grant = await grantOf(h);
    expect((await list(h, h.token, grant)).statusCode).toBe(200);
    const start = realNow();
    Date.now = () => start + 5 * 60 * 1000 + 1_000;
    // The access token lives 15 minutes; the grant five.
    const late = await list(h, h.token, grant);
    expect(late.statusCode).toBe(403);
    expect(late.json()).toMatchObject({ details: { reason: 'step_up_required' } });
  });

  it('counts a wrong password on the sign-in lockout, and the sixth try is refused', async () => {
    const { h } = await setup();
    for (let i = 0; i < 5; i += 1) {
      const wrong = await stepUp(h, h.token, 'not-the-password');
      expect(wrong.statusCode).toBe(401);
      expect(wrong.json()).toMatchObject({ details: { reason: 'wrong_password' } });
    }
    const locked = await stepUp(h, h.token, TEST_ADMIN_PASSWORD);
    expect(locked.statusCode).toBe(429);
    expect(locked.headers['retry-after']).toBeDefined();
    // The same address cannot sign in either: one lockout for the password everywhere.
    const login = await h.app.inject({
      method: 'POST',
      url: '/api/v1/auth/login',
      payload: { username: 'admin', password: TEST_ADMIN_PASSWORD },
    });
    expect(login.statusCode).toBe(429);
    const failed = auditRows(h).filter((row) => row.action === 'auth.step_up_failed');
    expect(failed).toHaveLength(5);
  });

  it('writes who revealed which secret and when, and no value reaches a row or a log line', async () => {
    const { h, lines } = await setup();
    const grant = await grantOf(h);
    const items = (await list(h, h.token, grant)).json() as {
      items: Array<{ id: string; kind: string }>;
    };
    const channel = items.items.find((item) => item.kind === 'channel')!;
    await reveal(h, h.token, grant, channel.id);
    await stepUp(h, h.token, 'wrong-password-in-the-log?');

    const rows = auditRows(h);
    expect(rows.map((row) => row.action).sort()).toEqual(
      ['auth.step_up', 'auth.step_up_failed', 'secrets.listed', 'secrets.revealed'].sort(),
    );
    const revealed = rows.find((row) => row.action === 'secrets.revealed')!;
    expect(revealed.actorId).toBe(h.userId);
    expect(revealed.data).toMatchObject({
      kind: 'channel',
      profile: 'default',
      label: 'Telegram',
      name: 'TELEGRAM_BOT_TOKEN',
    });
    const written = JSON.stringify(rows);
    for (const value of [...VALUES, 'wrong-password-in-the-log?']) {
      expect(written).not.toContain(value);
      expect(JSON.stringify(lines)).not.toContain(value);
    }
  });
});
