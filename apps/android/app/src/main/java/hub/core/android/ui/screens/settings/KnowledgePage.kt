package hub.core.android.ui.screens

/*
 * Settings → knowledge: not native on the phone yet, so the registry draws the web fallback
 * (OnTheWebPage). Knowledge (list with kind filter and search): batch 10.
 * To make it native: draw the page here and drop `native = false` (docs/clients/phone-pages.md).
 */
internal val knowledgePage = SettingsPageEntry("knowledge", native = false) { OnTheWebPage(it.destination) }
