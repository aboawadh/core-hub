/**
 * 32. A workflow drawn on the canvas, against the real hub (2026-09-25, DECISIONS §52).
 *
 * In Arabic, so the canvas runs right-to-left: a new workflow in the Workflows section of
 * the Schedules page, an Agent step and a Notify step added from the palette, connected by
 * dragging from the agent's success dot onto the notice, the notice reading the agent's
 * answer (`{{steps.agent_1.output}}`), saved, run — the agent step is a real turn of the
 * scripted runner — and the run shown on the canvas: both steps done, the connection taken,
 * and the notice's output with the agent's words in it.
 *
 * It runs after every journey that photographs the Schedules page, because it adds a
 * workflow to the shared hub.
 */
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const PASSWORD = 'e2e-owner-password';
const shots = process.env.COREHUB_SHOTS ?? path.resolve('e2e/shots');
mkdirSync(shots, { recursive: true });
const shot = (page: Page, name: string) =>
  page.screenshot({ path: path.join(shots, `${name}.png`), fullPage: true });

test.use({ viewport: { width: 1440, height: 1000 } });

async function login(page: Page) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('اسم المستخدم').fill('admin');
  await page.getByLabel('كلمة المرور').fill(PASSWORD);
  await page.getByRole('button', { name: 'دخول' }).click();
  await expect(page).toHaveURL(/\/chat$/);
}

/** The top selector on Default, where the journeys before this one may have left it. */
async function inDefault(page: Page) {
  const top = page.getByTestId('workspace-switcher').first();
  if ((await top.count()) && !(await top.textContent())?.includes('Default')) {
    await top.click();
    await page.getByRole('option', { name: 'Default', exact: true }).click();
  }
}

