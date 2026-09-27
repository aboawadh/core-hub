/**
 * The hub's own Hermes home on top of the person's own Hermes install.
 *
 * Where the hub runs a `hermes` program somebody installed with Hermes's own installer (the
 * desktop app's local mode, ADR 0021; a hub run natively beside Hermes), it runs it with its
 * own home, `${DATA_DIR}/hermes`, never the person's `~/.hermes` (ADR 0021 decision 3).
 *
 * Current Hermes (its package manager, `pm/`, since v0.21.5 of 2026-09-24) keeps the Python
 * packages and tools an install runs on per **data root**, not beside the program:
 *
 *   <root>/installs/<install key>/   which dependency environment the install uses
 *   <root>/tools/                    the Python, Node, ripgrep … it runs on
 *
 * and takes any `HERMES_HOME` outside `~/.hermes` for a root of its own
 * (`hermes_constants.get_default_hermes_root`, `pm/environments.dependency_home_root`). So the
 * first `hermes gateway run` in the hub's home saw no dependencies at all and rebuilt a whole
 * second runtime there — about 2 GB and several minutes online (the card said "starting" and
 * chat said the gateway did not answer), a crash loop offline (`No module named 'ruamel'`),
 * and at the end Hermes pointed the person's own `hermes` launcher at the Python it had just
 * downloaded into Core Hub's folder (reproduced 2026-09-27, docs/changes/2026-09-27-…).
 *
 * The hub's home therefore uses the install's dependency state where the person's Hermes
 * keeps it, the way Hermes's own profiles share one install:
 *
 *   ${home}/installs  ->  <root>/installs   (a symlink; a junction on Windows)
 *   HERMES_RUNTIME_DIR = <root>/tools       (Hermes's documented override for its tool store)
 *
 * Nothing in the person's home is written by this: the link lives in the hub's home, and
 * `HERMES_RUNTIME_DIR` is only put into the environment of the Hermes processes the hub
 * starts. Configuration, keys, memory, skills and sessions stay the hub's own.
 *
 * Only done when the person's root has `installs/` (an older Hermes keeps its packages in a
 * venv beside the program and needs nothing), when the hub's home is not that root (the image,
 * where `HERMES_HOME` is the hub's home), and when the hub's home has no dependency state of its
 * own already: a runtime a previous version let Hermes finish there is left as it is, and a
 * download that never finished is moved aside (renamed, never deleted).
 */
import {
  lstatSync,
  mkdirSync,
  readdirSync,
  realpathSync,
  renameSync,
  statSync,
  symlinkSync,
} from 'node:fs';
import path from 'node:path';

/** `COREHUB_HERMES_SHARED_INSTALL=off` keeps Hermes's own behaviour (a runtime per home). */
export const SHARED_INSTALL_SWITCH = 'COREHUB_HERMES_SHARED_INSTALL';

export type SharedInstallOutcome =
  /** The hub's home now uses the person's install state. */
  | 'linked'
  /** It already did (an earlier start made the link). */
  | 'already'
  /** The hub's home has a finished runtime of its own; left alone. */
  | 'own'
  /** Nothing to share: no person's root, an older Hermes, the same folder, or switched off. */
  | 'none';

export interface SharedInstall {
  outcome: SharedInstallOutcome;
  /** The person's Hermes root whose install state is shared; null when none is. */
  root: string | null;
  /** `HERMES_RUNTIME_DIR` for the Hermes processes the hub starts; null to leave it unset. */
  runtimeDir: string | null;
  /** An unfinished runtime moved out of the way, when there was one. */
  movedAside: string | null;
  /** Why nothing could be linked, when something failed. */
  error: string | null;
}

/**
 * The Hermes root of the person running the hub, as Hermes itself resolves it for a process
 * started from their shell: `HERMES_HOME` (the root above it when it names a profile), else
 * `~/.hermes`, or `%LOCALAPPDATA%\hermes` on Windows. Null when the environment says neither.
 */
