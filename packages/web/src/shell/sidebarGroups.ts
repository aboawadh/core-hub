/**
 * Opening and closing a group of the sidebar's rail (owner, 2026-09-28, DECISIONS §126).
 *
 * «الأدوات» / "Tools" gathers Agents, Tasks, Workflows and Schedules under one heading; a press
 * on the heading closes it so the chats list below gets the room, and opens it again. Like the
 * fold, the choice is this device's own — a convenience, not an account setting — so it lives in
 * `localStorage`, read and written inside try/catch: a private window or blocked storage simply
 * starts with every group open, which is also the default.
 */
import { derived } from '@corehub/contracts';
import { useCallback, useState } from 'react';

export const GROUPS_STORAGE = `${derived.storagePrefix}sidebar-groups-closed`;

type Store = Pick<Storage, 'getItem' | 'setItem'>;

function defaultStore(): Store | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** The groups this device has closed; anything unreadable counts as none. */
export function readClosedGroups(store: Store | null = defaultStore()): Set<string> {
  try {
    const raw = store?.getItem(GROUPS_STORAGE);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return new Set(
      Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : [],
    );
  } catch {
    return new Set();
  }
}

export function writeClosedGroups(closed: ReadonlySet<string>, store = defaultStore()): void {
  try {
    store?.setItem(GROUPS_STORAGE, JSON.stringify([...closed].sort()));
  } catch {
    // fine: the choice then lasts until the page is reloaded
  }
}

/** Which groups are closed on this device, and the toggle the heading calls. */
export function useSidebarGroups(): { isOpen(id: string): boolean; toggle(id: string): void } {
  const [closed, setClosed] = useState(() => readClosedGroups());
  const toggle = useCallback((id: string) => {
    setClosed((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      writeClosedGroups(next);
      return next;
    });
  }, []);
  const isOpen = useCallback((id: string) => !closed.has(id), [closed]);
  return { isOpen, toggle };
}
