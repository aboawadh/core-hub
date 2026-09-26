package hub.core.android.ui.screens

/*
 * Settings → webhooks: not native on the phone yet, so the registry draws the web fallback
 * (OnTheWebPage). Notification webhooks (create, edit, events, deliveries): batch 14.
 * To make it native: draw the page here and drop `native = false` (docs/clients/phone-pages.md).
 */
internal val webhooksPage = SettingsPageEntry("webhooks", native = false) { OnTheWebPage(it.destination) }
