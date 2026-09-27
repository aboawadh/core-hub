# Core Hub privacy policy

*For the Core Hub apps for iPhone, iPad and Android (`com.twuijri.corehub`). Last updated 27 September 2026.*
[العربية](privacy.ar.md)

Core Hub is an app for **your own Core Hub server** (a "hub"): software you or someone you trust
runs on a computer of your choosing. The app is a window onto that hub. It is developed by
twuijri, who does not run your hub and cannot see it.

## In short

- **The developer collects no data.** No account with us, no analytics, no advertising, no
  tracking, no crash reporting of our own. The iPhone app contains no third-party SDK; the Android
  app contains one, Google's Firebase Cloud Messaging, to receive notifications (below).
- **The app talks only to the hub you choose**, at the address you type or the pairing code you
  scan. What you send there is kept by that hub and by whoever runs it, under their rules.
- A few things necessarily pass through **Apple** (on iPhone) or **Google** (on Android): push
  notifications, and speech recognition when you dictate with the phone's own voice. They are
  described below.

## What the app sends to your hub

Everything you do in the app is a request to your hub: signing in, your messages and the photos
and files you attach, your answers to an agent, changes to tasks, schedules and settings. When you
sign in, the app also tells your hub how to list this phone among your devices: the phone's name
and model, the iOS or Android and app versions, whether notifications can reach it, and a random identifier
made by the app. When you allow
notifications, it gives your hub this phone's push token so the hub can notify you.

Your sign-in (the hub's address and the token it gave the app) is kept in the phone's Keychain
(on Android, encrypted with a key in the Android Keystore).
Your choices on this phone (language, theme, voice) stay on the phone.

## Notifications

When a reply is ready or an agent is waiting for you, your hub can send a notification. It goes
through **Apple Push Notification service**, like every notification on iPhone:

- with the hub's own Apple key, if whoever runs your hub set one up; or
- **when enabled**, through the Core Hub push relay run by the developer (a Cloudflare Worker),
  for hubs that have no Apple key of their own. The relay passes each notification on to Apple
  and stores no content: no title, no text, no message and no push token. To know which hub may
  notify which phone, it keeps only a one-way hash of the push token, and it keeps counters to
  limit abuse. A hub can turn on private notifications, which then say only "New notice in
  Core Hub" and the app reads the rest from your hub.

**On Android** notifications go through Google's **Firebase Cloud Messaging** (FCM) in the same
two ways (the hub's own Firebase sender, or the relay). To receive them, the Firebase library in
the app gets a push token and an installation identifier from Google after you sign in; the app
gives the token to your hub, as above. Google handles them as Firebase's provider, under
[Google's privacy policy](https://policies.google.com/privacy). A build without Firebase (for
example one you build yourself) never contacts Google and checks your hub now and then instead.

You can turn notifications off at any time in the phone's Settings. Without them, the app looks
for new notices from your hub now and then while it is closed.

## Permissions the app asks for, and why

| Permission | Why |
|---|---|
| **Camera** | To scan the pairing code your hub shows on the web, and to take a photo you attach to a message. |
| **Photos** | To attach the photos you pick to a message. Only the photos you choose are read. |
| **Microphone** | To hear you while you dictate a message. |
| **Speech recognition** | To turn your dictation into text when the app uses the phone's own voice (see below). |
| **Local network** | To reach a hub on your home or office network. |
| **Notifications** | To tell you when a reply is ready or an agent is waiting for you. |
| **Location** (Android) | Only when an agent on your hub asks where this phone is and you agree (you choose: ask each time, always, or never, in **Settings → This device**). The answer goes only to your hub. Never in the background. |

Nothing is recorded or read unless you start it.

## Dictation and spoken replies

When you dictate, the app uses the voice setting in **Settings → This device**:

- **Core Hub** (the default): if your hub has a speech service set up, your recording goes to your
  hub, which turns it into text. Otherwise the phone's own recognizer is used.
- **This phone**: the phone's own speech recognition. This is **Apple's speech recognition**, which
  may send the audio to Apple to be processed, under
  [Apple's privacy policy](https://www.apple.com/legal/privacy/).

On Android, **This phone** uses the speech recognizer installed on the phone (usually Google's),
under its provider's terms.

Replies read aloud come from your hub's speech service or from the phone's own voice.

## Sharing into Core Hub

When you share text or a link to Core Hub from another app, it is kept on the phone until you open
Core Hub, where it becomes the draft of a new chat. Nothing is sent until you send it.

## Updates of the Android app

The Android app downloaded from the GitHub releases checks GitHub for a newer release now and then
and, when you agree, downloads and installs it; GitHub sees that request like any download. The
app installed from Google Play never does this: Google Play updates it.

## Deleting your account and data

Your Core Hub account and everything in it live on **your hub**, never with the developer, so they
are deleted there:

- **Chats, rooms, tasks, schedules and files**: delete them in the app or on the web, from each
  one's menu.
- **An account**: an admin of the hub deletes it in **Settings → Users** (in the app or on the web);
  its sign-ins, devices and push tokens are revoked. Ask your hub's admin if that is not you. The
  hub's owner account is removed with the hub itself.
- **Everything**: whoever runs the hub deletes the hub's data folder (its Docker volume or data
  directory).
- **This phone**: signing out removes the sign-in and the push token from the phone and the hub;
  uninstalling the app removes everything it kept on the phone. The push relay forgets a token's
  hash when the hub lets go of it.

The developer holds no account or personal data to delete; for questions,
[open an issue](https://github.com/twuijri/core-hub/issues).

## Children

Core Hub is a tool for people who run their own server. It is not directed at children.

## Changes and contact

Changes to this policy are made in this file, in the open, and its history shows every change.
Questions: [open an issue](https://github.com/twuijri/core-hub/issues).
