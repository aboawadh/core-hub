/**
 * A Hermes the person installed before Core Hub, of any age (the owner's question of
 * 2026-09-27): the card says when it is older than the hub is tested with, nothing is blocked,
 * and `agents.upgrade` runs Hermes's own updater — only where the Hermes is the person's own.
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  authed,
  drainJobs,
  emptyAdapterSet,
  signedInHub,
  type TestHub,
} from '../../../tests/unit/helpers.js';
import type { AgentProbe } from './adapters/types.js';
import { belowMinimum } from './serialize.js';
import type { HermesUpdater } from './service.js';

const hubs: TestHub[] = [];
afterEach(async () => {
  for (const hub of hubs.splice(0)) await hub.close();
});

function personsHermes(version: { current: string }): () => AgentProbe {
  return () => ({
    installed: true,
    source: 'user_cli',
    executablePath: '/Users/sam/.local/bin/hermes',
    version: version.current,
    runtime: { state: 'running', url: 'http://127.0.0.1:8642', error: null },
    error: null,
  });
}

function scriptedUpdater(options: { available: boolean; after?: () => void }) {
  const calls: string[] = [];
  const updater: HermesUpdater = {
    available: () => options.available,
    run: async (onLine) => {
      calls.push('update');
      onLine('→ Fetching updates…');
      options.after?.();
    },
    restart: async () => {
      calls.push('restart');
    },
  };
  return { updater, calls };
}

async function hermesOf(hub: TestHub & { token: string }) {
  const list = await authed(hub, hub.token, { method: 'GET', url: '/api/v1/agents' });
  expect(list.statusCode).toBe(200);
  const items = (
    list.json() as { items: Array<{ id: string; slug: string; install: Record<string, unknown> }> }
  ).items;
  return items.find((agent) => agent.slug === 'hermes')!;
}

describe('belowMinimum', () => {
  it('compares the release, ignoring build metadata, and never flags an unknown version', () => {
    expect(belowMinimum('0.20.9', '0.21.3')).toBe(true);
    expect(belowMinimum('0.21.3', '0.21.3')).toBe(false);
    expect(belowMinimum('0.21.5+3397.gd25bbd0', '0.21.3')).toBe(false);
    expect(belowMinimum('0.21.3+12.gabc', '0.21.3')).toBe(false);
    expect(belowMinimum(null, '0.21.3')).toBe(false);
  });
});

describe('a Hermes older than the hub is tested with, installed by the person', () => {
  it('is said on the card, and updated by its own updater when asked, then restarted and probed again', async () => {
    const version = { current: '0.20.1' };
    const { updater, calls } = scriptedUpdater({
      available: true,
      after: () => (version.current = '0.21.5'),
    });
    const hub = await signedInHub(
      {},
      { agents: { adapters: emptyAdapterSet(personsHermes(version)), hermesUpdate: updater } },
    );
    hubs.push(hub);
    const before = await hermesOf(hub);
    expect(before.install).toMatchObject({
      source: 'user_cli',
      version: '0.20.1',
      minimum_version: '0.21.3',
      below_minimum: true,
      self_update: true,
    });

    const started = await authed(hub, hub.token, {
      method: 'POST',
      url: `/api/v1/agents/${before.id}/update`,
    });
    expect(started.statusCode).toBe(202);
    await drainJobs(hub.app);
    expect(calls).toEqual(['update', 'restart']);
    const after = await hermesOf(hub);
    expect(after.install).toMatchObject({ version: '0.21.5', below_minimum: false });
  });

  it('is only said, never updated, where the Hermes is not the person’s own (the image)', async () => {
    const { updater, calls } = scriptedUpdater({ available: false });
    const hub = await signedInHub(
      {},
      {
        agents: {
          adapters: emptyAdapterSet(personsHermes({ current: '0.20.1' })),
          hermesUpdate: updater,
        },
      },
    );
    hubs.push(hub);
    const hermes = await hermesOf(hub);
    expect(hermes.install).toMatchObject({ below_minimum: true });
    expect(hermes.install.self_update).toBeUndefined();
    const refused = await authed(hub, hub.token, {
      method: 'POST',
      url: `/api/v1/agents/${hermes.id}/update`,
    });
    expect(refused.statusCode).toBeGreaterThanOrEqual(400);
    await drainJobs(hub.app);
    expect(calls).toEqual([]);
  });

  it('says nothing for a Hermes at or past the minimum', async () => {
    const hub = await signedInHub(
      {},
      { agents: { adapters: emptyAdapterSet(personsHermes({ current: '0.21.5+3397.gd25bbd0' })) } },
    );
    hubs.push(hub);
    expect((await hermesOf(hub)).install).toMatchObject({
      minimum_version: '0.21.3',
      below_minimum: false,
    });
  });
});
