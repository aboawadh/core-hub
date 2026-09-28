/**
 * MCP OAuth end to end against **the real Hermes** (DECISIONS §122): the hub's own routes, the
 * real `hermes serve` the hub supervises, and a real OAuth 2.1 + MCP server
 * (`tests/fixtures/fake-oauth-mcp.ts`) — discovery, client registration, PKCE, the code
 * exchanged for tokens by Hermes, the bearer required on every MCP call. The "browser" follows
 * the provider's redirect to the hub's callback exactly as a person's browser would.
 *
 * Run it with a Hermes executable (CI installs the image's pinned tag with uv), or the image:
 *
 *   COREHUB_HERMES_BIN=/path/to/venv/bin/hermes pnpm --filter @corehub/server exec \
 *     vitest run --project unit src/modules/agents/mcp-oauth.real.test.ts
 *   COREHUB_HERMES_IMAGE=core-hub:local … (same)
 *
 * Without either it is skipped.
 */
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { userInfo } from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { authed, capturingLogger, signedInHub, type TestHub } from '../../../tests/unit/helpers.js';
import { startFakeOAuthMcp, type FakeOAuthMcp } from '../../../tests/fixtures/fake-oauth-mcp.js';
import { HermesDashboard, type DashboardSpawner } from './hermes-dashboard.js';
import type { SpawnedProcess } from './hermes-runtime.js';
import type { HermesApiCall } from './hermes-tools.js';

const bin = process.env.COREHUB_HERMES_BIN;
const image = process.env.COREHUB_HERMES_IMAGE;

/** `0.21.4` from `Hermes Agent v0.21.4 (2026.9.21)`, asked of the Hermes under test. */
function hermesVersion(): number[] {
  const out = bin
    ? execFileSync(bin, ['--version'], { encoding: 'utf8' })
    : image
      ? execFileSync(
          'docker',
          ['run', '--rm', '--entrypoint', '/opt/hermes/.venv/bin/hermes', image, '--version'],
          { encoding: 'utf8' },
        )
      : '';
  return (/v(\d+)\.(\d+)\.(\d+)/.exec(out) ?? ['', '0', '0', '0']).slice(1).map(Number);
}

/**
 * Hermes carries the provider's RFC 9207 `iss` through its dashboard callback from 0.21.4. CI sets
 * `COREHUB_EXPECT_OAUTH_CONNECT=1`, so an image pin older than that fails there instead of
 * quietly testing the refusal.
 */
const carriesIss = (() => {
  if (!bin && !image) return true;
  const [major = 0, minor = 0, patch = 0] = hermesVersion();
  return major > 0 || minor > 21 || (minor === 21 && patch >= 4);
})();

const healthy: typeof fetch = async () =>
  new Response('{"status":"ok"}', { status: 200, headers: { 'content-type': 'application/json' } });

interface Flow {
  id: string;
  status: string;
  authorization_url: string | null;
  redirect_uri: string;
  error: string | null;
  tools: Array<{ name: string }>;
}

