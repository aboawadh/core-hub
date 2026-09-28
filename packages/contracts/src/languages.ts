/**
 * The UI languages and how every part of Core Hub treats them (ADR 0028).
 *
 * One registry, `locales/languages.json` at the repository root, generated into
 * `generated/ts/languages.ts` on every build of this package. The web client, the desktop
 * app, the hub and the terminal client read the list, the directions, the fallback chains and
 * the number locale from here; the phone apps read the same file at build time. It lives in
 * `contracts` for the reason `product.ts` does: every package already depends on this one.
 *
 * What a language needs to appear everywhere is an entry in the registry and a catalogue per
 * platform (`pnpm i18n:new <code>` writes both). A key a language lacks falls back along its
 * chain and finally to English, so a partial translation never shows a raw key.
 */
import { REGISTRY } from '../generated/ts/languages.js';

export type TextDirection = 'ltr' | 'rtl';

export interface LanguageInfo {
  /** BCP 47: `ar`, `en`, `fr`, `zh-Hans`, `pt-BR`. */
  readonly code: string;
  readonly englishName: string;
  /** How the language names itself — what every language picker shows. */
  readonly nativeName: string;
  readonly direction: TextDirection;
  readonly status: 'complete' | 'partial';
  /** Arabic and English only: held to strict parity by `pnpm i18n:check`. */
  readonly required: boolean;
  /** Tried in order for a missing key; English is always tried last. */
  readonly fallback: readonly string[];
  /** `latn` keeps 0-9 (DECISIONS §113); `native` is only ever the owner's decision. */
  readonly numerals: 'latn' | 'native';
}

/** A test-only locale made from a real catalogue (`pseudoize`); never offered to a person. */
export interface PseudoLocaleInfo {
  readonly code: string;
  readonly base: string;
  readonly direction: TextDirection;
  readonly style: 'accented' | 'long-rtl' | 'cjk' | 'tall' | 'tagged';
}

/** Every language a person can choose, in registry order. */
export const UI_LANGUAGES: readonly LanguageInfo[] = REGISTRY.languages as readonly LanguageInfo[];
export const UI_LANGUAGE_CODES: readonly string[] = UI_LANGUAGES.map((each) => each.code);
/** The languages every catalogue must hold in full: Arabic and English. */
export const REQUIRED_LANGUAGE_CODES: readonly string[] = UI_LANGUAGES.filter(
  (each) => each.required,
).map((each) => each.code);
export const PSEUDO_LOCALES: readonly PseudoLocaleInfo[] =
  REGISTRY.pseudo as readonly PseudoLocaleInfo[];

/** The last resort of every chain. */
export const ROOT_LANGUAGE = 'en';

const byCode = new Map(UI_LANGUAGES.map((each) => [each.code.toLowerCase(), each]));
const pseudoByCode = new Map(PSEUDO_LOCALES.map((each) => [each.code.toLowerCase(), each]));

export function languageInfo(code: string | null | undefined): LanguageInfo | undefined {
  return code ? byCode.get(code.toLowerCase()) : undefined;
}

export function pseudoLocaleInfo(code: string | null | undefined): PseudoLocaleInfo | undefined {
  return code ? pseudoByCode.get(code.toLowerCase()) : undefined;
}

/** A registered language a person may pick (pseudo-locales are not). */
export function isUiLanguage(value: unknown): value is string {
  return typeof value === 'string' && byCode.get(value.toLowerCase())?.code === value;
}

/** The registered code a pseudo-locale borrows its words from, or the code itself. */
export function baseLanguageOf(code: string): string {
  return pseudoLocaleInfo(code)?.base ?? code;
}

/**
 * The languages a key is looked up in, in order: the language, its declared fallbacks, then
 * English. A pseudo-locale comes first and then its base's chain. Unregistered codes are
 * skipped, so a chain always ends in a language every catalogue has.
 */
export function fallbackChain(code: string): string[] {
  const chain: string[] = [];
  const add = (each: string) => {
    const info = languageInfo(each) ?? pseudoLocaleInfo(each);
    if (info && !chain.includes(info.code)) chain.push(info.code);
  };
  const pseudo = pseudoLocaleInfo(code);
  if (pseudo) add(pseudo.code);
  const info = languageInfo(pseudo ? pseudo.base : code);
  if (info) {
    add(info.code);
    for (const next of info.fallback) add(next);
  }
  add(ROOT_LANGUAGE);
  return chain;
}

/** The first language of `code`'s chain found in `among` (e.g. the hub's own `ar`/`en`). */
export function nearestLanguage<T extends string>(code: string, among: readonly T[]): T {
  for (const each of fallbackChain(code)) {
    const found = among.find((candidate) => candidate.toLowerCase() === each.toLowerCase());
    if (found) return found;
  }
  return (among.find((each) => each === ROOT_LANGUAGE) ?? among[0]) as T;
}

