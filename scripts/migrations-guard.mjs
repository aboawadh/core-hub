#!/usr/bin/env node
// The migration guard (ADR 0027): an upgrade is "replace the image"; the hub then applies every
// migration it has not applied yet. So a migration must never lose data or rename what the
// previous release reads, and a released migration must never change. Compared with the latest
// release tag `v*`:
//
//   * a migration file the release shipped was edited or deleted          → break
//   * a new migration drops a table, drops a column, renames a table or a column,
//     empties a table (TRUNCATE, DELETE without WHERE)                     → break
//
// SQLite cannot alter a column in place, so Drizzle copies the table: CREATE `__new_x`,
// INSERT INTO `__new_x`(cols) SELECT … FROM `x`, DROP `x`, RENAME `__new_x` TO `x`. That copy is
// allowed when it keeps every column `x` had in the release (its last snapshot); tables whose
// name starts with `__` are scratch tables of the migration itself.
//
//   node scripts/migrations-guard.mjs [--base <ref>] [--root <repo>]
//
// A break the owner approved is listed by its id in docs/contracts/breaking-approved.json.
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  argValue,
  listAt,
  listHere,
  loadApprovals,
  report,
  resolveBase,
  showAt,
} from './compat-base.mjs';

export const PREFIX = 'migration ';
export const MIGRATIONS = 'packages/server/drizzle';

const ident = String.raw`(?:[\`"]?[A-Za-z0-9_]+[\`"]?\.)?[\`"\[]?([A-Za-z0-9_]+)[\`"\]]?`;
const RE = {
  dropTable: new RegExp(String.raw`^DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?${ident}`, 'i'),
  dropColumn: new RegExp(
    String.raw`^ALTER\s+TABLE\s+(?:ONLY\s+)?${ident}\s+DROP\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?${ident}`,
    'i',
  ),
  renameTable: new RegExp(String.raw`^ALTER\s+TABLE\s+${ident}\s+RENAME\s+TO\s+${ident}`, 'i'),
  renameColumn: new RegExp(
    String.raw`^ALTER\s+TABLE\s+${ident}\s+RENAME\s+(?:COLUMN\s+)?${ident}\s+TO\s+${ident}`,
    'i',
  ),
  truncate: new RegExp(String.raw`^TRUNCATE\s+(?:TABLE\s+)?${ident}`, 'i'),
  deleteAll: new RegExp(String.raw`^DELETE\s+FROM\s+${ident}\s*$`, 'i'),
  insertCopy: new RegExp(
    String.raw`^INSERT\s+INTO\s+${ident}\s*\(([^)]*)\)\s*SELECT\b[\s\S]*\bFROM\s+${ident}\s*$`,
    'i',
  ),
};

/** SQL statements without comments, whitespace collapsed. */
export function statements(sql) {
  const noComments = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, ' ');
  return noComments
    .split(';')
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter(Boolean);
}

