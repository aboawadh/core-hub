/**
 * The owner of the hub on this computer, on the sign-in screen (DECISIONS §131). Only inside the
 * desktop app, and only while its window talks to the hub the app runs itself:
 * - «نسيت كلمة المرور؟» / "Forgot password?" — the operating system confirms the person (Touch
 *   ID or the Mac's password, Windows Hello, polkit), then the owner's username is shown and a
 *   new password is set; every other sign-in of the owner ends;
 * - «ادخل على هذا الحاسوب» / "Sign in on this computer" — without the password, while the
 *   owner's setting in This device is on.
 * A browser, or the app talking to a hub elsewhere, shows neither.
 */
import { useEffect, useState, type FormEvent } from 'react';
import { useAuth } from '../auth/context.js';
import { useI18n } from '../i18n/context.js';
import { Button, Field, Input, Notice } from '../ui/index.js';
import type { DesktopOwnerAccessBridge, DesktopOwnerAccessState } from './bridge-types.js';
import { desktopBridge } from './desktop.js';

/** The same rule as first-run setup (`Password` in the hub: 8 to 1024 characters). */
export const RECOVERY_MIN_PASSWORD = 8;
const MAX_PASSWORD = 1024;

const KNOWN_REASONS = new Set([
  'cancelled',
  'unavailable',
  'rate_limited',
  'expired',
  'invalid_password',
  'no_owner',
  'owner_disabled',
  'busy',
]);

/** A refusal's sentence; anything the page does not know reads as "something went wrong". */
export function ownerReasonKey(reason: string): string {
  return `login.recovery.errors.${KNOWN_REASONS.has(reason) ? reason : 'failed'}`;
}

/** The bridge and its state, when this window talks to the hub on this computer. */
export function useLocalOwner(): {
  bridge: DesktopOwnerAccessBridge;
  state: DesktopOwnerAccessState;
} | null {
  // Read once: the bridge does not change while the page lives.
  const [bridge] = useState(() => desktopBridge()?.ownerAccess ?? null);
  const [state, setState] = useState<DesktopOwnerAccessState | null>(null);
  useEffect(() => {
    if (!bridge) return;
    let live = true;
    bridge
      .get()
      .then((next) => live && setState(next))
      .catch(() => live && setState(null));
    return () => {
      live = false;
    };
  }, [bridge]);
  return bridge && state?.local ? { bridge, state } : null;
}

export interface RecoveryOpened {
  username: string;
  displayName: string | null;
}

