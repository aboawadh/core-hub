#!/usr/bin/env node
// `pnpm i18n:limits`: every translation against the room its label has (ADR 0028).
//
// locales/limits.json holds, for each web key shown in a single-line label (a sidebar row, a
// tab, a segment, a button, a badge, a card or page title), the widest text that label shows
// before its ellipsis — measured in the running client, not guessed (`--measure`, below) — and
// the font size and weight it is drawn in. This check shapes each language's string with
// HarfBuzz in the Noto fonts of its script (Latin, Arabic, Hebrew, Thai, Devanagari, CJK) and
// fails when a translation is wider than its room and wider than English there: English is the
// yardstick the room was measured with, so a translation may be as long as English, never
// longer than both. Arabic and English are reported, not failed: a label they overrun is cut
// with an ellipsis today and the owner decides whether to change the wording or the layout.
//
//   pnpm i18n:limits                  check every language (CI installs the Noto fonts)
//   pnpm i18n:limits --measure FILE   rebuild locales/limits.json and locales/limits.md from a
//                                      measurement (e2e/zzzzzzzzzzzzzzzzzz-measure-space.spec.ts)
//
// Without the fonts (a contributor's laptop) widths are estimated from per-script character
// widths and the result says so; CI never estimates.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import * as prettier from 'prettier';
import { SETS, flatten, limitsFile, readRegistry, repoRoot } from './registry.mjs';

const WEB = SETS.find((set) => set.name === 'web');
const limitsDoc = path.join(repoRoot, 'locales', 'limits.md');
const VIEWPORT = { desktop: 1280, phone: 390 };
/** Stands in for a `{placeholder}` when a string is measured: a short name or a number. */
const SAMPLE = 'Nnnnnn';

const catalogueOf = (code) => {
  const file = path.join(repoRoot, WEB.dir, `${code}.json`);
  return existsSync(file) ? flatten(JSON.parse(readFileSync(file, 'utf8'))) : null;
};

// ---- --measure: samples from the running client → locales/limits.json ----------------------
async function measure(file) {
  const samples = JSON.parse(readFileSync(file, 'utf8'));
  const en = catalogueOf('en');
  const exact = new Map();
  const templates = [];
  for (const [key, value] of en) {
    if (typeof value !== 'string') continue;
    if (/\{[a-zA-Z0-9_]+\}/.test(value)) {
      const pattern = value
        .split(/\{[a-zA-Z0-9_]+\}/)
        .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
        .join('.+?');
      templates.push({ key, pattern: new RegExp(`^${pattern}$`) });
    } else {
      if (!exact.has(value)) exact.set(value, []);
      exact.get(value).push(key);
    }
  }
  const limits = {};
  let unmatched = 0;
  for (const sample of samples) {
    // A label that took the whole long text grows with its words: it has no fixed room.
    if (sample.width <= 0 || sample.width >= (VIEWPORT[sample.size] ?? 1280)) continue;
    // `en-XK` names the key exactly; an untagged label (older measurement) is matched by text.
    const keys =
      sample.key && en.has(sample.key)
        ? [sample.key]
        : sample.key === null && samples.some((each) => each.key)
          ? []
          : (exact.get(sample.text) ??
            templates.filter((t) => t.pattern.test(sample.text)).map((t) => t.key));
    if (keys.length === 0) {
      unmatched += 1;
      continue;
    }
    for (const key of keys) {
      const seen = limits[key];
      const where = `${sample.screen} (${sample.size})`;
      if (!seen || sample.width < seen.maxWidth)
        limits[key] = {
          area: sample.area,
          maxWidth: sample.width,
          fontSize: sample.fontSize,
          fontWeight: sample.fontWeight,
          maxLines: 1,
          where,
        };
    }
  }
  const sorted = Object.fromEntries(Object.entries(limits).sort(([a], [b]) => a.localeCompare(b)));
  const doc = {
    $comment:
      'The room each single-line web label has, measured in the running client (ADR 0028): the widest text it shows before its ellipsis, in CSS pixels, at its font size and weight. Written by `pnpm i18n:limits --measure`; checked by `pnpm i18n:limits`. Translator-facing copy: locales/limits.md.',
    measured: {
      screens: [...new Set(samples.map((s) => s.screen))].length,
      widths: VIEWPORT,
      language: 'en (as en-XK, each label matched to its key)',
    },
    web: sorted,
  };
  await writeFormatted(limitsFile, JSON.stringify(doc, null, 2));
  await writeFormatted(limitsDoc, renderDoc(sorted, en));
  console.log(
    `i18n:limits  measured ${Object.keys(sorted).length} keys from ${samples.length} labels (${unmatched} labels were not catalogue text: names, numbers, content)`,
  );
}

