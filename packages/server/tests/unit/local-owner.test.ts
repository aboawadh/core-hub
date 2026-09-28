// The owner on the computer the hub runs on (DECISIONS §131): a password reset the desktop app
// opens after the OS confirmed the person, and sign-in without a password. Both exist only
// through `localOwnerAccessFor(io)` — the desktop app's IPC channel — never over HTTP.
import { eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { requireSqlite } from '../../src/lib/db.js';
import { TEST_ADMIN_PASSWORD, signedInHub, testHub } from './helpers.js';
import { auditEvents } from '../../src/modules/audit/schema.js';
import { devices } from '../../src/modules/devices/schema.js';
import {
  localOwnerAccessFor,
  LocalOwnerRefusal,
  RECOVERY_TTL_MS,
} from '../../src/modules/auth/index.js';
import { appTokens } from '../../src/modules/auth/schema.js';

const NEW_PASSWORD = 'a-new-owner-password';
const LABEL = 'desktop · this computer';

async function pairPhone(hub: Awaited<ReturnType<typeof signedInHub>>) {
  const created = await hub.app.inject({
    method: 'POST',
    url: '/api/v1/auth/pairings',
    headers: { authorization: `Bearer ${hub.token}` },
    payload: {},
  });
  const pairing = created.json() as { id: string; code: string };
  const claim = await hub.app.inject({
    method: 'POST',
    url: `/api/v1/auth/pairings/${pairing.id}/claim`,
    payload: {
      code: pairing.code,
      device: { device_key: 'phone-key', name: 'phone', platform: 'ios', kind: 'phone' },
    },
  });
  expect(claim.statusCode).toBe(201);
  return claim.json() as { app_token: string; device: { id: string } };
}

const login = (hub: { app: Awaited<ReturnType<typeof testHub>>['app'] }, password: string) =>
  hub.app.inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    payload: { username: 'admin', password },
  });

const me = (hub: { app: Awaited<ReturnType<typeof testHub>>['app'] }, token: string) =>
  hub.app.inject({
    method: 'GET',
    url: '/api/v1/auth/me',
    headers: { authorization: `Bearer ${token}` },
  });

