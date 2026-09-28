/**
 * Signing an MCP server in by OAuth from the hub's pages (contract decision §122).
 *
 * Hermes does the sign-in, with its own command `hermes mcp login <server>` (MIT source
 * `hermes_cli/mcp_config.py` §_reauth_oauth_server and `tools/mcp_oauth.py`, v2026.9.14, read and
 * described here in our words): it discovers the provider, registers a client whose redirect is
 * the server's `oauth.redirect_uri`, prints the provider's page, waits on a listener at
 * `127.0.0.1:<oauth.redirect_port>/callback`, exchanges the code with PKCE, keeps the tokens in
 * the profile's home (`mcp-tokens/<server>.json`, `.client.json`, `.meta.json`, `.cimd-off`),
 * connects once and says "Authenticated — N tool(s)".
 *
 * The hub adds the two things Hermes cannot know from where it runs: **where the browser can come
 * back to** — the hub's public callback, written as `oauth.redirect_uri` — and **the way from there
 * to Hermes's listener** — the callback hands the provider's query, unchanged, to
 * `127.0.0.1:<port>`. The CLI's listener keeps RFC 9207's `iss`, which a provider such as ClickUp
 * requires; Hermes's dashboard route did not until v2026.9.21 (the ClickUp report of 2026-09-28).
 * **No token passes through the hub**: it reads only the metadata of the token file and never
 * returns or logs a value from it, nor Hermes's output as it is.
 */
