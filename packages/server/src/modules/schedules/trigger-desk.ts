/**
 * The tables behind inbound workflow triggers (DECISIONS §122): the triggers, the keys of the
 * deliveries already taken (so a repeat starts nothing), and the delivery log — and the one
 * receiving path both a real delivery and "Send test event" go through.
 *
 * Receiving, in order: the signature over the raw body (refused: `signature_rejected`, 401),
 * then the body is read, then the repeat check (`duplicate`, 200), then the trigger's event
 * filter (`filtered_out`, 200), then the run is queued (`run_started`, 202) — its steps go on
 * after the answer, so the sender gets its 2xx at once. When the run ends its line becomes
 * `run_succeeded` or `run_failed` (`settleRun`).
 */
import { and, desc, eq, inArray, lt, sql } from 'drizzle-orm';
import { newUlid } from '../../db/ids.js';
import type { ModuleDb } from '../../lib/db.js';
import { conflict, notFound } from '../../lib/errors.js';
import {
  workflowRuns,
  workflowTriggerDeliveries,
  workflowTriggerSeen,
  workflowTriggers,
  workflows,
  WORKFLOW_TRIGGER_PRESETS,
  type WorkflowDeliveryStatus,
  type WorkflowTriggerPreset,
} from './schema.js';
import {
  MAX_DELIVERY_LINES,
  TRIGGER_MEMORY_MS,
  factsOf,
  parseBody,
  previewOf,
  safeHeaders,
  verify,
  type EventFacts,
  type Headers,
} from './triggers.js';
import type { Scope } from './service.js';

export type TriggerRow = typeof workflowTriggers.$inferSelect;
export type DeliveryRow = typeof workflowTriggerDeliveries.$inferSelect;

/** Seals and opens a secret with the hub's data key ring (lent by the composition root). */
export interface Sealer {
  seal(plaintext: string): { ciphertext: string; nonce: string; keyId: string };
  open(sealed: { ciphertext: string; nonce: string; keyId: string }): string;
}

/** What a run started by a delivery reads as `{{trigger.*}}`. */
export interface TriggerContext {
  body: unknown;
  event: string | null;
  event_id: string | null;
  task_id: string | null;
  headers: Record<string, string>;
  preset: WorkflowTriggerPreset;
  trigger_id: string;
  delivery_id: string;
  received_at: string;
  test: boolean;
}

/** Starts the workflow's run for a delivery that got through; its id. */
export type StartRun = (
  trigger: TriggerRow,
  context: TriggerContext,
  facts: EventFacts,
) => { workflowRunId: string };

export interface Receipt {
  httpStatus: 200 | 202 | 401;
  status: 'accepted' | 'duplicate' | 'filtered' | 'rejected';
  delivery: DeliveryRow;
  reason: string | null;
}

const trimmed = (value: unknown, max: number): string | null => {
  if (typeof value !== 'string') return null;
  const out = value.trim();
  return out === '' ? null : out.slice(0, max);
};

function eventsOf(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    const name = trimmed(item, 120);
    if (name && !out.includes(name)) out.push(name);
  }
  return out;
}

export class TriggerDesk {
  constructor(
    private readonly db: ModuleDb,
    private readonly sealer: () => Sealer | null,
    private readonly now: () => Date = () => new Date(),
  ) {}

  // ------------------------------------------------------------- the triggers

  list(scope: Scope, workflowId: string): TriggerRow[] {
    this.workflowOf(scope, workflowId);
    return this.db
      .select()
      .from(workflowTriggers)
      .where(
        and(
          eq(workflowTriggers.workspace, scope.workspace),
          eq(workflowTriggers.workflowId, workflowId),
        ),
      )
      .orderBy(workflowTriggers.id)
      .all();
  }

  get(scope: Scope, id: string): TriggerRow {
    const row = this.db
      .select()
      .from(workflowTriggers)
      .where(and(eq(workflowTriggers.workspace, scope.workspace), eq(workflowTriggers.id, id)))
      .get();
    if (!row) throw notFound({ resource: 'workflow_trigger', id });
    return row;
  }

  /** The trigger behind a public address, whatever its profile; `undefined` when none. */
  byId(id: string): TriggerRow | undefined {
    return this.db.select().from(workflowTriggers).where(eq(workflowTriggers.id, id)).get();
  }