/** The two local actions under the sign-in form. */
export function LocalOwnerActions({
  owner,
  onOpened,
  onSignedIn,
}: {
  owner: NonNullable<ReturnType<typeof useLocalOwner>>;
  onOpened(opened: RecoveryOpened): void;
  onSignedIn(): void;
}) {
  const { t } = useI18n();
  const { adoptTokens } = useAuth();
  const [busy, setBusy] = useState<'forgot' | 'sign-in' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const forgot = async () => {
    setBusy('forgot');
    setError(null);
    try {
      const result = await owner.bridge.beginRecovery();
      if (result.ok) onOpened({ username: result.username, displayName: result.displayName });
      else setError(ownerReasonKey(result.reason));
    } catch {
      setError(ownerReasonKey('failed'));
    } finally {
      setBusy(null);
    }
  };

  const signInHere = async () => {
    setBusy('sign-in');
    setError(null);
    try {
      const result = await owner.bridge.signIn();
      if (result.ok) {
        adoptTokens(result.tokens);
        onSignedIn();
      } else setError(ownerReasonKey(result.reason));
    } catch {
      setError(ownerReasonKey('failed'));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="flex flex-col gap-2" data-testid="local-owner-actions">
      {owner.state.localSignIn && (
        <Button
          variant="secondary"
          size="lg"
          loading={busy === 'sign-in'}
          disabled={busy !== null}
          onClick={() => void signInHere()}
          data-testid="local-sign-in"
        >
          {t('login.local_sign_in')}
        </Button>
      )}
      {owner.state.recovery && (
        <Button
          variant="ghost"
          size="sm"
          loading={busy === 'forgot'}
          disabled={busy !== null}
          onClick={() => void forgot()}
          data-testid="forgot-password"
        >
          {busy === 'forgot' ? t('login.forgot_waiting') : t('login.forgot')}
        </Button>
      )}
      {owner.state.recovery && busy === 'forgot' && (
        <p className="text-xs text-muted" data-testid="forgot-hint">
          {t(`login.forgot_hint.${owner.state.recovery}`)}
        </p>
      )}
      {error && (
        <Notice tone="danger">
          <span data-testid="local-owner-error">
            {t(error, { min: String(RECOVERY_MIN_PASSWORD) })}
          </span>
        </Notice>
      )}
    </div>
  );
}

/** After the OS confirmed the person: the owner's username, and the new password twice. */
export function RecoveryForm({
  owner,
  opened,
  onCancel,
  onDone,
}: {
  owner: NonNullable<ReturnType<typeof useLocalOwner>>;
  opened: RecoveryOpened;
  onCancel(): void;
  onDone(): void;
}) {
  const { t } = useI18n();
  const { adoptTokens } = useAuth();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [problem, setProblem] = useState<'mismatch' | 'too_short' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (password.length < RECOVERY_MIN_PASSWORD || password.length > MAX_PASSWORD) {
      setProblem('too_short');
      return;
    }
    if (password !== confirm) {
      setProblem('mismatch');
      return;
    }
    setProblem(null);
    setError(null);
    setBusy(true);
    try {
      const result = await owner.bridge.finishRecovery(password);
      if (result.ok) {
        adoptTokens(result.tokens);
        onDone();
      } else setError(ownerReasonKey(result.reason));
    } catch {
      setError(ownerReasonKey('failed'));
    } finally {
      setBusy(false);
    }
  };

  const cancel = () => {
    void owner.bridge.cancelRecovery().catch(() => undefined);
    onCancel();
  };

  return (
    <form
      onSubmit={(e) => void onSubmit(e)}
      className="flex flex-col gap-3"
      aria-labelledby="recovery-title"
      data-testid="recovery-form"
    >
      <h2 id="recovery-title" className="text-base font-semibold">
        {t('login.recovery.title')}
      </h2>
      <p className="text-sm text-muted">{t('login.recovery.confirmed')}</p>
      <div className="flex flex-col gap-1">
        <span className="text-sm font-medium">{t('login.recovery.username_label')}</span>
        {/* Monospace and isolated: an extra dot or letter in the name must be visible. */}
        <code
          dir="auto"
          className="self-start rounded-md border border-line bg-surface-2 px-2 py-1 font-mono text-base select-all"
          data-testid="recovery-username"
        >
          {opened.username}
        </code>
        <span className="text-xs text-muted">{t('login.recovery.username_hint')}</span>
      </div>
      <Field
        label={t('login.recovery.password')}
        hint={t('login.recovery.password_hint', { min: String(RECOVERY_MIN_PASSWORD) })}
        {...(problem === 'too_short'
          ? {
              error: t('login.recovery.too_short', { min: String(RECOVERY_MIN_PASSWORD) }),
            }
          : {})}
      >
        {(props) => (
          <Input
            {...props}
            name="new-password"
            type="password"
            autoComplete="new-password"
            minLength={RECOVERY_MIN_PASSWORD}
            maxLength={MAX_PASSWORD}
            invalid={problem === 'too_short'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoFocus
          />
        )}
      </Field>
      <Field
        label={t('login.recovery.confirm')}
        {...(problem === 'mismatch' ? { error: t('login.recovery.mismatch') } : {})}
      >
        {(props) => (
          <Input
            {...props}
            name="confirm-new-password"
            type="password"
            autoComplete="new-password"
            minLength={RECOVERY_MIN_PASSWORD}
            maxLength={MAX_PASSWORD}
            invalid={problem === 'mismatch'}
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            required
          />
        )}
      </Field>
      <Notice tone="warning">
        <span data-testid="recovery-sign-out-notice">{t('login.recovery.signs_out')}</span>
      </Notice>
      {error && (
        <Notice tone="danger">
          <span data-testid="recovery-error">
            {t(error, { min: String(RECOVERY_MIN_PASSWORD) })}
          </span>
        </Notice>
      )}
      <Button type="submit" variant="primary" size="lg" loading={busy}>
        {busy ? t('login.recovery.saving') : t('login.recovery.save')}
      </Button>
      <Button variant="ghost" onClick={cancel} disabled={busy}>
        {t('login.recovery.cancel')}
      </Button>
    </form>
  );
}
