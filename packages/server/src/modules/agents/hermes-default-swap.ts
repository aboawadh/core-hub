/**
 * An unpacked profile archive becomes Hermes's **default** profile, and the default profile it
 * replaces is kept as a named one (contract decision §116).
 *
 * Hermes's default profile is its root home itself; the named ones live under
 * `profiles/<name>/`. Hermes refuses to import as `default` and has no operation that makes the
 * root a named profile (`hermes_cli/profiles.py` §import_profile and §rename_profile, v2026.9.14),
 * so the hub moves the files itself — inside the one home, so every move is a `rename` on one
 * filesystem — and writes each move in a journal it can replay backwards.
 *
 * The root also holds what Hermes shares across every profile: the task board, the shared OAuth
 * store, the named profiles themselves, the root gateway's runtime files and the installation.
 * Those stay (`SHARED_AT_ROOT`) and an archive never replaces them. Everything else at the root
 * belongs to the default profile and moves; the list is of what stays, because Hermes's own
 * per-profile files are many and grow with it (more than sixty names in its source).
 */
import { existsSync, mkdirSync, readdirSync, renameSync, rmSync, rmdirSync } from 'node:fs';
import path from 'node:path';

/**
 * Root entries Hermes shares across profiles or keeps for the installation — never moved, never
 * replaced by an archive (observed in Hermes v2026.9.14):
 * - `profiles` — the named profiles (the backup lands there too);
 * - `kanban.db*`, `kanban` — the task board, shared by design (`kanban_db.py` §kanban_home);
 * - `auth.json` — the shared OAuth store named profiles fall back on (`auth_oauth_grants.py`);
 * - `shared`, `honcho.json`, `active_profile` — root stores that name every profile;
 * - `logs`, `gateway_state.json`, `gateway.pid`, `processes.json` — the root processes' own;
 * - the installation: `bin`, `node`, `node_modules`, `hermes-agent`, `.worktrees`, `runtime`,
 *   `runtimes`, `models`, `backups`, `profile-exports`, `desktop-ssh`, `.update_check`,
 *   `.update_response`.
 */
export const SHARED_AT_ROOT: ReadonlySet<string> = new Set([
  'profiles',
  'kanban.db',
  'kanban.db-wal',
  'kanban.db-shm',
  'kanban',
  'auth.json',
  'shared',
  'honcho.json',
  'active_profile',
  'logs',
  'gateway_state.json',
  'gateway.pid',
  'processes.json',
  'bin',
  'node',
  'node_modules',
  'hermes-agent',
  '.worktrees',
  'runtime',
  'runtimes',
  'models',
  'backups',
  'profile-exports',
  'desktop-ssh',
  '.update_check',
  '.update_response',
]);

/** Hermes's `_PROFILE_ID_RE`. */
const PROFILE_ID = /^[a-z0-9][a-z0-9_-]{0,63}$/;
/** The hub's working folders under `profiles/`: a dot name Hermes and the hub never list. */
const STAGING_PREFIX = '.corehub-';

/** Whether Hermes has, or had, a profile of this name (a folder, or a deletion tombstone). */
export function hermesProfileTaken(home: string, name: string): boolean {
  return (
    name === 'default' ||
    existsSync(path.join(home, 'profiles', name)) ||
    existsSync(path.join(home, 'profiles', '.deleted', name))
  );
}

/** Where an archive is unpacked before it replaces the default: inside the home, hidden. */
export function defaultImportStaging(home: string, job: string): string {
  return path.join(home, 'profiles', `${STAGING_PREFIX}import-${job.toLowerCase()}`);
}

export interface DefaultSwapReport {
  /** Root entries of the old default, now in the backup. */
  moved: string[];
  /** The archive's entries, now at the root. */
  placed: string[];
  /** The archive's entries left out because Hermes shares that name across profiles. */
  skipped: string[];
  /** The backup profile's folder. */
  backup: string;
}

export interface DefaultSwap {
  report: DefaultSwapReport;
  /** Puts everything back where it was. Throws when a move back fails (the log says which). */
  undo(): void;
}

/** The file operations, so a test can make one fail half-way. */
export interface SwapFs {
  rename(from: string, to: string): void;
}

const realFs: SwapFs = { rename: (from, to) => renameSync(from, to) };

/**
 * Makes `incoming` (an unpacked profile folder inside `home`) Hermes's default profile and the
 * current default the named profile `backup`. Every move is journalled; when one fails, the moves
 * already made are undone before the error is thrown, so the home is as it was.
 */
