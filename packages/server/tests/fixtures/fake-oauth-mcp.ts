/**
 * A remote MCP server behind OAuth 2.1, small enough to read, strict where real providers are:
 * the MCP endpoint answers `401` with `WWW-Authenticate: Bearer resource_metadata=…` until a
 * bearer it issued is sent; discovery is RFC 9728 (protected resource) + RFC 8414 (authorization
 * server); a client registers itself (RFC 7591); `/authorize` takes only a registered
 * `redirect_uri` and an S256 PKCE challenge and consents at once (a `302` to the redirect with a
 * code and the state); `/token` exchanges a code once, only with the same `redirect_uri` and the
 * PKCE verifier. It is what the MCP OAuth real-Hermes test (`mcp-oauth.real.test.ts`) signs in
 * to, so the exchange really happens in Hermes.
 *
 * Every refusal is recorded in `events` with its reason, so a failing test says which step broke.
 */
import { createHash, randomBytes } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';

export interface FakeOAuthMcp {
  /** `http://127.0.0.1:<port>` */
  origin: string;
  /** The MCP endpoint to configure: `<origin>/mcp`. */
  url: string;
  /** What happened, in order: `register`, `authorize`, `token`, `mcp:<method>`, `refused:<why>`. */
  events: string[];
  /** Access tokens issued so far (to prove none of them leaks anywhere). */
  issued: string[];
  /**
   * `iss` (RFC 9207): advertised in the metadata and sent on the redirect, as ClickUp does
   * (`authorization_response_iss_parameter_supported: true`). The MCP SDK then refuses a
   * response without it. On by default.
   */
  options: { iss: boolean };
  close(): Promise<void>;
}

const TOOLS = [
  { name: 'get_tasks', description: 'List the tasks of a list.', inputSchema: { type: 'object' } },
  { name: 'create_task', description: 'Create a task.', inputSchema: { type: 'object' } },
  {
    name: 'get_workspace_hierarchy',
    description: 'The spaces, folders and lists.',
    inputSchema: { type: 'object' },
  },
];

const b64url = (buffer: Buffer) => buffer.toString('base64url');

async function bodyOf(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString('utf8');
}

