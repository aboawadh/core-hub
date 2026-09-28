/**
 * Measures the room every single-line label has on the main screens, for locales/limits.json
 * (ADR 0028). Not a check: it runs only when asked,
 *
 *   COREHUB_MEASURE_SPACE=/tmp/space.json pnpm --filter @corehub/web exec playwright test \
 *     e2e/zzzzzzzzzzzzzzzzzz-measure-space.spec.ts --workers=1
 *   pnpm i18n:limits --measure /tmp/space.json
 *
 * and is re-run when a screen's layout changes. The English UI is measured in `en-XK` — English
 * with each string's key appended in zero-width characters — so every label is matched to its
 * key exactly and the layout is the English one to the pixel; at the desktop and the phone width,
 * a label's limit is the narrowest room it was seen with.
 */
import { writeFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { measureSpaces, type SpaceSample } from './i18n-audit.js';
import { SCREENS, WIDTHS } from './pseudo-screens.js';

const out = process.env.COREHUB_MEASURE_SPACE;
const PASSWORD = 'e2e-owner-password';

async function login(page: Page) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('اسم المستخدم').fill('admin');
  await page.getByLabel('كلمة المرور').fill(PASSWORD);
  await page.getByRole('button', { name: 'دخول' }).click();
  await expect(page).toHaveURL(/\/chat$/);
}

test('measure the room of every single-line label', async ({ page }) => {
  test.skip(!out, 'set COREHUB_MEASURE_SPACE=<file> to measure');
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1280, height: 800 });
  await login(page);
  await page.evaluate(() => localStorage.setItem('corehub.pseudo-locale', 'en-XK'));
  const samples: Array<SpaceSample & { screen: string; size: string }> = [];
  for (const size of WIDTHS) {
    await page.setViewportSize({ width: size.width, height: size.height });
    for (const screen of SCREENS) {
      await page.goto(screen.path);
      await expect(page.locator('html')).toHaveAttribute('lang', 'en-XK');
      await page.waitForLoadState('networkidle').catch(() => undefined);
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(300);
      for (const sample of await page.evaluate(measureSpaces))
        samples.push({ ...sample, screen: screen.path, size: size.name });
    }
  }
  writeFileSync(out!, `${JSON.stringify(samples, null, 2)}\n`);
});
