# Harness

The harness is everything that lets a contributor — human or AI — see a constraint and verify
it without asking: scripts, tests, CI jobs and the documents they enforce. When a task gets
stuck, improve the harness rather than retrying the same prompt (`AGENTS.md`).

`validation.md` says which checks to run for which change. This page says what each check
catches and where it lives.

## Contract drift (ADR 0003)

| Drift | Caught by | Where |
|---|---|---|
| Invalid or incomplete OpenAPI (no operationId, no example, not 3.1) | `pnpm contracts:lint` | `packages/contracts/scripts/lint.mjs` (Redocly + house rules) |
| Invalid realtime event schema | `pnpm contracts:lint` | same script, Ajv over `packages/contracts/events/**` |
| Generated clients out of date | `pnpm contracts:generate` then "no uncommitted diff" step | `.github/workflows/ci.yml` job `checks` |
| A client hand-types an `/api/` path | `pnpm contracts:check-clients` | `packages/contracts/scripts/check-clients.mjs` scans `packages/cli`, `packages/web`, `apps/*` |
| Server routes drift from the document | `pnpm contract:test` | `packages/server/tests/contract/contract.test.ts`: every operation is called through the generated TS client; the answer must be schema-valid for a documented status, or the documented `501 not_implemented` envelope |
| An operation declared but never implemented | the server mounts a `501` stub for it at boot | `packages/server/src/app/routes.ts` — the gap is visible in `app.hub.stubs` and to clients |

The contract test does not replace per-module tests: TEAM-RULES §4 asks every implemented
route for at least one success and one failure case in the module's own tests.

## Breaking changes (ADR 0027)

Both guards compare the working tree with the newest release tag `v*` (semver; `--base <ref>`
or `COMPAT_BASE` to compare with something else) and run in the `checks` job after
`git fetch --no-tags --depth=1 origin 'refs/tags/v*:refs/tags/v*'`.

| Break | Caught by | Where |
|---|---|---|
| Removed operation, path, method, webhook, parameter, field, enum value, union variant; newly required parameter, request field or body; narrowed request type, enum or limit; request object newly closed; changed request default; response field that became nullable, optional or another type; removed success status or media type; auth added, `x-roles` narrowed, `x-scope: global` lost | `pnpm contracts:compat` | `packages/contracts/scripts/compat.mjs`; tests and fixtures in `packages/contracts/tests/compat.test.ts`, `tests/fixtures/compat/` |
| Removed or renamed realtime event, removed event field or enum value, event field that became nullable or another type | `pnpm contracts:compat` | same script, over `packages/contracts/events/**` |
| Released migration edited or deleted; new migration that drops or renames a released table or column, empties a table, or copies a table without one of its columns | `pnpm migrations:guard` | `scripts/migrations-guard.mjs`; tests in `scripts/migrations-guard.test.mjs` (`pnpm scripts:test`) |

A break the owner approved is listed, by the id the guard prints, in
`docs/contracts/breaking-approved.json` with `base`, `decision` (an existing `ADR NNNN` or
`DECISIONS §N`), `approved_by: "twuijri"` and `reason` (ADR 0027 §8). An approval covers one
release: after the next tag the guard reports it as stale. Meaning, defaults outside the
contract, environment variables, release file names, socket commands and the models catalogue
are not machine-checked: the pull request template's Compatibility item asks for them.

## i18n drift

`pnpm i18n:check` (`scripts/i18n-check.mjs`) flattens each locale set's `ar.json` and
`en.json` and fails on a key missing on either side, an empty value, or placeholders that
differ. Today the sets are `packages/server/src/i18n` (error-code messages),
`packages/cli/src/i18n` (every string the reference client prints) and
`packages/web/src/i18n` (every string the web client shows); the script already lists
`apps/desktop/src/i18n` and starts checking it as soon as it exists. Native apps add their
locale directories to `LOCALE_SETS`.

