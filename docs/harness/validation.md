# Validation matrix

| Change touches | Run before the PR |
|---|---|
| `packages/contracts` | `pnpm contracts:lint` (OpenAPI + schemas), `pnpm contracts:generate` (all three clients regenerate with no diff left uncommitted), `pnpm test --filter contracts`, `pnpm contracts:compat` (no breaking change against the latest release, ADR 0027; needs the `v*` tags: `git fetch origin --tags`) |
| `packages/server` | `pnpm typecheck`, `pnpm test --filter server`, `pnpm contract:test` (every route exercised from the generated client), `pnpm lint` |
| `packages/cli` | `pnpm typecheck`, `pnpm test --filter @corehub/cli` (unit + the in-process integration test against the real server), `pnpm contracts:check-clients`, `pnpm i18n:check`, `pnpm lint`, `pnpm build` then `node packages/cli/dist/bin.js --help` |
| `packages/ui-tokens` | `pnpm --filter @corehub/ui-tokens test` (WCAG AA contrast of every declared pair in both themes and over every glass level), `pnpm tokens:build` leaves `dist/` regenerated |
| `packages/web` | `pnpm typecheck`, `pnpm test --filter @corehub/web` (navigation parity, logical CSS, i18n coverage, reducer, auth client), `pnpm contracts:check-clients`, `pnpm i18n:check`, `pnpm nav:check`, `pnpm lint`, `pnpm build`, then `pnpm web:e2e` (four Playwright journeys against the real hub — the fourth boots a second hub with no owner for first-run setup; `playwright install chromium` once) |
| `docs/clients/navigation.json` | `pnpm nav:check` plus the web parity test (`pnpm test --filter @corehub/web`) |
| a module's schema | `pnpm db:generate` produces a migration; `pnpm db:migrate` on a fresh SQLite and on PostgreSQL in CI; `pnpm migrations:guard` (no released migration edited, nothing released dropped, renamed or emptied, ADR 0027) |
| the compatibility guards (`packages/contracts/scripts/compat.mjs`, `scripts/migrations-guard.mjs`, `scripts/compat-base.mjs`) or `docs/contracts/breaking-approved.json` | `pnpm --filter @corehub/contracts exec vitest run tests/compat.test.ts`, `node --test scripts/migrations-guard.test.mjs`, `pnpm contracts:compat`, `pnpm migrations:guard`; an approval entry only with the owner's decision (ADR 0027 §8) |
| any user text | `pnpm i18n:check` (ar/en parity, no missing keys; every other language's coverage and broken placeholders), `pnpm i18n:limits` (each translation against its label's measured room — CI installs the Noto fonts) |
| `locales/`, `scripts/i18n*`, a label's layout | `pnpm scripts:test`, `pnpm i18n:check`, `pnpm i18n:limits`; after a layout change re-measure (`docs/guides/add-a-language.md`, "For maintainers") and run `e2e/zzzzzzzzzzzzzzzzz-pseudo-locales.spec.ts` (ADR 0028) |
| any code or doc file | nothing for the code map: do **not** commit `graphify-out/` — the code-map bot proposes it after merge (docs/harness/knowledge-graph.md). `pnpm graph` is for your own queries |
| `.github/workflows/`, `scripts/graph*.mjs`, `scripts/check-change-record.mjs` | `pnpm lint`, `pnpm change-record:check`; `actionlint` if you have it; the bot's behaviour is only proven by its first run on `main` (docs/harness/knowledge-graph.md) |
| the agents catalog (`modules/agents/catalog/`) | `pnpm test --filter server` — the catalog guard checks unique ids, an exact version pin and a licence on every entry (ADR 0006); the pinned versions themselves are the owner's review |
| `docs/clients/NAVIGATION.md` | the client parity tests of every existing client |
| release | `pnpm build`, Docker image builds, smoke test against the image, and the latest "Update the code map" PR merged if one is open (docs/harness/knowledge-graph.md) |

Never claim a check passed without pasting its output in the change record.
