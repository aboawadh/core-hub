/**
 * MCP OAuth through the hub's own routes (DECISIONS §122), with a scripted Hermes that behaves
 * like Hermes's dashboard: `…/auth` starts a flow with an authorization URL, the callback
 * accepts only the flow's `state` and then writes the tokens into the profile's home, the flow
 * route reports `approved` with the tools. The last test is the promise that matters most: no
 * token value appears in any answer or any log line.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { authed, capturingLogger, signedInHub, type TestHub } from '../../../tests/unit/helpers.js';
import { HermesDashboardRefusal } from './hermes-dashboard.js';
import type { HermesApiCall } from './hermes-tools.js';

type Hub = TestHub & { token: string };
let hub: Hub | null = null;
afterEach(async () => {
  await hub?.close();
  hub = null;
});

const healthy: typeof fetch = async () =>
  new Response('{"status":"ok"}', { status: 200, headers: { 'content-type': 'application/json' } });

const ACCESS = 'at-9f8e7d6c5b4a-SECRET';
const REFRESH = 'rt-1a2b3c4d5e6f-SECRET';
const CLIENT_SECRET = 'cs-0011223344-SECRET';
const CODE = 'code-5566778899-SECRET';

interface FakeFlow {
  id: string;
  server: string;
  home: string;
  state: string;
  status: 'authorization_required' | 'approved' | 'error';
  error: string | null;
}

/** Hermes's dashboard, played in memory against the hub's data folder. */
function fakeHermes(root: string) {
  const calls: string[] = [];
  const flows = new Map<string, FakeFlow>();
  let next = 0;
  const homeOf = (profile: string) =>
    profile === 'default' ? root : path.join(root, 'profiles', profile);
  const snapshot = (flow: FakeFlow) => ({
    flow_id: flow.id,
    server_name: flow.server,
    status: flow.status,
    authorization_url: `https://auth.example/authorize?client_id=c1&state=${flow.state}`,
    error: flow.error,
  });
  const api: HermesApiCall = async <T>(method: string, route: string): Promise<T> => {
    calls.push(`${method} ${route}`);
    const url = new URL(route, 'http://hermes');
    const auth = /^\/api\/mcp\/servers\/([^/]+)\/auth$/.exec(url.pathname);
    if (method === 'POST' && auth) {
      next += 1;
      const flow: FakeFlow = {
        id: `hermes-flow-${next}`,
        server: decodeURIComponent(auth[1]!),
        home: homeOf(url.searchParams.get('profile') ?? 'default'),
        state: `state-${next}`,
        status: 'authorization_required',
        error: null,
      };
      flows.set(flow.id, flow);
      return snapshot(flow) as T;
    }
    const status = /^\/api\/mcp\/oauth\/flows\/([^/]+)$/.exec(url.pathname);
    if (status) {
      const flow = flows.get(decodeURIComponent(status[1]!));
      if (!flow)
        throw new HermesDashboardRefusal(`${method} ${route}`, 404, 'OAuth flow not found');
      if (method === 'DELETE') {
        if (flow.status !== 'approved') {
          flow.status = 'error';
          flow.error = 'Cancelled by user';
        }
        return { ok: true } as T;
      }
      return {
        ...snapshot(flow),
        tools:
          flow.status === 'approved'
            ? [
                { name: 'get_tasks', description: 'List tasks.' },
                { name: 'create_task', description: 'Create a task.' },
              ]
            : [],
      } as T;
    }
    const callback = /^\/api\/mcp\/oauth\/callback\/(.+)$/.exec(url.pathname);
    if (callback) {
      const flow = [...flows.values()].find(
        (each) =>
          each.server === decodeURIComponent(callback[1]!) &&
          each.status === 'authorization_required' &&
          each.state === url.searchParams.get('state'),
      );
      if (!flow) throw new HermesDashboardRefusal(`${method} ${route}`, 404, 'expired');
      if (url.searchParams.get('code') !== CODE) {
        throw new HermesDashboardRefusal(`${method} ${route}`, 400, 'bad code');
      }
      // What Hermes's worker writes once the provider exchanged the code.
      const dir = path.join(flow.home, 'mcp-tokens');
      mkdirSync(dir, { recursive: true });
      writeFileSync(
        path.join(dir, `${flow.server}.json`),
        JSON.stringify({
          access_token: ACCESS,
          refresh_token: REFRESH,
          token_type: 'Bearer',
          expires_at: Date.now() / 1000 + 3600,
        }),
      );
      writeFileSync(
        path.join(dir, `${flow.server}.client.json`),
        JSON.stringify({ client_id: 'c1', client_secret: CLIENT_SECRET }),
      );
      flow.status = 'approved';
      return '<h1>Authorization received</h1>' as T;
    }
    throw new Error(`unscripted ${method} ${route}`);
  };
  return { api, calls, flows };
}

