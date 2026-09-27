/**
 * "Test this step" (DECISIONS §127): the selected step tried on its own with a sample input
 * and, for a run a trigger starts, a sample event — the rendered words, a condition's yes or
 * no, and (only when asked) an agent's real answer. Nothing is saved and no run is made.
 */
import { useState } from 'react';
import { describeError } from '../../auth/client.js';
import { useI18n } from '../../i18n/context.js';
import { Button, Checkbox, Notice, Textarea } from '../../ui/index.js';
import { toWrite, type Draft, type WfNode } from './model.js';
import { useStepTest } from './queries.js';

/** A ClickUp-shaped sample event, to start from. */
export const SAMPLE_TRIGGER = JSON.stringify(
  {
    event: 'taskStatusUpdated',
    task_id: 'sample-task',
    body: { history_items: [{ field: 'status', after: { status: 'review' } }] },
  },
  null,
  2,
);

export function StepTest({ node, profile }: { node: WfNode; profile: string }) {
  const { t } = useI18n();
  const test = useStepTest(profile);
  const [input, setInput] = useState('');
  const [trigger, setTrigger] = useState(SAMPLE_TRIGGER);
  const [execute, setExecute] = useState(false);
  const [badJson, setBadJson] = useState(false);
  const run = () => {
    let parsed: unknown = null;
    if (trigger.trim()) {
      try {
        parsed = JSON.parse(trigger);
      } catch {
        setBadJson(true);
        return;
      }
    }
    setBadJson(false);
    const single: Draft = {
      name: 'test',
      description: null,
      working_dir: null,
      nodes: [node],
      edges: [],
    };
    test.mutate({
      node: toWrite(single).nodes[0]!,
      input: input.trim() || null,
      trigger: parsed,
      execute: node.kind === 'agent' && execute,
    });
  };
  const result = test.data;
  return (
    <details
      className="flex flex-col gap-2 border-t border-line pt-3"
      data-testid="workflow-step-test"
    >
      <summary className="cursor-pointer text-sm font-medium">{t('workflows.test.title')}</summary>
      <Textarea
        value={input}
        onChange={(event) => setInput(event.target.value)}
        placeholder={t('workflows.test.input')}
        aria-label={t('workflows.test.input')}
        rows={2}
        dir="auto"
        data-testid="workflow-step-test-input"
      />
      <Textarea
        value={trigger}
        onChange={(event) => setTrigger(event.target.value)}
        aria-label={t('workflows.test.trigger')}
        rows={6}
        dir="ltr"
        className="font-mono text-xs"
        data-testid="workflow-step-test-trigger"
      />
      {badJson && <Notice tone="danger">{t('workflows.test.bad_json')}</Notice>}
      {node.kind === 'agent' && (
        <Checkbox
          checked={execute}
          onChange={setExecute}
          label={t('workflows.test.execute')}
          testId="workflow-step-test-execute"
        />
      )}
      <Button
        size="sm"
        variant="secondary"
        loading={test.isPending}
        onClick={run}
        data-testid="workflow-step-test-run"
      >
        {t('workflows.test.run')}
      </Button>
      {test.error && <Notice tone="danger">{describeError(test.error, t)}</Notice>}
      {result && (
        <div className="flex flex-col gap-1 text-xs" data-testid="workflow-step-test-result">
          {result.answer !== null && (
            <span data-answer={String(result.answer)}>
              {t(result.answer ? 'workflows.test.yes' : 'workflows.test.no')}
            </span>
          )}
          {result.rendered !== null && (
            <pre className="whitespace-pre-wrap rounded-md bg-sunken p-2" dir="auto">
              {result.rendered}
            </pre>
          )}
          {result.executed && result.output !== null && node.kind === 'agent' && (
            <pre
              className="whitespace-pre-wrap rounded-md bg-sunken p-2"
              dir="auto"
              data-testid="workflow-step-test-output"
            >
              {result.output}
            </pre>
          )}
          {result.error && (
            <span className="text-danger" dir="auto">
              {result.error}
            </span>
          )}
        </div>
      )}
    </details>
  );
}
