#!/usr/bin/env node
// Checks the Google Play listing in apps/android/fastlane/metadata/android (fastlane `supply`
// layout) against Play Console's limits before anything is uploaded (docs/store/google/README.md):
//
//   node apps/android/scripts/play-listing.mjs              # check; exit 1 and list every problem
//   node apps/android/scripts/play-listing.mjs --summary    # also print each text's length
//   node apps/android/scripts/play-listing.mjs --take-shots # copy the screenshots the Android
//                                                           # PlayStoreShots test made, then check
//
// Texts are counted in characters (Unicode code points), as Play Console counts them, and must be
// plain text. Images are read from their PNG header: the icon a 512 × 512 32-bit PNG, the feature
// graphic 1024 × 500 and the phone screenshots 2–8 PNGs with no alpha channel, each side
// 320–3840 px and the long side at most twice the short one.
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const metadataDir = path.resolve(here, '..', 'fastlane', 'metadata', 'android');
const shotsBuildDir = path.resolve(here, '..', 'app', 'build', 'play-shots');

/** Play's language codes (the folder names supply uploads). */
export const LOCALES = ['en-US', 'ar'];

/** Per-language texts: [file, max characters, required]. */
export const TEXTS = [
  ['title.txt', 30, true],
  ['short_description.txt', 80, true],
  ['full_description.txt', 4000, true],
  ['changelogs/default.txt', 500, true],
];

export const characters = (text) => [...text].length;

/** Every problem with one language's texts, given as `{ file: text }`. */
export function checkTexts(locale, texts) {
  const problems = [];
  for (const [file, max, required] of TEXTS) {
    const text = texts[file];
    if (text === undefined || text === '') {
      if (required) problems.push(`${locale}/${file}: missing`);
      continue;
    }
    const length = characters(text);
    if (length > max) problems.push(`${locale}/${file}: ${length} characters, the limit is ${max}`);
    if (/<\/?[a-z][^>]*>/i.test(text)) problems.push(`${locale}/${file}: HTML tags; Play wants plain text`);
  }
  return problems;
}

/** Width, height, bit depth and colour type from a PNG's IHDR, or null when it is not a PNG. */
export function pngInfo(buffer) {
  const signature = '89504e470d0a1a0a';
  if (buffer.length < 26 || buffer.subarray(0, 8).toString('hex') !== signature) return null;
  if (buffer.subarray(12, 16).toString('ascii') !== 'IHDR') return null;
  return {
    width: buffer.readUInt32BE(16),
    height: buffer.readUInt32BE(20),
    depth: buffer[24],
    colorType: buffer[25],
  };
}

// PNG colour types: 2 = RGB (24-bit at depth 8), 6 = RGBA (32-bit at depth 8).
const RGB = 2;
const RGBA = 6;

/** Every problem with one language's images, given as `{ name: buffer }` and screenshot buffers. */
export function checkImages(locale, { icon, feature, screenshots }) {
  const problems = [];
  const where = (name) => `${locale}/images/${name}`;
  const exact = (name, buffer, width, height, types, what) => {
    if (!buffer) return problems.push(`${where(name)}: missing`);
    const info = pngInfo(buffer);
    if (!info) return problems.push(`${where(name)}: not a PNG`);
    if (info.width !== width || info.height !== height) {
      problems.push(`${where(name)}: ${info.width} × ${info.height}, Play wants ${width} × ${height}`);
    }
    if (info.depth !== 8 || !types.includes(info.colorType)) {
      problems.push(`${where(name)}: not a ${what} PNG (depth ${info.depth}, colour type ${info.colorType})`);
    }
  };
  exact('icon.png', icon, 512, 512, [RGBA], '32-bit');
  exact('featureGraphic.png', feature, 1024, 500, [RGB], '24-bit (no alpha)');
  const shots = Object.entries(screenshots ?? {});
  if (shots.length < 2 || shots.length > 8) {
    problems.push(`${where('phoneScreenshots')}: ${shots.length} screenshot(s), Play wants 2 to 8`);
  }
  for (const [name, buffer] of shots) {
    const file = `phoneScreenshots/${name}`;
    const info = pngInfo(buffer);
    if (!info) {
      problems.push(`${where(file)}: not a PNG`);
      continue;
    }
    const short = Math.min(info.width, info.height);
    const long = Math.max(info.width, info.height);
    if (short < 320 || long > 3840) {
      problems.push(`${where(file)}: ${info.width} × ${info.height}, each side must be 320–3840 px`);
    }
    if (long > 2 * short) {
      problems.push(`${where(file)}: ${info.width} × ${info.height}, the long side is over twice the short one`);
    }
    if (info.depth !== 8 || info.colorType !== RGB) {
      problems.push(`${where(file)}: not a 24-bit PNG without alpha (colour type ${info.colorType})`);
    }
  }
  return problems;
}

function readText(file) {
  return existsSync(file) ? readFileSync(file, 'utf8').trim() : undefined;
}

function readBuffer(file) {
  return existsSync(file) ? readFileSync(file) : undefined;
}

function loadLocale(locale) {
  const dir = path.join(metadataDir, locale);
  const texts = Object.fromEntries(TEXTS.map(([file]) => [file, readText(path.join(dir, file))]));
  const shotsDir = path.join(dir, 'images', 'phoneScreenshots');
  const screenshots = existsSync(shotsDir)
    ? Object.fromEntries(
        readdirSync(shotsDir)
          .filter((name) => !name.startsWith('.'))
          .sort()
          .map((name) => [name, readFileSync(path.join(shotsDir, name))]),
      )
    : {};
  return {
    texts,
    images: {
      icon: readBuffer(path.join(dir, 'images', 'icon.png')),
      feature: readBuffer(path.join(dir, 'images', 'featureGraphic.png')),
      screenshots,
    },
  };
}

/** Replaces each language's phoneScreenshots with what the PlayStoreShots test wrote. */
function takeShots() {
  for (const locale of LOCALES) {
    const from = path.join(shotsBuildDir, locale);
    if (!existsSync(from)) {
      console.error(
        `play-listing  no screenshots in ${path.relative(process.cwd(), from)}: run ` +
          "`./gradlew testDebugUnitTest --tests 'hub.core.android.shots.PlayStoreShots'` in apps/android first",
      );
      process.exit(1);
    }
    const to = path.join(metadataDir, locale, 'images', 'phoneScreenshots');
    rmSync(to, { recursive: true, force: true });
    mkdirSync(to, { recursive: true });
    for (const name of readdirSync(from).filter((n) => n.endsWith('.png')).sort()) {
      copyFileSync(path.join(from, name), path.join(to, name));
    }
  }
}

function main() {
  if (process.argv.includes('--take-shots')) takeShots();
  const summary = process.argv.includes('--summary');
  const problems = [];
  for (const locale of LOCALES) {
    const { texts, images } = loadLocale(locale);
    problems.push(...checkTexts(locale, texts), ...checkImages(locale, images));
    if (summary) {
      for (const [file, max] of TEXTS) console.log(`${locale}/${file}: ${characters(texts[file] ?? '')}/${max}`);
      for (const [name, buffer] of Object.entries(images.screenshots)) {
        const info = pngInfo(buffer);
        console.log(`${locale}/images/phoneScreenshots/${name}: ${info?.width} × ${info?.height}`);
      }
    }
  }
  if (problems.length > 0) {
    console.error(`play-listing  ${problems.length} problem(s):`);
    for (const problem of problems) console.error(`  ${problem}`);
    process.exit(1);
  }
  console.log(`play-listing  OK — ${LOCALES.join(', ')} are within Google Play's limits.`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
