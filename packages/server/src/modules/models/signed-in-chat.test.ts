/**
 * The `direct` agent on a provider signed in through Hermes (DECISIONS §118), against a stand-in
 * for Hermes's Python and scripted provider backends that stream the real wire shapes.
 *
 * `hermes_cli.auth` and the few `agent.*` helpers below are stand-ins written for this test (the
 * names Hermes exposes, same shapes). The tokens they hand out are fakes; the Codex one is a
 * JWT-shaped string carrying a ChatGPT account id, as the backend expects.
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  authed,
  capturingLogger,
  drainJobs,
  signedInHub,
  type TestHub,
} from '../../../tests/unit/helpers.js';
import { hermesPythonRunner } from '../agents/index.js';
import type { DirectChatEvent } from '../agents/ports.js';
import { modelsServiceFor } from './index.js';
import { hermesSignInRuntime, type DashboardRequest } from './sign-in.js';
import { scrub, signedInCredential } from './signed-in-chat.js';

function python(): string | null {
  for (const candidate of ['python3', 'python']) {
    try {
      const version = execFileSync(candidate, ['--version'], { encoding: 'utf8' });
      const minor = /Python 3\.(\d+)/.exec(version);
      if (minor && Number(minor[1]) >= 9) return candidate;
    } catch {
      // not there
    }
  }
  return null;
}
const PY = python();

const jwt = (account: string, tag: string) => {
  const part = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${part({ alg: 'none' })}.${part({
    'https://api.openai.com/auth': { chatgpt_account_id: account },
    tag,
  })}.sig`;
};
const CODEX = jwt('acct-pro', 'fresh');
const CODEX_EXPIRED = jwt('acct-pro', 'expired');
const ECHOED = 'nous-token-the-backend-quotes-back';
const TOKENS = {
  'openai-codex': CODEX,
  'xai-oauth': 'xai-token-0123456789',
  nous: 'nous-token-0123456789',
  'minimax-oauth': 'minimax-token-0123456789',
} as const;

/** Hermes's credential resolvers, stood in for: token and base from the environment. */
const STUB_AUTH = `
import os
def _log(what):
    path = os.environ.get("STUB_LOG")
    if path:
        with open(path, "a") as f:
            f.write(what + "\\n")
def _creds(name, force):
    _log(name + (" refresh" if force else " resolve") + " " + os.environ.get("HERMES_HOME", ""))
    if os.environ.get("STUB_SIGNED_OUT"):
        raise RuntimeError("No credentials stored for " + name + ". Run hermes auth to authenticate.")
    key = "STUB_FRESH_" + name.replace("-", "_").upper() if force else "STUB_TOKEN_" + name.replace("-", "_").upper()
    token = os.environ.get(key) or os.environ.get("STUB_TOKEN_" + name.replace("-", "_").upper())
    return {"api_key": token, "base_url": os.environ["STUB_BASE_" + name.replace("-", "_").upper()]}
def resolve_codex_runtime_credentials(force_refresh=False, refresh_if_expiring=True):
    return _creds("openai-codex", force_refresh)
def resolve_xai_oauth_runtime_credentials(force_refresh=False):
    return _creds("xai-oauth", force_refresh)
def resolve_nous_runtime_credentials(force_refresh=False):
    return _creds("nous", force_refresh)
def resolve_minimax_oauth_runtime_credentials():
    return _creds("minimax-oauth", False)
`;

/** Hermes's Nous wire choice: Messages only for an `anthropic/*` model the config sends natively. */
const STUB_PROVIDERS = `
import os
def nous_api_mode(model=""):
    if str(model).startswith("anthropic/") and os.environ.get("STUB_NOUS_NATIVE"):
        return "anthropic_messages"
    return "chat_completions"
`;

const STUB_CODEX_HEADERS = `
import base64, json
def codex_cloudflare_headers(access_token, *, base_url=""):
    headers = {"User-Agent": "HermesAgent/test", "originator": "hermes-agent"}
    part = access_token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    headers["ChatGPT-Account-ID"] = claims["https://api.openai.com/auth"]["chatgpt_account_id"]
    return headers
`;

