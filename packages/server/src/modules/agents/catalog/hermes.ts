// Hermes Agent — the base runtime (ADR 0006). It ships inside the image, so the hub never
// installs or removes it; the entry exists so the registry, the adapters and the clients
// all read Hermes from the same place as every other agent.
import type { CatalogEntry } from './types.js';

export const hermes: CatalogEntry = {
  id: 'hermes',
  name: 'Hermes',
  vendor: 'Nous Research',
  licence: 'MIT',
  adapter: 'hermes',
  binary: 'hermes',
  protocolArgs: ['acp'],
  versionArgs: ['--version'],
  install: { kind: 'bundled' },
  // The oldest Hermes the hub is known to work with: release v2026.9.14, the version the image
  // pins (packages/server/Dockerfile `HERMES_REF`) and every `*.real.test.ts` runs against. The
  // hub's own commands (`profile create --no-alias --clone-from`, `plugins … --no-enable`,
  // `kanban`, the TUI gateway's `llm.oneshot`, `session.steer`, `command.dispatch`, the API
  // server under `API_SERVER_KEY`) were read from that source; an older Hermes is not proven
  // (a person's own install may be any age, `docs/changes/2026-09-27-…-existing-hermes.md`).
  minimumVersion: '0.21.3',
  // Hermes's API server documents `GET /health` (docs/inspirations/hermes-agent.md §1).
  // Hermes does not take its keys from the process environment the hub spawns it with:
  // it reads its own `${HERMES_HOME}/.env`, which the hub writes (ADR 0010
  // §Propagation, `modules/models/propagation.ts`). Declaring none here keeps the two
  // paths from disagreeing.
  credentials: {},
  health: { kind: 'http', path: '/health' },
  defaultEndpoint: 'http://127.0.0.1:8642',
  capabilities: [
    'streaming',
    'tools',
    'approvals',
    'mcp',
    'skills',
    'memory',
    'channels',
    'resume',
    'jobs',
    'tasks',
    'plugins',
    // Its TUI gateway's own commands (decision §57): `session.compress`, `session.steer`,
    // and `command.dispatch` for `/goal`, `/plan`, `/learn` and a skill by name.
    'compress',
    'steer',
    'goals',
    'plans',
    'learn',
    'skill_commands',
  ],
  sections: [
    'jobs',
    'tasks',
    'channels',
    'skills',
    'plugins',
    'mcp',
    'memory',
    'journey',
    'settings',
  ],
  // Its TUI gateway reports every delegation and takes stop, steer and tail (§56).
  subagents: 'full',
};
