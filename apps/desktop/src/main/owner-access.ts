/**
 * The owner of the hub on this computer (DECISIONS §131), the app's half: "Forgot password?" on
 * the sign-in screen and signing the owner in without a password. Only in local mode, only for
 * the app's own window, and only through the IPC channel to the hub the app started — the hub
 * holds the grant and has no HTTP route for any of it (`hub-ipc.ts`, packages/server
 * `auth/local-owner.ts`).
 *
 * A reset starts with the operating system's own confirmation (`os-confirm.ts`). The app asks the
 * OS at most `PROMPTS_MAX` times in `PROMPTS_WINDOW_MS`; the hub limits the grants it opens as
 * well, and each grant is single use and lives five minutes.
 */
import type {
  DesktopOwnerAccessState,
  DesktopOwnerResult,
  DesktopRecoveryStart,
} from '../../../../packages/web/src/desktop/bridge-types.js';
import { OwnerRefusal } from '../shared/hub-ipc.js';
import type { ConfirmKind, ConfirmResult } from './os-confirm.js';

export const PROMPTS_MAX = 5;
export const PROMPTS_WINDOW_MS = 15 * 60_000;

type Ask = (
  request:
    | { op: 'begin'; method: string }
    | { op: 'finish'; grant: string; password: string; label: string }
    | { op: 'sign-in'; label: string }
    | { op: 'is-owner'; token: string },
) => Promise<unknown>;

export interface OwnerAccessOptions {
  /** The hub of local mode is running and this window talks to it. */
  isLocal(): boolean;
  /** Which prompt this computer has (cached by the caller), or null. */
  kind(): Promise<ConfirmKind | null>;
  /** Asks the OS; `reason` is shown in the prompt where the OS shows one. */
  confirm(reason: string): Promise<ConfirmResult>;
  reason(): string;
  ask: Ask;
  localSignIn(): boolean;
  setLocalSignIn(value: boolean): void;
  /** How the app's sign-in is labelled in the owner's list of sessions. */
  label(): string;
  now?: () => number;
}

interface TokenPairLike {
  access_token: string;
  refresh_token: string | null;
  expires_in: number;
  user: {
    id: string;
    username: string;
    display_name: string;
    role: string;
    default_profile: string;
  };
}

const isTokenPair = (value: unknown): value is TokenPairLike => {
  const v = value as Partial<TokenPairLike> | null;
  return (
    !!v &&
    typeof v.access_token === 'string' &&
    typeof v.expires_in === 'number' &&
    !!v.user &&
    typeof v.user.id === 'string'
  );
};

const refusal = (error: unknown): { ok: false; reason: string } => ({
  ok: false,
  reason: error instanceof OwnerRefusal ? error.reason : 'failed',
});

export class OwnerAccessService {
  private prompts: number[] = [];
  private grant: { value: string; expiresAt: number } | null = null;
  private busy = false;

  constructor(private readonly options: OwnerAccessOptions) {}

  private now(): number {
    return (this.options.now ?? Date.now)();
  }

  async state(): Promise<DesktopOwnerAccessState> {
    const local = this.options.isLocal();
    const kind = local ? await this.options.kind() : null;
    return {
      local,
      recovery: kind,
      localSignIn: this.options.localSignIn(),
    };
  }

  /** "Forgot password?": the OS confirms the person, then the hub opens one reset. */
  async beginRecovery(): Promise<DesktopRecoveryStart> {
    if (!this.options.isLocal()) return { ok: false, reason: 'not_local' };
    if (this.busy) return { ok: false, reason: 'busy' };
    const now = this.now();
    this.prompts = this.prompts.filter((at) => now - at < PROMPTS_WINDOW_MS);
    if (this.prompts.length >= PROMPTS_MAX) return { ok: false, reason: 'rate_limited' };
    this.busy = true;
    try {
      if (!(await this.options.kind())) return { ok: false, reason: 'unavailable' };
      this.prompts.push(now);
      const confirmed = await this.options.confirm(this.options.reason());
      if (!confirmed.ok) return { ok: false, reason: confirmed.reason };
      const opened = (await this.options.ask({ op: 'begin', method: confirmed.method })) as {
        grant: string;
        username: string;
        display_name: string | null;
        expires_at: string;
      };
      // The grant stays in the app; the page gets what it shows.
      this.grant = { value: opened.grant, expiresAt: Date.parse(opened.expires_at) };
      return {
        ok: true,
        username: opened.username,
        displayName: opened.display_name,
        expiresAt: opened.expires_at,
      };
    } catch (error) {
      return refusal(error);
    } finally {
      this.busy = false;
    }
  }

  /** The new password: the hub spends the grant and answers the app's new sign-in. */
  async finishRecovery(password: string): Promise<DesktopOwnerResult> {
    if (!this.options.isLocal()) return { ok: false, reason: 'not_local' };
    const grant = this.grant;
    if (!grant || this.now() >= grant.expiresAt) {
      this.grant = null;
      return { ok: false, reason: 'expired' };
    }
    try {
      const pair = await this.options.ask({
        op: 'finish',
        grant: grant.value,
        password,
        label: this.options.label(),
      });
      this.grant = null;
      return isTokenPair(pair) ? { ok: true, tokens: pair } : { ok: false, reason: 'failed' };
    } catch (error) {
      // A password the hub refused can be typed again; anything else spent the grant.
      if (!(error instanceof OwnerRefusal && error.reason === 'invalid_password'))
        this.grant = null;
      return refusal(error);
    }
  }

  cancelRecovery(): void {
    this.grant = null;
  }

  /** The owner, signed in without a password — only while the setting is on. */
  async signIn(): Promise<DesktopOwnerResult> {
    if (!this.options.isLocal()) return { ok: false, reason: 'not_local' };
    if (!this.options.localSignIn()) return { ok: false, reason: 'off' };
    try {
      const pair = await this.options.ask({ op: 'sign-in', label: this.options.label() });
      return isTokenPair(pair) ? { ok: true, tokens: pair } : { ok: false, reason: 'failed' };
    } catch (error) {
      return refusal(error);
    }
  }

  /**
   * Turning password-free sign-in on takes the owner, signed in (`token` is the page's access
   * token, checked by the hub); turning it off takes nobody.
   */
  async setLocalSignIn(value: boolean, token: string | null): Promise<DesktopOwnerAccessState> {
    if (value && !this.options.localSignIn()) {
      const owner =
        this.options.isLocal() && !!token
          ? await this.options.ask({ op: 'is-owner', token }).catch(() => false)
          : false;
      if (owner !== true) return { ...(await this.state()), refused: 'not_owner' };
    }
    if (value !== this.options.localSignIn()) this.options.setLocalSignIn(value);
    return this.state();
  }
}
