// The owner of the hub on this computer (DECISIONS §131): the OS prompt per platform, and the
// whole "Forgot password?" / password-free sign-in path — the app's service, the IPC protocol and
// a real hub's `LocalOwnerAccess` — with the OS prompt faked.
import { describe, expect, it, vi } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadConfig } from '../../../../packages/server/src/app/config.js';
import { buildServer } from '../../../../packages/server/src/app/server.js';
import { createLogger } from '../../../../packages/server/src/lib/logger.js';
import {
  LocalOwnerRefusal,
  localOwnerAccessFor,
} from '../../../../packages/server/src/modules/auth/index.js';
import {
  ownerAccessClient,
  serveOwnerAccess,
  type OwnerAnswer,
  type OwnerRequest,
} from '../../src/shared/hub-ipc.js';
import { parseConfig } from '../../src/shared/config.js';
import { OwnerAccessService, PROMPTS_MAX } from '../../src/main/owner-access.js';
import {
  confirmKind,
  encodePowershell,
  fakeConfirm,
  helloScript,
  osConfirm,
  type ConfirmResult,
  type ExecResult,
  type OsConfirmEnv,
} from '../../src/main/os-confirm.js';

function osEnv(
  platform: NodeJS.Platform,
  answer: ExecResult,
  over: Partial<OsConfirmEnv> = {},
): OsConfirmEnv & { exec: ReturnType<typeof vi.fn> } {
  const exec = vi.fn(async () => answer);
  return {
    platform,
    env: { DISPLAY: ':0', SystemRoot: 'C:\\Windows' },
    exists: () => true,
    exec,
    ...over,
  } as OsConfirmEnv & { exec: ReturnType<typeof vi.fn> };
}

describe('the OS prompt', () => {
  it('Linux: polkit runs /usr/bin/true and nothing else; 126 is a no, 127 means nobody could ask', async () => {
    const ok = osEnv('linux', { code: 0, stdout: '', stderr: '' });
    expect(await osConfirm(ok, 'why')).toEqual({ ok: true, method: 'polkit' });
    expect(ok.exec).toHaveBeenCalledWith('/usr/bin/pkexec', ['/usr/bin/true']);
    expect(
      await osConfirm(osEnv('linux', { code: 126, stdout: '', stderr: '' }), 'why'),
    ).toMatchObject({ ok: false, reason: 'cancelled' });
    expect(
      await osConfirm(osEnv('linux', { code: 127, stdout: '', stderr: 'no agent' }), 'why'),
    ).toMatchObject({ ok: false, reason: 'unavailable' });
    // No graphical session, or no pkexec: no prompt, and the app offers no reset.
    const headless = osEnv('linux', { code: 0, stdout: '', stderr: '' }, { env: {} });
    expect(await confirmKind(headless)).toBeNull();
    expect(await osConfirm(headless, 'why')).toMatchObject({ reason: 'unavailable' });
    expect(headless.exec).not.toHaveBeenCalled();
    const none = osEnv('linux', { code: 0, stdout: '', stderr: '' }, { exists: () => false });
    expect(await confirmKind(none)).toBeNull();
    expect(await confirmKind(ok)).toBe('polkit');
  });

  it('macOS: Touch ID first; the Mac password dialog when Touch ID is missing or refused', async () => {
    const touch = osEnv(
      'darwin',
      { code: 0, stdout: '', stderr: '' },
      { touchId: { can: () => true, prompt: vi.fn(async () => undefined) } },
    );
    expect(await osConfirm(touch, 'reset the password')).toEqual({ ok: true, method: 'touch_id' });
    expect(touch.exec).not.toHaveBeenCalled();

    const fallback = osEnv(
      'darwin',
      { code: 0, stdout: '', stderr: '' },
      {
        touchId: {
          can: () => true,
          prompt: vi.fn(async () => Promise.reject(new Error('fallback'))),
        },
      },
    );
    expect(await osConfirm(fallback, 'reset "it"; rm -rf /')).toEqual({
      ok: true,
      method: 'macos_password',
    });
    const [file, args] = fallback.exec.mock.calls[0] as [string, string[]];
    expect(file).toBe('/usr/bin/osascript');
    // The reason is an argument, never part of the script.
    expect(args.at(-1)).toBe('reset "it"; rm -rf /');
    expect(args.join(' ')).toContain('do shell script "/usr/bin/true"');
    expect(args.join(' ')).toContain('with administrator privileges');

    const cancelled = osEnv('darwin', { code: 1, stdout: '', stderr: 'User canceled. (-128)' });
    expect(await osConfirm(cancelled, 'why')).toMatchObject({ ok: false, reason: 'cancelled' });
  });

  it('Windows: Windows Hello through PowerShell; offered only when Hello is set up', async () => {
    const verified = osEnv('win32', { code: 0, stdout: 'result:Verified\r\n', stderr: '' });
    expect(await osConfirm(verified, "the owner's password")).toEqual({
      ok: true,
      method: 'windows_hello',
    });
    const [file, args] = verified.exec.mock.calls[0] as [string, string[]];
    expect(file).toBe('C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe');
    expect(args.at(-1)).toBe(encodePowershell(helloScript('ask', "the owner's password")));
    // A quote in the reason stays inside the PowerShell literal.
    expect(helloScript('ask', "the owner's password")).toContain("'the owner''s password'");

    expect(
      await osConfirm(osEnv('win32', { code: 0, stdout: 'result:Canceled', stderr: '' }), 'x'),
    ).toMatchObject({ reason: 'cancelled' });
    expect(
      await osConfirm(
        osEnv('win32', { code: 0, stdout: 'availability:NotConfiguredForUser', stderr: '' }),
        'x',
      ),
    ).toMatchObject({ reason: 'unavailable' });
    expect(
      await confirmKind(osEnv('win32', { code: 0, stdout: 'availability:Available', stderr: '' })),
    ).toBe('windows_hello');
    expect(
      await confirmKind(
        osEnv('win32', { code: 0, stdout: 'availability:DeviceNotPresent', stderr: '' }),
      ),
    ).toBeNull();
  });

  it('the development app can be told the answer; nothing else can', async () => {
    expect(await fakeConfirm('allow')!('x')).toEqual({ ok: true, method: 'test' });
    expect(await fakeConfirm('deny')!('x')).toMatchObject({ ok: false, reason: 'cancelled' });
    expect(fakeConfirm(undefined)).toBeNull();
    expect(fakeConfirm('yes')).toBeNull();
  });
});