export function directionOfLanguage(code: string): TextDirection {
  return (pseudoLocaleInfo(code) ?? languageInfo(code))?.direction ?? 'ltr';
}

/**
 * The best registered language for a BCP 47 tag from a browser, an OS or a header: the exact
 * tag, then the tag with its likely script (`zh-TW` → `zh-Hant`), then the bare language, then
 * any registered variant of that language. Undefined when the language is not registered.
 */
export function matchLanguage(
  tag: string | null | undefined,
  available: readonly string[] = UI_LANGUAGE_CODES,
): string | undefined {
  const raw = tag?.trim().replace(/_/g, '-').split('.')[0];
  if (!raw || raw === '*') return undefined;
  const find = (candidate: string | undefined) =>
    candidate
      ? available.find((each) => each.toLowerCase() === candidate.toLowerCase())
      : undefined;
  let locale: Intl.Locale | undefined;
  try {
    locale = new Intl.Locale(raw);
  } catch {
    return find(raw.split('-')[0]);
  }
  let full: Intl.Locale = locale;
  try {
    full = locale.maximize();
  } catch {
    // An engine without likely-subtags data: the tag as given still matches.
  }
  const language = locale.language;
  const script = full.script;
  const region = locale.region ?? full.region;
  const candidates = [
    locale.baseName,
    script && region ? `${language}-${script}-${region}` : undefined,
    script ? `${language}-${script}` : undefined,
    region ? `${language}-${region}` : undefined,
    language,
  ];
  for (const candidate of candidates) {
    const found = find(candidate);
    if (found) return found;
  }
  return available.find((each) => each.toLowerCase().split('-')[0] === language.toLowerCase());
}

/**
 * The best registered language for an `Accept-Language` header: the highest `q` wins and the
 * header's order breaks ties. Undefined when nothing in it is registered.
 */
export function pickFromAcceptLanguage(
  header: string | null | undefined,
  available: readonly string[] = UI_LANGUAGE_CODES,
): string | undefined {
  if (!header) return undefined;
  let best: { code: string; q: number } | undefined;
  for (const entry of header.split(',')) {
    const [tag = '', ...params] = entry.trim().split(';');
    const qParam = params.find((p) => p.trim().startsWith('q='));
    const q = qParam ? Number.parseFloat(qParam.trim().slice(2)) : 1;
    if (Number.isNaN(q) || q <= 0) continue;
    const code = matchLanguage(tag, available);
    if (code && (!best || q > best.q)) best = { code, q };
  }
  return best?.code;
}

const intlLocales = new Map<string, string>();

/**
 * The locale every number, date, time, size and percentage is formatted in. Digits stay Latin
 * (123) in every language — also Arabic — unless a language's registry entry says `native`
 * (DECISIONS §113). Never hand a bare UI language to `Intl` or `toLocale*String`.
 */
export function intlLocaleOf(code: string): string {
  const cached = intlLocales.get(code);
  if (cached) return cached;
  const base = languageInfo(baseLanguageOf(code))?.code ?? ROOT_LANGUAGE;
  let locale = base;
  // A language written in Latin script has Latin digits already; every other one is told so
  // explicitly, whatever its engine's default — one engine gives `ar` Arabic-Indic digits and
  // another does not, so the answer never depends on the engine.
  if (languageInfo(base)?.numerals !== 'native') {
    let script: string | undefined;
    try {
      script = new Intl.Locale(base).maximize().script;
    } catch {
      script = undefined;
    }
    if (script !== 'Latn') locale = `${base}-u-nu-latn`;
  }
  intlLocales.set(code, locale);
  return locale;
}

/** CLDR plural categories, in CLDR's order. A plural string is a group of these keys. */
export const PLURAL_CATEGORIES = ['zero', 'one', 'two', 'few', 'many', 'other'] as const;
export type PluralCategory = (typeof PLURAL_CATEGORIES)[number];

const pluralRules = new Map<string, Intl.PluralRules>();
function rulesOf(code: string): Intl.PluralRules {
  const base = baseLanguageOf(code);
  let rules = pluralRules.get(base);
  if (!rules) {
    try {
      rules = new Intl.PluralRules(base);
    } catch {
      rules = new Intl.PluralRules(ROOT_LANGUAGE);
    }
    pluralRules.set(base, rules);
  }
  return rules;
}

/** The CLDR category `count` takes in a language: Arabic has six, Chinese one. */
export function pluralCategory(code: string, count: number): PluralCategory {
  return rulesOf(code).select(count) as PluralCategory;
}