  create(scope: Scope, workflowId: string, input: Record<string, unknown>): TriggerRow {
    const workflow = this.workflowOf(scope, workflowId);
    const preset = input.preset as WorkflowTriggerPreset;
    if (!WORKFLOW_TRIGGER_PRESETS.includes(preset)) {
      throw conflict({ reason: 'preset_unknown', field: 'preset' });
    }
    const id = newUlid();
    const at = this.now();
    this.db
      .insert(workflowTriggers)
      .values({
        id,
        ownerId: scope.userId,
        workspace: scope.workspace,
        workflowId: workflow.id,
        name: trimmed(input.name, 120) ?? defaultName(preset),
        preset,
        enabled: input.enabled !== false,
        events: eventsOf(input.events),
        signatureHeader: trimmed(input.signature_header, 120),
        signatureEncoding: encodingOf(input.signature_encoding),
        signaturePrefix: trimmed(input.signature_prefix, 40),
        createdAt: at,
        updatedAt: at,
        ...this.sealedSecret(input.secret),
      })
      .run();
    return this.get(scope, id);
  }

  update(scope: Scope, id: string, patch: Record<string, unknown>): TriggerRow {
    this.get(scope, id);
    const values: Partial<typeof workflowTriggers.$inferInsert> = { updatedAt: this.now() };
    if (patch.name !== undefined) values.name = trimmed(patch.name, 120) ?? 'Trigger';
    if (patch.enabled !== undefined) values.enabled = patch.enabled === true;
    if (patch.events !== undefined) values.events = eventsOf(patch.events);
    if (patch.signature_header !== undefined) {
      values.signatureHeader = trimmed(patch.signature_header, 120);
    }
    if (patch.signature_encoding !== undefined) {
      values.signatureEncoding = encodingOf(patch.signature_encoding);
    }
    if (patch.signature_prefix !== undefined) {
      values.signaturePrefix = trimmed(patch.signature_prefix, 40);
    }
    if (patch.secret !== undefined) Object.assign(values, this.sealedSecret(patch.secret));
    this.db.update(workflowTriggers).set(values).where(eq(workflowTriggers.id, id)).run();
    return this.get(scope, id);
  }

  remove(scope: Scope, id: string): void {
    this.get(scope, id);
    this.db.delete(workflowTriggers).where(eq(workflowTriggers.id, id)).run();
  }

  /** The stored secret in plain text, for checking a delivery or signing a test; never sent. */
  secretOf(row: TriggerRow): string | null {
    if (!row.secretCiphertext || !row.secretNonce || !row.secretKeyId) return null;
    const sealer = this.sealer();
    if (!sealer) return null;
    try {
      return sealer.open({
        ciphertext: row.secretCiphertext,
        nonce: row.secretNonce,
        keyId: row.secretKeyId,
      });
    } catch {
      return null;
    }
  }

  private sealedSecret(value: unknown) {
    const plain = typeof value === 'string' ? value.trim() : '';
    if (plain === '') return { secretCiphertext: null, secretNonce: null, secretKeyId: null };
    const sealer = this.sealer();
    if (!sealer) throw conflict({ reason: 'secrets_unavailable', field: 'secret' });
    const sealed = sealer.seal(plain);
    return {
      secretCiphertext: sealed.ciphertext,
      secretNonce: sealed.nonce,
      secretKeyId: sealed.keyId,
    };
  }

  private workflowOf(scope: Scope, workflowId: string) {
    const row = this.db
      .select()
      .from(workflows)
      .where(and(eq(workflows.workspace, scope.workspace), eq(workflows.id, workflowId)))
      .get();
    if (!row) throw notFound({ resource: 'workflow', id: workflowId });
    return row;
  }

  // ------------------------------------------------------------- receiving

