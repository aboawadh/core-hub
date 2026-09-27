/**
 * Settings → Secrets: the owner's view of every secret the hub holds or knows of (owner's
 * request 2026-09-28, DECISIONS §125).
 *
 *   secrets.list    POST /secrets/list    names only, grouped by kind and profile
 *   secrets.reveal  POST /secrets/reveal  one value, by the id the list gave
 *
 * Both are the owner's alone (`x-roles: [owner]`), from a web sign-in session only, and both
 * need a live step-up grant — the password asked again each time the page is opened
 * (`auth.stepUp`); being signed in is not enough. Every list and every reveal is an audit row
 * that names the secret and never holds its value; the answers are `Cache-Control: no-store`;
 * no value is put in a log line, an error or a cache.
 *
 * The secrets come from where they already live, through each owner module's public surface:
 * the provider keys in this module's encrypted store, the channel variables, MCP credentials
 * and incoming webhook secrets in the Hermes profiles' files (`agents`), and the outgoing
 * webhooks' signing secrets (`notify`). An id is a digest of where the secret sits, so reveal
 * finds it again by listing, and nothing a client sends is ever used as a path.
 */
import { createHash } from 'node:crypto';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { HubError } from '../../lib/errors.js';
import { defineRoute, type RouteDeps } from '../../lib/route.js';
import { hermesSecretsFor } from '../agents/index.js';
import { auditFor } from '../audit/index.js';
import { defaultWorkspace, findWorkspace, stepUpFor, type Principal } from '../auth/index.js';
import { webhookSecretsFor } from '../notify/index.js';
import { requireSqlite } from '../../lib/db.js';
import { revealProviderKey, storedProviderKeys } from './stored-keys.js';
import type { DataKeyRing } from './crypto.js';

export const SECRET_KINDS = [
  'provider_key',
  'channel',
  'mcp',
  'webhook_out',
  'webhook_in',
] as const;
export type SecretKind = (typeof SECRET_KINDS)[number];

export interface SecretEntry {
  id: string;
  kind: SecretKind;
  profile: string | null;
  label: string | null;
  name: string;
}

interface Located extends SecretEntry {
  read(): string | null;
}

const idOf = (kind: SecretKind, profile: string | null, where: string) =>
  createHash('sha256')
    .update(`${kind}\u0000${profile ?? ''}\u0000${where}`)
    .digest('hex')
    .slice(0, 24);

/** Every secret, where it sits, in the order the page shows them. */
export function locateSecrets(app: FastifyInstance, keys: () => DataKeyRing): Located[] {
  const db = requireSqlite(app.hub.database);
  const home = defaultWorkspace(db);
  const defaultSlug = home?.slug ?? 'default';
  const out: Located[] = [];

  for (const key of storedProviderKeys(db)) {
    const profile = key.shared ? null : (findWorkspace(db, key.workspaceId)?.slug ?? null);
    out.push({
      id: idOf('provider_key', profile, key.secretId),
      kind: 'provider_key',
      profile,
      label: key.labels.join(' · ') || null,
      name: key.family,
      read: () => revealProviderKey(db, keys(), key.workspaceId, key.secretId),
    });
  }
  for (const secret of hermesSecretsFor(app, defaultSlug)) {
    out.push({
      id: idOf(secret.kind, secret.profile, `${secret.label}\u0000${secret.name}`),
      kind: secret.kind,
      profile: secret.profile,
      label: secret.label,
      name: secret.name,
      read: () => secret.read(),
    });
  }
  for (const hook of webhookSecretsFor(app)) {
    out.push({
      id: idOf('webhook_out', null, hook.id),
      kind: 'webhook_out',
      profile: null,
      label: hook.name,
      name: 'signing_secret',
      read: () => hook.read(),
    });
  }
  const rank = (kind: SecretKind) => SECRET_KINDS.indexOf(kind);
  return out.sort(
    (a, b) =>
      rank(a.kind) - rank(b.kind) ||
      (a.profile ?? '').localeCompare(b.profile ?? '') ||
      (a.label ?? '').localeCompare(b.label ?? '') ||
      a.name.localeCompare(b.name),
  );
}

const noStore = (reply: FastifyReply) => void reply.header('cache-control', 'no-store');

const present = ({ id, kind, profile, label, name }: Located): SecretEntry => ({
  id,
  kind,
  profile,
  label,
  name,
});

export function registerSecretsRoutes(
  app: FastifyInstance,
  deps: RouteDeps,
  keys: () => DataKeyRing,
): void {
  const audit = auditFor(app);
  const guard = (request: FastifyRequest, grant: string): Principal => {
    const principal = request.principal!;
    stepUpFor(app.hub.io).assert(principal, grant, 'secrets');
    return principal;
  };

  defineRoute(app, deps, {
    operationId: 'secrets.list',
    handler: (request, { body }, reply) => {
      const { grant } = body as { grant: string };
      const principal = guard(request, grant);
      const items = locateSecrets(app, keys).map(present);
      audit.record({
        actorKind: 'user',
        actorId: principal.user.id,
        ownerId: principal.user.id,
        action: 'secrets.listed',
        summary: `secrets listed (${items.length})`,
        data: { count: items.length },
        requestId: request.id,
      });
      noStore(reply);
      return { items };
    },
  });

  defineRoute(app, deps, {
    operationId: 'secrets.reveal',
    handler: (request, { body }, reply) => {
      const { grant, id } = body as { grant: string; id: string };
      const principal = guard(request, grant);
      const found = locateSecrets(app, keys).find((entry) => entry.id === id);
      const value = found?.read() ?? null;
      if (!found || value === null) {
        throw new HubError('not_found', { details: { resource: 'secret', id } });
      }
      // Who, which secret, when — never the value.
      audit.record({
        actorKind: 'user',
        actorId: principal.user.id,
        ownerId: principal.user.id,
        action: 'secrets.revealed',
        entityKind: 'secret',
        entityId: found.id,
        summary:
          `secret revealed: ${found.kind} ${found.profile ?? 'hub'} ${found.label ?? ''} ${found.name}`.trim(),
        data: {
          kind: found.kind,
          profile: found.profile,
          label: found.label,
          name: found.name,
        },
        requestId: request.id,
      });
      noStore(reply);
      return { id: found.id, value };
    },
  });
}
