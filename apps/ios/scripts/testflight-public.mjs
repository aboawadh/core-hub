#!/usr/bin/env node
// Puts a build just uploaded to TestFlight in front of anyone with the app's public TestFlight
// link (owner, 2026-09-27: send people a link instead of adding each tester by hand), through the
// App Store Connect API key the signed iOS build already uses (docs/RELEASING.md → TestFlight).
//
//   node apps/ios/scripts/testflight-public.mjs <bundle id> <build number> <marketing version> <group> [link limit]
//
// Reads ASC_API_KEY_ID, ASC_API_ISSUER_ID and ASC_API_KEY_PATH (the .p8 file) from the
// environment, and optionally TESTFLIGHT_WAIT_MINUTES (default 30) and TESTFLIGHT_POLL_SECONDS
// (default 30), like testflight-distribute.mjs.
//
// Each run:
//   1. makes sure the external group <group> (default "Public") exists with its public link on
//      (limit <link limit>, default 1000) and feedback on, creating it if missing, and prints the
//      link in the log and the job summary;
//   2. waits until App Store Connect has processed the build;
//   3. sets the build's "What to Test" (en-US and ar-SA) to a line pointing at the release;
//   4. checks the Test Information Beta App Review needs. The beta app description and privacy
//      policy URL are filled from apps/ios/fastlane/metadata when empty; the feedback email, the
//      review contact and the demo account are the owner's own and are never invented: when any is
//      missing the step fails and names each field and where it is in App Store Connect;
//   5. submits the build for Beta App Review unless it already is (or was approved), and adds it
//      to the group. Testers with the link get it once Apple approves it.
// The upload and the internal groups are earlier steps, so they are done whatever happens here.
// Nothing secret is printed: neither the token nor the review details' values reach the log.
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { metadataDir } from './store-metadata.mjs';
import {
  createClient,
  findApp,
  githubLog,
  groupKey,
  waitForBuild,
} from './testflight-distribute.mjs';

const q = encodeURIComponent;

export const REPO = 'twuijri/core-hub';
export const DEFAULT_GROUP = 'Public';
export const DEFAULT_LINK_LIMIT = 1000;

/** Where the owner types what only the owner can give. */
export const TEST_INFORMATION =
  'App Store Connect → Apps → Core Hub → TestFlight → Test Information';

/** Apple's limit for a public link, 1 to 10,000 testers. */
export function parseLinkLimit(value) {
  const text = String(value ?? '').trim();
  if (!text) return DEFAULT_LINK_LIMIT;
  const n = Number(text);
  if (!Number.isInteger(n) || n < 1 || n > 10_000) {
    throw new Error(`The public link limit must be a whole number from 1 to 10000, not "${text}".`);
  }
  return n;
}

/** The build's "What to Test", per locale: the version and its release page. */
export function whatToTest(version) {
  const url = `https://github.com/${REPO}/releases/tag/v${version}`;
  return {
    'en-US': `Core Hub ${version} — what's new: ${url}`,
    'ar-SA': `كور هب ${version} — ما الجديد: ${url}`,
  };
}

/** The listing's beta-relevant text for a locale, or {} when the repository has none. */
export function storeText(locale, dir = metadataDir) {
  const read = (file) => {
    const p = path.join(dir, locale, file);
    return existsSync(p) ? readFileSync(p, 'utf8').trim() : '';
  };
  const description = read('description.txt');
  const privacyPolicyUrl = read('privacy_url.txt');
  return {
    ...(description ? { description } : {}),
    ...(privacyPolicyUrl ? { privacyPolicyUrl } : {}),
  };
}

const blank = (v) => !String(v ?? '').trim();

/**
 * The external group `name` with its public link on, created when missing. Returns the group as
 * App Store Connect has it after the change (with `publicLink`), and what was done.
 */