test('32. a two-step workflow drawn on the canvas runs, and its run is read on the canvas', async ({
  page,
}) => {
  await login(page);
  await inDefault(page);
  // Workflows has its own entry under «الأدوات» since 2026-09-28 (DECISIONS §126).
  await page.getByTestId('rail').getByRole('link', { name: 'سير العمل', exact: true }).click();
  await expect(page).toHaveURL(/\/workflows$/);

  await page.getByTestId('workflow-new').click();
  const editor = page.getByTestId('workflow-editor');
  await expect(editor).toHaveAttribute('data-workflow-id', 'new');
  const canvas = page.getByTestId('workflow-canvas');
  await expect(canvas).toHaveAttribute('data-direction', 'rtl');

  await page.getByTestId('workflow-name').fill('مراجعة ثم إشعار');

  // Step one: an agent, the Direct one, asked a question the scripted runner answers.
  await page.getByTestId('workflow-add-agent').click();
  await page.getByTestId('workflow-step-agent').click();
  await page.getByRole('option', { name: /Direct|مباشر/ }).click();
  await page.getByTestId('workflow-step-prompt').fill('راجع قائمة الإصدار وقل ما فيها');

  // Step two: a notice that reads what the agent said.
  await page.getByTestId('workflow-add-notify').click();
  await page.getByTestId('workflow-step-text').fill('اكتملت المراجعة: {{steps.agent_1.output}}');

  // Connected by drawing: from the agent's success dot onto the notice.
  const agentNode = canvas.locator('[data-node-id="agent_1"]').first();
  const notifyNode = page.getByTestId('workflow-node').filter({ hasText: 'إشعار' });
  const port = agentNode.getByTestId('workflow-port-success');
  const from = (await port.boundingBox())!;
  const to = (await notifyNode.boundingBox())!;
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 8 });
  await page.mouse.up();
  await expect(page.getByTestId('workflow-edge')).toHaveCount(1);

  // The hub checked the drawing: nothing refuses it.
  await expect(page.getByTestId('workflow-check')).toHaveAttribute('data-valid', 'true');
  await shot(page, 'workflow-editor-ar-light');

  await page.getByTestId('workflow-save').click();
  await expect(editor).not.toHaveAttribute('data-workflow-id', 'new');
  await expect(page).toHaveURL(/workflow=[0-9A-Z]{26}/);

  // Nothing selected: the side panel holds the workflow's own limits (decision §102), and Run
  // has a companion that sets them for one run only.
  await canvas.press('Escape');
  const settings = page.getByTestId('workflow-settings');
  await expect(settings.getByTestId('workflow-limits-form')).toBeVisible();
  await shot(page, 'workflow-limits-ar-light');
  await page.getByTestId('workflow-run-with-limits').click();
  const limits = page.getByTestId('workflow-run-limits-dialog');
  await expect(limits).toBeVisible();
  await limits.getByTestId('workflow-run-limit-time').fill('30');
  await limits.screenshot({ path: path.join(shots, 'workflow-run-limits-ar-light.png') });
  await limits.getByRole('button', { name: 'إلغاء' }).first().click();
  await expect(limits).toHaveCount(0);

  // Run: the canvas turns into the run, and both steps end done.
  await page.getByTestId('workflow-run').click();
  const run = page.getByTestId('workflow-run-view');
  await expect(run).toBeVisible();
  const nodes = run.getByTestId('workflow-node');
  await expect(nodes).toHaveCount(2);
  await expect(
    run.locator('[data-testid="workflow-node"][data-node-id="agent_1"]'),
  ).toHaveAttribute('data-state', 'done', { timeout: 20_000 });
  await expect(
    run.locator('[data-testid="workflow-node"][data-node-id="notify_1"]'),
  ).toHaveAttribute('data-state', 'done', { timeout: 20_000 });
  await expect(run.getByTestId('workflow-run-state')).toContainText('تم');
  await expect(run.getByTestId('workflow-edge')).toHaveAttribute('data-taken', 'true');

  // The notice's output carries the agent's words.
  await run.locator('[data-testid="workflow-node"][data-node-id="notify_1"]').click();
  await expect(run.getByTestId('workflow-step-output')).toContainText(
    'اكتملت المراجعة: القائمة سليمة: ثلاثة بنود جاهزة.',
  );
  await shot(page, 'workflow-run-ar-light');

  // Back in the list, the workflow is there with its run.
  await page.getByTestId('workflow-back').click();
  await expect(
    page.getByTestId('workflow-card').filter({ hasText: 'مراجعة ثم إشعار' }),
  ).toContainText('عدد مرات التشغيل: 1');
});

/** Selects a step on the drawing canvas and connects it to the step titled `to` on success. */
async function connect(page: Page, from: string, to: string) {
  // Focus selects a step even where the canvas has scrolled it out of view.
  await page
    .getByTestId('workflow-canvas')
    .locator(`[data-testid="workflow-node"][data-node-id="${from}"]`)
    .focus();
  await expect(page.getByTestId('workflow-panel')).toHaveAttribute('data-node-id', from);
  await page.getByTestId('workflow-connect-target').click();
  await page.getByRole('option', { name: to, exact: true }).click();
  await page.getByTestId('workflow-connect').click();
}

