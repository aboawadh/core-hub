// Shared by the compatibility guards (ADR 0027): the contract guard
// (packages/contracts/scripts/compat.mjs) and the migration guard (scripts/migrations-guard.mjs).
// Both compare the working tree with the latest release tag `v*` — what people run — and fail
// on a breaking difference unless the owner approved that exact break in APPROVALS_FILE.
//
// Plain ESM with no dependencies, so it runs from the repository root and from the contracts
// package alike.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';

/** Where approved breaks are listed, relative to the repository root. */
export const APPROVALS_FILE = 'docs/contracts/breaking-approved.json';

function git(root, args) {
  return execFileSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

/** Semver precedence for `vX.Y.Z[-pre]` tags; null for anything else. */
export function parseReleaseTag(tag) {
  const m = /^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/.exec(tag);
  if (!m) return null;
  return {
    tag,
    core: [Number(m[1]), Number(m[2]), Number(m[3])],
    pre: m[4] ? m[4].split('.') : [],
  };
}

export function compareReleaseTags(a, b) {
  for (let i = 0; i < 3; i += 1) if (a.core[i] !== b.core[i]) return a.core[i] - b.core[i];
  // A release outranks its own pre-releases (1.2.0 > 1.2.0-beta.1).
  if (a.pre.length === 0 || b.pre.length === 0) return b.pre.length - a.pre.length;
  for (let i = 0; i < Math.max(a.pre.length, b.pre.length); i += 1) {
    const x = a.pre[i];
    const y = b.pre[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const nx = /^\d+$/.test(x);
    const ny = /^\d+$/.test(y);
    if (nx && ny && Number(x) !== Number(y)) return Number(x) - Number(y);
    if (nx !== ny) return nx ? -1 : 1;
    if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

/** The newest `v*` tag by semver, or null when the clone has none. */
export function latestReleaseTag(tags) {
  const parsed = tags.map(parseReleaseTag).filter(Boolean);
  parsed.sort(compareReleaseTags);
  return parsed.length ? parsed[parsed.length - 1].tag : null;
}

/**
 * The ref to compare with: `--base <ref>` / COMPAT_BASE when given, else the latest release tag.
 * Throws with a hint when there is none (a shallow CI checkout without tags).
 */
export function resolveBase(root, explicit) {
  const wanted = explicit || process.env.COMPAT_BASE;
  if (wanted) return wanted;
  const tag = latestReleaseTag(git(root, ['tag', '--list', 'v*']).split('\n').filter(Boolean));
  if (!tag) {
    throw new Error(
      "no release tag `v*` in this clone. Fetch them first: git fetch --no-tags --depth=1 origin 'refs/tags/v*:refs/tags/v*'",
    );
  }
  return tag;
}

/** A file's text at `ref`, or null when it does not exist there. */
export function showAt(root, ref, file) {
  try {
    return git(root, ['show', `${ref}:${file}`]);
  } catch {
    return null;
  }
}

/** Every file under `dir` at `ref` (repository-relative paths). */
export function listAt(root, ref, dir) {
  return git(root, ['ls-tree', '-r', '--name-only', ref, '--', dir]).split('\n').filter(Boolean);
}

/** Every file under `dir` in the working tree (repository-relative paths). */
export function listHere(root, dir) {
  const abs = path.join(root, dir);
  if (!existsSync(abs)) return [];
  return readdirSync(abs, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile())
    .map((e) =>
      path
        .relative(root, path.join(e.parentPath ?? e.path, e.name))
        .split(path.sep)
        .join('/'),
    )
    .sort();
}

const DECISION = /^(ADR (\d{4})|DECISIONS §(\d+))$/;

/**
 * Reads APPROVALS_FILE. Every entry must name the exact break (`id`, as the guard prints it), the
 * release it breaks against (`base`), a decision that exists in the repository (`ADR NNNN` or
 * `DECISIONS §N`), `approved_by: "twuijri"` and a `reason`. Problems are returned, not thrown, so
 * the guard can report them next to the breaks.
 */
export function loadApprovals(root) {
  const file = path.join(root, APPROVALS_FILE);
  if (!existsSync(file)) return { approvals: [], problems: [] };
  let data;
  try {
    data = JSON.parse(readFileSync(file, 'utf8'));
  } catch (err) {
    return { approvals: [], problems: [`${APPROVALS_FILE} is not valid JSON: ${err.message}`] };
  }
  const list = Array.isArray(data?.approvals) ? data.approvals : null;
  if (!list) return { approvals: [], problems: [`${APPROVALS_FILE} needs an "approvals" array`] };
  const problems = [];
  const approvals = [];
  list.forEach((entry, i) => {
    const where = `${APPROVALS_FILE} approvals[${i}]`;
    const missing = ['id', 'base', 'decision', 'approved_by', 'reason'].filter(
      (k) => typeof entry?.[k] !== 'string' || entry[k].trim() === '',
    );
    if (missing.length) {
      problems.push(`${where}: missing ${missing.join(', ')}`);
      return;
    }
    if (entry.approved_by !== 'twuijri') {
      problems.push(`${where}: approved_by must be the owner, "twuijri" (ADR 0027)`);
      return;
    }
    const m = DECISION.exec(entry.decision);
    if (!m) {
      problems.push(
        `${where}: decision must be "ADR NNNN" or "DECISIONS §N", not "${entry.decision}"`,
      );
      return;
    }
    if (m[2]) {
      const adrDir = path.join(root, 'docs', 'adr');
      const found = existsSync(adrDir) && readdirSync(adrDir).some((f) => f.startsWith(`${m[2]}-`));
      if (!found) {
        problems.push(`${where}: ${entry.decision} does not exist in docs/adr/`);
        return;
      }
    } else {
      const decisions = path.join(root, 'docs', 'contracts', 'DECISIONS.md');
      const text = existsSync(decisions) ? readFileSync(decisions, 'utf8') : '';
      if (!new RegExp(`^## ${m[3]}\\. `, 'm').test(text)) {
        problems.push(`${where}: ${entry.decision} does not exist in docs/contracts/DECISIONS.md`);
        return;
      }
    }
    approvals.push(entry);
  });
  return { approvals, problems };
}

/**
 * Splits breaks into approved and not approved. An approval counts only for the base it names:
 * once a newer release is tagged the break is part of that release, and the entry is reported as
 * stale so it can be removed.
 */
export function applyApprovals(breaks, approvals, base, prefix) {
  const current = approvals.filter((a) => a.base === base && a.id.startsWith(prefix));
  const ids = new Set(current.map((a) => a.id));
  const approved = breaks.filter((b) => ids.has(b.id));
  const blocking = breaks.filter((b) => !ids.has(b.id));
  const seen = new Set(breaks.map((b) => b.id));
  const unused = current.filter((a) => !seen.has(a.id));
  const stale = approvals.filter((a) => a.base !== base && a.id.startsWith(prefix));
  return { approved, blocking, unused, stale };
}

/** Prints the result the same way for both guards; returns the exit code. */
export function report(name, base, breaks, { approvals, problems }, prefix) {
  const { approved, blocking, unused, stale } = applyApprovals(breaks, approvals, base, prefix);
  for (const a of approved) console.log(`  approved  ${a.id}  (${approvalOf(approvals, a.id)})`);
  for (const a of unused) console.log(`  notice    approval not needed any more: ${a.id}`);
  for (const a of stale)
    console.log(`  notice    approval for ${a.base} is stale (base is ${base}): ${a.id}`);
  for (const p of problems) console.error(`  error     ${p}`);
  if (blocking.length === 0 && problems.length === 0) {
    console.log(`${name}  OK — no breaking change against ${base}`);
    return 0;
  }
  if (blocking.length) {
    console.error(
      `${name}  FAILED: ${blocking.length} breaking change(s) against ${base} (ADR 0027)`,
    );
    for (const b of blocking) console.error(`  break     ${b.id}\n            ${b.message}`);
    console.error(
      `  Keep the old shape and add the new one beside it. If the break cannot be avoided, the owner\n` +
        `  approves it in an ADR or DECISIONS entry and lists each id above in ${APPROVALS_FILE}\n` +
        `  with "base": "${base}" (docs/adr/0027-compatibility-no-breaking-changes.md).`,
    );
  }
  return 1;
}

function approvalOf(approvals, id) {
  return approvals.find((a) => a.id === id)?.decision ?? '';
}

/** `--name value` from argv. */
export function argValue(args, name) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
}
