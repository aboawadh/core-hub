// The owner on the computer the hub runs on (DECISIONS §131): what the desktop app may do for
// the owner of the hub it started itself, in local mode — reset a forgotten password after the
// operating system confirmed the person, and sign the owner in without a password when the
// owner turned that on.
//
// None of this is an HTTP route, and none may become one. The only caller is the desktop app
// over the IPC channel of the child process it forked (`apps/desktop/src/hub/entry.ts`): a
// channel nobody on the network, behind a tunnel or on another account of the computer can
// open. A hub in a container has no such channel, so there this whole file is unreachable.
//
// The reset is two steps so the grant — not the app's word — is what the hub checks:
// `beginRecovery` (called by the app only after the OS confirmed the person) answers the
// owner's username and a random grant, kept here by its SHA-256 for five minutes; `finishRecovery`
// spends it once. At most `RECOVERY_BEGINS_MAX` grants in `RECOVERY_WINDOW_MS`, one alive at a
// time.
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { and, eq, isNull } from 'drizzle-orm';
import type { ModuleDb } from '../../lib/db.js';
import { AuditService } from '../audit/index.js';
import { endPushForOwners, revokeDeviceByToken } from '../devices/index.js';
import type { AuthContext } from './context.js';
import { hashPassword } from './passwords.js';
import { resolvePrincipal } from './principal.js';
import { appTokens, users } from './schema.js';
import type { UserRow } from './serialize.js';
import { revalidateSockets } from './sockets.js';
import { ACCESS_TOKEN_TTL_SECONDS, signAccessToken } from './tokens.js';
import { createSession, ownerUser, presentUser, touchLogin } from './users.js';

/** How long the OS confirmation opens the reset for. */
export const RECOVERY_TTL_MS = 5 * 60_000;
/** How many resets may be opened in `RECOVERY_WINDOW_MS`. */
export const RECOVERY_BEGINS_MAX = 5;
export const RECOVERY_WINDOW_MS = 15 * 60_000;
/** The same rule as first-run setup and a password change (`auth` routes' `Password`). */
export const RECOVERY_PASSWORD_MIN = 8;
export const RECOVERY_PASSWORD_MAX = 1024;

/** Why the hub said no; the app turns each into a sentence in the person's language. */
export type LocalOwnerRefusalReason =
  'no_owner' | 'owner_disabled' | 'rate_limited' | 'expired' | 'invalid_password' | 'not_owner';

export class LocalOwnerRefusal extends Error {
  constructor(
    readonly reason: LocalOwnerRefusalReason,
    message: string,
  ) {
    super(message);
    this.name = 'LocalOwnerRefusal';
  }
}

/** What `POST /auth/login` answers (`TokenPair`), so the web client stores it the same way. */
export interface LocalTokenPair {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  user: ReturnType<typeof presentUser>;
}

export interface RecoveryGrant {
  /** Handed to the app once; the hub keeps only its SHA-256. */
  grant: string;
  username: string;
  display_name: string | null;
  expires_at: string;
}

const digest = (value: string) => createHash('sha256').update(value, 'utf8').digest();

export class LocalOwnerAccess {
  private readonly ledger: AuditService;
  /** The one grant alive, by digest; a new one replaces it. */
  private live: { digest: Buffer; ownerId: string; expiresAt: number; method: string } | null =
    null;
  private begins: number[] = [];

  constructor(private readonly ctx: AuthContext) {
    this.ledger = new AuditService(ctx.db);
  }

  private get db(): ModuleDb {
    return this.ctx.db;
  }

  private owner(): UserRow {
    const owner = ownerUser(this.db);
    if (!owner) throw new LocalOwnerRefusal('no_owner', 'the hub has no owner yet');
    if (owner.status !== 'active')
      throw new LocalOwnerRefusal('owner_disabled', 'the owner account is disabled');
    return owner;
  }

  private async tokens(user: UserRow, label: string): Promise<LocalTokenPair> {
    const now = this.ctx.now();
    const session = createSession(this.db, user, now, label.slice(0, 120));
    touchLogin(this.db, user.id, now);
    const fresh = this.db.select().from(users).where(eq(users.id, user.id)).get()!;
    return {
      access_token: await signAccessToken(
        this.ctx.key,
        { userId: fresh.id, role: fresh.role, sessionId: session.sessionId },
        now,
      ),
      refresh_token: session.refreshToken,
      expires_in: ACCESS_TOKEN_TTL_SECONDS,
      user: presentUser(this.db, fresh),
    };
  }

