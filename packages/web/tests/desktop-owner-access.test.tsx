// The owner of the hub on this computer, in the web client (DECISIONS §131): "Forgot password?"
// and "Sign in on this computer" appear only inside the desktop app talking to its own hub; the
// reset shows the owner's username in monospace and sets the new password; password-free sign-in
// happens at start when the owner turned it on; This device holds the switch.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../src/auth/context.js';
import { SessionStore, type StoredSession } from '../src/auth/store.js';
import type {
  DesktopOwnerAccessState,
  DesktopOwnerTokens,
  DesktopState,
} from '../src/desktop/bridge-types.js';
import { adoptDesktopSession } from '../src/desktop/desktop.js';
import { ThemeProvider } from '../src/design/theme.js';
import { I18nProvider } from '../src/i18n/context.js';
import { LoginScreen } from '../src/screens/LoginScreen.js';
import { ThisDeviceTab } from '../src/settings/ThisDeviceTab.js';

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    length: 0,
  };
}

const TOKENS: DesktopOwnerTokens = {
  access_token: 'jwt-after-reset',
  refresh_token: 'hub_rt_after',
  expires_in: 900,
  user: {
    id: '01J8QK3ZR2W7M5N4P6T8V9X0HM',
    username: 't.wuijri',
    display_name: 'Tariq',
    role: 'owner',
    default_profile: 'default',
  },
};

function fakeOwner(over: Partial<DesktopOwnerAccessState> = {}) {
  let state: DesktopOwnerAccessState = {
    local: true,
    recovery: 'macos',
    localSignIn: false,
    ...over,
  };
  return {
    get: vi.fn(async () => state),
    beginRecovery: vi.fn(async () => ({
      ok: true as const,
      username: 't.wuijri',
      displayName: 'Tariq',
      expiresAt: '2026-09-28T10:05:00Z',
    })),
    finishRecovery: vi.fn(async (_password: string) => ({ ok: true as const, tokens: TOKENS })),
    cancelRecovery: vi.fn(async () => {}),
    signIn: vi.fn(async () => ({ ok: true as const, tokens: TOKENS })),
    setLocalSignIn: vi.fn(async (value: boolean, _token: string | null) => {
      state = { ...state, localSignIn: value };
      return state;
    }),
  };
}

function fakeBridge(owner: ReturnType<typeof fakeOwner> | null, over: Partial<DesktopState> = {}) {
  const state: DesktopState = {
    appVersion: '1.2.3',
    platform: 'linux',
    mode: 'local',
    hubUrl: 'http://127.0.0.1:47113',
    closeToTray: true,
    trayAvailable: true,
    local: { dataDir: '/home/t/.config/Core Hub/local-hub', hermes: 'none', hermesProgram: null },
    ...over,
  };
  return {
    surface: 'desktop' as const,
    getState: vi.fn(async () => state),
    takePendingSession: vi.fn(async (): Promise<unknown> => null),
    changeConnection: vi.fn(async () => {}),
    setCloseToTray: vi.fn(async () => state),
    setLanguage: vi.fn(),
    notify: vi.fn(),
    setUnreadCount: vi.fn(),
    onOpenPath: vi.fn(() => () => {}),
    helper: {
      get: vi.fn(async () => ({
        enabled: false,
        url: null,
        token: 'a'.repeat(64),
        folders: [],
        allowOpen: false,
        tools: [],
        activity: [],
        error: null,
      })),
    },
    programs: {
      get: vi.fn(async () => ({ programs: [], scannedAt: null, resolve: null })),
    },
    device: {
      get: vi.fn(async () => ({
        hub: null,
        linked: false,
        deviceId: null,
        status: 'unlinked',
        detail: null,
      })),
    },
    updates: { get: vi.fn(async () => ({ auto: true, last: null, releasesPage: 'https://x' })) },
    ...(owner ? { ownerAccess: owner } : {}),
  };
}

const withBridge = (bridge: unknown) => {
  (globalThis as { corehubDesktop?: unknown }).corehubDesktop = bridge;
};

