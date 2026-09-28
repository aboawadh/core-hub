/**
 * Every main screen in the four test-only pseudo-locales (ADR 0028): `en-XA` (about 40% longer,
 * accented), `ar-XB` (long right-to-left), `zh-XC` (full-width CJK, no spaces) and `th-XD`
 * (stacked Thai marks, tall). A language added tomorrow is longer, wider or taller than English
 * and Arabic; if a screen survives these four, it survives that language.
 *
 * On each screen, at a desktop and a phone width, `auditLayout` (e2e/i18n-audit.ts) looks for a
 * single-line label running out of its box without an ellipsis, text clipped without one, a
 * label that wrapped a lone letter onto its last line, controls drawn over each other, and a page
 * that scrolls sideways. Every screen's screenshot is attached to the report, and the findings
 * with it; the journey fails on the first screen that has any.
 *
 * A pseudo-locale is switched on through browser storage only (`corehub.pseudo-locale`); no
 * picker lists one. Fresh browser context, so it reaches no other journey.
 */
import { expect, test, type Page } from '@playwright/test';
import { PSEUDO_LOCALES } from '@corehub/contracts';
import { auditLayout, type AuditFinding } from './i18n-audit.js';
import { SCREENS, WIDTHS } from './pseudo-screens.js';

const PASSWORD = 'e2e-owner-password';

async function login(page: Page) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('اسم المستخدم').fill('admin');
  await page.getByLabel('كلمة المرور').fill(PASSWORD);
  await page.getByRole('button', { name: 'دخول' }).click();
  await expect(page).toHaveURL(/\/chat$/);
}

/** A screen is drawn once its loading spinners are gone and the fonts have settled. */
async function settle(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await expect(page.locator('[role="status"][aria-busy="true"], .ch-spinner'))
    .toHaveCount(0, {
      timeout: 10_000,
    })
    .catch(() => undefined);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(250);
}

const report = (findings: AuditFinding[]) =>
  findings
    .map((f) => `  ${f.rule.padEnd(11)} ${f.where}\n      “${f.text}” — ${f.detail}`)
    .join('\n');

// The audit itself, on a page broken on purpose: it must find each thing it looks for, and
// nothing in a label that fits or one that ends in an ellipsis.
test('the layout audit finds what it looks for, and only that', async ({ page }) => {
  await page.setContent(`<!doctype html><html lang="en"><body style="margin:0;font:14px sans-serif">
    <button style="width:60px;white-space:nowrap">Spilling label far too long</button>
    <button style="width:60px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">Ellipsis label far too long</button>
    <span style="display:block;width:60px;white-space:nowrap;overflow:hidden">Cut label far too long</span>
    <button style="white-space:nowrap">Fits</button>
    <h3 style="width:62px;font-size:16px;margin:0;text-wrap:wrap">Columns x</h3>
    <div style="display:flex;width:80px"><button style="flex:none;width:70px">One</button><button style="flex:none;width:70px">Two</button></div>
    <div style="position:relative;height:40px"><button style="position:absolute;inset-inline-start:0;width:80px">Under</button><button style="position:absolute;inset-inline-start:40px;width:80px">Over</button></div>
    <div style="width:3000px;height:4px"></div>
  </body></html>`);
  const findings = await page.evaluate(auditLayout);
  const has = (rule: AuditFinding['rule'], text: string) =>
    findings.some((f) => f.rule === rule && f.text.includes(text));
  expect(has('overflow', 'Spilling label')).toBe(true);
  expect(has('clipped', 'Cut label')).toBe(true);
  expect(has('lone-letter', 'Columns x')).toBe(true);
  expect(has('overflow', 'OneTwo')).toBe(true);
  expect(has('overlap', 'Under ⟷ Over')).toBe(true);
  expect(findings.some((f) => f.rule === 'page-scroll')).toBe(true);
  // A label that fits, and one that ends in an ellipsis, are fine.
  expect(findings.filter((f) => /^(Fits|Ellipsis label)/.test(f.text))).toEqual([]);
});

// `en-XK` (English with invisible key tags) measures space; it is not one of the four looks.
for (const pseudo of PSEUDO_LOCALES.filter((each) => each.style !== 'tagged')) {
  test(`${pseudo.code}: every main screen fits at desktop and phone width`, async ({
    page,
  }, testInfo) => {
    test.setTimeout(240_000);
    await page.setViewportSize({ width: 1280, height: 800 });
    await login(page);
    await page.evaluate((code) => localStorage.setItem('corehub.pseudo-locale', code), pseudo.code);

    const failures: string[] = [];
    for (const size of WIDTHS) {
      await page.setViewportSize({ width: size.width, height: size.height });
      for (const screen of SCREENS) {
        await page.goto(screen.path);
        await expect(page.locator('html')).toHaveAttribute('lang', pseudo.code);
        await expect(page.locator('html')).toHaveAttribute('dir', pseudo.direction);
        await settle(page);
        const shot = testInfo.outputPath(`${pseudo.code}-${size.name}-${screen.name}.png`);
        await page.screenshot({ path: shot });
        await testInfo.attach(`${pseudo.code}-${size.name}-${screen.name}.png`, {
          path: shot,
          contentType: 'image/png',
        });
        const findings = await page.evaluate(auditLayout);
        if (findings.length > 0) {
          await testInfo.attach(`${pseudo.code}-${size.name}-${screen.name}.json`, {
            body: JSON.stringify(findings, null, 2),
            contentType: 'application/json',
          });
          failures.push(`${size.name} ${screen.path}\n${report(findings)}`);
        }
      }
    }
    expect(failures, `layout findings in ${pseudo.code}:\n${failures.join('\n')}`).toEqual([]);
  });
}