  /**
   * The OS confirmed the person at this computer (`method` says how: `touch_id`,
   * `macos_password`, `windows_hello`, `polkit`). Opens one reset for five minutes.
   */
  beginRecovery(method: string): RecoveryGrant {
    const now = this.ctx.now();
    const owner = this.owner();
    this.begins = this.begins.filter((at) => now - at < RECOVERY_WINDOW_MS);
    if (this.begins.length >= RECOVERY_BEGINS_MAX)
      throw new LocalOwnerRefusal('rate_limited', 'too many password resets; wait a while');
    this.begins.push(now);
    const grant = randomBytes(32).toString('hex');
    const expiresAt = now + RECOVERY_TTL_MS;
    this.live = {
      digest: digest(grant),
      ownerId: owner.id,
      expiresAt,
      method: method.slice(0, 32),
    };
    this.ledger.record(
      {
        actorKind: 'user',
        actorId: owner.id,
        action: 'auth.password_recovery_started',
        entityKind: 'user',
        entityId: owner.id,
        summary: 'password reset opened on this computer',
        data: { via: 'desktop_local', method: this.live.method },
        ownerId: owner.id,
      },
      now,
    );
    return {
      grant,
      username: owner.username,
      display_name: owner.displayName ?? null,
      expires_at: new Date(expiresAt).toISOString(),
    };
  }

  /**
   * Spends the grant: the owner's new password; every token and session of the owner revoked
   * (phones and other computers pair again, personal tokens stop working); a fresh sign-in for
   * the app; an audit row without the password.
   */
  async finishRecovery(grant: string, password: string, label: string): Promise<LocalTokenPair> {
    if (
      typeof password !== 'string' ||
      password.length < RECOVERY_PASSWORD_MIN ||
      password.length > RECOVERY_PASSWORD_MAX
    )
      // Checked before the grant is spent: a password that is too short can be typed again.
      throw new LocalOwnerRefusal('invalid_password', 'the new password does not meet the rules');
    const now = this.ctx.now();
    const live = this.live;
    const presented = digest(typeof grant === 'string' ? grant : '');
    const matches = !!live && timingSafeEqual(live.digest, presented);
    // Single use: whatever happens next, this grant is gone.
    if (matches) this.live = null;
    if (!live || !matches || now >= live.expiresAt)
      throw new LocalOwnerRefusal('expired', 'the confirmation expired; confirm again');
    const owner = this.owner();
    if (owner.id !== live.ownerId)
      throw new LocalOwnerRefusal('expired', 'the owner changed; confirm again');

    const passwordHash = await hashPassword(password);
    const revoked = this.db.transaction((tx) => {
      tx.update(users)
        .set({ passwordHash, passwordChangedAt: new Date(now) })
        .where(eq(users.id, owner.id))
        .run();
      const ended = tx
        .update(appTokens)
        .set({ revokedAt: new Date(now) })
        .where(and(eq(appTokens.userId, owner.id), isNull(appTokens.revokedAt)))
        .returning({ id: appTokens.id, kind: appTokens.kind })
        .all();
      let devices = 0;
      for (const row of ended)
        if (row.kind === 'device' && revokeDeviceByToken(tx as ModuleDb, row.id, now)) devices++;
      endPushForOwners(tx as ModuleDb, [owner.id]);
      return { tokens: ended.length, devices };
    });
    revalidateSockets(this.ctx.io(), this.db, now);
    const pair = await this.tokens(
      this.db.select().from(users).where(eq(users.id, owner.id)).get()!,
      label,
    );
    this.ledger.record(
      {
        actorKind: 'user',
        actorId: owner.id,
        action: 'auth.password_recovered',
        entityKind: 'user',
        entityId: owner.id,
        summary: 'password reset on this computer',
        data: {
          via: 'desktop_local',
          method: live.method,
          tokens_revoked: revoked.tokens,
          devices_revoked: revoked.devices,
        },
        ownerId: owner.id,
      },
      now,
    );
    return pair;
  }

  /** The owner, signed in by the app on this computer without a password (when turned on). */
  async signIn(label: string): Promise<LocalTokenPair> {
    const owner = this.owner();
    const pair = await this.tokens(owner, label);
    this.ledger.record(
      {
        actorKind: 'user',
        actorId: owner.id,
        action: 'auth.login',
        entityKind: 'user',
        entityId: owner.id,
        summary: 'signed in on this computer',
        data: { via: 'desktop_local' },
        ownerId: owner.id,
      },
      this.ctx.now(),
    );
    return pair;
  }

  /**
   * Whether `bearer` is a live sign-in of the owner: the app lets only the owner, signed in,
   * turn password-free sign-in on.
   */
  async isOwnerSession(bearer: string): Promise<boolean> {
    try {
      const principal = await resolvePrincipal(this.ctx, bearer, '127.0.0.1');
      return principal.user.role === 'owner' && principal.kind === 'user';
    } catch {
      return false;
    }
  }
}
