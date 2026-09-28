// pnpm scripts:test — the language tooling (ADR 0028) on a small tree of its own
// (COREHUB_I18N_ROOT): `pnpm i18n:new` scaffolds a language every platform can load, and
// `pnpm i18n:check` keeps Arabic and English strict while failing another language only on what
// would break the UI; `pnpm i18n:limits` fails a translation wider than both its room and English.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, it } from 'node:test';

const scripts = path.dirname(fileURLToPath(import.meta.url));
let root;

const write = (file, value) => {
  mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
  writeFileSync(
    path.join(root, file),
    typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`,
  );
};
const read = (file) => JSON.parse(readFileSync(path.join(root, file), 'utf8'));
const run = (script, ...args) => {
  const result = spawnSync(process.execPath, [path.join(scripts, script), ...args], {
    env: { ...process.env, COREHUB_I18N_ROOT: root, CI: '' },
    encoding: 'utf8',
  });
  return { code: result.status, out: `${result.stdout}${result.stderr}` };
};
const check = () => run('i18n-check.mjs');

const EN = {
  nav: { chat: 'Chat', tasks: 'Tasks' },
  // Arabic's six plural forms, so English has the same keys (i18n:check keeps them in parity).
  files: {
    count: {
      zero: 'No files',
      one: 'One file',
      two: 'Two files',
      few: '{count} files',
      many: '{count} files',
      other: '{count} files',
    },
  },
  hint: 'Run `corehub help` for {topic}.',
};
const AR = {
  nav: { chat: 'محادثة', tasks: 'المهام' },
  files: {
    count: {
      zero: 'لا ملفات',
      one: 'ملف واحد',
      two: 'ملفان',
      few: '{count} ملفات',
      many: '{count} ملفًا',
      other: '{count} ملف',
    },
  },
  hint: 'شغّل `corehub help` لـ {topic}.',
};

beforeEach(() => {
  root = mkdtempSync(path.join(tmpdir(), 'i18n-'));
  write('locales/languages.json', {
    languages: [
      {
        code: 'ar',
        englishName: 'Arabic',
        nativeName: 'العربية',
        direction: 'rtl',
        status: 'complete',
        required: true,
        fallback: ['en'],
        numerals: 'latn',
      },
      {
        code: 'en',
        englishName: 'English',
        nativeName: 'English',
        direction: 'ltr',
        status: 'complete',
        required: true,
        fallback: [],
        numerals: 'latn',
      },
    ],
    pseudo: [{ code: 'en-XA', base: 'en', direction: 'ltr', style: 'accented' }],
  });
  for (const dir of [
    'packages/server/src/i18n',
    'packages/cli/src/i18n',
    'packages/web/src/i18n',
  ]) {
    write(`${dir}/en.json`, EN);
    write(`${dir}/ar.json`, AR);
  }
  // A phone app: flat resource names, Android's `%1$s`, and a screen's strings in an area file.
  write('apps/android/i18n/en.json', { back: 'Back', error_code: 'The hub refused: %1$s' });
  write('apps/android/i18n/ar.json', { back: 'رجوع', error_code: 'رفض المركز: %1$s' });
  write('apps/android/i18n/tasks.en.json', {
    steps: { one: '%1$d step', other: '%1$d steps' },
  });
  write('apps/android/i18n/tasks.ar.json', {
    steps: {
      zero: 'لا خطوات',
      one: 'خطوة',
      two: 'خطوتان',
      few: '%1$d خطوات',
      many: '%1$d خطوة',
      other: '%1$d خطوة',
    },
  });
  assert.equal(run('i18n/generate.mjs').code, 0);
});
afterEach(() => rmSync(root, { recursive: true, force: true }));

describe('i18n:check with Arabic and English only', () => {
  it('passes, and keeps the two strict', () => {
    assert.equal(check().code, 0, check().out);
    write('packages/web/src/i18n/ar.json', { ...AR, nav: { chat: 'محادثة' } });
    const result = check();
    assert.equal(result.code, 1);
    assert.match(result.out, /"nav.tasks" exists in en.json but not in ar.json/);
  });

  it('refuses Arabic-Indic digits and a stale catalogue index', () => {
    write('packages/server/src/i18n/ar.json', {
      ...AR,
      nav: { chat: 'محادثة ٣', tasks: 'المهام' },
    });
    assert.match(check().out, /non-Latin digits/);
    write('packages/server/src/i18n/ar.json', AR);
    writeFileSync(path.join(root, 'packages/web/src/i18n/catalogues.ts'), '// stale\n');
    assert.match(check().out, /catalogues.ts is out of date/);
  });
});

describe('i18n:new and a partial language', () => {
  it('scaffolds every platform and the registry; every string falls back until translated', () => {
    const made = run('i18n/new.mjs', 'fr');
    assert.equal(made.code, 0, made.out);
    const entry = read('locales/languages.json').languages.find((each) => each.code === 'fr');
    assert.deepEqual(
      { ...entry, nativeName: entry.nativeName.toLowerCase() },
      {
        code: 'fr',
        englishName: 'French',
        nativeName: 'français',
        direction: 'ltr',
        status: 'partial',
        required: false,
        fallback: [],
        numerals: 'latn',
      },
    );
    assert.equal(entry.nativeName, 'Français');
    for (const dir of ['packages/server', 'packages/cli', 'packages/web'])
      assert.deepEqual(read(`${dir}/src/i18n/fr.json`).nav, { chat: '', tasks: '' });
    // The phones too: every area of the English catalogue.
    assert.deepEqual(read('apps/android/i18n/fr.json'), { back: '', error_code: '' });
    assert.deepEqual(read('apps/android/i18n/tasks.fr.json'), { steps: { one: '', other: '' } });
    assert.match(
      readFileSync(path.join(root, 'packages/web/src/i18n/catalogues.ts'), 'utf8'),
      /fr\.json/,
    );
    const result = check();
    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /web: 9 keys, ar\/en in parity; fr 0%/);
  });

  it('knows a right-to-left language and a script variant’s parent', () => {
    assert.equal(run('i18n/new.mjs', 'fa').code, 0);
    assert.equal(run('i18n/new.mjs', 'zh-Hans').code, 0);
    assert.equal(run('i18n/new.mjs', 'zh-Hant', '--fallback', 'zh-Hans').code, 0);
    const byCode = Object.fromEntries(
      read('locales/languages.json').languages.map((each) => [each.code, each]),
    );
    assert.equal(byCode.fa.direction, 'rtl');
    assert.deepEqual(byCode['zh-Hant'].fallback, ['zh-Hans']);
    assert.equal(run('i18n/new.mjs', 'fr-fr').code, 1);
    assert.equal(run('i18n/new.mjs', 'en').code, 1);
  });

  it('fails a translation only on what would break the UI', () => {
    run('i18n/new.mjs', 'fr');
    const fr = (value) => write('packages/web/src/i18n/fr.json', value);
    fr({
      nav: { chat: 'Discussion', tasks: '' },
      files: { count: { one: 'Un fichier', other: '{count} fichiers' } },
      hint: 'Lancez `corehub help` pour {topic}.',
    });
    let result = check();
    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /fr 44.4%/);

    fr({ ...EN, nav: { chat: 'Discussion {name}', tasks: 'Tâches' } });
    assert.match(check().out, /fr "nav.chat" has \{name\}, which English does not/);
    fr({ ...EN, hint: 'Lancez `corehub aide` pour {topic}.' });
    assert.match(check().out, /changes a `code` span/);
    fr({ ...EN, hint: 'Lancez `corehub help`.' });
    assert.match(check().out, /drops \{topic\}/);
    write('apps/android/i18n/fr.json', { back: 'Retour', error_code: 'Refus : %2$s' });
    assert.match(check().out, /android: fr "error_code" has %2\$s, which English does not/);
    write('apps/android/i18n/fr.json', { back: 'Retour', error_code: '' });
    fr({ ...EN, files: { count: { one: 'Un fichier' } } });
    assert.match(check().out, /"files.count" is translated without "files.count.other"/);
    // A plural form may leave its number out; a word English does not have is only a warning.
    fr({
      ...EN,
      files: { count: { one: 'Un fichier', many: 'Beaucoup', other: '{count} fichiers' } },
      extra: 'x',
    });
    result = check();
    assert.equal(result.code, 0, result.out);
    assert.match(result.out, /fr "extra" is not an English key/);
  });

  it('holds a language marked complete to every key', () => {
    run('i18n/new.mjs', 'fr');
    const registry = read('locales/languages.json');
    registry.languages.find((each) => each.code === 'fr').status = 'complete';
    write('locales/languages.json', registry);
    assert.match(check().out, /fr is "complete" in the registry but 9 keys are untranslated/);
  });
});

describe('i18n:limits', () => {
  it('fails a translation wider than its room and than English, and only that', () => {
    run('i18n/new.mjs', 'fr');
    write('locales/limits.json', {
      web: {
        'nav.chat': {
          area: 'ch-sidebar-row-label',
          maxWidth: 60,
          fontSize: 14,
          fontWeight: 400,
          maxLines: 1,
          where: '/chat (desktop)',
        },
        'nav.tasks': {
          area: 'ch-sidebar-row-label',
          maxWidth: 60,
          fontSize: 14,
          fontWeight: 400,
          maxLines: 1,
          where: '/chat (desktop)',
        },
      },
    });
    write('packages/web/src/i18n/fr.json', {
      ...EN,
      nav: { chat: 'Discussion instantanée avec un agent', tasks: 'Tâches' },
    });
    const result = run('i18n/limits.mjs');
    assert.equal(result.code, 1, result.out);
    assert.match(result.out, /fr "nav.chat" is \d+px in a 60px ch-sidebar-row-label/);
    assert.doesNotMatch(result.out, /"nav.tasks" is/);
    write('packages/web/src/i18n/fr.json', { ...EN, nav: { chat: 'Chat', tasks: 'Tâches' } });
    assert.equal(run('i18n/limits.mjs').code, 0);
  });

  it('writes the limits and the translators’ table from a measurement', () => {
    const measured = path.join(root, 'space.json');
    writeFileSync(
      measured,
      JSON.stringify([
        {
          key: 'nav.chat',
          text: 'Chat',
          area: 'ch-sidebar-row-label',
          width: 224,
          fontSize: 14,
          fontWeight: 400,
          screen: '/chat',
          size: 'desktop',
        },
        {
          key: 'nav.chat',
          text: 'Chat',
          area: 'ch-sidebar-row-label',
          width: 200,
          fontSize: 14,
          fontWeight: 400,
          screen: '/tasks',
          size: 'phone',
        },
        {
          key: 'nav.tasks',
          text: 'Tasks',
          area: 'ch-btn-label',
          width: 5000,
          fontSize: 14,
          fontWeight: 500,
          screen: '/chat',
          size: 'desktop',
        },
        {
          key: null,
          text: 'Admin',
          area: 'span',
          width: 96,
          fontSize: 12,
          fontWeight: 400,
          screen: '/chat',
          size: 'desktop',
        },
      ]),
    );
    assert.equal(run('i18n/limits.mjs', '--measure', measured).code, 0);
    const limits = read('locales/limits.json').web;
    assert.deepEqual(Object.keys(limits), ['nav.chat']);
    assert.equal(limits['nav.chat'].maxWidth, 200);
    assert.ok(existsSync(path.join(root, 'locales/limits.md')));
    assert.match(
      readFileSync(path.join(root, 'locales/limits.md'), 'utf8'),
      /`nav.chat` \| Chat +\| 200px/,
    );
  });
});
