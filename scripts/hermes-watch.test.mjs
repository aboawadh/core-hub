// pnpm scripts:test — the Hermes watch (DECISIONS §132), against the repository's own pins and
// stand-in releases.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';

import {
  FILES,
  bumpTexts,
  compareVersions,
  failingTests,
  pickLatest,
  readPins,
  releaseVersion,
  reportBody,
  verdict,
} from './hermes-watch.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const files = () => ({
  dockerfile: readFileSync(path.join(ROOT, FILES.dockerfile), 'utf8'),
  versions: readFileSync(path.join(ROOT, FILES.versions), 'utf8'),
});

describe('the pins', () => {
  it('reads the floor and the tested pin, and the Dockerfile carries the tested one', () => {
    const pins = readPins(files());
    assert.match(pins.floor.ref, /^v\d{4}\.\d+\.\d+$/);
    assert.match(pins.tested.version, /^\d+\.\d+\.\d+$/);
    assert.equal(pins.dockerfileRef, pins.tested.ref);
    assert.ok(compareVersions(pins.tested.version, pins.floor.version) >= 0);
  });

  it('moves the tested pin in both files and leaves the floor', () => {
    const next = bumpTexts(files(), { ref: 'v2026.10.2', version: '0.22.0' });
    const pins = readPins(next);
    assert.deepEqual(pins.tested, { ref: 'v2026.10.2', version: '0.22.0' });
    assert.equal(pins.dockerfileRef, 'v2026.10.2');
    assert.deepEqual(pins.floor, readPins(files()).floor);
    assert.equal(next.dockerfile.split('\n').length, files().dockerfile.split('\n').length);
  });

  it('refuses a tag or a version that could not be one', () => {
    assert.throws(() => bumpTexts(files(), { ref: 'main; rm -rf /', version: '0.22.0' }));
    assert.throws(() => bumpTexts(files(), { ref: 'v2026.10.2', version: 'latest' }));
  });
});

describe('the latest Hermes release', () => {
  const releases = [
    { tag_name: 'v2026.9.21', name: 'Hermes Agent v0.21.4 (v2026.9.21)', body: '' },
    {
      tag_name: 'v2026.10.1',
      name: 'Hermes Agent v0.22.0 (v2026.10.1)',
      body: '',
      prerelease: true,
    },
    { tag_name: 'v2026.9.24', name: 'Hermes Agent v0.21.5 (v2026.9.24)', body: '' },
    {
      tag_name: 'v2026.9.30',
      name: 'x',
      body: '# Hermes Agent v0.21.6 (v2026.9.30)\n',
      draft: true,
    },
    { tag_name: 'nightly', name: 'Nightly', body: 'no version here' },
  ];

  it('reads the version from the release name or its notes', () => {
    assert.equal(releaseVersion(releases[0]), '0.21.4');
    assert.equal(
      releaseVersion({ name: 'x', body: '# Hermes Agent v0.21.6 (v2026.9.30)' }),
      '0.21.6',
    );
    assert.equal(releaseVersion(releases[4]), null);
  });

  it('takes the newest published release, never a draft or a pre-release', () => {
    assert.deepEqual(pickLatest(releases), { ref: 'v2026.9.24', version: '0.21.5' });
    assert.equal(pickLatest([]), null);
  });

  it('says newer only past the tested pin', () => {
    const tested = { ref: 'v2026.9.24', version: '0.21.5' };
    assert.equal(verdict({ ref: 'v2026.9.24', version: '0.21.5' }, tested).newer, false);
    assert.equal(verdict({ ref: 'v2026.9.30', version: '0.21.6' }, tested).newer, true);
    assert.equal(verdict({ ref: 'v2026.9.21', version: '0.21.4' }, tested).newer, false);
    assert.equal(verdict(null, tested).newer, false);
  });
});

describe('the report', () => {
  const vitest = {
    numTotalTests: 5,
    testResults: [
      {
        name: path.join(ROOT, 'packages/server/src/a.real.test.ts'),
        status: 'failed',
        assertionResults: [
          { status: 'passed', fullName: 'a works' },
          { status: 'failed', fullName: 'a keeps the key' },
        ],
      },
      {
        name: path.join(ROOT, 'packages/server/src/b.real.test.ts'),
        status: 'failed',
        message: 'Cannot find module x\n at …',
        assertionResults: [],
      },
      {
        name: path.join(ROOT, 'packages/server/src/c.real.test.ts'),
        status: 'passed',
        assertionResults: [],
      },
    ],
  };
  const pins = {
    latest: { ref: 'v2026.9.30', version: '0.21.6' },
    tested: { ref: 'v2026.9.24', version: '0.21.5' },
    floor: { ref: 'v2026.9.14', version: '0.21.3' },
    runUrl: 'https://example.invalid/run/1',
  };

  it('lists every failed test, and a file that failed before its tests ran', () => {
    assert.deepEqual(failingTests(vitest), [
      'packages/server/src/a.real.test.ts › a keeps the key',
      'packages/server/src/b.real.test.ts › (the file failed: Cannot find module x)',
    ]);
  });

  it('says what failed and that the pin stays when red', () => {
    const body = reportBody({ ...pins, failed: failingTests(vitest), total: 5 });
    assert.match(body, /\*\*2 of 5 tests failed\*\*, so the image stays on v2026\.9\.24/);
    assert.match(body, /a keeps the key/);
    assert.match(body, /example\.invalid\/run\/1/);
  });

  it('proposes the move, never merges, when green', () => {
    const body = reportBody({ ...pins, failed: [], total: 5 });
    assert.match(body, /All 5 tests passed/);
    assert.match(body, /Never merged automatically/);
  });
});
