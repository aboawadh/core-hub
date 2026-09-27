/**
 * Moving a profile in and out of the hub as Hermes's own archive (ADR 0014 stage 2).
 *
 * `auth` owns the operations (`auth.exportProfile`, `auth.importProfile`) and the workspace
 * rows; it knows nothing of Hermes, of where attachment bytes live or of how a provider key
 * is sealed. Those arrive through one port that `modules/index.ts` fills:
 *
 * - `runtime` — Hermes's dashboard API (ADR 0015), which writes and reads the archive. It
 *   exists only where the hub supervises Hermes; `null` anywhere else, and both operations
 *   then answer `409 state_invalid` (`hermes_not_supervised`) before any job is made.
 * - `files` — `knowledge`, which keeps the finished export as an attachment only its
 *   requester can read, and hands back the path of an uploaded archive.
 * - `secrets` — every credential value the hub holds (`models`' provider keys, Hermes's API
 *   key), so the export can be checked for them byte by byte (`profile-archive.ts`).
 *
 * Both jobs work in a folder of their own under `<DATA_DIR>/tmp/profile-transfer/<job>/`,
 * which Hermes (the same user, the same container) can write to and which is removed when
 * the job ends, whatever the outcome.
 */
import { LEGACY, derived } from '@corehub/contracts';
import { existsSync, statSync } from 'node:fs';
import { copyFile, mkdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import { eq } from 'drizzle-orm';
import { t, type Language } from '../../i18n/index.js';
import type { ModuleDb } from '../../lib/db.js';
import { HubError } from '../../lib/errors.js';
import type { JobHandle } from '../audit/index.js';
import {
  ArchiveError,
  extractArchive,
  isCredentialFile,
  rewriteArchive,
} from './profile-archive.js';
import { runtimeProfileName } from './profile-mirror.js';
import { createProfile, slugTaken } from './profiles.js';
import { workspaces } from './schema.js';
import type { WorkspaceRow } from './serialize.js';
import { defaultWorkspace, findWorkspace } from './workspace.js';

/** How long a finished export stays downloadable (contract decision §34). */
export const EXPORT_KEEP_MS = 24 * 60 * 60_000;
/** An export larger than this is refused rather than stored (the hub's disk is not a backup). */
export const MAX_EXPORT_BYTES = 1024 * 1024 * 1024;
/** An archive that unpacks to more than this is refused before Hermes sees it. */
export const MAX_UNPACKED_BYTES = 4 * 1024 * 1024 * 1024;

/** Hermes said no; `message` is Hermes's own sentence. */
export class ProfileArchiveRefusal extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProfileArchiveRefusal';
  }
}

/** Hermes could not be asked at all (its server would not start, or died). */
export class ProfileArchiveUnavailable extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProfileArchiveUnavailable';
  }
}

export interface ProfileArchiveRuntime {
  /** Writes `profile`'s archive to `target` (a `.tar.gz` path); answers the path written. */
  export(profile: string, target: string): Promise<string>;
  /** Makes profile `name` from the archive at `archive`. */
  import(archive: string, name: string): Promise<void>;
}

export interface TransferScope {
  workspace: string;
  userId: string;
}

export interface ProfileArchiveFiles {
  /** Stores a finished export as an attachment only `scope.userId` can read. */
  keep(
    scope: TransferScope,
    file: string,
    name: string,
    expiresAt: Date,
  ): Promise<{ id: string; sizeBytes: number }>;
  /** The bytes of an attachment the caller can see, or null. */
  open(scope: TransferScope, attachmentId: string): { path: string; name: string } | null;
  /** Deletes an uploaded archive once it has served. */
  discard(scope: TransferScope, attachmentId: string): void;
  /** Deletes what has expired (old exports). */
  sweep(): void;
}

export interface ProfileTransferPorts {
  runtime: ProfileArchiveRuntime | null;
  files: ProfileArchiveFiles;
  /** Every credential value the hub holds; none of them may leave in an export. */
  secrets(): readonly string[];
  /**
   * A profile's providers and their keys, for an export that carries them, and back into
   * an imported profile as its own (contract decision §37). Absent in a hub composed
   * without `models`: such an export carries none, and such an import adds none.
   */
  providers?: {
    exportOf(workspaceId: string): unknown;
    importInto(workspaceId: string, actorId: string, bundle: unknown): number;
  };
  /**
   * Other modules set up a profile an import just added (its Hermes side, for one); `source` is
   * the profile it is a copy of — the old default, for the backup an import into the default
   * makes (contract decision §116).
   */
  added?(profile: WorkspaceRow, actorId: string, source?: WorkspaceRow | null): Promise<void>;
  /**
   * Replacing the default profile with an archive (§116): only where the hub runs Hermes
   * itself; absent or null anywhere else, and such an import is refused by name.
   */
  defaultProfile?: DefaultProfileReplacement | null;
  /** Writes a workspace's name to the runtime (the default's, once it has a new one). */
  renamed?(profile: WorkspaceRow): Promise<void>;
}

