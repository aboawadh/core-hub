/**
 * The provider keys the hub holds, by name, for Settings → Secrets (DECISIONS §125).
 *
 * One stored key serves every provider row of its credential family in its scope (ADR 0010),
 * so a key is listed once, labelled with the providers that use it. Nothing here decrypts
 * except `reveal`, whose one caller is the owner's step-up-guarded `secrets.reveal`.
 */
import { isNull } from 'drizzle-orm';
import type { ModuleDb } from '../../lib/db.js';
import type { DataKeyRing } from './crypto.js';
import { SecretStore } from './secrets.js';
import { providers, secrets } from './schema.js';

export interface StoredProviderKey {
  secretId: string;
  /** The workspace row the key is stored under (the default profile's for a shared key). */
  workspaceId: string;
  /** Every profile's key (decision §37); false: one profile's own. */
  shared: boolean;
  family: string;
  /** The providers that use it, by their label, sorted. */
  labels: string[];
}

export function storedProviderKeys(db: ModuleDb): StoredProviderKey[] {
  const live = new Map(
    db
      .select()
      .from(secrets)
      .all()
      .filter((row) => !!row.ciphertext && !row.wipedAt)
      .map((row) => [row.id, row]),
  );
  const byId = new Map<string, StoredProviderKey>();
  for (const row of db.select().from(providers).where(isNull(providers.archivedAt)).all()) {
    if (!row.apiKeySecretId) continue;
    const secret = live.get(row.apiKeySecretId);
    if (!secret) continue;
    const entry = byId.get(secret.id) ?? {
      secretId: secret.id,
      workspaceId: secret.workspace,
      shared: row.shared,
      family: row.family,
      labels: [],
    };
    if (!entry.labels.includes(row.label)) entry.labels.push(row.label);
    entry.shared ||= row.shared;
    byId.set(secret.id, entry);
  }
  return [...byId.values()].map((entry) => ({
    ...entry,
    labels: entry.labels.sort((a, b) => a.localeCompare(b)),
  }));
}

/** One key's plaintext, or null when it is gone. */
export function revealProviderKey(
  db: ModuleDb,
  keys: DataKeyRing,
  workspaceId: string,
  secretId: string,
): string | null {
  return new SecretStore({ db, keys }).reveal(workspaceId, secretId);
}
