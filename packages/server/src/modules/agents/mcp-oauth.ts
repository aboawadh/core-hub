/**
 * Signing an MCP server in by OAuth from the hub's pages (contract decision §122).
 *
 * Hermes already does the whole sign-in for its own dashboard (MIT source
 * `hermes_cli/web_routers/mcp.py` and `tools/mcp_dashboard_oauth.py`, tag v2026.9.14, read and
 * described here in our words): `POST /api/mcp/servers/{name}/auth?profile=` discovers the
 * provider, registers a client, and answers a flow with the provider's authorization URL;
 * `GET /api/mcp/oauth/flows/{id}` says how it stands (`starting`, `authorization_required`,
 * `approved` with the tools it then listed, or `error`); `DELETE` of the same stops it; and
 * `GET /api/mcp/oauth/callback/{server}` takes the browser back, accepting it only with the
 * `state` of a flow it started. Hermes keeps the tokens in the profile's home, under
 * `mcp-tokens/<server>.json` (plus `.client.json`, `.meta.json` and `.cimd-off`).
 *
 * What the hub adds is the part Hermes cannot know from inside the container: **where the
 * browser can come back to.** Hermes would name its own address, `127.0.0.1:<port>`, which the
 * person's browser cannot reach. Hermes reads the server's `oauth.redirect_uri` first, so the
 * hub writes its own public callback there before starting, and relays the browser's arrival to
 * Hermes's callback. **No token passes through the hub**: it reads only the metadata of the
 * token file (whether it exists, when it runs out, whether it can be renewed) and never returns
 * or logs a value from it.
 */
