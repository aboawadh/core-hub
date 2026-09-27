// pnpm scripts:test — the migration guard (scripts/migrations-guard.mjs, ADR 0027): every kind of
// destructive migration is caught, Drizzle's lossless table copy is not, and an approval by the
// owner lets exactly the approved break through.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, it, mock } from 'node:test';

import { compareMigrations, run, scanMigration, snapshotColumns } from './migrations-guard.mjs';
import { loadApprovals } from './compat-base.mjs';

// The release had `notes(id, title, body)` and `tags(id, name)`.
const SNAPSHOT = JSON.stringify({
  tables: {
    notes: { name: 'notes', columns: { id: {}, title: {}, body: {} } },
    tags: { name: 'tags', columns: { id: {}, name: {} } },
  },
});
const released = snapshotColumns(SNAPSHOT);
const scan = (sql) => scanMigration('0033_x.sql', sql, released).map((b) => b.id);

// Drizzle's SQLite table copy, keeping `columns` of `notes`.
const copy = (columns) => `PRAGMA foreign_keys=OFF;--> statement-breakpoint
CREATE TABLE \`__new_notes\` (
	\`id\` text PRIMARY KEY NOT NULL,
	\`title\` text NOT NULL
);
--> statement-breakpoint
INSERT INTO \`__new_notes\`(${columns.map((c) => `"${c}"`).join(', ')}) SELECT ${columns
  .map((c) => `"${c}"`)
  .join(', ')} FROM \`notes\`;--> statement-breakpoint
DROP TABLE \`notes\`;--> statement-breakpoint
ALTER TABLE \`__new_notes\` RENAME TO \`notes\`;--> statement-breakpoint
PRAGMA foreign_keys=ON;`;

describe('migration guard: what breaks', () => {
  const cases = [
    ['a dropped table', 'DROP TABLE `tags`;', 'migration drops-table 0033_x.sql tags'],
    [
      'DROP TABLE IF EXISTS',
      'DROP TABLE IF EXISTS "tags";',
      'migration drops-table 0033_x.sql tags',
    ],
    [
      'a dropped column',
      'ALTER TABLE `notes` DROP COLUMN `body`;',
      'migration drops-column 0033_x.sql notes.body',
    ],
    [
      'a dropped column (PostgreSQL, schema-qualified)',
      'ALTER TABLE "public"."notes" DROP COLUMN IF EXISTS "body";',
      'migration drops-column 0033_x.sql notes.body',
    ],
    [
      'a renamed table',
      'ALTER TABLE `tags` RENAME TO `labels`;',
      'migration renames-table 0033_x.sql tags',
    ],
    [
      'a renamed column',
      'ALTER TABLE `notes` RENAME COLUMN `title` TO `name`;',
      'migration renames-column 0033_x.sql notes.title',
    ],
    ['an emptied table', 'DELETE FROM `tags`;', 'migration empties-table 0033_x.sql tags'],
    ['a truncated table', 'TRUNCATE TABLE "tags";', 'migration empties-table 0033_x.sql tags'],
    [
      'a table copy that leaves a column out',
      copy(['id', 'title']),
      'migration drops-column 0033_x.sql notes.body',
    ],
    [
      'a statement after a comment',
      '-- tidy up\nDROP TABLE `tags`;',
      'migration drops-table 0033_x.sql tags',
    ],
  ];
  for (const [name, sql, id] of cases) {
    it(`catches ${name}`, () => {
      assert.ok(scan(sql).includes(id), `${id} not in ${JSON.stringify(scan(sql))}`);
    });
  }

  it('catches a released migration that was edited or deleted', () => {
    const files = {
      'packages/server/drizzle/0001_a.sql': 'CREATE TABLE a (id text);',
      'packages/server/drizzle/0002_b.sql': 'CREATE TABLE b (id text);',
    };
    const ids = compareMigrations({
      baseFiles: Object.keys(files),
      files: ['packages/server/drizzle/0001_a.sql'],
      readBase: (f) => files[f],
      read: () => 'CREATE TABLE a (id text, extra text);',
    }).map((b) => b.id);
    assert.deepEqual(ids, ['migration edited 0001_a.sql', 'migration removed 0002_b.sql']);
  });

  it('assumes every table is real when the release has no snapshot', () => {
    const ids = scanMigration('0001_x.sql', 'DROP TABLE `anything`;', snapshotColumns(null));
    assert.equal(ids.length, 1);
  });
});

