/**
 * A "Send message" step's targets (DECISIONS §124): Telegram — a chat id, sent by the
 * profile's own bot — and/or a conversation of this hub the words are posted in. "Send test
 * message" sends the step's words now, to the same targets, and says what each one did. The
 * same targets say where a workflow's failure alert goes (§127).
 */
import { useState } from 'react';
import { describeError } from '../../auth/client.js';
import { useI18n } from '../../i18n/context.js';
import { Button, Checkbox, Input, Notice, Select, Switch } from '../../ui/index.js';
import type { FailureAlert, Send, SendTarget, WfNode } from './model.js';
import { useProfileConversations, useSendTest } from './queries.js';

/** Telegram and/or a conversation, as a `Send`'s targets. */
export function SendTargets({
  send,
  profile,
  onChange,
  testPrefix = 'workflow-send',
}: {
  send: Send;
  profile: string;
  onChange: (send: Send) => void;
  testPrefix?: string;
}) {
  const { t } = useI18n();
  const telegram = send.targets.find((target) => target.platform === 'telegram') ?? null;
  const conversation = send.targets.find((target) => target.platform === 'core_hub') ?? null;
  const conversations = useProfileConversations(profile, !!conversation);
  const [chat, setChat] = useState(telegram?.chat_id ?? '');

  /** Replace (or remove, with `null`) the target of one platform; the others stay as they are. */
  const setTarget = (platform: string, next: SendTarget | null) => {
    const others = send.targets.filter((target) => target.platform !== platform);
    onChange({ targets: next ? [...others, next] : others });
  };

  return (
    <>
      <Checkbox
        checked={!!telegram}
        onChange={(next) =>
          setTarget('telegram', next ? { platform: 'telegram', chat_id: chat.trim() } : null)
        }
        label={t('workflows.send.telegram')}
        testId={`${testPrefix}-telegram`}
      />
      {telegram && (
        <>
          <Input
            value={chat}
            onChange={(event) => {
              setChat(event.target.value);
              setTarget('telegram', { platform: 'telegram', chat_id: event.target.value.trim() });
            }}
            placeholder="-1001234567890"
            aria-label={t('workflows.send.chat_id')}
            dir="ltr"
            data-testid={`${testPrefix}-chat`}
          />
          <p className="text-xs text-muted">{t('workflows.send.telegram_hint')}</p>
        </>
      )}
      <Checkbox
        checked={!!conversation}
        onChange={(next) => setTarget('core_hub', next ? { platform: 'core_hub' } : null)}
        label={t('workflows.send.conversation')}
        testId={`${testPrefix}-conversation`}
      />
      {conversation && (
        <>
          <Select
            value={conversation.session_id ?? null}
            onValueChange={(value) => {
              const picked = (conversations.data ?? []).find((each) => each.id === value);
              setTarget('core_hub', {
                platform: 'core_hub',
                session_id: picked?.id ?? null,
                title: picked?.title ?? null,
                agent_id: picked?.agent_id ?? null,
              });
            }}
            options={(conversations.data ?? []).map((each) => ({
              value: each.id,
              label: each.title || t('workflows.send.untitled'),
            }))}
            placeholder={conversation.title ?? t('workflows.send.pick')}
            label={t('workflows.send.conversation')}
            testId={`${testPrefix}-session`}
          />
          <p className="text-xs text-muted">{t('workflows.send.conversation_hint')}</p>
        </>
      )}
    </>
  );
}

export function SendForm({
  node,
  profile,
  update,
}: {
  node: WfNode;
  profile: string;
  update: (patch: Partial<WfNode>) => void;
}) {
  const { t } = useI18n();
  const send: Send = node.send ?? { targets: [] };
  const test = useSendTest(profile);

  return (
    <div className="flex flex-col gap-2" data-testid="workflow-send">
      <p className="text-xs font-medium">{t('workflows.send.targets')}</p>
      <SendTargets send={send} profile={profile} onChange={(next) => update({ send: next })} />
      <Button
        size="sm"
        variant="secondary"
        disabled={send.targets.length === 0 || !(node.input ?? '').trim()}
        loading={test.isPending}
        onClick={() => test.mutate({ send, text: (node.input ?? '').trim() })}
        data-testid="workflow-send-test"
      >
        {t('workflows.send.test')}
      </Button>
      {test.error && <Notice tone="danger">{describeError(test.error, t)}</Notice>}
      {test.data && (
        <div className="flex flex-col gap-1 text-xs" data-testid="workflow-send-test-result">
          <span data-status={test.data.status}>
            {t(`workflows.send.status.${test.data.status}`)}
          </span>
          {test.data.failures.map((failure) => (
            <span key={failure.target} dir="auto" className="text-danger">
              {failure.target}: {failure.reason}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

/** Who is told when a run of the workflow fails (§127): the inbox, and optionally targets. */
export function FailureAlertForm({
  alert,
  profile,
  onChange,
}: {
  alert: FailureAlert | null;
  profile: string;
  onChange: (alert: FailureAlert | null) => void;
}) {
  const { t } = useI18n();
  const current: FailureAlert = alert ?? { inbox: false, send: null };
  const set = (next: FailureAlert) =>
    onChange(next.inbox || next.send?.targets.length ? next : null);
  return (
    <section className="flex flex-col gap-2 border-t border-line pt-3" data-testid="workflow-alert">
      <h3 className="text-sm font-medium">{t('workflows.alert.title')}</h3>
      <Switch
        checked={current.inbox}
        onChange={(next) => set({ ...current, inbox: next })}
        label={t('workflows.alert.inbox')}
        testId="workflow-alert-inbox"
      />
      <SendTargets
        send={current.send ?? { targets: [] }}
        profile={profile}
        onChange={(send) => set({ ...current, send })}
        testPrefix="workflow-alert"
      />
      <p className="text-xs text-muted">{t('workflows.alert.hint')}</p>
    </section>
  );
}
