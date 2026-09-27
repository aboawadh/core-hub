/**
 * Connecting a remote MCP server by OAuth from the web alone (DECISIONS §122), against the real
 * hub with a scripted Hermes (`e2e/hub.ts`) whose "provider" signs the person in at once:
 *
 * - a server that signs in by OAuth reads «غير متصل»; its Test fails in Hermes's words and
 *   offers the sign-in right there;
 * - Connect opens a tab that goes to the provider and comes back to the hub's own callback,
 *   which hands it to Hermes and says, in Arabic, that the sign-in arrived;
 * - the row then reads «متصل» with the number of tools, and Test lists them;
 * - Disconnect, after asking, forgets the sign-in in this profile.
 */
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const PASSWORD = 'e2e-owner-password';
const shots = process.env.COREHUB_SHOTS ?? path.resolve('e2e/shots');
mkdirSync(shots, { recursive: true });

async function login(page: Page) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('اسم المستخدم').fill('admin');
  await page.getByLabel('كلمة المرور').fill(PASSWORD);
  await page.getByRole('button', { name: 'دخول' }).click();
  await expect(page).toHaveURL(/\/chat$/);
}

async function openMcp(page: Page) {
  await page.getByTestId('rail').getByRole('link', { name: 'الوكلاء' }).click();
  await page.getByTestId('agent-menu').getByRole('link', { name: 'MCP' }).first().click();
  await expect(page).toHaveURL(/\/mcp$/);
}

test('MCP OAuth: a remote server is signed in from the web, tested, and disconnected', async ({
  page,
  context,
}) => {
  await login(page);
  await openMcp(page);

  await page.getByTestId('new-mcp').click();
  await page.getByTestId('mcp-name').fill('clickup');
  await page
    .getByTestId('mcp-config')
    .fill('{ "url": "https://mcp.clickup.example/mcp", "auth": "oauth" }');
  await page.getByTestId('save-mcp').click();
  await expect(page.getByTestId('mcp-oauth-status-clickup')).toContainText('غير متصل');
  await expect(page.getByTestId('mcp-oauth-clickup')).toContainText('كل بروفايل يُربط وحده.');

  // Test before signing in: Hermes's sentence, and the sign-in offered where it is read.
  await page.getByTestId('mcp-test-clickup').click();
  const failed = page.getByTestId('mcp-test-result-clickup');
  await expect(failed).toHaveAttribute('data-ok', 'false');
  await expect(failed).toContainText('no token found');

  // Connect: the tab opened on the click goes to the provider and back to the hub's callback.
  const popup = context.waitForEvent('page');
  await failed.getByTestId('mcp-test-connect-clickup').click();
  const tab = await popup;
  await tab.waitForURL(/\/api\/v1\/mcp-oauth\/callback\/clickup\?code=e2e-code&state=/);
  await expect(tab.locator('main')).toHaveAttribute('data-outcome', 'received');
  await expect(tab.locator('h1')).toHaveText('وصل تسجيل الدخول');
  await expect(tab.locator('html')).toHaveAttribute('dir', 'rtl');
  await tab.close();

  const done = page.getByTestId('mcp-oauth-result-clickup');
  await expect(done).toHaveAttribute('data-status', 'approved');
  await expect(done).toContainText('3');
  await expect(page.getByTestId('mcp-oauth-status-clickup')).toHaveText(/^\s*متصل\s*$/);
  await page.screenshot({ path: path.join(shots, 'agent-mcp-oauth-ar-light.png'), fullPage: true });

  await page.getByTestId('mcp-oauth-test-clickup').click();
  const ok = page.getByTestId('mcp-test-result-clickup');
  await expect(ok).toHaveAttribute('data-ok', 'true');
  await expect(ok).toContainText('get_workspace_hierarchy');

  // Disconnect, after asking: this profile forgets the sign-in.
  await page.getByTestId('mcp-oauth-disconnect-clickup').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'فصل' }).click();
  await expect(page.getByTestId('mcp-oauth-status-clickup')).toContainText('غير متصل');

  // Leave the page as it was for the journeys after this one.
  await page.getByTestId('mcp-delete-clickup').click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'حذف' }).click();
  await expect(page.getByTestId('mcp-oauth-status-clickup')).toHaveCount(0);
});