/** What an import into the default moved (`agents/hermes-default-swap.ts`). */
export interface DefaultSwapReport {
  moved: string[];
  placed: string[];
  skipped: string[];
  /** The backup profile's folder. */
  backup: string;
}

/**
 * Hermes's side of an import into the default profile (§116). `replace` holds Hermes down,
 * makes `incoming` the root home and the current default the profile `backup`, runs `commit`
 * (the hub's rows) and puts every file back when the move or `commit` fails; `settle` runs after
 * a commit that held, and its failures are logged, never undone.
 */
export interface DefaultProfileReplacement {
  /** A folder inside Hermes's home, on its filesystem, to unpack the archive into. */
  staging(job: string): string;
  /** Whether Hermes has, or had, a profile of this name. */
  taken(name: string): boolean;
  replace(request: {
    incoming: string;
    backup: string;
    job: string;
    commit(report: DefaultSwapReport): void | Promise<void>;
    settle?(report: DefaultSwapReport): void | Promise<void>;
  }): Promise<DefaultSwapReport>;
  /** Removes a staging folder and what is left in it. */
  discard(staging: string): void;
}

/**
 * The file an export "with providers" adds at the top of the profile folder (§37). An
 * import reads it before Hermes sees the archive and never hands it on.
 */
export const PROVIDERS_FILE = derived.providersFile;
/** The same file in an archive exported before the rename (Majlis); read, never written. */
export const LEGACY_PROVIDERS_FILE = LEGACY.providersFile;

function isProvidersFile(root: string | null) {
  return (entryPath: string) => {
    const parts = entryPath.split('/').filter(Boolean);
    return (
      parts.length === 2 &&
      (parts[1] === PROVIDERS_FILE || parts[1] === LEGACY_PROVIDERS_FILE) &&
      (!root || parts[0] === root)
    );
  };
}

let factory: ((app: FastifyInstance) => ProfileTransferPorts | null) | null = null;

/** Wired once, from `modules/index.ts`. Returns the previous factory (tests restore it). */
export function registerProfileTransfer(
  next: ((app: FastifyInstance) => ProfileTransferPorts | null) | null,
): ((app: FastifyInstance) => ProfileTransferPorts | null) | null {
  const previous = factory;
  factory = next;
  return previous;
}

/** The ports for this app, or none (a hub composed without the other modules). */
export function profileTransferFor(app: FastifyInstance): ProfileTransferPorts | null {
  if (!factory) return null;
  try {
    return factory(app);
  } catch {
    return null;
  }
}

/** The ports, with a runtime; the named refusal when this hub cannot ask Hermes. */
export function requireTransfer(
  app: FastifyInstance,
): ProfileTransferPorts & { runtime: ProfileArchiveRuntime } {
  const ports = profileTransferFor(app);
  if (!ports?.runtime) {
    throw new HubError('state_invalid', {
      messageKey: 'auth.profile_transfer_unmanaged',
      details: { reason: 'hermes_not_supervised' },
    });
  }
  return ports as ProfileTransferPorts & { runtime: ProfileArchiveRuntime };
}

export interface TransferContext {
  db: ModuleDb;
  dataDir: string;
  ports: ProfileTransferPorts & { runtime: ProfileArchiveRuntime };
  scope: TransferScope;
  language: Language;
  now?: () => Date;
}

function stagingOf(dataDir: string, jobId: string): string {
  return path.join(dataDir, 'tmp', 'profile-transfer', jobId);
}

/** `20260924-101500`, in UTC: the stamp Hermes's own export names carry. */
function stampOf(at: Date): string {
  return at
    .toISOString()
    .replace(/\.\d{3}Z$/, '')
    .replace(/[-:]/g, '')
    .replace('T', '-');
}

