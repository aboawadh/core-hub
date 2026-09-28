/**
 * One messaging gateway per Hermes profile with a channel (`hermes-gateways.ts`), with a fake
 * spawner: which profiles get one, with what argv and environment, that a channel change
 * starts, restarts or stops it, that a crash is restarted, and that the runtime's Restart and
 * stop reach every one of them.
 */
import { EventEmitter } from 'node:events';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { PassThrough } from 'node:stream';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { capturingLogger, unreachableFetch } from '../../../tests/unit/helpers.js';
import { WHATSAPP_BRIDGE_PORT, whatsappBridgePort } from './channels.js';
import {
  ProfileGateways,
  activeCronJobs,
  saysOneGatewayPerHost,
  stopOrphanBridge,
  topologyForVersion,
  type RootGatewayHandle,
} from './hermes-gateways.js';
import { HermesRuntime, type SpawnedProcess, type Spawner } from './hermes-runtime.js';

const dirs: string[] = [];
function tempDir(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'corehub-gateways-'));
  dirs.push(dir);
  return dir;
}
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

let nextPid = 5000;
class FakeChild extends EventEmitter implements SpawnedProcess {
  pid = nextPid++;
  stdout = new PassThrough();
  stderr = new PassThrough();
  killed: NodeJS.Signals[] = [];
  kill(signal: NodeJS.Signals = 'SIGTERM'): boolean {
    this.killed.push(signal);
    setTimeout(() => this.emit('exit', 0, null), 1);
    return true;
  }
  crash(): void {
    this.emit('exit', 1, null);
  }
}

interface Spawn {
  command: string;
  args: string[];
  env: NodeJS.ProcessEnv;
  cwd: string;
  child: FakeChild;
  /** `config.yaml` as the gateway would have read it at start. */
  config: string;
}

function fakeSpawner() {
  const spawned: Spawn[] = [];
  const spawnImpl: Spawner = (command, args, options) => {
    const child = new FakeChild();
    let config = '';
    try {
      config = readFileSync(path.join(options.cwd, 'config.yaml'), 'utf8');
    } catch {
      // No file yet.
    }
    spawned.push({ command, args, env: options.env, cwd: options.cwd, child, config });
    return child;
  };
  const of = (profile: string) =>
    spawned.filter((entry) =>
      profile === 'default' ? !entry.args.includes('-p') : entry.args.includes(profile),
    );
  return { spawned, spawnImpl, of };
}

/** Hermes's root home with named profiles, each with the config given. */
function hermesRoot(profiles: Record<string, string>, rootConfig = ''): string {
  const root = tempDir();
  writeFileSync(path.join(root, 'config.yaml'), rootConfig);
  for (const [name, config] of Object.entries(profiles)) {
    mkdirSync(path.join(root, 'profiles', name), { recursive: true });
    writeFileSync(path.join(root, 'profiles', name, 'config.yaml'), config);
  }
  return root;
}

const TELEGRAM_ON = 'platforms:\n  telegram:\n    enabled: true\n    token: 1234:abc\n';
const NOTHING = 'model:\n  default: m\n';

/** A profile whose WhatsApp Hermes paired: the switch in `.env`, the phone in the session. */
function pairWhatsApp(home: string): void {
  writeFileSync(path.join(home, '.env'), 'WHATSAPP_ENABLED=true\nWHATSAPP_MODE=bot\n');
  const session = path.join(home, 'platforms', 'whatsapp', 'session');
  mkdirSync(session, { recursive: true });
  writeFileSync(
    path.join(session, 'creds.json'),
    JSON.stringify({ me: { id: '966500000000:12@s.whatsapp.net', name: 'Office' } }),
  );
}

function gatewaysOver(
  root: string,
  extra: Partial<ConstructorParameters<typeof ProfileGateways>[0]> = {},
) {
  const { logger, lines } = capturingLogger();
  const spawner = fakeSpawner();
  const gateways = new ProfileGateways({
    root: () => root,
    executable: () => '/opt/hermes/bin/hermes',
    env: () => ({
      PATH: '/usr/bin',
      OPENAI_API_KEY: 'sk-shared',
      // Must never reach a profile gateway: it would bind the default's port.
      API_SERVER_KEY: 'k'.repeat(40),
      API_SERVER_PORT: '8642',
      HERMES_HOME: root,
    }),
    spawnImpl: spawner.spawnImpl,
    log: logger,
    backoffMs: [5, 5],
    stopGraceMs: 50,
    rescanMs: 0,
    ...extra,
  });
  return { gateways, ...spawner, lines };
}