describe('password-free sign-in: the default', () => {
  it('is on for a new install and off for an install from before it', () => {
    const id = () => 'device-key-0001';
    expect(parseConfig(null, id).localSignIn).toBe(true);
    expect(parseConfig({ mode: 'local' }, id).localSignIn).toBe(false);
    expect(parseConfig({ localSignIn: true }, id).localSignIn).toBe(true);
    expect(parseConfig({ localSignIn: 'yes' }, id).localSignIn).toBe(false);
  });
});

const TEST_ADMIN_PASSWORD = 'owner-password-1';

/** The real hub, as local mode starts it, with its owner signed in once (a browser elsewhere). */
async function signedInHub() {
  const dataDir = mkdtempSync(path.join(os.tmpdir(), 'corehub-owner-'));
  const app = await buildServer({
    config: loadConfig({
      DATA_DIR: dataDir,
      PORT: '0',
      HUB_ADMIN_PASSWORD: TEST_ADMIN_PASSWORD,
      COREHUB_MODELS_CATALOG_URL: 'off',
      COREHUB_PUSH_RELAY: 'off',
    }),
    logger: createLogger({ level: 'silent' }),
    webDir: null,
  });
  const signedIn = await app.inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    payload: { username: 'admin', password: TEST_ADMIN_PASSWORD },
  });
  const token = (signedIn.json() as { access_token: string }).access_token;
  return {
    app,
    token,
    async close() {
      await app.close();
      rmSync(dataDir, { recursive: true, force: true });
    },
  };
}

/** The app's service wired to a real hub through the IPC protocol, as in local mode. */
async function localMode(
  confirm: (reason: string) => Promise<ConfirmResult>,
  options: { localSignIn?: boolean; local?: boolean } = {},
) {
  const hub = await signedInHub();
  const access = localOwnerAccessFor(hub.app.hub.io)!;
  let toApp: (message: unknown) => void = () => undefined;
  let toHub: (message: unknown) => void = () => undefined;
  const sent: OwnerRequest[] = [];
  serveOwnerAccess({
    access,
    send: (message: OwnerAnswer) => setImmediate(() => toApp(message)),
    listen: (listener) => (toHub = listener),
    reasonOf: (error) => (error instanceof LocalOwnerRefusal ? error.reason : null),
  });
  const client = ownerAccessClient({
    send: (message) => {
      sent.push(message);
      setImmediate(() => toHub(message));
      return true;
    },
  });
  toApp = (message) => client.receive(message);
  let localSignIn = options.localSignIn ?? false;
  const service = new OwnerAccessService({
    isLocal: () => options.local ?? true,
    kind: async () => 'test',
    confirm,
    reason: () => 'reset the owner password',
    ask: (request) => client.ask(request),
    localSignIn: () => localSignIn,
    setLocalSignIn: (value) => (localSignIn = value),
    label: () => 'desktop · test',
  });
  return { hub, service, sent };
}