/** A failure in the requester's language, with Hermes's sentence appended when it spoke. */
function failure(
  code: 'conflict' | 'bad_request' | 'service_unavailable' | 'payload_too_large' | 'internal',
  key: string,
  language: Language,
  detail?: string,
): HubError {
  const sentence = t(key, language);
  return new HubError(code, { message: detail ? `${sentence} ${detail}` : sentence });
}

function fromRuntime(error: unknown, language: Language, key: string): never {
  if (error instanceof ProfileArchiveRefusal) {
    throw failure('conflict', key, language, error.message);
  }
  if (error instanceof ProfileArchiveUnavailable) {
    throw failure(
      'service_unavailable',
      'auth.profile_transfer_unreachable',
      language,
      error.message,
    );
  }
  throw error;
}

function fromArchive(error: unknown, language: Language): never {
  if (error instanceof ArchiveError) {
    throw failure(
      error.reason === 'too_large' ? 'payload_too_large' : 'bad_request',
      `auth.profile_archive_${error.reason}`,
      language,
    );
  }
  throw error;
}

/**
 * The export job: Hermes writes its archive, the hub rewrites it without credential files
 * and with every stored key overwritten, and keeps the result for its requester.
 */
export async function runExport(
  context: TransferContext,
  handle: JobHandle,
  profile: WorkspaceRow,
  options: { providers?: boolean } = {},
): Promise<Record<string, unknown>> {
  const { ports, language, scope } = context;
  const now = context.now ?? (() => new Date());
  const staging = stagingOf(context.dataDir, handle.id);
  const name = runtimeProfileName(profile);
  try {
    ports.files.sweep();
    await mkdir(staging, { recursive: true, mode: 0o700 });
    handle.progress(10, t('auth.profile_export_hermes', language));
    let raw: string;
    try {
      raw = await ports.runtime.export(name, path.join(staging, `${name}.tar.gz`));
    } catch (error) {
      fromRuntime(error, language, 'auth.profile_export_refused');
    }
    if (handle.cancelRequested()) return {};

    handle.progress(60, t('auth.profile_export_checking', language));
    const at = now();
    const fileName = `${archiveStemOf(profile)}-${stampOf(at)}.tar.gz`;
    const checked = path.join(staging, fileName);
    // With providers (§37): this profile's own and the shared ones it uses, keys in the
    // clear, in one file of its own. Every other file is still checked as before — the keys
    // are masked everywhere else — and the file itself is added after that check.
    const bundle =
      options.providers && ports.providers ? ports.providers.exportOf(profile.id) : null;
    const carried =
      bundle && typeof bundle === 'object'
        ? ((bundle as { providers?: unknown[] }).providers?.length ?? 0)
        : 0;
    let report;
    try {
      report = await rewriteArchive(raw, checked, {
        drop: (entry) => isCredentialFile(entry) || isProvidersFile(null)(entry),
        secrets: ports.secrets(),
        ...(bundle
          ? {
              append: [
                {
                  path: `${name}/${PROVIDERS_FILE}`,
                  data: Buffer.from(`${JSON.stringify(bundle, null, 2)}\n`, 'utf8'),
                },
              ],
            }
          : {}),
      });
    } catch (error) {
      fromArchive(error, language);
    }
    if (report.roots.length !== 1 || report.roots[0] !== name) {
      // Hermes's archive always holds one folder named after the profile; anything else is
      // not what was asked for, and is not handed out.
      throw failure('internal', 'auth.profile_export_unexpected', language);
    }
    const { size } = await stat(checked);
    if (size > MAX_EXPORT_BYTES) {
      throw failure('payload_too_large', 'auth.profile_export_too_large', language);
    }
    if (handle.cancelRequested()) return {};

    handle.progress(90, t('auth.profile_export_storing', language));
    const expiresAt = new Date(at.getTime() + EXPORT_KEEP_MS);
    const kept = await ports.files.keep(scope, checked, fileName, expiresAt);
    handle.progress(100, t('auth.profile_export_done', language));
    return {
      attachment_id: kept.id,
      profile: profile.slug,
      name: fileName,
      size_bytes: kept.sizeBytes,
      expires_at: expiresAt.toISOString().replace(/\.\d{3}Z$/, 'Z'),
      removed: report.removed,
      masked: report.masked,
      providers: carried,
    };
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}

/**
 * The archive's file name starts with the profile's name as people see it — `الرئيسي`, not
 * `default` — cleaned of what a file name cannot carry; the id when nothing is left. The
 * import dialog reads a name and an id suggestion back from it.
 */
export function archiveStemOf(profile: Pick<WorkspaceRow, 'name' | 'slug'>): string {
  const stem = profile.name
    .normalize('NFC')
    // Path separators, characters Windows refuses, control and direction marks.
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u001f\u007f\u200e\u200f\u202a-\u202e\u2066-\u2069/\\:*?"<>|]+/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^[\s.]+|[\s.]+$/g, '');
  return stem || profile.slug;
}

export interface ImportRequest {
  attachmentId: string;
  slug: string;
  name: string;
}

/**
 * The import job: the upload is checked to be a profile archive, Hermes makes the profile
 * from it, and the hub adds the workspace. The upload is deleted when the job ends.
 */
export async function runImport(
  context: TransferContext,
  handle: JobHandle,
  input: ImportRequest,
): Promise<Record<string, unknown>> {
  const { db, ports, language, scope } = context;
  const staging = stagingOf(context.dataDir, handle.id);
  try {
    const upload = ports.files.open(scope, input.attachmentId);
    if (!upload) {
      throw new HubError('not_found', {
        message: t('auth.profile_archive_missing', language),
        details: { resource: 'attachment', id: input.attachmentId },
      });
    }
    await mkdir(staging, { recursive: true, mode: 0o700 });
    handle.progress(10, t('auth.profile_import_checking', language));
    const archive = path.join(staging, `${input.slug}.tar.gz`);
    // Checked — and the hub's providers file, when an export "with providers" wrote one, is
    // read here (§37): its keys go to the hub's store, never into the profile's folder.
    let report;
    try {
      report = await rewriteArchive(upload.path, null, {
        maxUnpackedBytes: MAX_UNPACKED_BYTES,
        capture: isProvidersFile(null),
      });
    } catch (error) {
      fromArchive(error, language);
    }
    if (report.roots.length !== 1) {
      throw failure('bad_request', 'auth.profile_archive_roots', language);
    }
    if (report.unsafe.length > 0 || report.unsupported.length > 0) {
      throw failure(
        'bad_request',
        'auth.profile_archive_entries',
        language,
        [...report.unsafe, ...report.unsupported].slice(0, 3).join(', '),
      );
    }
    const providersFile = Object.values(report.captured)[0] ?? null;
    let bundle: unknown = null;
    if (providersFile) {
      try {
        bundle = JSON.parse(providersFile.toString('utf8'));
      } catch {
        throw failure('bad_request', 'auth.profile_archive_entries', language, PROVIDERS_FILE);
      }
      // Hermes gets the archive without it.
      try {
        await rewriteArchive(upload.path, archive, { drop: isProvidersFile(null) });
      } catch (error) {
        fromArchive(error, language);
      }
    } else {
      await copyFile(upload.path, archive);
    }
    if (handle.cancelRequested()) return {};
    // Checked when the job was asked for, and again now: another import may have won.
    if (slugTaken(db, input.slug)) {
      throw failure('conflict', 'auth.slug_taken', language);
    }

    handle.progress(40, t('auth.profile_import_hermes', language));
    try {
      await ports.runtime.import(archive, input.slug);
    } catch (error) {
      fromRuntime(error, language, 'auth.profile_import_refused');
    }

    handle.progress(90, t('auth.profile_import_adding', language));
    // The profile exists in Hermes now; a listing that ran in between may already have
    // adopted it under its bare name (ADR 0014 §3). Then it is named here instead.
    const adopted = findWorkspace(db, input.slug);
    const row = adopted
      ? db
          .update(workspaces)
          .set({ name: input.name, updatedAt: new Date() })
          .where(eq(workspaces.id, adopted.id))
          .returning()
          .get()
      : createProfile(db, scope.userId, { slug: input.slug, name: input.name, cloneFrom: null });
    // The providers the archive carried become this profile's own (§37).
    let providers = 0;
    if (bundle !== null && ports.providers) {
      try {
        providers = ports.providers.importInto(row.id, scope.userId, bundle);
      } catch {
        throw failure('bad_request', 'auth.profile_archive_entries', language, PROVIDERS_FILE);
      }
    }
    await ports.added?.(row, scope.userId);
    handle.progress(100, t('auth.profile_import_done', language));
    return { profile_id: row.id, slug: row.slug, name: row.name, providers };
  } finally {
    ports.files.discard(scope, input.attachmentId);
    await rm(staging, { recursive: true, force: true });
  }
}

// ------------------------------------------------ an archive as the default profile (§116)

/** The backup's id: this, then `default-backup-2`, `-3`, … (contract decision §116). */
export const BACKUP_SLUG = 'default-backup';

/**
 * The first backup id free both in the hub (any workspace, archived ones included — their rows
 * keep the slug) and in Hermes (a folder, or a deletion tombstone). Never refuses: the numbers
 * go on for as long as people import (the owner, 2026-09-27).
 */
export function backupSlugFor(db: ModuleDb, hermesTaken: (name: string) => boolean): string {
  for (let n = 1; ; n += 1) {
    const slug = n === 1 ? BACKUP_SLUG : `${BACKUP_SLUG}-${n}`;
    if (!slugTaken(db, slug) && !hermesTaken(slug)) return slug;
  }
}

/** Hubs (by their database) with a replacement of the default running: one at a time. */
const replacing = new WeakSet<ModuleDb>();

/**
 * Claims the one replacement slot of this hub; `false` when another one is still running (the
 * route answers `409` before any job exists). The job gives it back when it ends.
 */
export function claimDefaultReplacement(db: ModuleDb): boolean {
  if (replacing.has(db)) return false;
  replacing.add(db);
  return true;
}

export function releaseDefaultReplacement(db: ModuleDb): void {
  replacing.delete(db);
}

/** The ports of a hub that can replace its default profile; the named refusal otherwise. */
export function requireDefaultReplacement(ports: ProfileTransferPorts): DefaultProfileReplacement {
  if (!ports.defaultProfile) {
    throw new HubError('state_invalid', {
      messageKey: 'auth.profile_transfer_unmanaged',
      details: { reason: 'hermes_not_supervised' },
    });
  }
  return ports.defaultProfile;
}

export interface ReplaceDefaultRequest {
  attachmentId: string;
  /** The default profile's new name; its name stays when absent. */
  name: string | null;
}

/** An audit row about the replacement, written by the route that started it. */
export type ReplaceAudit = (
  action: 'auth.profile_default_replaced' | 'auth.profile_default_replace_failed',
  summary: string,
  data: Record<string, unknown>,
) => void;

/**
 * Whatever went wrong, the person reads first that nothing happened (the owner, 2026-09-27),
 * then why — the hub's own sentence, or Hermes's.
 */
function notDone(error: unknown, language: Language): HubError {
  const lead = t('auth.profile_replace_not_done', language);
  if (error instanceof HubError) {
    const why =
      error.messageKey && error.message === error.code
        ? t(error.messageKey, language)
        : error.message;
    return new HubError(error.code, {
      message: `${lead} ${why}`,
      ...(error.details !== undefined ? { details: error.details } : {}),
    });
  }
  const why = error instanceof Error ? error.message : String(error);
  return new HubError('internal', { message: `${lead} ${why}` });
}

/**
 * The import into the default profile (contract decision §116). The whole archive is checked
 * before anything changes; it is unpacked inside Hermes's home; then, with Hermes held down, the
 * current default's home becomes the profile `default-backup[-n]` and the archive takes its
 * place, and the hub's rows follow in one transaction: a workspace for the backup, the default's
 * new name, and the archive's providers as the default's own. The default workspace keeps its id
 * and `is_default`, so the shared providers and every hub row stay. Any failure before the rows
 * are in puts every file back and rolls the rows back; the job then fails with "the import did
 * not happen" and the reason.
 */
export async function runImportAsDefault(
  context: TransferContext,
  handle: JobHandle,
  input: ReplaceDefaultRequest,
  audit?: ReplaceAudit,
): Promise<Record<string, unknown>> {
  const { db, ports, language, scope } = context;
  let swap: DefaultProfileReplacement;
  try {
    swap = requireDefaultReplacement(ports);
  } catch (error) {
    ports.files.discard(scope, input.attachmentId);
    releaseDefaultReplacement(db);
    throw notDone(error, language);
  }
  const staging = swap.staging(handle.id);
  let backupSlug: string | null = null;
  try {
    const upload = ports.files.open(scope, input.attachmentId);
    if (!upload) {
      throw new HubError('not_found', {
        message: t('auth.profile_archive_missing', language),
        details: { resource: 'attachment', id: input.attachmentId },
      });
    }
    const current = defaultWorkspace(db);
    if (!current) throw failure('internal', 'auth.profile_replace_no_default', language);

    // 1. The whole archive, before anything changes.
    handle.progress(10, t('auth.profile_import_checking', language));
    let report;
    try {
      report = await rewriteArchive(upload.path, null, {
        maxUnpackedBytes: MAX_UNPACKED_BYTES,
        capture: isProvidersFile(null),
      });
    } catch (error) {
      fromArchive(error, language);
    }
    if (report.roots.length !== 1) {
      throw failure('bad_request', 'auth.profile_archive_roots', language);
    }
    if (report.unsafe.length > 0 || report.unsupported.length > 0) {
      throw failure(
        'bad_request',
        'auth.profile_archive_entries',
        language,
        [...report.unsafe, ...report.unsupported].slice(0, 3).join(', '),
      );
    }
    const providersFile = Object.values(report.captured)[0] ?? null;
    let bundle: unknown = null;
    if (providersFile) {
      try {
        bundle = JSON.parse(providersFile.toString('utf8'));
      } catch {
        throw failure('bad_request', 'auth.profile_archive_entries', language, PROVIDERS_FILE);
      }
    }
    if (handle.cancelRequested()) return {};

    // 2. Unpacked inside Hermes's home (one filesystem: every move after this is a rename).
    handle.progress(30, t('auth.profile_replace_unpacking', language));
    try {
      await extractArchive(upload.path, staging, {
        drop: isProvidersFile(null),
        maxUnpackedBytes: MAX_UNPACKED_BYTES,
      });
    } catch (error) {
      fromArchive(error, language);
    }
    const incoming = path.join(staging, report.roots[0]!);
    if (!existsSync(incoming) || !statSync(incoming).isDirectory()) {
      throw failure('bad_request', 'auth.profile_archive_roots', language);
    }
    if (handle.cancelRequested()) return {};

    // 3. The swap and the hub's rows, together or not at all.
    backupSlug = backupSlugFor(db, (name) => swap.taken(name));
    const backup = backupSlug;
    handle.progress(50, t('auth.profile_replace_moving', language).replace('{backup}', backup));
    const newName = input.name?.trim() || null;
    let backupRow: WorkspaceRow | null = null;
    let providers = 0;
    const moved = await swap.replace({
      incoming,
      backup,
      job: handle.id,
      commit: () => {
        db.transaction((tx) => {
          backupRow = tx
            .insert(workspaces)
            .values({
              ownerId: scope.userId,
              slug: backup,
              name: backup,
              settings: structuredClone(current.settings),
            })
            .returning()
            .get();
          if (newName && newName !== current.name) {
            tx.update(workspaces)
              .set({ name: newName, updatedAt: new Date() })
              .where(eq(workspaces.id, current.id))
              .run();
          }
          // The providers the archive carried become the default's own (§37). The models
          // module writes through the same connection, so this is inside the transaction.
          if (bundle !== null && ports.providers) {
            try {
              providers = ports.providers.importInto(current.id, scope.userId, bundle);
            } catch {
              throw failure(
                'bad_request',
                'auth.profile_archive_entries',
                language,
                PROVIDERS_FILE,
              );
            }
          }
        });
      },
      // 4. What follows, with Hermes still held down: logged when it fails, never undone.
      settle: async () => {
        const renamed = findWorkspace(db, current.id);
        if (renamed) await ports.renamed?.(renamed);
        if (backupRow) await ports.added?.(backupRow, scope.userId, current);
      },
    });
    const made = backupRow as WorkspaceRow | null;
    const now = findWorkspace(db, current.id) ?? current;
    audit?.(
      'auth.profile_default_replaced',
      `default profile replaced; old one kept as ${backup}`,
      {
        job_id: handle.id,
        backup,
        moved: moved.moved.length,
        placed: moved.placed.length,
        skipped: moved.skipped,
        providers,
      },
    );
    handle.progress(100, t('auth.profile_replace_done', language).replace('{backup}', backup));
    return {
      profile_id: now.id,
      slug: now.slug,
      name: now.name,
      providers,
      replaced_default: true,
      backup: { profile_id: made?.id ?? null, slug: backup, name: made?.name ?? backup },
      skipped: moved.skipped,
    };
  } catch (error) {
    audit?.('auth.profile_default_replace_failed', 'default profile replacement did not happen', {
      job_id: handle.id,
      ...(backupSlug ? { backup: backupSlug } : {}),
      reason: error instanceof Error ? error.message : String(error),
    });
    throw notDone(error, language);
  } finally {
    swap.discard(staging);
    ports.files.discard(scope, input.attachmentId);
    releaseDefaultReplacement(db);
  }
}
