/**
 * Notices, preferences and webhooks over HTTP.
 */
import { describe, expect, it } from 'vitest';
import {
  authed,
  drainJobs,
  expectModuleRegistered,
  signedInHub,
} from '../../../tests/unit/helpers.js';
import { notifyModule, overrideNotify, record } from './index.js';
import { requireSqlite } from '../../lib/db.js';

type Json = Record<string, unknown>;

const hook = {
  name: 'CI',
  url: 'https://example.com/hook',
  events: ['run.failed'],
  profiles: [],
  enabled: true,
  secret: null,
  include_content: false,
  allow_private_network: false,
  max_retries: 3,
};

const dns = async (host: string) =>
  host === 'example.com' ? ['93.184.216.34'] : host === 'inside.example' ? ['10.0.0.9'] : [];

describe('module: notify', () => {
  it('is composed by the app with routes and events registered', async () => {
    await expectModuleRegistered(notifyModule);
  });
});

describe('notify: the inbox', () => {
  it('is empty on a hub where nothing has happened, and does not invent a welcome', async () => {
    const hub = await signedInHub();
    try {
      const response = await authed(hub, hub.token, {
        method: 'GET',
        url: '/api/v1/notify/notices',
      });
      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({ items: [], next_cursor: null, unread_count: 0 });
    } finally {
      await hub.close();
    }
  });

  it('shows what another module wrote, and marks it read', async () => {
    const hub = await signedInHub();
    try {
      const workspace = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/profiles' })
      ).json() as { items: Array<{ id: string }> };
      const db = requireSqlite(hub.app.hub.database);
      record(db, {
        workspace: workspace.items[0]!.id,
        userId: hub.userId,
        kind: 'run_failed',
        title: 'A run failed',
        entityKind: 'session',
        entityId: '01J8QK3ZR2W7M5N4P6T8V9X0SE',
      });

      const listed = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/notices' })
      ).json() as { items: Json[] };
      expect(listed.items).toHaveLength(1);
      // The table's thirteen kinds map onto the contract's seven.
      expect(listed.items[0]).toMatchObject({
        kind: 'run_completed',
        title: 'A run failed',
        read_at: null,
        resource: { kind: 'session', id: '01J8QK3ZR2W7M5N4P6T8V9X0SE' },
      });

      const read = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/notify/notices/${listed.items[0]!.id as string}`,
        payload: { read: true },
      });
      expect((read.json() as Json).read_at).not.toBeNull();

      const unread = (
        await authed(hub, hub.token, {
          method: 'GET',
          url: '/api/v1/notify/notices?unread=true',
        })
      ).json() as { items: Json[] };
      expect(unread.items).toEqual([]);
    } finally {
      await hub.close();
    }
  });

  it('marks everything read at once and says how many', async () => {
    const hub = await signedInHub();
    try {
      const workspace = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/profiles' })
      ).json() as { items: Array<{ id: string }> };
      const db = requireSqlite(hub.app.hub.database);
      for (const title of ['one', 'two', 'three']) {
        record(db, {
          workspace: workspace.items[0]!.id,
          userId: hub.userId,
          kind: 'system',
          title,
        });
      }
      const response = await authed(hub, hub.token, {
        method: 'PATCH',
        url: '/api/v1/notify/notices',
        payload: { before: null },
      });
      expect(response.json()).toEqual({ updated: 3 });
    } finally {
      await hub.close();
    }
  });
});

describe('notify: preferences', () => {
  it('remembers the quiet window it was given, in the zone it was given', async () => {
    const hub = await signedInHub();
    try {
      const window = {
        enabled: true,
        from: '23:30',
        to: '06:15',
        timezone: 'Asia/Riyadh',
      };
      const saved = await authed(hub, hub.token, {
        method: 'PUT',
        url: '/api/v1/notify/preferences',
        payload: { events: {}, quiet_hours: window },
      });
      expect(saved.statusCode).toBe(200);
      expect((saved.json() as Json).quiet_hours).toEqual(window);
      // And on the way back out — a window that answers 22:00–07:00 whatever you typed
      // is not a setting, it is a decoration.
      const read = await authed(hub, hub.token, {
        method: 'GET',
        url: '/api/v1/notify/preferences',
      });
      expect((read.json() as Json).quiet_hours).toEqual(window);
    } finally {
      await hub.close();
    }
  });

  it('keeps a window that was switched off, because it will be switched back on', async () => {
    const hub = await signedInHub();
    try {
      await authed(hub, hub.token, {
        method: 'PUT',
        url: '/api/v1/notify/preferences',
        payload: {
          events: {},
          quiet_hours: { enabled: true, from: '23:00', to: '05:00', timezone: 'UTC' },
        },
      });
      await authed(hub, hub.token, {
        method: 'PUT',
        url: '/api/v1/notify/preferences',
        payload: {
          events: {},
          quiet_hours: { enabled: false, from: '23:00', to: '05:00', timezone: 'UTC' },
        },
      });
      const read = await authed(hub, hub.token, {
        method: 'GET',
        url: '/api/v1/notify/preferences',
      });
      expect((read.json() as Json).quiet_hours).toEqual({
        enabled: false,
        from: '23:00',
        to: '05:00',
        timezone: 'UTC',
      });
    } finally {
      await hub.close();
    }
  });

  it('stores only what was changed, because a missing kind means "on"', async () => {
    const hub = await signedInHub();
    try {
      const empty = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/preferences' })
      ).json() as Json;
      expect(empty.events).toEqual({});

      await authed(hub, hub.token, {
        method: 'PUT',
        url: '/api/v1/notify/preferences',
        payload: {
          events: { run_completed: { in_app: true, push: false } },
          quiet_hours: { enabled: false, from: '22:00', to: '07:00', timezone: 'UTC' },
        },
      });
      const after = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/preferences' })
      ).json() as Json;
      expect(after.events).toEqual({ run_completed: { in_app: true, push: false } });
    } finally {
      await hub.close();
    }
  });
});

describe('notify: webhooks', () => {
  it('refuses a URL that resolves somewhere private, before storing it', async () => {
    overrideNotify({ resolveHost: dns });
    const hub = await signedInHub();
    try {
      const response = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/notify/webhooks',
        payload: { ...hook, url: 'https://inside.example/hook' },
      });
      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ details: { reason: 'url_private' } });

      const listed = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/webhooks' })
      ).json() as { items: unknown[] };
      // Refused means not stored.
      expect(listed.items).toEqual([]);
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('allows the private one when the person said so on purpose', async () => {
    overrideNotify({ resolveHost: dns });
    const hub = await signedInHub();
    try {
      const response = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/notify/webhooks',
        payload: { ...hook, url: 'https://inside.example/hook', allow_private_network: true },
      });
      expect(response.statusCode).toBe(201);
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('never sends a signing secret back, and keeps it when the client echoes [stored]', async () => {
    overrideNotify({ resolveHost: dns });
    const hub = await signedInHub();
    try {
      const created = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/notify/webhooks',
        payload: { ...hook, secret: 'top-secret' },
      });
      expect(created.body).not.toContain('top-secret');
      expect((created.json() as Json).secret).toBe('[stored]');

      const id = (created.json() as Json).id as string;
      const kept = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/notify/webhooks/${id}`,
        payload: { secret: '[stored]', name: 'CI renamed' },
      });
      expect(kept.json()).toMatchObject({ name: 'CI renamed', secret: '[stored]' });

      const cleared = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/notify/webhooks/${id}`,
        payload: { secret: null },
      });
      expect((cleared.json() as Json).secret).toBeNull();
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('sends a signed test and records what came back', async () => {
    const calls: Array<{ url: string; headers: Record<string, string>; body: string }> = [];
    overrideNotify({
      resolveHost: dns,
      fetchImpl: (async (url: string, init: RequestInit) => {
        calls.push({
          url: String(url),
          headers: init.headers as Record<string, string>,
          body: String(init.body),
        });
        return new Response('ok', { status: 200 });
      }) as unknown as typeof fetch,
    });
    const hub = await signedInHub();
    try {
      const created = (
        await authed(hub, hub.token, {
          method: 'POST',
          url: '/api/v1/notify/webhooks',
          payload: { ...hook, secret: 'top-secret' },
        })
      ).json() as Json;

      const test = await authed(hub, hub.token, {
        method: 'POST',
        url: `/api/v1/notify/webhooks/${created.id as string}/test`,
      });
      expect(test.statusCode).toBe(202);
      // `JobAccepted`, as the contract says: the id a client follows.
      const accepted = test.json() as Json;
      expect(accepted).toEqual({ job_id: expect.stringMatching(/^[0-9A-Z]{26}$/) });
      await drainJobs(hub.app);

      const job = await authed(hub, hub.token, {
        method: 'GET',
        url: `/api/v1/jobs/${accepted.job_id as string}`,
      });
      expect(job.json()).toMatchObject({
        status: 'succeeded',
        result: { delivered: true, status: 200, error: null },
      });

      expect(calls).toHaveLength(1);
      // Signed, so the receiver can verify the body instead of trusting it.
      expect(calls[0]!.headers['x-corehub-signature']).toMatch(/^sha256=[0-9a-f]{64}$/);
      expect(JSON.parse(calls[0]!.body)).toMatchObject({ event: 'webhook.test' });

      const after = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/webhooks' })
      ).json() as { items: Json[] };
      expect((after.items[0]!.stats as Json).delivered).toBe(1);

      // The delivery itself is readable: what was sent and what the endpoint answered.
      const deliveries = await authed(hub, hub.token, {
        method: 'GET',
        url: `/api/v1/notify/webhooks/${created.id as string}/deliveries`,
      });
      expect(deliveries.statusCode).toBe(200);
      const items = (deliveries.json() as { items: Json[] }).items;
      expect(items).toHaveLength(1);
      expect(items[0]).toMatchObject({
        webhook_id: created.id,
        event: 'webhook.test',
        status: 'delivered',
        attempts: 1,
        response_status: 200,
        error: null,
      });
      expect(items[0]!.delivered_at).toEqual(expect.any(String));
      // The payload is not returned: it may carry message text.
      expect(items[0]).not.toHaveProperty('payload');
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('records a failure rather than pretending the endpoint answered', async () => {
    overrideNotify({
      resolveHost: dns,
      fetchImpl: (async () => {
        throw new Error('connection refused');
      }) as unknown as typeof fetch,
    });
    const hub = await signedInHub();
    try {
      const created = (
        await authed(hub, hub.token, {
          method: 'POST',
          url: '/api/v1/notify/webhooks',
          payload: hook,
        })
      ).json() as Json;
      await authed(hub, hub.token, {
        method: 'POST',
        url: `/api/v1/notify/webhooks/${created.id as string}/test`,
      });
      await drainJobs(hub.app);
      const after = (
        await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/webhooks' })
      ).json() as { items: Json[] };
      expect((after.items[0]!.stats as Json).failed).toBe(1);
      expect((after.items[0]!.stats as Json).last_error).toContain('connection refused');

      const items = (
        (
          await authed(hub, hub.token, {
            method: 'GET',
            url: `/api/v1/notify/webhooks/${created.id as string}/deliveries`,
          })
        ).json() as { items: Json[] }
      ).items;
      expect(items[0]).toMatchObject({
        status: 'failed',
        response_status: null,
        delivered_at: null,
      });
      expect(items[0]!.error).toContain('connection refused');
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('answers 404 for the deliveries of a webhook that does not exist', async () => {
    const hub = await signedInHub();
    try {
      const response = await authed(hub, hub.token, {
        method: 'GET',
        url: '/api/v1/notify/webhooks/01J8QK3ZR2W7M5N4P6T8V9X0ZZ/deliveries',
      });
      expect(response.statusCode).toBe(404);
    } finally {
      await hub.close();
    }
  });

  it('serves the event catalogue from the contract rather than a list in the code', async () => {
    const hub = await signedInHub();
    try {
      const response = await authed(hub, hub.token, {
        method: 'GET',
        url: '/api/v1/notify/webhook-events',
      });
      const names = (response.json() as { items: Array<{ name: string }> }).items.map(
        (i) => i.name,
      );
      expect(names).toContain('run.completed');
      expect(names.length).toBeGreaterThan(10);
    } finally {
      await hub.close();
    }
  });
});

// Decision §115 as amended for the compatibility rule (ADR 0027): the seven webhook operations
// declare `X-Hub-Profile` as optional. A client built for v1.1.2 sends no header and must be
// answered exactly as v1.1.2 answered it: from `default`, or from a `?profile=` value.
describe('notify: webhooks without X-Hub-Profile, as hubs before v1.1.3 answered them', () => {
  type Hub = Awaited<ReturnType<typeof signedInHub>>;
  /** A request with the token and nothing else: no `X-Hub-Profile`. */
  const bare = (
    hub: Hub,
    method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
    url: string,
    payload?: unknown,
    headers: Record<string, string> = {},
  ) =>
    hub.app.inject({
      method,
      url,
      ...(payload !== undefined ? { payload } : {}),
      headers: { authorization: `Bearer ${hub.token}`, ...headers },
    });
  const ids = (response: { json(): unknown }) =>
    (response.json() as { items: Json[] }).items.map((item) => item.id);

  async function withOtherProfile(hub: Hub) {
    const made = await bare(hub, 'POST', '/api/v1/profiles', { slug: 'other', name: 'Other' });
    expect(made.statusCode).toBe(201);
  }

  it('answers all seven operations without the header, in the default profile', async () => {
    overrideNotify({
      resolveHost: dns,
      fetchImpl: (async () => {
        throw new Error('connection refused');
      }) as unknown as typeof fetch,
    });
    const hub = await signedInHub();
    try {
      await withOtherProfile(hub);
      const created = await bare(hub, 'POST', '/api/v1/notify/webhooks', hook);
      expect(created.statusCode).toBe(201);
      const id = (created.json() as Json).id as string;

      // Stored in `default`: listed there with or without the header, not in another profile.
      expect(ids(await bare(hub, 'GET', '/api/v1/notify/webhooks'))).toEqual([id]);
      expect(
        ids(await authed(hub, hub.token, { method: 'GET', url: '/api/v1/notify/webhooks' })),
      ).toEqual([id]);
      expect(
        ids(
          await authed(hub, hub.token, {
            method: 'GET',
            url: '/api/v1/notify/webhooks',
            profile: 'other',
          }),
        ),
      ).toEqual([]);

      const patched = await bare(hub, 'PATCH', `/api/v1/notify/webhooks/${id}`, { name: 'CI 2' });
      expect(patched.statusCode).toBe(200);
      expect((patched.json() as Json).name).toBe('CI 2');

      const test = await bare(hub, 'POST', `/api/v1/notify/webhooks/${id}/test`);
      expect(test.statusCode).toBe(202);
      await drainJobs(hub.app);

      const deliveries = await bare(hub, 'GET', `/api/v1/notify/webhooks/${id}/deliveries`);
      expect(deliveries.statusCode).toBe(200);
      const delivery = (deliveries.json() as { items: Json[] }).items[0]!;
      expect(delivery).toMatchObject({ webhook_id: id, status: 'failed' });

      const again = await bare(
        hub,
        'POST',
        `/api/v1/notify/webhooks/${id}/deliveries/${delivery.id as string}/redeliver`,
      );
      expect(again.statusCode).toBe(202);

      expect((await bare(hub, 'DELETE', `/api/v1/notify/webhooks/${id}`)).statusCode).toBe(204);
      expect(ids(await bare(hub, 'GET', '/api/v1/notify/webhooks'))).toEqual([]);
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('uses the profile the header names, and only there', async () => {
    overrideNotify({ resolveHost: dns });
    const hub = await signedInHub();
    try {
      await withOtherProfile(hub);
      const other = { 'x-hub-profile': 'other' };
      const created = await bare(hub, 'POST', '/api/v1/notify/webhooks', hook, other);
      expect(created.statusCode).toBe(201);
      const id = (created.json() as Json).id as string;

      expect(ids(await bare(hub, 'GET', '/api/v1/notify/webhooks', undefined, other))).toEqual([
        id,
      ]);
      // Without the header it is `default`, where this webhook is not.
      expect(ids(await bare(hub, 'GET', '/api/v1/notify/webhooks'))).toEqual([]);
      expect((await bare(hub, 'DELETE', `/api/v1/notify/webhooks/${id}`)).statusCode).toBe(404);
      expect(
        (await bare(hub, 'DELETE', `/api/v1/notify/webhooks/${id}`, undefined, other)).statusCode,
      ).toBe(204);
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('reads a ?profile= value when the header is absent, as v1.1.2 did; the header wins', async () => {
    overrideNotify({ resolveHost: dns });
    const hub = await signedInHub();
    try {
      await withOtherProfile(hub);
      const created = await bare(hub, 'POST', '/api/v1/notify/webhooks?profile=other', hook);
      expect(created.statusCode).toBe(201);
      const id = (created.json() as Json).id as string;

      expect(ids(await bare(hub, 'GET', '/api/v1/notify/webhooks?profile=other'))).toEqual([id]);
      expect(
        ids(
          await authed(hub, hub.token, {
            method: 'GET',
            url: '/api/v1/notify/webhooks',
            profile: 'other',
          }),
        ),
      ).toEqual([id]);
      expect(ids(await bare(hub, 'GET', '/api/v1/notify/webhooks'))).toEqual([]);
      // A header that is sent is what counts.
      expect(
        ids(
          await bare(hub, 'GET', '/api/v1/notify/webhooks?profile=other', undefined, {
            'x-hub-profile': 'default',
          }),
        ),
      ).toEqual([]);
    } finally {
      overrideNotify({});
      await hub.close();
    }
  });

  it('answers 404 profile_not_found only for a profile that is named and does not exist', async () => {
    const hub = await signedInHub();
    try {
      expect((await bare(hub, 'GET', '/api/v1/notify/webhooks')).statusCode).toBe(200);
      const byHeader = await bare(hub, 'GET', '/api/v1/notify/webhooks', undefined, {
        'x-hub-profile': 'nowhere',
      });
      expect(byHeader.statusCode).toBe(404);
      expect(byHeader.json()).toMatchObject({ code: 'profile_not_found' });
      const byQuery = await bare(hub, 'GET', '/api/v1/notify/webhooks?profile=nowhere');
      expect(byQuery.statusCode).toBe(404);
      expect(byQuery.json()).toMatchObject({ code: 'profile_not_found' });
    } finally {
      await hub.close();
    }
  });
});
