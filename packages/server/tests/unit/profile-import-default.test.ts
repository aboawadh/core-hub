/**
 * An archive imported as the default profile (contract decision §116), through the hub's own
 * route, with a real Hermes home on disk (a temporary folder laid out the way Hermes lays out
 * its root) and everything else real: the jobs runner, the file registry, the models module.
 *
 * What must hold (the owner, 2026-09-27): the archive becomes the default, the old default is
 * kept whole as `default-backup` — then `-2`, `-3`, … for as long as people import, never an
 * error for a taken name — what Hermes shares across profiles stays at the root, the shared
 * providers stay, and any failure at any step leaves the original default exactly as it was
 * and says the import did not happen. Only an admin may do it.
 */
import { derived } from '@corehub/contracts';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterAll, afterEach, describe, expect, it } from 'vitest';
import { profileTransferPorts } from '../../src/modules/index.js';
import { registerProfileTransfer } from '../../src/modules/auth/index.js';
import { fakeProfileRuntime } from '../../src/modules/auth/testing/fake-profile-runtime.js';
import { tarGz, type TarEntry } from '../../src/modules/auth/testing/tar.js';
import { createDefaultProfileReplacement, type SwapFs } from '../../src/modules/agents/index.js';
import { authed, drainJobs, signedInHub, type TestHub } from './helpers.js';

const scratch = mkdtempSync(path.join(tmpdir(), 'corehub-import-default-'));
afterAll(() => rmSync(scratch, { recursive: true, force: true }));

type Hub = TestHub & { token: string; userId: string };
type Json = Record<string, unknown>;

let restore: ReturnType<typeof registerProfileTransfer> | undefined;
afterEach(() => {
  if (restore !== undefined) registerProfileTransfer(restore);
  restore = undefined;
});

/** A Hermes root home with an old default profile in it, a named profile and the shared board. */
function hermesHome(): string {
  const home = mkdtempSync(path.join(scratch, 'home-'));
  const put = (relative: string, content: string) => {
    const file = path.join(home, relative);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, content);
  };
  put('config.yaml', 'model:\n  default: old-model\n');
  put('SOUL.md', 'I am the old default.\n');
  put('.env', 'TELEGRAM_BOT_TOKEN=old-bot-token\n');
  put('memories/MEMORY.md', 'The old default remembers the harbour.\n');
  put('sessions/session-1.json', '{"id":"session-1"}\n');
  put('state.db', 'old sessions store');
  put('cron/jobs.json', '{"jobs":[]}\n');
  // What Hermes shares across profiles: never moved, never replaced.
  put('kanban.db', 'the shared task board');
  put('auth.json', '{"shared":"oauth"}\n');
  put('logs/gateway.log', 'root log\n');
  put('profiles/design/SOUL.md', 'I am the design profile.\n');
  return home;
}

/** Every file under `dir` with its content, for "exactly as it was". */
function snapshot(dir: string): Record<string, string> {
  const out: Record<string, string> = {};
  const walk = (at: string) => {
    for (const name of readdirSync(at).sort()) {
      const full = path.join(at, name);
      if (statSync(full).isDirectory()) {
        out[`${path.relative(dir, full)}/`] = '';
        walk(full);
      } else {
        out[path.relative(dir, full)] = readFileSync(full, 'utf8');
      }
    }
  };
  walk(dir);
  return out;
}

/** The archive of a profile from another program (a named profile's export: one folder). */
function archive(extra: TarEntry[] = []): Buffer {
  return tarGz([
    { path: 'migrated' },
    { path: 'migrated/SOUL.md', content: 'I came from the old program.\n' },
    { path: 'migrated/config.yaml', content: 'model:\n  default: imported-model\n' },
    { path: 'migrated/memories' },
    { path: 'migrated/memories/MEMORY.md', content: 'The imported one remembers the desert.\n' },
    // A name Hermes shares across profiles: it must not replace the hub's own board.
    { path: 'migrated/kanban.db', content: 'a board from elsewhere' },
    ...extra,
  ]);
}

/**
 * Hermes scripted (the archive calls, unused here) and a real replacement over `home`, with
 * nothing to hold down (no Hermes processes in a test). `fs` makes a move fail; `gate` keeps
 * the replacement waiting inside the hold.
 */
function withHome(home: string, options: { fs?: SwapFs; gate?: Promise<void> } = {}) {
  const fake = fakeProfileRuntime();
  restore = registerProfileTransfer((app) =>
    profileTransferPorts(
      app,
      fake.runtime,
      createDefaultProfileReplacement({
        home,
        hold: async (work) => {
          if (options.gate) await options.gate;
          return work();
        },
        ...(options.fs ? { fs: options.fs } : {}),
        log: app.log,
      }),
    ),
  );
}

