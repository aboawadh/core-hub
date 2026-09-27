package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupScope
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.HubPlugin

/**
 * Settings → Plugins (the web's PluginsTab): what is installed on this hub, with its kind, version and
 * state. The contract answers only the list (`plugins.list`): the hub has no installer, switch or
 * settings for hub plugins yet, and the empty state says so rather than promising one. An agent's own
 * plugins are the agent's Plugins page. iOS's HubPluginsPage.swift is the twin.
 */
@Composable
private fun HubPluginsPage(profile: String) {
    val ops = rememberHubDataOps(profile)
    val plugins = rememberLoad("plugins") { ops.plugins().getOrThrow() }
    LazyColumn(Modifier.testTag("plugins.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.knowledge_plugins_intro), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted) }
        item {
            LoadView(plugins) { list ->
                if (list.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.knowledge_plugins_none), Modifier.testTag("plugins.empty"),
                        body = stringResource(R.string.knowledge_plugins_none_body), icon = Lucide.Puzzle,
                    )
                } else {
                    GroupedList { list.forEach { HubPluginRow(it) } }
                }
            }
        }
    }
}

/** One plugin: its name, `slug · version · kind`, and its state as a badge. */
@Composable
internal fun GroupScope.HubPluginRow(plugin: HubPlugin) {
    val (label, tone) = when (plugin.status) {
        HubPlugin.Status.RUNNING -> stringResource(R.string.knowledge_plugins_status_running) to BadgeTone.Success
        HubPlugin.Status.ERROR -> stringResource(R.string.knowledge_plugins_status_error) to BadgeTone.Danger
        else -> stringResource(R.string.knowledge_plugins_status_stopped) to BadgeTone.Neutral
    }
    Item(
        plugin.name, icon = Lucide.Puzzle, subtitle = "${plugin.slug} · ${plugin.version} · ${plugin.kind.value}",
        tag = "plugin.${plugin.slug}", trailing = { Badge(label, tone = tone, dot = true) },
    )
}

internal val hubPluginsPage = SettingsPageEntry("plugins") { HubPluginsPage(it.session.profile) }
