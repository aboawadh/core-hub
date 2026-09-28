// `pnpm contract:test`: Settings → Secrets (DECISIONS §125) through the generated TypeScript
// client — `auth.stepUp`, `auth.endStepUp`, `secrets.list` and `secrets.reveal` answer what the
// contract documents: a schema-valid grant, list and value for the owner; `401` for a wrong
// password, `403 step_up_required` without a live grant, `403` for an admin, `404` for an
// unknown secret.
import { afterAll, describe, expect, it } from 'vitest';
import {
  HubApiError,
  createHubClient,
  loadOpenApiDocument,
  serverBasePath,
  type ClientMethod,
} from '@corehub/contracts';
import { testHub, type TestHub } from '../unit/helpers.js';
import { ajvFor, operationsById, responseSchema } from './schema.js';

const doc = loadOpenApiDocument();
const PASSWORD = 'contract-test-password';

describe.skipIf(!doc)('contract: Settings → Secrets', () => {
  const document = doc!;
  const ops = operationsById(document);
  const schemas = ajvFor(document);
  const hubs: TestHub[] = [];

  afterAll(async () => {
    for (const hub of hubs) await hub.close();
  });

  async function start() {
    const hub = await testHub({ HUB_ADMIN_PASSWORD: PASSWORD });
    hubs.push(hub);
    await hub.app.listen({ port: 0, host: '127.0.0.1' });
    const address = hub.app.server.address();
    const baseUrl = `http://127.0.0.1:${typeof address === 'object' && address ? address.port : 0}`;
    let token: string | undefined;
    const client = createHubClient({
      baseUrl,
      apiBase: serverBasePath(document),
      token: () => token,
    });
    const signIn = async (username: string, password: string) => {
      const res = await client.raw('post', '/auth/login', { body: { username, password } });
      token = (res.data as { access_token: string }).access_token;
    };
    const call = async (operationId: string, expected: number, body?: unknown) => {
      const op = ops.get(operationId)!;
      let status: number;
      let data: unknown;
      try {
        const res = await client.raw(
          op.method as ClientMethod,
          op.path,
          body === undefined ? {} : { body },
        );
        status = res.status;
        data = res.data;
      } catch (error) {
        if (!(error instanceof HubApiError)) throw error;
        status = error.status;
        data = error.body;
      }
      expect(status, `${operationId} ${JSON.stringify(data)}`).toBe(expected);
      if (expected !== 204) {
        const schema = responseSchema(op, status);
        expect(schema, `${operationId} documents ${status}`).toBeDefined();
        expect(schemas.validate(schema!, data)).toEqual([]);
      }
      return data as Record<string, unknown>;
    };
    return { client, signIn, call };
  }

  it('answers each documented status with a schema-valid body', async () => {
    const { client, signIn, call } = await start();
    await signIn('admin', PASSWORD);

    const wrong = await call('auth.stepUp', 401, { password: 'nope', purpose: 'secrets' });
    expect(wrong).toMatchObject({ details: { reason: 'wrong_password' } });
    const missing = await call('secrets.list', 403, { grant: 'su_not-a-grant-at-all' });
    expect(missing).toMatchObject({ details: { reason: 'step_up_required' } });

    const granted = await call('auth.stepUp', 200, { password: PASSWORD, purpose: 'secrets' });
    const grant = granted.grant as string;
    const listed = await call('secrets.list', 200, { grant });
    expect(Array.isArray(listed.items)).toBe(true);
    await call('secrets.reveal', 404, { grant, id: 'not-a-secret' });

    await call('auth.endStepUp', 204);
    await call('secrets.reveal', 403, { grant, id: 'not-a-secret' });

    // An admin is not the owner.
    await client.raw('post', '/auth/users', {
      body: { username: 'amal', password: 'amal-password-1', role: 'admin', profiles: ['default'] },
    });
    await signIn('amal', 'amal-password-1');
    const refused = await call('auth.stepUp', 403, {
      password: 'amal-password-1',
      purpose: 'secrets',
    });
    expect(refused).toMatchObject({ details: { required_role: 'owner' } });
    await call('secrets.list', 403, { grant });
    // `auth.endStepUp` documents its 403 by the shared `Forbidden` response.
    const ended = await client.raw('delete', '/auth/step-up', {}).catch((error: unknown) => error);
    expect(ended).toBeInstanceOf(HubApiError);
    expect((ended as HubApiError).status).toBe(403);
  });
});