function renderDoc(limits, en) {
  const byArea = new Map();
  for (const [key, limit] of Object.entries(limits)) {
    if (!byArea.has(limit.area)) byArea.set(limit.area, []);
    byArea.get(limit.area).push([key, limit]);
  }
  const AREA_NAMES = {
    'ch-sidebar-row-label': 'Sidebar and Settings rows',
    'ch-segment-label': 'Segmented controls (tabs, filters, choices)',
    'ch-badge-label': 'Badges and status chips',
    'ch-btn-label': 'Buttons',
    'ch-card-title': 'Card titles',
    'ch-card-subtitle': 'Card subtitles',
    h1: 'Page titles (top bar)',
  };
  let out =
    '# How much room each label has\n\n' +
    'Generated by `pnpm i18n:limits --measure` from the running web client (ADR 0028). Do not edit.\n\n' +
    'Each row is a web key shown in a label that holds **one line** and ends in an ellipsis (…) ' +
    'when its words do not fit. **Room** is the widest text the label shows, in CSS pixels, at ' +
    'the font size and weight given; ≈ Latin letters is that room in average Latin letters, a ' +
    'hint only. A translation may be as wide as the English, and as wide as the room; ' +
    '`pnpm i18n:limits` measures yours with real fonts and names every string that is wider ' +
    'than both. Shorter words, or a common abbreviation, are better than a cut label.\n\n' +
    'Keys that are not listed have no fixed room: they wrap onto more lines or the layout ' +
    'grows with them, and the pseudo-locale journeys check those screens.\n';
  for (const [area, rows] of [...byArea].sort(([a], [b]) => a.localeCompare(b))) {
    out += `\n## ${AREA_NAMES[area] ?? `\`${area}\``}\n\n| Key | English | Room | ≈ Latin letters | Font |\n| --- | --- | --: | --: | --- |\n`;
    for (const [key, limit] of rows) {
      const english = String(en.get(key) ?? '').replace(/\|/g, '\\|');
      const letters = Math.floor(limit.maxWidth / (limit.fontSize * 0.55));
      out += `| \`${key}\` | ${english} | ${limit.maxWidth}px | ${letters} | ${limit.fontSize}px/${limit.fontWeight} |\n`;
    }
  }
  return out;
}

async function writeFormatted(file, text) {
  const options = (await prettier.resolveConfig(file)) ?? {};
  writeFileSync(file, await prettier.format(text, { ...options, filepath: file }));
  console.log(`i18n:limits  wrote ${path.relative(repoRoot, file)}`);
}

// ---- fonts and shaping -----------------------------------------------------------------------
const FONT_DIRS = [
  process.env.COREHUB_FONTS_DIR,
  '/usr/share/fonts',
  '/usr/local/share/fonts',
  path.join(homedir(), '.local/share/fonts'),
  path.join(homedir(), '.fonts'),
  '/Library/Fonts',
  path.join(homedir(), 'Library/Fonts'),
  'C:\\Windows\\Fonts',
].filter(Boolean);

