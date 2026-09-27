package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.AgentPlugin
import hub.core.client.model.Job
import hub.core.client.model.JobStatus
import kotlinx.coroutines.launch

/**
 * An agent's plugins, as Hermes lists them in the profile (apps batch 8, the web's page): the switch,
 * the source and Hermes's own status, Remove (after asking) for what was installed into the profile,
 * and Install by catalog name, `owner/repo` or Git URL — Hermes's install as a job, followed to its end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginsPage(agent: Agent, profile: String) {
    val ops = rememberToolOps(agent, profile)
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var error by remember { mutableStateOf<HubError?>(null) }
    var removing by remember { mutableStateOf<AgentPlugin?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var identifier by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var installError by remember { mutableStateOf<HubError?>(null) }
    val load = rememberLoad(agent.id, profile) { ops.plugins().getOrThrow() }

    fun change(key: String, block: suspend () -> Result<*>) {
        busy = key
        scope.launch {
            block().onSuccess { error = null }.onFailure { error = it as HubError }
            busy = null
            load.reload()
        }
    }

    fun install() {
        val value = identifier.trim()
        if (!PluginRules.validIdentifier(value) || installing) return
        installing = true
        job = null
        installError = null
        scope.launch {
            ops.installPlugin(value).onSuccess { id ->
                identifier = ""
                ops.follow(id) { job = it }
                load.reload()
            }.onFailure { installError = it as HubError }
            installing = false
        }
    }

    LoadView(load) { list ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.plugins")) {
            item(key = "note") { Text(stringResource(R.string.agents_plugin_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            item(key = "install") {
                val bad = identifier.isNotBlank() && !PluginRules.validIdentifier(identifier)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubTextField(
                            identifier, { identifier = it }, Modifier.weight(1f), placeholder = "owner/repo · https://…", mono = true,
                            size = ControlSize.Md, error = if (bad) stringResource(R.string.agents_plugin_install_bad) else null, fieldTag = "plugins.identifier",
                        )
                        HubButton(
                            stringResource(R.string.agents_plugin_install), ::install, kind = ButtonKind.Secondary, size = ControlSize.Md,
                            loading = installing, enabled = PluginRules.validIdentifier(identifier) && !installing, modifier = Modifier.testTag("plugins.install"),
                        )
                    }
                    Text(stringResource(R.string.agents_plugin_install_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    ToolErrorNotice(installError)
                    job?.let { j -> PluginJobLine(j) }
                }
            }
            item(key = "error") { ToolErrorNotice(error) }
            items(list.warnings) { InContentDirection(it) { NoticeBox(it, BadgeTone.Warning) } }
            if (list.items.isEmpty()) item(key = "none") {
                EmptyState(stringResource(R.string.agents_plugin_none), body = stringResource(R.string.agents_plugin_none_body), icon = Lucide.Puzzle)
            }
            items(list.items, key = { it.key }) { plugin ->
                PluginRow(
                    plugin, busy = busy == plugin.key,
                    onSwitch = { on -> change(plugin.key) { ops.switchPlugin(plugin.key, on) } },
                    onRemove = { removing = plugin },
                )
            }
        }
    }
    removing?.let { plugin ->
        ConfirmDialog(
            stringResource(R.string.agents_plugin_remove_title, plugin.name), stringResource(R.string.agents_plugin_remove_body),
            stringResource(R.string.agents_plugin_remove), { removing = null; change(plugin.key) { ops.removePlugin(plugin.key) } }, { removing = null }, danger = true,
        )
    }
}

/** Where Hermes's install stands: its progress while it runs, then what came of it. */
@Composable
private fun PluginJobLine(job: Job) {
    when (job.status) {
        JobStatus.SUCCEEDED -> {
            val name = (job.result?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            NoticeBox(stringResource(R.string.agents_plugin_installed, name), BadgeTone.Success, Modifier.testTag("plugins.result"))
        }
        JobStatus.FAILED, JobStatus.CANCELLED ->
            NoticeBox(stringResource(R.string.agents_tools_refused, job.error?.error.orEmpty()), BadgeTone.Danger, Modifier.testTag("plugins.result"))
        else -> NoticeBox(job.progress.message ?: stringResource(R.string.agents_plugin_installing), BadgeTone.Info, Modifier.testTag("plugins.progress"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginRow(plugin: AgentPlugin, busy: Boolean, onSwitch: (Boolean) -> Unit, onRemove: () -> Unit) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("plugin.${plugin.key}"), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plugin.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium)
                    plugin.version?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.align(Alignment.CenterVertically)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Badge(
                        stringResource(
                            when (plugin.source) {
                                AgentPlugin.Source.BUNDLED -> R.string.agents_plugin_source_bundled
                                AgentPlugin.Source.USER -> R.string.agents_plugin_source_user
                                AgentPlugin.Source.EXTERNAL -> R.string.agents_plugin_source_external
                            },
                        ),
                        tone = if (plugin.source == AgentPlugin.Source.BUNDLED) BadgeTone.Neutral else BadgeTone.Accent,
                    )
                    Badge(
                        stringResource(
                            when (plugin.status) {
                                AgentPlugin.Status.ENABLED -> R.string.agents_plugin_status_enabled
                                AgentPlugin.Status.DISABLED -> R.string.agents_plugin_status_disabled
                                AgentPlugin.Status.NOT_ENABLED -> R.string.agents_plugin_status_not_enabled
                            },
                        ),
                        tone = when (plugin.status) {
                            AgentPlugin.Status.ENABLED -> BadgeTone.Success
                            AgentPlugin.Status.DISABLED -> BadgeTone.Warning
                            AgentPlugin.Status.NOT_ENABLED -> BadgeTone.Neutral
                        },
                    )
                }
            }
            if (plugin.removable) {
                HubIconButton(Lucide.Trash, stringResource(R.string.agents_plugin_remove), onRemove, size = 32.dp, iconSize = 16.dp, enabled = !busy, modifier = Modifier.testTag("plugin.${plugin.key}.remove"))
            }
            HubSwitch(plugin.enabled, onSwitch, enabled = plugin.manageable && !busy, modifier = Modifier.testTag("plugin.${plugin.key}.switch"))
        }
        plugin.description?.takeIf { it.isNotBlank() }?.let { d -> InContentDirection(d) { Text(d, fontSize = FontTokens.sizeSm.sp, color = t.textMuted) } }
    }
}

internal val agentPluginsPage = AgentPageEntry("agent_plugins") { agent, profile -> PluginsPage(agent, profile) }
