package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.MemoryItem
import kotlinx.coroutines.launch

@Composable
private fun MemoryPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    val load = rememberLoad(agent.id, profile) { apis().agents.agentsListMemory(profile, agent.id).items }
    LoadView(load) { items ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.memory")) {
            if (items.isEmpty()) item { EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.Brain) }
            items(items, key = { it.id }) { item ->
                HubCard(Modifier.testTag("memory.${item.id}"), onClick = { editing = item }, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        hub.core.android.ui.kit.LucideIcon(Lucide.Pencil, null, size = 14.dp, tint = t.textFaint)
                    }
                    item.content?.let { c -> InContentDirection(c) { Text(c, fontSize = FontTokens.sizeSm.sp, color = t.textMuted, maxLines = 6) } }
                }
            }
        }
    }
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.content.orEmpty()) }
        var saving by remember(item.id) { mutableStateOf(false) }
        var error by remember(item.id) { mutableStateOf<HubError?>(null) }
        HubSheet(onDismiss = { editing = null }, title = item.title) {
            ErrorNotice(error)
            HubTextField(text, { text = it }, singleLine = false, minLines = 8, maxLines = 16, fieldTag = "memory.editor", modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubButton(stringResource(R.string.save), {
                    saving = true
                    scope.launch {
                        ops.saveMemory(item, text).onSuccess { editing = null; load.reload() }.onFailure { error = it as HubError }
                        saving = false
                    }
                }, size = ControlSize.Md, icon = Lucide.Check, loading = saving, enabled = text != item.content.orEmpty(), modifier = Modifier.testTag("memory.save"))
                HubButton(stringResource(R.string.cancel), { editing = null }, kind = ButtonKind.Ghost, size = ControlSize.Md)
            }
        }
    }
}

internal val agentMemoryPage = AgentPageEntry("agent_memory") { agent, profile -> MemoryPage(agent, profile) }