import { existsSync, readFileSync, statSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { HubError } from '../../lib/errors.js';
import { t, type Language } from '../../i18n/index.js';
import { PRODUCT } from '@corehub/contracts';
import { HermesDashboardRefusal, HermesDashboardUnavailable } from './hermes-dashboard.js';
import { hermesFault, type HermesApiCall } from './hermes-tools.js';

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

/** Hermes's `DashboardOAuthFlow.snapshot()`, plus `tools` on its status route. */
interface HermesFlow {
  flow_id?: unknown;
  status?: unknown;
  authorization_url?: unknown;
  error?: unknown;
  tools?: unknown;
}

/** Hermes's sentence for a flow its `DELETE` stopped (`web_routers/mcp.py`). */
const CANCELLED = 'Cancelled by user';

/** Hermes forgets a flow fifteen minutes after it started (`_MCP_DASHBOARD_OAUTH_TTL`). */
export const FLOW_TTL_MS = 15 * 60_000;

/** How long starting may take: Hermes waits up to 30 s for the provider's authorization URL. */
export const START_TIMEOUT_MS = 45_000;

export interface McpOAuthFlowRecord {
  id: string;
  agentId: string;
  profile: string;
  serverName: string;
  hermesFlowId: string;
  redirectUri: string;
  createdAt: number;
  /** What was last heard, answered again once Hermes has forgotten the flow. */
  last: McpOAuthView;
}

/** The contract's `McpOAuthFlow` minus the fields the record fills. */
export interface McpOAuthView {
  status: McpOAuthStatus;
  authorization_url: string | null;
  error: string | null;
  tools: Array<{ name: string; description: string | null }>;
}

/** Hermes's state as the contract's. */
export function viewOf(flow: HermesFlow): McpOAuthView {
  const status = typeof flow.status === 'string' ? flow.status : 'starting';
  const error = typeof flow.error === 'string' && flow.error.trim() ? flow.error.trim() : null;
  const mapped: McpOAuthStatus =
    status === 'approved'
      ? 'approved'
      : status === 'error'
        ? error === CANCELLED
          ? 'cancelled'
          : 'failed'
        : status === 'starting' || status === 'authorization_required'
          ? 'pending'
          : 'failed';
  const tools = Array.isArray(flow.tools)
    ? (flow.tools as Array<{ name?: unknown; description?: unknown }>)
        .filter((tool) => typeof tool?.name === 'string')
        .map((tool) => ({
          name: tool.name as string,
          description:
            typeof tool.description === 'string' && tool.description !== ''
              ? tool.description
              : null,
        }))
    : [];
  return {
    status: mapped,
    authorization_url:
      typeof flow.authorization_url === 'string' && flow.authorization_url
        ? flow.authorization_url
        : null,
    error: mapped === 'approved' || mapped === 'pending' ? null : error,
    tools: mapped === 'approved' ? tools : [],
  };
}

const query = (profile: string) => `profile=${encodeURIComponent(profile)}`;

/** Start Hermes's sign-in for `name` in `profile`. Hermes's refusals are the hub's (`hermesFault`). */
export async function startHermesFlow(
  api: HermesApiCall,
  profile: string,
  name: string,
): Promise<{ hermesFlowId: string; view: McpOAuthView }> {
  let flow: HermesFlow;
  try {
    flow = await api<HermesFlow>(
      'POST',
      `/api/mcp/servers/${encodeURIComponent(name)}/auth?${query(profile)}`,
      undefined,
      { timeoutMs: START_TIMEOUT_MS },
    );
  } catch (error) {
    return hermesFault(error);
  }
  const id = typeof flow?.flow_id === 'string' ? flow.flow_id : '';
  if (!id) {
    throw new HubError('service_unavailable', {
      details: { reason: 'hermes_api_unavailable', message: 'Hermes started no sign-in' },
    });
  }
  return { hermesFlowId: id, view: viewOf(flow) };
}

/** How Hermes's flow stands now; `expired` once Hermes no longer knows it. */
export async function pollHermesFlow(
  api: HermesApiCall,
  hermesFlowId: string,
): Promise<McpOAuthView | null> {
  try {
    return viewOf(
      await api<HermesFlow>('GET', `/api/mcp/oauth/flows/${encodeURIComponent(hermesFlowId)}`),
    );
  } catch (error) {
    if (error instanceof HermesDashboardRefusal && error.status === 404) return null;
    return hermesFault(error);
  }
}

/** Ask Hermes to stop waiting. Idempotent on Hermes's side. */
export async function cancelHermesFlow(api: HermesApiCall, hermesFlowId: string): Promise<void> {
  try {
    await api('DELETE', `/api/mcp/oauth/flows/${encodeURIComponent(hermesFlowId)}`);
  } catch (error) {
    if (error instanceof HermesDashboardRefusal && error.status === 404) return;
    hermesFault(error);
  }
}

/** The sign-ins this hub started, kept as long as Hermes keeps them. Per hub, in memory. */
export class McpOAuthFlows {
  private readonly flows = new Map<string, McpOAuthFlowRecord>();

  constructor(private readonly now: () => number = Date.now) {}

  add(record: McpOAuthFlowRecord): void {
    this.sweep();
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

  expiresAt(record: McpOAuthFlowRecord): string {
    return new Date(record.createdAt + FLOW_TTL_MS).toISOString();
  }

  private sweep(): void {
    const cutoff = this.now() - FLOW_TTL_MS;
    for (const [id, record] of this.flows) if (record.createdAt < cutoff) this.flows.delete(id);
  }
}

// ------------------------------------------------------------------ the callback

export type CallbackOutcome = 'received' | 'declined' | 'expired' | 'unavailable';

/**
 * Hand the browser's arrival to Hermes's callback, the query exactly as the provider sent it.
 * Nothing about it is logged or thrown: the query holds the authorization code, and an error
 * from the call would carry the URL with it.
 */
export async function relayCallback(
  api: HermesApiCall | null,
  name: string,
  rawQuery: string,
): Promise<CallbackOutcome> {
  if (!api) return 'unavailable';
  const params = new URLSearchParams(rawQuery);
  try {
    await api(
      'GET',
      `/api/mcp/oauth/callback/${encodeURIComponent(name)}${rawQuery ? `?${rawQuery}` : ''}`,
    );
    return params.has('error') ? 'declined' : 'received';
  } catch (error) {
    if (error instanceof HermesDashboardRefusal) {
      if (error.status === 404 || error.status === 409) return 'expired';
      return 'declined';
    }
    if (error instanceof HermesDashboardUnavailable) return 'unavailable';
    return 'unavailable';
  }
}

const escape = (text: string): string =>
  text.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);

/**
 * The page the browser lands on: one heading and one sentence in the browser's language, the
 * hub's colours in light and dark, and a button that closes the tab the MCP page opened. No
 * script but the close; no value from the query is echoed.
 */
export function callbackPage(outcome: CallbackOutcome, name: string, language: Language): string {
  const key = `agents.mcp_oauth`;
  const title =
    outcome === 'received' ? t(`${key}.title`, language) : t(`${key}.${outcome}_title`, language);
  const body = t(`${key}.${outcome}`, language).replace('{server}', name);
  const product = language === 'ar' ? PRODUCT.nameAr : PRODUCT.name;
  const dir = language === 'ar' ? 'rtl' : 'ltr';
  const tone = outcome === 'received' ? '#1f7a4d' : '#b3261e';
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
<button type="button" onclick="window.close()">${escape(t(`${key}.close`, language))}</button>
</main>
</body>
</html>
`;
}