export function personalHermesRoot(
  env: NodeJS.ProcessEnv,
  platform: NodeJS.Platform = process.platform,
): string | null {
  const p = platform === 'win32' ? path.win32 : path.posix;
  const explicit = env.HERMES_HOME?.trim();
  if (explicit) {
    const resolved = p.resolve(explicit);
    return p.basename(p.dirname(resolved)) === 'profiles'
      ? p.dirname(p.dirname(resolved))
      : resolved;
  }
  if (platform === 'win32') {
    const local =
      env.LOCALAPPDATA?.trim() ||
      (env.USERPROFILE?.trim() ? p.join(env.USERPROFILE.trim(), 'AppData', 'Local') : '');
    return local ? p.join(local, 'hermes') : null;
  }
  const home = env.HOME?.trim();
  return home ? p.join(home, '.hermes') : null;
}

function isDirectory(file: string): boolean {
  try {
    return statSync(file).isDirectory();
  } catch {
    return false;
  }
}

function sameFolder(a: string, b: string): boolean {
  try {
    return realpathSync(a) === realpathSync(b);
  } catch {
    return path.resolve(a) === path.resolve(b);
  }
}

/** True when some install under `installs` has a committed dependency selection. */
function hasFinishedRuntime(installs: string): boolean {
  try {
    return readdirSync(installs).some((key) => {
      try {
        return statSync(path.join(installs, key, 'facts.json')).isFile();
      } catch {
        return false;
      }
    });
  } catch {
    return false;
  }
}

export interface ShareOptions {
  /** The hub's Hermes home (`${DATA_DIR}/hermes`). */
  home: string;
  /** The environment the hub runs Hermes with (`HostEnvironment.inherited`). */
  env: NodeJS.ProcessEnv;
  platform?: NodeJS.Platform;
  /** For the name of a moved-aside folder; tests pin it. */
  now?: () => number;
}

/**
 * Makes the hub's home use the person's Hermes install state. Never throws: a folder it cannot
 * link is reported (`error`) and Hermes then does what it does by itself.
 */
export function shareHermesInstall(options: ShareOptions): SharedInstall {
  try {
    return share(options);
  } catch (error) {
    return {
      outcome: 'none',
      root: null,
      runtimeDir: null,
      movedAside: null,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

function share(options: ShareOptions): SharedInstall {
  const none: SharedInstall = {
    outcome: 'none',
    root: null,
    runtimeDir: null,
    movedAside: null,
    error: null,
  };
  const switchValue = options.env[SHARED_INSTALL_SWITCH]?.trim().toLowerCase();
  if (switchValue === 'off' || switchValue === '0' || switchValue === 'false') return none;
  const platform = options.platform ?? process.platform;
  const root = personalHermesRoot(options.env, platform);
  if (!root) return none;
  const sharedInstalls = path.join(root, 'installs');
  if (!isDirectory(sharedInstalls)) return none;
  if (sameFolder(root, options.home)) return none;

  const target = realpathSync(sharedInstalls);
  const tools = path.join(root, 'tools');
  const runtimeDir = isDirectory(tools) ? realpathSync(tools) : null;
  const link = path.join(options.home, 'installs');
  let movedAside: string | null = null;

  let existing: ReturnType<typeof lstatSync> | null;
  try {
    existing = lstatSync(link);
  } catch {
    existing = null;
  }
  if (existing?.isSymbolicLink()) {
    let pointsAt: string | null;
    try {
      pointsAt = realpathSync(link);
    } catch {
      pointsAt = null;
    }
    if (pointsAt === target) {
      return { outcome: 'already', root, runtimeDir, movedAside: null, error: null };
    }
    // A link to somewhere else (the person moved their Hermes): pointed again below. Only the
    // link is renamed away, never what it pointed at.
    movedAside = `${link}.previous-${(options.now ?? Date.now)()}`;
    renameSync(link, movedAside);
  } else if (existing) {
    if (!existing.isDirectory() || hasFinishedRuntime(link)) {
      return { outcome: 'own', root, runtimeDir: null, movedAside: null, error: null };
    }
    // Hermes began a runtime of its own here and never finished it: out of the way, kept.
    movedAside = `${link}.unfinished-${(options.now ?? Date.now)()}`;
    renameSync(link, movedAside);
  }

  mkdirSync(options.home, { recursive: true });
  symlinkSync(target, link, platform === 'win32' ? 'junction' : 'dir');
  return { outcome: 'linked', root, runtimeDir, movedAside, error: null };
}
