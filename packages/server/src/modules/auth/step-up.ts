/**
 * Step-up: the account password asked again, for one sensitive page (DECISIONS §125).
 *
 * The owner opens Settings → Secrets, and the page asks for the password every time it is
 * opened — being signed in is not enough, and neither is having typed it a minute ago. A right
 * password answers a **grant**: an opaque string, good for five minutes, for one purpose, for
 * this person and this sign-in session only. Another module (`secrets`) takes the grant in the
 * body of its operations and asks `stepUpFor(io).assert` whether it still opens them.
 *
 * - The grant lives in this process's memory, by its SHA-256, never in a row or a log line. A
 *   restart, the page being left (`auth.endStepUp`), a newer grant for the same session and
 *   purpose, and the clock each end it.
 * - A wrong password counts on the sign-in lockout of the caller's address (`lockouts.ts`:
 *   five in 15 minutes lock it for 15), and both outcomes are audit rows without the password.
 * - Only a web sign-in session may step up: an app token (a paired phone, an integration) is
 *   refused, so a leaked integration token never opens a secret.
 */
import { createHash, randomBytes } from 'node:crypto';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { HubError } from '../../lib/errors.js';
import { parse } from '../../lib/validate.js';
import { AuditService } from '../audit/index.js';
import type { AuthContext } from './context.js';
import { assertNotLocked, clearFailures, recordFailure } from './lockouts.js';
import { verifyPassword } from './passwords.js';
import { requireRole, requireUser, type Principal } from './principal.js';
import { findUser } from './users.js';

export const STEP_UP_PURPOSES = ['secrets'] as const;
export type StepUpPurpose = (typeof STEP_UP_PURPOSES)[number];

/** How long a grant opens its page: long enough to read a list and reveal a few, no more. */
export const STEP_UP_TTL_MS = 5 * 60 * 1000;

const GRANT_PREFIX = 'su_';

interface GrantRow {
  userId: string;
  sessionId: string;
  purpose: StepUpPurpose;
  expiresAt: number;
}

const digest = (grant: string) => createHash('sha256').update(grant).digest('hex');

/** The live grants of one hub, by the SHA-256 of each. */
export class StepUpGrants {
  private readonly rows = new Map<string, GrantRow>();

  constructor(private readonly now: () => number) {}

  issue(principal: Principal, purpose: StepUpPurpose): { grant: string; expiresAt: number } {
    this.sweep();
    // One grant per session and purpose: a new one ends the one before it.
    this.end(principal, purpose);
    const grant = `${GRANT_PREFIX}${randomBytes(24).toString('base64url')}`;
    const expiresAt = this.now() + STEP_UP_TTL_MS;
    this.rows.set(digest(grant), {
      userId: principal.user.id,
      sessionId: principal.tokenId,
      purpose,
      expiresAt,
    });
    return { grant, expiresAt };
  }

  /** Whether `grant` opens `purpose` for this caller now. */
  valid(principal: Principal, grant: string, purpose: StepUpPurpose): boolean {
    const key = digest(grant);
    const row = this.rows.get(key);
    if (!row) return false;
    if (row.expiresAt <= this.now()) {
      this.rows.delete(key);
      return false;
    }
    return (
      row.purpose === purpose &&
      row.userId === principal.user.id &&
      row.sessionId === principal.tokenId &&
      principal.kind === 'user'
    );
  }

  /** Ends every grant of this caller's session (for one purpose, or all). */
  end(principal: Principal, purpose?: StepUpPurpose): number {
    let ended = 0;
    for (const [key, row] of this.rows) {
      if (row.userId !== principal.user.id || row.sessionId !== principal.tokenId) continue;
      if (purpose !== undefined && row.purpose !== purpose) continue;
      this.rows.delete(key);
      ended += 1;
    }
    return ended;
  }

  /** How many grants are live — for tests. */
  get size(): number {
    this.sweep();
    return this.rows.size;
  }

  private sweep(): void {
    const at = this.now();
    for (const [key, row] of this.rows) if (row.expiresAt <= at) this.rows.delete(key);
  }
}

