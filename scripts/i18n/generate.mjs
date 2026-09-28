#!/usr/bin/env node
// `pnpm i18n:generate`: writes every platform's catalogue index (`src/i18n/catalogues.ts`)
// from locales/languages.json (ADR 0028). `--check` only reports an index that is out of date.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import * as prettier from 'prettier';
import { SETS, indexPath, readRegistry, renderIndex, repoRoot } from './registry.mjs';

export async function staleIndexes(registry = readRegistry()) {
  const stale = [];
  for (const set of SETS) {
    if (!set.index || !existsSync(path.join(repoRoot, set.dir))) continue;
    const file = indexPath(set);
    const options = (await prettier.resolveConfig(file)) ?? {};
    const want = await prettier.format(renderIndex(set, registry.languages), {
      ...options,
      filepath: file,
    });
    const have = existsSync(file) ? readFileSync(file, 'utf8') : null;
    if (have !== want) stale.push({ set, file, want });
  }
  return stale;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const check = process.argv.includes('--check');
  const stale = await staleIndexes();
  for (const { file, want } of stale) {
    const shown = path.relative(repoRoot, file);
    if (check) console.error(`i18n:generate  ${shown} is out of date — run \`pnpm i18n:generate\``);
    else {
      writeFileSync(file, want);
      console.log(`i18n:generate  wrote ${shown}`);
    }
  }
  if (check && stale.length > 0) process.exit(1);
  if (stale.length === 0) console.log('i18n:generate  every catalogue index is up to date');
}
