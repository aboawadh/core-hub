// pnpm scripts:test — finding or making the App Store version the listing is uploaded to.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { ensureVersion } from './asc-review-detail.mjs';

/** A fake App Store Connect holding `versions`; records every call. */
function fakeApi(versions) {
  const calls = [];
  const api = async (method, path, body) => {
    calls.push({ method, path, body });
    if (method === 'GET') {
      const want = /filter\[versionString\]=([^&]+)/.exec(path)?.[1];
      const data = want
        ? versions.filter((v) => v.attributes.versionString === decodeURIComponent(want))
        : versions;
      return { data };
    }
    if (method === 'PATCH') {
      const v = versions.find((x) => `/appStoreVersions/${x.id}` === path);
      Object.assign(v.attributes, body.data.attributes);
      return { data: v };
    }
    if (method === 'POST') {
      const v = { type: 'appStoreVersions', id: 'new', attributes: { ...body.data.attributes } };
      versions.push(v);
      return { data: v };
    }
    throw new Error(`unexpected ${method} ${path}`);
  };
  return { api, calls };
}

const ver = (id, versionString, appVersionState) => ({
  type: 'appStoreVersions',
  id,
  attributes: { versionString, appVersionState },
});

describe('ensureVersion', () => {
  it('returns the version when it already exists', async () => {
    const { api, calls } = fakeApi([ver('v4', '1.1.4', 'PREPARE_FOR_SUBMISSION')]);
    const v = await ensureVersion(api, 'app-1', '1.1.4');
    assert.equal(v.id, 'v4');
    assert.deepEqual(
      calls.map((c) => c.method),
      ['GET'],
    );
  });

  it('renames a never-released editable version instead of creating another', async () => {
    const { api, calls } = fakeApi([
      ver('v2', '1.1.2', 'READY_FOR_SALE'),
      ver('v3', '1.1.3', 'PREPARE_FOR_SUBMISSION'),
    ]);
    const v = await ensureVersion(api, 'app-1', '1.1.4');
    assert.equal(v.id, 'v3');
    assert.equal(v.attributes.versionString, '1.1.4');
    assert.ok(!calls.some((c) => c.method === 'POST'));
  });

  it('creates a new version when every existing one is released or in review', async () => {
    const { api, calls } = fakeApi([
      ver('v2', '1.1.2', 'READY_FOR_SALE'),
      ver('v3', '1.1.3', 'WAITING_FOR_REVIEW'),
    ]);
    const v = await ensureVersion(api, 'app-1', '1.1.4');
    assert.equal(v.id, 'new');
    assert.ok(!calls.some((c) => c.method === 'PATCH'));
  });
});
