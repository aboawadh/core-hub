/**
 * Connecting a remote MCP server by OAuth from its row (DECISIONS §121): the chip says whether
 * this profile is signed in, Connect opens the provider's page in the tab it opened on the
 * click and waits for Hermes, a failed test that wants a sign-in offers Connect where it is
 * read, and Disconnect forgets the sign-in after asking. The whole app is mounted on a scripted
 * hub, so what is asserted is what a person meets and what the page asks the hub.
 */
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Agent } from '../src/types.js';

class FakeSocket {
  connected = false;
  io = { on: () => undefined };
  on() {
    return this;
  }
  off() {
    return this;
  }
  once() {
    return this;
  }
  connect() {
    return this;
  }
  emit(_event: string, _payload: unknown, ack?: (reply: unknown) => void) {
    ack?.({ ok: true, replayed: 0, truncated: false });
    return this;
  }
  removeAllListeners() {}
  disconnect() {}
}

vi.mock('../src/realtime/socket.js', async (importOriginal) => {
  const real = await importOriginal<Record<string, unknown>>();
  return { ...real, connectNamespace: () => new FakeSocket() };
});

const { App } = await import('../src/app.js');
const { SessionStore } = await import('../src/auth/store.js');

afterEach(cleanup);

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

const HERMES = '01J8QK3ZR2W7M5N4P6T8V9X0AG';

const AGENT = {
  id: HERMES,
  profile: 'default',
  owner_id: '01J8QK3ZR2W7M5N4P6T8V9X0HM',
  created_at: '2026-09-01T00:00:00Z',
  updated_at: '2026-09-01T00:00:00Z',
  slug: 'hermes',
  name: 'Hermes',
  vendor: null,
  kind: 'hermes',
  adapter: 'hermes',
  status: 'available',
  enabled: true,
  limited: false,
  capabilities: ['streaming', 'skills', 'memory', 'mcp'],
  sections: [],
  default_model: null,
  runtime: { error: null },
  install: {
    source: 'bundled',
    version: '1.0.0',
    error: null,
    update_available: false,
    latest_version: null,
  },
} as unknown as Agent;

interface Sent {
  url: string;
  method: string;
  body: Record<string, unknown> | null;
}

type OAuthStatus = 'connected' | 'expired' | 'not_connected' | 'error';

function server(
  name: string,
  config: Record<string, unknown>,
  oauth?: { required: boolean; status: OAuthStatus },
) {
  return {
    name,
    transport: typeof config.url === 'string' ? 'http' : 'stdio',
    enabled: true,
    connected: false,
    tools: [],
    error: null,
    config,
    updated_at: '2026-09-27T08:00:00Z',
    ...(oauth ? { oauth: { ...oauth, expires_at: null } } : {}),
  };
}

const AUTHORIZE = 'https://auth.example/authorize?client_id=c1&state=s1';
const FLOW = '01J8QK3ZR2W7M5N4P6T8V9X0F1';

function hub(options: { status?: OAuthStatus; testError?: string | null } = {}) {
  const sent: Sent[] = [];
  let status: OAuthStatus = options.status ?? 'not_connected';
  let polls = 0;
  const flow = (state: string, tools: unknown[] = []) => ({
    id: FLOW,
    server_name: 'clickup',
    status: state,
    authorization_url: AUTHORIZE,
    redirect_uri: 'http://localhost:3000/api/v1/mcp-oauth/callback/clickup',
    error: null,
    tools,
    expires_at: '2026-09-27T09:00:00Z',
  });
  const fetchImpl = ((input: string, init: RequestInit = {}) => {
    const url = new URL(String(input));
    const path = url.pathname;
    const method = (init.method ?? 'GET').toUpperCase();
    const body = init.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null;
    sent.push({ url: `${path}${url.search}`, method, body });
    const json = (value: unknown, code = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(value), {
          status: code,
          headers: { 'content-type': 'application/json' },
        }),
      );
    if (path.endsWith('/agents')) return json({ items: [AGENT] });
    if (path.endsWith('/profiles'))
      return json({
        items: [{ id: '01J8QK3ZR2W7M5N4P6T8V9X0P1', slug: 'default', name: 'Default' }],
      });
    if (path.endsWith('/meta')) return json({ name: 'Core Hub', server_version: '0.0.0' });
    if (path.endsWith('/hub-tools'))
      return json({
        enabled: false,
        available: false,
        unavailable_reason: 'runtime_absent',
        server_name: 'corehub',
        url: null,
        groups: [],
        recent_calls: [],
        updated_at: null,
      });
    if (path.endsWith('/mcp-servers/clickup/oauth') && method === 'POST') {
      polls = 0;
      return json(flow('pending'));
    }
    if (path.endsWith('/mcp-servers/clickup/oauth') && method === 'DELETE') {
      status = 'not_connected';
      return json(
        server(
          'clickup',
          { url: 'https://mcp.clickup.example/mcp', auth: 'oauth' },
          { required: true, status },
        ),
      );
    }
    if (path.endsWith(`/mcp-servers/clickup/oauth/${FLOW}`)) {
      polls += 1;
      if (polls < 2) return json(flow('pending'));
      status = 'connected';
      return json(
        flow('approved', [
          { name: 'get_tasks', description: null },
          { name: 'create_task', description: null },
        ]),
      );
    }
    if (path.endsWith('/mcp-servers/clickup/test')) {
      const error = status === 'connected' ? null : (options.testError ?? null);
      return json({
        ok: error === null,
        tools: error === null ? [{ name: 'get_tasks', description: null }] : [],
        error,
        duration_ms: 700,
      });
    }
    if (path.endsWith('/mcp-servers')) {
      return json({
        items: [
          server(
            'clickup',
            { url: 'https://mcp.clickup.example/mcp', auth: 'oauth' },
            { required: true, status },
          ),
          server(
            'github',
            { url: 'https://mcp.example/github', headers: { Authorization: '[stored]' } },
            { required: false, status: 'not_connected' },
          ),
          server('legacy', { url: 'https://old-hub.example/mcp' }),
          server('files', { command: 'npx' }),
        ],
      });
    }
    return json({ items: [], next_cursor: null });
  }) as unknown as typeof fetch;
  return { fetchImpl, sent };
}

