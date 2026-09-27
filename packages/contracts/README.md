# @corehub/contracts

The single source of truth for the Core Hub API (ADR 0003).

- `openapi.yaml` — OpenAPI 3.1 document for `/api/v1`.
- `events/` — JSON Schema for every realtime event (`<entity>.<verb>`).
- `src/` — the TypeScript client wrapper (`createHubClient`) and document helpers used by the server.
- `generated/` — output of `pnpm contracts:generate` (git-ignored): `ts/schema.ts`, `kotlin/`, `swift/`.
- `openapi-generator/` — checked-in configs for the Kotlin and Swift generators.
- `scripts/check-clients.mjs` — fails when a client hand-types an `/api/` path (see `docs/harness/README.md`).

Commands (from the repository root): `pnpm contracts:lint`, `pnpm contracts:generate`,
`pnpm test --filter @corehub/contracts`.

## Explicit `null` in the generated phone clients

A request property that is required and may be `null` is always sent (`null` when unset); an
optional one that may be `null` is sent as `null` only when listed in the model's `sendNull`
(`SessionPatch(sendNull = setOf(SessionPatch.Clearable.MODEL))` in Kotlin,
`SessionPatch(sendNull: [.model])` in Swift), and otherwise left out. `scripts/explicit-nulls.mjs`
patches the generator's output after `generate:native` and fails loudly when that output changes
shape. Contract decision §114.