async function json(
  hub: Hub,
  method: 'GET' | 'POST',
  url: string,
  payload?: unknown,
  token?: string,
) {
  const res = await authed(hub, token ?? hub.token, {
    method,
    url,
    ...(payload ? { payload } : {}),
  });
  return { status: res.statusCode, body: (res.body ? res.json() : null) as Json };
}

async function upload(hub: Hub, name: string, body: Buffer): Promise<string> {
  const boundary = '----corehubDefaultBoundary';
  const payload = Buffer.concat([
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${name}"\r\n` +
        'Content-Type: application/gzip\r\n\r\n',
    ),
    body,
    Buffer.from(
      `\r\n--${boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nimport\r\n--${boundary}--\r\n`,
    ),
  ]);
  const res = await authed(hub, hub.token, {
    method: 'POST',
    url: '/api/v1/attachments',
    payload,
    headers: { 'content-type': `multipart/form-data; boundary=${boundary}` },
  });
  expect(res.statusCode, res.body).toBe(201);
  return (res.json() as { id: string }).id;
}

async function replaceDefault(hub: Hub, bytes: Buffer, name?: string): Promise<Json> {
  const started = await json(hub, 'POST', '/api/v1/profile-imports', {
    attachment_id: await upload(hub, 'migrated.tar.gz', bytes),
    slug: 'unused-slug',
    replace_default: true,
    ...(name ? { name } : {}),
  });
  expect(started.status, JSON.stringify(started.body)).toBe(202);
  await drainJobs(hub.app);
  const done = await json(hub, 'GET', `/api/v1/jobs/${started.body.job_id as string}`);
  expect(done.status).toBe(200);
  return done.body;
}

async function profiles(hub: Hub): Promise<Json[]> {
  return (await json(hub, 'GET', '/api/v1/profiles')).body.items as Json[];
}

