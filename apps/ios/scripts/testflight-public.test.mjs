// pnpm scripts:test — the TestFlight public-link step against a fake App Store Connect API.
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { after, before, beforeEach, describe, it } from 'node:test';

import { createClient } from './testflight-distribute.mjs';
import { TEST_INFORMATION, parseLinkLimit, publish, whatToTest } from './testflight-public.mjs';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
const KEY_ID = 'TESTKEY123';
const ISSUER = '00000000-0000-0000-0000-000000000000';
const LINK = 'https://testflight.apple.com/join/AbCd1234';
const PASSWORD = 'demo-password-never-logged';

function verifyToken(jwt) {
  const [head, body, sig] = jwt.split('.');
  return verify(
    'sha256',
    Buffer.from(`${head}.${body}`),
    { key: publicKey, dsaEncoding: 'ieee-p1363' },
    Buffer.from(sig, 'base64url'),
  );
}

// The listing the step fills the beta description and privacy URL from.
const metadata = mkdtempSync(path.join(tmpdir(), 'corehub-metadata-'));
for (const [locale, description, privacy] of [
  ['en-US', 'Core Hub is the app for your own hub.', 'https://example.test/privacy'],
  ['ar-SA', 'كور هب تطبيق لخادمك.', 'https://example.test/privacy.ar'],
]) {
  mkdirSync(path.join(metadata, locale));
  writeFileSync(path.join(metadata, locale, 'description.txt'), `${description}\n`);
  writeFileSync(path.join(metadata, locale, 'privacy_url.txt'), `${privacy}\n`);
}

const completeReview = () => ({
  contactFirstName: 'Test',
  contactLastName: 'Owner',
  contactPhone: '+10000000000',
  contactEmail: 'owner@example.test',
  demoAccountRequired: true,
  demoAccountName: 'reviewer',
  demoAccountPassword: PASSWORD,
  notes: 'Demo hub: https://demo.example.test',
});

// The fake App Store Connect; each test scripts its state.
const fake = {};
let server;
let base;
let nextId = 0;

function groupView(id) {
  const a = fake.groups[id];
  return {
    type: 'betaGroups',
    id,
    attributes: { ...a, ...(a.publicLinkEnabled ? { publicLink: LINK } : {}) },
  };
}

