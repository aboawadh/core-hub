package hub.core.android.ui.screens

/*
 * Settings → plugins: not native on the phone yet, so the registry draws the web fallback
 * (OnTheWebPage). The hub's plugins list: batch 10.
 * To make it native: draw the page here and drop `native = false` (docs/clients/phone-pages.md).
 */
internal val hubPluginsPage = SettingsPageEntry("plugins", native = false) { OnTheWebPage(it.destination) }