function mount(fetchImpl: typeof fetch) {
  const store = new SessionStore(memoryStorage());
  store.save({
    profile: 'default',
    token: 't',
    refresh_token: null,
    expires_at: null,
    user: { id: '01J8QK3ZR2W7M5N4P6T8V9X0AA', username: 'u', display_name: 'U', role: 'owner' },
  });
  render(
    <App
      store={store}
      baseUrl="http://hub.test"
      fetchImpl={fetchImpl}
      router={(children) => (
        <MemoryRouter initialEntries={[`/agents/${HERMES}/mcp`]}>{children}</MemoryRouter>
      )}
    />,
  );
}

/** The tab `window.open` hands back on the click, before the hub has named the page. */
function fakeTab() {
  const tab = {
    closed: false,
    opener: {} as unknown,
    location: { href: 'about:blank' },
    close: vi.fn(),
  };
  const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
  return { tab, open };
}

afterEach(() => vi.restoreAllMocks());

describe('connecting an MCP server by OAuth', () => {
  it('offers it only where Hermes can sign in, and says each profile connects separately', async () => {
    const { fetchImpl } = hub();
    mount(fetchImpl);
    const chip = await screen.findByTestId('mcp-oauth-status-clickup');
    expect(chip.textContent).toContain('Not connected');
    expect(screen.getByTestId('mcp-oauth-connect-clickup').textContent).toBe('Connect OAuth');
    expect(
      within(screen.getByTestId('mcp-oauth-clickup')).getByText(
        'Each profile connects separately.',
      ),
    ).toBeTruthy();
    // A server that signs in by its own header, an older hub, a process: nothing to offer.
    expect(screen.queryByTestId('mcp-oauth-github')).toBeNull();
    expect(screen.queryByTestId('mcp-oauth-legacy')).toBeNull();
    expect(screen.queryByTestId('mcp-oauth-files')).toBeNull();
  });

  it('opens the provider in the tab opened on the click, waits for Hermes, then says connected with the tools and tests', async () => {
    const { fetchImpl, sent } = hub();
    const { tab, open } = fakeTab();
    mount(fetchImpl);
    fireEvent.click(await screen.findByTestId('mcp-oauth-connect-clickup'));
    expect(open).toHaveBeenCalledWith('about:blank', '_blank');
    await waitFor(() => expect(tab.location.href).toBe(AUTHORIZE));
    expect(tab.opener).toBeNull();
    const start = sent.find(
      (s) => s.method === 'POST' && s.url.endsWith('/mcp-servers/clickup/oauth'),
    );
    expect(start?.body).toEqual({ hub_url: window.location.origin });
    expect((await screen.findByTestId('mcp-oauth-link-clickup')).getAttribute('href')).toBe(
      AUTHORIZE,
    );

    const done = await screen.findByTestId('mcp-oauth-result-clickup', undefined, {
      timeout: 5000,
    });
    expect(done.getAttribute('data-status')).toBe('approved');
    expect(done.textContent).toContain('2 tools');
    await waitFor(() =>
      expect(screen.getByTestId('mcp-oauth-status-clickup').textContent).toContain('Connected'),
    );

    fireEvent.click(screen.getByTestId('mcp-oauth-test-clickup'));
    const result = await screen.findByTestId('mcp-test-result-clickup');
    expect(result.getAttribute('data-ok')).toBe('true');
  });

  it("offers Connect under a test that failed for want of a sign-in, in Hermes's words", async () => {
    const { fetchImpl } = hub({ testError: 'OAuth authentication required — no token found.' });
    fakeTab();
    mount(fetchImpl);
    fireEvent.click(await screen.findByTestId('mcp-test-clickup'));
    const result = await screen.findByTestId('mcp-test-result-clickup');
    expect(result.textContent).toContain('no token found');
    expect(within(result).getByText('This server needs a sign-in.')).toBeTruthy();
    fireEvent.click(within(result).getByTestId('mcp-test-connect-clickup'));
    await screen.findByTestId('mcp-oauth-waiting-clickup');
  });

  it('offers Reconnect when the sign-in ran out, and forgets it on Disconnect after asking', async () => {
    const { fetchImpl, sent } = hub({ status: 'expired' });
    mount(fetchImpl);
    expect((await screen.findByTestId('mcp-oauth-status-clickup')).textContent).toContain(
      'Expired',
    );
    expect(screen.getByTestId('mcp-oauth-connect-clickup').textContent).toBe('Reconnect OAuth');
    fireEvent.click(screen.getByTestId('mcp-oauth-disconnect-clickup'));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Disconnect' }));
    await waitFor(() =>
      expect(
        sent.some((s) => s.method === 'DELETE' && s.url.endsWith('/mcp-servers/clickup/oauth')),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(screen.getByTestId('mcp-oauth-status-clickup').textContent).toContain('Not connected'),
    );
  });
});
