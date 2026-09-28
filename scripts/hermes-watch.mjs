#!/usr/bin/env node
// The Hermes watch (DECISIONS §132; .github/workflows/hermes-watch.yml and release.yml).
//
// Core Hub is proven against two Hermes releases: the floor (the oldest it works with) and the
// tested one the image carries (`packages/server/src/modules/agents/catalog/hermes-versions.ts`,
// `packages/server/Dockerfile` `HERMES_REF`). This script reads them, asks GitHub for Hermes's
// latest release, moves the tested pin to a release, and writes the report the workflows put on
// a pull request or an issue. It never merges anything.
//
//   node scripts/hermes-watch.mjs current                 → {"floor":…,"tested":…}; exit 1 if the
//                                                           Dockerfile and hermes-versions.ts differ
//   node scripts/hermes-watch.mjs latest [--output file]  → {"latest":…,"tested":…,"newer":bool}
//   node scripts/hermes-watch.mjs bump --ref vX --version X.Y.Z
//   node scripts/hermes-watch.mjs report --results vitest.json --ref vX --version X.Y.Z
//       --run-url URL --body body.md [--summary summary.json] [--output $GITHUB_OUTPUT]
//   node scripts/hermes-watch.mjs record --ref vX --version X.Y.Z --run-url URL --branch B
//       --total N [--date YYYY-MM-DD]   → writes the change record of the bot's pull request
//
// `latest` reads GITHUB_TOKEN when it is set (a higher rate limit); the release list is public.
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export const HERMES_REPO = 'NousResearch/hermes-agent';
export const FILES = {
  dockerfile: 'packages/server/Dockerfile',
  versions: 'packages/server/src/modules/agents/catalog/hermes-versions.ts',
};

const PIN_LINE = (name) =>
  new RegExp(`^export const ${name} = \\{ ref: '([^']+)', version: '([^']+)' \\} as const;$`, 'm');
const DOCKER_ARG = /^ARG HERMES_REF=(\S+)$/m;

/** The floor and the tested pin, and the Dockerfile's ref, from the two files' texts. */
export function readPins({ dockerfile, versions }) {
  const pin = (name) => {
    const match = PIN_LINE(name).exec(versions);
    if (!match) throw new Error(`${FILES.versions}: no "${name}" line in the expected shape`);
    return { ref: match[1], version: match[2] };
  };
  const arg = DOCKER_ARG.exec(dockerfile);
  if (!arg) throw new Error(`${FILES.dockerfile}: no "ARG HERMES_REF=" line`);
  return { floor: pin('HERMES_FLOOR'), tested: pin('HERMES_TESTED'), dockerfileRef: arg[1] };
}

/**
 * The version a Hermes release carries. Its releases are named "Hermes Agent v0.21.5
 * (v2026.9.24)" and their notes open with "# Hermes Agent v0.21.5 (v2026.9.24)".
 */
export function releaseVersion(release) {
  for (const text of [release.name, release.body]) {
    const match = /Hermes Agent v(\d+\.\d+\.\d+)\b/.exec(String(text ?? ''));
    if (match) return match[1];
  }
  return null;
}

/** Numeric, part by part; a pre-release sorts before its release. */
export function compareVersions(a, b) {
  const parse = (value) => {
    const [core = '', pre] = String(value).trim().replace(/^v/, '').split(/-(.*)/s, 2);
    return { parts: core.split('.').map((part) => Number.parseInt(part, 10) || 0), pre };
  };
  const left = parse(a);
  const right = parse(b);
  for (let index = 0; index < Math.max(left.parts.length, right.parts.length, 3); index += 1) {
    const diff = (left.parts[index] ?? 0) - (right.parts[index] ?? 0);
    if (diff !== 0) return diff < 0 ? -1 : 1;
  }
  if (left.pre === right.pre) return 0;
  if (left.pre === undefined) return 1;
  if (right.pre === undefined) return -1;
  return left.pre < right.pre ? -1 : 1;
}

/** The release to compare with: the newest published, non-draft, non-prerelease one. */
export function pickLatest(releases) {
  const usable = releases
    .filter((release) => !release.draft && !release.prerelease)
    .map((release) => ({ ref: release.tag_name, version: releaseVersion(release) }))
    .filter((release) => release.ref && release.version);
  usable.sort((a, b) => compareVersions(b.version, a.version));
  return usable[0] ?? null;
}

/** What the watch decides: is there a Hermes release newer than the tested pin? */
export function verdict(latest, tested) {
  return { latest, tested, newer: !!latest && compareVersions(latest.version, tested.version) > 0 };
}

