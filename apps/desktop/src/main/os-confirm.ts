/**
 * "Is this really the person at this computer?" — asked of the operating system itself, the way
 * an installer asks before it changes the system (DECISIONS §131). The desktop app asks before it
 * opens a password reset for the owner of the hub it runs on this computer.
 *
 * No native module: each platform's own prompt is reached from what Electron and the OS already
 * ship.
 * - macOS: Touch ID through Electron (`systemPreferences.promptTouchID`); without Touch ID, or
 *   when the person picks the password instead, the system's authorization dialog through
 *   `osascript … with administrator privileges` (Authorization Services: a Mac administrator's
 *   name and password), running `/usr/bin/true` and nothing else.
 * - Windows: Windows Hello (`UserConsentVerifier` — face, fingerprint or the Hello PIN) through
 *   Windows PowerShell's WinRT projection. Without Hello set up there is no prompt here, and the
 *   app does not offer the reset (the Windows account password prompt, CredUI, would need
 *   P/Invoke and a logon check we could not verify; see the change record).
 * - Linux: polkit (`pkexec /usr/bin/true`): the desktop's own authentication agent asks for the
 *   person's (an administrator's) password. Without `pkexec` or a graphical session: no prompt.
 *
 * `COREHUB_DESKTOP_FAKE_OS_CONFIRM=allow|deny` answers without asking, for the desktop tests; the
 * controller honours it only in an unpackaged (development) app.
 */
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

export type ConfirmMethod = 'touch_id' | 'macos_password' | 'windows_hello' | 'polkit' | 'test';

/** What the page can say before asking: which prompt the OS will show, or none. */
export type ConfirmKind = 'macos' | 'windows_hello' | 'polkit' | 'test';

export type ConfirmResult =
  | { ok: true; method: ConfirmMethod }
  | { ok: false; reason: 'cancelled' | 'unavailable' | 'failed'; detail: string | null };

export interface ExecResult {
  code: number | null;
  stdout: string;
  stderr: string;
}

export interface OsConfirmEnv {
  platform: NodeJS.Platform;
  env: NodeJS.ProcessEnv;
  exec(file: string, args: string[]): Promise<ExecResult>;
  exists(file: string): boolean;
  /** Electron's Touch ID (macOS). */
  touchId?: { can(): boolean; prompt(reason: string): Promise<void> };
}

/** How long the person has to answer the OS before the app gives up waiting. */
const PROMPT_TIMEOUT_MS = 3 * 60_000;

export function realExec(file: string, args: string[]): Promise<ExecResult> {
  return new Promise((resolve) => {
    execFile(
      file,
      args,
      { timeout: PROMPT_TIMEOUT_MS, windowsHide: true, encoding: 'utf8' },
      (error, stdout, stderr) => {
        // A number is the program's exit status; a string (`ENOENT`) or a timeout means it
        // never answered.
        const raw = error ? (error as { code?: unknown }).code : 0;
        resolve({
          code: typeof raw === 'number' ? raw : null,
          stdout: String(stdout ?? ''),
          stderr: String(stderr || error?.message || ''),
        });
      },
    );
  });
}

export function realExists(file: string): boolean {
  return existsSync(file);
}

const first = (env: OsConfirmEnv, files: string[]) => files.find((f) => env.exists(f)) ?? null;

function pkexecOf(env: OsConfirmEnv) {
  return {
    pkexec: first(env, ['/usr/bin/pkexec', '/bin/pkexec']),
    truth: first(env, ['/usr/bin/true', '/bin/true']),
    graphical: !!(env.env.DISPLAY || env.env.WAYLAND_DISPLAY),
  };
}

