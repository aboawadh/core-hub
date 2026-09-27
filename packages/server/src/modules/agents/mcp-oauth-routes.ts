/**
 * The MCP OAuth operations (contract decision §121): start a sign-in, read and stop it, forget
 * it, and the public callback the provider sends the browser back to. The flow itself is
 * Hermes's (`mcp-oauth.ts`); these routes find the profile's home, write the redirect the
 * browser can reach, and keep the hub's own id for each sign-in so a client never holds
 * Hermes's.
 */
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { newUlid } from '../../db/ids.js';
import { HubError, notFound } from '../../lib/errors.js';
import { defineRoute, type RouteDeps } from '../../lib/route.js';
import type { HermesApiCall } from './hermes-tools.js';
import { McpError, getMcpServer, setOAuthRedirect, type McpServer } from './mcp.js';
import {
  McpOAuthFlows,
  callbackPage,
  callbackUri,
  cancelHermesFlow,
  hubBaseOf,
  isHubCallback,
  pollHermesFlow,
  relayCallback,
  removeOAuthTokens,
  startHermesFlow,
  type McpOAuthFlowRecord,
} from './mcp-oauth.js';

export interface McpOAuthRouteHelpers {
  /** The selected profile's Hermes home and name, or the reason there is none. */
  toolHome(request: FastifyRequest, agentId: string): { home: string; profile: string };
  /** Hermes's API, or the reason there is none (`hermes_not_supervised`). */
  hermesApi(request: FastifyRequest, agentId: string): HermesApiCall;
  /** Hermes's API for the public callback, where there is no agent to name; null when absent. */
  callbackApi(app: FastifyInstance): HermesApiCall | null;
  /** The contract's `McpServer`, `oauth` included. */
  toMcpServer(server: McpServer, home: string): Record<string, unknown>;
  /** The hub's own block is not signed in from here (§67). */
  refuseManaged(name: string): void;
  mcpFault(error: unknown): never;
  /** The contract's API base (`/api/v1`). */
  apiBase: string;
}

export function registerMcpOAuthRoutes(
  app: FastifyInstance,
  deps: RouteDeps,
  helpers: McpOAuthRouteHelpers,
): void {
  const flows = new McpOAuthFlows();

  const serverOf = (home: string, name: string): McpServer => {
    let server: McpServer | null;
    try {
      server = getMcpServer(home, name);
    } catch (error) {
      return helpers.mcpFault(error);
    }
    if (!server) throw notFound({ resource: 'mcp_server', id: name });
    return server;
  };

  const answer = (record: McpOAuthFlowRecord) => ({
    id: record.id,
    server_name: record.serverName,
    status: record.last.status,
    authorization_url: record.last.authorization_url,
    redirect_uri: record.redirectUri,
    error: record.last.error,
    tools: record.last.tools,
    expires_at: flows.expiresAt(record),
  });

  /** The flow this request names, in this agent, profile and server; `404` otherwise. */
  const recordOf = (request: FastifyRequest, params: Record<string, unknown>) => {
    const agentId = params.agent_id as string;
    const name = params.server_name as string;
    const { profile } = helpers.toolHome(request, agentId);
    const record = flows.get(params.flow_id as string, agentId, profile, name);
    if (!record) throw notFound({ resource: 'mcp_oauth_flow', id: params.flow_id as string });
    return { agentId, record };
  };

  defineRoute(app, deps, {
    operationId: 'agents.startMcpOAuth',
    handler: async (request, { params, body }) => {
      const agentId = params.agent_id as string;
      const name = params.server_name as string;
      helpers.refuseManaged(name);
      const { home, profile } = helpers.toolHome(request, agentId);
      const api = helpers.hermesApi(request, agentId);
      const server = serverOf(home, name);
      if (server.transport === 'stdio') {
        throw new HubError('conflict', {
          details: { reason: 'mcp_oauth_stdio', name },
        });
      }
      const input = (body ?? {}) as { hub_url?: string };
      const base = hubBaseOf(input.hub_url, `${request.protocol}://${request.host}`);
      let redirectUri: string;
      try {
        redirectUri = setOAuthRedirect(
          home,
          name,
          callbackUri(base, helpers.apiBase, name),
          (current) => isHubCallback(current, helpers.apiBase, name),
        );
      } catch (error) {
        if (error instanceof McpError) return helpers.mcpFault(error);
        throw error;
      }
      const started = await startHermesFlow(api, profile, name);
      const record: McpOAuthFlowRecord = {
        id: newUlid(),
        agentId,
        profile,
        serverName: name,
        hermesFlowId: started.hermesFlowId,
        redirectUri,
        createdAt: Date.now(),
        last: started.view,
      };
      flows.add(record);
      return answer(record);
    },
  });

  defineRoute(app, deps, {
    operationId: 'agents.getMcpOAuthFlow',
    handler: async (request, { params }) => {
      const { agentId, record } = recordOf(request, params);
      if (record.last.status === 'pending') {
        const view = await pollHermesFlow(helpers.hermesApi(request, agentId), record.hermesFlowId);
        record.last = view ?? { ...record.last, status: 'expired', authorization_url: null };
      }
      return answer(record);
    },
  });

  defineRoute(app, deps, {
    operationId: 'agents.cancelMcpOAuthFlow',
    handler: async (request, { params }) => {
      const { agentId, record } = recordOf(request, params);
      if (record.last.status === 'pending') {
        const api = helpers.hermesApi(request, agentId);
        await cancelHermesFlow(api, record.hermesFlowId);
        const view = await pollHermesFlow(api, record.hermesFlowId);
        record.last = view ?? { ...record.last, status: 'cancelled', error: null };
      }
      return answer(record);
    },
  });

  defineRoute(app, deps, {
    operationId: 'agents.disconnectMcpOAuth',
    handler: (request, { params }) => {
      const agentId = params.agent_id as string;
      const name = params.server_name as string;
      helpers.refuseManaged(name);
      const { home } = helpers.toolHome(request, agentId);
      const server = serverOf(home, name);
      removeOAuthTokens(home, name);
      return helpers.toMcpServer(server, home);
    },
  });

  defineRoute(app, deps, {
    operationId: 'agents.mcpOAuthCallback',
    // The query is the provider's authorization code: the request's own log lines, which
    // carry the URL, are not written for this route (`logLevel` in `defineRoute`).
    logLevel: 'warn',
    handler: async (request, { params }, reply) => {
      const name = params.server_name as string;
      const url = request.raw.url ?? '';
      const at = url.indexOf('?');
      const outcome = await relayCallback(
        helpers.callbackApi(request.server),
        name,
        at === -1 ? '' : url.slice(at + 1),
      );
      void reply
        .status(200)
        .type('text/html; charset=utf-8')
        .header('cache-control', 'no-store')
        .header('referrer-policy', 'no-referrer')
        .send(callbackPage(outcome, name, request.language));
      return reply;
    },
  });
}
