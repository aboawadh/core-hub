/**
 * Settings → Secrets in the web client (DECISIONS §125). The hub is the gate; what the client
 * must get right:
 *
 * - only the owner sees the entry (an admin and a member do not), on web and desktop only;
 * - the page opens locked and asks for the password; the password leaves the page at once;
 * - the list is grouped by kind and profile, values masked; showing one hides any other, and
 *   it hides itself after 30 seconds; Copy fetches it when hidden and puts it on the clipboard;
 * - a refused grant locks the page again, asking for the password; leaving the page ends the
 *   grant on the hub; an older hub without the page says so;
 * - nothing is kept: no value in storage.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../src/auth/context.js';
import { SessionStore } from '../src/auth/store.js';
import { ThemeProvider } from '../src/design/theme.js';
import { I18nProvider } from '../src/i18n/context.js';
import { destinationsById, navigation, visibleEntries } from '../src/navigation/manifest.js';
import { RealtimeProvider } from '../src/realtime/context.js';
import { SecretsTab } from '../src/settings/SecretsTab.js';
import { SettingsNav } from '../src/settings/SettingsNav.js';
import { groupSecrets, refusalOf } from '../src/settings/secrets.js';
import { HubApiError } from '@corehub/contracts';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    length: 0,
    dump: () => [...map.values()].join('\n'),
  } as Storage;
}

const ITEMS = [
  { id: 'k1', kind: 'provider_key', profile: null, label: 'OpenAI', name: 'openai' },
  { id: 'c1', kind: 'channel', profile: 'work', label: 'Telegram', name: 'TELEGRAM_BOT_TOKEN' },
  { id: 'c2', kind: 'channel', profile: 'default', label: 'Discord', name: 'DISCORD_BOT_TOKEN' },
  { id: 'w1', kind: 'webhook_out', profile: null, label: 'CI', name: 'signing_secret' },
];
const VALUES: Record<string, string> = {
  k1: 'sk-web-test-0001',
  c1: '123:telegram-web-test',
  c2: 'discord-web-test',
  w1: 'whsec-web-test',
};

interface Call {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
}

function hub(
  options: {
    password?: string;
    refuseReveal?: boolean;
    missing?: boolean;
  } = {},
) {
  const calls: Call[] = [];
  const fetchImpl = (async (url: string, init?: RequestInit) => {
    const path = new URL(String(url)).pathname.replace(/^\/api\/v\d+/, '');
    const method = init?.method ?? 'GET';
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null;
    calls.push({ method, path, body });
    const json = (value: unknown, status = 200) =>
      new Response(status === 204 ? null : JSON.stringify(value), {
        status,
        headers: { 'content-type': 'application/json' },
      });
    if (options.missing) return json({ error: 'Not found', code: 'not_found' }, 404);
    if (path === '/auth/step-up' && method === 'POST') {
      if (body?.password !== (options.password ?? 'right-password'))
        return json(
          {
            error: 'That password is not right.',
            code: 'unauthorized',
            details: { reason: 'wrong_password' },
          },
          401,
        );
      return json({
        grant: 'su_test-grant-0000000000000',
        purpose: 'secrets',
        expires_at: new Date(Date.now() + 300_000).toISOString(),
        ttl_seconds: 300,
      });
    }
    if (path === '/auth/step-up' && method === 'DELETE') return json(null, 204);
    if (path === '/secrets/list') return json({ items: ITEMS });
    if (path === '/secrets/reveal') {
      if (options.refuseReveal)
        return json(
          { error: 'again', code: 'forbidden', details: { reason: 'step_up_required' } },
          403,
        );
      return json({ id: body?.id, value: VALUES[String(body?.id)] });
    }
    return json({ items: [] });
  }) as unknown as typeof fetch;
  return { fetchImpl, calls };
}

function mount(node: React.ReactElement, fetchImpl: typeof fetch, role = 'owner') {
  const storage = memoryStorage();
  const store = new SessionStore(storage);
  store.save({
    profile: 'default',
    token: 't',
    refresh_token: null,
    expires_at: null,
    user: { id: 'u', username: 'admin', display_name: 'Admin', role },
  });
  const view = render(
    <ThemeProvider>
      <I18nProvider language="en">
        <QueryClientProvider
          client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
        >
          <AuthProvider store={store} baseUrl="http://hub.test" fetchImpl={fetchImpl}>
            <RealtimeProvider>
              <MemoryRouter initialEntries={['/settings/secrets']}>{node}</MemoryRouter>
            </RealtimeProvider>
          </AuthProvider>
        </QueryClientProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, storage };
}

async function open(password = 'right-password') {
  fireEvent.change(screen.getByTestId('secrets-password'), { target: { value: password } });
  fireEvent.click(screen.getByTestId('secrets-unlock'));
}

describe('Secrets: who sees it', () => {
  it('is the owner’s entry alone, on web and desktop, right after Privacy', () => {
    const secrets = destinationsById.get('secrets')!;
    expect(secrets.roles).toEqual(['owner']);
    expect(secrets.surfaces).toEqual(['web', 'desktop']);
    expect(navigation.settingsTabs.indexOf('secrets')).toBe(
      navigation.settingsTabs.indexOf('privacy') + 1,
    );
    const ids = (role: string) => visibleEntries(navigation.settingsTabs, role).map((d) => d.id);
    expect(ids('owner')).toContain('secrets');
    expect(ids('admin')).not.toContain('secrets');
    expect(ids('member')).not.toContain('secrets');
  });

  it('draws no row for an admin, and one for the owner', () => {
    const { fetchImpl } = hub();
    mount(<SettingsNav current="account" />, fetchImpl, 'admin');
    expect(screen.queryByRole('link', { name: 'Secrets' })).toBeNull();
    cleanup();
    mount(<SettingsNav current="account" />, fetchImpl, 'owner');
    expect(screen.getByRole('link', { name: 'Secrets' }).getAttribute('href')).toBe(
      '/settings/secrets',
    );
  });
});

describe('Secrets: the page', () => {
  it('opens locked, asks for the password, and lists names grouped by kind and profile', async () => {
    const { fetchImpl, calls } = hub();
    const { storage } = mount(<SecretsTab />, fetchImpl);
    expect(screen.getByTestId('secrets-locked')).toBeTruthy();
    expect(calls.filter((c) => c.path.startsWith('/secrets'))).toEqual([]);
    await open();
    await screen.findByTestId('secrets-open');
    expect(calls.find((c) => c.path === '/auth/step-up')!.body).toEqual({
      password: 'right-password',
      purpose: 'secrets',
    });
    expect(calls.find((c) => c.path === '/secrets/list')!.body).toEqual({
      grant: 'su_test-grant-0000000000000',
    });
    const keys = screen.getByTestId('secrets-group-provider_key');
    expect(within(keys).getByText('Shared by every profile')).toBeTruthy();
    const channels = screen.getByTestId('secrets-group-channel');
    // Profiles in order, each named: default before work.
    const rows = within(channels).getAllByTestId('secret-row');
    expect(rows.map((row) => within(row).getByText(/_BOT_TOKEN$/).textContent)).toEqual([
      'DISCORD_BOT_TOKEN',
      'TELEGRAM_BOT_TOKEN',
    ]);
    // Masked, every one.
    for (const value of screen.getAllByTestId('secret-value'))
      expect(value.textContent).toBe('••••••••••••');
    for (const value of Object.values(VALUES))
      expect(document.body.textContent).not.toContain(value);
    expect((storage as unknown as { dump(): string }).dump()).not.toContain('su_test');
  });

  it('a wrong password says so and keeps the page locked', async () => {
    const { fetchImpl } = hub();
    mount(<SecretsTab />, fetchImpl);
    await open('wrong');
    expect((await screen.findByTestId('secrets-error')).textContent).toContain(
      'That password is not right.',
    );
    expect(screen.getByTestId('secrets-locked')).toBeTruthy();
    expect((screen.getByTestId('secrets-password') as HTMLInputElement).value).toBe('');
  });

  it('shows one value at a time, hides it after 30 seconds, and copies', async () => {
    const writeText = vi.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const { fetchImpl, calls } = hub();
    mount(<SecretsTab />, fetchImpl);
    await open();
    await screen.findByTestId('secrets-open');
    const rows = () => screen.getAllByTestId('secret-row');
    fireEvent.click(within(rows()[0]!).getByTestId('secret-show'));
    await waitFor(() =>
      expect(within(rows()[0]!).getByTestId('secret-value').textContent).toBe(VALUES.k1),
    );
    // Another: the first hides.
    fireEvent.click(within(rows()[1]!).getByTestId('secret-show'));
    await waitFor(() =>
      expect(within(rows()[1]!).getByTestId('secret-value').textContent).toBe(VALUES.c2),
    );
    expect(within(rows()[0]!).getByTestId('secret-value').textContent).toBe('••••••••••••');
    // Copy of a hidden one fetches it (the hub audits that) without showing it.
    fireEvent.click(within(rows()[3]!).getByTestId('secret-copy'));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(VALUES.w1));
    expect(within(rows()[3]!).getByTestId('secret-value').textContent).toBe('••••••••••••');
    expect(calls.filter((c) => c.path === '/secrets/reveal').map((c) => c.body?.id)).toEqual([
      'k1',
      'c2',
      'w1',
    ]);
    // Thirty seconds later the shown one hides itself.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await act(async () => {
      vi.advanceTimersByTime(31_000);
    });
    await waitFor(() =>
      expect(within(rows()[1]!).getByTestId('secret-value').textContent).toBe('••••••••••••'),
    );
  });

  it('locks again when the hub refuses the grant, asking for the password', async () => {
    const { fetchImpl } = hub({ refuseReveal: true });
    mount(<SecretsTab />, fetchImpl);
    await open();
    await screen.findByTestId('secrets-open');
    fireEvent.click(within(screen.getAllByTestId('secret-row')[0]!).getByTestId('secret-show'));
    expect(await screen.findByTestId('secrets-locked')).toBeTruthy();
    expect(screen.getByTestId('secrets-notice').textContent).toMatch(/password again/);
  });

  it('ends the grant on the hub when the page is left, and when closed by hand', async () => {
    const { fetchImpl, calls } = hub();
    const view = mount(<SecretsTab />, fetchImpl);
    await open();
    await screen.findByTestId('secrets-open');
    fireEvent.click(screen.getByTestId('secrets-lock'));
    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'DELETE' && c.path === '/auth/step-up')).toHaveLength(
        1,
      ),
    );
    expect(screen.getByTestId('secrets-locked')).toBeTruthy();
    await open();
    await screen.findByTestId('secrets-open');
    view.unmount();
    await waitFor(() =>
      expect(calls.filter((c) => c.method === 'DELETE' && c.path === '/auth/step-up')).toHaveLength(
        2,
      ),
    );
  });

  it('says so on an older hub that has no Secrets', async () => {
    const { fetchImpl } = hub({ missing: true });
    mount(<SecretsTab />, fetchImpl);
    await open();
    expect((await screen.findByTestId('secrets-error')).textContent).toContain(
      'does not offer Secrets',
    );
  });
});

describe('Secrets: the pieces', () => {
  it('groups by kind (unknown kinds last) and profile (the hub-wide first)', () => {
    const groups = groupSecrets([
      { id: 'x', kind: 'future_kind', profile: null, label: null, name: 'x' },
      ...ITEMS,
    ]);
    expect(groups.map((g) => g.kind)).toEqual([
      'provider_key',
      'channel',
      'webhook_out',
      'future_kind',
    ]);
    expect(groups[1]!.profiles.map((p) => p.profile)).toEqual(['default', 'work']);
  });

  it('reads what a refusal means', () => {
    const error = (status: number, reason?: string) =>
      new HubApiError(status, 'x', 'x', reason ? { details: { reason } } : null);
    expect(refusalOf(error(401, 'wrong_password'))).toBe('wrong_password');
    expect(refusalOf(error(429))).toBe('locked_out');
    expect(refusalOf(error(403, 'step_up_required'))).toBe('step_up_required');
    expect(refusalOf(error(404))).toBe('unsupported');
    expect(refusalOf(error(403, 'owner_only'))).toBeNull();
    expect(refusalOf(new Error('x'))).toBeNull();
  });
});
