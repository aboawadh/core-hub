# ADR 0028 — One language registry, fallback to English, and space rules that are measured

Status: **Proposed** (2026-09-28), for the owner to confirm. Phase 1 (web, desktop, hub, CLI) is
`feat/i18n-languages-foundation`; phase 2 (Android, iOS) is `feat/i18n-languages-apps`.

## Context
The owner's goal (2026-09-28): outside developers will add languages — Chinese, French, … — and
adding one must be professional and safe. Fill the translation files and the language appears
everywhere (web, desktop, the hub's messages, the CLI, iOS, Android), and the interface does not
break with longer, wider or taller text: about 95% confidence nothing breaks. He had just seen a
label on the iPhone task board whose last letter dropped to a second line (PR #210).

Until now Arabic and English were written into the code: the web client's `Language = 'ar' | 'en'`,
the i18n check's `LANGUAGES`, toggles that flipped `ar ⇄ en`, `language === 'ar'` for the reading
direction, and every package with its own copy of the lookup. A third language would have meant
editing dozens of files, and nothing measured whether its words fit.

Standing rules this keeps: Latin digits everywhere (DECISIONS §113); the content-direction
contract (UI language ≠ content direction); the owner's "global languages" wish (every popular
language, never Arabic and English only — `feedback-global-languages`); no breaking changes (ADR
0027) — people running Core Hub today see exactly the same Arabic and English interface.

## Decision

### 1. One registry: `locales/languages.json`
Each language: BCP 47 `code`, `englishName`, `nativeName` (what every picker shows), `direction`,
`status` (`complete` | `partial`), `required` (Arabic and English only), `fallback` (languages
tried before English, e.g. `zh-Hant → zh-Hans`), and `numerals`. `languages.schema.json` describes
it; `pnpm i18n:check` validates it (unique valid tags, `ar`/`en` required and complete, fallbacks
registered, no loops). It sits at the repository root for translators and the phone builds; every
TypeScript package reads it through `@corehub/contracts` (`src/languages.ts`, generated into
`generated/ts/languages.ts` on each build — the reason `product.ts` lives there applies: every
package already depends on it). The Docker build copies it.

Each platform keeps its own catalogue per language (`packages/server|cli|web/src/i18n`,
`apps/desktop/src/i18n`; the phones in phase 2), and a generated `catalogues.ts` imports them.
The web bundles Arabic and English and fetches any other language when it is chosen (a 47 kB,
13 kB gzipped chunk per language today), so a new language costs people who do not use it
nothing.

### 2. Fallback, never a raw key
A key is looked up along the language's chain — itself, its `fallback`s, English — and only then is
the key itself shown. An empty string counts as untranslated. A plural form a language lacks
(`….few`) falls to that language's own `….other` before the next language, so a partial
translation keeps its own words. `pnpm i18n:check` keeps Arabic and English strict exactly as
before (same keys, same placeholders, no empty string) and, for every other language, reports its
coverage per platform and fails only on what would break the UI: a placeholder English does not
have or one it drops, a changed `` `code` `` span or line count, a value that is not a string, a
plural group without `other`, digits other than 0-9, or `status: complete` while keys are missing.

### 3. Plurals and formatting
Plural strings stay what they already are: CLDR category keys (`zero one two few many other`)
chosen with `Intl.PluralRules` — Arabic uses six, English two, Chinese one. Not ICU MessageFormat:
the keys already work in every client, need no parser in the bundle, map one to one onto Android's
`<plurals>` and iOS's `.stringsdict` in phase 2, and a translator fills plain strings. Numbers and
dates use `intlLocaleOf(code)`: every language not written in Latin script gets `-u-nu-latn`, so
digits stay 0-9 (§113). `numerals: native` exists in the registry as a **proposal** a language's
translators may make — the owner decides; nothing uses it today.

### 4. Space rules, measured
`locales/limits.json` holds, for each web key shown in a label that holds one line and ends in an
ellipsis (a sidebar row, a segment, a badge, a card or page title), the widest text that label
shows, at its font size and weight. It is **measured in the running client**, not guessed: an
opt-in Playwright spec walks the main screens at desktop and phone width in `en-XK` — English with
each string's key appended in zero-width characters, so a label is matched to its exact key
without moving a pixel — and, for each ellipsis label, puts in a far too long text for a moment and
reads how wide the label becomes. `pnpm i18n:limits --measure` writes `limits.json` and the
translators' table `locales/limits.md`.

`pnpm i18n:limits` shapes every language's string with HarfBuzz (`harfbuzzjs`, WebAssembly, a
dev dependency) in the Noto fonts of its script — Latin, Arabic, Hebrew, Thai, Devanagari, CJK —
and fails when a translation is wider than its room **and** wider than English there (English is
the yardstick the room was measured with). Arabic and English are reported, not failed: what they
overrun is cut with an ellipsis today. CI installs the fonts in its own job; on a laptop without
them the result is an estimate from per-script character widths and says so.