async function boot(logger?: ReturnType<typeof capturingLogger>['logger']) {
  let hermes: ReturnType<typeof fakeHermes> | null = null;
  const api: HermesApiCall = (...args) => hermes!.api(...args);
  hub = await signedInHub(
    {},
    {
      ...(logger ? { logger } : {}),
      agents: { adapterOptions: { hermes: { fetchImpl: healthy } }, hermesApi: api },
    },
  );
  const root = path.join(hub.dataDir, 'hermes');
  hermes = fakeHermes(root);
  const list = await authed(hub, hub.token, { method: 'GET', url: '/api/v1/agents' });
  const agent = (list.json() as { items: Array<{ id: string; kind: string }> }).items.find(
    (row) => row.kind === 'hermes',
  )!.id;
  mkdirSync(root, { recursive: true });
  writeFileSync(
    path.join(root, 'config.yaml'),
    [
      '# the person’s own comment',
      'mcp_servers:',
      '  clickup:',
      '    url: https://mcp.clickup.example/mcp',
      '  local:',
      '    command: node',
      '',
    ].join('\n'),
  );
  return { hub, agent, root, hermes };
}

const servers = async (h: Hub, agent: string, profile = 'default') =>
  (
    (
      await authed(h, h.token, {
        method: 'GET',
        url: `/api/v1/agents/${agent}/mcp-servers`,
        profile,
      })
    ).json() as { items: Array<{ name: string; oauth?: { status: string; required: boolean } }> }
  ).items;