afterEach(() => {
  cleanup();
  delete (globalThis as { corehubDesktop?: unknown }).corehubDesktop;
});

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } });
const fetchImpl = (async (url: string) => {
  const pathname = new URL(String(url)).pathname;
  if (pathname.endsWith('/meta'))
    return json({
      name: 'Core Hub',
      server_version: '1.1.4',
      contract_version: '1.0.0',
      api_versions: ['v1'],
      realtime_namespaces: [],
      locales: ['ar', 'en'],
      setup_required: false,
    });
  if (pathname.endsWith('/auth/setup')) return json({ required: false });
  return json({ items: [] });
}) as unknown as typeof fetch;

function Probe() {
  return <span data-testid="path">{useLocation().pathname}</span>;
}

function mountLogin(language: 'ar' | 'en' = 'en', store = new SessionStore(memoryStorage())) {
  render(
    <ThemeProvider>
      <I18nProvider language={language}>
        <QueryClientProvider
          client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
        >
          <AuthProvider store={store} baseUrl="http://hub.test" fetchImpl={fetchImpl}>
            <MemoryRouter initialEntries={['/login']}>
              <Probe />
              <Routes>
                <Route path="/login" element={<LoginScreen />} />
                <Route path="*" element={<span>home</span>} />
              </Routes>
            </MemoryRouter>
          </AuthProvider>
        </QueryClientProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
  return store;
}

describe('the sign-in screen', () => {
  it('offers nothing extra in a browser', async () => {
    mountLogin();
    await screen.findByRole('heading', { name: 'Sign in' });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByTestId('forgot-password')).toBeNull();
    expect(screen.queryByTestId('local-sign-in')).toBeNull();
  });

  it('offers nothing extra when the app talks to a hub elsewhere, or the OS has no prompt', async () => {
    const remote = fakeOwner({ local: false, recovery: null, localSignIn: true });
    withBridge(fakeBridge(remote, { mode: 'remote' }));
    mountLogin();
    await waitFor(() => expect(remote.get).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByTestId('forgot-password')).toBeNull();
    expect(screen.queryByTestId('local-sign-in')).toBeNull();
    cleanup();

    const noPrompt = fakeOwner({ recovery: null });
    withBridge(fakeBridge(noPrompt));
    mountLogin();
    await waitFor(() => expect(noPrompt.get).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByTestId('forgot-password')).toBeNull();
  });

  it('forgot password: the OS confirms, the username is shown in monospace, the new password signs in', async () => {
    const owner = fakeOwner();
    withBridge(fakeBridge(owner));
    const store = mountLogin('ar');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'نسيت كلمة المرور؟' }));
    expect(owner.beginRecovery).toHaveBeenCalledTimes(1);

    const name = await screen.findByTestId('recovery-username');
    expect(name).toHaveTextContent('t.wuijri');
    expect(name.tagName).toBe('CODE');
    expect(name.className).toContain('font-mono');
    expect(name).toHaveAttribute('dir', 'auto');
    expect(screen.getByTestId('recovery-sign-out-notice')).toHaveTextContent('الهواتف');

    const password = screen.getByLabelText('كلمة المرور الجديدة');
    const confirm = screen.getByLabelText('تأكيد كلمة المرور الجديدة');
    await user.type(password, 'short');
    await user.type(confirm, 'short');
    await user.click(screen.getByRole('button', { name: 'احفظ وادخل' }));
    expect(await screen.findByText('يجب ألا تقل كلمة المرور عن 8 أحرف.')).toBeInTheDocument();
    expect(owner.finishRecovery).not.toHaveBeenCalled();

    await user.clear(password);
    await user.clear(confirm);
    await user.type(password, 'a-new-password');
    await user.type(confirm, 'a-new-passwort');
    await user.click(screen.getByRole('button', { name: 'احفظ وادخل' }));
    expect(await screen.findByText('كلمتا المرور غير متطابقتين.')).toBeInTheDocument();
    expect(owner.finishRecovery).not.toHaveBeenCalled();

    await user.clear(confirm);
    await user.type(confirm, 'a-new-password');
    await user.click(screen.getByRole('button', { name: 'احفظ وادخل' }));
    await waitFor(() => expect(owner.finishRecovery).toHaveBeenCalledWith('a-new-password'));
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/chat'));
    expect(store.read()).toMatchObject({
      token: 'jwt-after-reset',
      refresh_token: 'hub_rt_after',
      user: { username: 't.wuijri', role: 'owner' },
    });
  });

  it('says so when the computer did not confirm, and changes nothing', async () => {
    const owner = fakeOwner();
    owner.beginRecovery.mockResolvedValueOnce({ ok: false, reason: 'cancelled' } as never);
    withBridge(fakeBridge(owner));
    mountLogin();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Forgot password?' }));
    expect(await screen.findByTestId('local-owner-error')).toHaveTextContent(
      'Your computer did not confirm it is you. Nothing changed.',
    );
    expect(screen.queryByTestId('recovery-form')).toBeNull();
  });

  it('signs in on this computer without a password when the owner turned it on', async () => {
    const owner = fakeOwner({ localSignIn: true });
    withBridge(fakeBridge(owner));
    const store = mountLogin();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Sign in on this computer' }));
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/chat'));
    expect(owner.signIn).toHaveBeenCalledTimes(1);
    expect(store.read()?.user.username).toBe('t.wuijri');
  });
});

