# Phone pages: how to add or finish a native page

For whoever builds a page of the iOS app (`apps/ios`) or the Android app (`apps/android`). Several
people (and agents) build pages at the same time, so each page lives in files of its own and
nobody edits a shared switch. Build the page on **both** phones, from the same contract operations.

## 1. The page registry

Every Settings destination and every agent-level destination (`agent_*`) already has a file, with
an entry that names it, says whether the phone draws it, and draws it:

| | iOS | Android |
|---|---|---|
| Entry type | `PhonePage` (`CoreHub/Pages/PhonePage.swift`) | `SettingsPageEntry` / `AgentPageEntry` (`ui/screens/PageRegistry.kt`) |
| Settings pages | `CoreHub/Settings/Pages/<Name>Page.swift`, `Settings/Models/`, `Settings/LiveTools.swift` | `ui/screens/settings/<Name>Page.kt`, plus `ModelsScreen.kt`, `AdminPages.kt`, `ToolsScreens.kt` |
| Agent pages | `CoreHub/Screens/Agent/Agent<Name>Page.swift` | `ui/screens/agent/Agent<Name>Page.kt` (shared helpers in `AgentPageKit.kt`) |
| The lists | `PageRegistry.settings` / `.agent` | `PageRegistry.settings` / `.agent` |
| Fallback | `NotNativePage` (the “coming later” screen) | `OnTheWebPage` (opens the same page on the web; since 2026-09-27 no Android page draws it — every section works on its own, and a new page should too) |

The lists are complete and in the manifest's order; you do not edit them. A page that is not native
yet has an entry with `native: false` (Swift) / `native = false` (Kotlin) that draws the fallback:

```swift
extension PhonePage {
    static let files = PhonePage(.files, native: false) { context in NotNativePage(destination: context.destination) }
}
```

```kotlin
internal val filesPage = SettingsPageEntry("files", native = false) { OnTheWebPage(it.destination) }
```

**To make a page native:** draw it in that file (or in a new file of your own next to it) and drop
`native: false`. That is the whole wiring. An agent page gets the agent (`context.agent` /
`{ agent, profile -> … }`); a Settings page on Android gets the session, the shell and `onOpen`.

Keep one page per file. If your page grows, split it into more files of your own (a sheet, a row),
not into a file another page uses. Tasks and Schedules are `Screens/Tasks/`, `Screens/Schedules/`
(iOS) and `TasksScreen.kt` / `SchedulesScreen.kt` (Android).

## 2. Strings in a file of your own

Every user string exists in Arabic and English (AGENTS.md), with Latin digits (123) in both.

- **iOS:** add `CoreHub/i18n/<area>.en.json` and `<area>.ar.json` (nested keys, like `en.json`).
  `L10n` merges every `<area>.<lang>.json` after `en.json`/`ar.json`. Use keys under your area
  (`files.upload`), never a key that exists elsewhere.
- **Android:** add `res/values/strings_<area>.xml` and `res/values-ar/strings_<area>.xml`.

Checked by: `pnpm i18n:check` (iOS files, duplicate keys, Arabic-Indic digits), iOS `L10nTests`,
Android `StringsParityTest` (each English file has its Arabic twin with the same keys; no key in
two files). Do not add to the shared `en.json`/`ar.json`/`strings.xml` unless you change a string
already there.

## 3. Shared pieces

Use these instead of writing your own; each has a small test (iOS `PageKitTests`, Android
`PageKitTest`) and Android shots (`PageKitShots`, `apps/android/app/build/shots/page-kit/`).

| Piece | iOS (`CoreHub/Components/`) | Android (`ui/components/`) |
|---|---|---|
| Form sheet: text, multiline, number (min/max/whole), toggle, choice, secret with show/hide; checks before Save, shows the hub's refusal | `FormSheet(title:fields:initial:save:)` with `[FormField]`; rules in `FormRules` | `FormSheet(title, fields, initial, onDismiss, onSave)` / `FormBody(...)`; rules in `FormRules` |
| List scaffold: search, filter chips, pull to refresh, next page near the end, empty/failed states, long-press menu and swipe | `ListScaffold(list, key:query:filters:filter:actions:swipe:) { row }` over `PagedList { cursor in ListPage(items:next:) }` | `ListScaffold(list, key, query, onQuery, filters, filter, onFilter, actions, swipeAction) { row }` over `rememberPagedList(keys…, key = …) { cursor -> ListPage(items, next) }` |
| Confirm before delete | `.confirmDelete($pending, name:delete:deleted:)` | `rememberConfirmDelete<T>()` + `ConfirmDeleteDialog(state, { deleteTitle(name) }, onDelete)` |
| Text/Markdown editor (Edit/Preview, revision conflict → Reload) | `TextEditorSheet(title:initial:markdown:reload:save:)`; the field alone: `DocumentEditor` | `TextEditorSheet(title, initial, onDismiss, onSave, markdown, onReload)` / `TextEditorBody`; the field alone: `DocumentField` |
| Trigger editor: cron (with presets) / every N minutes·hours·days / once, time zone, next runs from `schedules.previewTrigger` | `TriggerEditor(draft: $draft, preview: { trigger in … })`; `TriggerRules.draft(saved)` / `.build(draft)` | `TriggerEditor(draft, onChange, preview = { trigger -> … })`; `TriggerRules.draft(saved)` / `.build(draft)` |

A delete is always: the row's action (swipe or menu) sets the pending item → the confirm asks → the
call runs → the list removes the row (`list.remove`) or reloads.

## 4. Tests and checks

- **Parity:** iOS `NavigationParityTests.testThePageRegistryNamesEverySettingsAndAgentPageOnceInOrder`;
  Android `NavigationParityTest` also checks, from the sources, that a page is `native` exactly when
  its entry does not draw `OnTheWebPage`. You do not edit these tests to finish a page.
- **Your page:** a test of its rules (plain functions next to the page, as `FormRules`/`TriggerRules`)
  that fails on the old code; on Android a shot or Compose test where it helps.
- **Run:** Android through `mj-run` (`./gradlew :app:testDebugUnitTest :app:lintDebug`, JDK 17,
  `pnpm --filter @corehub/contracts generate:native` first). iOS cannot build on Linux: read your
  Swift carefully and dispatch the iOS workflow on your branch
  (`gh workflow run ios.yml --ref <branch>`) or rely on the PR's iOS job.
- **Never** hand-type a hub path; call the generated clients (`pnpm contracts:check-clients`).