describe('connecting an MCP server by OAuth', () => {
  it('writes the callback the browser can reach, starts Hermes, relays the callback and reports approved', async () => {
    const { hub: h, agent, root, hermes } = await boot();
    expect((await servers(h, agent)).map((s) => [s.name, s.oauth?.status ?? null])).toEqual([
      ['clickup', 'not_connected'],
      ['local', null],
    ]);

    const started = await authed(h, h.token, {
      method: 'POST',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
      payload: { hub_url: 'https://tunnel.example' },
    });
    expect(started.statusCode, started.body).toBe(200);
    const flow = started.json() as {
      id: string;
      status: string;
      authorization_url: string;
      redirect_uri: string;
    };
    expect(flow).toMatchObject({
      status: 'pending',
      redirect_uri: 'https://tunnel.example/api/v1/mcp-oauth/callback/clickup',
      authorization_url: 'https://auth.example/authorize?client_id=c1&state=state-1',
    });
    expect(flow.id).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(hermes.calls).toEqual(['POST /api/mcp/servers/clickup/auth?profile=default']);
    // The redirect is in the server's block; the comment and the other server stay.
    const config = readFileSync(path.join(root, 'config.yaml'), 'utf8');
    expect(config).toContain('# the person’s own comment');
    expect(config).toContain(
      '    oauth:\n      redirect_uri: https://tunnel.example/api/v1/mcp-oauth/callback/clickup',
    );
    expect(config).toContain('  local:\n    command: node');

    const poll = () =>
      authed(h, h.token, {
        method: 'GET',
        url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${flow.id}`,
      });
    expect((await poll()).json()).toMatchObject({ status: 'pending' });

    // The provider sends the browser back: no session, the query handed to Hermes as it came.
    const back = await h.app.inject({
      method: 'GET',
      url: `/api/v1/mcp-oauth/callback/clickup?code=${CODE}&state=state-1`,
      headers: { 'accept-language': 'ar' },
    });
    expect(back.statusCode).toBe(200);
    expect(back.headers['content-type']).toContain('text/html');
    expect(back.body).toContain('data-outcome="received"');
    expect(back.body).toContain('dir="rtl"');
    expect(hermes.calls).toContain(
      `GET /api/mcp/oauth/callback/clickup?code=${CODE}&state=state-1`,
    );

    const done = (await poll()).json() as { status: string; tools: Array<{ name: string }> };
    expect(done.status).toBe('approved');
    expect(done.tools.map((tool) => tool.name)).toEqual(['get_tasks', 'create_task']);
    expect((await servers(h, agent))[0]).toMatchObject({
      name: 'clickup',
      oauth: { status: 'connected', required: false },
    });

    // Used once: the same callback again is an ended sign-in, said as such.
    const again = await h.app.inject({
      method: 'GET',
      url: `/api/v1/mcp-oauth/callback/clickup?code=${CODE}&state=state-1`,
    });
    expect(again.body).toContain('data-outcome="expired"');
  });

  it("uses the request's own address without `hub_url`, and keeps a redirect the person wrote", async () => {
    const { hub: h, agent, root } = await boot();
    const first = await authed(h, h.token, {
      method: 'POST',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
      headers: { host: 'hub.lan:8080' },
    });
    expect(first.statusCode, first.body).toBe(200);
    expect(first.json()).toMatchObject({
      redirect_uri: 'http://hub.lan:8080/api/v1/mcp-oauth/callback/clickup',
    });

    writeFileSync(
      path.join(root, 'config.yaml'),
      'mcp_servers:\n  clickup:\n    url: https://mcp.clickup.example/mcp\n    oauth:\n      redirect_uri: https://my-proxy.example/cb\n',
    );
    const second = await authed(h, h.token, {
      method: 'POST',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
      payload: { hub_url: 'https://tunnel.example' },
    });
    expect(second.json()).toMatchObject({ redirect_uri: 'https://my-proxy.example/cb' });
    expect(readFileSync(path.join(root, 'config.yaml'), 'utf8')).toContain(
      'redirect_uri: https://my-proxy.example/cb',
    );
  });

  it('refuses a stdio server, a bad hub_url, a missing server and a flow of another profile', async () => {
    const { hub: h, agent, root } = await boot();
    const start = (name: string, payload?: unknown, profile = 'default') =>
      authed(h, h.token, {
        method: 'POST',
        url: `/api/v1/agents/${agent}/mcp-servers/${name}/oauth`,
        profile,
        ...(payload ? { payload } : {}),
      });
    expect((await start('local')).json()).toMatchObject({
      code: 'conflict',
      details: { reason: 'mcp_oauth_stdio' },
    });
    expect((await start('clickup', { hub_url: 'javascript:alert(1)' })).statusCode).toBe(400);
    expect((await start('nope')).statusCode).toBe(404);

    const flow = (await start('clickup')).json() as { id: string };
    await authed(h, h.token, {
      method: 'POST',
      url: '/api/v1/profiles',
      payload: { slug: 'work', name: 'Work' },
    });
    mkdirSync(path.join(root, 'profiles', 'work'), { recursive: true });
    const elsewhere = await authed(h, h.token, {
      method: 'GET',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${flow.id}`,
      profile: 'work',
    });
    expect(elsewhere.statusCode).toBe(404);
    // Each profile signs in on its own: the other profile's list knows nothing of this one.
    writeFileSync(
      path.join(root, 'profiles', 'work', 'config.yaml'),
      'mcp_servers:\n  clickup:\n    url: https://mcp.clickup.example/mcp\n    auth: oauth\n',
    );
    expect((await servers(h, agent, 'work'))[0]).toMatchObject({
      oauth: { status: 'not_connected', required: true },
    });
  });

  it('cancels a sign-in through Hermes, and says expired once Hermes forgot it', async () => {
    const { hub: h, agent, hermes } = await boot();
    const flow = (
      await authed(h, h.token, {
        method: 'POST',
        url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
      })
    ).json() as { id: string };
    const cancelled = await authed(h, h.token, {
      method: 'DELETE',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${flow.id}`,
    });
    expect(cancelled.statusCode, cancelled.body).toBe(200);
    expect(cancelled.json()).toMatchObject({ status: 'cancelled' });
    expect(hermes.calls).toContain('DELETE /api/mcp/oauth/flows/hermes-flow-1');

    const second = (
      await authed(h, h.token, {
        method: 'POST',
        url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
      })
    ).json() as { id: string };
    hermes.flows.clear();
    const forgotten = await authed(h, h.token, {
      method: 'GET',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${second.id}`,
    });
    expect(forgotten.json()).toMatchObject({ status: 'expired', authorization_url: null });
  });

  it("disconnects by deleting that profile's sign-in files and says not connected", async () => {
    const { hub: h, agent, root } = await boot();
    const dir = path.join(root, 'mcp-tokens');
    mkdirSync(dir, { recursive: true });
    for (const suffix of ['.json', '.client.json', '.meta.json']) {
      writeFileSync(path.join(dir, `clickup${suffix}`), JSON.stringify({ access_token: ACCESS }));
    }
    expect((await servers(h, agent))[0]?.oauth?.status).toBe('connected');
    const res = await authed(h, h.token, {
      method: 'DELETE',
      url: `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`,
    });
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json()).toMatchObject({ name: 'clickup', oauth: { status: 'not_connected' } });
    expect((await servers(h, agent))[0]?.oauth?.status).toBe('not_connected');
  });

  it('answers the callback with a page even when there is no Hermes to hand it to', async () => {
    hub = await signedInHub();
    const res = await hub.app.inject({
      method: 'GET',
      url: '/api/v1/mcp-oauth/callback/clickup?code=x&state=y',
    });
    expect(res.statusCode).toBe(200);
    expect(res.body).toContain('data-outcome="unavailable"');
  });
});

