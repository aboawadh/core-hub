/**
 * Settings → Secrets (DECISIONS §125), in a browser, in Arabic, against the real hub.
 *
 * The owner opens Secrets from the Settings list; the page asks for the password, says so when
 * it is wrong, and opens on the right one; a provider key is listed by name and masked; Show
 * reveals it and it hides itself again; Close locks the page; coming back asks for the password
 * again (nothing is remembered). An admin has no Secrets row, and its address sends them home.
 */
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const PASSWORD = 'e2e-owner-password';
const KEY = 'sk-e2e-secrets-journey-0001';
const shots = process.env.COREHUB_SHOTS ?? path.resolve('e2e/shots');
mkdirSync(shots, { recursive: true });

async function login(page: Page, username = 'admin', password = PASSWORD) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('اسم المستخدم').fill(username);
  await page.getByLabel('كلمة المرور').fill(password);
  await page.getByRole('button', { name: 'دخول' }).click();
  await expect(page).toHaveURL(/\/chat$/);
}

test('the owner opens Secrets with the password each time, and reveals one value at a time', async ({
  page,
  browser,
  request,
}) => {
  const owner = await request.post('/api/v1/auth/login', {
    data: { username: 'admin', password: PASSWORD },
  });
  const auth = { authorization: `Bearer ${(await owner.json()).access_token}` };
  // A key to find: a provider of the owner's, added the way the Models page adds one.
  const added = await request.post('/api/v1/models/providers', {
    headers: { ...auth, 'x-hub-profile': 'default' },
    data: { preset: 'mistral', label: 'Mistral', kind: 'llm', api_key: KEY },
  });
  expect([201, 409]).toContain(added.status());

  await page.setViewportSize({ width: 1280, height: 800 });
  await login(page);
  await page.getByRole('link', { name: 'الإعدادات', exact: true }).first().click();
  const nav = page.getByTestId('settings-nav');
  await nav.getByRole('link', { name: 'الأسرار' }).click();
  await expect(page).toHaveURL(/\/settings\/secrets$/);
  await expect(page.getByTestId('secrets-locked')).toBeVisible();

  // A wrong password is said, and the page stays locked.
  await page.getByTestId('secrets-password').fill('not-the-password');
  await page.getByTestId('secrets-unlock').click();
  await expect(page.getByTestId('secrets-error')).toBeVisible();
  await expect(page.getByTestId('secrets-locked')).toBeVisible();

  await page.getByTestId('secrets-password').fill(PASSWORD);
  await page.getByTestId('secrets-unlock').click();
  await expect(page.getByTestId('secrets-open')).toBeVisible();
  const keys = page.getByTestId('secrets-group-provider_key');
  const row = keys.getByTestId('secret-row').filter({ hasText: 'Mistral' });
  await expect(row).toHaveCount(1);
  await expect(row.getByTestId('secret-value')).toHaveText('••••••••••••');
  await expect(page.locator('body')).not.toContainText(KEY);
  await page.screenshot({ path: path.join(shots, 'secrets-ar-light.png') });

  await row.getByTestId('secret-show').click();
  await expect(row.getByTestId('secret-value')).toHaveText(KEY);
  await expect(row.getByTestId('secret-hides-in')).toBeVisible();
  await row.getByTestId('secret-hide').click();
  await expect(row.getByTestId('secret-value')).toHaveText('••••••••••••');

  // Closed by hand, and asked again on the next visit: nothing is remembered.
  await page.getByTestId('secrets-lock').click();
  await expect(page.getByTestId('secrets-locked')).toBeVisible();
  await page.getByTestId('secrets-password').fill(PASSWORD);
  await page.getByTestId('secrets-unlock').click();
  await expect(page.getByTestId('secrets-open')).toBeVisible();
  await page.reload();
  await expect(page.getByTestId('secrets-locked')).toBeVisible();

  // An admin: no row, and the address sends them home.
  const made = await request.post('/api/v1/auth/users', {
    headers: auth,
    data: { username: 'salem', password: 'salem-password-1', role: 'admin', profiles: ['default'] },
  });
  expect([201, 409]).toContain(made.status());
  const baseURL = test.info().project.use.baseURL;
  const context = await browser.newContext({ ...(baseURL ? { baseURL } : {}), locale: 'ar' });
  try {
    const admin = await context.newPage();
    await login(admin, 'salem', 'salem-password-1');
    await admin.getByRole('link', { name: 'الإعدادات', exact: true }).first().click();
    await expect(admin.getByTestId('settings-nav')).toBeVisible();
    await expect(admin.getByTestId('settings-nav')).not.toContainText('الأسرار');
    await admin.goto('/settings/secrets');
    await expect(admin).not.toHaveURL(/\/settings\/secrets$/);
  } finally {
    await context.close();
  }
});
