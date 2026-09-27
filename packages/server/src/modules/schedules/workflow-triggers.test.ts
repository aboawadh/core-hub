/**
 * Inbound workflow triggers (DECISIONS §123): each preset's signature over the raw bytes, the
 * event read after it, a repeat recognised, the trigger's event filter, the fast answer and
 * the run with `{{trigger.*}}`, the delivery log from `run_started` to how the run ended, the
 * test event, a condition's several rules, and the runs of one task found by its id.
 */
import { createHmac } from 'node:crypto';
import { afterEach, describe, expect, it } from 'vitest';
import { authed, signedInHub } from '../../../tests/unit/helpers.js';
import { registerWorkflowPorts, workflowEngineFor } from './index.js';
import {
  factsOf,
  previewOf,
  safeHeaders,
  sameSecret,
  signatureHeaders,
  verify,
  type SignatureSettings,
} from './triggers.js';
import { evaluateRules, ruleProblem } from './expr.js';
import type { WorkflowPorts } from './workflow-engine.js';

type Json = Record<string, unknown>;
type Hub = Awaited<ReturnType<typeof signedInHub>>;

const SECRET = 'OXCRKFDHP6IE1BX3ND4KZ9R5Y1WLG4XB';
const hex = (secret: string, raw: string | Buffer) =>
  createHmac('sha256', secret).update(raw).digest('hex');
const settings = (preset: SignatureSettings['preset'], extra: Partial<SignatureSettings> = {}) =>
  ({
    preset,
    signatureHeader: null,
    signatureEncoding: null,
    signaturePrefix: null,
    ...extra,
  }) as SignatureSettings;

/** A reversible stand-in for the hub's key ring. */
const sealer = {
  seal: (plain: string) => ({
    ciphertext: Buffer.from(plain).toString('base64'),
    nonce: 'n',
    keyId: 'k',
  }),
  open: (sealed: { ciphertext: string }) => Buffer.from(sealed.ciphertext, 'base64').toString(),
};

const notices: Array<{ title: string; body: string | null }> = [];
let previous: ReturnType<typeof registerWorkflowPorts> | undefined;
function fakePorts(ports: Partial<WorkflowPorts> = {}) {
  previous = registerWorkflowPorts(() => ({
    agentTurn: null,
    notice: (_scope, input) => void notices.push(input),
    sealer,
    ...ports,
  }));
}
afterEach(() => {
  if (previous !== undefined) registerWorkflowPorts(previous);
  previous = undefined;
  notices.length = 0;
});

const step = (id: string, kind: string, input: string | null, extra: Json = {}) => ({
  id,
  kind,
  title: id,
  agent_id: null,
  model: null,
  provider: null,
  reasoning_effort: null,
  skills: [],
  input,
  approval_required: false,
  position: { x: 0, y: 0 },
  ...extra,
});
const edge = (from: string, to: string, route = 'success') => ({
  id: `${from}-${to}`,
  from,
  to,
  route,
});

/** ClickUp's task event, as its bytes arrive (spaces and all). */
const clickup = (event: string, task: string, history: string[]) =>
  `{ "event": "${event}", "task_id": "${task}", "webhook_id": "wh-1",\n  "history_items": [${history
    .map((id) => `{ "id": "${id}", "field": "status" }`)
    .join(', ')}] }`;

async function workflowWith(hub: Hub, nodes: unknown[], edges: unknown[]) {
  const res = await authed(hub, hub.token, {
    method: 'POST',
    url: '/api/v1/workflows',
    payload: { name: 'ClickUp to agent', nodes, edges },
  });
  expect(res.statusCode, res.body).toBe(201);
  return (res.json() as Json).id as string;
}

async function triggerOn(hub: Hub, workflowId: string, body: Json) {
  const res = await authed(hub, hub.token, {
    method: 'POST',
    url: `/api/v1/workflows/${workflowId}/triggers`,
    payload: body,
  });
  expect(res.statusCode, res.body).toBe(201);
  return res.json() as Json & { id: string; path: string };
}

