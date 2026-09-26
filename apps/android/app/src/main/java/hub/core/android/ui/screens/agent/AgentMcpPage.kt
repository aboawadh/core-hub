package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import kotlinx.coroutines.launch

@Composable
private fun McpPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var error by remember { mutableStateOf<HubError?>(null) }
    val results = remember { mutableStateMapOf<String, AgentNote>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val load = rememberLoad(agent.id, profile) { apis().agents.agentsListMcpServers(profile, agent.id).items }
    val okText = stringResource(R.string.mcp_test_ok)
    LoadView(load) { servers ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.mcp")) {
            item { ErrorNotice(error) }
            if (servers.isEmpty()) item { EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.Server) }
            items(servers, key = { it.name }) { server ->
                HubCard(Modifier.testTag("mcp.${server.name}"), padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(server.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Badge(
                            stringResource(if (server.connected) R.string.mcp_connected else if (server.enabled) R.string.mcp_not_connected else R.string.agent_off),
                            tone = if (server.connected) BadgeTone.Success else BadgeTone.Neutral, dot = true,
                        )
                        HubSwitch(server.enabled, { on -> scope.launch { ops.setMcp(server.name, on).onFailure { error = it as HubError }.onSuccess { error = null }; load.reload() } }, Modifier.testTag("mcp.${server.name}.switch"))
                    }
                    Text(
                        listOf(server.transport.value, stringResource(R.string.agent_tools, server.tools.size)).joinToString(" · "),
                        fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                    )
                    server.error?.let { NoticeBox(it, BadgeTone.Danger) }
                    AgentNoteView(results[server.name], Modifier.testTag("mcp.${server.name}.result"))
                    HubButton(
                        stringResource(R.string.mcp_test), {
                            testing[server.name] = true
                            scope.launch {
                                ops.testMcp(server.name)
                                    .onSuccess { r -> results[server.name] = AgentNote(if (r.ok) okText.format(r.tools.size) else r.error ?: "—", if (r.ok) BadgeTone.Success else BadgeTone.Danger) }
                                    .onFailure { results[server.name] = AgentNote(error = it as HubError) }
                                testing[server.name] = false
                            }
                        },
                        kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Activity, loading = testing[server.name] == true,
                        modifier = Modifier.testTag("mcp.${server.name}.test"),
                    )
                }
            }
        }
    }
}

internal val agentMcpPage = AgentPageEntry("agent_mcp") { agent, profile -> McpPage(agent, profile) }
