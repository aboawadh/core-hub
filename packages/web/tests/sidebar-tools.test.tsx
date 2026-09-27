/**
 * The sidebar's «Tools» group and Search beside the fold toggle (owner, 2026-09-28,
 * DECISIONS §126).
 *
 * Asserted on the whole app with a scripted hub: Search is an icon in the brand row next to the
 * fold toggle, and a row right below New chat once folded; «Tools» holds Agents (owners and
 * admins only), Tasks, Workflows and Schedules in that order; a press closes and opens it, the
 * choice is remembered on this device (and a blocked storage just leaves it open); closed while
 * the page is inside it, the heading is marked as the place; Workflows opens its own page.
 * The browser journey (e2e zzzzzzzzzzzzzzz-sidebar-tools) proves the same in Arabic, in a browser.
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FOLD_STORAGE } from '../src/shell/sidebarFold.js';
import { GROUPS_STORAGE, readClosedGroups, writeClosedGroups } from '../src/shell/sidebarGroups.js';

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
beforeEach(() => localStorage.clear());

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

function hub() {
  return ((url: string) => {
    const path = new URL(String(url)).pathname;
    const json = (value: unknown) =>
      Promise.resolve(
        new Response(JSON.stringify(value), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    if (path.endsWith('/profiles'))
      return json({
        items: [{ id: '01J8QK3ZR2W7M5N4P6T8V9X0P1', slug: 'default', name: 'Default' }],
      });
    if (path.endsWith('/meta')) return json({ name: 'Core Hub', server_version: '0.0.0' });
    return json({ items: [], next_cursor: null });
  }) as unknown as typeof fetch;
}

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.pathname}</output>;
}

function mount(path = '/chat', role: 'owner' | 'member' = 'owner') {
  const store = new SessionStore(memoryStorage());
  store.save({
    profile: 'default',
    token: 't',
    refresh_token: null,
    expires_at: null,
    user: {
      id: '01J8QK3ZR2W7M5N4P6T8V9X0AA',
      username: 'noura',
      display_name: 'Noura',
      role,
    },
  });
  render(
    <App
      store={store}
      baseUrl="http://hub.test"
      fetchImpl={hub()}
      router={(children) => (
        <MemoryRouter initialEntries={[path]}>
          {children}
          <Where />
        </MemoryRouter>
      )}
    />,
  );
}

const sidebar = () => screen.getAllByRole('navigation', { name: 'Main menu' })[0]!;

const railIds = () =>
  within(within(sidebar()).getByTestId('rail'))
    .getAllByRole('link')
    .map((link) => link.getAttribute('data-nav-id'));
const tools = () => within(sidebar()).getByTestId('sidebar-group-tools');

describe('the remembered open and closed groups', () => {
  it('reads and writes the closed groups, and anything unreadable leaves them open', () => {
    const map = new Map<string, string>();
    const store = {
      getItem: (k: string) => map.get(k) ?? null,
      setItem: (k: string, v: string) => void map.set(k, v),
    };
    expect(readClosedGroups(store).size).toBe(0);
    writeClosedGroups(new Set(['tools']), store);
    expect(map.get(GROUPS_STORAGE)).toBe('["tools"]');
    expect([...readClosedGroups(store)]).toEqual(['tools']);
    map.set(GROUPS_STORAGE, '{not json');
    expect(readClosedGroups(store).size).toBe(0);
    map.set(GROUPS_STORAGE, '[1, "tools", null]');
    expect([...readClosedGroups(store)]).toEqual(['tools']);
    const blocked = {
      getItem: () => {
        throw new Error('SecurityError');
      },
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
    };
    expect(readClosedGroups(blocked).size).toBe(0);
    expect(() => writeClosedGroups(new Set(['tools']), blocked)).not.toThrow();
    expect(readClosedGroups(null).size).toBe(0);
  });
});

describe('Search beside the fold toggle, and the Tools group', () => {
  it('puts Search next to the fold toggle and Tools under New chat, in order', async () => {
    mount();
    const nav = sidebar();
    await within(nav).findByTestId('rail');
    const search = within(nav).getByTestId('brand-search');
    expect(search.getAttribute('href')).toBe('/search');
    expect(search.getAttribute('aria-label')).toBe('Search');
    // The same brand row as the fold toggle.
    expect(search.closest('.ch-sidebar-brand')).toBe(
      within(nav).getByTestId('sidebar-fold').closest('.ch-sidebar-brand'),
    );
    expect(railIds()).toEqual(['new_chat', 'agent_manager', 'tasks', 'workflows', 'schedules']);
    expect(tools().textContent).toContain('Tools');
    expect(tools().getAttribute('aria-expanded')).toBe('true');
  });

  it('closes and opens with a press, and remembers it on this device', async () => {
    const user = userEvent.setup();
    mount();
    await within(sidebar()).findByTestId('rail');
    await user.click(tools());
    expect(tools().getAttribute('aria-expanded')).toBe('false');
    expect(railIds()).toEqual(['new_chat']);
    expect(JSON.parse(localStorage.getItem(GROUPS_STORAGE) ?? '[]')).toEqual(['tools']);
    // The chats list is still there, with the room.
    expect(within(sidebar()).getByTestId('segments')).toBeTruthy();
    cleanup();
    mount();
    await within(sidebar()).findByTestId('rail');
    expect(tools().getAttribute('aria-expanded')).toBe('false');
    await user.click(tools());
    expect(tools().getAttribute('aria-expanded')).toBe('true');
    expect(railIds()).toEqual(['new_chat', 'agent_manager', 'tasks', 'workflows', 'schedules']);
    expect(JSON.parse(localStorage.getItem(GROUPS_STORAGE) ?? '[]')).toEqual([]);
  });

  it('closed while the page is inside it, the heading is marked as the place', async () => {
    localStorage.setItem(GROUPS_STORAGE, '["tools"]');
    mount('/tasks');
    await within(sidebar()).findByTestId('rail');
    expect(tools().className).toContain('active');
    expect(tools().getAttribute('aria-current')).toBe('true');
    cleanup();
    mount('/chat');
    await within(sidebar()).findByTestId('rail');
    expect(tools().className).not.toContain('active');
    expect(tools().getAttribute('aria-current')).toBeNull();
    cleanup();
    // Open, the page's own row is the marked one, not the heading.
    localStorage.clear();
    mount('/tasks');
    await within(sidebar()).findByTestId('rail');
    expect(tools().className).not.toContain('active');
  });

  it('a member sees Tools without Agents', async () => {
    mount('/chat', 'member');
    await within(sidebar()).findByTestId('rail');
    expect(railIds()).toEqual(['new_chat', 'tasks', 'workflows', 'schedules']);
  });

  it('Workflows is its own entry and opens its own page', async () => {
    const user = userEvent.setup();
    mount();
    await within(sidebar()).findByTestId('rail');
    const entry = within(within(sidebar()).getByTestId('rail')).getByRole('link', {
      name: 'Workflows',
    });
    expect(entry.getAttribute('href')).toBe('/workflows');
    await user.click(entry);
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/workflows'));
    expect(await screen.findByTestId('workflows-section')).toBeTruthy();
  });

  it('folded, Search is the row right below New chat', async () => {
    localStorage.setItem(FOLD_STORAGE, '1');
    mount();
    await within(sidebar()).findByTestId('rail');
    expect(within(sidebar()).queryByTestId('brand-search')).toBeNull();
    expect(railIds().slice(0, 2)).toEqual(['new_chat', 'search']);
    // The group is there as an icon, with its entries.
    expect(tools().getAttribute('aria-label') ?? tools().textContent).toContain('Tools');
  });
});
