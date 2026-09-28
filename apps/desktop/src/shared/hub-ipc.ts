/**
 * What the app and the hub it runs (local mode) say to each other over the child's IPC channel,
 * besides "listening" and "stop" (`main/local-hub.ts`, `hub/entry.ts`): the way in from outside
 * (DECISIONS §95). The hub asks (`relay`), the app answers (`relay-answer`) and also tells the
 * hub when the state changes on its own (`relay-state`), so a pairing started a second later
 * already gets the tunnel's address.
 */
import type { RelayChange, RelayState } from './relay.js';

export type HubToApp = { type: 'relay'; id: number; op: 'get' | 'set'; change?: RelayChange };

export type AppToHub =
  | { type: 'relay-answer'; id: number; ok: true; state: RelayState }
  | { type: 'relay-answer'; id: number; ok: false; reason: string | null; message: string }
  | { type: 'relay-state'; state: RelayState }
  | OwnerRequest;

/**
 * The owner on this computer (DECISIONS §131): the app asks, the hub answers. This channel is
 * the only way to these operations — the hub has no HTTP route for any of them.
 * - `begin`: the OS just confirmed the person (`method`); open a reset (the owner's username and
 *   a five-minute, single-use grant).
 * - `finish`: spend the grant on a new password; answers a sign-in (`TokenPair`).
 * - `sign-in`: the owner, signed in without a password (the app's setting is on).
 * - `is-owner`: whether a bearer is a live sign-in of the owner.
 */
export type OwnerRequest =
  | { type: 'owner'; id: number; op: 'begin'; method: string }
  | { type: 'owner'; id: number; op: 'finish'; grant: string; password: string; label: string }
  | { type: 'owner'; id: number; op: 'sign-in'; label: string }
  | { type: 'owner'; id: number; op: 'is-owner'; token: string };

export type OwnerAnswer =
  | { type: 'owner-answer'; id: number; ok: true; value: unknown }
  | { type: 'owner-answer'; id: number; ok: false; reason: string | null; message: string };

/** What the hub side needs: `LocalOwnerAccess` (packages/server `auth`). */
export interface OwnerAccessPort {
  beginRecovery(method: string): unknown;
  finishRecovery(grant: string, password: string, label: string): Promise<unknown>;
  signIn(label: string): Promise<unknown>;
  isOwnerSession(token: string): Promise<boolean>;
}

export function isOwnerAnswer(message: unknown): message is OwnerAnswer {
  const m = message as Partial<OwnerAnswer> | null;
  return !!m && m.type === 'owner-answer' && typeof m.id === 'number' && typeof m.ok === 'boolean';
}

const text = (value: unknown, max: number) =>
  typeof value === 'string' && value.length <= max ? value : null;

/** The hub's side: answers the app's `owner` requests over `process.send`. */
export function serveOwnerAccess(input: {
  access: OwnerAccessPort;
  send: (message: OwnerAnswer) => void;
  listen: (listener: (message: unknown) => void) => void;
  /** The refusal's reason (`no_owner`, `expired`, …), or null for an unexpected error. */
  reasonOf: (error: unknown) => string | null;
}): void {
  input.listen((raw) => {
    const m = raw as Partial<OwnerRequest> & Record<string, unknown>;
    if (!m || typeof m !== 'object' || m.type !== 'owner' || typeof m.id !== 'number') return;
    const id = m.id;
    const run = async (): Promise<unknown> => {
      if (m.op === 'begin') return input.access.beginRecovery(text(m.method, 32) ?? 'unknown');
      if (m.op === 'finish')
        return input.access.finishRecovery(
          text(m.grant, 128) ?? '',
          text(m.password, 4096) ?? '',
          text(m.label, 120) ?? 'desktop',
        );
      if (m.op === 'sign-in') return input.access.signIn(text(m.label, 120) ?? 'desktop');
      if (m.op === 'is-owner') return input.access.isOwnerSession(text(m.token, 4096) ?? '');
      throw new Error('unknown request');
    };
    run().then(
      (value) => input.send({ type: 'owner-answer', id, ok: true, value }),
      (error: unknown) =>
        input.send({
          type: 'owner-answer',
          id,
          ok: false,
          reason: input.reasonOf(error),
          message: error instanceof Error ? error.message : String(error),
        }),
    );
  });
}