test('32b. a new workflow is checked before it has a name, then named, saved and run by hand', async ({
  page,
}) => {
  await login(page);
  await inDefault(page);
  await page.getByTestId('rail').getByRole('link', { name: 'سير العمل', exact: true }).click();
  await page.getByTestId('workflow-new').click();
  const editor = page.getByTestId('workflow-editor');
  await expect(editor).toHaveAttribute('data-workflow-id', 'new');
  // The name is empty: a hint by the name field says so, not an error about the drawing.
  await expect(page.getByTestId('workflow-name')).toHaveValue('');
  await expect(editor.getByText('سمِّ سير العمل قبل الحفظ.')).toBeVisible();

  // Five steps, one of each kind, each added after the one selected.
  await page.getByTestId('workflow-add-agent').click();
  await page.getByTestId('workflow-step-agent').click();
  await page.getByRole('option', { name: /Direct|مباشر/ }).click();
  await page.getByTestId('workflow-step-prompt').fill('راجع قائمة الإصدار وقل ما فيها');
  await page.getByTestId('workflow-add-condition').click();
  await page.getByTestId('workflow-add-delay').click();
  await page.getByTestId('workflow-delay-unit').click();
  await page.getByRole('option', { name: 'ثوانٍ', exact: true }).click();
  await page.getByTestId('workflow-delay-amount').fill('1');
  await page.getByTestId('workflow-add-approval').click();
  await page.getByTestId('workflow-step-question').fill('هل ننشر الإصدار؟');
  await page.getByTestId('workflow-add-notify').click();
  await page.getByTestId('workflow-step-text').fill('نُشر: {{steps.agent_1.output}}');
  await expect(page.getByTestId('workflow-node')).toHaveCount(5);

  // The hub's check of the whole drawing, sent while the name is still empty.
  const checked = page.waitForResponse((response) => {
    if (!response.url().includes('/workflows/validate')) return false;
    const sent = JSON.parse(response.request().postData() ?? '{}') as {
      name?: string;
      nodes?: unknown[];
      edges?: unknown[];
    };
    return sent.nodes?.length === 5 && sent.edges?.length === 4 && !sent.name;
  });
  await connect(page, 'agent_1', 'شرط');
  await connect(page, 'condition_1', 'انتظار');
  await connect(page, 'delay_1', 'موافقة');
  await connect(page, 'approval_1', 'إشعار');
  await expect(page.getByTestId('workflow-edge')).toHaveCount(4);

  // Still no name, and the hub checked the drawing without refusing it.
  const check = await checked;
  expect(check.status()).toBe(200);
  expect(await check.json()).toEqual({ valid: true, problems: [], warnings: [] });
  await expect(page.getByTestId('workflow-issue')).toHaveCount(0);
  await expect(page.getByTestId('workflow-check')).toHaveAttribute('data-valid', 'true');
  await expect(page.getByTestId('workflow-check-error')).toHaveCount(0);
  await expect(editor.getByText('تعذّر فحص الرسم', { exact: false })).toHaveCount(0);
  await expect(page.getByTestId('workflow-save')).toBeDisabled();
  await shot(page, 'workflow-editor-unnamed-ar-light');

  await page.getByTestId('workflow-name').fill('خمس خطوات قبل الاسم');
  await expect(editor.getByText('سمِّ سير العمل قبل الحفظ.')).toHaveCount(0);
  await page.getByTestId('workflow-save').click();
  await expect(editor).not.toHaveAttribute('data-workflow-id', 'new');
  await expect(page).toHaveURL(/workflow=[0-9A-Z]{26}/);
  await expect(page.getByTestId('workflow-check-error')).toHaveCount(0);

  // Run by hand, with words the condition finds; it waits at the approval, then finishes.
  await page.getByTestId('workflow-run-input').fill('الإصدار 1.2');
  await page.getByTestId('workflow-run').click();
  const run = page.getByTestId('workflow-run-view');
  await expect(run).toBeVisible();
  await expect(run.getByTestId('workflow-node')).toHaveCount(5);
  const step = (id: string) => run.locator(`[data-testid="workflow-node"][data-node-id="${id}"]`);
  await expect(step('approval_1')).toHaveAttribute('data-state', 'waiting', { timeout: 30_000 });
  for (const id of ['agent_1', 'condition_1', 'delay_1'])
    await expect(step(id)).toHaveAttribute('data-state', 'done');
  await run.getByTestId('workflow-approve').first().click();
  await expect(step('notify_1')).toHaveAttribute('data-state', 'done', { timeout: 20_000 });
  await expect(run.getByTestId('workflow-run-state')).toContainText('تم');
  await step('notify_1').click();
  await expect(run.getByTestId('workflow-step-output')).toContainText(
    'نُشر: القائمة سليمة: ثلاثة بنود جاهزة.',
  );
});
