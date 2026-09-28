# Add your language

Core Hub is written in Arabic and English, and anyone can add another language (ADR 0028). You
fill translation files; the language then appears in every language picker — web, desktop, the
hub's messages and the terminal client, and on the phones once they join (phase 2) — in its own
name, with its own reading direction. Nothing needs a code change.

## 1. Start the language

```sh
corepack enable && pnpm install
pnpm i18n:new fr                       # French
pnpm i18n:new pt-BR                    # a regional variant
pnpm i18n:new zh-Hant --fallback zh-Hans   # try Simplified Chinese before English
```

The code is a BCP 47 tag: a language (`fr`), optionally a script (`zh-Hant`) and a region
(`pt-BR`). The command:

- adds the language to `locales/languages.json` as `partial`, with its English and native names and
  its direction taken from your system (`--english-name`, `--native-name`, `--direction rtl` if they
  are missing or wrong);
- writes `<code>.json` next to `en.json` in `packages/server/src/i18n`, `packages/cli/src/i18n`,
  `packages/web/src/i18n` and `apps/desktop/src/i18n` — the English keys, every string empty;
- regenerates each platform's `catalogues.ts`.

An empty string is not translated yet: that text keeps showing in English (or in your
`--fallback` language first). So the language works from the first minute, and gets better with
each string you fill.

## 2. Translate

Fill the empty strings. Keep, exactly as they are:

- `{placeholders}` — every one the English has, and no new ones (`{count} files` → `{count} fichiers`);
- text in `` `backticks` `` — commands and names a person types;
- the number of lines.

**Plurals.** A string that changes with a number is a group of keys named after the CLDR plural
forms: `zero`, `one`, `two`, `few`, `many`, `other`. Give `other` always, and each form your
language uses (French: `one`, `many`, `other`; Chinese: `other` only; Arabic: all six). A form you
leave empty uses your `other`.

**Digits** are always 0-9, in every language (DECISIONS §113).

**Words.** What the code calls a workspace, a person reads as a *profile*.

## 3. Check it

```sh
pnpm i18n:check    # coverage per platform, and anything that would break the UI
pnpm i18n:limits   # every string wider than the label it is shown in
```

`i18n:check` fails only on a broken placeholder, a changed `` `code` `` span or line count, a plural
group without `other`, or non-Latin digits; the rest is your coverage.

`i18n:limits` measures your words with real fonts (install the Noto fonts for your script, or it
estimates and says so). **`locales/limits.md` lists every label that holds one line, with its
room in pixels and roughly how many Latin letters fit.** A translation may be as wide as the
English and as wide as the room; if it is wider than both, find shorter words or a common
abbreviation — a cut label is worse.

To see your language: `pnpm dev` and `pnpm web:dev`, then pick it in the sidebar's language menu or
Settings → Display.

## 4. Open a pull request

Open a [New language issue](../../.github/ISSUE_TEMPLATE/new_language.yml) first, then a pull
request with `Closes #<issue>`. Say how complete it is and who can review the wording. The owner
marks the language `complete` in the registry when every string is translated and reviewed.

## For maintainers

- A new user-facing string goes into every platform's `ar.json` and `en.json`; other languages
  pick it up in English until translated.
- When a screen's layout changes a label's room, re-measure:
  `COREHUB_MEASURE_SPACE=/tmp/space.json pnpm --filter @corehub/web exec playwright test e2e/zzzzzzzzzzzzzzzzzz-measure-space.spec.ts --workers=1`
  (after `pnpm build`), then `pnpm i18n:limits --measure /tmp/space.json`.
- The pseudo-locale journeys (`e2e/zzzzzzzzzzzzzzzzz-pseudo-locales.spec.ts`) walk every main
  screen in `en-XA`, `ar-XB`, `zh-XC` and `th-XD`; a new screen belongs in `e2e/pseudo-screens.ts`.