  /**
   * One delivery, real or a test, from its raw bytes to its answer. `start` queues the run;
   * it is called only for a delivery that passed every check.
   */
  receive(
    trigger: TriggerRow,
    raw: Buffer,
    headers: Headers,
    start: StartRun,
    options: { test?: boolean } = {},
  ): Receipt {
    const now = this.now();
    this.forget(trigger.id, now);
    const test = options.test === true;
    const verdict = verify(trigger, this.secretOf(trigger), raw, headers);
    if (!verdict.ok) {
      // Nothing of a refused body is kept: it may be anyone's.
      const delivery = this.log(trigger, now, {
        status: 'signature_rejected',
        error: verdict.reason,
        test,
      });
      return { httpStatus: 401, status: 'rejected', delivery, reason: verdict.reason };
    }
    const body = parseBody(raw);
    const facts = factsOf(trigger.preset, body, raw, headers);
    const seen = this.db
      .insert(workflowTriggerSeen)
      .values({
        id: newUlid(),
        ownerId: trigger.ownerId,
        workspace: trigger.workspace,
        triggerId: trigger.id,
        key: facts.dedupeKey,
        createdAt: now,
        updatedAt: now,
      })
      .onConflictDoNothing()
      .run();
    const common = {
      event: facts.event,
      eventId: facts.eventId,
      taskId: facts.taskId,
      bodyPreview: previewOf(body),
      test,
    };
    if (seen.changes === 0) {
      const delivery = this.log(trigger, now, { ...common, status: 'duplicate' });
      return { httpStatus: 200, status: 'duplicate', delivery, reason: null };
    }
    const events = trigger.events ?? [];
    if (events.length > 0 && (!facts.event || !events.includes(facts.event))) {
      const delivery = this.log(trigger, now, {
        ...common,
        status: 'filtered_out',
        error: `the trigger does not take the event "${facts.event ?? ''}"`,
      });
      return { httpStatus: 200, status: 'filtered', delivery, reason: null };
    }
    const delivery = this.log(trigger, now, { ...common, status: 'received' });
    try {
      const { workflowRunId } = start(
        trigger,
        {
          body,
          event: facts.event,
          event_id: facts.eventId,
          task_id: facts.taskId,
          headers: safeHeaders(headers),
          preset: trigger.preset,
          trigger_id: trigger.id,
          delivery_id: delivery.id,
          received_at: now.toISOString(),
          test,
        },
        facts,
      );
      this.setDelivery(delivery.id, { status: 'run_started', workflowRunId });
    } catch (error) {
      this.setDelivery(delivery.id, {
        status: 'run_failed',
        error: error instanceof Error ? error.message : String(error),
      });
    }
    return {
      httpStatus: 202,
      status: 'accepted',
      delivery: this.delivery(delivery.id)!,
      reason: null,
    };
  }

  private log(
    trigger: TriggerRow,
    at: Date,
    fields: Partial<typeof workflowTriggerDeliveries.$inferInsert> & {
      status: WorkflowDeliveryStatus;
    },
  ): DeliveryRow {
    const id = lineId(at);
    this.db
      .insert(workflowTriggerDeliveries)
      .values({
        id,
        ownerId: trigger.ownerId,
        workspace: trigger.workspace,
        triggerId: trigger.id,
        workflowId: trigger.workflowId,
        createdAt: at,
        updatedAt: at,
        ...fields,
      })
      .run();
    this.db
      .update(workflowTriggers)
      .set({ lastDeliveryAt: at })
      .where(eq(workflowTriggers.id, trigger.id))
      .run();
    return this.delivery(id)!;
  }

  private setDelivery(id: string, patch: Partial<typeof workflowTriggerDeliveries.$inferInsert>) {
    this.db
      .update(workflowTriggerDeliveries)
      .set({ ...patch, updatedAt: this.now() })
      .where(eq(workflowTriggerDeliveries.id, id))
      .run();
  }

  delivery(id: string): DeliveryRow | undefined {
    return this.db
      .select()
      .from(workflowTriggerDeliveries)
      .where(eq(workflowTriggerDeliveries.id, id))
      .get();
  }

  /** Keys and log lines older than a week go, and the log keeps its latest 500 lines. */
  private forget(triggerId: string, now: Date): void {
    const before = new Date(now.getTime() - TRIGGER_MEMORY_MS);
    this.db
      .delete(workflowTriggerSeen)
      .where(
        and(
          eq(workflowTriggerSeen.triggerId, triggerId),
          lt(workflowTriggerSeen.createdAt, before),
        ),
      )
      .run();
    this.db
      .delete(workflowTriggerDeliveries)
      .where(
        and(
          eq(workflowTriggerDeliveries.triggerId, triggerId),
          lt(workflowTriggerDeliveries.createdAt, before),
        ),
      )
      .run();
    const keep = this.db
      .select({ id: workflowTriggerDeliveries.id })
      .from(workflowTriggerDeliveries)
      .where(eq(workflowTriggerDeliveries.triggerId, triggerId))
      .orderBy(desc(workflowTriggerDeliveries.id))
      .limit(1)
      .offset(MAX_DELIVERY_LINES - 1)
      .get();
    if (keep) {
      this.db
        .delete(workflowTriggerDeliveries)
        .where(
          and(
            eq(workflowTriggerDeliveries.triggerId, triggerId),
            lt(workflowTriggerDeliveries.id, keep.id),
          ),
        )
        .run();
    }
  }

  // ------------------------------------------------------------- the log

  /** A page of a trigger's deliveries, newest first, each settled against its run. */
  deliveries(scope: Scope, triggerId: string, limit: number, after: string | null): DeliveryRow[] {
    this.get(scope, triggerId);
    const rows = this.db
      .select()
      .from(workflowTriggerDeliveries)
      .where(
        and(
          eq(workflowTriggerDeliveries.triggerId, triggerId),
          after ? lt(workflowTriggerDeliveries.id, after) : sql`1 = 1`,
        ),
      )
      .orderBy(desc(workflowTriggerDeliveries.id))
      .limit(limit + 1)
      .all();
    this.reconcile(rows);
    return rows.map((row) => this.delivery(row.id) ?? row);
  }