/** The two files with the tested pin moved to `ref` / `version`. The floor never moves here. */
export function bumpTexts({ dockerfile, versions }, { ref, version }) {
  if (!/^v[0-9][0-9A-Za-z.-]*$/.test(ref)) throw new Error(`not a Hermes tag: ${ref}`);
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error(`not a version: ${version}`);
  readPins({ dockerfile, versions });
  return {
    dockerfile: dockerfile.replace(DOCKER_ARG, `ARG HERMES_REF=${ref}`),
    versions: versions.replace(
      PIN_LINE('HERMES_TESTED'),
      `export const HERMES_TESTED = { ref: '${ref}', version: '${version}' } as const;`,
    ),
  };
}

/** Every failed test in a vitest JSON report (`--reporter=json`), as "file › name". */
export function failingTests(report) {
  const failed = [];
  for (const file of report.testResults ?? []) {
    const name = path.relative(ROOT, file.name ?? file.testFilePath ?? '');
    const assertions = file.assertionResults ?? [];
    for (const test of assertions) {
      if (test.status === 'failed') failed.push(`${name} › ${test.fullName ?? test.title}`);
    }
    // A file that failed before any test ran (an import or a hook) has no failed assertion.
    if (file.status === 'failed' && !assertions.some((test) => test.status === 'failed')) {
      failed.push(`${name} › (the file failed: ${String(file.message ?? '').split('\n')[0]})`);
    }
  }
  return failed;
}

/** The Markdown the workflows put on the issue (red) or the pull request (green). */
export function reportBody({ latest, tested, floor, failed, total, runUrl }) {
  const head = [
    `Hermes **${latest.ref}** (\`${latest.version}\`) is out; Core Hub is tested with ${tested.ref} (\`${tested.version}\`) and works down to ${floor.ref} (\`${floor.version}\`).`,
    '',
    `Every real-Hermes suite (\`*.real.test.ts\`) was run against an image built with \`HERMES_REF=${latest.ref}\`: [the run](${runUrl}).`,
    '',
  ];
  if (failed.length === 0) {
    return [
      ...head,
      `**All ${total} tests passed.** This pull request moves the image's Hermes to ${latest.ref}.`,
      '',
      'Before merging: CI runs the real-Hermes suites again on both the floor and the new pin (job `hermes-real`), and the change record for the move is still to be written (docs/changes/). Never merged automatically.',
      '',
    ].join('\n');
  }
  return [
    ...head,
    `**${failed.length} of ${total} tests failed**, so the image stays on ${tested.ref}:`,
    '',
    ...failed.map((line) => `- \`${line}\``),
    '',
    'Fix each in the hub (version-aware, so the floor keeps passing), then move the pin by pull request (`node scripts/hermes-watch.mjs bump`). This issue is updated by every run and closed when a later run passes.',
    '',
  ].join('\n');
}

/** Where the bot's change record for moving the pin goes (`docs/changes/README.md`). */
export function recordPath(date, ref) {
  return `docs/changes/${date}-twuijri-hermes-${ref.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.md`;
}

/**
 * The change record of the pull request the watch opens (TEAM-RULES §2; Arabic, like every record).
 * It says what the watch did and ran, and leaves the rest to the owner's review.
 */
export function recordText({ latest, tested, floor, runUrl, branch, total }) {
  return [
    `# نقل صورة المركز إلى هرمز ${latest.ref} (${latest.version})`,
    `المسؤول: twuijri · الفرع: ${branch} · الحالة: review`,
    '',
    '## المشكلة والهدف',
    `صدر هرمز ${latest.ref} (\`${latest.version}\`) والصورة على ${tested.ref} (\`${tested.version}\`). فتح مراقب هرمز (DECISIONS §132) هذا الطلب لأن كل اختبارات هرمز الحقيقية نجحت عليه.`,
    '',
    '## القرار والموافقات',
    `نقل \`HERMES_REF\` و\`HERMES_TESTED\` إلى ${latest.ref}؛ الحد الأدنى يبقى ${floor.ref} (\`${floor.version}\`). مقترح آليًا — ينتظر مراجعة المالك ودمجه، ولا يُدمج آليًا أبدًا.`,
    '',
    '## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)',
    'لا شيء.',
    '',
    '## الملفات والتأثير',
    '- `packages/server/Dockerfile` (`HERMES_REF`)',
    '- `packages/server/src/modules/agents/catalog/hermes-versions.ts` (`HERMES_TESTED`)',
    '',
    '## الفحوص (الأوامر ونواتجها الفعلية)',
    `تشغيل المراقب: ${runUrl}`,
    '```',
    `COREHUB_HERMES_IMAGE=<صورة بهرمز ${latest.ref}> vitest run $(find src tests -name '*.real.test.ts')`,
    `→ ${total} passed, 0 failed`,
    '```',
    'ويشغّل CI الاختبارات الحقيقية مرة أخرى على الحد الأدنى والإصدار الجديد (`hermes-real`) عند كل دفع لهذا الفرع.',
    '',
    '## المخاطر والرجوع',
    'ما لا تغطيه الاختبارات الحقيقية قد يتغير في هرمز الجديد. الرجوع: إعادة `HERMES_REF` إلى الإصدار السابق وبناء الصورة.',
    '',
    '## التسليم والخطوة التالية',
    'مراجعة المالك؛ وبعد الدمج إصدار صورة جديدة حسب docs/RELEASING.md.',
    '',
  ].join('\n');
}