export async function ensurePublicGroup(api, appId, { name, limit }) {
  const wanted = {
    publicLinkEnabled: true,
    publicLinkLimitEnabled: true,
    publicLinkLimit: limit,
    feedbackEnabled: true,
  };
  const res = await api('GET', `/apps/${q(appId)}/betaGroups?limit=200`);
  const existing = (res.data ?? []).find((g) => groupKey(g.attributes?.name) === groupKey(name));
  let id;
  let action;
  if (!existing) {
    const made = await api('POST', '/betaGroups', {
      data: {
        type: 'betaGroups',
        attributes: { name, isInternalGroup: false, ...wanted },
        relationships: { app: { data: { type: 'apps', id: appId } } },
      },
    });
    id = made.data.id;
    action = 'created';
  } else {
    if (existing.attributes?.isInternalGroup === true) {
      throw new Error(
        `TestFlight group "${existing.attributes.name}" is an internal group, which cannot have a ` +
          'public link. Give the external_group input another name (a new external group is made).',
      );
    }
    id = existing.id;
    const a = existing.attributes ?? {};
    const change = Object.fromEntries(Object.entries(wanted).filter(([k, v]) => a[k] !== v));
    if (Object.keys(change).length > 0) {
      await api('PATCH', `/betaGroups/${q(id)}`, {
        data: { type: 'betaGroups', id, attributes: change },
      });
      action = 'updated';
    } else {
      action = 'unchanged';
    }
  }
  const read = await api('GET', `/betaGroups/${q(id)}`);
  return { group: read.data, action };
}

/** Sets whatsNew on the build for each locale; a locale Apple refuses is skipped with a warning. */
export async function setWhatToTest(api, buildId, texts, log) {
  const res = await api('GET', `/builds/${q(buildId)}/betaBuildLocalizations?limit=50`);
  const have = res.data ?? [];
  const set = [];
  for (const [locale, whatsNew] of Object.entries(texts)) {
    const loc = have.find((l) => l.attributes?.locale === locale);
    try {
      if (loc) {
        if (loc.attributes?.whatsNew !== whatsNew) {
          await api('PATCH', `/betaBuildLocalizations/${q(loc.id)}`, {
            data: { type: 'betaBuildLocalizations', id: loc.id, attributes: { whatsNew } },
          });
        }
      } else {
        await api('POST', '/betaBuildLocalizations', {
          data: {
            type: 'betaBuildLocalizations',
            attributes: { locale, whatsNew },
            relationships: { build: { data: { type: 'builds', id: buildId } } },
          },
        });
      }
      set.push(locale);
    } catch (err) {
      // en-US is the app's language; a second language Apple does not take is not worth a failure.
      if (locale === 'en-US') throw err;
      log.warning(`"What to Test" was not set for ${locale}: ${err.message}`);
    }
  }
  return set;
}

/**
 * Fills the beta app description and privacy policy URL from the listing where they are empty
 * (creating the primary locale's Test Information when there is none), and returns what Beta App
 * Review still lacks that only the owner can give. Never returns or prints the values.
 */
export async function testInformationGaps(api, app, { dir = metadataDir } = {}) {
  const appId = app.id;
  const primary = app.attributes?.primaryLocale || 'en-US';
  const filled = [];
  const gaps = [];

  const res = await api('GET', `/apps/${q(appId)}/betaAppLocalizations?limit=50`);
  const locs = res.data ?? [];
  for (const loc of locs) {
    const a = loc.attributes ?? {};
    const text = storeText(a.locale, dir);
    const patch = {};
    if (blank(a.description) && text.description) patch.description = text.description;
    if (blank(a.privacyPolicyUrl) && text.privacyPolicyUrl) {
      patch.privacyPolicyUrl = text.privacyPolicyUrl;
    }
    if (Object.keys(patch).length > 0) {
      await api('PATCH', `/betaAppLocalizations/${q(loc.id)}`, {
        data: { type: 'betaAppLocalizations', id: loc.id, attributes: patch },
      });
      Object.assign(a, patch);
      filled.push(`${a.locale}: ${Object.keys(patch).join(', ')}`);
    }
  }
  let primaryLoc = locs.find((l) => l.attributes?.locale === primary);
  if (!primaryLoc) {
    const text = { ...storeText('en-US', dir), ...storeText(primary, dir) };
    const made = await api('POST', '/betaAppLocalizations', {
      data: {
        type: 'betaAppLocalizations',
        attributes: { locale: primary, ...text },
        relationships: { app: { data: { type: 'apps', id: appId } } },
      },
    });
    primaryLoc = { id: made.data?.id, attributes: { locale: primary, ...text } };
    filled.push(`${primary}: ${['locale', ...Object.keys(text)].join(', ')}`);
  }
  const m = primaryLoc.attributes ?? {};
  if (blank(m.description)) gaps.push(`Beta App Description (${primary})`);
  if (blank(m.feedbackEmail)) gaps.push(`Feedback Email (${primary})`);

  const detail = await api('GET', `/apps/${q(appId)}/betaAppReviewDetail`).catch((err) => {
    if (err.status === 404) return { data: null };
    throw err;
  });
  const d = detail.data?.attributes ?? {};
  const contact = [
    ['contactFirstName', 'First name'],
    ['contactLastName', 'Last name'],
    ['contactPhone', 'Phone number'],
    ['contactEmail', 'Email'],
  ]
    .filter(([k]) => blank(d[k]))
    .map(([, label]) => label);
  if (contact.length)
    gaps.push(`Beta App Review Information → Contact Information: ${contact.join(', ')}`);
  if (d.demoAccountRequired !== true) {
    gaps.push(
      'Beta App Review Information → tick "Sign-in required" (the app only works signed in to a hub)',
    );
  }
  const account = [
    ['demoAccountName', 'User name'],
    ['demoAccountPassword', 'Password'],
  ]
    .filter(([k]) => blank(d[k]))
    .map(([, label]) => label);
  if (account.length) {
    gaps.push(
      `Beta App Review Information → Sign-in required: the demo account's ${account.join(' and ')}`,
    );
  }
  if (blank(d.notes)) {
    gaps.push(
      'Beta App Review Information → Review Notes: the demo hub address (docs/store/apple/review-notes.md)',
    );
  }
  return { filled, gaps };
}

