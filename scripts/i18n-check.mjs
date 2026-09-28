#!/usr/bin/env node
// `pnpm i18n:check`: every catalogue against the language registry (locales/languages.json,
// ADR 0028).
//
// - The registry itself: unique BCP 47 codes, Arabic and English required and complete, every
//   fallback and pseudo-locale base registered, no fallback loops.
// - Arabic and English (the required languages): the same keys, the same placeholders and
//   no empty string — in every set, exactly as before any language was added.
// - Every other language: its coverage is reported per set; it fails only on what would break
//   the UI — a placeholder the English string does not have (or one it drops), a changed code
//   span or line count, a value that is not a string, a plural group without `other`, digits
//   other than 0-9 (DECISIONS §113), or `status: complete` while keys are missing.
// - The generated catalogue indexes (`src/i18n/catalogues.ts`) match the registry.
// The space rules (how wide a string may be) are `pnpm i18n:limits`, which needs the fonts.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { staleIndexes } from './i18n/generate.mjs';
import { SETS, readCatalogue, readRegistry, repoRoot } from './i18n/registry.mjs';

const OLD_WORD = { en: /\bworkspaces?\b/i, ar: /مساح(?:ة|ات) (?:ال)?عمل/ };
// Latin digits (123) everywhere, also in the Arabic UI (owner, 2026-09-26, DECISIONS §113):
// no other script's digits — nor Arabic's percent, thousands and decimal signs — in a catalogue,
// unless the owner lets a language use its own (`numerals: native`).
const NATIVE_DIGITS = /[\u0660-\u066C\u06F0-\u06F9]|(?![0-9])\p{Nd}/u;
const ANDROID_AR = 'apps/android/app/src/main/res/values-ar';
const PLURAL = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);
const verbose = process.argv.includes('--verbose');

let failures = 0;
const fail = (msg) => {
  failures += 1;
  console.error(`  error  ${msg}`);
};
const warn = (msg) => console.warn(`  warn   ${msg}`);

const placeholderList = (text) => [...String(text).matchAll(/\{[a-zA-Z0-9_]+\}/g)].map((m) => m[0]);
const placeholders = (text) => [...new Set(placeholderList(text))].sort().join(',');
const codeSpans = (text) =>
  [...String(text).matchAll(/`[^`]*`/g)]
    .map((m) => m[0])
    .sort()
    .join('\u0000');
const lines = (text) => String(text).split('\n').length;

/** `a.b.few` → `a.b` when the last segment is a CLDR plural category. */
const pluralBase = (key) => {
  const dot = key.lastIndexOf('.');
  return dot > 0 && PLURAL.has(key.slice(dot + 1)) ? key.slice(0, dot) : null;
};

// ---- the registry ------------------------------------------------------------------------
let registry;
try {
  registry = readRegistry();
} catch (error) {
  console.error(`i18n:check  locales/languages.json: ${error.message}`);
  process.exit(1);
}
const { languages, pseudo } = registry;
const codes = languages.map((each) => each.code);
const byCode = new Map(languages.map((each) => [each.code, each]));
const CODE = /^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$/;
for (const each of languages) {
  if (!CODE.test(each.code)) fail(`registry: "${each.code}" is not a BCP 47 language tag`);
  else {
    try {
      Intl.getCanonicalLocales(each.code);
    } catch {
      fail(`registry: "${each.code}" is not a valid BCP 47 tag`);
    }
  }
  for (const field of ['englishName', 'nativeName'])
    if (typeof each[field] !== 'string' || each[field].trim() === '')
      fail(`registry: ${each.code} has no ${field}`);
  if (!['ltr', 'rtl'].includes(each.direction))
    fail(`registry: ${each.code} direction is "${each.direction}" — ltr or rtl`);
  if (!['complete', 'partial'].includes(each.status))
    fail(`registry: ${each.code} status is "${each.status}" — complete or partial`);
  if (!['latn', 'native'].includes(each.numerals))
    fail(`registry: ${each.code} numerals is "${each.numerals}" — latn or native`);
  for (const next of each.fallback ?? [])
    if (!byCode.has(next)) fail(`registry: ${each.code} falls back to "${next}", not registered`);
}
if (new Set(codes).size !== codes.length) fail('registry: a language code appears twice');
for (const code of ['ar', 'en']) {
  const each = byCode.get(code);
  if (!each) fail(`registry: ${code} must be registered`);
  else if (!each.required || each.status !== 'complete')
    fail(`registry: ${code} must be required and complete`);
}
for (const each of languages)
  if (each.required && !['ar', 'en'].includes(each.code))
    fail(`registry: only ar and en are required; mark ${each.code} "required": false`);
for (const each of languages) {
  const seen = new Set([each.code]);
  const walk = (code) => {
    for (const next of byCode.get(code)?.fallback ?? []) {
      if (seen.has(next)) {
        fail(`registry: ${each.code}'s fallback chain loops through ${next}`);
        return;
      }
      seen.add(next);
      walk(next);
    }
  };
  walk(each.code);
}
for (const each of pseudo) {
  if (!byCode.has(each.base)) fail(`registry: pseudo-locale ${each.code}'s base is not registered`);
  if (byCode.has(each.code)) fail(`registry: ${each.code} is both a language and a pseudo-locale`);
}
console.log(
  `i18n:check  registry: ${languages.length} languages (${languages
    .map((each) => `${each.code}${each.required ? '' : `:${each.status}`}`)
    .join(', ')}), ${pseudo.length} test-only pseudo-locales`,
);