describe('migration guard: what passes', () => {
  const passes = [
    ['a new table', 'CREATE TABLE `labels` (`id` text PRIMARY KEY NOT NULL);'],
    ['a new column', 'ALTER TABLE `notes` ADD `pinned` integer DEFAULT false NOT NULL;'],
    [
      'a dropped or rebuilt index',
      'DROP INDEX `notes_title_idx`;\nCREATE INDEX `notes_title_idx` ON `notes` (`title`);',
    ],
    ['a table copy that keeps every column', copy(['id', 'title', 'body'])],
    ['a table copy that adds a column', copy(['id', 'title', 'body', 'pinned'])],
    ['a scratch table of the migration', 'DROP TABLE `__keep_rows`;'],
    [
      'a table made after the release',
      'DROP TABLE `drafts`;\nALTER TABLE `drafts2` RENAME TO `drafts3`;',
    ],
    ['a column made after the release', 'ALTER TABLE `notes` DROP COLUMN `pinned`;'],
    ['a DELETE with a WHERE', "DELETE FROM `tags` WHERE `name` = '';"],
    ['a dropped constraint', 'ALTER TABLE "notes" DROP CONSTRAINT "notes_title_check";'],
    ['DROP in a comment', '-- DROP TABLE `tags`;\nSELECT 1;'],
  ];
  for (const [name, sql] of passes) {
    it(`passes ${name}`, () => {
      assert.deepEqual(scan(sql), []);
    });
  }

  it('finds exactly the destructive migrations already in the repository', () => {
    // The table copies in 0005, 0011 and 0021 keep every column. What is left are the drops made
    // before this rule existed: 0001 recreated three tables no hub had written to, 0022 and 0024
    // dropped two tables on purpose. Each of them would now need the owner's approval.
    const root = path.resolve(import.meta.dirname, '..');
    const files = execFileSync('git', ['ls-files', 'packages/server/drizzle'], {
      cwd: root,
      encoding: 'utf8',
    })
      .split('\n')
      .filter(Boolean);
    const snapshots = files.filter((f) => f.endsWith('_snapshot.json')).sort();
    const read = (f) => execFileSync('git', ['show', `HEAD:${f}`], { cwd: root, encoding: 'utf8' });
    const found = [];
    const sql = files.filter((f) => f.endsWith('.sql')).sort();
    for (const [i, file] of sql.entries()) {
      if (i === 0) continue;
      const prev = snapshots.find((s) =>
        s.endsWith(`/${String(i - 1).padStart(4, '0')}_snapshot.json`),
      );
      found.push(
        ...scanMigration(
          path.basename(file),
          read(file),
          snapshotColumns(prev ? read(prev) : null),
        ),
      );
    }
    assert.deepEqual(
      found.map((b) => b.id),
      [
        'migration drops-table 0001_zippy_sir_ram.sql model_defaults',
        'migration drops-table 0001_zippy_sir_ram.sql models',
        'migration drops-table 0001_zippy_sir_ram.sql providers',
        'migration drops-table 0022_device_requests.sql device_commands',
        'migration drops-table 0024_drop_performance_snapshots.sql performance_snapshots',
      ],
    );
  });
});

describe('migration guard: base tag and approvals (a real git repository)', () => {
  let root;
  let errors;
  const git = (...args) =>
    execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', ...args], {
      cwd: root,
      stdio: 'pipe',
    });
  const write = (file, text) => {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), text);
  };
  const approvals = (entries) =>
    write('docs/contracts/breaking-approved.json', JSON.stringify({ approvals: entries }));
  const approval = {
    id: 'migration drops-table 0001_drop.sql tags',
    base: 'v1.0.0',
    decision: 'ADR 0027',
    approved_by: 'twuijri',
    reason: 'test',
  };

  beforeEach(() => {
    root = mkdtempSync(path.join(tmpdir(), 'migrations-guard-'));
    git('init', '-q');
    write('packages/server/drizzle/0000_init.sql', 'CREATE TABLE `tags` (`id` text);');
    write('packages/server/drizzle/meta/0000_snapshot.json', SNAPSHOT);
    write('docs/adr/0027-compatibility-no-breaking-changes.md', '# ADR 0027\n');
    git('add', '-A');
    git('commit', '-qm', 'release');
    git('tag', 'v1.0.0');
    errors = [];
    mock.method(console, 'log', () => {});
    mock.method(console, 'error', (...a) => errors.push(a.join(' ')));
  });
  afterEach(() => {
    mock.restoreAll();
    rmSync(root, { recursive: true, force: true });
  });

  it('passes an additive migration and fails a destructive one', () => {
    write('packages/server/drizzle/0001_add.sql', 'ALTER TABLE `tags` ADD `color` text;');
    assert.equal(run(root, 'v1.0.0'), 0);
    write('packages/server/drizzle/0001_drop.sql', 'DROP TABLE `tags`;');
    assert.equal(run(root, 'v1.0.0'), 1);
    assert.match(errors.join('\n'), /migration drops-table 0001_drop\.sql tags/);
  });

  it('fails an edited released migration', () => {
    write('packages/server/drizzle/0000_init.sql', 'CREATE TABLE `tags` (`id` text, `x` text);');
    assert.equal(run(root, 'v1.0.0'), 1);
    assert.match(errors.join('\n'), /migration edited 0000_init\.sql/);
  });

  it('passes a destructive migration the owner approved for this release, and only then', () => {
    write('packages/server/drizzle/0001_drop.sql', 'DROP TABLE `tags`;');
    approvals([approval]);
    assert.equal(run(root, 'v1.0.0'), 0);
    approvals([{ ...approval, base: 'v0.9.0' }]);
    assert.equal(run(root, 'v1.0.0'), 1);
    approvals([{ ...approval, approved_by: 'agent' }]);
    assert.equal(run(root, 'v1.0.0'), 1);
    approvals([{ ...approval, decision: 'owner said so' }]);
    assert.equal(run(root, 'v1.0.0'), 1);
  });

  it('reports a malformed approvals file', () => {
    write('docs/contracts/breaking-approved.json', '{ nope');
    assert.equal(loadApprovals(root).problems.length, 1);
    approvals([{ id: 'x' }]);
    assert.match(loadApprovals(root).problems[0], /missing base, decision, approved_by, reason/);
  });
});
