# Contracts

The API and realtime contract lives in code, in `packages/contracts`:

- `packages/contracts/openapi.yaml` — OpenAPI 3.1 for `/api/v1`. Every operation has an
  `operationId`, an example request and response (Arabic where the text is user-facing), and a
  compatibility note on change (ADR 0003).
- `packages/contracts/events/` — JSON Schema per realtime event, named `<entity>.<verb>`
  (`message.delta`, `run.failed`, `task.moved`), grouped by Socket.IO namespace
  (`/rt/sessions`, `/rt/rooms`, `/rt/tasks`, `/rt/schedules`, `/rt/devices`).
- Generated from it: the TypeScript client (`createHubClient`), the Kotlin client and the Swift
  client (`pnpm contracts:generate`). Clients never hand-type a path; CI fails if one does.

Rules every operation follows:

- Errors are always `{ error, code }`; `error` is localised (`Accept-Language`: `ar`/`en`),
  `code` is stable. An unknown or stale id is `404 not_found`; a declared-but-unimplemented
  operation is `501 not_implemented`.
- Every request carries `X-Hub-Profile` (workspace scope, ADR 0005).
- Long work returns a job id immediately and reports progress as events.
- `/api/v1` is never changed incompatibly (ADR 0027): add beside, never remove, rename, newly
  require or narrow. `pnpm contracts:compat` compares the document and the event schemas with the
  latest release in CI. A redesign goes to `/api/v2`, served next to `/api/v1`, with an ADR.

How to change the contract: edit the document first, run `pnpm contracts:lint` and
`pnpm contracts:generate`, then implement in the server module, then update clients — in that
order, in one PR with a change record (`docs/TEAM-RULES.md` §2).