before(async () => {
  server = createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      const url = new URL(req.url, 'http://fake');
      const auth = req.headers.authorization ?? '';
      const body = raw ? JSON.parse(raw) : null;
      fake.requests.push({ method: req.method, path: url.pathname, url, body });
      const send = (status, json) => {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(json === undefined ? '' : JSON.stringify(json));
      };
      const fail = (status, detail) =>
        send(status, { errors: [{ code: 'ENTITY_ERROR', title: 'refused', detail }] });
      if (!auth.startsWith('Bearer ') || !verifyToken(auth.slice(7))) return fail(401, 'bad token');
      const route = `${req.method} ${url.pathname}`;
      let m;

      if (route === 'GET /v1/apps') return send(200, { data: fake.apps });
      if (route === 'GET /v1/builds') {
        const step = fake.builds.length > 1 ? fake.builds.shift() : fake.builds[0];
        if (!step) return send(200, { data: [] });
        return send(200, {
          data: [
            {
              type: 'builds',
              id: 'build-1',
              attributes: { version: '150', processingState: step.processingState },
              relationships: {
                buildBetaDetail: { data: { type: 'buildBetaDetails', id: 'bbd-1' } },
              },
            },
          ],
          included: [
            {
              type: 'buildBetaDetails',
              id: 'bbd-1',
              attributes: {
                internalBuildState: 'READY_FOR_BETA_TESTING',
                externalBuildState: step.externalBuildState ?? 'READY_FOR_BETA_SUBMISSION',
              },
            },
          ],
        });
      }
      if (route === 'GET /v1/apps/app-1/betaGroups') {
        return send(200, { data: Object.keys(fake.groups).map(groupView) });
      }
      if (route === 'POST /v1/betaGroups') {
        const id = `g-${++nextId}`;
        fake.groups[id] = { ...body.data.attributes };
        return send(201, { data: groupView(id) });
      }
      if ((m = route.match(/^PATCH \/v1\/betaGroups\/([^/]+)$/))) {
        Object.assign(fake.groups[m[1]], body.data.attributes);
        return send(200, { data: groupView(m[1]) });
      }
      if ((m = route.match(/^GET \/v1\/betaGroups\/([^/]+)$/))) {
        return send(200, { data: groupView(m[1]) });
      }
      if ((m = route.match(/^POST \/v1\/betaGroups\/([^/]+)\/relationships\/builds$/))) {
        return send(204);
      }
      if (route === 'GET /v1/builds/build-1/betaBuildLocalizations') {
        return send(200, { data: fake.buildLocs });
      }
      if (route === 'POST /v1/betaBuildLocalizations') {
        const { locale } = body.data.attributes;
        if (fake.refuseLocales.includes(locale)) return fail(409, `locale ${locale}`);
        fake.buildLocs.push({
          type: 'betaBuildLocalizations',
          id: `bl-${++nextId}`,
          attributes: body.data.attributes,
        });
        return send(201, { data: fake.buildLocs.at(-1) });
      }
      if ((m = route.match(/^PATCH \/v1\/betaBuildLocalizations\/([^/]+)$/))) {
        const loc = fake.buildLocs.find((l) => l.id === m[1]);
        Object.assign(loc.attributes, body.data.attributes);
        return send(200, { data: loc });
      }
      if (route === 'GET /v1/apps/app-1/betaAppLocalizations') {
        return send(200, { data: fake.appLocs });
      }
      if (route === 'POST /v1/betaAppLocalizations') {
        fake.appLocs.push({
          type: 'betaAppLocalizations',
          id: `al-${++nextId}`,
          attributes: body.data.attributes,
        });
        return send(201, { data: fake.appLocs.at(-1) });
      }
      if ((m = route.match(/^PATCH \/v1\/betaAppLocalizations\/([^/]+)$/))) {
        const loc = fake.appLocs.find((l) => l.id === m[1]);
        Object.assign(loc.attributes, body.data.attributes);
        return send(200, { data: loc });
      }
      if (route === 'GET /v1/apps/app-1/betaAppReviewDetail') {
        if (!fake.review) return fail(404, 'none');
        return send(200, {
          data: { type: 'betaAppReviewDetails', id: 'app-1', attributes: fake.review },
        });
      }
      if (route === 'GET /v1/betaAppReviewSubmissions') {
        if (!url.searchParams.get('filter[build]')) return fail(400, 'filter[build] is required');
        return send(200, { data: fake.submissions });
      }
      if (route === 'POST /v1/betaAppReviewSubmissions') {
        const made = {
          type: 'betaAppReviewSubmissions',
          id: `s-${++nextId}`,
          attributes: { betaReviewState: 'WAITING_FOR_REVIEW' },
        };
        fake.submissions.push(made);
        return send(201, { data: made });
      }
      return fail(404, `no route ${route}`);
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}/v1`;
});
after(() => {
  rmSync(metadata, { recursive: true, force: true });
  return new Promise((r) => server.close(r));
});

beforeEach(() => {
  fake.requests = [];
  fake.apps = [
    {
      type: 'apps',
      id: 'app-1',
      attributes: { bundleId: 'com.twuijri.corehub', primaryLocale: 'en-US' },
    },
  ];
  fake.groups = {
    'g-owner': { name: 'Owner', isInternalGroup: true, hasAccessToAllBuilds: true },
  };
  fake.builds = [{ processingState: 'VALID' }];
  fake.buildLocs = [];
  fake.refuseLocales = [];
  fake.appLocs = [
    {
      type: 'betaAppLocalizations',
      id: 'al-en',
      attributes: {
        locale: 'en-US',
        description: 'Already written.',
        feedbackEmail: 'feedback@example.test',
        privacyPolicyUrl: 'https://example.test/p',
      },
    },
  ];
  fake.review = completeReview();
  fake.submissions = [];
});

function run(overrides = {}) {
  let now = 0;
  const lines = [];
  const log = {};
  for (const level of ['info', 'notice', 'warning', 'error', 'summary']) {
    log[level] = (m) => lines.push({ level, m });
  }
  const api = createClient({
    base,
    keyId: KEY_ID,
    issuer: ISSUER,
    privateKey: pem,
    clock: () => now,
  });
  return publish({
    api,
    bundleId: 'com.twuijri.corehub',
    buildNumber: '150',
    version: '1.1.4',
    group: 'Public',
    linkLimit: '',
    metadata,
    timeoutMs: 30 * 60_000,
    intervalMs: 30_000,
    clock: () => now,
    sleep: async (ms) => {
      now += ms;
    },
    log,
    ...overrides,
  }).then((code) => ({ code, lines }));
}

const calls = (method, pattern) =>
  fake.requests.filter((r) => r.method === method && pattern.test(r.path));
const has = (lines, level, text) => lines.some((l) => l.level === level && l.m.includes(text));
const order = (method, pattern) =>
  fake.requests.findIndex((r) => r.method === method && pattern.test(r.path));

describe('parseLinkLimit', () => {
  it('defaults to 1000 and keeps to Apple’s 1–10,000', () => {
    assert.equal(parseLinkLimit(''), 1000);
    assert.equal(parseLinkLimit(undefined), 1000);
    assert.equal(parseLinkLimit(' 250 '), 250);
    assert.equal(parseLinkLimit('10000'), 10_000);
    for (const bad of ['0', '10001', '1.5', 'many']) assert.throws(() => parseLinkLimit(bad));
  });
});

describe('whatToTest', () => {
  it('points at the release in English and Arabic', () => {
    const t = whatToTest('1.1.4');
    const url = 'https://github.com/twuijri/core-hub/releases/tag/v1.1.4';
    assert.equal(t['en-US'], `Core Hub 1.1.4 — what's new: ${url}`);
    assert.ok(t['ar-SA'].startsWith('كور هب 1.1.4'));
    assert.ok(t['ar-SA'].endsWith(url));
  });
});