describe('at start', () => {
  it('the app signs the owner in when password-free sign-in is on', async () => {
    const owner = fakeOwner({ localSignIn: true });
    withBridge(fakeBridge(owner));
    const store = new SessionStore(memoryStorage());
    await adoptDesktopSession(store);
    expect(store.read()?.token).toBe('jwt-after-reset');
  });

  it('does nothing when it is off, when a session exists, or with a hub elsewhere', async () => {
    const off = fakeOwner({ localSignIn: false });
    withBridge(fakeBridge(off));
    const empty = new SessionStore(memoryStorage());
    await adoptDesktopSession(empty);
    expect(empty.read()).toBeNull();
    expect(off.signIn).not.toHaveBeenCalled();

    const on = fakeOwner({ localSignIn: true });
    withBridge(fakeBridge(on));
    const signedIn = new SessionStore(memoryStorage());
    const existing: StoredSession = {
      profile: 'default',
      token: 'mine',
      refresh_token: null,
      expires_at: null,
      user: { id: 'u', username: 'sara', display_name: 'Sara', role: 'member' },
    };
    signedIn.save(existing);
    await adoptDesktopSession(signedIn);
    expect(signedIn.read()?.token).toBe('mine');
    expect(on.signIn).not.toHaveBeenCalled();

    const remote = fakeOwner({ local: false, localSignIn: true });
    withBridge(fakeBridge(remote, { mode: 'remote' }));
    await adoptDesktopSession(new SessionStore(memoryStorage()));
    expect(remote.signIn).not.toHaveBeenCalled();
  });
});

describe('This device', () => {
  const owner: StoredSession = {
    profile: 'default',
    token: 'owner-jwt',
    refresh_token: null,
    expires_at: null,
    user: { id: 'u', username: 't.wuijri', display_name: 'Tariq', role: 'owner' },
  };

  function mountThisDevice(session: StoredSession) {
    const store = new SessionStore(memoryStorage());
    store.save(session);
    render(
      <ThemeProvider>
        <I18nProvider language="en">
          <QueryClientProvider
            client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
          >
            <AuthProvider store={store} baseUrl="http://hub.test" fetchImpl={fetchImpl}>
              <MemoryRouter>
                <ThisDeviceTab />
              </MemoryRouter>
            </AuthProvider>
          </QueryClientProvider>
        </I18nProvider>
      </ThemeProvider>,
    );
  }

  it('the owner turns password-free sign-in on with their own sign-in', async () => {
    const access = fakeOwner({ localSignIn: false });
    withBridge(fakeBridge(access));
    mountThisDevice(owner);
    const toggle = await screen.findByTestId('this-device-local-sign-in');
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await userEvent.click(toggle);
    await waitFor(() => expect(access.setLocalSignIn).toHaveBeenCalledWith(true, 'owner-jwt'));
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
  });

  it('is not shown to anyone but the owner', async () => {
    const access = fakeOwner();
    withBridge(fakeBridge(access));
    mountThisDevice({ ...owner, user: { ...owner.user, role: 'admin' } });
    await screen.findByTestId('this-device');
    expect(screen.queryByTestId('this-device-local-sign-in')).toBeNull();
  });
});
