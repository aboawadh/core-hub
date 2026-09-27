package hub.core.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.graph
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
    "linked_hubs" -> Lucide.Network
    "terminal" -> Lucide.SquareTerminal
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
    val graph = LocalContext.current.graph
    val signedIn = graph.store.current
    val owner = signedIn?.user?.role == "owner"
    var terminalOn by remember { mutableStateOf(signedIn?.hub?.let(TerminalAvailability::cached) == true) }
    LaunchedEffect(owner, signedIn?.hub) {
        val s = signedIn ?: return@LaunchedEffect
        if (owner) terminalOn = TerminalAvailability.check(s.hub, hub.core.client.api.TerminalApi(hub.core.android.data.apiBase(s.hub), graph.http.authed))
    }
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
        SettingsList.visible(isAdmin).forEach { (title, all) ->
            // The terminal is the owner's, and only on a hub that offers it (GET /terminal answered 200).
            val rows = all.filter { it !in Screens.ownerOnly || (owner && terminalOn) }
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
    }
}
