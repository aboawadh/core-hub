package hub.core.android.ui.screens

/*
 * Settings → updates: not native on the phone yet, so the registry draws the web fallback
 * (OnTheWebPage). The hub's release shelf is a rare server task and may stay on the web; the phone's own
 * update check is on This device.
 * To make it native: draw the page here and drop `native = false` (docs/clients/phone-pages.md).
 */
internal val updatesPage = SettingsPageEntry("updates", native = false) { OnTheWebPage(it.destination) }
