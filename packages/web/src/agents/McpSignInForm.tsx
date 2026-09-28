/**
 * "Add server" → sign in (DECISIONS §122, amended — the owner's request of 2026-09-28): most
 * people do not know to write `{"url": …}`. This form takes the server's address and a name
 * (read from the host, editable), writes the smallest block a remote server that signs in needs
 * — `url` and `auth: oauth` — and starts the sign-in at once in the tab it opened on the click.
 * The row then follows the sign-in, turns Connected and runs Test by itself.
 *
 * `auth: oauth` is the one key added: it tells Hermes the server signs in (so a test before the
 * sign-in says "no token" rather than a 401 from each transport, and Hermes skips the
 * token-less content-type probe). `connect_timeout` and `skip_preflight` are not needed:
 * Hermes's sign-in waits 315 s on its own, and its preflight is already skipped for OAuth.
 */
import { useState } from 'react';
import { HubApiError } from '@corehub/contracts';
import { describeError } from '../auth/client.js';
import { useI18n } from '../i18n/context.js';
import { Button, Field, Input, Notice } from '../ui/index.js';
import { useCreateMcpServer, useStartMcpOAuth, type McpOAuthFlow } from './skills.js';

const NAME = /^[A-Za-z0-9._-]{1,60}$/;
const LOCAL = new Set(['localhost', '127.0.0.1', '[::1]', '::1']);

/** The address, if it is one a sign-in can use: https anywhere, http only on this machine. */
export function signInUrl(text: string): URL | null {
  let url: URL;
  try {
    url = new URL(text.trim());
  } catch {
    return null;
  }
  if (url.username || url.password) return null;
  if (url.protocol === 'https:') return url;
  if (
    url.protocol === 'http:' &&
    (LOCAL.has(url.hostname) || url.hostname.endsWith('.localhost'))
  ) {
    return url;
  }
  return null;
}

/**
 * A name from the host that is not taken yet: `mcp.clickup.com` → `clickup`, the service's own
 * word rather than the `mcp`/`api`/`www` in front of it; `clickup-2` when `clickup` exists.
 */
export function nameFor(url: URL | null, taken: readonly string[]): string {
  const labels = (url?.hostname ?? '')
    .replace(/^\[|\]$/g, '')
    .split('.')
    .filter(Boolean);
  while (labels.length > 2 && ['mcp', 'api', 'www'].includes(labels[0]!.toLowerCase())) {
    labels.shift();
  }
  if (labels.length > 1 && ['mcp', 'api', 'www'].includes(labels[0]!.toLowerCase())) labels.shift();
  const base =
    (labels[0] ?? '')
      .toLowerCase()
      .replace(/[^a-z0-9._-]/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 50) || 'server';
  if (!taken.includes(base)) return base;
  for (let n = 2; ; n += 1) if (!taken.includes(`${base}-${n}`)) return `${base}-${n}`;
}

export function McpSignInForm({
  agentId,
  taken,
  onStarted,
  onCancel,
}: {
  agentId: string | undefined;
  taken: readonly string[];
  /** The server was written and its sign-in started: the row follows `flow` from here. */
  onStarted(name: string, flow: McpOAuthFlow): void;
  onCancel(): void;
}) {
  const { t } = useI18n();
  const create = useCreateMcpServer(agentId);
  const start = useStartMcpOAuth(agentId);
  const [address, setAddress] = useState('');
  const [typedName, setTypedName] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const url = signInUrl(address);
  const name = typedName ?? (url ? nameFor(url, taken) : '');
  const badUrl = address.trim() !== '' && !url;
  const badName = name !== '' && !NAME.test(name);
  const takenName = name !== '' && taken.includes(name);
  const pending = create.isPending || start.isPending;

  const submit = () => {
    if (!url || !name || badName || takenName) return;
    setFailure(null);
    // Opened on the click itself: a tab opened after the requests would be blocked.
    let tab: Window | null = null;
    try {
      tab = window.open('about:blank', '_blank');
    } catch {
      tab = null;
    }
    create.mutate(
      {
        name,
        transport: 'http',
        enabled: true,
        config: { url: url.toString(), auth: 'oauth' },
      },
      {
        onError: (error) => {
          tab?.close();
          setFailure(
            error instanceof HubApiError && error.status === 409
              ? t('mcp.signin_add.name_taken', { name })
              : describeError(error, t),
          );
        },
        onSuccess: () =>
          start.mutate(name, {
            onSuccess: (flow) => {
              if (tab && !tab.closed) {
                if (flow.status === 'pending' && flow.authorization_url) {
                  tab.opener = null;
                  tab.location.href = flow.authorization_url;
                } else tab.close();
              }
              onStarted(name, flow);
            },
            onError: (error) => {
              tab?.close();
              // The server is saved; the row offers Connect again with the reason shown here.
              setFailure(describeError(error, t));
            },
          }),
      },
    );
  };

  return (
    <div className="flex flex-col gap-3" data-testid="mcp-signin-add">
      <p className="text-sm text-muted">{t('mcp.signin_add.about')}</p>
      <Field
        label={t('mcp.signin_add.url')}
        {...(badUrl ? { error: t('mcp.signin_add.url_bad') } : {})}
      >
        {(props) => (
          <Input
            {...props}
            dir="ltr"
            inputMode="url"
            placeholder="https://mcp.example.com/mcp"
            value={address}
            invalid={badUrl}
            onChange={(event) => setAddress(event.target.value)}
            data-testid="mcp-signin-url"
          />
        )}
      </Field>
      <Field
        label={t('mcp.name')}
        {...(badName
          ? { error: t('mcp.name_bad') }
          : takenName
            ? { error: t('mcp.signin_add.name_taken', { name }) }
            : {})}
      >
        {(props) => (
          <Input
            {...props}
            dir="ltr"
            value={name}
            invalid={badName || takenName}
            onChange={(event) => setTypedName(event.target.value)}
            data-testid="mcp-signin-name"
          />
        )}
      </Field>
      <p className="text-xs text-muted">{t('mcp.oauth.per_profile')}</p>
      {failure && <Notice tone="danger">{failure}</Notice>}
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="primary"
          disabled={!url || !name || badName || takenName || pending}
          onClick={submit}
          data-testid="mcp-signin-submit"
        >
          {pending ? t('mcp.oauth.starting_short') : t('mcp.signin_add.submit')}
        </Button>
      </div>
    </div>
  );
}