/** A hub whose provider refreshes find nothing (no network in a test), read in English. */
async function quietHub(): Promise<Hub> {
  const fetchImpl = (() =>
    Promise.resolve(
      new Response(JSON.stringify({ data: [] }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    )) as unknown as typeof fetch;
  const hub = await signedInHub({}, { models: { fetchImpl } });
  const res = await authed(hub, hub.token, {
    method: 'PATCH',
    url: '/api/v1/auth/me',
    payload: { locale: 'en' },
  });
  expect(res.statusCode, res.body).toBe(200);
  return hub;
}

const read = (home: string, relative: string) => readFileSync(path.join(home, relative), 'utf8');

describe('an archive imported as the default profile', () => {
  it('becomes the default; the old default is kept whole as default-backup; shared things stay', async () => {
    const home = hermesHome();
    withHome(home);
    const hub = await quietHub();
    try {
      // A shared provider, stored under the default profile (§37).
      const provider = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/models/providers',
        payload: {
          preset: 'openai',
          label: 'OpenAI',
          kind: 'llm',
          api_key: 'sk-shared-0123456789abcdef',
          scope: 'all',
        },
      });
      expect(provider.statusCode, provider.body).toBe(201);
      const before = (await profiles(hub)).find((p) => p.slug === 'default')!;

      const done = await replaceDefault(hub, archive(), 'المنقول');
      expect(done.status, JSON.stringify(done.error)).toBe('succeeded');
      const result = done.result as Json;
      expect(result).toMatchObject({
        profile_id: before.id,
        replaced_default: true,
        backup: { slug: 'default-backup', name: 'default-backup' },
        skipped: ['kanban.db'],
      });

      // The root is the archive now …
      expect(read(home, 'SOUL.md')).toBe('I came from the old program.\n');
      expect(read(home, 'memories/MEMORY.md')).toBe('The imported one remembers the desert.\n');
      expect(existsSync(path.join(home, 'sessions'))).toBe(false);
      expect(existsSync(path.join(home, '.env'))).toBe(false);
      // … what Hermes shares across profiles stayed, untouched by the archive's own board …
      expect(read(home, 'kanban.db')).toBe('the shared task board');
      expect(read(home, 'auth.json')).toBe('{"shared":"oauth"}\n');
      expect(read(home, 'logs/gateway.log')).toBe('root log\n');
      expect(read(home, 'profiles/design/SOUL.md')).toBe('I am the design profile.\n');
      // … and the old default is whole in the backup: its files, sessions, schedules, channel.
      const backup = path.join(home, 'profiles', 'default-backup');
      expect(read(backup, 'SOUL.md')).toBe('I am the old default.\n');
      expect(read(backup, 'memories/MEMORY.md')).toBe('The old default remembers the harbour.\n');
      expect(read(backup, 'sessions/session-1.json')).toBe('{"id":"session-1"}\n');
      expect(read(backup, 'state.db')).toBe('old sessions store');
      expect(read(backup, 'cron/jobs.json')).toBe('{"jobs":[]}\n');
      expect(read(backup, '.env')).toContain('TELEGRAM_BOT_TOKEN=old-bot-token');
      expect(existsSync(path.join(backup, 'kanban.db'))).toBe(false);
      // No working folder is left behind.
      expect(readdirSync(path.join(home, 'profiles')).sort()).toEqual(['default-backup', 'design']);

      // The default workspace kept its id and is still the default, under its new name; the
      // backup is a profile of its own.
      const after = await profiles(hub);
      expect(after.find((p) => p.slug === 'default')).toMatchObject({
        id: before.id,
        name: 'المنقول',
      });
      expect(after.find((p) => p.slug === 'default-backup')).toMatchObject({
        id: (result.backup as Json).profile_id,
      });
      // The shared provider is still shared, key and all, in the default and in the backup.
      for (const profile of ['default', 'default-backup']) {
        const listed = await authed(hub, hub.token, {
          method: 'GET',
          url: '/api/v1/models/providers?kind=llm',
          profile,
        });
        const items = (listed.json() as { items: Json[] }).items;
        expect(items.find((item) => item.id === (provider.json() as Json).id)).toMatchObject({
          scope: 'all',
          api_key: '[stored]',
        });
      }
    } finally {
      await hub.close();
    }
  });

  it('never stops at a taken name: default-backup, -2, -3, … over repeated imports', async () => {
    const home = hermesHome();
    withHome(home);
    const hub = await quietHub();
    try {
      // A profile the hub already calls `default-backup-3` (made by a person), and a Hermes
      // tombstone for `default-backup-4` (deleted in Hermes): both are skipped.
      const made = await json(hub, 'POST', '/api/v1/profiles', {
        slug: 'default-backup-3',
        name: 'default-backup-3',
      });
      expect(made.status, JSON.stringify(made.body)).toBe(201);
      mkdirSync(path.join(home, 'profiles', '.deleted', 'default-backup-4'), { recursive: true });

      const names: string[] = [];
      for (let round = 1; round <= 4; round += 1) {
        const done = await replaceDefault(
          hub,
          archive([{ path: 'migrated/round.txt', content: `round ${round}\n` }]),
        );
        expect(done.status, JSON.stringify(done.error)).toBe('succeeded');
        names.push(((done.result as Json).backup as Json).slug as string);
      }
      expect(names).toEqual([
        'default-backup',
        'default-backup-2',
        'default-backup-5',
        'default-backup-6',
      ]);
      // Each backup holds the default it replaced: the first the original, then each round's.
      expect(read(home, 'profiles/default-backup/SOUL.md')).toBe('I am the old default.\n');
      expect(read(home, 'profiles/default-backup-2/round.txt')).toBe('round 1\n');
      expect(read(home, 'profiles/default-backup-5/round.txt')).toBe('round 2\n');
      expect(read(home, 'profiles/default-backup-6/round.txt')).toBe('round 3\n');
      expect(read(home, 'round.txt')).toBe('round 4\n');
      const slugs = (await profiles(hub)).map((p) => p.slug);
      for (const name of names) expect(slugs).toContain(name);
    } finally {
      await hub.close();
    }
  });

  it('puts the original default back exactly as it was when a move fails half-way', async () => {
    const home = hermesHome();
    const before = snapshot(home);
    let renames = 0;
    // The fifth move fails: some of the old default has moved out by then.
    const fs: SwapFs = {
      rename(from, to) {
        renames += 1;
        if (renames === 5) throw new Error('EIO: the disk said no');
        renameSync(from, to);
      },
    };
    withHome(home, { fs });
    const hub = await quietHub();
    try {
      const defaultBefore = (await profiles(hub)).find((p) => p.slug === 'default')!;
      const done = await replaceDefault(hub, archive(), 'never applied');
      expect(done.status).toBe('failed');
      const error = done.error as Json;
      expect(error.error).toMatch(
        /^The import did not happen and your default profile is as it was: /,
      );
      expect(error.error).toContain('EIO: the disk said no');
      expect(renames).toBeGreaterThan(5); // the moves made were replayed backwards
      expect(snapshot(home)).toEqual(before);
      const after = await profiles(hub);
      expect(after.find((p) => p.slug?.toString().startsWith('default-backup'))).toBeUndefined();
      expect(after.find((p) => p.slug === 'default')).toMatchObject({
        id: defaultBefore.id,
        name: defaultBefore.name,
      });
    } finally {
      await hub.close();
    }
  });

  it("rolls the files and the rows back when the hub's own step fails after the move", async () => {
    const home = hermesHome();
    const before = snapshot(home);
    withHome(home);
    const hub = await quietHub();
    try {
      const defaultBefore = (await profiles(hub)).find((p) => p.slug === 'default')!;
      // A providers file that is JSON, but not a list this hub writes: the rows fail after the
      // files have moved and the backup's row was written.
      const done = await replaceDefault(
        hub,
        archive([
          {
            path: `migrated/${derived.providersFile}`,
            content: JSON.stringify({ format: 'something-else', version: 1, providers: [] }),
          },
        ]),
        'never applied',
      );
      expect(done.status).toBe('failed');
      expect((done.error as Json).error).toMatch(/^The import did not happen/);
      expect(snapshot(home)).toEqual(before);
      const after = await profiles(hub);
      expect(after.find((p) => String(p.slug).startsWith('default-backup'))).toBeUndefined();
      expect(after.find((p) => p.slug === 'default')).toMatchObject({
        id: defaultBefore.id,
        name: defaultBefore.name,
      });
    } finally {
      await hub.close();
    }
  });

  it('changes nothing when the file is not a profile archive, and says so in Arabic too', async () => {
    const home = hermesHome();
    const before = snapshot(home);
    withHome(home);
    const hub = await quietHub();
    try {
      await authed(hub, hub.token, {
        method: 'PATCH',
        url: '/api/v1/auth/me',
        payload: { locale: 'ar' },
      });
      const done = await replaceDefault(
        hub,
        tarGz([
          { path: 'one', content: 'x' },
          { path: 'two', content: 'y' },
        ]),
      );
      expect(done.status).toBe('failed');
      expect((done.error as Json).error).toMatch(
        /^لم يحدث الاستيراد، وبروفايلك الافتراضي كما كان:/,
      );
      expect(snapshot(home)).toEqual(before);
    } finally {
      await hub.close();
    }
  });

  it('is refused to a member, and while another replacement is still running', async () => {
    const home = hermesHome();
    let open: () => void = () => {};
    const gate = new Promise<void>((resolve) => (open = resolve));
    withHome(home, { gate });
    const hub = await quietHub();
    try {
      const password = 'member-password-1';
      const user = await json(hub, 'POST', '/api/v1/auth/users', {
        username: 'visitor',
        password,
        role: 'member',
        profiles: ['default'],
      });
      expect(user.status, JSON.stringify(user.body)).toBe(201);
      const login = await hub.app.inject({
        method: 'POST',
        url: '/api/v1/auth/login',
        payload: { username: 'visitor', password },
      });
      const memberToken = (login.json() as { access_token: string }).access_token;
      const attachment = await upload(hub, 'migrated.tar.gz', archive());
      const refused = await json(
        hub,
        'POST',
        '/api/v1/profile-imports',
        { attachment_id: attachment, slug: 'unused-slug', replace_default: true },
        memberToken,
      );
      expect(refused.status).toBe(403);

      const first = await json(hub, 'POST', '/api/v1/profile-imports', {
        attachment_id: attachment,
        slug: 'unused-slug',
        replace_default: true,
      });
      expect(first.status).toBe(202);
      const second = await json(hub, 'POST', '/api/v1/profile-imports', {
        attachment_id: await upload(hub, 'migrated.tar.gz', archive()),
        slug: 'unused-slug',
        replace_default: true,
      });
      expect(second.status).toBe(409);
      expect(second.body.code).toBe('conflict');
      open();
      await drainJobs(hub.app);
      const done = await json(hub, 'GET', `/api/v1/jobs/${first.body.job_id as string}`);
      expect(done.body.status).toBe('succeeded');
      // The slot is free again.
      const third = await replaceDefault(hub, archive());
      expect(third.status).toBe('succeeded');
    } finally {
      open();
      await hub.close();
    }
  });

  it('is refused by name where the hub does not run Hermes itself', async () => {
    const fake = fakeProfileRuntime();
    restore = registerProfileTransfer((app) => profileTransferPorts(app, fake.runtime, null));
    const hub = await quietHub();
    try {
      const res = await json(hub, 'POST', '/api/v1/profile-imports', {
        attachment_id: await upload(hub, 'migrated.tar.gz', archive()),
        slug: 'unused-slug',
        replace_default: true,
      });
      expect(res.status).toBe(409);
      expect(res.body).toMatchObject({
        code: 'state_invalid',
        details: { reason: 'hermes_not_supervised' },
      });
    } finally {
      await hub.close();
    }
  });
});