describe('a messaging gateway per profile', () => {
  it('starts one only for a profile with a channel switched on and able to sign in', async () => {
    const root = hermesRoot({
      sales: TELEGRAM_ON,
      quiet: NOTHING,
      // Switched on, never told how to sign in: nothing a gateway could serve.
      half: 'platforms:\n  discord:\n    enabled: true\n',
      manger: NOTHING,
    });
    pairWhatsApp(path.join(root, 'profiles', 'manger'));
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();

    expect(spawned.map((entry) => entry.args)).toEqual([
      ['-p', 'manger', 'gateway', 'run'],
      ['-p', 'sales', 'gateway', 'run'],
    ]);
    expect(gateways.status().map((entry) => [entry.profile, entry.channels])).toEqual([
      ['manger', ['whatsapp']],
      ['sales', ['telegram']],
    ]);
    await gateways.stopAll();
  });

  it("runs Hermes's own `-p <profile>` against the root, in the profile's folder, without the API server", async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    const [entry] = spawned;
    expect(entry?.command).toBe('/opt/hermes/bin/hermes');
    expect(entry?.cwd).toBe(path.join(root, 'profiles', 'sales'));
    expect(entry?.env.HERMES_HOME).toBe(root);
    // The shared keys reach it like every Hermes process (ADR 0010)…
    expect(entry?.env.OPENAI_API_KEY).toBe('sk-shared');
    // …the API server's never: only the default gateway serves 8642.
    expect(entry?.env.API_SERVER_KEY).toBeUndefined();
    expect(entry?.env.API_SERVER_PORT).toBeUndefined();
    expect(entry?.env.HERMES_DASHBOARD).toBe('0');
    await gateways.stopAll();
  });

  it("gives a second profile's WhatsApp bridge a port of its own before its gateway starts", async () => {
    const root = hermesRoot({ manger: NOTHING, sales: NOTHING });
    pairWhatsApp(path.join(root, 'profiles', 'manger'));
    pairWhatsApp(path.join(root, 'profiles', 'sales'));
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();

    const manger = whatsappBridgePort(path.join(root, 'profiles', 'manger'));
    const sales = whatsappBridgePort(path.join(root, 'profiles', 'sales'));
    expect(manger).not.toBe(WHATSAPP_BRIDGE_PORT);
    expect(sales).not.toBe(WHATSAPP_BRIDGE_PORT);
    expect(manger).not.toBe(sales);
    // Written where Hermes reads it, and already there when the gateway read its file.
    expect(spawned.find((entry) => entry.args.includes('manger'))?.config).toContain(
      `bridge_port: ${manger}`,
    );
    // Kept on the next start rather than handed out again.
    await gateways.restartAll();
    expect(whatsappBridgePort(path.join(root, 'profiles', 'manger'))).toBe(manger);
    await gateways.stopAll();
  });

  it("gives a WhatsApp profile its copy of the bridge on the image's dependencies before it starts", async () => {
    const source = path.join(tempDir(), 'image-bridge');
    mkdirSync(path.join(source, 'node_modules'), { recursive: true });
    writeFileSync(path.join(source, 'package.json'), '{"type":"module"}');
    writeFileSync(path.join(source, 'bridge.js'), '// bridge\n');
    writeFileSync(path.join(source, 'node_modules', '.hermes-pkg-hash'), 'abc');
    const root = hermesRoot({ manger: NOTHING, sales: TELEGRAM_ON });
    pairWhatsApp(path.join(root, 'profiles', 'manger'));
    const { gateways, spawned } = gatewaysOver(root, { whatsappBridge: source });
    await gateways.reconcile();
    expect(spawned).toHaveLength(2);
    const bridge = path.join(root, 'profiles', 'manger', 'scripts', 'whatsapp-bridge');
    expect(readFileSync(path.join(bridge, 'bridge.js'), 'utf8')).toBe('// bridge\n');
    expect(readFileSync(path.join(bridge, 'node_modules', '.hermes-pkg-hash'), 'utf8')).toBe('abc');
    // A profile without WhatsApp gets none.
    expect(existsSync(path.join(root, 'profiles', 'sales', 'scripts'))).toBe(false);
    await gateways.stopAll();
  });

  it('prepares the profile (the hub providers and its model) before every start', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const prepared: string[] = [];
    const { gateways, spawned } = gatewaysOver(root, {
      prepare: (profile, home) => {
        prepared.push(profile);
        writeFileSync(
          path.join(home, 'config.yaml'),
          `${TELEGRAM_ON}model:\n  provider: corehub-x\n`,
        );
      },
    });
    await gateways.reconcile();
    await gateways.restartAll();
    expect(prepared).toEqual(['sales', 'sales']);
    expect(spawned.every((entry) => entry.config.includes('provider: corehub-x'))).toBe(true);
    await gateways.stopAll();
  });

  it('follows a channel change: starts, restarts, then stops when none is left', async () => {
    const root = hermesRoot({ sales: NOTHING });
    const home = path.join(root, 'profiles', 'sales');
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    expect(spawned).toHaveLength(0);

    writeFileSync(path.join(home, 'config.yaml'), TELEGRAM_ON);
    await gateways.channelsChanged('sales');
    expect(spawned).toHaveLength(1);

    // Edited while it runs: a gateway reads its channels at start, so it starts again.
    await gateways.channelsChanged('sales');
    await vi.waitFor(() => expect(spawned).toHaveLength(2));
    expect(spawned[0]?.child.killed).toEqual(['SIGTERM']);

    writeFileSync(path.join(home, 'config.yaml'), 'platforms:\n  telegram:\n    enabled: false\n');
    await gateways.channelsChanged('sales');
    expect(spawned[1]?.child.killed).toEqual(['SIGTERM']);
    expect(gateways.status()).toEqual([]);
    // The default profile's gateway is not this supervisor's.
    await gateways.channelsChanged('default');
    expect(spawned).toHaveLength(2);
    await gateways.stopAll();
  });

  it('restarts a crashed gateway with backoff, and says it is in error meanwhile', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned, lines } = gatewaysOver(root, { backoffMs: [40] });
    await gateways.reconcile();
    spawned[0]!.child.crash();
    expect(gateways.status()[0]).toMatchObject({
      profile: 'sales',
      state: 'error',
      restarts: 1,
      lastError: 'hermes gateway exited (code 1)',
    });
    await vi.waitFor(() => expect(spawned).toHaveLength(2));
    expect(gateways.status()[0]?.state).toBe('starting');
    expect(lines.some((line) => line.profile === 'sales' && /crashed/.test(String(line.msg)))).toBe(
      true,
    );
    await gateways.stopAll();
  });

  it("says `running` once Hermes's own state file names this process running", async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    const home = path.join(root, 'profiles', 'sales');
    // A record another process left behind does not count.
    writeFileSync(
      path.join(home, 'gateway_state.json'),
      JSON.stringify({ pid: 1, gateway_state: 'running', platforms: {} }),
    );
    expect(gateways.status()[0]?.state).toBe('starting');
    writeFileSync(
      path.join(home, 'gateway_state.json'),
      JSON.stringify({
        pid: spawned[0]!.child.pid,
        gateway_state: 'running',
        platforms: { telegram: { state: 'connected' } },
      }),
    );
    expect(gateways.status()[0]?.state).toBe('running');
    expect(gateways.record('sales')?.platforms.telegram?.state).toBe('connected');
    await gateways.stopAll();
  });

  it('holds a gateway down while a channel is changed, and starts it again when still needed', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    const seen: number[] = [];
    await gateways.withStopped('sales', () => {
      seen.push(spawned.length);
      expect(spawned[0]?.child.killed).toEqual(['SIGTERM']);
    });
    expect(seen).toEqual([1]);
    expect(spawned).toHaveLength(2);
    await gateways.stopAll();
  });
});

