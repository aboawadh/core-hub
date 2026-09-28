/**
 * The assembly of a trigger-to-agent-to-message flow (DECISIONS §127): where a run is, in a
 * person's words (`phase`); who is told when a run fails (`on_failure`); and one step tried on
 * its own with a sample.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { authed, signedInHub } from '../../../tests/unit/helpers.js';
import { phaseOf, registerWorkflowPorts, workflowEngineFor } from './index.js';
import type { WorkflowPorts } from './workflow-engine.js';

type Json = Record<string, unknown>;
type Hub = Awaited<ReturnType<typeof signedInHub>>;

const AGENT = '01KAGENTXYZ000000000000000';
const notices: Array<{ title: string; body: string | null }> = [];
const telegram: Array<{ chat: string; text: string }> = [];
const prompts: string[] = [];

let previous: ReturnType<typeof registerWorkflowPorts> | undefined;
function fakePorts(ports: Partial<WorkflowPorts> = {}) {
  previous = registerWorkflowPorts(() => ({
    agentTurn: async (_scope, input) => {
      prompts.push(input.prompt);
      return {
        sessionId: 'S',
        runId: 'R',
        status: 'succeeded',
        output: 'نعم، جاهزة.',
        error: null,
      };
    },
    notice: (_scope, input) => void notices.push(input),
    approvals: { raise: () => '01J8QK3ZR2W7M5N4P6T8V9X0AP', cancel: () => 0 },
    messages: {
      telegramToken: () => '123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef012',
      telegramApi: 'http://telegram.test',
      fetch: (async (_url: string | URL | Request, init?: RequestInit) => {
        const body = JSON.parse(String(init?.body)) as { chat_id: string; text: string };
        telegram.push({ chat: body.chat_id, text: body.text });
        return Response.json({ ok: true, result: { message_id: 1 + telegram.length } });
      }) as typeof fetch,
      post: async () => ({ sessionId: 'S', messageId: 'M', recreated: false, title: null }),
    },
    ...ports,
  }));
}
afterEach(() => {
  if (previous !== undefined) registerWorkflowPorts(previous);
  previous = undefined;
  notices.length = 0;
  telegram.length = 0;
  prompts.length = 0;
});

const step = (id: string, kind: string, input: string | null, extra: Json = {}) => ({
  id,
  kind,
  title: id,
  agent_id: kind === 'agent' ? AGENT : null,
  model: null,
  provider: null,
  reasoning_effort: null,
  skills: [],
  input,
  approval_required: false,
  position: { x: 0, y: 0 },
  ...extra,
});

async function create(hub: Hub, body: Json) {
  const res = await authed(hub, hub.token, {
    method: 'POST',
    url: '/api/v1/workflows',
    payload: body,
  });
  expect(res.statusCode, res.body).toBe(201);
  return res.json() as Json & { id: string };
}

async function run(hub: Hub, id: string) {
  const res = await authed(hub, hub.token, {
    method: 'POST',
    url: `/api/v1/workflows/${id}/run`,
    payload: { input: 'x' },
  });
  expect(res.statusCode, res.body).toBe(202);
  await workflowEngineFor(hub.app).settled();
  const runId = (res.json() as { workflow_run_id: string }).workflow_run_id;
  return (
    await authed(hub, hub.token, { method: 'GET', url: `/api/v1/workflow-runs/${runId}` })
  ).json() as Json;
}

describe('a run’s phase', () => {
  it('reads received, analyzing, needs_input, approved, executing, completed and failed', () => {
    const s = (id: string, nodeType: string, status: string) => ({ id, nodeType, status });
    expect(phaseOf({ status: 'running' }, [])).toBe('received');
    expect(phaseOf({ status: 'running' }, [s('1', 'agent_run', 'running')])).toBe('analyzing');
    expect(phaseOf({ status: 'waiting_approval' }, [s('1', 'agent_run', 'succeeded')])).toBe(
      'needs_input',
    );
    expect(
      phaseOf({ status: 'running' }, [
        s('1', 'agent_run', 'succeeded'),
        s('2', 'approval', 'succeeded'),
      ]),
    ).toBe('approved');
    expect(
      phaseOf({ status: 'running' }, [
        s('1', 'agent_run', 'succeeded'),
        s('2', 'approval', 'succeeded'),
        s('3', 'agent_run', 'running'),
      ]),
    ).toBe('executing');
    expect(phaseOf({ status: 'running' }, [s('1', 'notify', 'running')])).toBe('executing');
    expect(phaseOf({ status: 'succeeded' }, [])).toBe('completed');
    expect(phaseOf({ status: 'timed_out' }, [])).toBe('failed');
  });

  it('is on every run the hub answers', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const flow = await create(hub, {
        name: 'p',
        nodes: [step('tell', 'notify', 'hi')],
        edges: [],
      });
      expect(await run(hub, flow.id)).toMatchObject({ status: 'succeeded', phase: 'completed' });
      const gated = await create(hub, {
        name: 'g',
        nodes: [step('ask', 'approval', 'OK?')],
        edges: [],
      });
      expect(await run(hub, gated.id)).toMatchObject({ status: 'waiting', phase: 'needs_input' });
    } finally {
      await hub.close();
    }
  });
});

describe('who is told when a run fails', () => {
  it('the inbox and the alert’s Telegram chat, once; kept when a save leaves it out, gone with null', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const flow = await create(hub, {
        name: 'Nightly report',
        nodes: [step('tell', 'notify', '   ')],
        edges: [],
        on_failure: {
          inbox: true,
          send: { targets: [{ platform: 'telegram', chat_id: '-100777' }] },
        },
      });
      expect(flow.on_failure).toEqual({
        inbox: true,
        send: { targets: [{ platform: 'telegram', chat_id: '-100777' }] },
      });
      const failed = await run(hub, flow.id);
      expect(failed).toMatchObject({ status: 'failed', phase: 'failed' });
      expect(notices).toEqual([
        { title: 'Nightly report: run failed', body: 'tell: the notice has no words' },
      ]);
      expect(telegram).toEqual([
        { chat: '-100777', text: 'Nightly report: the run failed.\ntell: the notice has no words' },
      ]);

      const renamed = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflows/${flow.id}`,
        payload: { name: 'Renamed', nodes: [step('tell', 'notify', 'ok')] },
      });
      expect((renamed.json() as Json).on_failure).toMatchObject({ inbox: true });
      const cleared = await authed(hub, hub.token, {
        method: 'PATCH',
        url: `/api/v1/workflows/${flow.id}`,
        payload: { on_failure: null },
      });
      expect((cleared.json() as Json).on_failure).toBeNull();

      // A run that succeeds tells nobody.
      notices.length = 0;
      await run(hub, flow.id);
      expect(notices.filter((n) => n.title.includes('failed'))).toEqual([]);
    } finally {
      await hub.close();
    }
  });
});

describe('one step tried on its own', () => {
  async function tryStep(hub: Hub, body: Json) {
    const res = await authed(hub, hub.token, {
      method: 'POST',
      url: '/api/v1/workflows/test-step',
      payload: body,
    });
    expect(res.statusCode, res.body).toBe(200);
    return res.json() as Json;
  }

  it('answers a condition, renders a template, and runs an agent only when asked', async () => {
    fakePorts();
    const hub = await signedInHub();
    try {
      const trigger = { event: 'taskCreated', task_id: 't-9', body: { priority: 1 } };
      expect(
        await tryStep(hub, {
          node: step('only', 'condition', null, {
            rules: {
              match: 'all',
              items: [
                { path: 'trigger.event', operator: '==', value: 'taskCreated' },
                { path: 'trigger.body.priority', operator: '<=', value: '2' },
              ],
            },
          }),
          trigger,
        }),
      ).toEqual({
        rendered: 'all: trigger.event == "taskCreated"; trigger.body.priority <= "2"',
        answer: true,
        output: 'true',
        error: null,
        executed: true,
      });
      expect(
        await tryStep(hub, {
          node: step('c', 'condition', 'trigger.event == taskDeleted'),
          trigger,
        }),
      ).toMatchObject({ answer: false });
      expect(
        await tryStep(hub, { node: step('c', 'condition', 'nonsense'), trigger }),
      ).toMatchObject({
        answer: null,
        error: 'condition_operator_missing',
      });

      const agent = step(
        'read',
        'agent',
        'Task {{trigger.task_id}} after {{steps.gate.output}}: {{input}}',
      );
      const dry = await tryStep(hub, { node: agent, trigger, steps: { gate: 'yes' }, input: 'go' });
      expect(dry).toMatchObject({
        rendered: 'Task t-9 after yes: go',
        executed: false,
        output: null,
      });
      expect(prompts).toEqual([]);
      const real = await tryStep(hub, { node: agent, trigger, execute: true });
      expect(real).toMatchObject({ output: 'نعم، جاهزة.', executed: true, error: null });
      expect(prompts).toEqual(['Task t-9 after : ']);

      expect(await tryStep(hub, { node: step('w', 'delay', '99999') })).toMatchObject({
        error: '"99999" is not 0 to 3600 seconds',
      });
      const message = await tryStep(hub, {
        node: step('m', 'notify', 'Done {{trigger.task_id}}', {
          send: { targets: [{ platform: 'telegram', chat_id: '-1' }] },
        }),
        trigger,
      });
      expect(message).toMatchObject({ rendered: 'Done t-9', executed: false });
      expect(telegram).toEqual([]);
    } finally {
      await hub.close();
    }
  });
});