export class OwnerRefusal extends Error {
  constructor(
    readonly reason: string,
    message: string,
  ) {
    super(message);
    this.name = 'OwnerRefusal';
  }
}

type OwnerAsk =
  | { op: 'begin'; method: string }
  | { op: 'finish'; grant: string; password: string; label: string }
  | { op: 'sign-in'; label: string }
  | { op: 'is-owner'; token: string };

/**
 * The app's side: asks the hub it runs and waits for the answer. `receive` takes every message
 * the hub sends and says whether it was one of these answers.
 */
export function ownerAccessClient(input: {
  send: (message: OwnerRequest) => boolean;
  timeoutMs?: number;
}) {
  let next = 1;
  const pending = new Map<
    number,
    { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >();
  return {
    ask(request: OwnerAsk): Promise<unknown> {
      return new Promise((resolve, reject) => {
        const id = next++;
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error('the hub on this computer did not answer'));
        }, input.timeoutMs ?? 30_000);
        timer.unref?.();
        pending.set(id, { resolve, reject, timer });
        if (!input.send({ type: 'owner', id, ...request } as OwnerRequest)) {
          pending.delete(id);
          clearTimeout(timer);
          reject(new OwnerRefusal('not_running', 'the hub on this computer is not running'));
        }
      });
    },
    receive(message: unknown): boolean {
      if (!isOwnerAnswer(message)) return false;
      const waiting = pending.get(message.id);
      if (!waiting) return true;
      pending.delete(message.id);
      clearTimeout(waiting.timer);
      if (message.ok) waiting.resolve(message.value);
      else if (message.reason) waiting.reject(new OwnerRefusal(message.reason, message.message));
      else waiting.reject(new Error(message.message));
      return true;
    },
  };
}

export function isHubToApp(message: unknown): message is HubToApp {
  const m = message as Partial<HubToApp> | null;
  return (
    !!m && m.type === 'relay' && typeof m.id === 'number' && (m.op === 'get' || m.op === 'set')
  );
}

/** The hub's side: a `RelayHost` (packages/server `devices/outside.ts`) over `process.send`. */
export function ipcRelayHost(input: {
  send: (message: HubToApp) => void;
  /** Subscribes to what the app sends; the hub entry passes `process.on('message')`. */
  listen: (listener: (message: unknown) => void) => void;
  refusal: (reason: string, message: string) => Error;
  timeoutMs?: number;
}) {
  let current: RelayState | null = null;
  let next = 1;
  const pending = new Map<
    number,
    { resolve: (state: RelayState) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >();
  input.listen((raw) => {
    const message = raw as AppToHub | null;
    if (!message || typeof message !== 'object') return;
    if (message.type === 'relay-state') {
      current = message.state;
      return;
    }
    if (message.type !== 'relay-answer') return;
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    clearTimeout(waiting.timer);
    if (message.ok) {
      current = message.state;
      waiting.resolve(message.state);
    } else if (message.reason) waiting.reject(input.refusal(message.reason, message.message));
    else waiting.reject(new Error(message.message));
  });
  const ask = (op: 'get' | 'set', change?: RelayChange) =>
    new Promise<RelayState>((resolve, reject) => {
      const id = next++;
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error('the desktop app did not answer'));
      }, input.timeoutMs ?? 15_000);
      timer.unref?.();
      pending.set(id, { resolve, reject, timer });
      input.send(change ? { type: 'relay', id, op, change } : { type: 'relay', id, op });
    });
  return {
    get: () => ask('get'),
    set: (change: RelayChange) => ask('set', change),
    current: () => current,
  };
}