function binDirWithHermes(): string {
  const dir = tempDir();
  const file = path.join(dir, 'hermes');
  writeFileSync(file, '#!/bin/sh\nexit 0\n');
  chmodSync(file, 0o755);
  return dir;
}

describe('the runtime runs them beside the default gateway', () => {
  async function managed(
    profiles: Record<string, string>,
    extra: { whatsappBridge?: string; before?: (root: string) => void } = {},
  ) {
    const dataDir = tempDir();
    const root = path.join(dataDir, 'hermes');
    mkdirSync(root, { recursive: true });
    extra.before?.(root);
    for (const [name, config] of Object.entries(profiles)) {
      mkdirSync(path.join(root, 'profiles', name), { recursive: true });
      writeFileSync(path.join(root, 'profiles', name, 'config.yaml'), config);
    }
    const { logger } = capturingLogger();
    const spawner = fakeSpawner();
    const prepared: string[] = [];
    const runtime = new HermesRuntime({
      dataDir,
      host: { pathValue: binDirWithHermes() },
      log: logger,
      fetchImpl: unreachableFetch,
      spawnImpl: spawner.spawnImpl,
      healthIntervalMs: 0,
      prepareGateway: (profile) => prepared.push(profile),
      ...(extra.whatsappBridge ? { whatsappBridge: extra.whatsappBridge } : {}),
    });
    await runtime.start();
    await vi.waitFor(() =>
      expect(runtime.gateways().length).toBe(
        1 + Object.keys(profiles).filter((name) => profiles[name] === TELEGRAM_ON).length,
      ),
    );
    return { runtime, root, prepared, ...spawner };
  }

  it('starts the named profiles with a channel at boot, and lists every gateway', async () => {
    const { runtime, of, prepared } = await managed({ sales: TELEGRAM_ON, quiet: NOTHING });
    expect(of('default')).toHaveLength(1);
    expect(of('sales')).toHaveLength(1);
    expect(of('quiet')).toHaveLength(0);
    // The default one serves the API server; its providers are prepared as well.
    expect(of('default')[0]?.env.API_SERVER_KEY).toBeTruthy();
    expect(prepared).toEqual(expect.arrayContaining(['default', 'sales']));
    expect(runtime.gateways().map((entry) => entry.profile)).toEqual(['default', 'sales']);
    await runtime.stop();
    expect(of('sales')[0]?.child.killed).toEqual(['SIGTERM']);
  });

  it('gives the root home its bridge copy before the default gateway serves WhatsApp', async () => {
    const source = path.join(tempDir(), 'image-bridge');
    mkdirSync(path.join(source, 'node_modules'), { recursive: true });
    writeFileSync(path.join(source, 'package.json'), '{"type":"module"}');
    writeFileSync(path.join(source, 'bridge.js'), '// bridge\n');
    const { runtime, root, of } = await managed(
      {},
      { whatsappBridge: source, before: (home) => pairWhatsApp(home) },
    );
    expect(of('default')).toHaveLength(1);
    const bridge = path.join(root, 'scripts', 'whatsapp-bridge');
    expect(readFileSync(path.join(bridge, 'bridge.js'), 'utf8')).toBe('// bridge\n');
    await runtime.stop();
  });

  it('Restart restarts every messaging gateway, not only the default one', async () => {
    const { runtime, of } = await managed({ sales: TELEGRAM_ON });
    await runtime.restart();
    await vi.waitFor(() => {
      expect(of('default')).toHaveLength(2);
      expect(of('sales')).toHaveLength(2);
    });
    await runtime.stop();
  });

  it('holds the default gateway down while its WhatsApp is unlinked, then starts it again', async () => {
    const { runtime, of } = await managed({});
    const during: number[] = [];
    await runtime.withGatewayStopped('default', async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
      during.push(of('default').length);
    });
    expect(during).toEqual([1]);
    await vi.waitFor(() => expect(of('default')).toHaveLength(2));
    await runtime.stop();
  });
});