// Beta App Review already has the build, or is done with it.
const REVIEWED = new Set([
  'WAITING_FOR_BETA_REVIEW',
  'IN_BETA_REVIEW',
  'BETA_APPROVED',
  'IN_BETA_TESTING',
]);

/** Submits the build for Beta App Review unless it already is. Returns { status, state }. */
export async function submitForBetaReview(api, buildId, externalBuildState) {
  if (REVIEWED.has(externalBuildState)) return { status: 'already', state: externalBuildState };
  const res = await api('GET', `/betaAppReviewSubmissions?filter[build]=${q(buildId)}&limit=5`);
  const prior = (res.data ?? [])[0];
  const state = prior?.attributes?.betaReviewState;
  if (state === 'WAITING_FOR_REVIEW' || state === 'IN_REVIEW' || state === 'APPROVED') {
    return { status: 'already', state };
  }
  if (state === 'REJECTED') return { status: 'rejected', state };
  const made = await api('POST', '/betaAppReviewSubmissions', {
    data: {
      type: 'betaAppReviewSubmissions',
      relationships: { build: { data: { type: 'builds', id: buildId } } },
    },
  });
  return {
    status: 'submitted',
    state: made.data?.attributes?.betaReviewState ?? 'WAITING_FOR_REVIEW',
  };
}

export async function addBuildToGroup(api, groupId, buildId) {
  await api('POST', `/betaGroups/${q(groupId)}/relationships/builds`, {
    data: [{ type: 'builds', id: buildId }],
  });
}