describe('no token value leaves Hermes’s files', () => {
  it('appears in no answer and no log line, the callback’s code included', async () => {
    const captured = capturingLogger();
    const { hub: h, agent, root } = await boot(captured.logger);
    // A secret the person put in the block itself, one level down, is masked as well.
    writeFileSync(
      path.join(root, 'config.yaml'),
      `mcp_servers:\n  clickup:\n    url: https://mcp.clickup.example/mcp\n    headers:\n      Authorization: Bearer ${ACCESS}\n    oauth:\n      client_secret: ${CLIENT_SECRET}\n`,
    );
    const bodies: string[] = [];
    const call = async (method: 'GET' | 'POST' | 'DELETE', url: string, payload?: unknown) => {
      const res = await authed(h, h.token, { method, url, ...(payload ? { payload } : {}) });
      bodies.push(res.body);
      return res;
    };
    const flow = (
      await call('POST', `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`, {
        hub_url: 'https://hub.example',
      })
    ).json() as { id: string };
    const back = await h.app.inject({
      method: 'GET',
      url: `/api/v1/mcp-oauth/callback/clickup?code=${CODE}&state=state-1`,
    });
    bodies.push(back.body);
    await call('GET', `/api/v1/agents/${agent}/mcp-servers/clickup/oauth/${flow.id}`);
    await call('GET', `/api/v1/agents/${agent}/mcp-servers`);
    await call('DELETE', `/api/v1/agents/${agent}/mcp-servers/clickup/oauth`);

    // The files did hold the values: the check below is not vacuous.
    expect(bodies.join('\n')).toContain('get_tasks');
    const logs = captured.lines.map((line) => JSON.stringify(line)).join('\n');
    expect(logs.length).toBeGreaterThan(0);
    for (const secret of [ACCESS, REFRESH, CLIENT_SECRET, CODE]) {
      expect(bodies.join('\n')).not.toContain(secret);
      expect(logs).not.toContain(secret);
    }
  });
});