/** The fonts tried for each character, in order; a CJK collection's Simplified Chinese face. */
const FACES = [
  { name: 'Noto Sans', regular: ['NotoSans-Regular.ttf'], bold: ['NotoSans-Bold.ttf'] },
  {
    name: 'Noto Sans Arabic',
    regular: ['NotoSansArabic-Regular.ttf'],
    bold: ['NotoSansArabic-Bold.ttf'],
  },
  {
    name: 'Noto Sans Hebrew',
    regular: ['NotoSansHebrew-Regular.ttf'],
    bold: ['NotoSansHebrew-Bold.ttf'],
  },
  {
    name: 'Noto Sans Thai',
    regular: ['NotoSansThai-Regular.ttf'],
    bold: ['NotoSansThai-Bold.ttf'],
  },
  {
    name: 'Noto Sans Devanagari',
    regular: ['NotoSansDevanagari-Regular.ttf'],
    bold: ['NotoSansDevanagari-Bold.ttf'],
  },
  {
    name: 'Noto Sans CJK',
    regular: ['NotoSansCJK-Regular.ttc', 'NotoSansSC-Regular.otf', 'NotoSansSC-Regular.ttf'],
    bold: ['NotoSansCJK-Bold.ttc', 'NotoSansSC-Bold.otf', 'NotoSansSC-Bold.ttf'],
    index: 2,
  },
];

function findFile(names) {
  const wanted = new Set(names);
  const visit = (dir, depth) => {
    if (depth > 4 || !existsSync(dir)) return null;
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return null;
    }
    for (const entry of entries)
      if (entry.isFile() && wanted.has(entry.name)) return path.join(dir, entry.name);
    for (const entry of entries)
      if (entry.isDirectory()) {
        const found = visit(path.join(dir, entry.name), depth + 1);
        if (found) return found;
      }
    return null;
  };
  for (const dir of FONT_DIRS) {
    const found = visit(dir, 0);
    if (found) return found;
  }
  return null;
}

async function loadShaper() {
  const hb = await import('harfbuzzjs');
  const faces = { regular: [], bold: [] };
  const missing = [];
  for (const spec of FACES) {
    for (const weight of ['regular', 'bold']) {
      const file = findFile(spec[weight]);
      if (!file) {
        missing.push(`${spec.name} ${weight}`);
        continue;
      }
      const blob = new hb.Blob(readFileSync(file));
      const index = file.endsWith('.ttc') ? (spec.index ?? 0) : 0;
      const face = new hb.Face(blob, index);
      faces[weight].push({
        name: spec.name,
        font: new hb.Font(face),
        upem: face.upem,
        covers: new Set(face.collectUnicodes()),
      });
    }
  }
  if (missing.length > 0) return { missing };
  const width = (text, size, weight) => {
    const list = weight >= 600 ? faces.bold : faces.regular;
    let total = 0;
    let run = '';
    let runFace = null;
    const flush = () => {
      if (!run || !runFace) return;
      const buffer = new hb.Buffer();
      buffer.addText(run);
      buffer.guessSegmentProperties();
      hb.shape(runFace.font, buffer);
      total +=
        (buffer.getGlyphPositions().reduce((sum, p) => sum + p.xAdvance, 0) / runFace.upem) * size;
      run = '';
    };
    for (const ch of text) {
      const code = ch.codePointAt(0);
      // Bidi and joiner marks take no room; a combining mark stays with its letter's run.
      if (/[\u200B-\u200F\u202A-\u202E\u2066-\u2069]/.test(ch)) continue;
      const face =
        (/\p{M}/u.test(ch) && runFace?.covers.has(code) ? runFace : null) ??
        list.find((each) => each.covers.has(code)) ??
        null;
      if (!face) {
        flush();
        total += size; // not in any font: a box one em wide
        continue;
      }
      if (face !== runFace) flush();
      runFace = face;
      run += ch;
    }
    flush();
    return total;
  };
  return { width, estimated: false };
}

