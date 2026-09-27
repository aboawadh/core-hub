/**
 * An agent's MCP servers, which live in **one block of a file we do not own**.
 *
 * Hermes keeps them in `${HERMES_HOME}/config.yaml` under `mcp_servers:`, in the same
 * file as `platforms:` and whatever else the person put there. So this module edits that
 * one node and leaves the document otherwise untouched — `yaml`'s document API keeps the
 * comments, the key order and the quoting style, because a hub that reformatted somebody's
 * config to change one flag would be the reason they stop editing it by hand.
 *
 * **A secret that goes in does not come back.** An `env` value whose key looks like a
 * credential is read as `[stored]`, the same shape the updates module uses for its source
 * token; writing `[stored]` back means "keep the one that is there", so a person can edit
 * a command without being asked to retype a key they cannot see.
 *
 * **Connection is not claimed.** Hermes connects to these servers when it starts; the hub
 * writes the file and does not probe, so `connected` is false and `tools` is empty until
 * there is something that actually asks. A screen that showed a green dot here would be
 * inventing a measurement.
 */
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { parseDocument, type Document } from 'yaml';

export const CONFIG_FILE = 'config.yaml';
const BLOCK = 'mcp_servers';
const NAME = /^[A-Za-z0-9._-]{1,60}$/;

/** Written once, read as the fact that there is one. */
export const STORED = '[stored]';
/** A key whose value is a credential. Matched on the name, because that is all we have. */
const SECRET_KEY = /(key|token|secret|password|passwd|credential|auth)/i;

export type Transport = 'stdio' | 'http' | 'sse';

export interface McpServer {
  name: string;
  transport: Transport;
  enabled: boolean;
  /** The block as stored, with credentials masked. */
  config: Record<string, unknown>;
  /** The block says `auth: oauth`: Hermes signs in to it by OAuth (DECISIONS §122). */
  oauth: boolean;
}

/**
 * The blocks one level down whose values are masked by their key's name, like the block's own
 * keys: a process's `env`, a socket's `headers` (`Authorization: Bearer …`) and the `oauth`
 * settings (`client_secret`). Before DECISIONS §122 only `env` was, and a header's key came
 * back to the client as it was written.
 */
const NESTED = new Set(['env', 'headers', 'oauth']);

/** `auth: oauth` names how the server signs in, not a credential; any other `auth` is one. */
const isSecret = (key: string, value: unknown): boolean =>
  SECRET_KEY.test(key) && raw(value) && !(key === 'auth' && value === 'oauth');

export class McpError extends Error {
  constructor(readonly reason: string) {
    super(reason);
    this.name = 'McpError';
  }
}

export function configPath(home: string): string {
  return path.join(home, CONFIG_FILE);
}

function load(home: string): Document {
  const file = configPath(home);
  if (!existsSync(file)) return parseDocument('');
  const doc = parseDocument(readFileSync(file, 'utf8'));
  // A config we cannot parse is a config we must not rewrite: saving would replace the
  // person's file with our idea of it, and their agent would stop where it stood.
  if (doc.errors.length > 0) throw new McpError('config_unreadable');
  return doc;
}

function save(home: string, doc: Document): void {
  mkdirSync(home, { recursive: true });
  writeFileSync(configPath(home), doc.toString(), 'utf8');
}

/** `command` means a process; `url` means a socket. Hermes's own shapes, not ours. */
function transportOf(config: Record<string, unknown>): Transport {
  if (typeof config.url === 'string') {
    return String(config.type ?? '').toLowerCase() === 'sse' ? 'sse' : 'http';
  }
  return 'stdio';
}

const isBlock = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);

function mask(config: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(config)) {
    if (key === 'env' && isBlock(value)) {
      const env: Record<string, unknown> = {};
      for (const [name, raw] of Object.entries(value)) {
        env[name] = SECRET_KEY.test(name) && raw !== '' && raw !== undefined ? STORED : raw;
      }
      out[key] = env;
      continue;
    }
    if (NESTED.has(key) && isBlock(value)) {
      const inner: Record<string, unknown> = {};
      for (const [name, raw] of Object.entries(value)) {
        inner[name] = isSecret(name, raw) ? STORED : raw;
      }
      out[key] = inner;
      continue;
    }
    out[key] = isSecret(key, value) ? STORED : value;
  }
  return out;
}