  /**
   * A run that ended while no one was listening (a restart failed it) still leaves its line
   * `run_started`; reading the line settles it from the run.
   */
  private reconcile(rows: DeliveryRow[]): void {
    const open = rows.filter((row) => row.status === 'run_started' && row.workflowRunId);
    if (open.length === 0) return;
    const runs = this.db
      .select({ id: workflowRuns.id, status: workflowRuns.status })
      .from(workflowRuns)
      .where(
        inArray(
          workflowRuns.id,
          open.map((row) => row.workflowRunId!),
        ),
      )
      .all();
    const byId = new Map(runs.map((run) => [run.id, run.status]));
    for (const row of open) {
      const status = byId.get(row.workflowRunId!);
      if (status === undefined) {
        this.setDelivery(row.id, { status: 'run_failed', error: 'the run was deleted' });
      } else if (['succeeded', 'failed', 'cancelled', 'timed_out'].includes(status)) {
        this.settleRun(row.workflowRunId!);
      }
    }
  }

  /** The run a delivery started ended: its line says how. */
  settleRun(workflowRunId: string): void {
    const run = this.db.select().from(workflowRuns).where(eq(workflowRuns.id, workflowRunId)).get();
    if (!run || !run.workflowTriggerId) return;
    const succeeded = run.status === 'succeeded';
    const filtered = (run.output as { filtered?: unknown } | null)?.filtered === true;
    this.db
      .update(workflowTriggerDeliveries)
      .set({
        status: succeeded ? 'run_succeeded' : 'run_failed',
        filtered: succeeded && filtered,
        error: succeeded ? null : (run.error ?? run.status),
        updatedAt: this.now(),
      })
      .where(eq(workflowTriggerDeliveries.workflowRunId, workflowRunId))
      .run();
  }
}

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
let lastLineId = '';
/**
 * A delivery line's id, always after the one before it: two deliveries in the same millisecond
 * keep the order they came in, so the log reads newest first and its cursor pages exactly.
 */
function lineId(now: Date): string {
  let id = newUlid(now.getTime());
  if (id <= lastLineId) {
    const chars = lastLineId.split('');
    for (let i = chars.length - 1; i >= 10; i -= 1) {
      const at = CROCKFORD.indexOf(chars[i]!);
      if (at < 31) {
        chars[i] = CROCKFORD[at + 1]!;
        break;
      }
      chars[i] = '0';
    }
    id = chars.join('');
  }
  lastLineId = id;
  return id;
}

function defaultName(preset: WorkflowTriggerPreset): string {
  switch (preset) {
    case 'clickup':
      return 'ClickUp';
    case 'github':
      return 'GitHub';
    case 'generic_hmac':
      return 'Signed webhook';
    case 'token':
      return 'Webhook with a token';
  }
}

function encodingOf(value: unknown): 'hex' | 'base64' | null {
  return value === 'hex' || value === 'base64' ? value : null;
}

/** A trigger as the contract says it (`WorkflowTrigger`). The secret never leaves. */
export function toWorkflowTrigger(row: TriggerRow, profile: string): Record<string, unknown> {
  return {
    id: row.id,
    profile,
    owner_id: row.ownerId,
    created_at: row.createdAt.toISOString(),
    updated_at: row.updatedAt.toISOString(),
    workflow_id: row.workflowId,
    name: row.name,
    preset: row.preset,
    enabled: row.enabled,
    events: row.events ?? [],
    secret_stored: !!row.secretCiphertext,
    signature_header: row.signatureHeader,
    signature_encoding: row.signatureEncoding,
    signature_prefix: row.signaturePrefix,
    path: `/api/v1/workflow-hooks/${row.id}`,
    last_delivery_at: row.lastDeliveryAt?.toISOString() ?? null,
  };
}

/** A delivery as the contract says it (`WorkflowTriggerDelivery`). */
export function toWorkflowTriggerDelivery(row: DeliveryRow): Record<string, unknown> {
  return {
    id: row.id,
    trigger_id: row.triggerId,
    workflow_id: row.workflowId,
    received_at: row.createdAt.toISOString(),
    status: row.status,
    event: row.event,
    event_id: row.eventId,
    task_id: row.taskId,
    workflow_run_id: row.workflowRunId,
    filtered: row.filtered,
    test: row.test,
    error: row.error,
    body_preview: row.bodyPreview,
  };
}
