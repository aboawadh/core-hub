/**
 * Settings → Secrets, the pieces with no screen in them (DECISIONS §125): the hub's four calls,
 * the grouping the page draws, and what an answer from the hub means for the page.
 *
 * Nothing here keeps a value: the calls hand it back to the caller, which holds it in its own
 * state for as long as it is shown. No query cache, no storage.
 */
import { HubApiError, type HubClient } from '@corehub/contracts';

export type SecretKind = 'provider_key' | 'channel' | 'mcp' | 'webhook_out' | 'webhook_in';

export interface SecretEntry {
  id: string;
  kind: SecretKind | string;
  profile: string | null;
  label: string | null;
  name: string;
}

export interface StepUpGrant {
  grant: string;
  purpose: 'secrets';
  expires_at: string;
  ttl_seconds: number;
}

/** How long a shown value stays on the screen before it hides itself. */
export const REVEAL_MS = 30_000;

/** The order the page shows the kinds in; a kind this client does not know goes last. */
export const KIND_ORDER: readonly SecretKind[] = [
  'provider_key',
  'channel',
  'mcp',
  'webhook_out',
  'webhook_in',
];

export interface SecretGroup {
  kind: string;
  profiles: Array<{ profile: string | null; items: SecretEntry[] }>;
}

/** Grouped by kind, then by profile (the hub-wide ones first), keeping the hub's order inside. */
export function groupSecrets(items: readonly SecretEntry[]): SecretGroup[] {
  const rank = (kind: string) => {
    const at = KIND_ORDER.indexOf(kind as SecretKind);
    return at === -1 ? KIND_ORDER.length : at;
  };
  const kinds = [...new Set(items.map((item) => item.kind))].sort((a, b) => rank(a) - rank(b));
  return kinds.map((kind) => {
    const ofKind = items.filter((item) => item.kind === kind);
    const profiles = [...new Set(ofKind.map((item) => item.profile))].sort((a, b) =>
      a === null ? -1 : b === null ? 1 : a.localeCompare(b),
    );
    return {
      kind,
      profiles: profiles.map((profile) => ({
        profile,
        items: ofKind.filter((item) => item.profile === profile),
      })),
    };
  });
}

/** What a refusal means for this page. */
export type Refusal = 'wrong_password' | 'locked_out' | 'step_up_required' | 'unsupported' | null;

export function refusalOf(error: unknown): Refusal {
  if (!(error instanceof HubApiError)) return null;
  const reason = (error.body as { details?: { reason?: string } } | undefined)?.details?.reason;
  if (error.status === 401 && reason === 'wrong_password') return 'wrong_password';
  if (error.status === 429) return 'locked_out';
  if (error.status === 403 && reason === 'step_up_required') return 'step_up_required';
  // A hub older than this page has none of these operations (ADR 0027: a newer app, an older hub).
  if (error.status === 404 || error.status === 501) return 'unsupported';
  return null;
}

type Client = Pick<HubClient, 'raw'>;

export async function stepUp(client: Client, password: string): Promise<StepUpGrant> {
  const res = await client.raw('post', '/auth/step-up', {
    body: { password, purpose: 'secrets' },
  });
  return res.data as StepUpGrant;
}

/** Ends the grant on the hub; a failure changes nothing for the person (it runs out anyway). */
export async function endStepUp(client: Client): Promise<void> {
  try {
    await client.raw('delete', '/auth/step-up', {});
  } catch {
    // the grant runs out on its own in minutes
  }
}

export async function listSecrets(client: Client, grant: string): Promise<SecretEntry[]> {
  const res = await client.raw('post', '/secrets/list', { body: { grant } });
  return (res.data as { items: SecretEntry[] }).items;
}

export async function revealSecret(client: Client, grant: string, id: string): Promise<string> {
  const res = await client.raw('post', '/secrets/reveal', { body: { grant, id } });
  return (res.data as { value: string }).value;
}