describe('a bridge left behind', () => {
  it('is only stopped when the pid file names a node process of this very session', () => {
    const session = tempDir();
    // Our own pid is a process that exists but is not a bridge of this session.
    writeFileSync(path.join(session, 'bridge.pid'), `${process.pid}\n`);
    expect(stopOrphanBridge(session)).toBe(false);
    writeFileSync(path.join(session, 'bridge.pid'), 'not-a-pid');
    expect(stopOrphanBridge(session)).toBe(false);
    expect(stopOrphanBridge(path.join(session, 'nothing-here'))).toBe(false);
  });
});

/** Hermes's own `cron/jobs.json` in a profile, as its scheduler writes it. */
function cronJobs(home: string, jobs: Array<Record<string, unknown>>): void {
  mkdirSync(path.join(home, 'cron'), { recursive: true });
  writeFileSync(path.join(home, 'cron', 'jobs.json'), JSON.stringify({ jobs }));
}
const job = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  name: id,
  enabled: true,
  state: 'scheduled',
  schedule: { kind: 'cron', expr: '0 9 * * *' },
  ...extra,
});

describe("a profile's scheduled jobs need its gateway too", () => {
  it("counts the jobs that will still fire, by Hermes's own rule", () => {
    const home = tempDir();
    expect(activeCronJobs(home)).toBe(0);
    cronJobs(home, [
      job('daily'),
      job('paused', { enabled: false, state: 'paused', paused_at: '2026-09-24T10:00:00Z' }),
      // Half-paused: Hermes never fires it either.
      job('marker', { paused_at: '2026-09-24T10:00:00Z' }),
      job('done', { state: 'completed', schedule: { kind: 'once' } }),
      job('stuck-once', { state: 'error', schedule: { kind: 'once' } }),
      // A recurring job in `error` still has occurrences.
      job('stuck-cron', { state: 'error' }),
    ]);
    expect(activeCronJobs(home)).toBe(2);
  });

  it('starts a gateway for a profile with only a job, and stops it when the last one is paused', async () => {
    const root = hermesRoot({ reports: NOTHING, quiet: NOTHING });
    const home = path.join(root, 'profiles', 'reports');
    cronJobs(home, [job('morning-brief')]);
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    expect(spawned.map((entry) => entry.args)).toEqual([['-p', 'reports', 'gateway', 'run']]);
    expect(gateways.status()[0]).toMatchObject({ profile: 'reports', channels: [], cronJobs: 1 });

    cronJobs(home, [job('morning-brief', { enabled: false, state: 'paused' })]);
    await gateways.reconcile();
    expect(spawned[0]?.child.killed).toEqual(['SIGTERM']);
    expect(gateways.status()).toEqual([]);
    await gateways.stopAll();
  });

  it('notices a job scheduled behind its back, on the next check', async () => {
    const root = hermesRoot({ reports: NOTHING });
    const { gateways, spawned } = gatewaysOver(root, { rescanMs: 20 });
    gateways.watch();
    expect(spawned).toHaveLength(0);
    // An agent scheduled it in a chat: Hermes wrote the file, the hub heard nothing.
    cronJobs(path.join(root, 'profiles', 'reports'), [job('weekly')]);
    await vi.waitFor(() => expect(spawned).toHaveLength(1));
    await gateways.stopAll();
  });

  it('keeps a profile gateway with a job when its last channel is switched off', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const home = path.join(root, 'profiles', 'sales');
    cronJobs(home, [job('daily')]);
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    writeFileSync(path.join(home, 'config.yaml'), 'platforms:\n  telegram:\n    enabled: false\n');
    await gateways.channelsChanged('sales');
    // Restarted (its channels changed), not stopped: the job still needs it.
    await vi.waitFor(() => expect(spawned).toHaveLength(2));
    expect(gateways.status()[0]).toMatchObject({ channels: [], cronJobs: 1 });
    await gateways.stopAll();
  });
});

