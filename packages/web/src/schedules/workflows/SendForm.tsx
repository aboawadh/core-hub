/**
 * A "Send message" step's targets (DECISIONS §124): Telegram — a chat id, sent by the
 * profile's own bot — and/or a conversation of this hub the words are posted in. "Send test
 * message" sends the step's words now, to the same targets, and says what each one did.
 */
import { useState } from 'react';
import { describeError } from '../../auth/client.js';
import { useI18n } from '../../i18n/context.js';
import { Button, Checkbox, Input, Notice, Select } from '../../ui/index.js';
import type { Send, SendTarget, WfNode } from './model.js';
import { useProfileConversations, useSendTest } from './queries.js';

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
  const telegram = send.targets.find((target) => target.platform === 'telegram') ?? null;
  const conversation = send.targets.find((target) => target.platform === 'core_hub') ?? null;
  const conversations = useProfileConversations(profile, !!conversation);
  const test = useSendTest(profile);
  const [chat, setChat] = useState(telegram?.chat_id ?? '');

  /** Replace (or remove, with `null`) the target of one platform; the others stay as they are. */
  const setTarget = (platform: string, next: SendTarget | null) => {
    const others = send.targets.filter((target) => target.platform !== platform);
    update({ send: { targets: next ? [...others, next] : others } });
  };

  return (
    <div className="flex flex-col gap-2" data-testid="workflow-send">
      <p className="text-xs font-medium">{t('workflows.send.targets')}</p>
      <Checkbox
        checked={!!telegram}
        onChange={(next) =>
          setTarget('telegram', next ? { platform: 'telegram', chat_id: chat.trim() } : null)
        }
        label={t('workflows.send.telegram')}
        testId="workflow-send-telegram"
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
            data-testid="workflow-send-chat"
          />
          <p className="text-xs text-muted">{t('workflows.send.telegram_hint')}</p>
        </>
      )}
      <Checkbox
        checked={!!conversation}
        onChange={(next) => setTarget('core_hub', next ? { platform: 'core_hub' } : null)}
        label={t('workflows.send.conversation')}
        testId="workflow-send-conversation"
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
            testId="workflow-send-session"
          />
          <p className="text-xs text-muted">{t('workflows.send.conversation_hint')}</p>
        </>
      )}
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