const STUB_EFFORT = `
CODEX_LEGACY_EFFORTS = ("low", "medium", "high")
XAI_LEGACY_EFFORTS = ("low", "high")
XAI_GROK46_EFFORTS = ("low", "medium", "high", "xhigh")
LADDER = ("minimal", "low", "medium", "high", "xhigh", "max")
def codex_supported_efforts(model):
    return CODEX_LEGACY_EFFORTS
def clamp_effort(effort, supported, overrides=None):
    if not supported or effort in supported or effort not in LADDER:
        return effort
    weaker = [level for level in LADDER[: LADDER.index(effort)] if level in supported]
    return weaker[-1] if weaker else supported[0]
`;

const STUB_METADATA = `
def strip_codex_context_variant_suffix(model):
    return model[:-5] if model.endswith("-900k") else model
def grok_supports_reasoning_effort(model):
    return model.startswith("grok-4")
def is_grok_46_family(model):
    return model.startswith("grok-4.6")
`;

interface Seen {
  method: string;
  url: string;
  headers: IncomingMessage['headers'];
  body: Record<string, unknown>;
}

let server: Server;
let base = '';
const seen: Seen[] = [];
let work = '';
let stubs = '';

const sse = (response: ServerResponse, events: Record<string, unknown>[], done = false) => {
  response.setHeader('content-type', 'text/event-stream');
  response.end(
    events
      .map((event) =>
        typeof event.type === 'string'
          ? `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
          : `data: ${JSON.stringify(event)}\n\n`,
      )
      .join('') + (done ? 'data: [DONE]\n\n' : ''),
  );
};

/** A Responses stream as the Codex backend and xAI send it. */
const RESPONSES_EVENTS = [
  { type: 'response.created', response: { id: 'resp_1', status: 'in_progress' } },
  { type: 'response.reasoning_summary_text.delta', item_id: 'rs_1', delta: 'Weighing it' },
  { type: 'response.output_item.added', item: { type: 'message', role: 'assistant' } },
  { type: 'response.output_text.delta', item_id: 'msg_1', delta: 'Hel' },
  { type: 'response.output_text.delta', item_id: 'msg_1', delta: 'lo' },
  { type: 'response.output_text.done', item_id: 'msg_1', text: 'Hello' },
  {
    type: 'response.completed',
    response: {
      id: 'resp_1',
      status: 'completed',
      usage: {
        input_tokens: 12,
        input_tokens_details: { cached_tokens: 2 },
        output_tokens: 5,
        output_tokens_details: { reasoning_tokens: 3 },
      },
    },
  },
];

beforeAll(async () => {
  work = mkdtempSync(path.join(tmpdir(), 'corehub-signed-in-'));
  stubs = path.join(work, 'stubs');
  mkdirSync(path.join(stubs, 'hermes_cli'), { recursive: true });
  mkdirSync(path.join(stubs, 'agent'), { recursive: true });
  writeFileSync(path.join(stubs, 'hermes_cli', '__init__.py'), '');
  writeFileSync(path.join(stubs, 'hermes_cli', 'auth.py'), STUB_AUTH);
  writeFileSync(path.join(stubs, 'hermes_cli', 'providers.py'), STUB_PROVIDERS);
  writeFileSync(path.join(stubs, 'agent', '__init__.py'), '');
  writeFileSync(path.join(stubs, 'agent', 'codex_headers.py'), STUB_CODEX_HEADERS);
  writeFileSync(path.join(stubs, 'agent', 'reasoning_effort.py'), STUB_EFFORT);
  writeFileSync(path.join(stubs, 'agent', 'model_metadata.py'), STUB_METADATA);

  server = createServer((request, response) => {
    let raw = '';
    request.on('data', (chunk: Buffer) => (raw += chunk.toString()));
    request.on('end', () => {
      let body: Record<string, unknown> = {};
      try {
        body = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
      } catch {
        body = {};
      }
      const url = request.url ?? '';
      seen.push({ method: request.method ?? '', url, headers: request.headers, body });
      const bearer = String(request.headers.authorization ?? '').replace(/^Bearer /, '');
      const json = (status: number, value: unknown) => {
        response.statusCode = status;
        response.setHeader('content-type', 'application/json');
        response.end(JSON.stringify(value));
      };
      if (request.method === 'GET') {
        if (url.startsWith('/codex/models')) {
          return json(200, { models: [{ slug: 'gpt-6-sol', priority: 1 }] });
        }
        if (url === '/xai/v1/models') return json(200, { data: [{ id: 'grok-4.6' }] });
        if (url === '/nous/v1/models') return json(200, { data: [{ id: 'hermes-5' }] });
        return json(404, { error: { message: 'no list here' } });
      }
      if (bearer === CODEX_EXPIRED) return json(401, { error: { message: 'token expired' } });
      if (bearer === ECHOED) {
        return json(401, { error: { message: `invalid bearer ${ECHOED}` } });
      }
      if (url === '/codex/responses' || url === '/xai/v1/responses') {
        if (body.model === 'slow') {
          response.setHeader('content-type', 'text/event-stream');
          response.write(
            `event: response.output_text.delta\ndata: ${JSON.stringify({
              type: 'response.output_text.delta',
              delta: 'Once',
            })}\n\n`,
          );
          request.socket.on('close', () => response.end());
          return;
        }
        return sse(response, RESPONSES_EVENTS);
      }
      if (url === '/nous/v1/chat/completions') {
        return sse(
          response,
          [
            { choices: [{ delta: { content: 'Hi ' } }] },
            { choices: [{ delta: { content: 'there' } }] },
            { choices: [], usage: { prompt_tokens: 9, completion_tokens: 2 } },
          ],
          true,
        );
      }
      if (url === '/minimax/anthropic/v1/messages' || url === '/nous/v1/messages') {
        return sse(response, [
          { type: 'message_start', message: { usage: { input_tokens: 7, output_tokens: 0 } } },
          { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Marhaba' } },
          { type: 'message_delta', usage: { output_tokens: 3 } },
          { type: 'message_stop' },
        ]);
      }
      return json(404, { error: { message: 'not part of this test' } });
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  base = `http://127.0.0.1:${String(typeof address === 'object' && address ? address.port : 0)}`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  rmSync(work, { recursive: true, force: true });
});

/** Hermes's Python as the hub runs it, with the stand-ins on its path. */
function runner(env: () => Record<string, string>) {
  return hermesPythonRunner({
    python: PY ?? 'python3',
    env: () => ({
      PATH: process.env.PATH ?? '',
      PYTHONPATH: stubs,
      STUB_BASE_OPENAI_CODEX: `${base}/codex`,
      STUB_BASE_XAI_OAUTH: `${base}/xai/v1`,
      STUB_BASE_NOUS: `${base}/nous/v1`,
      STUB_BASE_MINIMAX_OAUTH: `${base}/minimax/anthropic`,
      STUB_TOKEN_OPENAI_CODEX: TOKENS['openai-codex'],
      STUB_TOKEN_XAI_OAUTH: TOKENS['xai-oauth'],
      STUB_TOKEN_NOUS: TOKENS.nous,
      STUB_TOKEN_MINIMAX_OAUTH: TOKENS['minimax-oauth'],
      ...env(),
    }),
  });
}

describe.skipIf(!PY)('borrowing a signed-in credential from Hermes (§118)', () => {
  it('answers each provider’s wire the way Hermes calls it', async () => {
    const home = mkdtempSync(path.join(work, 'home-'));
    const run = runner(() => ({}));
    const codex = await signedInCredential(run, home, 'openai-codex', {
      model: 'gpt-6-sol-900k',
      effort: 'xhigh',
      force: false,
    });
    expect(codex).toEqual({
      ok: true,
      credential: {
        wire: 'codex_responses',
        baseUrl: `${base}/codex`,
        model: 'gpt-6-sol',
        headers: {
          Authorization: `Bearer ${CODEX}`,
          'User-Agent': 'HermesAgent/test',
          originator: 'hermes-agent',
          'ChatGPT-Account-ID': 'acct-pro',
        },
        // Hermes's own clamp: the nearest weaker level the model takes, never a stronger one.
        reasoning: { effort: 'high', summary: 'auto' },
        codexBackend: true,
      },
    });

    const xai = await signedInCredential(run, home, 'xai-oauth', {
      model: 'grok-4.6',
      effort: null,
      force: false,
    });
    expect(xai.ok && xai.credential).toMatchObject({
      wire: 'codex_responses',
      baseUrl: `${base}/xai/v1`,
      reasoning: { effort: 'medium' },
      codexBackend: false,
    });
    // A Grok model that takes no effort gets none (xAI answers 400 to one).
    const grok3 = await signedInCredential(run, home, 'xai-oauth', {
      model: 'grok-3',
      effort: 'high',
      force: false,
    });
    expect(grok3.ok && grok3.credential.reasoning).toBeNull();

    const nous = await signedInCredential(run, home, 'nous', {
      model: 'hermes-5',
      effort: 'high',
      force: false,
    });
    expect(nous.ok && nous.credential).toEqual({
      wire: 'chat_completions',
      baseUrl: `${base}/nous/v1`,
      model: 'hermes-5',
      headers: { Authorization: `Bearer ${TOKENS.nous}` },
      reasoning: null,
      codexBackend: false,
    });

    const minimax = await signedInCredential(run, home, 'minimax-oauth', {
      model: 'MiniMax-M3',
      effort: null,
      force: false,
    });
    expect(minimax.ok && minimax.credential.wire).toBe('anthropic_messages');
  });

  it('says why when Hermes has no sign-in, or no credential store, without the token', async () => {
    const home = mkdtempSync(path.join(work, 'home-'));
    const out = await signedInCredential(
      runner(() => ({ STUB_SIGNED_OUT: '1' })),
      home,
      'nous',
      { model: 'hermes-5', effort: null, force: false },
    );
    expect(out).toMatchObject({ ok: false, reason: 'not_signed_in' });
    expect(!out.ok && out.detail).toContain('No credentials stored for nous');
    expect(JSON.stringify(out)).not.toContain(TOKENS.nous);

    const bare = hermesPythonRunner({
      python: PY ?? 'python3',
      env: () => ({ PATH: process.env.PATH ?? '' }),
    });
    const missing = await signedInCredential(bare, home, 'nous', {
      model: 'hermes-5',
      effort: null,
      force: false,
    });
    expect(missing).toMatchObject({ ok: false, reason: 'hermes_unavailable' });
  });

  it('cuts the credential out of any text that leaves the turn', () => {
    expect(scrub(`bad token ${CODEX} here`, [`Bearer ${CODEX}`, CODEX])).toBe(
      'bad token [redacted] here',
    );
    expect(scrub(null, [CODEX])).toBeNull();
  });
});

// ------------------------------------------------------------------ in the hub

/** Hermes's server playing an approved device-code sign-in for any provider. */
function fakeDashboard(): DashboardRequest {
  return <T>(_method: string, route: string): Promise<T> => {
    const url = new URL(route, 'http://hermes.test');
    const start = /^\/api\/providers\/oauth\/([^/]+)\/start$/.exec(url.pathname);
    if (start) {
      return Promise.resolve({
        session_id: `s-${start[1]!}`,
        user_code: 'ABCD-1234',
        verification_url: 'https://auth.example/device',
        expires_in: 900,
      } as T);
    }
    if (/^\/api\/providers\/oauth\/[^/]+\/poll\//.test(url.pathname)) {
      return Promise.resolve({ status: 'approved' } as T);
    }
    if (url.pathname === '/api/model/options') {
      return Promise.resolve({
        providers: [{ slug: 'minimax-oauth', models: ['MiniMax-M3'] }],
      } as T);
    }
    return Promise.resolve({ ok: true } as T);
  };
}

type Hub = TestHub & { token: string };

async function hubWith(
  env: () => Record<string, string>,
  options: { python?: boolean; home?: string; logger?: ReturnType<typeof capturingLogger> } = {},
): Promise<{ hub: Hub; home: string }> {
  const home = options.home ?? mkdtempSync(path.join(work, 'hermes-'));
  const hub = await signedInHub(
    {},
    {
      ...(options.logger ? { logger: options.logger.logger } : {}),
      models: {
        restartDelayMs: 0,
        // The scripted backends above, on this machine; nothing else is reached.
        fetchImpl: (input, init) => fetch(input, init),
        signIn: hermesSignInRuntime(fakeDashboard(), {
          python: () => (options.python === false ? null : runner(env)),
          home: (profile) => (profile ? path.join(home, 'profiles', profile) : home),
        }),
        hermes: {
          home: () => home,
          profileHomes: () => [],
          restart: () => Promise.resolve(true),
          applyEnvironment: () => true,
        },
      },
    },
  );
  return { hub, home };
}

async function signIn(hub: Hub, preset: string, profile = 'default', scope = 'all') {
  const created = await authed(hub, hub.token, {
    method: 'POST',
    url: '/api/v1/models/providers',
    profile,
    payload: { preset, label: preset, kind: 'llm', scope },
  });
  expect(created.statusCode).toBe(201);
  const { id } = created.json() as { id: string };
  const started = (
    await authed(hub, hub.token, {
      method: 'POST',
      url: `/api/v1/models/providers/${id}/sign-in`,
      profile,
    })
  ).json() as { id: string };
  const polled = await authed(hub, hub.token, {
    method: 'GET',
    url: `/api/v1/models/providers/${id}/sign-in/${started.id}`,
    profile,
  });
  expect((polled.json() as { status: string }).status).toBe('approved');
  await drainJobs(hub.app);
  return id;
}

async function workspaceOf(hub: Hub, slug: string): Promise<string> {
  const response = await authed(hub, hub.token, { method: 'GET', url: '/api/v1/profiles' });
  const items = (response.json() as { items: { id: string; slug: string }[] }).items;
  return items.find((item) => item.slug === slug)!.id;
}

async function turn(
  hub: Hub,
  workspace: string,
  request: {
    providerId: string;
    model: string;
    reasoningEffort?: string | null;
    signal?: AbortSignal;
  },
  onEvent?: (event: DirectChatEvent) => void,
): Promise<DirectChatEvent[]> {
  const events: DirectChatEvent[] = [];
  for await (const event of modelsServiceFor(hub.app).chat(workspace, {
    ...request,
    messages: [
      { role: 'system', text: 'Answer briefly.' },
      { role: 'user', text: 'Say hello' },
    ],
  })) {
    events.push(event);
    onEvent?.(event);
  }
  return events;
}

const textOf = (events: DirectChatEvent[]) =>
  events.map((event) => (event.type === 'delta' ? event.text : '')).join('');

describe.skipIf(!PY)('a direct turn on a provider signed in through Hermes (§118)', () => {
  it('streams the ChatGPT subscription through the Codex /responses, with Hermes’s headers', async () => {
    const { hub } = await hubWith(() => ({}));
    try {
      const id = await signIn(hub, 'openai-codex');
      const workspace = await workspaceOf(hub, 'default');
      seen.length = 0;
      const events = await turn(hub, workspace, {
        providerId: id,
        model: 'gpt-6-sol',
        reasoningEffort: 'medium',
      });
      expect(events.map((event) => event.type)).toEqual([
        'reasoning',
        'delta',
        'delta',
        'usage',
        'completed',
      ]);
      expect(textOf(events)).toBe('Hello');
      expect(events.find((event) => event.type === 'reasoning')).toEqual({
        type: 'reasoning',
        text: 'Weighing it',
      });
      expect(events.find((event) => event.type === 'usage')).toMatchObject({
        providerId: id,
        modelLabel: 'gpt-6-sol',
        inputTokens: 10,
        cacheReadTokens: 2,
        outputTokens: 5,
        reasoningTokens: 3,
      });

      const posted = seen.find((entry) => entry.url === '/codex/responses');
      expect(posted?.headers.authorization).toBe(`Bearer ${CODEX}`);
      expect(posted?.headers['chatgpt-account-id']).toBe('acct-pro');
      expect(posted?.headers.originator).toBe('hermes-agent');
      expect(posted?.headers.accept).toBe('text/event-stream');
      expect(posted?.body).toEqual({
        model: 'gpt-6-sol',
        instructions: 'Answer briefly.',
        input: [
          { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'Say hello' }] },
        ],
        store: false,
        stream: true,
        include: [],
        reasoning: { effort: 'medium', summary: 'auto' },
      });
    } finally {
      await hub.close();
    }
  });

  it('asks Hermes once more with a forced refresh after a 401, and only once', async () => {
    const log = path.join(work, `auth-${String(Date.now())}.log`);
    const { hub, home } = await hubWith(() => ({
      STUB_TOKEN_OPENAI_CODEX: CODEX_EXPIRED,
      STUB_FRESH_OPENAI_CODEX: CODEX,
      STUB_LOG: log,
    }));
    try {
      const id = await signIn(hub, 'openai-codex');
      const workspace = await workspaceOf(hub, 'default');
      writeFileSync(log, '');
      const events = await turn(hub, workspace, { providerId: id, model: 'gpt-6-sol' });
      expect(textOf(events)).toBe('Hello');
      expect(events.at(-1)).toEqual({ type: 'completed' });
      expect(readFileSync(log, 'utf8').trim().split('\n')).toEqual([
        `openai-codex resolve ${home}`,
        `openai-codex refresh ${home}`,
      ]);
    } finally {
      await hub.close();
    }
  });

  it('never lets the token reach a log line or the error, even when the provider quotes it', async () => {
    const logger = capturingLogger();
    const { hub } = await hubWith(
      () => ({ STUB_TOKEN_NOUS: ECHOED, STUB_FRESH_NOUS: ECHOED }),
      { logger },
    );
    try {
      const id = await signIn(hub, 'nous');
      const workspace = await workspaceOf(hub, 'default');
      seen.length = 0;
      const events = await turn(hub, workspace, { providerId: id, model: 'hermes-5' });
      const failed = events.at(-1);
      expect(failed).toMatchObject({ type: 'failed', code: 'provider_unauthorized' });
      // The provider's own words, as for a key provider, with the token cut out.
      expect(failed?.type === 'failed' && failed.message).toContain('invalid bearer [redacted]');
      // Refreshed once, then refused: two requests, never a third.
      expect(seen.filter((entry) => entry.url === '/nous/v1/chat/completions')).toHaveLength(2);
      expect(JSON.stringify(events)).not.toContain(ECHOED);
      expect(JSON.stringify(logger.lines)).not.toContain(ECHOED);
    } finally {
      await hub.close();
    }
  });

  it('streams Nous Portal on chat/completions and xAI on /responses', async () => {
    const { hub } = await hubWith(() => ({}));
    try {
      const nous = await signIn(hub, 'nous');
      const xai = await signIn(hub, 'xai-oauth');
      const workspace = await workspaceOf(hub, 'default');
      seen.length = 0;

      const fromNous = await turn(hub, workspace, { providerId: nous, model: 'hermes-5' });
      expect(textOf(fromNous)).toBe('Hi there');
      expect(fromNous.find((event) => event.type === 'usage')).toMatchObject({
        inputTokens: 9,
        outputTokens: 2,
      });
      const toNous = seen.find((entry) => entry.url === '/nous/v1/chat/completions');
      expect(toNous?.headers.authorization).toBe(`Bearer ${TOKENS.nous}`);
      expect(toNous?.body).toMatchObject({ model: 'hermes-5', stream: true });

      const fromXai = await turn(hub, workspace, { providerId: xai, model: 'grok-4.6' });
      expect(textOf(fromXai)).toBe('Hello');
      const toXai = seen.find((entry) => entry.url === '/xai/v1/responses');
      expect(toXai?.headers.authorization).toBe(`Bearer ${TOKENS['xai-oauth']}`);
      expect(toXai?.headers['chatgpt-account-id']).toBeUndefined();
      expect(toXai?.body).toMatchObject({ model: 'grok-4.6', reasoning: { effort: 'medium' } });
    } finally {
      await hub.close();
    }
  });

  it('streams MiniMax on Anthropic Messages with a Bearer, not x-api-key', async () => {
    const { hub } = await hubWith(() => ({}));
    try {
      const id = await signIn(hub, 'minimax-oauth');
      const workspace = await workspaceOf(hub, 'default');
      seen.length = 0;
      const events = await turn(hub, workspace, { providerId: id, model: 'MiniMax-M3' });
      expect(textOf(events)).toBe('Marhaba');
      const posted = seen.find((entry) => entry.url === '/minimax/anthropic/v1/messages');
      expect(posted?.headers.authorization).toBe(`Bearer ${TOKENS['minimax-oauth']}`);
      expect(posted?.headers['x-api-key']).toBeUndefined();
      expect(posted?.body).toMatchObject({ model: 'MiniMax-M3', system: 'Answer briefly.' });
    } finally {
      await hub.close();
    }
  });

  it('borrows a profile’s own sign-in from that profile’s Hermes home', async () => {
    const log = path.join(work, `profile-${String(Date.now())}.log`);
    const { hub, home } = await hubWith(() => ({ STUB_LOG: log }));
    try {
      const made = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/profiles',
        payload: { slug: 'studio', name: 'studio' },
      });
      expect(made.statusCode).toBe(201);
      // Where Hermes keeps that profile, and the sign-in it made there.
      mkdirSync(path.join(home, 'profiles', 'studio'), { recursive: true });
      const id = await signIn(hub, 'nous', 'studio', 'profile');
      const workspace = await workspaceOf(hub, 'studio');
      writeFileSync(log, '');
      const events = await turn(hub, workspace, { providerId: id, model: 'hermes-5' });
      expect(events.at(-1)).toEqual({ type: 'completed' });
      expect(textOf(events)).toBe('Hi there');
      expect(readFileSync(log, 'utf8').trim()).toBe(
        `nous resolve ${path.join(home, 'profiles', 'studio')}`,
      );
    } finally {
      await hub.close();
    }
  });

  it('stops mid-stream when the turn is cancelled', async () => {
    const { hub } = await hubWith(() => ({}));
    try {
      const id = await signIn(hub, 'openai-codex');
      const workspace = await workspaceOf(hub, 'default');
      const controller = new AbortController();
      const events = await turn(
        hub,
        workspace,
        { providerId: id, model: 'slow', signal: controller.signal },
        (event) => {
          if (event.type === 'delta') controller.abort();
        },
      );
      expect(events[0]).toEqual({ type: 'delta', text: 'Once' });
      expect(events.at(-1)).toMatchObject({ type: 'failed', code: 'cancelled' });
    } finally {
      await hub.close();
    }
  });

  it('refuses by name when this hub cannot run Hermes’s Python', async () => {
    const bare = await hubWith(() => ({}), { python: false });
    try {
      const id = await signIn(bare.hub, 'nous');
      const workspace = await workspaceOf(bare.hub, 'default');
      const events = await turn(bare.hub, workspace, { providerId: id, model: 'hermes-5' });
      expect(events).toHaveLength(1);
      expect(events[0]).toMatchObject({ type: 'failed', code: 'agent_unavailable' });
      const message = events[0]?.type === 'failed' ? events[0].message : '';
      expect(message).toContain('signed in through Hermes');
      expect(message).toContain('Hermes’s Python is not available on this hub');
      expect(message).toContain('add a provider with a key');
    } finally {
      await bare.hub.close();
    }
  });

  it('refuses a provider whose sign-in was never approved, before asking Hermes', async () => {
    const log = path.join(work, `unsigned-${String(Date.now())}.log`);
    const { hub } = await hubWith(() => ({ STUB_LOG: log }));
    try {
      const created = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/models/providers',
        payload: { preset: 'nous', label: 'Nous', kind: 'llm' },
      });
      const { id } = created.json() as { id: string };
      const workspace = await workspaceOf(hub, 'default');
      writeFileSync(log, '');
      const events = await turn(hub, workspace, { providerId: id, model: 'hermes-5' });
      expect(events).toEqual([
        {
          type: 'failed',
          code: 'provider_not_configured',
          message: 'Nous is not signed in yet; sign in under Models → Providers',
        },
      ]);
      expect(readFileSync(log, 'utf8')).toBe('');
    } finally {
      await hub.close();
    }
  });
});