/** The categories a language's plural strings must give (always including `other`). */
export function pluralCategoriesOf(code: string): PluralCategory[] {
  const used = new Set(rulesOf(code).resolvedOptions().pluralCategories as string[]);
  used.add('other');
  return PLURAL_CATEGORIES.filter((each) => used.has(each));
}

export type Catalogue = { readonly [key: string]: unknown };
export type TranslationParams = Record<string, string | number | null | undefined>;

/** A dotted key's string in one catalogue; an empty string counts as not translated. */
export function lookupKey(catalogue: Catalogue | undefined, key: string): string | undefined {
  if (!catalogue) return undefined;
  let node: unknown = catalogue;
  for (const part of key.split('.')) {
    if (!node || typeof node !== 'object' || !(part in (node as Catalogue))) return undefined;
    node = (node as Catalogue)[part];
  }
  return typeof node === 'string' && node.trim() !== '' ? node : undefined;
}

export function interpolate(text: string, params: TranslationParams = {}): string {
  return text.replace(/\{([a-zA-Z0-9_]+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined || value === null ? match : String(value);
  });
}

const PLURAL_SET = new Set<string>(PLURAL_CATEGORIES);

/**
 * The template for `key` along `chain`: the first language that has it wins. A plural key
 * (`….few`) a language lacks falls to that language's own `….other` before the next language,
 * so a partial translation keeps its own words.
 */
export function resolveTemplate(
  chain: readonly string[],
  catalogueOf: (code: string) => Catalogue | undefined,
  key: string,
): string | undefined {
  const dot = key.lastIndexOf('.');
  const plural = dot > 0 && PLURAL_SET.has(key.slice(dot + 1));
  for (const code of chain) {
    const catalogue = catalogueOf(code);
    if (!catalogue) continue;
    const text =
      lookupKey(catalogue, key) ??
      (plural ? lookupKey(catalogue, `${key.slice(0, dot)}.other`) : undefined);
    if (text !== undefined) return text;
  }
  return undefined;
}

/**
 * A translate function over a platform's catalogues: the language's chain, then the key
 * itself — never thrown, never empty. Pseudo-locales transform their base's words.
 */
export function createTranslate(catalogueOf: (code: string) => Catalogue | undefined) {
  return (language: string, key: string, params?: TranslationParams): string => {
    const pseudo = pseudoLocaleInfo(language);
    const template = resolveTemplate(
      fallbackChain(pseudo ? pseudo.base : language),
      catalogueOf,
      key,
    );
    if (template === undefined) return interpolate(key, params);
    if (pseudo?.style === 'tagged') return interpolate(template, params) + keyTag(key);
    return interpolate(pseudo ? pseudoize(template, pseudo.style) : template, params);
  };
}

// ---- the key tag (tests only) ------------------------------------------------------------
// `en-XK` draws English with each string's key appended in characters that take no room and
// allow no line break, so a test can tell exactly which key a label shows without changing a
// pixel of the layout (the space measurement, ADR 0028).
const TAG_START = '\u2064';
const TAG_DIGITS = ['\u200C', '\u200D', '\u2060', '\uFEFF'];
const TAG = /\u2064((?:\u200C|\u200D|\u2060|\uFEFF)+)/g;

export function keyTag(key: string): string {
  let out = TAG_START;
  for (const byte of new TextEncoder().encode(key))
    for (let shift = 6; shift >= 0; shift -= 2) out += TAG_DIGITS[(byte >> shift) & 3];
  return out;
}

/** The keys tagged in a text, in order, and the text without its tags. */
export function readKeyTags(text: string): { keys: string[]; text: string } {
  const keys: string[] = [];
  for (const match of text.matchAll(TAG)) {
    const digits = [...match[1]!].map((ch) => TAG_DIGITS.indexOf(ch));
    const bytes = new Uint8Array(Math.floor(digits.length / 4));
    for (let i = 0; i < bytes.length; i += 1)
      bytes[i] =
        (digits[i * 4]! << 6) |
        (digits[i * 4 + 1]! << 4) |
        (digits[i * 4 + 2]! << 2) |
        digits[i * 4 + 3]!;
    keys.push(new TextDecoder().decode(bytes));
  }
  return { keys, text: text.replace(TAG, '') };
}

// ---- pseudo-locales (tests only) ---------------------------------------------------------

// prettier-ignore
const ACCENTED: Record<string, string> = {
  a: 'á', b: 'ƀ', c: 'ç', d: 'ð', e: 'é', f: 'ƒ', g: 'ĝ', h: 'ĥ', i: 'î', j: 'ĵ', k: 'ķ',
  l: 'ļ', m: 'ɱ', n: 'ñ', o: 'ö', p: 'þ', q: 'ǫ', r: 'ŕ', s: 'š', t: 'ţ', u: 'û', v: 'ṽ',
  w: 'ŵ', x: 'ẋ', y: 'ý', z: 'ž', A: 'Å', B: 'Ɓ', C: 'Ç', D: 'Ð', E: 'É', F: 'Ƒ', G: 'Ĝ',
  H: 'Ĥ', I: 'Î', J: 'Ĵ', K: 'Ķ', L: 'Ļ', M: 'Ṁ', N: 'Ñ', O: 'Ö', P: 'Þ', Q: 'Ǫ', R: 'Ŕ',
  S: 'Š', T: 'Ţ', U: 'Û', V: 'Ṽ', W: 'Ŵ', X: 'Ẋ', Y: 'Ý', Z: 'Ž',
};
const VOWELS = new Set('aeiouAEIOU');
// Letters that join the next letter in Arabic script, so a tatweel after them stretches the word.
const ARABIC_JOINS_NEXT =
  /[\u0628\u062A-\u062B\u062C-\u062E\u0633-\u063A\u0641-\u0647\u064A\u0626\u0644]/;
const HAN =
  '设置聊天任务模型代理文件记忆频道工具技能搜索新建删除保存取消确认打开关闭显示隐藏更多帮助用户账户通知隐私更新插件日志用量性能主题语言';
const THAI_CONSONANTS = 'กขคฆงจฉชซญฎฏฐฑฒณดตถทธนบปผฝพฟภมยรลวศษสหฬอฮ';
const THAI_ABOVE = ['\u0E34\u0E48', '\u0E35\u0E49', '\u0E36\u0E4A', '\u0E37\u0E4B', '\u0E31\u0E49'];
const THAI_BELOW = ['\u0E38', '\u0E39'];

function transformText(text: string, style: PseudoLocaleInfo['style']): string {
  switch (style) {
    case 'tagged':
      return text;
    case 'accented': {
      // Every vowel doubled lengthens each word itself, not only the sentence.
      let out = '';
      for (const ch of text) out += VOWELS.has(ch) ? ACCENTED[ch]!.repeat(2) : (ACCENTED[ch] ?? ch);
      return out;
    }
    case 'long-rtl': {
      let out = '';
      let index = 0;
      for (const ch of text) {
        out += ch;
        if (ARABIC_JOINS_NEXT.test(ch) && index % 2 === 0) out += '\u0640\u0640';
        index += 1;
      }
      return out;
    }
    case 'cjk': {
      // About 0.6 ideographs per Latin letter, each a full em wide, and no spaces to break at.
      let out = '';
      let seed = 0;
      for (const word of text.split(/(\s+)/)) {
        if (/^\s+$/.test(word)) continue;
        const letters = [...word].filter((ch) => /[A-Za-z]/.test(ch)).length;
        const rest = [...word].filter((ch) => !/[A-Za-z]/.test(ch)).join('');
        const count = letters === 0 ? 0 : Math.max(1, Math.ceil(letters * 0.6));
        for (let i = 0; i < count; i += 1) out += HAN[(seed + i * 7) % HAN.length];
        seed += count + 3;
        out += rest.replace(
          /[:?!,.]/g,
          (p) => ({ ':': '：', '?': '？', '!': '！', ',': '，', '.': '。' })[p]!,
        );
      }
      return out;
    }
    case 'tall': {
      let out = '';
      let index = 0;
      for (const ch of text) {
        if (/[A-Za-z]/.test(ch)) {
          out += THAI_CONSONANTS[ch.charCodeAt(0) % THAI_CONSONANTS.length];
          if (index % 2 === 0) out += THAI_ABOVE[index % THAI_ABOVE.length];
          else if (index % 3 === 0) out += THAI_BELOW[index % THAI_BELOW.length];
          index += 1;
        } else out += ch;
      }
      return out;
    }
  }
}

/**
 * A catalogue string in a pseudo-locale: the words transformed, `{placeholders}` untouched, and
 * the whole in brackets so a string cut short, or one not coming from a catalogue, shows.
 */
export function pseudoize(template: string, style: PseudoLocaleInfo['style']): string {
  const parts = template.split(/(\{[a-zA-Z0-9_]+\})/);
  const body = parts
    .map((part) => (/^\{[a-zA-Z0-9_]+\}$/.test(part) ? part : transformText(part, style)))
    .join('');
  if (style === 'tagged') return body;
  if (style === 'long-rtl') return `«${body}»`;
  if (style !== 'accented') return `[${body}]`;
  // At least 40% longer than the English, as German or Finnish often is: doubled vowels, then
  // a filler word for what they did not add.
  const words = template.replace(/\{[a-zA-Z0-9_]+\}/g, '');
  const short = Math.ceil(words.length * 1.4) - body.replace(/\{[a-zA-Z0-9_]+\}/g, '').length;
  return `[${body}${short > 0 ? ` ${'ẋ'.repeat(Math.max(short - 1, 3))}` : ''}]`;
}
