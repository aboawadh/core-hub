import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkImages, checkTexts, pngInfo } from './play-listing.mjs';

/** A PNG header (signature + IHDR) is all the checks read. */
function png(width, height, colorType, depth = 8) {
  const buffer = Buffer.alloc(33);
  Buffer.from('89504e470d0a1a0a', 'hex').copy(buffer, 0);
  buffer.writeUInt32BE(13, 8);
  buffer.write('IHDR', 12, 'ascii');
  buffer.writeUInt32BE(width, 16);
  buffer.writeUInt32BE(height, 20);
  buffer[24] = depth;
  buffer[25] = colorType;
  return buffer;
}

const good = {
  icon: png(512, 512, 6),
  feature: png(1024, 500, 2),
  screenshots: { '01.png': png(1320, 2346, 2), '02.png': png(1320, 2346, 2) },
};

const texts = {
  'title.txt': 'Core Hub',
  'short_description.txt': 'Chat with your agents.',
  'full_description.txt': 'A long description.',
  'changelogs/default.txt': 'First release.',
};

test('a listing within the limits passes', () => {
  assert.deepEqual(checkTexts('en-US', texts), []);
  assert.deepEqual(checkImages('en-US', good), []);
});

test('texts over Play limits, missing or with HTML are refused', () => {
  const problems = checkTexts('ar', {
    ...texts,
    'title.txt': 'ك'.repeat(31),
    'short_description.txt': 'x'.repeat(81),
    'full_description.txt': '<b>bold</b>',
    'changelogs/default.txt': 'y'.repeat(501),
  });
  assert.equal(problems.length, 4);
  assert.match(problems[0], /title\.txt: 31 characters, the limit is 30/);
  assert.match(problems[1], /short_description\.txt: 81 characters, the limit is 80/);
  assert.match(problems[2], /HTML/);
  assert.match(problems[3], /changelogs\/default\.txt: 501 characters, the limit is 500/);
  assert.deepEqual(checkTexts('ar', { ...texts, 'title.txt': '' }), ['ar/title.txt: missing']);
});

test('Arabic is counted in characters, not bytes', () => {
  // «كور هب» is 6 characters but 11 UTF-8 bytes.
  assert.deepEqual(checkTexts('ar', { ...texts, 'title.txt': 'ك'.repeat(30) }), []);
});

test('images with the wrong size, alpha or count are refused', () => {
  const problems = checkImages('en-US', {
    icon: png(512, 512, 2),
    feature: png(1024, 512, 6),
    screenshots: { '01.png': png(1320, 2868, 6) },
  });
  assert.ok(problems.some((p) => /icon\.png: not a 32-bit PNG/.test(p)));
  assert.ok(problems.some((p) => /featureGraphic\.png: 1024 × 512/.test(p)));
  assert.ok(problems.some((p) => /featureGraphic\.png: not a 24-bit/.test(p)));
  assert.ok(problems.some((p) => /1 screenshot\(s\), Play wants 2 to 8/.test(p)));
  // The iPhone 6.9" size (1320 × 2868) is taller than Play's 2:1.
  assert.ok(problems.some((p) => /over twice the short one/.test(p)));
  assert.ok(problems.some((p) => /01\.png: not a 24-bit PNG without alpha/.test(p)));
  assert.deepEqual(checkImages('ar', { ...good, icon: undefined }), ['ar/images/icon.png: missing']);
  const tooMany = Object.fromEntries(Array.from({ length: 9 }, (_, i) => [`${i}.png`, png(1080, 1920, 2)]));
  assert.ok(checkImages('ar', { ...good, screenshots: tooMany }).some((p) => /9 screenshot/.test(p)));
  const small = { '01.png': png(300, 600, 2), '02.png': png(1080, 1920, 2) };
  assert.ok(checkImages('ar', { ...good, screenshots: small }).some((p) => /320–3840/.test(p)));
});

test('pngInfo reads the header and rejects other files', () => {
  assert.deepEqual(pngInfo(png(10, 20, 6)), { width: 10, height: 20, depth: 8, colorType: 6 });
  assert.equal(pngInfo(Buffer.from('GIF89a, not a png at all.....')), null);
});