export function powershellOf(env: OsConfirmEnv): string {
  const root = env.env.SystemRoot || env.env.SYSTEMROOT || 'C:\\Windows';
  return path.win32.join(root, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
}

/** A PowerShell single-quoted literal: only `'` is special inside one. */
const psQuote = (text: string) => `'${text.replace(/'/g, "''")}'`;

/**
 * Windows Hello through WinRT from Windows PowerShell 5.1: `check` answers the availability;
 * otherwise it asks, and answers the result (`Verified`, `Canceled`, …).
 */
export function helloScript(mode: 'check' | 'ask', reason: string): string {
  return [
    "$ErrorActionPreference = 'Stop'",
    'Add-Type -AssemblyName System.Runtime.WindowsRuntime',
    '$null = [Windows.Security.Credentials.UI.UserConsentVerifier, Windows.Security.Credentials.UI, ContentType = WindowsRuntime]',
    "$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1",
    'function Await($operation, [Type]$type) { $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation)); $null = $task.Wait(-1); $task.Result }',
    '$availability = Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])',
    `if (${psQuote(mode)} -eq 'check' -or "$availability" -ne 'Available') { Write-Output "availability:$availability"; exit 0 }`,
    `$result = Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync(${psQuote(reason)})) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])`,
    'Write-Output "result:$result"',
  ].join('\n');
}

/** `-EncodedCommand` takes UTF-16LE in base64: no quoting of the script on the command line. */
export function encodePowershell(script: string): string {
  return Buffer.from(script, 'utf16le').toString('base64');
}

async function runHello(env: OsConfirmEnv, mode: 'check' | 'ask', reason: string) {
  return env.exec(powershellOf(env), [
    '-NoProfile',
    '-NonInteractive',
    '-ExecutionPolicy',
    'Bypass',
    '-EncodedCommand',
    encodePowershell(helloScript(mode, reason)),
  ]);
}

/** Which prompt this computer can show, or null when it has none the app can reach. */
export async function confirmKind(env: OsConfirmEnv): Promise<ConfirmKind | null> {
  if (env.platform === 'darwin') return env.exists('/usr/bin/osascript') ? 'macos' : null;
  if (env.platform === 'linux') {
    const found = pkexecOf(env);
    return found.pkexec && found.truth && found.graphical ? 'polkit' : null;
  }
  if (env.platform === 'win32') {
    if (!env.exists(powershellOf(env))) return null;
    const answer = await runHello(env, 'check', '');
    return /availability:Available\b/.test(answer.stdout) ? 'windows_hello' : null;
  }
  return null;
}

async function macPassword(env: OsConfirmEnv, reason: string): Promise<ConfirmResult> {
  // The reason reaches AppleScript as an argument, never inside the script's text.
  const answer = await env.exec('/usr/bin/osascript', [
    '-e',
    'on run argv',
    '-e',
    'do shell script "/usr/bin/true" with prompt (item 1 of argv) with administrator privileges',
    '-e',
    'end run',
    reason,
  ]);
  if (answer.code === 0) return { ok: true, method: 'macos_password' };
  if (/-128\b|User canceled/i.test(answer.stderr))
    return { ok: false, reason: 'cancelled', detail: null };
  return { ok: false, reason: 'failed', detail: answer.stderr.trim().slice(0, 300) || null };
}

/** Asks the OS; resolves once the person answered (or the prompt could not be shown). */
export async function osConfirm(env: OsConfirmEnv, reason: string): Promise<ConfirmResult> {
  if (env.platform === 'darwin') {
    if (env.touchId?.can()) {
      try {
        await env.touchId.prompt(reason);
        return { ok: true, method: 'touch_id' };
      } catch {
        // Cancelled, or "use password" chosen: the Mac's own password dialog next.
      }
    }
    if (!env.exists('/usr/bin/osascript'))
      return { ok: false, reason: 'unavailable', detail: null };
    return macPassword(env, reason);
  }
  if (env.platform === 'linux') {
    const found = pkexecOf(env);
    if (!found.pkexec || !found.truth || !found.graphical)
      return { ok: false, reason: 'unavailable', detail: null };
    const answer = await env.exec(found.pkexec, [found.truth]);
    if (answer.code === 0) return { ok: true, method: 'polkit' };
    // pkexec: 126 = the dialog was dismissed or the password refused; 127 = nobody could ask
    // (no authentication agent in this session).
    if (answer.code === 126) return { ok: false, reason: 'cancelled', detail: null };
    if (answer.code === 127)
      return {
        ok: false,
        reason: 'unavailable',
        detail: answer.stderr.trim().slice(0, 300) || null,
      };
    return { ok: false, reason: 'failed', detail: answer.stderr.trim().slice(0, 300) || null };
  }
  if (env.platform === 'win32') {
    if (!env.exists(powershellOf(env))) return { ok: false, reason: 'unavailable', detail: null };
    const answer = await runHello(env, 'ask', reason);
    const result = /result:(\w+)/.exec(answer.stdout)?.[1];
    if (result === 'Verified') return { ok: true, method: 'windows_hello' };
    if (result === 'Canceled') return { ok: false, reason: 'cancelled', detail: null };
    if (/availability:/.test(answer.stdout))
      return { ok: false, reason: 'unavailable', detail: answer.stdout.trim().slice(0, 300) };
    return {
      ok: false,
      reason: 'failed',
      detail: (result ?? answer.stderr.trim()).slice(0, 300) || null,
    };
  }
  return { ok: false, reason: 'unavailable', detail: null };
}

/** The test double the development app uses when told to (`COREHUB_DESKTOP_FAKE_OS_CONFIRM`). */
export function fakeConfirm(
  answer: string | undefined,
): ((reason: string) => Promise<ConfirmResult>) | null {
  if (answer === 'allow') return async () => ({ ok: true, method: 'test' });
  if (answer === 'deny') return async () => ({ ok: false, reason: 'cancelled', detail: null });
  return null;
}