import { spawn } from 'node:child_process';
import { existsSync, readFileSync, statSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { HubError } from '../../lib/errors.js';
import { t, type Language } from '../../i18n/index.js';
import { PRODUCT } from '@corehub/contracts';

// ------------------------------------------------------------------ what is stored

/** The contract's `McpOAuthState`. */
export interface McpOAuthState {
  required: boolean;
  status: 'connected' | 'expired' | 'not_connected' | 'error';
  expires_at: string | null;
}

/** Hermes's folder of MCP sign-ins in a profile's home (`tools/mcp_oauth.py` §_get_token_dir). */
export const TOKEN_DIR = 'mcp-tokens';

/**
 * The file name Hermes gives a server's sign-in: every character that is not a word character
 * or `-` becomes `_`, `_` is trimmed from both ends, 128 characters at most, `default` if
 * nothing is left (`tools/mcp_oauth.py` §_safe_filename). The hub's server names are
 * `[A-Za-z0-9._-]`, so in practice only `.` changes.
 */
export function tokenFileStem(name: string): string {
  return (
    name
      .replace(/[^\w-]/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, 128) || 'default'
  );
}

/** Every file Hermes keeps for one server's sign-in (`HermesTokenStorage.remove`). */
const SUFFIXES = ['.json', '.client.json', '.meta.json', '.cimd-off'] as const;

/**
 * How a server's sign-in stands in this profile, from the token file's **metadata only**: that
 * it exists and has an access token, `expires_at` (Hermes's absolute time, seconds) or else the
 * file's time plus `expires_in`, and whether a refresh token is there to renew it. No value is
 * returned, logged or kept.
 */
export function oauthStateOf(
  home: string,
  name: string,
  required: boolean,
  now: number = Date.now(),
): McpOAuthState {
  const file = path.join(home, TOKEN_DIR, `${tokenFileStem(name)}.json`);
  if (!existsSync(file)) return { required, status: 'not_connected', expires_at: null };
  let data: Record<string, unknown>;
  let modified: number;
  try {
    const parsed: unknown = JSON.parse(readFileSync(file, 'utf8'));
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('shape');
    data = parsed as Record<string, unknown>;
    modified = statSync(file).mtimeMs;
  } catch {
    return { required, status: 'error', expires_at: null };
  }
  if (typeof data.access_token !== 'string' || data.access_token === '') {
    return { required, status: 'error', expires_at: null };
  }
  const expiresAt =
    typeof data.expires_at === 'number' && Number.isFinite(data.expires_at)
      ? data.expires_at * 1000
      : typeof data.expires_in === 'number' && Number.isFinite(data.expires_in)
        ? modified + data.expires_in * 1000
        : null;
  const renewable = typeof data.refresh_token === 'string' && data.refresh_token !== '';
  const ranOut = expiresAt !== null && expiresAt <= now;
  return {
    required,
    status: ranOut && !renewable ? 'expired' : 'connected',
    expires_at: expiresAt === null ? null : new Date(expiresAt).toISOString(),
  };
}

/**
 * Forget a server's sign-in in one profile: the files Hermes's own `remove` deletes. Hermes has
 * no API for it (only its CLI's `hermes mcp remove`), so the hub deletes them in the profile's
 * home, which is the hub's to manage. Answers whether anything was there.
 */
export function removeOAuthTokens(home: string, name: string): boolean {
  let removed = false;
  for (const suffix of SUFFIXES) {
    const file = path.join(home, TOKEN_DIR, `${tokenFileStem(name)}${suffix}`);
    try {
      unlinkSync(file);
      removed = true;
    } catch {
      // Not there: nothing to forget.
    }
  }
  return removed;
}

// ------------------------------------------------------------------ the redirect

/** The hub's callback under its API base: `agents.mcpOAuthCallback`. */
export const CALLBACK_SEGMENT = '/mcp-oauth/callback/';

/**
 * The base the browser reaches the hub on, from what the client said (`hub_url`) or, without
 * it, from the request (`protocol://host`, which honours `X-Forwarded-*` from the proxies
 * `COREHUB_TRUST_PROXY` trusts). A `hub_url` that is not `http(s)`, or carries credentials, is
 * refused rather than quietly replaced.
 */
export function hubBaseOf(hubUrl: string | undefined, requestOrigin: string): string {
  if (hubUrl === undefined || hubUrl.trim() === '') return requestOrigin.replace(/\/+$/, '');
  let parsed: URL;
  try {
    parsed = new URL(hubUrl.trim());
  } catch {
    throw new HubError('bad_request', { details: { field: 'hub_url', reason: 'url_invalid' } });
  }
  if (
    (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') ||
    parsed.username ||
    parsed.password
  ) {
    throw new HubError('bad_request', { details: { field: 'hub_url', reason: 'url_invalid' } });
  }
  return `${parsed.origin}${parsed.pathname}`.replace(/\/+$/, '');
}

/** The redirect URI for one server: the hub's base, its API base, the callback, the name. */
export function callbackUri(base: string, apiBase: string, name: string): string {
  return `${base}${apiBase}${CALLBACK_SEGMENT}${encodeURIComponent(name)}`;
}

/** Whether a `redirect_uri` found in the file is one the hub wrote (any host), so it may move. */
export function isHubCallback(uri: string, apiBase: string, name: string): boolean {
  try {
    return new URL(uri).pathname.endsWith(
      `${apiBase}${CALLBACK_SEGMENT}${encodeURIComponent(name)}`,
    );
  } catch {
    return false;
  }
}

// ------------------------------------------------------------------ the flow

/** The contract's `McpOAuthFlow.status`. */
export type McpOAuthStatus = 'pending' | 'approved' | 'failed' | 'cancelled' | 'expired';

/** Hermes waits 300 s for the browser (its `oauth.timeout`); the hub keeps a sign-in a while longer. */
export const FLOW_TTL_MS = 15 * 60_000;

/** How long starting may take: Hermes discovers the provider and registers before it names the page. */
export const START_TIMEOUT_MS = 45_000;

/** How long the callback page waits for Hermes to finish before it says "still finishing". */
export const CALLBACK_WAIT_MS = 45_000;

/**
 * One run of Hermes's own `hermes mcp login <server>` (the CLI's browser sign-in), as the hub
 * drives it. What it printed is kept to find the provider's page and the outcome; it holds no
 * token (Hermes prints the authorization URL, a count of tools and its reasons) and is never
 * logged or returned as it is.
 */
export interface McpLoginProcess {
  output(): string;
  running(): boolean;
  /** Resolves when the process has ended. */
  exited: Promise<void>;
  kill(): void;
}

/** Starts `hermes <argv>` with `HERMES_HOME` set to a profile's home. */
export type McpLoginSpawner = (home: string, argv: string[]) => McpLoginProcess;

/**
 * The real spawner. Two things in the environment matter: `HERMES_HOME` is the profile's home
 * (where Hermes keeps the tokens), and `SSH_CLIENT` is set so Hermes does not open a browser on
 * the machine the hub runs on — the person's own browser, on the page the hub hands them, is the
 * one that signs in (Hermes's `_can_open_browser` says no inside an SSH session; on a server
 * there is no browser anyway, on the desktop app there would be a second one).
 */
export function mcpLoginSpawner(options: {
  command: string;
  env: () => NodeJS.ProcessEnv;
}): McpLoginSpawner {
  return (home, argv) => {
    let output = '';
    let alive = true;
    const child = spawn(options.command, argv, {
      env: {
        ...options.env(),
        HERMES_HOME: home,
        SSH_CLIENT: 'corehub 0 0',
        NO_COLOR: '1',
        COLUMNS: '1000',
      },
      cwd: home,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    const keep = (chunk: Buffer) => {
      if (output.length < 256 * 1024) output += chunk.toString('utf8');
    };
    child.stdout?.on('data', keep);
    child.stderr?.on('data', keep);
    const exited = new Promise<void>((resolve) => {
      child.on('error', (error) => {
        output += `\n${error.message}`;
        alive = false;
        resolve();
      });
      child.on('close', () => {
        alive = false;
        resolve();
      });
    });
    return {
      output: () => output,
      running: () => alive,
      exited,
      kill: () => {
        if (alive) child.kill('SIGTERM');
      },
    };
  };
}

/** The contract's `McpOAuthFlow` minus the fields the record fills. */
export interface McpOAuthView {
  status: McpOAuthStatus;
  authorization_url: string | null;
  error: string | null;
  tools: Array<{ name: string; description: string | null }>;
}

export interface McpOAuthFlowRecord {
  id: string;
  agentId: string;
  profile: string;
  home: string;
  serverName: string;
  /** Where Hermes's callback listener waits, on this host (`oauth.redirect_port`). */
  port: number;
  redirectUri: string;
  createdAt: number;
  /** The `state` of the provider's page: the callback finds its sign-in by it. Never logged. */
  state: string | null;
  authorizationUrl: string | null;
  process: McpLoginProcess;
  cancelled: boolean;
  /** The tools Hermes listed once signed in (asked once, after the sign-in). */
  tools: Array<{ name: string; description: string | null }>;
  toolsAsked: Promise<void> | null;
}

/** The `state` query parameter of an authorization URL, if it has one. */
export function stateOf(authorizationUrl: string | null): string | null {
  if (!authorizationUrl) return null;
  try {
    return new URL(authorizationUrl).searchParams.get('state');
  } catch {
    return null;
  }
}

// eslint-disable-next-line no-control-regex
const ANSI = /\u001b\[[0-9;]*[A-Za-z]/g;
const lines = (text: string) =>
  text
    .replace(ANSI, '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

/** The provider's page Hermes printed ("Open this URL in your browser"), once it has. */
export function authorizationUrlIn(output: string): string | null {
  for (const line of lines(output)) {
    const match = /^(https?:\/\/\S+)$/.exec(line);
    if (match && /[?&]state=/.test(match[1]!)) return match[1]!;
  }
  return null;
}

/** "✓ Authenticated — 3 tool(s) available": Hermes signed in, and how many tools it saw. */
export function signedInIn(output: string): { tools: number | null } | null {
  for (const line of lines(output)) {
    const match = /Authenticated(?:\s*[—-]\s*(\d+) tool)?/.exec(line);
    if (match) return { tools: match[1] ? Number(match[1]) : null };
  }
  return null;
}

/** Why Hermes's sign-in ended without a token, in its own words (its last sentence of failure). */
export function failureIn(output: string): string {
  const all = lines(output);
  const said = [...all]
    .reverse()
    .find((line) =>
      /Authentication failed:|no OAuth token was obtained|timed out|not configured for OAuth|has no URL|Error/i.test(
        line,
      ),
    );
  const text = (said ?? all.at(-1) ?? 'Hermes ended the sign-in without a token').replace(
    /^[✗✘×!\s]+/,
    '',
  );
  return text.length > 600 ? `${text.slice(0, 600)}…` : text;
}

/** How a sign-in stands, from Hermes's process and the profile's token file. */
export function viewOfLogin(record: McpOAuthFlowRecord): McpOAuthView {
  const url = record.authorizationUrl;
  if (record.cancelled) {
    return { status: 'cancelled', authorization_url: url, error: null, tools: [] };
  }
  if (record.process.running()) {
    return { status: 'pending', authorization_url: url, error: null, tools: [] };
  }
  const output = record.process.output();
  const token = oauthStateOf(record.home, record.serverName, true).status === 'connected';
  if (signedInIn(output) && token) {
    return { status: 'approved', authorization_url: url, error: null, tools: record.tools };
  }
  const reason = failureIn(output);
  return {
    status: /timed out/i.test(reason) ? 'expired' : 'failed',
    authorization_url: null,
    error: reason,
    tools: [],
  };
}

/**
 * Start `hermes mcp login <server>` and wait until it names the provider's page (or ends
 * without one). The caller has written the server's `auth: oauth`, `oauth.redirect_uri` (the
 * hub's callback) and `oauth.redirect_port` (a free port here) first.
 */
export async function startLogin(
  spawner: McpLoginSpawner,
  home: string,
  name: string,
  waitMs = START_TIMEOUT_MS,
): Promise<{ process: McpLoginProcess; authorizationUrl: string | null }> {
  const process = spawner(home, ['mcp', 'login', name]);
  const began = Date.now();
  for (;;) {
    const url = authorizationUrlIn(process.output());
    if (url) return { process, authorizationUrl: url };
    if (!process.running() || Date.now() - began > waitMs) {
      if (process.running()) process.kill();
      return { process, authorizationUrl: null };
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

/** The sign-ins this hub started, kept as long as Hermes waits for them. Per hub, in memory. */
export class McpOAuthFlows {
  private readonly flows = new Map<string, McpOAuthFlowRecord>();

  constructor(private readonly now: () => number = Date.now) {}

  add(record: McpOAuthFlowRecord): void {
    this.sweep();
    // One sign-in per server and profile at a time: a new one ends the one before.
    for (const other of this.flows.values()) {
      if (
        other.serverName === record.serverName &&
        other.home === record.home &&
        other.process.running()
      ) {
        other.cancelled = true;
        other.process.kill();
      }
    }
    this.flows.set(record.id, record);
  }

  /** The flow, if it was started for this agent, profile and server. */
  get(id: string, agentId: string, profile: string, serverName: string): McpOAuthFlowRecord | null {
    this.sweep();
    const record = this.flows.get(id);
    if (!record) return null;
    if (
      record.agentId !== agentId ||
      record.profile !== profile ||
      record.serverName !== serverName
    ) {
      return null;
    }
    return record;
  }

  /** The sign-in the provider's callback is for: this server, this `state`. */
  byState(serverName: string, state: string | null): McpOAuthFlowRecord | null {
    this.sweep();
    if (!state) return null;
    for (const record of this.flows.values()) {
      if (record.serverName === serverName && record.state !== null && record.state === state) {
        return record;
      }
    }
    return null;
  }

  expiresAt(record: McpOAuthFlowRecord): string {
    return new Date(record.createdAt + FLOW_TTL_MS).toISOString();
  }

  /** Every sign-in still waiting: stopped when the hub closes. */
  running(): McpOAuthFlowRecord[] {
    return [...this.flows.values()].filter((record) => record.process.running());
  }

  private sweep(): void {
    const cutoff = this.now() - FLOW_TTL_MS;
    for (const [id, record] of this.flows) {
      if (record.createdAt < cutoff) {
        record.process.kill();
        this.flows.delete(id);
      }
    }
  }
}

// ------------------------------------------------------------------ the callback

/**
 * What the page says. `connected` only once Hermes's sign-in ended with a token in the profile's
 * home (DECISIONS §122, amended): the code arriving is `pending` while Hermes exchanges it and
 * reaches the server, and `failed` with Hermes's reason when that fails.
 */
export type CallbackOutcome = 'connected' | 'failed' | 'pending' | 'declined' | 'expired';

/**
 * Hand the browser's arrival to Hermes's callback listener on this host, the query exactly as
 * the provider sent it — `code`, `state`, `error` and RFC 9207's `iss`, which Hermes's CLI
 * listener carries to the MCP SDK (its dashboard route before v2026.9.21 dropped `iss`, and a
 * provider that advertises it — ClickUp — was then refused). Nothing about it is logged or
 * thrown: the query holds the authorization code.
 */
export async function relayToLogin(
  record: McpOAuthFlowRecord,
  rawQuery: string,
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  try {
    const response = await fetchImpl(
      `http://127.0.0.1:${record.port}/callback${rawQuery ? `?${rawQuery}` : ''}`,
      { signal: AbortSignal.timeout(10_000) },
    );
    await response.text().catch(() => '');
    return response.ok;
  } catch {
    return false;
  }
}

/** Wait (at most `waitMs`) for Hermes's sign-in to end. */
export async function awaitLogin(
  record: McpOAuthFlowRecord,
  waitMs = CALLBACK_WAIT_MS,
): Promise<void> {
  await Promise.race([
    record.process.exited,
    new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, waitMs);
      timer.unref?.();
    }),
  ]);
}

const escape = (text: string): string =>
  text.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);

/**
 * The page the browser lands on: one heading and one sentence in the browser's language, the
 * hub's colours in light and dark, and a button that closes the tab the MCP page opened. No
 * script but the close; no value from the query is echoed.
 */
export function callbackPage(
  outcome: CallbackOutcome,
  name: string,
  language: Language,
  detail: { tools?: number; error?: string | null } = {},
): string {
  const key = `agents.mcp_oauth`;
  const title = t(`${key}.${outcome}_title`, language);
  const body = t(`${key}.${outcome}`, language)
    .replace('{server}', name)
    .replace('{count}', String(detail.tools ?? 0));
  const reason = outcome === 'failed' && detail.error ? detail.error : null;
  const product = language === 'ar' ? PRODUCT.nameAr : PRODUCT.name;
  const dir = language === 'ar' ? 'rtl' : 'ltr';
  const tone = outcome === 'connected' ? '#1f7a4d' : outcome === 'pending' ? '#8a5a00' : '#b3261e';
  return `<!doctype html>
<html lang="${language}" dir="${dir}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${escape(title)} · ${escape(product)}</title>
<style>
:root { color-scheme: light dark; --bg: #f6f7f9; --card: #ffffff; --text: #16181d; --muted: #5b6270; --line: #e3e6eb; }
@media (prefers-color-scheme: dark) { :root { --bg: #111317; --card: #1b1e24; --text: #eef0f3; --muted: #a3aab6; --line: #2c3139; } }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px; background: var(--bg); color: var(--text); font: 15px/1.6 system-ui, -apple-system, "Segoe UI", "Noto Sans Arabic", sans-serif; }
main { max-width: 28rem; width: 100%; background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 24px; }
.product { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
.reason { color: var(--muted); font-size: 13px; overflow-wrap: anywhere; }
h1 { font-size: 18px; margin: 0 0 8px; color: ${tone}; }
p { margin: 0 0 16px; }
button { font: inherit; border: 1px solid var(--line); background: transparent; color: var(--text); border-radius: 10px; padding: 6px 14px; cursor: pointer; }
</style>
</head>
<body>
<main data-outcome="${outcome}">
<p class="product">${escape(product)}</p>
<h1>${escape(title)}</h1>
<p>${escape(body)}</p>
${reason ? `<p class="reason" dir="auto">${escape(reason)}</p>\n` : ''}<button type="button" onclick="window.close()">${escape(t(`${key}.close`, language))}</button>
</main>
</body>
</html>
`;
}
