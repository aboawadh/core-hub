/**
 * Settings → Secrets (owner's request 2026-09-28, DECISIONS §125): the names of every secret
 * the hub holds or knows of, and one value at a time — for the owner alone.
 *
 * - **The password every time.** The page opens locked; the account password is asked again
 *   (`auth.stepUp`) and answers a grant good for five minutes, kept in this component's state
 *   and nowhere else. Leaving the page ends it on the hub (`auth.endStepUp`); a reload forgets
 *   it; its time running out, or the hub refusing it, locks the page again.
 * - **One value at a time.** Values are masked. Showing one hides any other, and it hides
 *   itself after 30 seconds; Copy puts it on the clipboard (fetching it first, when hidden).
 *   Every value fetched is an audit row on the hub — who, which secret, when; never the value.
 * - **Nothing is kept.** No query cache, no storage: a value lives in this state while shown.
 */
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useAuth } from '../auth/context.js';
import { describeError } from '../auth/client.js';
import { useI18n } from '../i18n/context.js';
import { ProfileBadge } from '../shell/ProfileBadge.js';
import { Badge, Button, Card, CardHeader, EmptyState, Field, Input, Notice } from '../ui/index.js';
import { IconCopy, IconEye, IconEyeOff, destinationIcons } from '../ui/icons.js';
import {
  REVEAL_MS,
  endStepUp,
  groupSecrets,
  listSecrets,
  refusalOf,
  revealSecret,
  stepUp,
  type SecretEntry,
  type StepUpGrant,
} from './secrets.js';

const KEY_ICON = destinationIcons.secrets;

type Shown = { id: string; value: string; until: number };