// ---- the catalogues ----------------------------------------------------------------------
const pluralCategoriesOf = (code) => {
  try {
    const set = new Set(new Intl.PluralRules(code).resolvedOptions().pluralCategories);
    set.add('other');
    return set;
  } catch {
    return new Set(['one', 'other']);
  }
};
const present = (catalogue, key) => {
  const value = catalogue.get(key);
  return typeof value === 'string' && value.trim() !== '';
};

for (const set of SETS) {
  const dir = path.join(repoRoot, set.dir);
  if (!existsSync(dir)) {
    if (set.required) fail(`${set.dir} is missing`);
    else console.log(`i18n:check  ${set.name}: ${set.dir} not present yet — skipped`);
    continue;
  }
  const catalogues = {};
  for (const each of languages) {
    const catalogue = readCatalogue(set, each.code, fail);
    if (catalogue === null) {
      if (existsSync(path.join(dir, `${each.code}.json`))) continue; // unreadable: reported
      // The phone apps join the registry in phase 2 (ADR 0028): until then only ar/en there.
      if (each.required || set.index) fail(`${set.dir}/${each.code}.json is missing`);
      continue;
    }
    catalogues[each.code] = catalogue;
  }
  const { ar, en } = catalogues;
  if (!ar || !en) continue;

  // Arabic and English: strict, as always.
  for (const key of en.keys())
    if (!ar.has(key)) fail(`${set.name}: "${key}" exists in en.json but not in ar.json`);
  for (const key of ar.keys())
    if (!en.has(key)) fail(`${set.name}: "${key}" exists in ar.json but not in en.json`);
  for (const [key, value] of en) {
    if (typeof value !== 'string' || value.trim() === '')
      fail(`${set.name}: en "${key}" is empty or not a string`);
    const other = ar.get(key);
    if (typeof other !== 'string' || other.trim() === '')
      fail(`${set.name}: ar "${key}" is empty or not a string`);
    else if (placeholders(value) !== placeholders(other))
      fail(`${set.name}: "${key}" placeholders differ between ar and en`);
  }
  // One word for one thing (owner, 2026-09-23): what the code calls a workspace, a person
  // reads as «بروفايل» / "profile" — in the hub's own messages as much as in a client's.
  // Keys, API fields and codes keep `workspace`; only the text a person reads is checked.
  for (const [key, value] of en)
    if (typeof value === 'string' && OLD_WORD.en.test(value))
      fail(`${set.name}: en "${key}" says "workspace" — the product word is "profile"`);
  for (const [key, value] of ar)
    if (typeof value === 'string' && OLD_WORD.ar.test(value))
      fail(`${set.name}: ar "${key}" says «مساحة العمل» — the product word is «بروفايل»`);

  // Plural strings: English keys ending in a CLDR category, grouped by what comes before it.
  // `x.other` alone is a word ("Other"), not a plural string.
  const pluralGroups = new Map();
  for (const key of en.keys()) {
    const base = pluralBase(key);
    if (!base) continue;
    if (!pluralGroups.has(base)) pluralGroups.set(base, new Set());
    pluralGroups.get(base).add(key.slice(base.length + 1));
  }
  for (const [base, categories] of [...pluralGroups])
    if (categories.size < 2 || !categories.has('other')) pluralGroups.delete(base);

  const report = [];
  for (const each of languages) {
    const catalogue = catalogues[each.code];
    if (!catalogue) continue;
    const lang = each.code;
    if (each.numerals !== 'native')
      for (const [key, value] of catalogue)
        if (typeof value === 'string' && NATIVE_DIGITS.test(value))
          fail(
            `${set.name}: ${lang} "${key}" has non-Latin digits — digits are Latin (123) everywhere (DECISIONS §113)`,
          );
    if (each.required) continue;

    let translated = 0;
    const wanted = pluralCategoriesOf(lang);
    for (const [key, value] of catalogue) {
      if (typeof value !== 'string') {
        fail(`${set.name}: ${lang} "${key}" is not a string`);
        continue;
      }
      if (value.trim() === '') continue; // untranslated: falls back along the chain
      const base = pluralBase(key);
      const inGroup = base !== null && pluralGroups.has(base);
      const english = en.get(key) ?? (inGroup ? en.get(`${base}.other`) : undefined);
      if (english === undefined) {
        warn(`${set.name}: ${lang} "${key}" is not an English key — nothing reads it`);
        continue;
      }
      const theirs = placeholderList(value);
      const ours = placeholderList(english);
      const extra = [...new Set(theirs.filter((p) => !ours.includes(p)))];
      // A plural form may leave its number out ("one file"); any other string keeps them all.
      const dropped = inGroup ? [] : [...new Set(ours.filter((p) => !theirs.includes(p)))];
      if (extra.length > 0)
        fail(
          `${set.name}: ${lang} "${key}" has ${extra.join(', ')}, which English does not — it would show as typed`,
        );
      if (dropped.length > 0)
        fail(
          `${set.name}: ${lang} "${key}" drops ${dropped.join(', ')} — keep every {placeholder}`,
        );
      if (codeSpans(value) !== codeSpans(english))
        fail(`${set.name}: ${lang} "${key}" changes a \`code\` span — keep them verbatim`);
      if (lines(value) !== lines(english))
        fail(
          `${set.name}: ${lang} "${key}" has ${lines(value)} line(s), English has ${lines(english)}`,
        );
      if (en.has(key)) translated += 1;
    }
    // A plural string the language translated says `other`; the other forms it uses are wanted.
    for (const [base] of pluralGroups) {
      if (![...PLURAL].some((category) => present(catalogue, `${base}.${category}`))) continue;
      if (!present(catalogue, `${base}.other`))
        fail(`${set.name}: ${lang} "${base}" is translated without "${base}.other"`);
      else if (verbose) {
        const lacking = [...wanted].filter(
          (category) => !present(catalogue, `${base}.${category}`),
        );
        if (lacking.length > 0)
          warn(`${set.name}: ${lang} "${base}" lacks ${lacking.join(', ')}; "other" stands in`);
      }
    }
    const coverage = en.size === 0 ? 100 : Math.floor((translated / en.size) * 1000) / 10;
    report.push(`${lang} ${coverage}%`);
    if (each.status === 'complete' && translated < en.size)
      fail(
        `${set.name}: ${lang} is "complete" in the registry but ${en.size - translated} keys are untranslated`,
      );
  }
  console.log(
    `i18n:check  ${set.name}: ${en.size} keys, ar/en in parity${report.length ? `; ${report.join(', ')}` : ''}`,
  );
}

// ---- generated indexes -------------------------------------------------------------------
for (const { file } of await staleIndexes(registry))
  fail(`${path.relative(repoRoot, file)} is out of date — run \`pnpm i18n:generate\``);

// The Android app keeps its strings in resource XML (its own parity test checks the keys).
if (existsSync(path.join(repoRoot, ANDROID_AR))) {
  for (const name of readdirSync(path.join(repoRoot, ANDROID_AR))) {
    if (!name.endsWith('.xml')) continue;
    const text = readFileSync(path.join(repoRoot, ANDROID_AR, name), 'utf8');
    text.split('\n').forEach((line, index) => {
      if (NATIVE_DIGITS.test(line))
        fail(
          `android: ${ANDROID_AR}/${name}:${index + 1} has Arabic-Indic digits — digits are Latin (123) everywhere`,
        );
    });
  }
  console.log('i18n:check  android: Arabic resources use Latin digits');
}

if (failures > 0) {
  console.error(`i18n:check  FAILED with ${failures} problem(s)`);
  process.exit(1);
}
console.log('i18n:check  OK');