export function swapDefaultProfile(options: {
  home: string;
  incoming: string;
  backup: string;
  job: string;
  fs?: SwapFs;
}): DefaultSwap {
  const fs = options.fs ?? realFs;
  const home = path.resolve(options.home);
  const incoming = path.resolve(options.incoming);
  if (!PROFILE_ID.test(options.backup) || options.backup === 'default') {
    throw new Error(`"${options.backup}" cannot be a Hermes profile`);
  }
  if (hermesProfileTaken(home, options.backup)) {
    throw new Error(`Profile '${options.backup}' already exists`);
  }
  const profiles = path.join(home, 'profiles');
  const building = path.join(profiles, `${STAGING_PREFIX}swap-${options.job.toLowerCase()}`);
  const backupDir = path.join(profiles, options.backup);
  const own = (name: string) => !SHARED_AT_ROOT.has(name);
  const rootEntries = readdirSync(home).filter(own).sort();
  const archiveEntries = readdirSync(incoming).sort();

  const journal: Array<{ from: string; to: string }> = [];
  const move = (from: string, to: string) => {
    fs.rename(from, to);
    journal.push({ from, to });
  };
  let madeProfiles = false;
  let madeBuilding = false;
  const undo = () => {
    const failures: string[] = [];
    for (const step of [...journal].reverse()) {
      try {
        fs.rename(step.to, step.from);
      } catch (error) {
        failures.push(`${step.to} → ${step.from}: ${(error as Error).message}`);
      }
    }
    journal.length = 0;
    if (madeBuilding) removeIfEmpty(building);
    if (madeProfiles) removeIfEmpty(profiles);
    if (failures.length > 0) {
      throw new Error(`could not put the default profile back: ${failures.join('; ')}`);
    }
  };

  const report: DefaultSwapReport = { moved: [], placed: [], skipped: [], backup: backupDir };
  try {
    if (!existsSync(profiles)) {
      mkdirSync(profiles, { recursive: true });
      madeProfiles = true;
    }
    mkdirSync(building, { mode: 0o700 });
    madeBuilding = true;
    for (const name of rootEntries) {
      move(path.join(home, name), path.join(building, name));
      report.moved.push(name);
    }
    for (const name of archiveEntries) {
      if (!own(name)) {
        report.skipped.push(name);
        continue;
      }
      move(path.join(incoming, name), path.join(home, name));
      report.placed.push(name);
    }
    move(building, backupDir);
  } catch (error) {
    undo();
    throw error;
  }
  return { report, undo };
}

/** Removes a working folder the hub made, when nothing is left in it. */
function removeIfEmpty(dir: string): void {
  try {
    rmdirSync(dir);
  } catch {
    // Not empty, or gone already: it is left for a person to look at.
  }
}

/** Removes a staging folder and whatever is left in it (the archive's skipped entries). */
export function removeStaging(dir: string): void {
  if (!path.basename(dir).startsWith(STAGING_PREFIX)) return;
  rmSync(dir, { recursive: true, force: true });
}

/** What the job hands in (contract decision §116): the hub's rows, and what follows them. */
export interface DefaultReplaceRequest {
  /** The unpacked profile folder (inside the home, from `defaultImportStaging`). */
  incoming: string;
  /** The named profile the current default becomes. */
  backup: string;
  job: string;
  /** The hub's own rows. When it throws, the files are put back and the error rethrown. */
  commit(report: DefaultSwapReport): void | Promise<void>;
  /** After a commit that held, still with Hermes held down. Failures are logged, never undone. */
  settle?(report: DefaultSwapReport): void | Promise<void>;
}

export interface DefaultProfileReplacement {
  staging(job: string): string;
  taken(name: string): boolean;
  replace(request: DefaultReplaceRequest): Promise<DefaultSwapReport>;
  discard(staging: string): void;
}

/**
 * The replacement over one Hermes home. `hold` keeps every Hermes process that works in the root
 * home down while its work runs (`HermesRuntime.withRootHeld`); `prepare` puts the hub's own
 * files back where they belong once the rows are in (its tools and skill library in the new
 * root, none of its tools in the backup, whose key now belongs to the default); `after` runs once
 * Hermes is back (the backup's gateway, when it brought channels or schedules along).
 */
export function createDefaultProfileReplacement(options: {
  home: string;
  hold: <T>(work: () => Promise<T>) => Promise<T>;
  prepare?: (report: DefaultSwapReport) => void | Promise<void>;
  after?: () => void | Promise<void>;
  fs?: SwapFs;
  log: {
    warn(object: Record<string, unknown>, message: string): void;
    error(object: Record<string, unknown>, message: string): void;
  };
}): DefaultProfileReplacement {
  const { home, log } = options;
  return {
    staging: (job) => defaultImportStaging(home, job),
    taken: (name) => hermesProfileTaken(home, name),
    discard: (dir) => removeStaging(dir),
    async replace(request) {
      const report = await options.hold(async () => {
        const swap = swapDefaultProfile({
          home,
          incoming: request.incoming,
          backup: request.backup,
          job: request.job,
          ...(options.fs ? { fs: options.fs } : {}),
        });
        try {
          await request.commit(swap.report);
        } catch (error) {
          try {
            swap.undo();
          } catch (undoError) {
            log.error(
              { err: undoError, home, backup: request.backup },
              'hermes: the default profile could not be put back after a failed replacement',
            );
            throw undoError;
          }
          throw error;
        }
        for (const step of [options.prepare, request.settle]) {
          try {
            await step?.(swap.report);
          } catch (error) {
            log.warn(
              { err: error, backup: request.backup },
              'hermes: a step after replacing the default profile did not finish',
            );
          }
        }
        return swap.report;
      });
      try {
        await options.after?.();
      } catch (error) {
        log.warn({ err: error }, 'hermes: the profile gateways did not follow the new default');
      }
      return report;
    },
  };
}
