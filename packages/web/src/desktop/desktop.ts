/**
 * The web client inside the desktop app (apps/desktop, ADR 0009).
 *
 * The same bundle runs in a browser and in the desktop window. The difference is one
 * object the desktop app's preload puts on `window` before any script runs: when it is
 * there, this is the `desktop` surface of the navigation manifest (it adds the
 * "This device" settings tab), notices also reach the OS, and a `corehub://open/…` link
 * lands on its page. In a browser every function here is a no-op.
 */
import { isSession, sessionFromTokens, type SessionStore } from '../auth/store.js';
import type { DesktopBridge } from './bridge-types.js';

export type { DesktopBridge, DesktopState, DesktopNotice } from './bridge-types.js';

export function desktopBridge(): DesktopBridge | null {
  const candidate = (globalThis as { corehubDesktop?: unknown }).corehubDesktop;
  if (!candidate || typeof candidate !== 'object') return null;
  const bridge = candidate as Partial<DesktopBridge>;
  return bridge.surface === 'desktop' && typeof bridge.getState === 'function'
    ? (bridge as DesktopBridge)
    : null;
}

/**
 * A sign-in the desktop app got by pairing this computer is stored as the session before
 * the app renders, so the first screen is the chat and not the sign-in form.
 */
export async function adoptDesktopSession(store: SessionStore): Promise<void> {
  const bridge = desktopBridge();
  if (!bridge) return;
  try {
    const pending = await bridge.takePendingSession();
    if (isSession(pending)) store.save(pending);
  } catch {
    // Nothing handed over: the sign-in screen asks as usual.
  }
  if (store.read()) return;
  // The hub on this computer, with password-free sign-in on (DECISIONS §131): the app signs the
  // owner in. After a sign-out the sign-in screen stays until the window loads again, and offers
  // the same thing as a button.
  try {
    const owner = await bridge.ownerAccess?.get();
    if (!owner?.local || !owner.localSignIn) return;
    const result = await bridge.ownerAccess!.signIn();
    if (result.ok) store.save(sessionFromTokens(result.tokens));
  } catch {
    // An older app, or the hub not ready: the sign-in screen asks as usual.
  }
}
