# Google Play listing for Core Hub (Android)

Owner's decision (2026-09-27): publish the Android app (`com.twuijri.corehub`, `apps/android`) on
Google Play from a **personal** developer account. This page is everything the Play Console asks
for, with the answers taken from the app's code and [docs/privacy.md](../../privacy.md). Answers
marked *proposed* are the owner's to confirm. The owner enters every personal value (contact
email, the demo account, the demo hub's address) himself; none is written in this repository
(AGENTS.md: no hostnames of the owner's servers, no secrets).

| What | Where |
|---|---|
| Listing text (title, short and full description, release notes) | `apps/android/fastlane/metadata/android/{en-US,ar}/` (fastlane `supply` layout) |
| Icon (512 × 512, 32-bit PNG) and feature graphic (1024 × 500, 24-bit PNG) | `…/{locale}/images/icon.png`, `featureGraphic.png`, made by `pnpm icons:build` (`scripts/icons/build-icons.mjs`) from the Core Hub mark and `packages/ui-tokens/tokens.json` |
| Phone screenshots (6 per language, 1320 × 2346, 9:16, no alpha) | `…/{locale}/images/phoneScreenshots/`, see [Screenshots](#screenshots) |
| Limits check | `node apps/android/scripts/play-listing.mjs --summary` (tests: `pnpm scripts:test`) |
| Upload | Actions → **Google Play upload** (`.github/workflows/play-upload.yml`) |
| Privacy policy | https://github.com/twuijri/core-hub/blob/main/docs/privacy.md |

## The Play build

The Play build is the release AAB with the in-app updater off:

```sh
cd apps/android
./gradlew bundleRelease -Pcorehub.selfUpdate=false
```

`-Pcorehub.selfUpdate=false` (or `COREHUB_ANDROID_SELF_UPDATE=false`) sets
`BuildConfig.SELF_UPDATE = false`, so the app never asks GitHub for a release, and removes
`REQUEST_INSTALL_PACKAGES` from the manifest (`app/src/noSelfUpdate/AndroidManifest.xml`). Play's
Device and Network Abuse policy forbids an app that updates itself outside Play, and Play asks for
a declaration for that permission; the Play build has neither.

It is signed with **the same release keystore as the GitHub APK** (the secrets
`ANDROID_TEST_KEYSTORE_B64`, `ANDROID_TEST_KEYSTORE_PASSWORD`, `ANDROID_TEST_KEY_ALIAS`,
`ANDROID_TEST_KEY_PASSWORD`, read through `COREHUB_ANDROID_KEYSTORE*` exactly as
`android-signed.yml` does) and carries Firebase from `GOOGLE_SERVICES_JSON`. Without the keystore
variables a local `bundleRelease` stays unsigned. The GitHub APK build (`android-signed.yml`,
`publish-release.yml`) is unchanged and keeps self-update on.

**versionCode.** Play refuses a code it has already seen. The workflow uses 100000 + its own run
number (or the `version_code` input), which always grows and stays above the GitHub APK's codes
(100 + / 1000 + that workflow's run number): a phone with the GitHub APK can take the Play build as
an update when both carry the same signature (see [Play App Signing](#play-app-signing)).

**Target API level — blocker to clear first.** Since 31 August 2026 Play takes new apps and
updates only when they target **Android 16 (API 36)**; an extension to 1 November 2026 can be
requested in Play Console (developer.android.com/google/play/requirements/target-sdk, checked
2026-09-27). The app targets API 35 today (`app/build.gradle.kts`). Raising `targetSdk` and
`compileSdk` to 36 changes behaviour for the GitHub APK too, so it is its own change with its own
testing; until then request the extension, or the first upload will be refused. The workflow warns
when the target is below 36.

## Uploading (`play-upload.yml`)

Actions → **Google Play upload** → Run workflow. Inputs:

| Input | Default | Meaning |
|---|---|---|
| `upload` | `bundle` | `bundle`: the AAB and its release notes (`changelogs/default.txt`); `listing`: texts and images only, no build; `both` |
| `track` | `internal` | `internal`, `alpha` (the closed test), `beta` (open test), `production`. Anything but `internal` must run on `main`. |
| `status` | `draft` | Release status. A **draft app** (nothing ever rolled out) accepts only `draft`: roll the first release out in Play Console. |
| `version_code` | empty | Override the versionCode. |
| `keep_aab` | off | Keep the signed AAB as a workflow artifact for 7 days (public: the repository is public), for the first manual upload. |

It fails at once, before building, with a message naming the missing secret when
`PLAY_SERVICE_ACCOUNT_JSON` (or, for a build, `ANDROID_TEST_KEYSTORE_B64` / `GOOGLE_SERVICES_JSON`)
is not set. It checks the listing's limits, builds the AAB, checks the package name, the
versionCode, that self-update is off, that Firebase is in, and the signature, then runs
`fastlane supply` (images with `sync_image_upload`, so unchanged images are not sent again). It
never promotes a release between tracks and never sends anything for review by itself.

### Service account for uploads

1. Google Cloud console → a project (the Firebase project is fine) → IAM & Admin → Service
   accounts → **Create service account** (no roles needed in Cloud) → Keys → **Add key → JSON**.
2. Enable the **Google Play Android Developer API** in that project.
3. Play Console → **Users and permissions** → **Invite new users** → the service account's email →
   App permissions: Core Hub, with *Release to testing tracks*, *Release to production* (when
   wanted), *Manage store presence*. Account permissions: none.
4. GitHub → twuijri/core-hub → Settings → Secrets and variables → Actions → **New repository
   secret** `PLAY_SERVICE_ACCOUNT_JSON` = the whole JSON file. Delete the downloaded file.

The Play Developer API cannot create an app, and may refuse to take the very first bundle of a new
app. Create the app in Play Console first; if the first `bundle` run answers that the package is not
found or has no bundle, run it once with `keep_aab`, download the artifact, and upload that AAB by
hand in Play Console → Test and release → Internal testing → Create new release. Every later
upload goes through the workflow.

## Play App Signing

Play signs what it delivers with an **app signing key**; the AAB we upload is signed with an
**upload key**. Play Console → Test and release → App integrity → Play app signing offers, on the
first release:

- **Google generates the app signing key** (the default). Our release key becomes the upload key.
  Play installs are then signed with Google's key and the GitHub APK with ours: Android treats them
  as different signers, so a phone cannot move between the two without uninstalling (losing the
  sign-in and settings), and the GitHub APK's key has to be registered separately for developer
  verification (below).
- **Use our existing key** — *recommended (proposed — owner to confirm)*: "Use a different app
  signing key" → "Export and upload a key from Java keystore". Play Console gives an encryption key
  and the PEPK tool; on a machine that has the keystore (decode `ANDROID_TEST_KEYSTORE_B64`):

  ```sh
  java -jar pepk.jar --keystore=corehub-release.jks --alias=<the key alias> \
    --output=corehub-signing-key.zip --include-cert --rsa-aes-encryption \
    --encryption-key-path=encryption_public_key.pem
  ```

  Upload the zip. Play then signs with the same key as the GitHub APK: one signature for both, a
  GitHub user can move to Play as an update, and one key covers developer verification.
  Trade-off: Google holds a copy of the key, and a leak of the one key affects both channels.
  Play can later rotate the key for Play installs only (which would split the signatures again).

With our key as the app signing key the workflow may keep signing uploads with the same key (Play
accepts it). A separate upload key is safer — a lost or leaked upload key can be reset by Google
support without changing the app's signature — and can be added later as new secrets; the
workflow would then read those instead of the `ANDROID_TEST_*` ones.

## Android developer verification

From 30 September 2026 certified Android devices in Brazil, Indonesia, Singapore and Thailand
install only apps from verified developers, also outside Play; the rule goes global in 2027
(developer.android.com/developer-verification, checked 2026-09-27). For `com.twuijri.corehub`:

1. Finish the account's identity verification (in progress).
2. **Creating the app in Play Console registers the package name** to the account.
3. Play Console → Home (Android developer verification) → check that `com.twuijri.corehub` is
   listed as registered, with its signing key(s). The GitHub APK is distributed outside Play: if
   Play App Signing uses our key, it is the same key; if not, register the GitHub APK's key for the
   package there too (Play Console asks for an APK signed with that key as proof and gives the
   steps).

## Store listing

Play Console → Grow users → Store presence → Main store listing (default language **English
(United States) – en-US**; add translation **Arabic – ar**). `upload: listing` fills these from
the repository:

- **App name** "Core Hub" / «كور هب» (30 max), **Short description** (80 max), **Full
  description** (4000 max, plain text). The full description keeps the "Works with Hermes Agent"
  section so a Play search for Hermes finds the app.
- **App icon**, **Feature graphic**, **Phone screenshots**.

Entered by hand once (Store settings):

- **App or game**: App. **Category** *(proposed)*: **Productivity**. **Tags**: up to five from
  Play's list closest to AI assistant / productivity.
- **Contact details**: Email — *the owner's public support email* (Play requires one and shows
  it); Website `https://twuijri.github.io/core-hub/`; Phone optional (leave empty). Support is on
  GitHub: `https://github.com/twuijri/core-hub/issues`.
- **Privacy policy** (App content): `https://github.com/twuijri/core-hub/blob/main/docs/privacy.md`.
- **Price**: Free. **Countries**: all Play offers *(proposed)*.

### Screenshots

The six pictures in each language are the real app, rendered on the JVM (Robolectric) against the
demo hub — the same made-up data as the iOS store screenshots, never a real hub: a chat, the drawer
with the chats list, the Tasks board, Agents, Schedules and a new chat. Play wants 2–8 phone
screenshots, each side 320–3840 px, the long side at most twice the short one, JPEG or 24-bit PNG
(no alpha). The iPhone size the other shots use (1320 × 2868) is taller than 2:1, so the Play set
is 440 × 782 dp at 3× (1320 × 2346, 9:16), written without an alpha channel. To make them again:

```sh
cd apps/android
./gradlew testDebugUnitTest --tests 'hub.core.android.shots.PlayStoreShots'
cd ../..
node apps/android/scripts/play-listing.mjs --take-shots --summary   # copies and checks them
```

`PlayStoreShots` also runs with the other unit tests on every Android CI run, so a screen that
stops drawing fails there.

## App content (Policy → App content)

### Privacy policy

`https://github.com/twuijri/core-hub/blob/main/docs/privacy.md` (it covers the Android app).

### Ads

**No**, the app contains no ads.

### App access

**All or some functionality is restricted.** Add one set of instructions:

- Name: `Demo hub`
- Username / Password: *the demo account, entered by the owner in these fields only.*
- Any other information:

  > Core Hub is a client for a self-hosted server ("hub"); it has no account with the developer.
  > On the first screen, fill in Hub address: <DEMO_HUB_URL> and the username and password above,
  > then tap Sign in. The pairing-code buttons are an alternative for people who already use their
  > hub on the web and are not needed. After sign-in: send a message in the new chat (a free model
  > may take a few seconds), open the drawer (top left) for chats, Agents, Tasks and Schedules.

  Replace `<DEMO_HUB_URL>` with the demo hub's address when pasting. The demo hub must answer on
  the version under review and its model must reply ([the Apple notes](../apple/review-notes.md)
  has the same checklist).

### Content rating (IARC questionnaire) — proposed

Category: **All other app types** (not a game, not social networking). Core Hub ships no content
of its own; it shows what the AI models on the person's own hub write.

| Question | Answer | Why |
|---|---|---|
| Violence, blood, gore | No | The app ships no such content. |
| Sexuality, nudity | No | |
| Language (profanity, crude humour) | No | |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Gambling (real or simulated) | No | |
| Fear / horror | No | |
| Does the app let users interact or exchange content with other users? | **Yes** | On a shared hub, members work in the same rooms, tasks and chats. |
| Does the app share the user's current physical location with other users? | **No** | Only when an agent asks and the person agrees, and only to the person's own hub. |
| Can users purchase digital goods? | No | |
| Unrestricted internet access (browser or search engine)? | No | Links open the phone's browser. |
| Is the app a web browser or search engine? | No | |
| If asked whether the app shows AI-generated content | **Yes** | Replies come from models on the person's own hub; the developer supplies no model. |

Expected result: the lowest age band with a "Users Interact" notice. The target audience below (18+)
still keeps it out of the family sections.

### Target audience and content

- **Target age groups**: **18 and over** only.
- **Could the store listing unintentionally appeal to children?** No.
- The app is not in the Designed for Families programme.

### Other declarations

| Declaration | Answer |
|---|---|
| News app | No |
| COVID-19 contact tracing / status | No |
| Government app | No |
| Financial features | None |
| Health apps | None |
| Advertising ID | **No** — the app does not use it; the merged manifest has no `AD_ID` permission. |
| Data safety | Below |

Permissions Play may ask about: none needs a declaration. Location is foreground only (no
`ACCESS_BACKGROUND_LOCATION`), photos come from the system Photo Picker (no `READ_MEDIA_*`), the
Play build has no `REQUEST_INSTALL_PACKAGES`, and `FOREGROUND_SERVICE` comes from WorkManager
without a foreground service type.

**Generative AI.** Play's AI-Generated Content policy asks apps whose central feature is an AI
chatbot to let people report offensive AI output from inside the app. Core Hub is a client for
models the person runs on their own hub, which that policy's scope may leave out; if review asks
for it, a "Report" action on a reply (sending no chat content) is a small follow-up change.

### Data safety — proposed

Play counts data as **collected** when the app transmits it off the device, to anyone, including
through a library (Play Console Help → Data safety, checked 2026-09-27); it does not exempt a
server the person runs. So, although the developer receives none of it, the honest answers declare
what the app sends to the person's own hub, plus what Firebase Cloud Messaging and the push relay
handle. Nothing is **shared**: the transfer to the hub is the person's own action, Google (FCM) is a
service provider, and the relay is the developer's own service.

Overview questions:

- *Does your app collect or share any of the required user data types?* **Yes**.
- *Is all of the user data collected by your app encrypted in transit?* **No** — the app allows a
  plain `http://` hub on the home network (`usesCleartextTraffic`). FCM and the relay are HTTPS; a
  hub on `https://` is encrypted. (Should a later Play build refuse `http://` hubs, this becomes
  Yes.)
- *Which account creation methods does your app support?* **Username and password** — the account
  is made on the person's own hub (first-run setup, or an admin adds people); none with the
  developer.
- *Delete account URL*:
  `https://github.com/twuijri/core-hub/blob/main/docs/privacy.md#deleting-your-account-and-data`.
- *Do you provide a way for users to request that their data is deleted?* **Yes** — the same
  section: chats, tasks and files are deleted in the app; an admin deletes accounts
  (Settings → Users); the hub's owner deletes everything by removing the hub's data.

Data types (every one: collected **yes**, shared **no**, processed ephemerally **no**, purposes
**App functionality**; the two sign-in types also **Account management**):

| Category → type | Required or optional | What it is |
|---|---|---|
| Personal info → Name | Required | Display name, on the hub. |
| Personal info → User IDs | Required | Username, and the hub's user id. |
| Location → Approximate and Precise location | Optional | Sent to the hub only when an agent asks and the person agrees (or chose "always"). |
| Messages → Other in-app messages | Optional | Chats and rooms with agents. |
| Photos and videos → Photos, Videos | Optional | Attachments and files shared into the app. |
| Audio → Voice or sound recordings | Optional | Dictation when the voice setting is "Core Hub" (the hub transcribes it). |
| Files and docs | Optional | Attached and shared files. |
| App activity → Other user-generated content | Optional | Tasks, schedules, answers to an agent, settings. |
| Device or other IDs | Required | The app's random device key, the phone's name, maker, model, Android and app versions (the device record on the hub), and the FCM token (Firebase, the hub, and the relay as a SHA-256 hash only). |

Not collected: contacts, calendar, financial info, health, web browsing, app interactions for
analytics, crash logs, diagnostics, advertising ID. The phone's own speech recognizer (voice setting
"This phone") runs under its provider's terms, outside the app.

The alternative — "No data collected", as on the App Store (Apple counts only data the developer
can access) — is not recommended on Play: its definition does not depend on who runs the server,
and the Firebase SDK does send the token and an installation id to Google.

## Closed test (personal account)

A personal developer account created after 13 November 2023 must run a **closed test with at least
12 testers opted in for 14 days in a row** before it can apply for production. Plan *(proposed)*:

1. After the app, the listing and App content are complete: run the workflow with
   `upload: both`, `track: internal` to check that the build installs (internal testing has no
   review and does not count toward the 12).
2. Play Console → Test and release → Testing → **Closed testing** → the `Alpha` track →
   Testers: a Google Group (easiest to grow) or an email list with at least **12** testers who can
   sign in to a hub (the demo hub's account or their own). Countries: all.
3. Run the workflow with `track: alpha`, `status: draft`, then in Play Console review the release
   and **Start rollout to Closed testing** (the first rollout goes through review; it can take
   several days).
4. Send the testers the opt-in link; each must accept it and install from Play. Keep at least 12
   opted in for **14 consecutive days**; ship fixes to `alpha` during the test (after the first
   rollout, `status: completed` works).
5. Dashboard → **Apply for production**: answer the questions about the test (how testers were
   found, what feedback changed). Once approved, promote the release to production in Play Console,
   or run the workflow with `track: production`.

## What the owner still does

- Finish identity verification; create the app `com.twuijri.corehub` (App, Free).
- Clear the target API blocker (API 36 change, or request the extension to 1 November 2026).
- Choose the Play App Signing option (recommended: upload our existing key with PEPK).
- Make the service account and the secret `PLAY_SERVICE_ACCOUNT_JSON`.
- Enter the contact email, App access (demo account and hub address), and the App content forms
  above.
- Recruit the 12 closed-test testers.