function raw(value: unknown): boolean {
  return typeof value === 'string' && value !== '';
}

/**
 * Put the masked values back before writing.
 *
 * Without this, saving a server after looking at it would write the literal `[stored]`
 * into the file and break the server the next time Hermes started — the exact failure a
 * masked field invites.
 */
function unmask(
  next: Record<string, unknown>,
  previous: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(next)) {
    if (NESTED.has(key) && isBlock(value)) {
      const before = isBlock(previous[key]) ? previous[key] : {};
      const inner: Record<string, unknown> = {};
      for (const [name, raw] of Object.entries(value)) {
        inner[name] = raw === STORED ? before[name] : raw;
      }
      out[key] = inner;
      continue;
    }
    out[key] = value === STORED ? previous[key] : value;
  }
  return out;
}

export function listMcpServers(home: string): McpServer[] {
  const doc = load(home);
  const block = doc.toJS()?.[BLOCK] as Record<string, Record<string, unknown>> | undefined;
  if (!block || typeof block !== 'object') return [];
  return Object.entries(block)
    .filter(([, config]) => config && typeof config === 'object')
    .map(([name, config]) => ({
      name,
      transport: transportOf(config),
      // A server with no `enabled` is on: that is what an absent flag means in this file.
      enabled: config.enabled !== false,
      config: mask(config),
      oauth: config.auth === 'oauth',
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function getMcpServer(home: string, name: string): McpServer | null {
  return listMcpServers(home).find((server) => server.name === name) ?? null;
}

export interface McpWrite {
  transport?: Transport | undefined;
  enabled?: boolean | undefined;
  config?: Record<string, unknown> | undefined;
}

/** Create or replace one server, leaving every other byte of the file alone. */
export function putMcpServer(home: string, name: string, input: McpWrite): McpServer {
  if (!NAME.test(name)) throw new McpError('mcp_name_invalid');
  const doc = load(home);
  const existing = (doc.toJS()?.[BLOCK]?.[name] ?? {}) as Record<string, unknown>;
  const config = input.config ? unmask(input.config, existing) : existing;
  const merged: Record<string, unknown> = { ...config };
  if (input.enabled !== undefined) merged.enabled = input.enabled;
  if (Object.keys(merged).length === 0) throw new McpError('mcp_config_empty');
  doc.setIn([BLOCK, name], merged);
  save(home, doc);
  const written = getMcpServer(home, name);
  if (!written) throw new McpError('mcp_write_failed');
  return written;
}

export function deleteMcpServer(home: string, name: string): void {
  if (!NAME.test(name)) throw new McpError('mcp_name_invalid');
  const doc = load(home);
  const block = doc.toJS()?.[BLOCK] as Record<string, unknown> | undefined;
  if (!block || !(name in block)) throw new McpError('mcp_not_found');
  doc.deleteIn([BLOCK, name]);
  save(home, doc);
}

/**
 * Point the server's OAuth callback at `uri` (`oauth.redirect_uri`, the key Hermes reads first
 * for a browser sign-in; DECISIONS §122), leaving every other key and byte alone. A
 * `redirect_uri` the person wrote themselves — anything `ours` does not recognise as the hub's
 * own callback — is kept. Answers the URI that is in the file afterwards.
 */
export function setOAuthRedirect(
  home: string,
  name: string,
  uri: string,
  ours: (current: string) => boolean,
): string {
  if (!NAME.test(name)) throw new McpError('mcp_name_invalid');
  const doc = load(home);
  const block = doc.toJS()?.[BLOCK]?.[name] as Record<string, unknown> | undefined;
  if (!block || typeof block !== 'object') throw new McpError('mcp_not_found');
  const oauth = isBlock(block.oauth) ? block.oauth : null;
  const current = typeof oauth?.redirect_uri === 'string' ? oauth.redirect_uri.trim() : '';
  if (current && !ours(current)) return current;
  if (current === uri) return uri;
  if (block.oauth !== undefined && !oauth) throw new McpError('mcp_oauth_block_invalid');
  doc.setIn([BLOCK, name, 'oauth', 'redirect_uri'], uri);
  save(home, doc);
  return uri;
}
