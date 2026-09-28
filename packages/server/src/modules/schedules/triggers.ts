/**
 * Inbound workflow triggers (DECISIONS §123): how a delivery from outside proves who sent it,
 * what event it carries, and how a repeat of it is recognised. Pure functions over the raw
 * bytes and the headers; the table work is `trigger-desk.ts`.
 *
 * The signature is always checked over the **raw body exactly as received**, never over a
 * re-serialisation of the parsed JSON: a sender signs its bytes, and `JSON.stringify` of the
 * parsed body is a different string as soon as the sender's spacing, key order or escaping
 * differs. Only a delivery that passed is parsed. The comparison is constant-time: both sides
 * are hashed first, so neither their content nor their length leaks through timing.
 */
import { createHash, createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import type { WorkflowTriggerPreset } from './schema.js';

/** The largest body a trigger reads (a larger one is refused with 413 before anything else). */
export const MAX_TRIGGER_BODY_BYTES = 1024 * 1024;
/** How long a delivery's key is remembered, and how long the delivery log keeps a line. */
export const TRIGGER_MEMORY_MS = 7 * 24 * 60 * 60 * 1000;
/** The most delivery lines kept per trigger. */
export const MAX_DELIVERY_LINES = 500;
/** How much of a body the delivery log keeps. */
export const BODY_PREVIEW_CHARS = 1000;

export interface SignatureSettings {
  preset: WorkflowTriggerPreset;
  signatureHeader: string | null;
  signatureEncoding: 'hex' | 'base64' | null;
  signaturePrefix: string | null;
}

export type Headers = Record<string, string | string[] | undefined>;

/** The header a preset reads its signature (or its token) from, lower-case. */
export function signatureHeaderOf(settings: SignatureSettings): string {
  switch (settings.preset) {
    case 'clickup':
      return 'x-signature';
    case 'github':
      return 'x-hub-signature-256';
    case 'generic_hmac':
      return (settings.signatureHeader ?? 'x-signature').trim().toLowerCase();
    case 'token':
      return (settings.signatureHeader ?? 'x-webhook-token').trim().toLowerCase();
  }
}

export function headerOf(headers: Headers, name: string): string | null {
  const value = headers[name.toLowerCase()];
  const first = Array.isArray(value) ? value[0] : value;
  return typeof first === 'string' && first !== '' ? first : null;
}

const sha256 = (value: string | Buffer) => createHash('sha256').update(value).digest();

/** Equal or not, in the same time whatever the two are (their lengths included). */
export function sameSecret(given: string, expected: string): boolean {
  return timingSafeEqual(sha256(given), sha256(expected));
}

function hmac(secret: string, raw: Buffer, encoding: 'hex' | 'base64'): string {
  return createHmac('sha256', secret).update(raw).digest(encoding);
}

export type Verdict = { ok: true } | { ok: false; reason: string };

/** Whether `raw`, as received, was signed with `secret` the way the trigger's preset says. */
export function verify(
  settings: SignatureSettings,
  secret: string | null,
  raw: Buffer,
  headers: Headers,
): Verdict {
  if (!secret) return { ok: false, reason: 'no secret is stored for this trigger yet' };
  const name = signatureHeaderOf(settings);
  const given = headerOf(headers, name)?.trim() ?? null;
  if (!given) return { ok: false, reason: `the ${name} header is missing` };
  let expected: string;
  let presented = given;
  switch (settings.preset) {
    case 'clickup':
      expected = hmac(secret, raw, 'hex');
      presented = given.toLowerCase();
      break;
    case 'github':
      expected = `sha256=${hmac(secret, raw, 'hex')}`;
      presented = given.toLowerCase();
      break;
    case 'generic_hmac': {
      const prefix = settings.signaturePrefix ?? '';
      if (prefix && !given.startsWith(prefix)) {
        return { ok: false, reason: `the ${name} header does not start with "${prefix}"` };
      }
      presented = given.slice(prefix.length);
      const encoding = settings.signatureEncoding ?? 'hex';
      expected = hmac(secret, raw, encoding);
      if (encoding === 'hex') presented = presented.toLowerCase();
      break;
    }
    case 'token':
      expected = secret;
      break;
  }
  return sameSecret(presented, expected)
    ? { ok: true }
    : { ok: false, reason: `the ${name} header does not match the stored secret` };
}

/** The headers that make `raw` pass `verify` — what "Send test event" sends. */
export function signatureHeaders(
  settings: SignatureSettings,
  secret: string,
  raw: Buffer,
): Record<string, string> {
  const name = signatureHeaderOf(settings);
  switch (settings.preset) {
    case 'clickup':
      return { [name]: hmac(secret, raw, 'hex') };
    case 'github':
      return { [name]: `sha256=${hmac(secret, raw, 'hex')}` };
    case 'generic_hmac':
      return {
        [name]: `${settings.signaturePrefix ?? ''}${hmac(secret, raw, settings.signatureEncoding ?? 'hex')}`,
      };
    case 'token':
      return { [name]: secret };
  }
}

/** What a delivery is about, read after its signature passed. */
export interface EventFacts {
  event: string | null;
  eventId: string | null;
  taskId: string | null;
  /** Stable across repeats of the same delivery; at most 200 characters. */
  dedupeKey: string;
}

/** Headers some senders put a delivery's own id in (a repeat carries the same one). */
const DELIVERY_ID_HEADERS = [
  'x-github-delivery',
  'x-delivery-id',
  'x-webhook-id',
  'webhook-id',
  'x-event-id',
  'idempotency-key',
  'x-request-id',
];

function text(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() === '' ? null : value.slice(0, 200);
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return null;
}

function field(body: unknown, name: string): unknown {
  return body && typeof body === 'object' && !Array.isArray(body)
    ? (body as Record<string, unknown>)[name]
    : undefined;
}

/** A key of at most 200 characters: a long one is replaced by its hash. */
function bounded(key: string): string {
  return key.length <= 200 ? key : `sha256:${sha256(key).toString('hex')}`;
}

/** The event, its ids and the repeat key, per preset. */
export function factsOf(
  preset: WorkflowTriggerPreset,
  body: unknown,
  raw: Buffer,
  headers: Headers,
): EventFacts {
  const bodyHash = `sha256:${sha256(raw).toString('hex')}`;
  const deliveryId = DELIVERY_ID_HEADERS.map((name) => headerOf(headers, name)).find(Boolean);
  if (preset === 'clickup') {
    const items = field(body, 'history_items');
    const ids = (Array.isArray(items) ? items : [])
      .map((item) => text(field(item, 'id')))
      .filter((id): id is string => id !== null)
      .sort();
    const webhookId = text(field(body, 'webhook_id'));
    return {
      event: text(field(body, 'event')),
      eventId: ids.length > 0 ? ids.join(',').slice(0, 200) : null,
      taskId: text(field(body, 'task_id')),
      dedupeKey: bounded(ids.length > 0 ? `clickup:${webhookId ?? ''}:${ids.join(',')}` : bodyHash),
    };
  }
  if (preset === 'github') {
    const action = text(field(body, 'action'));
    const event = headerOf(headers, 'x-github-event');
    return {
      event: event ? event.slice(0, 200) : action,
      eventId: deliveryId ? deliveryId.slice(0, 200) : null,
      taskId: null,
      dedupeKey: bounded(deliveryId ? `delivery:${deliveryId}` : bodyHash),
    };
  }
  const event =
    text(field(body, 'event')) ??
    text(field(body, 'type')) ??
    text(field(body, 'event_type')) ??
    headerOf(headers, 'x-event-type')?.slice(0, 200) ??
    null;
  return {
    event,
    eventId: deliveryId ? deliveryId.slice(0, 200) : text(field(body, 'id')),
    taskId: text(field(body, 'task_id')),
    dedupeKey: bounded(deliveryId ? `delivery:${deliveryId}` : bodyHash),
  };
}

/**
 * The headers a run may read (`{{trigger.headers.<name>}}`, with `-` written `_`): a short
 * allow-list, and never one that carries a signature, a token or a secret.
 */
const SAFE_HEADERS = [
  'content-type',
  'user-agent',
  'x-github-event',
  'x-github-delivery',
  'x-github-hook-id',
  'x-event-type',
  'x-delivery-id',
  'x-webhook-id',
  'webhook-id',
  'x-event-id',
  'x-request-id',
  'idempotency-key',
];
const SECRET_LOOKING = /signature|secret|token|authorization|password|api[-_]?key/i;

export function safeHeaders(headers: Headers): Record<string, string> {
  const out: Record<string, string> = {};
  for (const name of SAFE_HEADERS) {
    if (SECRET_LOOKING.test(name)) continue;
    const value = headerOf(headers, name);
    if (value !== null) out[name.replace(/-/g, '_')] = value.slice(0, 500);
  }
  return out;
}

/** The body as the run reads it: JSON when it is JSON, the text otherwise. */
export function parseBody(raw: Buffer): unknown {
  const textBody = raw.toString('utf8');
  if (textBody.trim() === '') return null;
  try {
    return JSON.parse(textBody) as unknown;
  } catch {
    return textBody;
  }
}

function masked(value: unknown, depth = 0): unknown {
  if (depth > 12 || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map((item) => masked(item, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [key, inner] of Object.entries(value as Record<string, unknown>)) {
    out[key] = SECRET_LOOKING.test(key) ? '[masked]' : masked(inner, depth + 1);
  }
  return out;
}

/** The first characters of a body for the delivery log, secret-looking fields masked. */
export function previewOf(body: unknown): string | null {
  if (body === null || body === undefined) return null;
  const shown = typeof body === 'string' ? body : JSON.stringify(masked(body));
  return shown.length > BODY_PREVIEW_CHARS ? `${shown.slice(0, BODY_PREVIEW_CHARS)}…` : shown;
}

/** A sample delivery for "Send test event": its body and the headers a real one carries. */
export function sampleOf(
  preset: WorkflowTriggerPreset,
  options: { event: string; taskId: string; now?: Date },
): { raw: Buffer; headers: Record<string, string> } {
  const now = options.now ?? new Date();
  const unique = `${now.getTime()}${Math.floor(Math.random() * 1_000_000)
    .toString()
    .padStart(6, '0')}`;
  let body: Record<string, unknown>;
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (preset === 'clickup') {
    // The shape ClickUp documents for a task event, about a task that does not exist.
    body = {
      event: options.event,
      task_id: options.taskId,
      webhook_id: 'core-hub-test',
      history_items: [
        {
          id: unique,
          type: 1,
          date: String(now.getTime()),
          field: 'status',
          parent_id: 'core-hub-test-list',
          data: { status_type: 'custom' },
          source: null,
          user: {
            id: 0,
            username: 'Core Hub test event',
            email: '',
            color: '#7b68ee',
            initials: 'CH',
            profilePicture: null,
          },
          before: { status: 'to do', color: '#d3d3d3', type: 'open', orderindex: 0 },
          after: { status: 'in progress', color: '#4194f6', type: 'custom', orderindex: 1 },
        },
      ],
    };
  } else if (preset === 'github') {
    body = { action: 'opened', test: true, sender: { login: 'core-hub-test' } };
    headers['x-github-event'] = options.event;
    headers['x-github-delivery'] = randomUUID();
  } else {
    body = { event: options.event, id: unique, test: true, task_id: options.taskId };
    headers['x-delivery-id'] = randomUUID();
  }
  return { raw: Buffer.from(JSON.stringify(body)), headers };
}

/** The event a test sends when none is asked for. */
export function defaultTestEvent(preset: WorkflowTriggerPreset, events: readonly string[]): string {
  if (events.length > 0) return events[0]!;
  switch (preset) {
    case 'clickup':
      return 'taskCreated';
    case 'github':
      return 'issues';
    default:
      return 'test';
  }
}