describe('auth: the owner on this computer (desktop local mode)', () => {
  it('has no HTTP route: nothing on the network can open or spend a reset', async () => {
    const hub = await testHub({ HUB_ADMIN_PASSWORD: TEST_ADMIN_PASSWORD });
    try {
      const routes = hub.app.printRoutes({ commonPrefix: false });
      expect(routes).not.toMatch(/recover|local-owner|local_owner|local-sign/i);
      for (const url of [
        '/api/v1/auth/recovery',
        '/api/v1/auth/local-recovery',
        '/api/v1/auth/local-sign-in',
        '/api/v1/auth/password/reset',
      ]) {
        const response = await hub.app.inject({
          method: 'POST',
          url,
          payload: { password: NEW_PASSWORD },
        });
        expect(response.statusCode, url).toBe(404);
      }
      // The password is still the old one.
      expect((await login(hub, TEST_ADMIN_PASSWORD)).statusCode).toBe(200);
    } finally {
      await hub.close();
    }
  });

  it('resets the password once, ends the sign-ins on other devices but keeps personal tokens, signs the app in, audits without the password', async () => {
    const hub = await signedInHub();
    try {
      const phone = await pairPhone(hub);
      // A personal token for a script or integration (owner, 2026-09-28: it must survive).
      const created = await hub.app.inject({
        method: 'POST',
        url: '/api/v1/auth/app-tokens',
        headers: { authorization: `Bearer ${hub.token}` },
        payload: { name: 'backup script', scopes: ['read'] },
      });
      expect(created.statusCode).toBe(201);
      const personal = created.json() as { token: string };
      const access = localOwnerAccessFor(hub.app.hub.io)!;
      const opened = access.beginRecovery('touch_id');
      expect(opened.username).toBe('admin');
      expect(opened.grant).toMatch(/^[0-9a-f]{64}$/);

      // Too short: refused before the grant is spent, so it can be typed again.
      await expect(access.finishRecovery(opened.grant, 'short', LABEL)).rejects.toMatchObject({
        reason: 'invalid_password',
      });
      // A wrong grant does not open anything.
      await expect(
        access.finishRecovery('0'.repeat(64), NEW_PASSWORD, LABEL),
      ).rejects.toMatchObject({ reason: 'expired' });

      const pair = await access.finishRecovery(opened.grant, NEW_PASSWORD, LABEL);
      expect(pair.user.username).toBe('admin');
      expect(pair.refresh_token).toMatch(/^hub_rt_/);

      // The app's new sign-in and the personal token are all that is left.
      const db = requireSqlite(hub.app.hub.database);
      const device = db.select().from(devices).where(eq(devices.id, phone.device.id)).get()!;
      expect(device.status).toBe('revoked');
      const live = db
        .select()
        .from(appTokens)
        .where(eq(appTokens.userId, hub.userId))
        .all()
        .filter((row) => row.revokedAt === null);
      expect(live.map((row) => row.name).sort()).toEqual(['backup script', LABEL].sort());

      // Single use.
      await expect(
        access.finishRecovery(opened.grant, 'another-password-1', LABEL),
      ).rejects.toBeInstanceOf(LocalOwnerRefusal);

      // The new password works, the old one does not.
      expect((await login(hub, TEST_ADMIN_PASSWORD)).statusCode).toBe(401);
      expect((await login(hub, NEW_PASSWORD)).statusCode).toBe(200);

      // The earlier browser session and the paired phone are signed out; the app's new sign-in is not.
      expect((await me(hub, hub.token)).statusCode).toBe(401);
      expect((await me(hub, phone.app_token)).statusCode).toBe(401);
      expect((await me(hub, pair.access_token)).statusCode).toBe(200);
      // The personal token still works.
      expect((await me(hub, personal.token)).statusCode).toBe(200);

      const rows = db.select().from(auditEvents).all();
      const recovered = rows.find((row) => row.action === 'auth.password_recovered')!;
      expect(recovered).toBeDefined();
      expect(recovered.actorId).toBe(hub.userId);
      expect(recovered.data).toMatchObject({
        via: 'desktop_local',
        method: 'touch_id',
        sessions_revoked: 1,
        devices_revoked: 1,
      });
      expect(rows.some((row) => row.action === 'auth.password_recovery_started')).toBe(true);
      const everything = JSON.stringify(rows);
      expect(everything).not.toContain(NEW_PASSWORD);
      expect(everything).not.toContain(opened.grant);
      expect(everything).not.toContain(personal.token);
    } finally {
      await hub.close();
    }
  });

  it('expires the grant five minutes after the OS confirmation', async () => {
    const hub = await signedInHub();
    try {
      const access = localOwnerAccessFor(hub.app.hub.io)!;
      const realNow = Date.now;
      const opened = access.beginRecovery('polkit');
      const start = realNow();
      Date.now = () => start + RECOVERY_TTL_MS + 1;
      try {
        await expect(
          access.finishRecovery(opened.grant, NEW_PASSWORD, LABEL),
        ).rejects.toMatchObject({ reason: 'expired' });
      } finally {
        Date.now = realNow;
      }
      expect((await login(hub, TEST_ADMIN_PASSWORD)).statusCode).toBe(200);
    } finally {
      await hub.close();
    }
  });

  it('a new confirmation replaces the old grant, and five in fifteen minutes is the limit', async () => {
    const hub = await signedInHub();
    try {
      const access = localOwnerAccessFor(hub.app.hub.io)!;
      const first = access.beginRecovery('windows_hello');
      const second = access.beginRecovery('windows_hello');
      await expect(access.finishRecovery(first.grant, NEW_PASSWORD, LABEL)).rejects.toMatchObject({
        reason: 'expired',
      });
      access.beginRecovery('windows_hello');
      access.beginRecovery('windows_hello');
      access.beginRecovery('windows_hello');
      expect(() => access.beginRecovery('windows_hello')).toThrow(LocalOwnerRefusal);
      // `second` was replaced by the later confirmations as well.
      await expect(access.finishRecovery(second.grant, NEW_PASSWORD, LABEL)).rejects.toMatchObject({
        reason: 'expired',
      });
    } finally {
      await hub.close();
    }
  });

  it('refuses before an owner exists', async () => {
    const hub = await testHub({ COREHUB_SETUP_OPEN_MINUTES: '0' });
    try {
      const access = localOwnerAccessFor(hub.app.hub.io)!;
      expect(() => access.beginRecovery('touch_id')).toThrow(LocalOwnerRefusal);
      await expect(access.signIn(LABEL)).rejects.toMatchObject({ reason: 'no_owner' });
    } finally {
      await hub.close();
    }
  });

  it('signs the owner in without a password, and knows an owner session from anything else', async () => {
    const hub = await signedInHub();
    try {
      const access = localOwnerAccessFor(hub.app.hub.io)!;
      const pair = await access.signIn(LABEL);
      expect((await me(hub, pair.access_token)).json()).toMatchObject({ username: 'admin' });
      expect(await access.isOwnerSession(pair.access_token)).toBe(true);
      expect(await access.isOwnerSession('not-a-token')).toBe(false);

      // A member's sign-in is not the owner's.
      const created = await hub.app.inject({
        method: 'POST',
        url: '/api/v1/auth/users',
        headers: { authorization: `Bearer ${hub.token}` },
        payload: {
          username: 'sara',
          password: 'member-password-1',
          role: 'member',
          profiles: ['default'],
        },
      });
      expect(created.statusCode).toBe(201);
      const member = (await (
        await hub.app.inject({
          method: 'POST',
          url: '/api/v1/auth/login',
          payload: { username: 'sara', password: 'member-password-1' },
        })
      ).json()) as { access_token: string };
      expect(await access.isOwnerSession(member.access_token)).toBe(false);

      const db = requireSqlite(hub.app.hub.database);
      const logins = db
        .select()
        .from(auditEvents)
        .all()
        .filter((row) => row.action === 'auth.login' && row.actorId === hub.userId);
      expect(logins.some((row) => (row.data as { via?: string }).via === 'desktop_local')).toBe(
        true,
      );
    } finally {
      await hub.close();
    }
  });
});
