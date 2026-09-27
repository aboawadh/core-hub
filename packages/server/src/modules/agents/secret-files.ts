/**
 * The secrets in Hermes's own files that the hub already knows are secret, by name, for the
 * owner's Settings → Secrets (DECISIONS §125):
 *
 * - `channel`: a messaging platform's secret variable in a profile's `.env` — the ones
 *   `channel-platforms.ts` declares `secret` for its platform;
 * - `mcp`: a credential in an MCP server's block of the profile's `config.yaml`, the ones the
 *   MCP pages show as `[stored]` (`mcp.ts`);
 * - `webhook_in`: an incoming webhook route's own secret (`hermes-webhooks.ts`).
 *
 * Each entry reads its value again when asked (`read`), from the file as it is then; nothing
 * here keeps a value, and listing reads the files only to learn the names.
 */
import path from 'node:path';
import { PLATFORMS } from './channel-platforms.js';
import { namedHermesProfiles } from './hermes-profiles.js';
import { listWebhooks } from './hermes-webhooks.js';
import { mcpCredentials } from './mcp.js';
import { readEnv } from './profile-env.js';

export type HermesSecretKind = 'channel' | 'mcp' | 'webhook_in';

export interface HermesSecret {
  kind: HermesSecretKind;
  /** The hub profile's slug the Hermes profile is. */
  profile: string;
  /** What it belongs to: the platform, the MCP server, the webhook route. */
  label: string;
  /** Its own name: the variable, the place in the server's block. */
  name: string;
  /** The value as the file holds it now, or null when it is gone. */
  read(): string | null;
}

/** Every Hermes profile home under `root`, with the hub profile slug each one is. */
function homesOf(root: string, defaultSlug: string): Array<[string, string]> {
  return [
    [defaultSlug, root],
    ...namedHermesProfiles(root).map((name): [string, string] => [
      name,
      path.join(root, 'profiles', name),
    ]),
  ];
}

export function hermesSecrets(root: string, defaultSlug: string): HermesSecret[] {
  const out: HermesSecret[] = [];
  for (const [profile, home] of homesOf(root, defaultSlug)) {
    const env = readEnv(home);
    const seen = new Set<string>();
    for (const spec of PLATFORMS) {
      for (const credential of spec.credentials) {
        if (credential.kind !== 'secret' || seen.has(credential.key)) continue;
        if (!env[credential.key]) continue;
        seen.add(credential.key);
        const key = credential.key;
        out.push({
          kind: 'channel',
          profile,
          label: spec.label,
          name: key,
          read: () => readEnv(home)[key] || null,
        });
      }
    }
    for (const credential of mcpCredentials(home)) {
      const { server, path: where } = credential;
      out.push({
        kind: 'mcp',
        profile,
        label: server,
        name: where,
        read: () =>
          mcpCredentials(home).find((c) => c.server === server && c.path === where)?.value ?? null,
      });
    }
    let routes: ReturnType<typeof listWebhooks>;
    try {
      routes = listWebhooks(home);
    } catch {
      routes = [];
    }
    for (const route of routes) {
      if (!route.secret) continue;
      const name = route.name;
      out.push({
        kind: 'webhook_in',
        profile,
        label: name,
        name: 'secret',
        read: () => {
          try {
            return listWebhooks(home).find((r) => r.name === name)?.secret ?? null;
          } catch {
            return null;
          }
        },
      });
    }
  }
  return out;
}
