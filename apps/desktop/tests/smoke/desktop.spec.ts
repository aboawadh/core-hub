// The desktop app end to end: first run → remote mode against a real hub → sign in → a chat
// that streams through the app's loopback origin (HTTP and WebSocket) → This device. Then a
// second computer pairs by link instead of a password.
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { _electron as electron, expect, test, type ElectronApplication } from '@playwright/test';
import { hubPort } from './playwright.config.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const appDir = path.resolve(here, '../..');
// COREHUB_DESKTOP_SMOKE_EXECUTABLE runs the same journeys against a packaged app
// (release/linux-unpacked/core-hub) instead of the development runtime.
const packaged = process.env.COREHUB_DESKTOP_SMOKE_EXECUTABLE;
const executablePath =
  packaged ?? (createRequire(import.meta.url)('electron') as unknown as string);
const hub = `http://127.0.0.1:${hubPort}`;
const PASSWORD = 'e2e-owner-password';
const shots = path.join(appDir, 'test-results', 'shots');
mkdirSync(shots, { recursive: true });

const userDataDirs = new WeakMap<ElectronApplication, string>();
const userDataOf = (app: ElectronApplication) => userDataDirs.get(app) ?? '';

async function launch(
  language: 'ar' | 'en',
  env: Record<string, string> = {},
): Promise<ElectronApplication> {
  const userData = mkdtempSync(path.join(os.tmpdir(), 'corehub-desktop-'));
  writeFileSync(path.join(userData, 'desktop.json'), JSON.stringify({ language }));
  const app = await electron.launch({
    executablePath,
    // On Linux the test draws on the X server xvfb-run gives it, never on the desktop session
    // of whoever runs it (a Wayland session would otherwise be picked up from the env).
    args: [
      ...(packaged ? [] : [appDir]),
      ...(process.platform === 'linux' ? ['--no-sandbox', '--ozone-platform=x11'] : []),
    ],
    env: {
      ...process.env,
      WAYLAND_DISPLAY: '',
      COREHUB_DESKTOP_USER_DATA: userData,
      // `~/Core Hub` and the other assistants' files: the test's, never the machine's.
      COREHUB_DESKTOP_HOME: path.join(userData, 'home'),
      COREHUB_DESKTOP_NO_TRAY: '1',
      COREHUB_DESKTOP_NO_AUTO_UPDATE: '1',
      ...env,
    },
  });
  userDataDirs.set(app, userData);
  return app;
}

