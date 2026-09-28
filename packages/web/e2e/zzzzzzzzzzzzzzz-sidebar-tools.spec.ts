/**
 * The sidebar's «الأدوات» group and Search beside the fold toggle (owner, 2026-09-28,
 * DECISIONS §126), in a browser, in Arabic.
 *
 * Search is the icon next to the fold toggle, and the row right below New chat once folded;
 * «الأدوات» holds Agents · Tasks · Workflows · Schedules; a press closes it (the chats list
 * keeps the room), a reload keeps it closed, and while it is closed on one of its pages the
 * heading is the marked place; Workflows is its own page, and the old Schedules address of
 * Workflows lands there. Fresh browser context, so its choices reach no other journey.
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

const sidebar = (page: Page) =>
  page.getByRole('navigation', { name: /القائمة الرئيسية|Main menu/ }).first();

test('Tools folds away, Search sits by the toggle, and Workflows has its own page', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await login(page);
  const nav = sidebar(page);
  const rail = nav.getByTestId('rail');
  const tools = nav.getByTestId('sidebar-group-tools');

  // Search: an icon in the brand row, next to the fold toggle, and it opens the search.
  const search = nav.getByTestId('brand-search');
  await expect(search).toHaveAccessibleName('بحث');
  const searchBox = (await search.boundingBox())!;
  const foldBox = (await nav.getByTestId('sidebar-fold').boundingBox())!;
  expect(Math.abs(searchBox.y - foldBox.y)).toBeLessThan(8);
  // In Arabic the toggle is at the row's end — the left — and Search just before it.
  expect(searchBox.x).toBeGreaterThan(foldBox.x);

  // «الأدوات»: open, in order, under New chat.
  await expect(tools).toHaveAccessibleName(/الأدوات/);
  await expect(tools).toHaveAttribute('aria-expanded', 'true');
  const order = await rail
    .getByRole('link')
    .evaluateAll((links) => links.map((link) => link.getAttribute('data-nav-id')));
  expect(order).toEqual(['new_chat', 'agent_manager', 'tasks', 'workflows', 'schedules']);
  await page.screenshot({ path: path.join(shots, 'sidebar-tools-open-ar.png') });

  // Workflows is its own entry and page.
  await rail.getByRole('link', { name: 'سير العمل', exact: true }).click();
  await expect(page).toHaveURL(/\/workflows$/);
  await expect(page.getByTestId('workflows-section')).toBeVisible();
  await expect(rail.getByRole('link', { name: 'سير العمل', exact: true })).toHaveClass(/active/);

  // Closed: the entries go, the chats list gets the room, and on Workflows the heading is
  // the marked place.
  const listTop = (await nav.getByTestId('segments').boundingBox())!.y;
  await tools.click();
  await expect(tools).toHaveAttribute('aria-expanded', 'false');
  await expect(rail.getByRole('link')).toHaveCount(1);
  expect((await nav.getByTestId('segments').boundingBox())!.y).toBeLessThan(listTop - 100);
  await expect(tools).toHaveClass(/active/);
  await expect(tools).toHaveAttribute('aria-current', 'true');
  await page.screenshot({ path: path.join(shots, 'sidebar-tools-closed-ar.png') });

  // Remembered on this device.
  await page.reload();
  await expect(sidebar(page).getByTestId('sidebar-group-tools')).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  // Away from its pages the closed heading is not marked.
  await search.click();
  await expect(page).toHaveURL(/\/search$/);
  await expect(tools).not.toHaveClass(/active/);
  await tools.click();
  await expect(tools).toHaveAttribute('aria-expanded', 'true');
  await expect(rail.getByRole('link')).toHaveCount(5);

  // Folded into the rail of icons, Search is the row right below New chat.
  await nav.getByTestId('sidebar-fold').click();
  await expect(nav).toHaveAttribute('data-folded', 'true');
  await expect(nav.getByTestId('brand-search')).toHaveCount(0);
  const folded = await rail
    .getByRole('link')
    .evaluateAll((links) => links.map((link) => link.getAttribute('data-nav-id')));
  expect(folded.slice(0, 2)).toEqual(['new_chat', 'search']);
  await page.screenshot({ path: path.join(shots, 'sidebar-tools-folded-ar.png') });
  await nav.getByTestId('sidebar-fold').click();
  await expect(nav).not.toHaveAttribute('data-folded', 'true');

  // The old address of the Workflows tab still opens Workflows.
  await page.goto('/schedules?section=workflows');
  await expect(page).toHaveURL(/\/workflows$/);
  await expect(page.getByTestId('workflows-section')).toBeVisible();
  // And Schedules no longer has a Workflows tab.
  await rail.getByRole('link', { name: 'الجدولة', exact: true }).click();
  await expect(page).toHaveURL(/\/schedules$/);
  await expect(page.getByRole('tab', { name: 'سير العمل' })).toHaveCount(0);
});
