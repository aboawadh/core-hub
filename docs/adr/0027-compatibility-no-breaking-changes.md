# ADR 0027 — No breaking changes: what people already run keeps working

Status: **Accepted** by the owner, 2026-09-27 («حط قرار صارم»). Several people now run Core Hub.
Until now the owner was the only user and accepted that an update could break his own install;
from this date no change may break a hub, an app, a script or the data people already have.

## Context
A Core Hub install is a Docker stack (or the desktop app) that its owner upgrades by replacing
the image; the phone and desktop apps update on their own schedule, from app stores and release
feeds; scripts and the CLI call `/api/v1` directly; webhooks are received by other people's
services. So at any moment an older app talks to a newer hub, a newer app to an older hub, and a
hub opens a database written by the release before it. `docs/contracts/README.md` already said
"`/api/v1` is never changed incompatibly", but nothing checked it: comparing the contract with
the tags shows the webhook operations became profile-scoped between v1.1.2 and v1.1.3, and
fields of presets, relay and peers were removed between v1.1.1 and v1.1.3. None of those was
caught.

## Decision
A change is **breaking** when something that worked with the latest release stops working, or
silently does something else, without the person doing anything. Breaking changes are not
made. Where one cannot be avoided, the new thing goes **beside** the old one, and the old one is
removed only after a deprecation window and with the owner's written approval.

### 1. The API contract (`/api/v1`, `packages/contracts`)
What a client **sends** may only be accepted more widely; what a client **receives** may only
promise more. Breaking, for the contract:

- removing or renaming an operation, a path or a method (a path parameter's name is not part of
  the wire and may change); removing a webhook;
- removing a request field or parameter the hub reads, or a response field clients read;
- making a request field, a parameter or a request body required when it was not;
- narrowing what a request accepts: a type (`string|null` → `string`, `number` → `integer`), a
  new or smaller enum, a tighter `maxLength` / `minimum` / `pattern` / …, a request object that
  newly refuses unlisted fields;
- widening what a response or an event may hold beyond what clients were told: a field that may
  now be `null` or another type, a field that was always present and now may be absent;
- removing an enum value or a variant of a union, anywhere;
- removing a success status or a media type an operation answered with;
- a public operation that now needs a token, an operation closed to a role that could call it,
  a global operation that now needs `X-Hub-Profile`;
- a changed default, or any changed meaning that makes an unchanged client get other behaviour
  (a field that now counts something else, an operation that now also deletes, a status that
  now means something else).

**Not breaking** (additions): new operations, new optional parameters and request fields, new
response and event fields, new enum values and union variants, new events, new status codes,
looser request limits. Clients must be written for that: ignore unknown fields, show an unknown
enum value as a neutral fallback instead of failing, and ignore unknown events.

### 2. Realtime events (`packages/contracts/events`)
The same rules as a response: removing or renaming an event, removing an event field or an enum
value, or a field that may now be null or another type is breaking. The socket commands in
`events/README.md` (`subscribe`, `join`, …) follow the request rules; they have no schema yet, so
the reviewer checks them.

### 3. Data and upgrades
- Migrations are **forward-only and non-destructive**: no `DROP TABLE`, `DROP COLUMN`, table or
  column rename, or emptying a table, of anything a released hub wrote. Stop using a column
  instead of dropping it; add the new column and copy into it instead of renaming. Drizzle's
  SQLite table copy (`__new_x`) is fine when it keeps every column.
- A migration that shipped in a release is never edited or deleted: installs already applied it.
- An upgrade is **replacing the image only** (`docs/DEPLOY.md`): no new required environment
  variable, volume, port or Compose change. A new setting has a working default.
- Old names keep working: a renamed environment variable, browser key, file or token issuer is
  still read under its old name (as `LEGACY` in `packages/contracts/src/product.ts` does for
  `MAJLIS_*`).
- Files the hub writes for later (archives, exports, config it owns) stay readable by the next
  release; a new format version is read beside the old one.

### 4. Apps and hubs of different ages
- **A newer app with an older hub**: the app detects what the hub offers (`meta.get`:
  `server_version`, `api_versions`; or a `404` / `501` from an operation it tried) and hides or
  disables the feature with a clear note, instead of failing or showing an empty screen.
- **An older app with a newer hub** keeps working, which is what the contract rules above
  guarantee.

