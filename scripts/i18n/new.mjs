#!/usr/bin/env node
// `pnpm i18n:new <code>`: adds a UI language (ADR 0028, CONTRIBUTING "Add your language").
//
//   pnpm i18n:new fr
//   pnpm i18n:new zh-Hant --fallback zh-Hans
//   pnpm i18n:new ckb --english-name "Central Kurdish" --native-name "کوردیی ناوەندی" --direction rtl
//
// It writes the registry entry (status `partial`), a catalogue per platform — the English keys
// with empty strings, so every string still reads in English until it is translated — and the
// generated catalogue indexes. Translate by filling the empty strings; `pnpm i18n:check` shows
// the coverage and `pnpm i18n:limits` the space each string has.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import * as prettier from 'prettier';
import { staleIndexes } from './generate.mjs';
import { SETS, readRegistry, registryFile, repoRoot } from './registry.mjs';

const CODE = /^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$/;
const RTL = new Set(['ar', 'fa', 'he', 'ur', 'ps', 'sd', 'ug', 'yi', 'dv', 'ckb', 'syr']);

function option(name) {
  const at = process.argv.indexOf(`--${name}`);
  return at > 0 ? process.argv[at + 1] : undefined;
}

function die(message) {
  console.error(`i18n:new  ${message}`);
  process.exit(1);
}

const code = process.argv
  .slice(2)
  .find((arg, index, all) => !arg.startsWith('--') && !all[index - 1]?.startsWith('--'));
if (!code)
  die(
    'usage: pnpm i18n:new <code> [--english-name …] [--native-name …] [--direction ltr|rtl] [--fallback a,b]',
  );
if (!CODE.test(code))
  die(`"${code}" is not a BCP 47 tag of the form language[-Script][-REGION] (fr, pt-BR, zh-Hant)`);
let canonical;
try {
  [canonical] = Intl.getCanonicalLocales(code);
} catch {
  die(`"${code}" is not a valid BCP 47 tag`);
}
if (canonical !== code) die(`write it as "${canonical}"`);

const registry = readRegistry();
if (registry.languages.some((each) => each.code.toLowerCase() === code.toLowerCase()))
  die(`${code} is already registered in locales/languages.json`);
if (registry.pseudo.some((each) => each.code.toLowerCase() === code.toLowerCase()))
  die(`${code} is a test-only pseudo-locale`);

const named = (locales) => {
  try {
    return new Intl.DisplayNames(locales, { type: 'language' }).of(code);
  } catch {
    return undefined;
  }
};
const englishName = option('english-name') ?? named(['en']);
// A language names itself in lower case inside a sentence ("français"); a picker lists it
// capitalised, as the language would at the start of a line.
const own = named([code]);
const nativeName =
  option('native-name') ?? (own ? own.charAt(0).toLocaleUpperCase(code) + own.slice(1) : own);
if (!englishName || !nativeName || englishName === code)
  die('pass --english-name and --native-name: this Node has no names for that language');
let direction = option('direction');
if (!direction) {
  try {
    const locale = new Intl.Locale(code);
    direction = (locale.getTextInfo?.() ?? locale.textInfo)?.direction;
  } catch {
    direction = undefined;
  }
  direction ??= RTL.has(code.split('-')[0]) ? 'rtl' : 'ltr';
}
if (!['ltr', 'rtl'].includes(direction)) die('--direction is ltr or rtl');

// Fallback: what is given, else the registered parent of a script/region variant (pt-BR → pt).
const known = new Set(registry.languages.map((each) => each.code));
let fallback = option('fallback')
  ?.split(',')
  .map((each) => each.trim())
  .filter(Boolean);
if (fallback) {
  for (const each of fallback) if (!known.has(each)) die(`fallback "${each}" is not registered`);
} else {
  const parts = code.split('-');
  fallback = [];
  for (let end = parts.length - 1; end > 0; end -= 1) {
    const parent = parts.slice(0, end).join('-');
    if (known.has(parent)) fallback.push(parent);
  }
}
fallback = fallback.filter((each) => each !== 'en');

const entry = {
  code,
  englishName,
  nativeName,
  direction,
  status: 'partial',
  required: false,
  fallback,
  numerals: 'latn',
};

/** The English catalogue with every string emptied: the shape to translate into. */
function blank(value) {
  if (value && typeof value === 'object' && !Array.isArray(value))
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, blank(child)]));
  return '';
}

async function write(file, data) {
  const options = (await prettier.resolveConfig(file)) ?? {};
  writeFileSync(
    file,
    await prettier.format(JSON.stringify(data, null, 2), { ...options, filepath: file }),
  );
  console.log(`i18n:new  wrote ${path.relative(repoRoot, file)}`);
}

const raw = JSON.parse(readFileSync(registryFile, 'utf8'));
raw.languages.push(entry);
await write(registryFile, raw);

for (const set of SETS) {
  const dir = path.join(repoRoot, set.dir);
  if (!existsSync(dir)) continue;
  // `en.json`, and on the phones every area's `<area>.en.json` too.
  const english = readdirSync(dir).filter(
    (name) => name === 'en.json' || (set.areas && name.endsWith('.en.json')),
  );
  for (const name of english) {
    const target = path.join(dir, name.replace(/en\.json$/, `${code}.json`));
    if (existsSync(target)) {
      console.log(`i18n:new  kept ${path.relative(repoRoot, target)} (already there)`);
      continue;
    }
    await write(target, blank(JSON.parse(readFileSync(path.join(dir, name), 'utf8'))));
  }
}

for (const { file, want } of await staleIndexes(readRegistry())) {
  writeFileSync(file, want);
  console.log(`i18n:new  wrote ${path.relative(repoRoot, file)}`);
}

console.log(`
i18n:new  ${englishName} (${nativeName}, ${direction}) is registered as partial.
Next:
  1. Fill the empty strings in each ${code}.json above (keep {placeholders} and \`code\` as they are).
  2. pnpm i18n:check     — coverage per platform, and anything that would break the UI
  3. pnpm i18n:limits    — every string that is wider than its place (locales/limits.md)
  4. Open a pull request (template: "New language"). The owner marks it complete when it is.`);