describe("Hermes's one kanban board", () => {
  it('is dispatched by the default gateway only: a profile gateway is told not to', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned } = gatewaysOver(root);
    await gateways.reconcile();
    expect(spawned[0]?.env.HERMES_KANBAN_DISPATCH_IN_GATEWAY).toBe('false');
    await gateways.stopAll();
  });

  it('leaves the default gateway dispatching', async () => {
    const dataDir = tempDir();
    const { logger } = capturingLogger();
    const spawner = fakeSpawner();
    const runtime = new HermesRuntime({
      dataDir,
      host: { pathValue: binDirWithHermes() },
      log: logger,
      fetchImpl: unreachableFetch,
      spawnImpl: spawner.spawnImpl,
      healthIntervalMs: 0,
      gatewayRescanMs: 0,
    });
    await runtime.start();
    expect(spawner.of('default')[0]?.env.HERMES_KANBAN_DISPATCH_IN_GATEWAY).toBeUndefined();
    await runtime.stop();
  });
});

/** What Hermes v2026.9.21+ prints before it exits 75 (`gateway/host_attach.py` §attach_message). */
const ATTACH_MESSAGE = [
  "✓ The host gateway already serves profile 'sales' — nothing to start.",
  "  PID 4242 (launched by profile 'default'; serves: default, sales)",
  '  One gateway per host serves every profile; manage it with `hermes -p default gateway restart`.',
];