### 5. Releases and updates
File names and paths that updaters, the download page (`site/`), the store pipelines and the
models catalogue readers use do not change: the asset names fixed in
`apps/desktop/scripts/release-assets.mjs`, `latest*.yml`, the `.deb` / `.apk` names, the image
name and tags, the raw URL of `catalog/models.json`. A new name is added beside the old one.

### 6. The shared models catalogue (`catalog/models.json`)
Every running hub reads it from `main`. Its keys and fields keep their meaning; a field is only
added, and a hub ignores fields it does not know. Removing or renaming a key, or changing a
field's type or meaning, is breaking — for hubs already running, not just the next release.

### 7. When a break cannot be avoided
1. Stop and ask the owner before writing it. An assistant never decides this.
2. Prefer a new thing beside the old one: a new operation or field (the old one marked
   `deprecated: true` and described as such), a new event, a new column, a new file name — or,
   for a change to the whole API, `/api/v2` served next to `/api/v1`.
3. Keep the old one working for a deprecation window of at least two releases and 90 days
   (the length is proposed — owner to confirm), and say in the release notes when it goes.
4. Removing it after the window is itself a break: it needs the owner's approval as below.

### 8. The guards (CI)
Two checks in the `checks` job of `.github/workflows/ci.yml` compare the branch with the latest
release tag `v*` (by semver, pre-releases included), which is what people run:

- `pnpm contracts:compat` (`packages/contracts/scripts/compat.mjs`) — the rules of §1 and §2
  that can be read from the documents: removed operations, paths, methods, webhooks, parameters,
  fields, enum values, variants, events and event fields; newly required parameters, request
  fields and bodies; narrowed request types and limits; closed request objects; changed request
  defaults; response and event fields that became nullable, optional or another type; removed
  success statuses and media types; added authentication, narrowed `x-roles`, lost
  `x-scope: global`.
- `pnpm migrations:guard` (`scripts/migrations-guard.mjs`) — §3 for migrations: a released
  migration edited or deleted; a new migration that drops or renames a released table or
  column, empties a table, or copies a table without one of its columns.

What they cannot see — meaning, defaults outside the contract, environment variables, release
file names, socket commands, the catalogue — the author checks and the pull request template
asks about.

**The escape hatch.** A break the owner approved is listed in
`docs/contracts/breaking-approved.json`, one entry per break, with the id exactly as the guard
prints it:

```json
{
  "approvals": [
    {
      "id": "contract operation-removed DELETE /notes/{note_id}",
      "base": "v1.1.3",
      "decision": "ADR 0028",
      "approved_by": "twuijri",
      "reason": "Replaced by POST /notes/{note_id}/archive in v1.2.0; removed after the window."
    }
  ]
}
```

`decision` must name an ADR in `docs/adr/` or an entry of `docs/contracts/DECISIONS.md`
(`DECISIONS §N`) that records the owner's approval; `approved_by` must be `twuijri`; `base` is
the release the break is measured against, so an approval covers one release only and the guard
reports it as stale once a newer tag exists. The file is part of the pull request, so the owner
sees every approval when he reviews it. Nobody else adds an entry.

## Alternatives rejected
- **A general OpenAPI diff tool** (oasdiff, openapi-diff): another binary in CI, no view of our
  realtime event schemas, our `x-roles` / `x-scope`, or migrations, and its own idea of what
  breaks. A small script that states our rules in our words is easier to review.
- **Comparing with `main` instead of the latest release**: a break merged into `main` would then
  pass every later pull request. Users run releases; the release is the reference.
- **An inline `x-breaking-approved` marker in the contract**: it cannot mark something that was
  removed, and it would need a second form for migrations. One file covers both and is easy for
  the owner to review.
- **Versioning every change (`/api/v2` for each break)**: most breaks are avoidable by adding
  beside; a new API version is for a real redesign.

## Consequences
- A pull request that breaks the contract or the data fails CI with the list of breaks, until
  the change is made additive or the owner approves it.
- Clients must tolerate unknown fields, enum values and events, and must feature-detect an older
  hub. The phone and desktop apps follow this from their next change on.
- The guards compare with the newest tag; a clone without tags cannot run them, so CI fetches
  the `v*` tags first (`git fetch --no-tags --depth=1 origin 'refs/tags/v*:refs/tags/v*'`).
- Rules the guards cannot see still depend on review: the PR template has a Compatibility item,
  `AGENTS.md`, `CONTRIBUTING.md` and `docs/TEAM-RULES.md` state the rule.