const login = (hub: Awaited<ReturnType<typeof signedInHub>>, password: string) =>
  hub.app.inject({
    method: 'POST',
    url: '/api/v1/auth/login',
    payload: { username: 'admin', password },
  });

describe('Forgot password? on this computer', () => {
  it('OS confirms → the owner’s username → a new password → signed in; the old sign-in is gone', async () => {
    const confirm = vi.fn(async (): Promise<ConfirmResult> => ({ ok: true, method: 'test' }));
    const { hub, service, sent } = await localMode(confirm);
    try {
      expect(await service.state()).toEqual({ local: true, recovery: 'test', localSignIn: false });
      const opened = await service.beginRecovery();
      expect(confirm).toHaveBeenCalledWith('reset the owner password');
      expect(opened).toMatchObject({ ok: true, username: 'admin' });
      // The page gets the username, never the grant.
      expect(JSON.stringify(opened)).not.toMatch(/[0-9a-f]{64}/);

      expect(await service.finishRecovery('short')).toEqual({
        ok: false,
        reason: 'invalid_password',
      });
      const done = await service.finishRecovery('a-brand-new-password');
      expect(done.ok).toBe(true);
      if (!done.ok) return;
      expect(done.tokens.user.username).toBe('admin');
      // Spent: a second try needs the OS again.
      expect(await service.finishRecovery('another-new-password')).toEqual({
        ok: false,
        reason: 'expired',
      });

      expect((await login(hub, TEST_ADMIN_PASSWORD)).statusCode).toBe(401);
      expect((await login(hub, 'a-brand-new-password')).statusCode).toBe(200);
      const old = await hub.app.inject({
        method: 'GET',
        url: '/api/v1/auth/me',
        headers: { authorization: `Bearer ${hub.token}` },
      });
      expect(old.statusCode).toBe(401);
      // Everything went over the IPC channel.
      expect(sent.map((m) => m.op)).toEqual(['begin', 'finish', 'finish']);
    } finally {
      await hub.close();
    }
  });

  it('a refused OS prompt asks the hub nothing, and the prompts are rate-limited', async () => {
    const { hub, service, sent } = await localMode(async () => ({
      ok: false,
      reason: 'cancelled',
      detail: null,
    }));
    try {
      for (let i = 0; i < PROMPTS_MAX; i++)
        expect(await service.beginRecovery()).toEqual({ ok: false, reason: 'cancelled' });
      expect(await service.beginRecovery()).toEqual({ ok: false, reason: 'rate_limited' });
      expect(sent).toEqual([]);
      expect(await service.finishRecovery('a-brand-new-password')).toEqual({
        ok: false,
        reason: 'expired',
      });
      expect((await login(hub, TEST_ADMIN_PASSWORD)).statusCode).toBe(200);
    } finally {
      await hub.close();
    }
  });

  it('is not offered, and does nothing, when the window talks to a hub elsewhere', async () => {
    const confirm = vi.fn(async (): Promise<ConfirmResult> => ({ ok: true, method: 'test' }));
    const { hub, service, sent } = await localMode(confirm, { local: false });
    try {
      expect(await service.state()).toMatchObject({ local: false, recovery: null });
      expect(await service.beginRecovery()).toEqual({ ok: false, reason: 'not_local' });
      expect(await service.signIn()).toEqual({ ok: false, reason: 'not_local' });
      expect(confirm).not.toHaveBeenCalled();
      expect(sent).toEqual([]);
    } finally {
      await hub.close();
    }
  });
});

describe('signing in on this computer without a password', () => {
  it('works only while the setting is on, and only the owner may turn it on', async () => {
    const { hub, service } = await localMode(async () => ({ ok: true, method: 'test' }));
    try {
      expect(await service.signIn()).toEqual({ ok: false, reason: 'off' });
      // Not the owner's sign-in: refused.
      expect(await service.setLocalSignIn(true, 'hub_at_nobody')).toMatchObject({
        localSignIn: false,
        refused: 'not_owner',
      });
      expect(await service.setLocalSignIn(true, hub.token)).toMatchObject({ localSignIn: true });
      const signed = await service.signIn();
      expect(signed.ok).toBe(true);
      if (signed.ok) expect(signed.tokens.user.role).toBe('owner');
      // Off takes nobody.
      expect(await service.setLocalSignIn(false, null)).toMatchObject({ localSignIn: false });
      expect(await service.signIn()).toEqual({ ok: false, reason: 'off' });
    } finally {
      await hub.close();
    }
  });
});
