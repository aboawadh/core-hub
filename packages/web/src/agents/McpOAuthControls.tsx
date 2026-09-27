/**
 * Signing a remote MCP server in by OAuth, on its row of the MCP page (DECISIONS §121).
 *
 * Hermes does the sign-in; this page only starts it, opens the provider's page in a new tab
 * and waits. The tab is opened on the click itself (a tab opened after a request would be
 * blocked as a pop-up) and pointed at the provider once Hermes has named the page; when the
 * browser blocked it anyway, the link is there to open by hand. The provider sends the browser
 * back to the hub, the hub hands it to Hermes, and this page learns the outcome by asking.
 * Each profile signs in on its own, and the page says so.
 *
 * Nothing here holds a token: the row reads `connected`, `expired`, `not connected` from the
 * hub, which reads it from the metadata of Hermes's file.
 */
import { useState } from 'react';
import { describeError } from '../auth/client.js';
import { useI18n } from '../i18n/context.js';
import { Badge, Button, Notice, Spinner, useConfirm, type BadgeTone } from '../ui/index.js';
import {
  useCancelMcpOAuth,
  useDisconnectMcpOAuth,
  useMcpOAuthFlow,
  useStartMcpOAuth,
  type McpOAuthState,
  type McpServer,
} from './skills.js';

const TONE: Record<McpOAuthState['status'], BadgeTone> = {
  connected: 'success',
  expired: 'warning',
  not_connected: 'neutral',
  error: 'danger',
};

/**
 * Whether the row offers OAuth at all: a remote server from a hub that reports it, that either
 * signs in by OAuth already or does not carry its own credential in `headers` (Hermes refuses
 * a sign-in for a server that authenticates by header).
 */
export function offersOAuth(server: McpServer): boolean {
  if (!server.oauth || server.transport === 'stdio') return false;
  const headers = server.config.headers;
  const hasHeaders =
    !!headers && typeof headers === 'object' && Object.keys(headers as object).length > 0;
  return server.oauth.required || server.oauth.status !== 'not_connected' || !hasHeaders;
}

/** The status chip, next to the server's name. Only once there is something to say. */
export function McpOAuthChip({ server }: { server: McpServer }) {
  const { t } = useI18n();
  const oauth = server.oauth;
  if (!oauth || !offersOAuth(server)) return null;
  if (!oauth.required && oauth.status === 'not_connected') return null;
  return (
    <Badge tone={TONE[oauth.status]} dot testId={`mcp-oauth-status-${server.name}`}>
      {t(`mcp.oauth.status.${oauth.status}`)}
    </Badge>
  );
}

/** Starting, waiting on and ending one sign-in; shared by the row and the failed test. */
export function useMcpOAuthConnect(agentId: string | undefined, name: string) {
  const start = useStartMcpOAuth(agentId);
  const [flowId, setFlowId] = useState<string | null>(null);
  const flow = useMcpOAuthFlow(agentId, name, flowId);
  const connect = () => {
    let tab: Window | null = null;
    try {
      tab = window.open('about:blank', '_blank');
    } catch {
      tab = null;
    }
    setFlowId(null);
    start.mutate(name, {
      onSuccess: (started) => {
        setFlowId(started.id);
        if (!tab || tab.closed) return;
        if (started.status === 'pending' && started.authorization_url) {
          tab.opener = null;
          tab.location.href = started.authorization_url;
        } else {
          tab.close();
        }
      },
      onError: () => tab?.close(),
    });
  };
  const current = flow.data ?? start.data ?? null;
  const reset = () => {
    setFlowId(null);
    start.reset();
  };
  return { connect, start, flow, current, flowId, reset };
}

export type McpOAuthConnect = ReturnType<typeof useMcpOAuthConnect>;