/** A gateway that prints `lines` and then exits with `code`, as the real one would. */
async function sayAndExit(child: FakeChild, lines: string[], code: number): Promise<void> {
  for (const line of lines) child.stdout.write(`${line}\n`);
  await new Promise((resolve) => setTimeout(resolve, 10));
  child.emit('exit', code, null);
}

/** The root gateway the hub runs, scripted: its pid, and a record of each restart asked for. */
function fakeRoot(root: string, pid = 4242) {
  const restarts: number[] = [];
  const handle: RootGatewayHandle & { pid: number } = {
    pid,
    status: () => ({ state: 'running', pid: handle.pid, startedAt: 1 }),
    restart: async () => {
      restarts.push(handle.pid);
      handle.pid += 1;
    },
  };
  /** What Hermes writes about it (`gateway_state.json`), `served` omitted for an older Hermes. */
  const record = (served: string[] | undefined, platforms: Record<string, unknown> = {}) =>
    writeFileSync(
      path.join(root, 'gateway_state.json'),
      JSON.stringify({
        pid: handle.pid,
        gateway_state: 'running',
        platforms,
        ...(served ? { served_profiles: served } : {}),
      }),
    );
  return { handle, restarts, record };
}

describe('one gateway per host (Hermes v2026.9.21, 0.21.4, and later)', () => {
  it('reads the topology from the version, build metadata ignored', () => {
    expect(topologyForVersion('0.21.3')).toBe('per-profile');
    expect(topologyForVersion('0.21.3+12.gabc')).toBe('per-profile');
    expect(topologyForVersion('0.21.4')).toBe('one-per-host');
    expect(topologyForVersion('0.21.5+3397.gd25bbd0')).toBe('one-per-host');
    expect(topologyForVersion('0.22.0')).toBe('one-per-host');
    expect(topologyForVersion(null)).toBeNull();
    expect(topologyForVersion('unknown')).toBeNull();
    expect(saysOneGatewayPerHost(ATTACH_MESSAGE.join('\n'))).toBe(true);
    expect(saysOneGatewayPerHost('❌ A gateway already owns this host and will not serve it')).toBe(
      true,
    );
    expect(saysOneGatewayPerHost('Traceback: something else')).toBe(false);
  });

  it('an older Hermes keeps a gateway per profile, exactly as before', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned } = gatewaysOver(root, { rootGateway: fakeRoot(root).handle });
    await gateways.noteVersion('0.21.3');
    await gateways.reconcile();
    expect(gateways.topology()).toBe('per-profile');
    expect(spawned.map((entry) => entry.args)).toEqual([['-p', 'sales', 'gateway', 'run']]);
    await gateways.stopAll();
  });

  it('a newer one starts no profile gateway; the root one serves the profile and its row follows it', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON, reports: NOTHING, quiet: NOTHING });
    cronJobs(path.join(root, 'profiles', 'reports'), [job('morning-brief')]);
    const rootGateway = fakeRoot(root);
    const rescans: string[] = [];
    const { gateways, spawned } = gatewaysOver(root, {
      rootGateway: rootGateway.handle,
      rescanProfiles: async (home) => void rescans.push(home),
    });
    await gateways.noteVersion('0.21.5');
    rootGateway.record(['default', 'reports', 'sales', 'quiet'], {
      telegram: { state: 'connected' },
      'sales:telegram': { state: 'connected' },
    });
    await gateways.reconcile();
    expect(spawned).toHaveLength(0);
    expect(rootGateway.restarts).toEqual([]);
    expect(gateways.status()).toEqual([
      expect.objectContaining({ profile: 'reports', state: 'running', pid: 4242, cronJobs: 1 }),
      expect.objectContaining({
        profile: 'sales',
        state: 'running',
        pid: 4242,
        lastError: null,
        channels: ['telegram'],
      }),
    ]);
    // Hermes's record of the root gateway, narrowed to the profile's own platforms.
    expect(gateways.record('sales')?.platforms).toEqual({
      telegram: { state: 'connected', errorMessage: null },
    });
    // A channel changed in the profile: the root gateway is asked to rescan, never restarted.
    await gateways.channelsChanged('sales');
    expect(rescans).toEqual([root]);
    expect(rootGateway.restarts).toEqual([]);
    expect(spawned).toHaveLength(0);
    await gateways.stopAll();
  });

  it('restarts a root gateway that came up serving the default profile alone, once', async () => {
    const root = hermesRoot({ sales: NOTHING });
    const rootGateway = fakeRoot(root);
    const { gateways, spawned } = gatewaysOver(root, {
      rootGateway: rootGateway.handle,
      rescanProfiles: async () => null,
    });
    await gateways.noteVersion('0.21.4');
    // It started with only the default profile: Hermes did not multiplex.
    rootGateway.record([]);
    await gateways.reconcile();
    expect(rootGateway.restarts).toEqual([]);

    // Telegram linked in «sales»: Hermes decides what it serves when it starts, so it restarts.
    writeFileSync(path.join(root, 'profiles', 'sales', 'config.yaml'), TELEGRAM_ON);
    await gateways.channelsChanged('sales');
    expect(rootGateway.restarts).toEqual([4242]);
    expect(spawned).toHaveLength(0);

    // It came up and still serves the default profile alone (Hermes has a reason): the next
    // check does not restart it again, and the row says so.
    rootGateway.record([]);
    await gateways.reconcile();
    expect(rootGateway.restarts).toEqual([4242]);
    expect(gateways.status()[0]).toMatchObject({ profile: 'sales', state: 'error' });
    expect(gateways.status()[0]?.lastError).toMatch(/does not serve this profile/);

    // Once it serves the profile, the row is running.
    rootGateway.record(['default', 'sales']);
    expect(gateways.status()[0]).toMatchObject({
      profile: 'sales',
      state: 'running',
      lastError: null,
    });
    await gateways.stopAll();
  });

  it('a profile gateway that exits 75 saying so switches to the root one, without a restart loop', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const rootGateway = fakeRoot(root);
    rootGateway.record(['default', 'sales']);
    const { gateways, spawned } = gatewaysOver(root, {
      rootGateway: rootGateway.handle,
      rescanProfiles: async () => null,
      backoffMs: [5],
    });
    // Version unknown: the old way first.
    await gateways.reconcile();
    expect(spawned).toHaveLength(1);
    await sayAndExit(spawned[0]!.child, ATTACH_MESSAGE, 75);
    await vi.waitFor(() => expect(gateways.topology()).toBe('one-per-host'));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(spawned).toHaveLength(1);
    expect(gateways.status()).toEqual([
      expect.objectContaining({ profile: 'sales', state: 'running', lastError: null }),
    ]);
    await gateways.stopAll();
  });

  it('a plain exit 75 (Hermes asking its supervisor for a restart) is still restarted', async () => {
    const root = hermesRoot({ sales: TELEGRAM_ON });
    const { gateways, spawned } = gatewaysOver(root, { backoffMs: [5] });
    await gateways.reconcile();
    await sayAndExit(spawned[0]!.child, ['Gateway restart requested'], 75);
    await vi.waitFor(() => expect(spawned).toHaveLength(2));
    expect(gateways.topology()).toBe('per-profile');
    await gateways.stopAll();
  });
});