export async function startFakeOAuthMcp(): Promise<FakeOAuthMcp> {
  const events: string[] = [];
  const issued: string[] = [];
  const options = { iss: true };
  const clients = new Map<string, { redirectUris: string[] }>();
  const codes = new Map<
    string,
    { clientId: string; redirectUri: string; challenge: string; used: boolean }
  >();
  const tokens = new Set<string>();
  const refreshes = new Set<string>();
  let origin = '';

  const json = (response: ServerResponse, status: number, value: unknown, headers = {}) => {
    response.writeHead(status, { 'content-type': 'application/json', ...headers });
    response.end(JSON.stringify(value));
  };
  const refuse = (response: ServerResponse, status: number, why: string) => {
    events.push(`refused:${why}`);
    json(response, status, { error: why });
  };

  const server: Server = createServer((request, response) => {
    void (async () => {
      const url = new URL(request.url ?? '/', origin);
      const path = url.pathname;
      const prm = {
        resource: `${origin}/mcp`,
        authorization_servers: [origin],
        bearer_methods_supported: ['header'],
      };
      if (
        path === '/.well-known/oauth-protected-resource/mcp' ||
        path === '/.well-known/oauth-protected-resource'
      ) {
        return json(response, 200, prm);
      }
      if (
        path === '/.well-known/oauth-authorization-server' ||
        path === '/.well-known/openid-configuration'
      ) {
        return json(response, 200, {
          issuer: origin,
          authorization_endpoint: `${origin}/authorize`,
          token_endpoint: `${origin}/token`,
          registration_endpoint: `${origin}/register`,
          response_types_supported: ['code'],
          grant_types_supported: ['authorization_code', 'refresh_token'],
          code_challenge_methods_supported: ['S256'],
          token_endpoint_auth_methods_supported: ['none', 'client_secret_post'],
          ...(options.iss ? { authorization_response_iss_parameter_supported: true } : {}),
        });
      }
      if (path === '/register' && request.method === 'POST') {
        const meta = JSON.parse((await bodyOf(request)) || '{}') as { redirect_uris?: string[] };
        const redirectUris = Array.isArray(meta.redirect_uris) ? meta.redirect_uris : [];
        if (redirectUris.length === 0) return refuse(response, 400, 'register_no_redirect_uris');
        const clientId = `client-${b64url(randomBytes(6))}`;
        clients.set(clientId, { redirectUris });
        events.push(`register:${redirectUris.join(',')}`);
        return json(response, 201, {
          ...meta,
          client_id: clientId,
          client_id_issued_at: Math.floor(Date.now() / 1000),
          token_endpoint_auth_method: 'none',
        });
      }
      if (path === '/authorize' && request.method === 'GET') {
        const q = url.searchParams;
        const client = clients.get(q.get('client_id') ?? '');
        const redirectUri = q.get('redirect_uri') ?? '';
        if (!client) return refuse(response, 400, 'authorize_unknown_client');
        if (!client.redirectUris.includes(redirectUri)) {
          return refuse(response, 400, 'authorize_redirect_uri_not_registered');
        }
        if (q.get('code_challenge_method') !== 'S256' || !q.get('code_challenge')) {
          return refuse(response, 400, 'authorize_no_pkce');
        }
        if (q.get('response_type') !== 'code')
          return refuse(response, 400, 'authorize_response_type');
        const code = b64url(randomBytes(18));
        codes.set(code, {
          clientId: q.get('client_id')!,
          redirectUri,
          challenge: q.get('code_challenge')!,
          used: false,
        });
        events.push('authorize');
        const back = new URL(redirectUri);
        back.searchParams.set('code', code);
        if (q.get('state') !== null) back.searchParams.set('state', q.get('state')!);
        if (options.iss) back.searchParams.set('iss', origin);
        response.writeHead(302, { location: back.toString() });
        return response.end();
      }
      if (path === '/token' && request.method === 'POST') {
        const form = new URLSearchParams(await bodyOf(request));
        const grant = form.get('grant_type');
        if (grant === 'refresh_token') {
          const old = form.get('refresh_token') ?? '';
          if (!refreshes.has(old)) return refuse(response, 400, 'token_bad_refresh');
          const access = `at-${b64url(randomBytes(18))}`;
          tokens.add(access);
          issued.push(access);
          events.push('token:refresh');
          return json(response, 200, {
            access_token: access,
            token_type: 'Bearer',
            expires_in: 3600,
            refresh_token: old,
          });
        }
        if (grant !== 'authorization_code') return refuse(response, 400, 'token_grant_type');
        const entry = codes.get(form.get('code') ?? '');
        if (!entry) return refuse(response, 400, 'token_unknown_code');
        if (entry.used) return refuse(response, 400, 'token_code_reused');
        if (form.get('client_id') !== entry.clientId)
          return refuse(response, 400, 'token_client_mismatch');
        if (form.get('redirect_uri') !== entry.redirectUri) {
          return refuse(response, 400, 'token_redirect_uri_mismatch');
        }
        const verifier = form.get('code_verifier') ?? '';
        if (b64url(createHash('sha256').update(verifier).digest()) !== entry.challenge) {
          return refuse(response, 400, 'token_pkce_mismatch');
        }
        entry.used = true;
        const access = `at-${b64url(randomBytes(18))}`;
        const refresh = `rt-${b64url(randomBytes(18))}`;
        tokens.add(access);
        refreshes.add(refresh);
        issued.push(access, refresh);
        events.push('token');
        return json(response, 200, {
          access_token: access,
          token_type: 'Bearer',
          expires_in: 3600,
          refresh_token: refresh,
        });
      }
      if (path === '/mcp') {
        const bearer = /^Bearer\s+(.+)$/i.exec(request.headers.authorization ?? '')?.[1];
        if (!bearer || !tokens.has(bearer)) {
          events.push(`refused:mcp_${bearer ? 'bad' : 'no'}_token`);
          response.writeHead(401, {
            'content-type': 'application/json',
            'www-authenticate': `Bearer resource_metadata="${origin}/.well-known/oauth-protected-resource/mcp"`,
          });
          return response.end(JSON.stringify({ error: 'invalid_token' }));
        }
        if (request.method === 'GET') {
          response.writeHead(405);
          return response.end();
        }
        if (request.method === 'DELETE') {
          response.writeHead(200);
          return response.end();
        }
        const message = JSON.parse((await bodyOf(request)) || '{}') as {
          id?: number | string;
          method?: string;
          params?: { protocolVersion?: string };
        };
        events.push(`mcp:${message.method}`);
        if (message.id === undefined) {
          response.writeHead(202);
          return response.end();
        }
        const reply = (result: unknown) =>
          json(
            response,
            200,
            { jsonrpc: '2.0', id: message.id, result },
            { 'mcp-session-id': 'fake-session' },
          );
        if (message.method === 'initialize') {
          return reply({
            protocolVersion: message.params?.protocolVersion ?? '2025-06-18',
            capabilities: { tools: {} },
            serverInfo: { name: 'fake-oauth-mcp', version: '1.0.0' },
          });
        }
        if (message.method === 'tools/list') return reply({ tools: TOOLS });
        if (message.method === 'ping') return reply({});
        return json(response, 200, {
          jsonrpc: '2.0',
          id: message.id,
          error: { code: -32601, message: 'Method not found' },
        });
      }
      response.writeHead(404);
      response.end();
    })().catch((error: unknown) => {
      events.push(`crashed:${error instanceof Error ? error.message : String(error)}`);
      if (!response.headersSent) response.writeHead(500);
      response.end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return {
    origin,
    url: `${origin}/mcp`,
    events,
    issued,
    options,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}
