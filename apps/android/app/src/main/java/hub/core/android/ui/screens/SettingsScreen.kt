package hub.core.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.graph
import hub.core.android.nav.AppPaths
import hub.core.android.nav.Route
import hub.core.android.nav.Screens
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens

/** The Settings list's three groups, in the manifest's order (the parity test compares them). */
object SettingsList {
    val groups: List<Pair<Int?, List<String>>> = listOf(
        null to Screens.settingsTabs,
        R.string.settings_management to Screens.settingsManagement,
        R.string.settings_tools to Screens.settingsTools,
    )

    /** What this person sees: admin-only rows are hidden from members; This device is this surface's. */
    fun visible(isAdmin: Boolean): List<Pair<Int?, List<String>>> =
        groups.map { (title, rows) -> title to rows.filter { Screens.visible(it, isAdmin) } }

    /** The pages the phone draws itself (PageRegistry); the rest open the same page on the web. */
    val native: Set<String> get() = PageRegistry.nativeSettings
}

/** Each Settings row's icon, the iOS app's and the web's (Lucide on every surface). */
fun settingsIcon(destination: String): Int = when (destination) {
    "account" -> Lucide.CircleUserRound
    "users" -> Lucide.Users
    "webhooks" -> Lucide.Webhook
    "display" -> Lucide.Type
    "notifications" -> Lucide.Bell
    "privacy" -> Lucide.Hand
    "this_device" -> Lucide.Smartphone
    "about" -> Lucide.Info
    "models" -> Lucide.Layers
    "device_connections" -> Lucide.QrCode
    "knowledge" -> Lucide.Library
    "logs" -> Lucide.FileSearch
    "usage" -> Lucide.ChartColumn
    "skills_usage" -> Lucide.WandSparkles
    "performance" -> Lucide.Gauge
    "theme" -> Lucide.Palette
    "workspaces" -> Lucide.LayoutGrid
    "updates" -> Lucide.CircleArrowDown
    "plugins" -> Lucide.Puzzle
    "files" -> Lucide.Folder
    else -> Lucide.Settings
}

/**
 * Settings on the phone, as on iOS: the list is the page (NAVIGATION.md §2), headed by «Back to
 * chats» (which returns to the conversation that was open before Settings), then the groups as
 * inset lists with an icon per row.
 */
@Composable
fun SettingsScreen(isAdmin: Boolean, onOpen: (Route) -> Unit, onBackToChats: () -> Unit) {
    val t = LocalTokens.current
    LazyColumn(Modifier.fillMaxSize().testTag("settings.list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item {
            GroupedList {
                Item(
                    stringResource(R.string.settings_back_to_chats), icon = Lucide.ArrowLeft, onClick = onBackToChats, tag = "settings.back", accent = true,
                )
            }
        }
        // A newer Core Hub was found (SelfUpdate.kt): one row, opening This device where Update is.
        item(key = "update") {
            hub.core.android.phone.UpdateSettingsRow(
                onOpen = { onOpen(Route.SettingsPage("this_device")) }, modifier = Modifier.padding(top = 16.dp),
            )
        }
        SettingsList.visible(isAdmin).forEach { (title, rows) ->
            if (rows.isEmpty()) return@forEach
            item(key = "g$title") {
                GroupedList(Modifier.padding(top = if (title == null) 16.dp else 0.dp), title = title?.let { stringResource(it) }) {
                    rows.forEach { destination ->
                        Item(
                            term(destination), icon = settingsIcon(destination), iconTint = t.accent, chevron = true,
                            tag = "settings.row.$destination", onClick = { onOpen(Route.SettingsPage(destination)) },
                        )
                    }
                }
            }
        }
        // What only a computer's screen does (a shell on the hub's host, linking hubs) stays on the
        // web: said here, one tap away, rather than missing (not destinations of the phone).
        if (isAdmin) item(key = "web-only") { OnTheWebRows() }
    }
}

/** The web-only pages an admin may still want from the phone: they open in the browser. */
@Composable
private fun OnTheWebRows() {
    val context = LocalContext.current
    val t = LocalTokens.current
    val session = context.graph.store.current ?: return
    val rows = WebOnly.rows(session.user.role)
    if (rows.isEmpty()) return
    GroupedList(title = stringResource(R.string.settings_on_the_web)) {
        rows.forEach { destination ->
            Item(
                term(destination), icon = if (destination == "terminal") Lucide.Terminal else Lucide.Globe, iconTint = t.textMuted,
                subtitle = stringResource(R.string.settings_on_the_web_hint), tag = "settings.web.$destination",
                trailing = { hub.core.android.ui.kit.LucideIcon(Lucide.ExternalLink, null, size = 16.dp, tint = t.textFaint) },
                onClick = {
                    AppPaths.webUrl(session.hub, destination)?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                },
            )
        }
    }
}

/** Which web-only pages a role sees (navigation.json: the terminal is the owner's, linked hubs an admin's). */
object WebOnly {
    fun rows(role: String): List<String> = when (role) {
        "owner" -> listOf("linked_hubs", "terminal")
        "admin" -> listOf("linked_hubs")
        else -> emptyList()
    }
}