const scratch = (name) => name.startsWith('__');
const unquote = (c) => c.trim().replace(/^[`"[]|[`"\]]$/g, '');

/**
 * Breaks in one new migration. `released(table)` returns the table's columns in the latest
 * release (its last Drizzle snapshot), or null when the release had no such table; when the
 * release has no snapshot at all (`released.unknown`), every table is assumed to be real.
 */
export function scanMigration(file, sql, released) {
  const out = [];
  const add = (kind, what, message) =>
    out.push({ id: `${PREFIX}${kind} ${file} ${what}`, kind, loc: `${file} ${what}`, message });
  const shipped = (table) => released.unknown || released(table) !== null;
  const shippedColumn = (table, col) => released.unknown || (released(table) ?? []).includes(col);
  const stmts = statements(sql);
  // Drizzle's table copy: which `__new_x` is renamed to `x`, and which columns it was filled with.
  const renamedInto = new Map();
  const copied = new Map();
  for (const s of stmts) {
    const r = RE.renameTable.exec(s);
    if (r && scratch(r[1])) renamedInto.set(r[2], r[1]);
    const c = RE.insertCopy.exec(s);
    if (c && scratch(c[1])) copied.set(c[1], { from: c[3], columns: c[2].split(',').map(unquote) });
  }
  for (const s of stmts) {
    let m;
    if ((m = RE.dropTable.exec(s))) {
      const table = m[1];
      if (scratch(table) || !shipped(table)) continue;
      const copy = renamedInto.has(table) ? copied.get(renamedInto.get(table)) : null;
      if (copy && copy.from === table) {
        const lost = (released(table) ?? []).filter((col) => !copy.columns.includes(col));
        for (const col of lost)
          add(
            'drops-column',
            `${table}.${col}`,
            `the copy of ${table} leaves out ${col}; its data is lost`,
          );
        continue;
      }
      add('drops-table', table, `drops ${table} and every row in it`);
    } else if ((m = RE.renameColumn.exec(s))) {
      if (!shippedColumn(m[1], m[2])) continue;
      add(
        'renames-column',
        `${m[1]}.${m[2]}`,
        `renames ${m[1]}.${m[2]} to ${m[3]}; the previous release reads ${m[2]}`,
      );
    } else if ((m = RE.renameTable.exec(s))) {
      if (scratch(m[1]) || !shipped(m[1])) continue;
      add('renames-table', m[1], `renames ${m[1]} to ${m[2]}; the previous release reads ${m[1]}`);
    } else if ((m = RE.dropColumn.exec(s))) {
      if (/^(CONSTRAINT|INDEX)$/i.test(m[2]) || !shippedColumn(m[1], m[2])) continue;
      add('drops-column', `${m[1]}.${m[2]}`, `drops ${m[1]}.${m[2]} and its data`);
    } else if ((m = RE.truncate.exec(s) ?? RE.deleteAll.exec(s))) {
      if (!shipped(m[1])) continue;
      add('empties-table', m[1], `deletes every row of ${m[1]}`);
    }
  }
  return out;
}

/** Columns per table from a Drizzle snapshot, as a lookup; `unknown` when there is no snapshot. */
export function snapshotColumns(snapshotText) {
  if (!snapshotText) {
    const f = () => null;
    f.unknown = true;
    return f;
  }
  const tables = JSON.parse(snapshotText).tables ?? {};
  const f = (table) => {
    const t = tables[table] ?? Object.values(tables).find((x) => x?.name === table);
    return t ? Object.keys(t.columns ?? {}) : null;
  };
  f.unknown = false;
  return f;
}

const normalise = (text) =>
  text
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((l) => l.trimEnd())
    .join('\n')
    .trim();

/**
 * Breaks between the migrations at `base` and the working tree. `read(file)` and `readBase(file)`
 * return file text or null; `baseFiles` and `files` list repository-relative paths.
 */
export function compareMigrations({ baseFiles, files, readBase, read }) {
  const isSql = (f) => f.startsWith(`${MIGRATIONS}/`) && f.endsWith('.sql');
  const before = baseFiles.filter(isSql);
  const now = new Set(files.filter(isSql));
  const out = [];
  for (const file of before) {
    const name = path.posix.basename(file);
    if (!now.has(file)) {
      out.push({
        id: `${PREFIX}removed ${name}`,
        kind: 'removed',
        loc: name,
        message: 'a released migration was deleted; installs that applied it no longer match',
      });
    } else if (normalise(readBase(file) ?? '') !== normalise(read(file) ?? '')) {
      out.push({
        id: `${PREFIX}edited ${name}`,
        kind: 'edited',
        loc: name,
        message:
          'a released migration changed; installs already applied the old text and never run it again',
      });
    }
  }
  const shipped = new Set(before);
  const snapshots = baseFiles.filter((f) => /\/meta\/\d+_snapshot\.json$/.test(f)).sort();
  const lastSnapshot = snapshots.length ? readBase(snapshots[snapshots.length - 1]) : null;
  const released = snapshotColumns(lastSnapshot);
  for (const file of [...now].sort()) {
    if (shipped.has(file)) continue;
    out.push(...scanMigration(path.posix.basename(file), read(file) ?? '', released));
  }
  return out;
}

/** Runs the guard against `base` in the checkout at `root`; returns the exit code. */
export function run(root, base) {
  const read = (f) => {
    const abs = path.join(root, f);
    return existsSync(abs) ? readFileSync(abs, 'utf8') : null;
  };
  const baseFiles = listAt(root, base, MIGRATIONS);
  const files = listHere(root, MIGRATIONS);
  const breaks = compareMigrations({
    baseFiles,
    files,
    readBase: (f) => showAt(root, base, f),
    read,
  });
  const released = baseFiles.filter((f) => f.endsWith('.sql')).length;
  const added = files.filter((f) => f.endsWith('.sql')).length - released;
  console.log(`migrations:guard  ${released} migration(s) in ${base}, ${Math.max(added, 0)} new`);
  return report('migrations:guard', base, breaks, loadApprovals(root), PREFIX);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const root = path.resolve(
    argValue(args, '--root') ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '..'),
  );
  try {
    process.exit(run(root, resolveBase(root, argValue(args, '--base'))));
  } catch (err) {
    console.error(`migrations:guard  FAILED: ${err.message}`);
    process.exit(1);
  }
}
