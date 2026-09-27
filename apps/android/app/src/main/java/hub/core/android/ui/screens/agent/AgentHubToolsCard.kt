package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.components.Load
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.HubTool
import hub.core.client.model.HubToolGroup
import hub.core.client.model.HubToolGroupId
import hub.core.client.model.HubTools
import kotlinx.coroutines.launch

@Composable
internal fun rememberAgentsTwoOps(agent: Agent, profile: String): AgentsTwoOps {
    val context = LocalContext.current
    return remember(agent.id, profile) { AgentsTwoOps({ context.graph.store.current?.let(context.graph::apis) }, profile, agent.id) }
}

/** Words for a key the hub sends, from a generated map; the key itself when the phone has none. */
@Composable
internal fun wordsOf(map: Map<String, Int>, key: String): String = map[key]?.let { stringResource(it) } ?: key

/**
 * «Core Hub tools» (decision §67): the hub offers itself to this agent as an MCP server, in groups.
 * Off until an admin switches it on; each group reads, and changes only when its second switch says
 * so. Test asks Hermes, as a server row's does; the last calls show whose they were refused for.
 * A hub older than the card answers something else: the card then shows nothing.
 */
@Composable
internal fun HubToolsCard(ops: AgentsTwoOps, onServer: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<HubTools?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<McpTest?>(null) }
    var testing by remember { mutableStateOf(false) }
    val load = rememberLoad(ops.agentId, ops.profile, "hub-tools") { ops.hubTools().getOrThrow() }
    LaunchedEffect(load.state) {
        (load.state as? Load.Ready)?.value?.let { data = it; onServer(it.serverName) }
    }
    fun change(block: suspend () -> Result<HubTools>) {
        busy = true
        scope.launch {
            block().onSuccess { data = it; error = null }.onFailure { error = it as HubError }
            busy = false
        }
    }
    val shown = data ?: return
    HubToolsView(
        shown, busy, error, test, testing,
        onSwitch = { on -> change { ops.switchHubTools(on) } },
        onGroup = { id, enabled, writes -> change { ops.setGroup(id, enabled, writes) } },
        onTest = {
            testing = true
            scope.launch {
                ops.testServer(shown.serverName).onSuccess { test = McpTest(result = it) }.onFailure { test = McpTest(error = it as HubError) }
                testing = false
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HubToolsView(
    data: HubTools,
    busy: Boolean,
    error: HubError?,
    test: McpTest?,
    testing: Boolean,
    onSwitch: (Boolean) -> Unit,
    onGroup: (HubToolGroupId, Boolean?, Boolean?) -> Unit,
    onTest: () -> Unit,
) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("hub.tools"), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agents2_hub_title), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.agents2_hub_subtitle), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            HubSwitch(data.enabled, onSwitch, Modifier.testTag("hub.tools.switch"), enabled = !busy && (data.available || data.enabled))
        }
        if (!data.available) NoticeBox(wordsOf(Agents2Words.unavailable, data.unavailableReason?.value ?: "runtime_absent"), BadgeTone.Warning)
        Text(stringResource(R.string.agents2_hub_acts_as), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ToolErrorNotice(error)
        data.groups.forEach { group -> HubGroupRow(group, !data.enabled || busy, onGroup) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(
                stringResource(if (testing) R.string.agents2_mcp_testing else R.string.mcp_test), onTest,
                kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Activity, loading = testing, enabled = data.enabled,
                modifier = Modifier.testTag("hub.tools.test"),
            )
            data.url?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f)) }
        }
        McpTestView(data.serverName, test)
        Text(stringResource(R.string.agents2_hub_recent), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium)
        if (data.recentCalls.isEmpty()) Text(stringResource(R.string.agents2_hub_no_calls), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        data.recentCalls.forEach { call ->
            FlowRow(Modifier.testTag("hub.tools.call"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Badge(stringResource(if (call.ok) R.string.agents2_hub_call_ok else R.string.agents2_hub_call_failed), tone = if (call.ok) BadgeTone.Success else BadgeTone.Danger)
                Text(call.tool, fontSize = FontTokens.sizeSm.sp, fontFamily = FontFamily.Monospace)
                call.errorCode?.let { Text(wordsOf(Agents2Words.reason, it), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
                Text(localTime(call.createdAt), fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubGroupRow(group: HubToolGroup, disabled: Boolean, onGroup: (HubToolGroupId, Boolean?, Boolean?) -> Unit) {
    val t = LocalTokens.current
    val writes = group.tools.any { it.access == HubTool.Access.WRITE }
    val reads = group.tools.any { it.access == HubTool.Access.READ }
    Column(Modifier.testTag("hub.group.${group.id.value}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(wordsOf(Agents2Words.group, group.id.value), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium)
                Text(wordsOf(Agents2Words.groupAbout, group.id.value), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            HubSwitch(group.enabled, { onGroup(group.id, it, null) }, Modifier.testTag("hub.group.${group.id.value}.switch"), enabled = !disabled)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            group.tools.forEach { Badge(it.name, tone = if (it.access == HubTool.Access.WRITE) BadgeTone.Warning else BadgeTone.Neutral) }
        }
        if (!reads) Text(stringResource(R.string.agents2_hub_writes_only), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        if (writes) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.agents2_hub_allow_writes), fontSize = FontTokens.sizeSm.sp, modifier = Modifier.weight(1f))
                HubSwitch(group.allowWrites, { onGroup(group.id, null, it) }, Modifier.testTag("hub.group.${group.id.value}.writes"), enabled = !disabled && group.enabled)
            }
        }
    }
}