export function SecretsTab() {
  const { t } = useI18n();
  const { client } = useAuth();
  const [grant, setGrant] = useState<StepUpGrant | null>(null);
  const [items, setItems] = useState<SecretEntry[] | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [shown, setShown] = useState<Shown | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const opened = useRef(false);

  const lock = useCallback(
    (why: string | null) => {
      setGrant(null);
      setItems(null);
      setShown(null);
      setCopied(null);
      setNotice(why);
      if (opened.current) void endStepUp(client);
      opened.current = false;
    },
    [client],
  );

  // Leaving the page ends the grant on the hub, and forgets everything here.
  useEffect(
    () => () => {
      if (opened.current) void endStepUp(client);
    },
    [client],
  );

  // One clock for the countdown, the value hiding itself and the grant running out.
  useEffect(() => {
    if (!grant) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [grant]);
  useEffect(() => {
    if (shown && now >= shown.until) setShown(null);
    if (grant && now >= Date.parse(grant.expires_at)) lock(t('secrets.expired'));
  }, [now, shown, grant, lock, t]);

  const refused = (caught: unknown): void => {
    const why = refusalOf(caught);
    if (why === 'step_up_required') lock(t('secrets.expired'));
    else if (why === 'unsupported') setError(t('secrets.unsupported'));
    else setError(describeError(caught, t));
  };

  const unlock = async (event: FormEvent) => {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const granted = await stepUp(client, password);
      opened.current = true;
      // The password is gone from the page the moment the hub has it.
      setPassword('');
      setGrant(granted);
      setNow(Date.now());
      setItems(await listSecrets(client, granted.grant));
    } catch (caught) {
      setPassword('');
      refused(caught);
    } finally {
      setBusy(false);
    }
  };

  const fetchValue = async (entry: SecretEntry): Promise<string | null> => {
    if (!grant) return null;
    if (shown?.id === entry.id) return shown.value;
    try {
      return await revealSecret(client, grant.grant, entry.id);
    } catch (caught) {
      refused(caught);
      return null;
    }
  };

  const reveal = async (entry: SecretEntry) => {
    setError(null);
    const value = await fetchValue(entry);
    // One at a time: showing this one hides any other.
    if (value !== null) setShown({ id: entry.id, value, until: Date.now() + REVEAL_MS });
  };

  const copy = async (entry: SecretEntry) => {
    setError(null);
    const value = await fetchValue(entry);
    if (value === null) return;
    try {
      await navigator.clipboard.writeText(value);
      setCopied(entry.id);
      window.setTimeout(() => setCopied((id) => (id === entry.id ? null : id)), 2000);
    } catch {
      setError(t('secrets.copy_failed'));
    }
  };

  const groups = useMemo(() => (items ? groupSecrets(items) : []), [items]);
  const left = grant ? Math.max(0, Math.ceil((Date.parse(grant.expires_at) - now) / 1000)) : 0;

  if (!grant) {
    return (
      <Card tone="flat" padding="md" testId="secrets-locked">
        <CardHeader title={t('secrets.unlock_title')} subtitle={t('secrets.unlock_hint')} />
        <form className="flex max-w-sm flex-col gap-3" onSubmit={(event) => void unlock(event)}>
          <Field label={t('secrets.password')}>
            {(props) => (
              <Input
                {...props}
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                data-testid="secrets-password"
              />
            )}
          </Field>
          {notice && (
            <Notice tone="warning" testId="secrets-notice">
              {notice}
            </Notice>
          )}
          {error && (
            <Notice tone="danger" role="alert" testId="secrets-error">
              {error}
            </Notice>
          )}
          <Button
            type="submit"
            variant="primary"
            className="self-start"
            disabled={!password}
            loading={busy}
            icon={KEY_ICON ? <KEY_ICON size={16} /> : undefined}
            data-testid="secrets-unlock"
          >
            {t('secrets.unlock')}
          </Button>
        </form>
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-4" data-testid="secrets-open">
      <div className="flex flex-wrap items-center gap-2">
        <p className="min-w-0 flex-1 text-sm text-muted">{t('secrets.audit_note')}</p>
        <Badge tone="warning" testId="secrets-time-left">
          {t('secrets.time_left', {
            minutes: Math.floor(left / 60),
            seconds: String(left % 60).padStart(2, '0'),
          })}
        </Badge>
        <Button size="sm" onClick={() => lock(null)} data-testid="secrets-lock">
          {t('secrets.lock')}
        </Button>
      </div>
      {error && (
        <Notice tone="danger" role="alert" testId="secrets-error">
          {error}
        </Notice>
      )}
      {items && items.length === 0 && (
        <EmptyState
          icon={KEY_ICON ? <KEY_ICON size={20} /> : undefined}
          title={t('secrets.empty')}
        />
      )}
      {groups.map((group) => (
        <Card key={group.kind} tone="flat" padding="md" testId={`secrets-group-${group.kind}`}>
          <CardHeader title={t(`secrets.kind.${knownKind(group.kind)}`)} />
          {group.profiles.map(({ profile, items: rows }) => (
            <section key={profile ?? '-'} className="mt-2 first:mt-0">
              <h3 className="mb-1 text-xs font-medium text-muted">
                {profile === null ? (
                  t(group.kind === 'provider_key' ? 'secrets.shared' : 'secrets.whole_hub')
                ) : (
                  <ProfileBadge profile={profile} />
                )}
              </h3>
              <ul className="flex flex-col divide-y divide-line">
                {rows.map((entry) => {
                  const visible = shown?.id === entry.id ? shown : null;
                  return (
                    <li
                      key={entry.id}
                      className="flex flex-wrap items-center gap-2 py-2"
                      data-testid="secret-row"
                      data-secret-kind={entry.kind}
                    >
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium" dir="auto">
                          {entry.label ?? entry.name}
                        </p>
                        <p className="truncate font-mono text-xs text-muted" dir="ltr">
                          {entry.name}
                        </p>
                      </div>
                      <code
                        className="max-w-full truncate rounded bg-surface-2 px-2 py-1 font-mono text-xs"
                        dir="ltr"
                        data-testid="secret-value"
                        aria-label={visible ? undefined : t('secrets.hidden_value')}
                      >
                        {visible ? visible.value : '••••••••••••'}
                      </code>
                      {visible && (
                        <span className="text-xs text-muted" data-testid="secret-hides-in">
                          {t('secrets.hides_in', {
                            seconds: Math.max(0, Math.ceil((visible.until - now) / 1000)),
                          })}
                        </span>
                      )}
                      <Button
                        size="sm"
                        variant="ghost"
                        iconOnly
                        aria-label={t(visible ? 'secrets.hide' : 'secrets.show', {
                          name: entry.name,
                        })}
                        tooltip={t(visible ? 'secrets.hide' : 'secrets.show', {
                          name: entry.name,
                        })}
                        icon={visible ? <IconEyeOff size={16} /> : <IconEye size={16} />}
                        onClick={() => (visible ? setShown(null) : void reveal(entry))}
                        data-testid={visible ? 'secret-hide' : 'secret-show'}
                      />
                      <Button
                        size="sm"
                        variant="ghost"
                        iconOnly
                        aria-label={t('secrets.copy', { name: entry.name })}
                        tooltip={
                          copied === entry.id
                            ? t('secrets.copied')
                            : t('secrets.copy', { name: entry.name })
                        }
                        icon={<IconCopy size={16} />}
                        onClick={() => void copy(entry)}
                        data-testid="secret-copy"
                      />
                      {copied === entry.id && (
                        <span className="text-xs text-muted" role="status">
                          {t('secrets.copied')}
                        </span>
                      )}
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </Card>
      ))}
    </div>
  );
}

const KNOWN = new Set(['provider_key', 'channel', 'mcp', 'webhook_out', 'webhook_in']);
/** A kind a newer hub names that this page does not know is shown as "other". */
function knownKind(kind: string): string {
  return KNOWN.has(kind) ? kind : 'other';
}