The server picks `ar` or `en` from `Accept-Language` and localises the `error` field of the
envelope; `code` is what clients localise on.

## Design and layout drift (web)

- `packages/ui-tokens/tests/contrast.test.ts` computes WCAG contrast from `tokens.json` for
  every pair listed under `contrast.pairs`, in both themes and — for text over the glass
  chrome — at every glass level (the tint composited over the page background).
- `packages/web/tests/logical-css.test.ts` fails on any physical `left`/`right` property or
  Tailwind class, so one stylesheet serves Arabic (rtl) and English (ltr).
- `packages/web/tests/i18n.test.ts` fails when a `t('key')` literal in `src` is missing from
  either catalogue, or when a dynamic key family (statuses, roles, …) is incomplete.

## Navigation drift

`pnpm nav:check` (`scripts/navigation-check.mjs`) validates `docs/clients/navigation.json`:
every term has `ar` and `en`, every destination has exactly one primary entry, entry label
key equals title key, every list item is a known destination, secondary entries are explicit,
and `surfaceRoutes.<surface>` names a unique route for every destination that exists on that
surface (and none for one that does not). Each client's parity test
(`docs/clients/README.md`) then compares the client to the manifest — for the web,
`packages/web/tests/navigation.parity.test.tsx`.

## Missing change records (TEAM-RULES §2)

`.github/workflows/change-record.yml` runs `scripts/check-change-record.mjs --base
origin/<base>` on every pull request. The PR must add or modify
`docs/changes/YYYY-MM-DD-<owner>-<topic>.md`, with the header line
(`المسؤول · الفرع · الحالة`) and all seven sections from `docs/changes/README.md`; the
`الفحوص` section must contain real command output (a fenced block) or say that checks were
not run. Validate locally with `pnpm change-record:check -- --files docs/changes/<file>.md`
or `--all`.

## Module boundaries (ARCHITECTURE §Modules)

- ESLint (`eslint.config.js`) forbids `packages/server/src/modules/**` from importing `app/`
  or another module's internals (only `../<module>/index.js`).
- One test per module (`src/modules/<name>/<name>.test.ts`) asserts the app composes it:
  `registerRoutes` and `registerEvents` are called once, and streaming modules own their
  Socket.IO namespace.
- `tests/unit/config.test.ts` asserts `app/config.ts` is the only file reading `process.env`
  and that it reads only `DATA_DIR`, `PORT`, `DATABASE_URL`, `HUB_ADMIN_PASSWORD`.
- `tests/unit/logger.test.ts` asserts secrets are redacted from logs.

## Database

`pnpm db:generate` and `pnpm db:migrate` wrap Drizzle Kit (`packages/server/scripts/db.mjs`);
both are no-ops until a `src/modules/<name>/schema.ts` and a migration exist. CI's
`migrations` job applies migrations to a fresh SQLite file and to PostgreSQL.

## Release

`pnpm build` and the Docker job in CI (`packages/server/Dockerfile`, smoke-tested on
`/api/v1/health` and on `/chat` answering the web client's `index.html`). The `web-e2e`
job runs the Playwright journeys of `packages/web/e2e` against the real hub. `.github/workflows/release.yml` publishes to GHCR only on a `v*` tag that
points at `main` (TEAM-RULES §6).

## CI on a private repository

`twuijri/core-hub` was private when this was written (it is public now, so the quota\nbelow no longer applies to it). GitHub Actions minutes for private repositories
come out of the account's monthly quota; when it is exhausted every job fails
in about three seconds with no steps executed (the signature we saw on the
founding commit: run 35627237198, three jobs, `steps: []`). Nothing is wrong
with the workflows — the same workflows run fine on a public repository.

Until the owner adds Actions minutes or makes the repository public, the
checks in `docs/harness/validation.md` are run locally before every merge and
their real output is pasted in the change record. Do not "fix" CI by removing
jobs.