test('remote mode: connect, sign in, chat, This device', async () => {
  const app = await launch('ar');
  try {
    const welcome = await app.firstWindow();
    await expect(welcome.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(welcome.getByRole('heading', { name: 'أهلًا بك في كور هب' })).toBeVisible();
    await welcome.screenshot({ path: path.join(shots, 'welcome-ar.png') });

    // A wrong address is refused on the spot, in the person's language.
    await welcome.getByTestId('choose-remote').click();
    await welcome.getByTestId('hub-url').fill('127.0.0.1:9');
    await welcome.getByTestId('connect').click();
    await expect(welcome.getByTestId('welcome-error')).toContainText('لم يُجب أحد');
    await welcome.screenshot({ path: path.join(shots, 'welcome-remote-ar.png') });

    await welcome.getByTestId('hub-url').fill(`127.0.0.1:${hubPort}`);
    const [page] = await Promise.all([
      app.waitForEvent('window'),
      welcome.getByTestId('connect').click(),
    ]);

    // The bundled web client, served by the app, talking to the hub through it.
    await expect(page).toHaveURL(/\/login$/);
    expect(new URL(page.url()).hostname).toBe('127.0.0.1');
    expect(new URL(page.url()).port).not.toBe(String(hubPort));
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await page.getByLabel('اسم المستخدم').fill('admin');
    await page.getByLabel('كلمة المرور').fill(PASSWORD);
    await page.getByRole('button', { name: 'دخول' }).click();
    await expect(page).toHaveURL(/\/chat$/);

    // A streamed reply proves the realtime socket crosses the loopback origin.
    await page.getByRole('link', { name: 'محادثة جديدة' }).first().click();
    await expect(page.getByTestId('composer-input')).toBeEnabled();
    await page.getByTestId('composer-input').fill('مرحبا');
    await page.getByTestId('send').click();
    const assistant = page.getByTestId('message-assistant');
    await expect(assistant.getByRole('heading', { name: 'مرحبا' })).toBeVisible();
    await expect(assistant).toHaveAttribute('data-status', 'complete');
    await page.screenshot({ path: path.join(shots, 'chat-ar.png') });

    // This device: only the desktop has it, and it knows which hub the window talks to.
    await page.goto(new URL('/settings/this-device', page.url()).toString());
    await expect(page.getByTestId('this-device-hub')).toHaveText(hub);
    await expect(page.getByTestId('this-device-mode')).toHaveText('متصل بمركز');
    await expect(page.getByTestId('this-device-hub-state')).toContainText('يُجيب');
    await page.screenshot({ path: path.join(shots, 'this-device-ar.png') });

    // Changing the connection goes back to the first-run screen, the hub remembered.
    const [back] = await Promise.all([
      app.waitForEvent('window'),
      page.getByTestId('this-device-change').click(),
    ]);
    await back.getByTestId('choose-remote').click();
    await expect(back.getByTestId('hub-url')).toHaveValue(hub);
  } finally {
    await app.close();
  }
});

test('pairing: a link from a signed-in device signs this computer in', async ({ request }) => {
  const login = await request.post(`${hub}/api/v1/auth/login`, {
    data: { username: 'admin', password: PASSWORD },
  });
  const token = (await login.json()).access_token as string;
  const pairing = await (
    await request.post(`${hub}/api/v1/auth/pairings`, {
      headers: { authorization: `Bearer ${token}` },
      data: {},
    })
  ).json();

  const app = await launch('en');
  try {
    const welcome = await app.firstWindow();
    await expect(welcome.getByRole('heading', { name: 'Welcome to Core Hub' })).toBeVisible();
    await welcome.getByTestId('choose-remote').click();
    await welcome.getByTestId('pair-text').fill(pairing.qr_payload);
    const [page] = await Promise.all([
      app.waitForEvent('window'),
      welcome.getByTestId('pair').click(),
    ]);
    // Signed in by the pairing: no sign-in screen.
    await expect(page).toHaveURL(/\/chat$/);
    const claimed = await (
      await request.get(`${hub}/api/v1/auth/pairings/${pairing.id}`, {
        headers: { authorization: `Bearer ${token}` },
      })
    ).json();
    expect(claimed.status).toBe('claimed');
  } finally {
    await app.close();
  }
});

test('local mode: no Hermes found → the hub starts on this computer anyway → first-run setup', async () => {
  // A computer with no Hermes: an empty home, and no gateway where one would answer.
  const home = mkdtempSync(path.join(os.tmpdir(), 'corehub-home-'));
  const app = await launch('ar', {
    HOME: home,
    HERMES_HOME: path.join(home, '.hermes'),
    COREHUB_DESKTOP_HERMES_GATEWAY: 'http://127.0.0.1:9/health',
    // The OS prompt of "Forgot password?" answers yes (an unpackaged app only; DECISIONS §131).
    COREHUB_DESKTOP_FAKE_OS_CONFIRM: 'allow',
  });
  try {
    const welcome = await app.firstWindow();
    // Local mode says up front that a Hermes already here keeps its own settings (2026-09-27).
    await expect(welcome.getByTestId('local-own-hermes')).toContainText('مجلد بيانات التطبيق');
    await welcome.getByTestId('choose-local').click();
    // The app offers Hermes's own installer and says exactly what would run.
    const missing = welcome.getByTestId('hermes-missing');
    await expect(missing).toBeVisible();
    await expect(missing).toContainText('hermes-agent.nousresearch.com/install.sh');
    await expect(missing.getByRole('button', { name: 'تثبيت هرمز' })).toBeVisible();
    await welcome.screenshot({ path: path.join(shots, 'local-no-hermes-ar.png') });

    const [page] = await Promise.all([
      app.waitForEvent('window'),
      welcome.getByTestId('local-without-hermes').click(),
    ]);
    // The embedded hub has no owner yet: the web client's first-run setup, on the app's origin.
    await expect(page).toHaveURL(/\/setup$/, { timeout: 60_000 });
    await page.getByLabel('اسم المستخدم', { exact: true }).fill('tariq');
    await page.getByLabel('كلمة المرور', { exact: true }).fill('local-owner-password');
    await page.getByLabel('تأكيد كلمة المرور', { exact: true }).fill('local-owner-password');
    await page.getByRole('button', { name: 'أنشئ الحساب وادخل' }).click();
    await expect(page).toHaveURL(/\/chat$/);

    await page.goto(new URL('/settings/this-device', page.url()).toString());
    await expect(page.getByTestId('this-device-mode')).toHaveText('يعمل على هذا الحاسوب');
    await expect(page.getByTestId('this-device-hermes')).toContainText('غير موجود');
    await expect(page.getByTestId('this-device-data-dir')).toContainText('local-hub');
    await page.screenshot({ path: path.join(shots, 'this-device-local-ar.png') });

    // The local helper: off until turned on, then an MCP server on this computer only.
    await expect(page.getByTestId('helper-enabled')).toHaveAttribute('aria-checked', 'false');
    await page.getByTestId('helper-enabled').click();
    const url = (await page.getByTestId('helper-url').textContent()) ?? '';
    expect(url).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/mcp$/);
    const settings = JSON.parse(
      readFileSync(path.join(userDataOf(app), 'desktop.json'), 'utf8'),
    ) as {
      helper: {
        token: string;
        enabled: boolean;
        folders: Array<{ path: string; write: boolean }>;
        defaultFolder: string | null;
      };
    };
    expect(settings.helper.enabled).toBe(true);
    // Turned on with nothing shared: the app made `~/Core Hub` and shared it writable.
    const coreHub = path.join(userDataOf(app), 'home', 'Core Hub');
    expect(settings.helper.folders).toEqual([{ path: coreHub, write: true }]);
    expect(settings.helper.defaultFolder).toBe(coreHub);
    const call = (body: unknown, token = settings.helper.token) =>
      fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
      });
    expect((await call({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, 'wrong')).status).toBe(401);
    const tools = (await (await call({ jsonrpc: '2.0', id: 1, method: 'tools/list' })).json()) as {
      result: { tools: Array<{ name: string }> };
    };
    // The app's own folder, writable; opening is still not allowed.
    expect(tools.result.tools.map((t) => t.name)).toEqual([
      'list_allowed_folders',
      'list_directory',
      'read_text_file',
      'write_text_file',
    ]);
    // Programs on this computer are a part of This device, not a page of their own.
    await expect(page.getByTestId('programs')).toBeVisible();
    await page.getByTestId('helper').screenshot({ path: path.join(shots, 'helper-ar.png') });

    // The owner of the hub on this computer (DECISIONS §131). A packaged app asks the real OS,
    // which a test cannot answer: these steps need the development runtime.
    if (!packaged) {
      const origin = new URL(page.url()).origin;
      const login = (password: string) =>
        page.evaluate(
          async ({ password }) =>
            (
              await fetch('/api/v1/auth/login', {
                method: 'POST',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({ username: 'tariq', password }),
              })
            ).status,
          { password },
        );
      // This install's settings file came before the setting: password-free sign-in stays off.
      const localSignIn = page.getByTestId('this-device-local-sign-in');
      await expect(localSignIn).toHaveAttribute('aria-checked', 'false');

      // Signed out (the stored sign-in forgotten), the app does not sign anyone in by itself.
      await page.evaluate(() => localStorage.clear());
      await page.goto(`${origin}/login`);
      await expect(page).toHaveURL(/\/login$/);
      await expect(page.getByRole('button', { name: 'نسيت كلمة المرور؟' })).toBeVisible();
      await page.screenshot({ path: path.join(shots, 'login-local-ar.png') });
      await page.getByRole('button', { name: 'نسيت كلمة المرور؟' }).click();
      await expect(page.getByTestId('recovery-username')).toHaveText('tariq');
      await page.getByLabel('كلمة المرور الجديدة', { exact: true }).fill('a-recovered-password');
      await page
        .getByLabel('تأكيد كلمة المرور الجديدة', { exact: true })
        .fill('a-recovered-password');
      await page.screenshot({ path: path.join(shots, 'recovery-ar.png') });
      await page.getByRole('button', { name: 'احفظ وادخل' }).click();
      await expect(page).toHaveURL(/\/chat$/);
      expect(await login('local-owner-password')).toBe(401);
      expect(await login('a-recovered-password')).toBe(200);

      // Turned on, the app signs the owner in at start without the password.
      await page.goto(`${origin}/settings/this-device`);
      await localSignIn.click();
      await expect(localSignIn).toHaveAttribute('aria-checked', 'true');
      expect(
        (
          JSON.parse(readFileSync(path.join(userDataOf(app), 'desktop.json'), 'utf8')) as {
            localSignIn: boolean;
          }
        ).localSignIn,
      ).toBe(true);
      await page.evaluate(() => localStorage.clear());
      await page.goto(`${origin}/login`);
      await expect(page).toHaveURL(/\/chat$/);
    }
  } finally {
    await app.close();
  }
});