### 5. The web and desktop UI itself
Every single-line label in the kit already ends in an ellipsis; badges now also shrink
(`min-inline-size: 0`), a profile card's title row lets its name and slug shrink, and a label cut
short shows its full words as a `title` while, and only while, it is cut (one document listener,
`src/i18n/truncation.ts`). `text-wrap: pretty` keeps a wrapped label from leaving one letter on its
last line. `:lang()` font stacks name each CJK language's own fonts and Thai, Devanagari and
Hebrew fonts, and scripts whose marks stack (Thai, Lao, Khmer, Burmese, Vietnamese, Devanagari)
get taller lines — including Tailwind's `text-*` line heights. **No font file is bundled**: the
stacks name fonts every system already has, so the image and installers do not grow.

### 6. Pseudo-locales, tests only
`en-XA` (accented, at least 40% longer, bracketed), `ar-XB` (Arabic stretched with tatweel,
right-to-left), `zh-XC` (full-width ideographs, no spaces), `th-XD` (Thai with stacked tone marks)
and `en-XK` (key tags, for the measurement). They are registry entries of their own, never listed
in a picker, and switched on only through browser storage (`corehub.pseudo-locale`). A Playwright
journey walks every main screen in each of the four at desktop and phone width and fails on a
single-line label that spills without an ellipsis, text cut without one (sideways, or glyph ink cut
at the top or bottom — measured with canvas `measureText`, not the font's em box), a lone letter
on a wrapped short label's last line, controls drawn over each other, and a page that scrolls
sideways; the audit proves itself on a page broken on purpose first. Every screenshot is a CI
artifact.

### 7. Contributors
CONTRIBUTING's "Add your language" and `docs/guides/add-a-language.md`: `pnpm i18n:new <code>`
writes the registry entry (names and direction from `Intl`) and every platform's catalogue with the
English keys and empty strings, then fill, `pnpm i18n:check`, `pnpm i18n:limits`, and a pull
request from the "New language" issue form. The owner marks a language `complete`.

### What stays Arabic and English
The contract's `Locale` (`ar` | `en`: a person's stored locale, push registrations, `meta.locales`)
and `Accept-Language`'s documented enum do not change: widening a closed enum would break the
generated clients of the phones people have (ADR 0027). The hub reads any registered code from
`Accept-Language` for its error messages (`request.uiLanguage`), stores the nearest of Arabic and
English as the person's `locale`, and writes its own inline sentences (notices, titles) in that
nearest one. The web keeps the chosen language in the browser, as it always kept the display
language. A persisted per-person UI language on the hub is a later, additive field.

## Consequences
- Adding a language is data plus translation: no code change on web, desktop, hub or CLI.
- With two languages every control looks exactly as before; a third turns the switch into a menu
  and Display's segmented control into a list.
- `limits.json` must be re-measured when a screen's layout changes a label's room (the command is
  in its header); a key not measured has no fixed room and is covered by the pseudo-locale journeys.
- The measured widths use Noto, among the widest common UI fonts; Segoe UI and SF are narrower, so
  a string that passes has room to spare there. A language's own system font may differ.

## Rejected
- ICU MessageFormat (a parser in every client, harder for translators, no gain over CLDR keys).
- Character-count limits (a Chinese character is twice as wide as a Latin one; Arabic joins) —
  kept only as the laptop estimate.
- Bundling CJK/Thai fonts (tens of megabytes in the image and installers).
- Widening `Locale` or `Accept-Language` in the contract now (breaks the phones' generated enums).