describe('publish', () => {
  it('creates the Public group with its link, sets What to Test, submits, and adds the build', async () => {
    const { code, lines } = await run();
    assert.equal(code, 0, JSON.stringify(lines));

    const [made] = calls('POST', /^\/v1\/betaGroups$/);
    assert.deepEqual(made.body.data.attributes, {
      name: 'Public',
      isInternalGroup: false,
      publicLinkEnabled: true,
      publicLinkLimitEnabled: true,
      publicLinkLimit: 1000,
      feedbackEnabled: true,
    });
    assert.deepEqual(made.body.data.relationships, {
      app: { data: { type: 'apps', id: 'app-1' } },
    });
    assert.ok(has(lines, 'summary', `TestFlight public link (Public): ${LINK}`));
    assert.ok(has(lines, 'notice', LINK));

    const whatsNew = calls('POST', /^\/v1\/betaBuildLocalizations$/).map((r) => r.body.data);
    assert.deepEqual(
      whatsNew.map((d) => [d.attributes.locale, d.relationships.build.data.id]),
      [
        ['en-US', 'build-1'],
        ['ar-SA', 'build-1'],
      ],
    );
    assert.equal(whatsNew[0].attributes.whatsNew, whatToTest('1.1.4')['en-US']);

    const [submission] = calls('POST', /^\/v1\/betaAppReviewSubmissions$/);
    assert.deepEqual(submission.body.data.relationships, {
      build: { data: { type: 'builds', id: 'build-1' } },
    });
    const [add] = calls('POST', /\/relationships\/builds$/);
    assert.equal(add.path, `/v1/betaGroups/${Object.keys(fake.groups)[1]}/relationships/builds`);
    assert.deepEqual(add.body, { data: [{ type: 'builds', id: 'build-1' }] });
    assert.ok(
      order('POST', /^\/v1\/betaAppReviewSubmissions$/) < order('POST', /\/relationships\/builds$/),
      'submitted before it is added to the external group',
    );
    assert.ok(has(lines, 'notice', 'Submitted build 150 (1.1.4) for Beta App Review'));
    assert.ok(!lines.some((l) => l.level === 'warning' || l.level === 'error'));
  });

  it('never touches the internal Owner group that gets every build (#184)', async () => {
    const { code } = await run();
    assert.equal(code, 0);
    assert.equal(calls('PATCH', /g-owner/).length, 0);
    assert.equal(calls('POST', /g-owner/).length, 0);
    assert.equal(calls('POST', /^\/v1\/builds\/build-1\/relationships\/betaGroups$/).length, 0);
  });

  it('turns the public link on for an existing external group, with the asked limit', async () => {
    fake.groups['g-public'] = {
      name: 'public⁩',
      isInternalGroup: false,
      publicLinkEnabled: false,
      feedbackEnabled: true,
    };
    const { code, lines } = await run({ linkLimit: '500' });
    assert.equal(code, 0);
    assert.equal(calls('POST', /^\/v1\/betaGroups$/).length, 0);
    const [patch] = calls('PATCH', /^\/v1\/betaGroups\/g-public$/);
    assert.deepEqual(patch.body.data.attributes, {
      publicLinkEnabled: true,
      publicLinkLimitEnabled: true,
      publicLinkLimit: 500,
    });
    assert.ok(has(lines, 'summary', LINK));
    assert.equal(calls('POST', /^\/v1\/betaGroups\/g-public\/relationships\/builds$/).length, 1);
  });

  it('leaves a group that is already set up alone', async () => {
    fake.groups['g-public'] = {
      name: 'Public',
      isInternalGroup: false,
      publicLinkEnabled: true,
      publicLinkLimitEnabled: true,
      publicLinkLimit: 1000,
      feedbackEnabled: true,
    };
    const { code } = await run();
    assert.equal(code, 0);
    assert.equal(calls('PATCH', /^\/v1\/betaGroups\//).length, 0);
    assert.equal(calls('POST', /^\/v1\/betaGroups$/).length, 0);
  });

  it('refuses an internal group name', async () => {
    const { code, lines } = await run({ group: 'Owner' });
    assert.equal(code, 1);
    assert.ok(has(lines, 'error', 'is an internal group'));
    assert.equal(calls('PATCH', /betaGroups/).length, 0);
    assert.equal(calls('POST', /betaAppReviewSubmissions/).length, 0);
  });

  it('updates What to Test the build already has, and goes on when Apple refuses Arabic', async () => {
    fake.buildLocs = [
      {
        type: 'betaBuildLocalizations',
        id: 'bl-en',
        attributes: { locale: 'en-US', whatsNew: '' },
      },
    ];
    fake.refuseLocales = ['ar-SA'];
    const { code, lines } = await run();
    assert.equal(code, 0);
    const [patch] = calls('PATCH', /^\/v1\/betaBuildLocalizations\/bl-en$/);
    assert.equal(patch.body.data.attributes.whatsNew, whatToTest('1.1.4')['en-US']);
    assert.ok(has(lines, 'warning', '"What to Test" was not set for ar-SA'));
    assert.equal(calls('POST', /^\/v1\/betaAppReviewSubmissions$/).length, 1);
  });

  it('does not submit a build Beta App Review already approved, and still adds it', async () => {
    fake.builds = [{ processingState: 'VALID', externalBuildState: 'BETA_APPROVED' }];
    const { code, lines } = await run();
    assert.equal(code, 0);
    assert.equal(calls('POST', /^\/v1\/betaAppReviewSubmissions$/).length, 0);
    assert.ok(has(lines, 'notice', 'already with Beta App Review (BETA_APPROVED)'));
    assert.equal(calls('POST', /\/relationships\/builds$/).length, 1);
  });

  it('does not submit twice when a submission is waiting', async () => {
    fake.submissions = [
      {
        type: 'betaAppReviewSubmissions',
        id: 's-0',
        attributes: { betaReviewState: 'WAITING_FOR_REVIEW' },
      },
    ];
    const { code } = await run();
    assert.equal(code, 0);
    const [query] = calls('GET', /^\/v1\/betaAppReviewSubmissions$/);
    assert.equal(query.url.searchParams.get('filter[build]'), 'build-1');
    assert.equal(calls('POST', /^\/v1\/betaAppReviewSubmissions$/).length, 0);
  });

  it('fails clearly, names the fields, and submits nothing when review details are missing', async () => {
    fake.review = { ...completeReview(), contactPhone: '', demoAccountRequired: false };
    fake.appLocs[0].attributes.feedbackEmail = '';
    const { code, lines } = await run();
    assert.equal(code, 1);
    const error = lines.find((l) => l.level === 'error')?.m ?? '';
    assert.ok(error.includes(TEST_INFORMATION), error);
    assert.ok(error.includes('Feedback Email (en-US)'));
    assert.ok(error.includes('Contact Information: Phone number'));
    assert.ok(error.includes('tick "Sign-in required"'));
    assert.ok(!error.includes('First name'));
    assert.ok(error.includes('The upload and the internal groups are not affected'));
    // The group and its link are still made, What to Test is still set; nothing is submitted.
    assert.ok(has(lines, 'summary', LINK));
    assert.equal(calls('POST', /^\/v1\/betaBuildLocalizations$/).length, 2);
    assert.equal(calls('POST', /^\/v1\/betaAppReviewSubmissions$/).length, 0);
    assert.equal(calls('POST', /\/relationships\/builds$/).length, 0);
    // The owner's details are never written by the step, nor printed.
    assert.equal(calls('PATCH', /betaAppReviewDetails/).length, 0);
    for (const l of lines) {
      assert.ok(!l.m.includes(PASSWORD));
      assert.ok(!l.m.includes('owner@example.test'));
    }
  });

  it('asks for every review field when the app has no review details at all', async () => {
    fake.review = null;
    const { code, lines } = await run();
    assert.equal(code, 1);
    const error = lines.find((l) => l.level === 'error').m;
    assert.ok(error.includes('Contact Information: First name, Last name, Phone number, Email'));
    assert.ok(error.includes("the demo account's User name and Password"));
    assert.ok(error.includes('Review Notes'));
  });

  it('fills the description and privacy URL from the listing, never the feedback email', async () => {
    fake.apps[0].attributes.primaryLocale = 'en-US';
    fake.appLocs = [
      {
        type: 'betaAppLocalizations',
        id: 'al-ar',
        attributes: { locale: 'ar-SA', description: '', privacyPolicyUrl: '' },
      },
    ];
    const { code, lines } = await run();
    assert.equal(code, 1);
    const [patch] = calls('PATCH', /^\/v1\/betaAppLocalizations\/al-ar$/);
    assert.deepEqual(patch.body.data.attributes, {
      description: 'كور هب تطبيق لخادمك.',
      privacyPolicyUrl: 'https://example.test/privacy.ar',
    });
    const [made] = calls('POST', /^\/v1\/betaAppLocalizations$/);
    assert.deepEqual(made.body.data.attributes, {
      locale: 'en-US',
      description: 'Core Hub is the app for your own hub.',
      privacyPolicyUrl: 'https://example.test/privacy',
    });
    assert.ok(!('feedbackEmail' in made.body.data.attributes));
    assert.ok(has(lines, 'notice', 'Test Information filled from the App Store listing'));
    const error = lines.find((l) => l.level === 'error').m;
    assert.ok(error.includes('Feedback Email (en-US)'));
    assert.ok(!error.includes('Beta App Description'));
    assert.equal(calls('POST', /^\/v1\/betaAppReviewSubmissions$/).length, 0);
  });

  it('stops waiting with a warning, not a failure, after printing the link', async () => {
    fake.builds = [{ processingState: 'PROCESSING' }];
    const { code, lines } = await run();
    assert.equal(code, 0);
    assert.ok(has(lines, 'summary', LINK));
    assert.ok(has(lines, 'warning', 'NOT submitted for Beta App Review'));
    assert.equal(calls('POST', /^\/v1\/betaAppReviewSubmissions$/).length, 0);
    assert.equal(calls('POST', /betaBuildLocalizations/).length, 0);
  });

  it('does nothing without a group name', async () => {
    const { code } = await run({ group: '  ' });
    assert.equal(code, 0);
    assert.equal(fake.requests.length, 0);
  });

  it('fails on a bad link limit before calling Apple', async () => {
    const { code, lines } = await run({ linkLimit: '20000' });
    assert.equal(code, 1);
    assert.ok(has(lines, 'error', 'from 1 to 10000'));
    assert.equal(fake.requests.length, 0);
  });
});
