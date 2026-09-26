package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.ToggleRow
import hub.core.client.model.Agent
import kotlinx.coroutines.launch

@Composable
private fun PluginsPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<HubError?>(null) }
    val load = rememberLoad(agent.id, profile) { apis().agents.agentsListPlugins(profile, agent.id) }
    LoadView(load) { list ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.plugins")) {
            item { ErrorNotice(error) }
            items(list.warnings) { NoticeBox(it, BadgeTone.Warning) }
            if (list.items.isEmpty()) item { EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.Puzzle) }
            else item {
                GroupedList {
                    list.items.forEach { plugin ->
                        Custom {
                            ToggleRow(
                                plugin.name, plugin.enabled,
                                { on -> scope.launch { ops.setPlugin(plugin.key, on).onFailure { error = it as HubError }.onSuccess { error = null }; load.reload() } },
                                subtitle = plugin.description ?: plugin.status.value.replace('_', ' '), enabled = plugin.manageable,
                                modifier = Modifier.testTag("plugin.${plugin.key}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal val agentPluginsPage = AgentPageEntry("agent_plugins") { agent, profile -> PluginsPage(agent, profile) }