function argsOf(argv) {
  const out = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (key.startsWith('--')) out[key.slice(2)] = argv[(index += 1)];
  }
  return out;
}

const readFiles = () => ({
  dockerfile: readFileSync(path.join(ROOT, FILES.dockerfile), 'utf8'),
  versions: readFileSync(path.join(ROOT, FILES.versions), 'utf8'),
});

async function fetchReleases() {
  const headers = { accept: 'application/vnd.github+json', 'user-agent': 'core-hub-hermes-watch' };
  if (process.env.GITHUB_TOKEN) headers.authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const response = await fetch(`https://api.github.com/repos/${HERMES_REPO}/releases?per_page=30`, {
    headers,
  });
  if (!response.ok)
    throw new Error(`GitHub answered ${response.status} for ${HERMES_REPO}'s releases`);
  return response.json();
}

/** `key=value` lines for `$GITHUB_OUTPUT`. */
function writeOutput(file, values) {
  if (!file) return;
  appendFileSync(
    file,
    Object.entries(values)
      .map(([key, value]) => `${key}=${value}\n`)
      .join(''),
  );
}

async function main([command, ...rest]) {
  const args = argsOf(rest);
  if (command === 'current') {
    const pins = readPins(readFiles());
    console.log(JSON.stringify(pins));
    writeOutput(args.output, {
      floor_ref: pins.floor.ref,
      floor_version: pins.floor.version,
      tested_ref: pins.tested.ref,
      tested_version: pins.tested.version,
    });
    if (pins.dockerfileRef !== pins.tested.ref) {
      console.error(
        `${FILES.dockerfile} has HERMES_REF=${pins.dockerfileRef}, ${FILES.versions} says ${pins.tested.ref}`,
      );
      return 1;
    }
    return 0;
  }
  if (command === 'latest') {
    const { tested } = readPins(readFiles());
    const result = verdict(pickLatest(await fetchReleases()), tested);
    console.log(JSON.stringify(result));
    writeOutput(args.output, {
      newer: String(result.newer),
      latest_ref: result.latest?.ref ?? '',
      latest_version: result.latest?.version ?? '',
    });
    return 0;
  }
  if (command === 'bump') {
    const next = bumpTexts(readFiles(), { ref: args.ref, version: args.version });
    writeFileSync(path.join(ROOT, FILES.dockerfile), next.dockerfile);
    writeFileSync(path.join(ROOT, FILES.versions), next.versions);
    console.log(`Hermes pin moved to ${args.ref} (${args.version})`);
    return 0;
  }
  if (command === 'report') {
    const { floor, tested } = readPins(readFiles());
    let report = { testResults: [], numTotalTests: 0 };
    let unreadable = false;
    try {
      report = JSON.parse(readFileSync(args.results, 'utf8'));
    } catch {
      unreadable = true;
    }
    const failed = unreadable
      ? ['(no test report: the image did not build or the suites did not run; see the run)']
      : failingTests(report);
    const body = reportBody({
      latest: { ref: args.ref, version: args.version },
      tested,
      floor,
      failed,
      total: report.numTotalTests ?? 0,
      runUrl: args['run-url'] ?? '',
    });
    writeFileSync(args.body, body);
    if (args.summary)
      writeFileSync(args.summary, JSON.stringify({ passed: failed.length === 0, failed }));
    writeOutput(args.output, { passed: String(failed.length === 0) });
    console.log(body);
    return 0;
  }
  if (command === 'record') {
    const { floor, tested } = readPins(readFiles());
    const date = args.date ?? new Date().toISOString().slice(0, 10);
    const file = recordPath(date, args.ref);
    writeFileSync(
      path.join(ROOT, file),
      recordText({
        latest: { ref: args.ref, version: args.version },
        tested,
        floor,
        runUrl: args['run-url'] ?? '',
        branch: args.branch ?? '',
        total: Number(args.total ?? 0),
      }),
    );
    console.log(file);
    return 0;
  }
  console.error('usage: hermes-watch.mjs current|latest|bump|report|record …');
  return 2;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (error) => {
      console.error(error instanceof Error ? error.message : error);
      process.exit(1);
    },
  );
}
