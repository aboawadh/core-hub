package hub.core.android.ui.screens

/*
 * Settings → files: not native on the phone yet, so the registry draws the web fallback
 * (OnTheWebPage). The profile's files (browse, preview, upload): batches 11 and 13.
 * To make it native: draw the page here and drop `native = false` (docs/clients/phone-pages.md).
 */
internal val filesPage = SettingsPageEntry("files", native = false) { OnTheWebPage(it.destination) }