const grantsByContext = new WeakMap<AuthContext, StepUpGrants>();

export function stepUpGrantsOf(ctx: AuthContext): StepUpGrants {
  let grants = grantsByContext.get(ctx);
  if (!grants) {
    grants = new StepUpGrants(() => ctx.now());
    grantsByContext.set(ctx, grants);
  }
  return grants;
}

/** A web sign-in session: the only kind of caller that may step up or use a grant. */
export function assertWebSession(principal: Principal): void {
  if (principal.kind !== 'user') {
    throw new HubError('forbidden', {
      messageKey: 'auth.step_up_web_only',
      details: { reason: 'web_session_required' },
    });
  }
}

/** Throws `403 step_up_required` unless `grant` opens `purpose` for this caller now. */
export function assertStepUp(
  grants: StepUpGrants,
  principal: Principal,
  grant: string,
  purpose: StepUpPurpose,
): void {
  assertWebSession(principal);
  if (!grants.valid(principal, grant, purpose)) {
    throw new HubError('forbidden', {
      messageKey: 'auth.step_up_required',
      details: { reason: 'step_up_required' },
    });
  }
}

const StepUpBody = z.object({
  password: z.string().min(1).max(1024),
  purpose: z.enum(STEP_UP_PURPOSES),
});

const noStore = (reply: FastifyReply) => void reply.header('cache-control', 'no-store');

export function registerStepUpRoutes(app: FastifyInstance, ctx: AuthContext): void {
  const { db } = ctx;
  const ledger = new AuditService(db);
  const grants = stepUpGrantsOf(ctx);
  // Only the owner opens Secrets, the one purpose there is; a purpose for others would say so.
  const guards = [requireUser, requireRole('owner')];

  app.route({
    method: 'POST',
    url: '/auth/step-up',
    preHandler: guards,
    handler: async (request: FastifyRequest, reply: FastifyReply) => {
      const principal = request.principal!;
      assertWebSession(principal);
      const body = parse(StepUpBody, request.body);
      const ip = request.ip;
      const now = ctx.now();
      assertNotLocked(db, 'password', ip, now);
      const user = findUser(db, principal.user.id);
      const ok = user ? await verifyPassword(user.passwordHash, body.password) : false;
      if (!ok) {
        const locked = recordFailure(db, 'password', ip, ctx.now(), principal.user.id);
        ledger.record(
          {
            actorKind: 'user',
            actorId: principal.user.id,
            action: 'auth.step_up_failed',
            entityKind: 'user',
            entityId: principal.user.id,
            summary: locked
              ? 'password confirmation failed; ip locked'
              : 'password confirmation failed',
            data: { purpose: body.purpose, locked },
            requestId: request.id,
            ownerId: principal.user.id,
          },
          ctx.now(),
        );
        throw new HubError('unauthorized', {
          messageKey: 'auth.step_up_wrong_password',
          details: { reason: 'wrong_password' },
        });
      }
      clearFailures(db, 'password', ip);
      const issued = grants.issue(principal, body.purpose);
      ledger.record(
        {
          actorKind: 'user',
          actorId: principal.user.id,
          action: 'auth.step_up',
          entityKind: 'user',
          entityId: principal.user.id,
          summary: `password confirmed for ${body.purpose}`,
          data: { purpose: body.purpose, expires_at: new Date(issued.expiresAt).toISOString() },
          requestId: request.id,
          ownerId: principal.user.id,
        },
        ctx.now(),
      );
      noStore(reply);
      return {
        grant: issued.grant,
        purpose: body.purpose,
        expires_at: new Date(issued.expiresAt).toISOString().replace(/\.\d{3}Z$/, 'Z'),
        ttl_seconds: Math.round(STEP_UP_TTL_MS / 1000),
      };
    },
  });

  app.route({
    method: 'DELETE',
    url: '/auth/step-up',
    preHandler: guards,
    handler: async (request: FastifyRequest, reply: FastifyReply) => {
      grants.end(request.principal!);
      return reply.status(204).send();
    },
  });
}