/** The buttons and the sign-in's progress, under the row. */
export function McpOAuthControls({
  agentId,
  server,
  oauth,
  onTest,
}: {
  agentId: string | undefined;
  server: McpServer;
  oauth: McpOAuthConnect;
  onTest(): void;
}) {
  const { t } = useI18n();
  const cancel = useCancelMcpOAuth(agentId);
  const disconnect = useDisconnectMcpOAuth(agentId);
  const { ask, dialog } = useConfirm();
  const state = server.oauth;
  if (!state || !offersOAuth(server)) return null;
  const { current } = oauth;
  const waiting = oauth.start.isPending || current?.status === 'pending';
  const signedIn = state.status !== 'not_connected';
  const renew = state.status === 'expired' || state.status === 'error';

  return (
    <div className="flex flex-col gap-2" data-testid={`mcp-oauth-${server.name}`}>
      <div className="flex flex-wrap items-center gap-2">
        {!waiting && (!signedIn || renew) && (
          <Button
            size="sm"
            variant={renew ? 'primary' : 'secondary'}
            data-testid={`mcp-oauth-connect-${server.name}`}
            onClick={oauth.connect}
          >
            {t(renew ? 'mcp.oauth.reconnect' : 'mcp.oauth.connect')}
          </Button>
        )}
        {!waiting && signedIn && (
          <Button
            size="sm"
            variant="ghost"
            data-testid={`mcp-oauth-disconnect-${server.name}`}
            disabled={disconnect.isPending}
            onClick={() => {
              void ask({
                title: t('mcp.oauth.disconnect_title', { name: server.name }),
                body: t('mcp.oauth.disconnect_body'),
                confirmLabel: t('mcp.oauth.disconnect'),
              }).then((yes) => {
                if (yes) {
                  oauth.reset();
                  disconnect.mutate(server.name);
                }
              });
            }}
          >
            {t('mcp.oauth.disconnect')}
          </Button>
        )}
        <span className="text-xs text-muted">{t('mcp.oauth.per_profile')}</span>
        {dialog}
      </div>

      {oauth.start.isPending && <Spinner label={t('mcp.oauth.starting')} />}
      {oauth.start.isError && <Notice tone="danger">{describeError(oauth.start.error, t)}</Notice>}
      {disconnect.isError && <Notice tone="danger">{describeError(disconnect.error, t)}</Notice>}
      {oauth.flow.isError && <Notice tone="danger">{describeError(oauth.flow.error, t)}</Notice>}

      {current && current.status === 'pending' && (
        <div
          className="flex flex-wrap items-center gap-2"
          data-testid={`mcp-oauth-waiting-${server.name}`}
        >
          <Spinner label={t('mcp.oauth.waiting')} />
          {current.authorization_url && (
            <a
              className="link text-sm underline"
              href={current.authorization_url}
              target="_blank"
              rel="noreferrer noopener"
              data-testid={`mcp-oauth-link-${server.name}`}
            >
              {t('mcp.oauth.open_page')}
            </a>
          )}
          <Button
            size="sm"
            variant="ghost"
            disabled={cancel.isPending}
            onClick={() => {
              if (oauth.flowId) {
                cancel.mutate(
                  { name: server.name, flowId: oauth.flowId },
                  { onSettled: () => void oauth.flow.refetch() },
                );
              }
            }}
          >
            {t('common.cancel')}
          </Button>
        </div>
      )}
      {current && current.status === 'approved' && (
        <div data-testid={`mcp-oauth-result-${server.name}`} data-status="approved">
          <Notice tone="success">
            <span className="font-medium">
              {t('mcp.oauth.approved', { count: String(current.tools.length) })}
            </span>{' '}
            <Button
              size="sm"
              variant="secondary"
              onClick={onTest}
              data-testid={`mcp-oauth-test-${server.name}`}
            >
              {t('mcp.oauth.test_now')}
            </Button>
          </Notice>
        </div>
      )}
      {current &&
        (current.status === 'failed' ||
          current.status === 'cancelled' ||
          current.status === 'expired') && (
          <div data-testid={`mcp-oauth-result-${server.name}`} data-status={current.status}>
            <Notice tone={current.status === 'cancelled' ? 'info' : 'danger'}>
              <span className="font-medium">{t(`mcp.oauth.${current.status}`)}</span>
              {current.error ? (
                <>
                  {' '}
                  <span dir="auto">{current.error}</span>
                </>
              ) : null}
            </Notice>
          </div>
        )}
    </div>
  );
}