function deliver(hub: Hub, path: string, raw: string | Buffer, headers: Record<string, string>) {
  return hub.app.inject({
    method: 'POST',
    url: path,
    payload: raw,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

async function deliveries(hub: Hub, triggerId: string) {
  const res = await authed(hub, hub.token, {
    method: 'GET',
    url: `/api/v1/workflow-triggers/${triggerId}/deliveries`,
  });
  expect(res.statusCode, res.body).toBe(200);
  return (res.json() as { items: Array<Json & { status: string }> }).items;
}

describe('workflow triggers: signatures over the raw bytes', () => {
  const raw = Buffer.from('{ "event": "taskCreated",  "task_id": "abc" }');

  it('clickup: X-Signature is the hex HMAC-SHA256 of the raw body', () => {
    const good = { 'x-signature': hex(SECRET, raw) };
    expect(verify(settings('clickup'), SECRET, raw, good)).toEqual({ ok: true });
    expect(verify(settings('clickup'), SECRET, raw, { 'x-signature': hex('other', raw) }).ok).toBe(
      false,
    );
    expect(verify(settings('clickup'), SECRET, raw, {}).ok).toBe(false);
    expect(verify(settings('clickup'), null, raw, good)).toEqual({
      ok: false,
      reason: 'no secret is stored for this trigger yet',
    });
    // The parsed body written again is other bytes: its signature is not the sender's.
    const again = Buffer.from(JSON.stringify(JSON.parse(raw.toString())));
    expect(again.equals(raw)).toBe(false);
    expect(verify(settings('clickup'), SECRET, again, good).ok).toBe(false);
  });

  it('github: X-Hub-Signature-256 is sha256= and the hex HMAC', () => {
    const good = { 'x-hub-signature-256': `sha256=${hex(SECRET, raw)}` };
    expect(verify(settings('github'), SECRET, raw, good).ok).toBe(true);
    expect(
      verify(settings('github'), SECRET, raw, { 'x-hub-signature-256': hex(SECRET, raw) }).ok,
    ).toBe(false);
  });

  it('generic_hmac: its own header, base64 or hex, after its prefix', () => {
    const b64 = settings('generic_hmac', {
      signatureHeader: 'X-Acme-Signature',
      signatureEncoding: 'base64',
      signaturePrefix: 'v1=',
    });
    const signature = createHmac('sha256', SECRET).update(raw).digest('base64');
    expect(verify(b64, SECRET, raw, { 'x-acme-signature': `v1=${signature}` }).ok).toBe(true);
    expect(verify(b64, SECRET, raw, { 'x-acme-signature': signature }).ok).toBe(false);
    expect(
      verify(settings('generic_hmac'), SECRET, raw, { 'x-signature': hex(SECRET, raw) }).ok,
    ).toBe(true);
    expect(signatureHeaders(b64, SECRET, raw)).toEqual({ 'x-acme-signature': `v1=${signature}` });
  });

  it('token: the secret itself in the header, compared in constant time', () => {
    expect(verify(settings('token'), SECRET, raw, { 'x-webhook-token': SECRET }).ok).toBe(true);
    expect(verify(settings('token'), SECRET, raw, { 'x-webhook-token': `${SECRET}x` }).ok).toBe(
      false,
    );
    expect(sameSecret('a', 'a')).toBe(true);
    expect(sameSecret('a', 'ab')).toBe(false);
  });

  it('reads ClickUp’s event, task and history ids; the repeat key does not depend on their order', () => {
    const one = Buffer.from(clickup('taskUpdated', 't1', ['20', '10']));
    const two = Buffer.from(clickup('taskUpdated', 't1', ['10', '20']));
    const a = factsOf('clickup', JSON.parse(one.toString()), one, {});
    const b = factsOf('clickup', JSON.parse(two.toString()), two, {});
    expect(a).toEqual({
      event: 'taskUpdated',
      eventId: '10,20',
      taskId: 't1',
      dedupeKey: 'clickup:wh-1:10,20',
    });
    expect(b.dedupeKey).toBe(a.dedupeKey);
    // Without history ids, the body's hash; with a delivery id header, that id.
    expect(factsOf('clickup', {}, Buffer.from('{}'), {}).dedupeKey).toMatch(/^sha256:/);
    expect(factsOf('token', {}, Buffer.from('{}'), { 'x-delivery-id': 'd-1' }).dedupeKey).toBe(
      'delivery:d-1',
    );
  });

  it('never lets a signature or a secret reach the run or the log', () => {
    const headers = safeHeaders({
      'x-signature': 'abc',
      authorization: 'Bearer x',
      'x-webhook-token': 't',
      'x-github-event': 'issues',
      'content-type': 'application/json',
    });
    expect(headers).toEqual({ x_github_event: 'issues', content_type: 'application/json' });
    expect(previewOf({ event: 'x', secret: 's', nested: { api_key: 'k', ok: 1 } })).toBe(
      '{"event":"x","secret":"[masked]","nested":{"api_key":"[masked]","ok":1}}',
    );
  });
});

describe('condition rules (§123)', () => {
  const ctx = {
    trigger: { event: 'taskStatusUpdated', body: { priority: 2, tags: ['bug'] } },
    steps: {},
  };
  it('answers all / any, with the single-line operators', () => {
    const rules = {
      match: 'all' as const,
      items: [
        { path: 'trigger.event', operator: '==', value: 'taskStatusUpdated' },
        { path: 'trigger.body.priority', operator: '<=', value: '2' },
        { path: 'trigger.body.tags', operator: 'contains', value: 'bug' },
      ],
    };
    expect(evaluateRules(rules, ctx)).toBe(true);
    const one = {
      ...rules,
      items: [...rules.items, { path: 'trigger.body.x', operator: 'exists', value: null }],
    };
    expect(evaluateRules(one, ctx)).toBe(false);
    expect(evaluateRules({ ...one, match: 'any' }, ctx)).toBe(true);
    expect(ruleProblem({ path: 'a b', operator: '==', value: '1' })).toBe('rule_path_invalid');
    expect(ruleProblem({ path: 'a', operator: '=~', value: '1' })).toBe('rule_operator_invalid');
    expect(ruleProblem({ path: 'a', operator: '==', value: null })).toBe('rule_value_missing');
    expect(ruleProblem({ path: 'a', operator: 'matches', value: '(' })).toBe('rule_regex_invalid');
  });
});

describe('workflow triggers: the hub receiving', () => {
  it('a ClickUp delivery runs the workflow with the event; repeats, other events and bad signatures do not', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const workflowId = await workflowWith(
        hub,
        [
          step('only_status', 'condition', null, {
            rules: {
              match: 'any',
              items: [
                { path: 'trigger.event', operator: '==', value: 'taskStatusUpdated' },
                { path: 'trigger.event', operator: '==', value: 'taskCreated' },
              ],
            },
          }),
          step(
            'tell',
            'notify',
            'Task {{trigger.task_id}}: {{trigger.event}} ({{trigger.event_id}})',
          ),
        ],
        [edge('only_status', 'tell')],
      );
      const trigger = await triggerOn(hub, workflowId, {
        name: 'ClickUp',
        preset: 'clickup',
        events: ['taskCreated', 'taskStatusUpdated', 'taskAssigneeUpdated'],
      });
      expect(trigger).toMatchObject({
        preset: 'clickup',
        secret_stored: false,
        path: `/api/v1/workflow-hooks/${trigger.id}`,
      });

      // No secret yet: refused, and the refusal is in the log without the body.
      const first = clickup('taskCreated', 'task-1', ['h1']);
      const early = await deliver(hub, trigger.path, first, { 'x-signature': hex(SECRET, first) });
      expect(early.statusCode).toBe(401);

      // The secret ClickUp made goes in; it is never returned.
      const set = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflow-triggers/${trigger.id}`,
        payload: { secret: SECRET },
      });
      expect(set.statusCode).toBe(200);
      expect(set.json()).toMatchObject({ secret_stored: true });
      expect(set.body).not.toContain(SECRET);

      // A good delivery: 202 at once, the run goes on after the answer.
      const ok = await deliver(hub, trigger.path, first, { 'x-signature': hex(SECRET, first) });
      expect(ok.statusCode, ok.body).toBe(202);
      const receipt = ok.json() as { status: string; workflow_run_id: string };
      expect(receipt.status).toBe('accepted');
      await workflowEngineFor(hub.app).settled();
      expect(notices.map((n) => n.body)).toEqual(['Task task-1: taskCreated (h1)']);
      const run = await authed(hub, hub.token, {
        method: 'GET',
        url: `/api/v1/workflow-runs/${receipt.workflow_run_id}`,
      });
      expect(run.json()).toMatchObject({
        status: 'succeeded',
        workflow_trigger_id: trigger.id,
        event_id: 'h1',
        task_id: 'task-1',
        filtered: false,
      });

      // The same delivery again: 200, logged as a repeat, no second run.
      const again = await deliver(hub, trigger.path, first, { 'x-signature': hex(SECRET, first) });
      expect(again.statusCode).toBe(200);
      expect(again.json()).toMatchObject({ status: 'duplicate', workflow_run_id: null });

      // An event the trigger does not take: 200, filtered out.
      const other = clickup('taskDeleted', 'task-1', ['h2']);
      const skipped = await deliver(hub, trigger.path, other, {
        'x-signature': hex(SECRET, other),
      });
      expect(skipped.json()).toMatchObject({ status: 'filtered' });

      // A signature over other bytes (the body written again): refused.
      const moved = JSON.stringify(JSON.parse(clickup('taskCreated', 'task-9', ['h9'])));
      const forged = await deliver(hub, trigger.path, moved, {
        'x-signature': hex(SECRET, clickup('taskCreated', 'task-9', ['h9'])),
      });
      expect(forged.statusCode).toBe(401);

      // The workflow's own rule says no: the run succeeds as "filtered", nobody is told.
      const assigned = clickup('taskAssigneeUpdated', 'task-2', ['h3']);
      const quiet = await deliver(hub, trigger.path, assigned, {
        'x-signature': hex(SECRET, assigned),
      });
      expect(quiet.statusCode).toBe(202);
      await workflowEngineFor(hub.app).settled();
      expect(notices).toHaveLength(1);

      const log = await deliveries(hub, trigger.id);
      expect(log.map((line) => [line.status, line.event, line.filtered])).toEqual([
        ['run_succeeded', 'taskAssigneeUpdated', true],
        ['signature_rejected', null, false],
        ['filtered_out', 'taskDeleted', false],
        ['duplicate', 'taskCreated', false],
        ['run_succeeded', 'taskCreated', false],
        ['signature_rejected', null, false],
      ]);
      expect(log[0]).toMatchObject({ task_id: 'task-2', event_id: 'h3' });
      expect(log.find((line) => line.status === 'signature_rejected')!.body_preview).toBeNull();
      expect(JSON.stringify(log)).not.toContain(SECRET);

      // The runs about one task, found by its id.
      const found = await authed(hub, hub.token, {
        method: 'GET',
        url: `/api/v1/workflows/${workflowId}/runs?task_id=task-2`,
      });
      expect((found.json() as { items: Json[] }).items).toEqual([
        expect.objectContaining({ task_id: 'task-2', filtered: true, status: 'succeeded' }),
      ]);
    } finally {
      await hub.close();
    }
  });

  it('"Send test event" goes through the whole receiving path, signed with the stored secret', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const workflowId = await workflowWith(
        hub,
        [step('tell', 'notify', '{{trigger.task_id}} {{trigger.test}}')],
        [],
      );
      const trigger = await triggerOn(hub, workflowId, {
        preset: 'clickup',
        events: ['taskCreated'],
      });
      const missing = await authed(hub, hub.token, {
        method: 'POST',
        url: `/api/v1/workflow-triggers/${trigger.id}/test`,
        payload: {},
      });
      expect(missing.statusCode).toBe(409);
      expect(missing.json()).toMatchObject({ details: { reason: 'secret_missing' } });
      await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflow-triggers/${trigger.id}`,
        payload: { secret: SECRET },
      });
      const sent = await authed(hub, hub.token, {
        method: 'POST',
        url: `/api/v1/workflow-triggers/${trigger.id}/test`,
        payload: { task_id: 'demo-task' },
      });
      expect(sent.statusCode, sent.body).toBe(200);
      expect(sent.json()).toMatchObject({
        status: 'run_started',
        event: 'taskCreated',
        task_id: 'demo-task',
        test: true,
      });
      await workflowEngineFor(hub.app).settled();
      expect(notices.map((n) => n.body)).toEqual(['demo-task true']);
      // An event the trigger does not take is filtered out, like a real one.
      const other = await authed(hub, hub.token, {
        method: 'POST',
        url: `/api/v1/workflow-triggers/${trigger.id}/test`,
        payload: { event: 'taskDeleted' },
      });
      expect(other.json()).toMatchObject({ status: 'filtered_out', test: true });
    } finally {
      await hub.close();
    }
  });

  it('github, token and generic presets over HTTP; too large a body is 413; a switched-off trigger is 404', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const workflowId = await workflowWith(
        hub,
        [step('tell', 'notify', '{{trigger.event}} {{trigger.headers.x_github_event}}')],
        [],
      );
      const github = await triggerOn(hub, workflowId, { preset: 'github', secret: SECRET });
      const body = '{"action":"opened"}';
      const res = await deliver(hub, github.path, body, {
        'x-hub-signature-256': `sha256=${hex(SECRET, body)}`,
        'x-github-event': 'issues',
        'x-github-delivery': 'd-1',
      });
      expect(res.statusCode, res.body).toBe(202);
      const repeat = await deliver(hub, github.path, body, {
        'x-hub-signature-256': `sha256=${hex(SECRET, body)}`,
        'x-github-event': 'issues',
        'x-github-delivery': 'd-1',
      });
      expect(repeat.json()).toMatchObject({ status: 'duplicate' });
      await workflowEngineFor(hub.app).settled();
      expect(notices.map((n) => n.body)).toEqual(['issues issues']);

      const token = await triggerOn(hub, workflowId, {
        preset: 'token',
        secret: SECRET,
        signature_header: 'X-Api-Token',
      });
      expect(
        (await deliver(hub, token.path, '{"event":"ping"}', { 'x-api-token': SECRET })).statusCode,
      ).toBe(202);
      expect(
        (await deliver(hub, token.path, '{"event":"ping2"}', { 'x-api-token': 'nope' })).statusCode,
      ).toBe(401);

      const generic = await triggerOn(hub, workflowId, {
        preset: 'generic_hmac',
        secret: SECRET,
        signature_header: 'X-Acme',
        signature_encoding: 'base64',
        signature_prefix: 'v1=',
      });
      const signed = createHmac('sha256', SECRET).update('{"type":"order"}').digest('base64');
      expect(
        (await deliver(hub, generic.path, '{"type":"order"}', { 'x-acme': `v1=${signed}` }))
          .statusCode,
      ).toBe(202);

      const big = Buffer.alloc(1024 * 1024 + 10, 'a');
      expect((await deliver(hub, generic.path, big, { 'x-acme': 'v1=x' })).statusCode).toBe(413);

      await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflow-triggers/${token.id}`,
        payload: { enabled: false },
      });
      expect(
        (await deliver(hub, token.path, '{"event":"ping3"}', { 'x-api-token': SECRET })).statusCode,
      ).toBe(404);
      const unknown = await deliver(
        hub,
        '/api/v1/workflow-hooks/01J8QK3ZR2W7M5N4P6T8V9X0AA',
        '{}',
        {},
      );
      expect(unknown.statusCode).toBe(404);
      await workflowEngineFor(hub.app).settled();

      const listed = await authed(hub, hub.token, {
        method: 'GET',
        url: `/api/v1/workflows/${workflowId}/triggers`,
      });
      expect((listed.json() as { items: Json[] }).items.map((t) => t.preset)).toEqual([
        'github',
        'token',
        'generic_hmac',
      ]);
      expect(listed.body).not.toContain(SECRET);
      const removed = await authed(hub, hub.token, {
        method: 'DELETE',
        url: `/api/v1/workflow-triggers/${github.id}`,
      });
      expect(removed.statusCode).toBe(204);
      expect((await deliver(hub, github.path, body, {})).statusCode).toBe(404);
    } finally {
      await hub.close();
    }
  });

  it('keeps a condition’s rules when an app that does not know them saves the node without the field', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const rules = {
        match: 'all',
        items: [{ path: 'trigger.event', operator: '==', value: 'taskCreated' }],
      };
      const workflowId = await workflowWith(
        hub,
        [step('gate', 'condition', 'input exists', { rules }), step('tell', 'notify', 'x')],
        [edge('gate', 'tell')],
      );
      const older = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflows/${workflowId}`,
        payload: {
          nodes: [
            step('gate', 'condition', 'input exists', { title: 'renamed' }),
            step('tell', 'notify', 'x'),
          ],
          edges: [edge('gate', 'tell')],
        },
      });
      expect(older.statusCode, older.body).toBe(200);
      const saved = older.json() as { nodes: Json[] };
      expect(saved.nodes[0]).toMatchObject({ title: 'renamed', rules });
      const cleared = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflows/${workflowId}`,
        payload: {
          nodes: [
            step('gate', 'condition', 'input exists', { rules: null }),
            step('tell', 'notify', 'x'),
          ],
          edges: [edge('gate', 'tell')],
        },
      });
      expect((cleared.json() as { nodes: Json[] }).nodes[0]!.rules).toBeNull();
      // A rule that cannot be read is refused when the drawing is checked.
      const check = await authed(hub, hub.token, {
        method: 'POST',
        url: '/api/v1/workflows/validate',
        payload: {
          nodes: [
            step('gate', 'condition', null, {
              rules: {
                match: 'any',
                items: [
                  { path: 'trigger.event', operator: 'like', value: 'x' },
                  { path: 'steps.ghost.output', operator: 'exists', value: null },
                ],
              },
            }),
          ],
          edges: [],
        },
      });
      expect((check.json() as { problems: Json[] }).problems.map((p) => p.code)).toEqual([
        'rule_operator_invalid',
        'template_step_unknown',
      ]);
    } finally {
      await hub.close();
    }
  });
});