describe('the runtime on a Hermes with one gateway per host', () => {
  function personalHome(): string {
    const home = tempDir();
    mkdirSync(path.join(home, '.hermes'), { recursive: true });
    return home;
  }

  async function managed(options: {
    profiles?: Record<string, string>;
    version?: string | null;
    inherited?: NodeJS.ProcessEnv;
  }) {
    const dataDir = tempDir();
    const root = path.join(dataDir, 'hermes');
    mkdirSync(root, { recursive: true });
    for (const [name, config] of Object.entries(options.profiles ?? {})) {
      mkdirSync(path.join(root, 'profiles', name), { recursive: true });
      writeFileSync(path.join(root, 'profiles', name, 'config.yaml'), config);
    }
    const { logger } = capturingLogger();
    const spawner = fakeSpawner();
    const versions: string[] = [];
    const runtime = new HermesRuntime({
      dataDir,
      host: {
        pathValue: binDirWithHermes(),
        ...(options.inherited ? { inherited: options.inherited } : {}),
      },
      log: logger,
      fetchImpl: unreachableFetch,
      spawnImpl: spawner.spawnImpl,
      healthIntervalMs: 0,
      gatewayRescanMs: 0,
      gatewayBackoffMs: [5],
      rescanProfiles: async () => null,
      hermesVersion: async (hermes) => {
        versions.push(hermes);
        return options.version ?? null;
      },
    });
    await runtime.start();
    await vi.waitFor(() => expect(versions).toHaveLength(1));
    return { runtime, root, ...spawner };
  }

  it('starts no profile gateway beside the default one on 0.21.5, and lists the profile', async () => {
    const { runtime, of } = await managed({ profiles: { sales: TELEGRAM_ON }, version: '0.21.5' });
    await vi.waitFor(() => expect(runtime.profileGateways.topology()).toBe('one-per-host'));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(of('default')).toHaveLength(1);
    expect(of('sales')).toHaveLength(0);
    expect(runtime.gateways().map((entry) => entry.profile)).toEqual(['default', 'sales']);
    expect(runtime.gateways()[1]?.pid).toBe(of('default')[0]?.child.pid);
    // The Performance screen lists the one process once.
    expect(runtime.processes().filter((entry) => entry.kind === 'gateway')).toHaveLength(1);
    await runtime.stop();
  });

  it('still starts a gateway per profile on 0.21.3', async () => {
    const { runtime, of } = await managed({ profiles: { sales: TELEGRAM_ON }, version: '0.21.3' });
    await vi.waitFor(() => expect(of('sales')).toHaveLength(1));
    expect(runtime.profileGateways.topology()).toBe('per-profile');
    expect(of('default')[0]?.env.HERMES_GATEWAY_LOCK_DIR).toBeUndefined();
    await runtime.stop();
  });

  it("takes a host lock of its own when the person's own gateway owns the host, and starts again at once", async () => {
    const home = personalHome();
    const { runtime, root, of } = await managed({ inherited: { HOME: home } });
    const first = of('default')[0]!;
    expect(first.env.HERMES_GATEWAY_LOCK_DIR).toBeUndefined();
    // The person's gateway for ~/.hermes holds the host: Hermes attaches and exits 75.
    await sayAndExit(
      first.child,
      [
        "✓ The host gateway already serves profile 'default' — nothing to start.",
        '  One gateway per host serves every profile; manage it with `hermes -p default gateway restart`.',
      ],
      75,
    );
    await vi.waitFor(() => expect(of('default')).toHaveLength(2));
    expect(of('default')[1]?.env.HERMES_GATEWAY_LOCK_DIR).toBe(path.join(root, 'gateway-locks'));
    expect(runtime.cliEnv().HERMES_GATEWAY_LOCK_DIR).toBe(path.join(root, 'gateway-locks'));
    expect(runtime.status()).toMatchObject({ state: 'starting', lastError: null });
    expect(runtime.profileGateways.topology()).toBe('one-per-host');

    // Once isolated, another exit 75 is somebody the hub cannot move: shown, restarted with backoff.
    await sayAndExit(
      of('default')[1]!.child,
      ['❌ Another gateway already owns this host: PID 1'],
      75,
    );
    expect(runtime.status().lastError).toMatch(/code 75.*already owns this host/);
    await runtime.stop();
  });

  it("on 0.21.5 beside a person's own Hermes, reads the version first and starts with its own host lock", async () => {
    const { root, of, runtime } = await managed({
      inherited: { HOME: personalHome() },
      version: '0.21.5',
    });
    expect(runtime.profileGateways.topology()).toBe('one-per-host');
    expect(of('default')).toHaveLength(1);
    expect(of('default')[0]?.env.HERMES_GATEWAY_LOCK_DIR).toBe(path.join(root, 'gateway-locks'));
    await runtime.stop();
  });

  it("never moves the host lock on 0.21.3, even beside a person's own Hermes", async () => {
    const { of, runtime } = await managed({
      inherited: { HOME: personalHome() },
      version: '0.21.3',
    });
    expect(of('default')[0]?.env.HERMES_GATEWAY_LOCK_DIR).toBeUndefined();
    expect(runtime.cliEnv().HERMES_GATEWAY_LOCK_DIR).toBeUndefined();
    await runtime.stop();
  });
});