/** The whole job. Returns the process exit code. */
export async function publish({
  api,
  bundleId,
  buildNumber,
  version,
  group = DEFAULT_GROUP,
  linkLimit,
  metadata = metadataDir,
  timeoutMs = 30 * 60_000,
  intervalMs = 30_000,
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)),
  clock = () => Date.now(),
  log = githubLog,
}) {
  const name = String(group ?? '').trim();
  if (!name) {
    log.info('No external TestFlight group asked for; nothing to do.');
    return 0;
  }
  let limit;
  try {
    limit = parseLinkLimit(linkLimit);
  } catch (err) {
    log.error(err.message);
    return 1;
  }
  const label = `build ${buildNumber} (${version})`;

  const app = await findApp(api, bundleId);
  if (!app) {
    log.error(`No app with bundle id ${bundleId} in App Store Connect (or the key cannot see it).`);
    return 1;
  }

  let publicGroup;
  try {
    const { group: g, action } = await ensurePublicGroup(api, app.id, { name, limit });
    publicGroup = g;
    const a = g.attributes ?? {};
    if (action !== 'unchanged') {
      log.notice(
        `TestFlight group "${a.name ?? name}" ${action}: public link on, up to ${limit} testers.`,
      );
    }
    if (a.publicLink) {
      log.notice(`TestFlight public link for "${a.name ?? name}": ${a.publicLink}`);
      log.summary(`- TestFlight public link (${a.name ?? name}): ${a.publicLink}`);
    } else {
      log.warning(
        `App Store Connect gave no public link for "${a.name ?? name}" yet; it appears once the ` +
          'group has an approved build (TestFlight → the group → Public Link).',
      );
      log.summary(`- TestFlight public link (${a.name ?? name}): not given yet.`);
    }
  } catch (err) {
    log.error(err.message);
    return 1;
  }

  const waited = await waitForBuild(api, {
    appId: app.id,
    buildNumber,
    version,
    timeoutMs,
    intervalMs,
    sleep,
    clock,
    log,
  });
  if (waited.status === 'timeout') {
    log.warning(
      `App Store Connect had not finished processing ${label} after ${Math.round(timeoutMs / 60_000)} ` +
        `minutes, so it was NOT submitted for Beta App Review or added to "${name}". The upload ` +
        `itself succeeded. By hand: TestFlight → ${name} → Builds → +, which submits it.`,
    );
    log.summary(`- TestFlight: ${label} still processing; not submitted for "${name}".`);
    return 0;
  }
  if (waited.status === 'rejected') {
    log.error(
      `App Store Connect rejected ${label}: processingState ${waited.found.build.attributes?.processingState}.`,
    );
    return 1;
  }
  const { build, beta } = waited.found;

  try {
    const set = await setWhatToTest(api, build.id, whatToTest(version), log);
    log.info(`"What to Test" set for ${set.join(', ')}.`);
  } catch (err) {
    log.error(`"What to Test" could not be set, so ${label} was not submitted: ${err.message}`);
    return 1;
  }

  const { filled, gaps } = await testInformationGaps(api, app, { dir: metadata });
  for (const f of filled) log.notice(`Test Information filled from the App Store listing: ${f}.`);
  if (gaps.length > 0) {
    log.error(
      `Beta App Review needs details only the owner can give, so ${label} was NOT submitted and ` +
        `not added to "${name}". Fill them once in ${TEST_INFORMATION}: ${gaps.join('; ')}. ` +
        `Then add this build by hand (TestFlight → ${name} → Builds → +, which submits it), or ` +
        'the next upload does it. The upload and the internal groups are not affected.',
    );
    log.summary(
      `- TestFlight: ${label} not submitted for "${name}"; missing in Test Information: ${gaps.join('; ')}.`,
    );
    return 1;
  }

  if (beta.externalBuildState === 'MISSING_EXPORT_COMPLIANCE') {
    log.error(
      `${label} is missing export compliance, so it cannot go to Beta App Review. Answer it in ` +
        `App Store Connect → TestFlight → the build, then add it to "${name}" by hand.`,
    );
    return 1;
  }

  const review = await submitForBetaReview(api, build.id, beta.externalBuildState);
  if (review.status === 'rejected') {
    log.error(
      `Beta App Review rejected ${label} earlier; see App Store Connect → TestFlight → the build. ` +
        'Not submitted again.',
    );
    log.summary(`- TestFlight: ${label} was rejected by Beta App Review.`);
    return 1;
  }
  if (review.status === 'already') {
    log.notice(`${label} is already with Beta App Review (${review.state}); not submitted again.`);
  } else {
    log.notice(`Submitted ${label} for Beta App Review (${review.state}).`);
  }

  await addBuildToGroup(api, publicGroup.id, build.id);
  log.notice(`Added ${label} to TestFlight group "${name}".`);
  log.summary(
    `- TestFlight: ${label} in "${name}"; Beta App Review ${review.status === 'already' ? review.state : 'submitted'}. ` +
      'Testers with the public link get it once Apple approves it.',
  );
  return 0;
}

async function main() {
  const [bundleId, buildNumber, version, group, linkLimit] = process.argv.slice(2);
  const {
    ASC_API_KEY_ID: keyId,
    ASC_API_ISSUER_ID: issuer,
    ASC_API_KEY_PATH: keyPath,
  } = process.env;
  if (!bundleId || !buildNumber || !version || !keyId || !issuer || !keyPath) {
    console.error(
      'usage: ASC_API_KEY_ID=… ASC_API_ISSUER_ID=… ASC_API_KEY_PATH=… ' +
        'testflight-public.mjs <bundle id> <build number> <marketing version> <group> [link limit]',
    );
    return 2;
  }
  const api = createClient({ keyId, issuer, privateKey: readFileSync(keyPath) });
  const minutes = Number(process.env.TESTFLIGHT_WAIT_MINUTES) || 30;
  const seconds = Number(process.env.TESTFLIGHT_POLL_SECONDS) || 30;
  return publish({
    api,
    bundleId,
    buildNumber,
    version,
    group,
    linkLimit,
    timeoutMs: minutes * 60_000,
    intervalMs: seconds * 1000,
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().then(
    (code) => process.exit(code),
    (err) => {
      console.log(`::error::${err.message}`);
      process.exit(1);
    },
  );
}
