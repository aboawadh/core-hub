// The language registry and the catalogue sets, for the i18n scripts (ADR 0028).
// Plain ESM with no dependencies: `pnpm i18n:check` runs before anything is built.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// `COREHUB_I18N_ROOT` points the scripts at another tree: their own tests use a small one.
export const repoRoot =
  process.env.COREHUB_I18N_ROOT ??
  path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const registryFile = path.join(repoRoot, 'locales', 'languages.json');
export const limitsFile = path.join(repoRoot, 'locales', 'limits.json');

/**
 * Every place strings a person reads live. `index`: the generated file that imports each
 * language's catalogue (`eager`: every language in the bundle — small catalogues; `lazy`:
 * Arabic and English in the bundle, the others loaded when chosen). `areas`: the phone apps
 * keep a screen's strings in `<area>.<lang>.json` beside `<lang>.json` (docs/clients/phone-pages.md).
 */
export const SETS = [
  { name: 'server', dir: 'packages/server/src/i18n', required: true, index: 'eager' },
  { name: 'cli', dir: 'packages/cli/src/i18n', required: true, index: 'eager' },
  { name: 'web', dir: 'packages/web/src/i18n', required: true, index: 'lazy' },
  { name: 'desktop', dir: 'apps/desktop/src/i18n', required: false, index: 'eager' },
  { name: 'ios', dir: 'apps/ios/CoreHub/i18n', required: false, areas: true, index: null },
];

export function readRegistry(file = registryFile) {
  const registry = JSON.parse(readFileSync(file, 'utf8'));
  return {
    languages: registry.languages ?? [],
    pseudo: registry.pseudo ?? [],
  };
}

export function flatten(value, prefix = '', out = new Map()) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const [key, child] of Object.entries(value))
      flatten(child, prefix ? `${prefix}.${key}` : key, out);
  } else {
    out.set(prefix, value);
  }
  return out;
}

/** A set's catalogue for one language, flattened; areas merged. `null` when the file is missing. */
export function readCatalogue(set, code, onError = () => {}) {
  const dir = path.join(repoRoot, set.dir);
  const file = path.join(dir, `${code}.json`);
  if (!existsSync(file)) return null;
  let catalogue;
  try {
    catalogue = flatten(JSON.parse(readFileSync(file, 'utf8')));
  } catch (error) {
    onError(`${set.dir}/${code}.json: ${error.message}`);
    return null;
  }
  if (set.areas) {
    const areaFiles = readdirSync(dir)
      .filter((name) => name.endsWith(`.${code}.json`))
      .sort();
    for (const name of areaFiles) {
      try {
        for (const [key, value] of flatten(
          JSON.parse(readFileSync(path.join(dir, name), 'utf8')),
        )) {
          if (catalogue.has(key))
            onError(`${set.name}: "${key}" is in ${name} and another ${code} file`);
          catalogue.set(key, value);
        }
      } catch (error) {
        onError(`${set.dir}/${name}: ${error.message}`);
      }
    }
  }
  return catalogue;
}

/** `zh-Hans` → `zh_Hans`: a language code as a JavaScript identifier. */
export const identifierOf = (code) => code.replace(/-/g, '_');

/** The generated index of one set: what `pnpm i18n:new` writes and `pnpm i18n:check` compares. */
export function renderIndex(set, languages) {
  const header =
    '// GENERATED from locales/languages.json by scripts/i18n/generate.mjs (ADR 0028). Do not edit:\n' +
    '// `pnpm i18n:new <code>` adds a language here and on every other platform.\n';
  const importOf = (code) =>
    `import ${identifierOf(code)} from './${code}.json' with { type: 'json' };\n`;
  const entry = (code) => (identifierOf(code) === code ? code : `'${code}': ${identifierOf(code)}`);
  if (set.index === 'eager') {
    const codes = languages.map((each) => each.code);
    return (
      header +
      codes.map(importOf).join('') +
      '\n' +
      'export const CATALOGUES: Readonly<Record<string, Record<string, unknown>>> = {\n' +
      codes.map((code) => `  ${entry(code)},\n`).join('') +
      '};\n'
    );
  }
  const bundled = languages.filter((each) => each.required).map((each) => each.code);
  const lazy = languages.filter((each) => !each.required).map((each) => each.code);
  return (
    header +
    '// Arabic and English are in the bundle; every other language is fetched when chosen.\n' +
    bundled.map(importOf).join('') +
    '\n' +
    'export const BUNDLED: Readonly<Record<string, Record<string, unknown>>> = {\n' +
    bundled.map((code) => `  ${entry(code)},\n`).join('') +
    '};\n\n' +
    'export const LOADERS: Readonly<Record<string, () => Promise<Record<string, unknown>>>> = {\n' +
    lazy
      .map(
        (code) =>
          `  '${code}': () =>\n    import('./${code}.json').then((module) => module.default as Record<string, unknown>),\n`,
      )
      .join('') +
    '};\n'
  );
}

export const indexPath = (set) => path.join(repoRoot, set.dir, 'catalogues.ts');
