package hub.core.android.ui.screens

/*
 * Settings → skills_usage: not native on the phone yet, so the registry draws the web fallback
 * (OnTheWebPage). Skills usage (audit.getSkillUsage): batch 10.
 * To make it native: draw the page here and drop `native = false` (docs/clients/phone-pages.md).
 */
internal val skillsUsagePage = SettingsPageEntry("skills_usage", native = false) { OnTheWebPage(it.destination) }
