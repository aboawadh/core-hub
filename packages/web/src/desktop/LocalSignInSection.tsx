/**
 * This device → signing in on this computer (DECISIONS §131): whether the desktop app signs the
 * owner in to the hub it runs here without the password. Only the owner sees it, and only in
 * local mode. Turning it on is checked by the hub (the owner's own sign-in); off takes nothing.
 * Phones, browsers and other computers always need the password.
 */
import { useEffect, useState } from 'react';
import { useAuth } from '../auth/context.js';
import { useI18n } from '../i18n/context.js';
import { Notice, Switch } from '../ui/index.js';
import type { DesktopOwnerAccessBridge, DesktopOwnerAccessState } from './bridge-types.js';

export function LocalSignInSection({ bridge: given }: { bridge: DesktopOwnerAccessBridge }) {
  const { t } = useI18n();
  // Held once: the bridge does not change while the page lives.
  const [bridge] = useState(given);
  const { accessToken } = useAuth();
  const [state, setState] = useState<DesktopOwnerAccessState | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    bridge
      .get()
      .then((next) => live && setState(next))
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, [bridge]);

  if (failed || !state?.local) return null;

  const change = async (next: boolean) => {
    setBusy(true);
    try {
      setState(await bridge.setLocalSignIn(next, next ? await accessToken() : null));
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="flex flex-col gap-3" aria-labelledby="this-device-local-sign-in">
      <h3 id="this-device-local-sign-in" className="text-sm font-semibold">
        {t('this_device.local_sign_in.title')}
      </h3>
      <Switch
        checked={state.localSignIn}
        disabled={busy}
        onChange={(next) => void change(next)}
        label={t('this_device.local_sign_in.label')}
        hint={t('this_device.local_sign_in.hint')}
        testId="this-device-local-sign-in"
      />
      {state.refused === 'not_owner' && (
        <Notice tone="warning">{t('this_device.local_sign_in.refused')}</Notice>
      )}
      <p className="text-sm text-muted">
        {state.recovery
          ? t('this_device.local_sign_in.recovery_on')
          : t('this_device.local_sign_in.recovery_off')}
      </p>
    </section>
  );
}