/** Without fonts: an upper estimate per script, in em. A hint, never a CI result. */
function estimate(text, size, weight) {
  let em = 0;
  for (const ch of text) {
    if (/\p{M}|[\u200B-\u200F\u2066-\u2069]/u.test(ch)) continue;
    if (/\s/.test(ch)) em += 0.27;
    else if (/[\u1100-\u11FF\u2E80-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF\uFF00-\uFF60]/.test(ch))
      em += 1;
    else if (/[\u0600-\u06FF\u0750-\u077F]/.test(ch)) em += 0.5;
    else if (/[A-Z]/.test(ch)) em += 0.68;
    else em += 0.58;
  }
  return em * size * (weight >= 600 ? 1.06 : 1);
}

// ---- the check ---------------------------------------------------------------------------------
async function check() {
  if (!existsSync(limitsFile)) {
    console.log('i18n:limits  locales/limits.json is missing — nothing to check');
    return;
  }
  const limits = JSON.parse(readFileSync(limitsFile, 'utf8')).web ?? {};
  const shaper = await loadShaper();
  let width;
  if (shaper.missing) {
    if (process.env.CI) {
      console.error(
        `i18n:limits  missing fonts: ${shaper.missing.join(', ')} — install fonts-noto-core and fonts-noto-cjk`,
      );
      process.exit(1);
    }
    console.warn(
      `i18n:limits  missing fonts (${shaper.missing.join(', ')}): widths are ESTIMATED from character counts.\n` +
        '             Install Noto Sans (+ Arabic, Hebrew, Thai, Devanagari, CJK) or set COREHUB_FONTS_DIR for measured widths.',
    );
    width = estimate;
  } else width = shaper.width;

  const registry = readRegistry();
  const en = catalogueOf('en');
  const measureText = (text) => String(text).replace(/\{[a-zA-Z0-9_]+\}/g, SAMPLE);
  let failures = 0;
  for (const language of registry.languages) {
    const catalogue = catalogueOf(language.code);
    if (!catalogue) continue;
    const over = [];
    for (const [key, limit] of Object.entries(limits)) {
      const text = catalogue.get(key);
      if (typeof text !== 'string' || text.trim() === '') continue;
      const english = en.get(key);
      const ours = width(measureText(text), limit.fontSize, limit.fontWeight);
      const yardstick = Math.max(
        limit.maxWidth,
        typeof english === 'string'
          ? width(measureText(english), limit.fontSize, limit.fontWeight)
          : 0,
      );
      if (ours > limit.maxWidth + 0.5)
        over.push({ key, ours, limit, fails: !language.required && ours > yardstick + 0.5 });
    }
    const failing = over.filter((each) => each.fails);
    failures += failing.length;
    for (const each of over) {
      const line = `${language.code} "${each.key}" is ${Math.ceil(each.ours)}px in a ${each.limit.maxWidth}px ${each.limit.area} (${each.limit.where})`;
      if (each.fails) console.error(`  error  ${line} — shorten it (locales/limits.md)`);
      else if (process.argv.includes('--verbose'))
        console.log(`  info   ${line}: cut with an ellipsis today`);
    }
    const cut = over.length - failing.length;
    console.log(
      `i18n:limits  ${language.code}: ${Object.keys(limits).length} measured labels, ${failing.length} too wide${cut ? `, ${cut} cut with an ellipsis as in English` : ''}${width === estimate ? ' (estimated)' : ''}`,
    );
  }
  if (failures > 0) {
    console.error(`i18n:limits  FAILED: ${failures} string(s) wider than their label`);
    process.exit(1);
  }
  console.log('i18n:limits  OK');
}

const at = process.argv.indexOf('--measure');
if (at > 0) {
  const file = process.argv[at + 1];
  if (!file) {
    console.error('i18n:limits  --measure needs the measurement file');
    process.exit(1);
  }
  await measure(file);
} else await check();
