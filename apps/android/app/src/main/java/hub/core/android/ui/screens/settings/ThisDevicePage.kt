package hub.core.android.ui.screens

/** This device: the page lives in `phone/ThisDevice.kt` and needs the activity's pieces, which MainActivity hands in. */
internal val thisDevicePage = SettingsPageEntry("this_device") { it.thisDevice() }