describe.skipIf(!bin && !image)(
  'MCP OAuth against the real Hermes (set COREHUB_HERMES_BIN or COREHUB_HERMES_IMAGE)',
  () => {
    let hub: TestHub & { token: string };
    let dashboard: HermesDashboard;
    let provider: FakeOAuthMcp;
    let agent = '';
    let root = '';
    const containers: string[] = [];
    const captured = capturingLogger();
    const bodies: string[] = [];

    beforeAll(async () => {
      provider = await startFakeOAuthMcp();
      let api: HermesApiCall | null = null;
      hub = await signedInHub(
        {},
        {
          logger: captured.logger,
          agents: {
            adapterOptions: { hermes: { fetchImpl: healthy } },
            hermesApi: (...args) => api!(...args),
          },
        },
      );
      root = path.join(hub.dataDir, 'hermes');
      mkdirSync(path.join(root, 'profiles', 'work'), { recursive: true });
      const { uid, gid } = userInfo();
      const spawnImpl: DashboardSpawner | undefined = image
        ? (_command, args, options) => {
            const name = `corehub-mcp-oauth-real-${process.pid}-${containers.length}`;
            containers.push(name);
            return spawn(
              'docker',
              [
                'run',
                '--rm',
                '--name',
                name,
                '--network',
                'host',
                '--user',
                `${uid}:${gid}`,
                '-v',
                `${root}:${root}`,
                '-e',
                `HERMES_HOME=${root}`,
                '-e',
                'HOME=/tmp',
                '-e',
                'HERMES_DASHBOARD_SESSION_TOKEN',
                '--entrypoint',
                '/opt/hermes/.venv/bin/hermes',
                image,
                ...args,
              ],
              {
                stdio: ['ignore', 'pipe', 'pipe'],
                env: {
                  ...process.env,
                  HERMES_DASHBOARD_SESSION_TOKEN: options.env.HERMES_DASHBOARD_SESSION_TOKEN,
                },
              },
            ) as unknown as SpawnedProcess;
          }
        : undefined;
      dashboard = new HermesDashboard({
        host: {
          status: () => ({ mode: 'managed', home: root }),
          executable: () => bin ?? '/opt/hermes/.venv/bin/hermes',
          cliEnv: () => ({ PATH: process.env.PATH ?? '', HOME: path.join(hub.dataDir, 'home') }),
        },
        dataDir: hub.dataDir,
        log: captured.logger,
        ...(spawnImpl ? { spawnImpl } : {}),
        startTimeoutMs: 120_000,
      });
      api = (method, route, body, options) => dashboard.request(method, route, body, options);
      const list = await authed(hub, hub.token, { method: 'GET', url: '/api/v1/agents' });
      agent = (list.json() as { items: Array<{ id: string; kind: string }> }).items.find(
        (row) => row.kind === 'hermes',
      )!.id;
    }, 180_000);

    afterAll(async () => {
      await dashboard?.close();
      for (const name of containers) {
        try {
          execFileSync('docker', ['rm', '-f', name], { stdio: 'ignore' });
        } catch {
          // gone with --rm
        }
      }
      await provider?.close();
      await hub?.close();
    });

    const call = async (
      method: 'GET' | 'POST' | 'DELETE',
      url: string,
      profile: string,
      payload?: unknown,
    ) => {
      const res = await authed(hub, hub.token, {
        method,
        url,
        profile,
        ...(payload ? { payload } : {}),
      });
      bodies.push(res.body);
      return res;
    };

    /** A person's browser: open the provider's page, follow it back to the hub's callback. */
    async function browse(authorizationUrl: string): Promise<string> {
      const consent = await fetch(authorizationUrl, { redirect: 'manual' });
      const location = consent.headers.get('location');
      expect(location, `the provider refused: ${provider.events.join(' | ')}`).toBeTruthy();
      const back = new URL(location!);
      expect(back.pathname).toContain('/api/v1/mcp-oauth/callback/');
      const page = await hub.app.inject({
        method: 'GET',
        url: `${back.pathname}${back.search}`,
        headers: { 'accept-language': 'en' },
      });
      bodies.push(page.body);
      expect(page.statusCode).toBe(200);
      return page.body;
    }

    async function connect(profile: string, home: string) {
      writeFileSync(
        path.join(home, 'config.yaml'),
        [
          'mcp_servers:',
          '  clickup:',
          `    url: ${provider.url}`,
          // What the owner's tester wrote for ClickUp.
          '    connect_timeout: 600',
          '    skip_preflight: true',
          '',
        ].join('\n'),
      );
      const before = await call('GET', `/api/v1/agents/${agent}/mcp-servers`, profile);
      expect(before.json()).toMatchObject({
        items: [{ name: 'clickup', oauth: { status: 'not_connected' } }],
      });

      const started = await call(
        'POST',
        `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
        profile,
        { hub_url: 'http://hub.test' },
      );
      expect(started.statusCode, started.body).toBe(200);
      const flow = started.json() as Flow;
      expect(flow.status, flow.error ?? '').toBe('pending');
      expect(flow.redirect_uri).toBe('http://hub.test/api/v1/mcp-oauth/callback/clickup');

      const page = await browse(flow.authorization_url!);
      // The page speaks only once Hermes has the token, not when the code merely arrived.

      if (!carriesIss) {
        // Hermes before v2026.9.21 drops the provider's `iss`: the page says so and never
        // claims success, and the flow reads failed with the update named.
        expect(page).toContain('data-outcome="failed"');
        expect(page).toContain('v2026.9.21');
        const failed = (
          await call('GET', `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${flow.id}`, profile)
        ).json() as Flow;
        expect(failed.status).toBe('failed');
        expect(failed.error).toMatch(/missing iss/);
        return;
      }
      expect(page, `${provider.events.join(' | ')}`).toContain('data-outcome="connected"');
      const done = (
        await call('GET', `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${flow.id}`, profile)
      ).json() as Flow;
      expect(done.status, done.error ?? '').toBe('approved');
      expect(done.tools.map((tool) => tool.name).sort()).toEqual([
        'create_task',
        'get_tasks',
        'get_workspace_hierarchy',
      ]);
      expect(existsSync(path.join(home, 'mcp-tokens', 'clickup.json'))).toBe(true);
      expect(readFileSync(path.join(home, 'config.yaml'), 'utf8')).toMatch(/auth: oauth/);

      const after = await call('GET', `/api/v1/agents/${agent}/mcp-servers`, profile);
      expect(after.json()).toMatchObject({
        items: [{ name: 'clickup', oauth: { status: 'connected', required: true } }],
      });

      const test = await call('POST', `/api/v1/agents/${agent}/mcp-servers/clickup/test`, profile);
      expect(test.statusCode, test.body).toBe(200);
      expect(test.json()).toMatchObject({ ok: true, error: null });
      expect((test.json() as { tools: unknown[] }).tools).toHaveLength(3);
    }

    it('runs the Hermes the image pins, which carries `iss`', () => {
      if (process.env.COREHUB_EXPECT_OAUTH_CONNECT === '1') expect(carriesIss).toBe(true);
    });

    it('signs in to the default profile: Hermes exchanges the code, keeps the token in the root home, and Test lists the tools', async () => {
      await connect('default', root);
      if (carriesIss) expect(provider.events).toContain('token');
    }, 240_000);

    it('signs in to a named profile separately, into that profile’s own home', async () => {
      await call('POST', '/api/v1/profiles', 'default', { slug: 'work', name: 'Work' });
      const work = path.join(root, 'profiles', 'work');
      await connect('work', work);
    }, 240_000);

    it('never shows a token in any answer or log line', () => {
      const logs = captured.lines.map((line) => JSON.stringify(line)).join('\n');
      const answers = bodies.join('\n');
      if (carriesIss) expect(provider.issued.length).toBeGreaterThan(0);
      for (const secret of provider.issued) {
        expect(answers).not.toContain(secret);
        expect(logs).not.toContain(secret);
      }
    });
  },
);
