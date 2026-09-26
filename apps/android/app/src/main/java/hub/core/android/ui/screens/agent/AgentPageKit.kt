package hub.core.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hub.core.android.AppLanguage
import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.NoticeBox
import hub.core.client.model.Agent
import hub.core.client.model.AgentPresetWrite
import hub.core.client.model.AgentSettingsPatch
import hub.core.client.model.AgentsUpdatePluginRequest
import hub.core.client.model.ChannelPlatform
import hub.core.client.model.ConfigFile
import hub.core.client.model.ConfigFileWrite
import hub.core.client.model.LocalizedText
import hub.core.client.model.McpServerPatch
import hub.core.client.model.MemoryItem
import hub.core.client.model.MemoryItemWrite
import hub.core.client.model.Schedule
import hub.core.client.model.ScheduleWrite
import hub.core.client.model.SkillPatch
import kotlinx.serialization.json.JsonElement

/*
 * What every agent page shares (docs/clients/phone-pages.md): the calls they make ([AgentOps]),
 * the agent's words in the app's language, the page padding, and the one-line note after an action.
 * Each agent page lives in its own file in this folder with its registry entry.
 */

/**
 * What an agent's pages change (B14), apart from the screens so it is tested against a scripted
 * hub. Every call carries the profile the pages edit (`X-Hub-Profile`), as on iOS and the web.
 */
class AgentOps(private val apis: () -> HubApis?, val profile: String, val agentId: String) {
    private suspend fun <T> call(block: suspend (HubApis) -> T): Result<T> {
        val api = apis() ?: return Result.failure(HubError(401, "unauthorized", null))
        return hubCall { block(api) }
    }

    suspend fun setSkill(key: String, on: Boolean) = call { it.agents.agentsUpdateSkill(profile, agentId, key, SkillPatch(enabled = on)) }
    suspend fun setMcp(name: String, on: Boolean) = call { it.agents.agentsUpdateMcpServer(profile, agentId, name, McpServerPatch(enabled = on)) }
    suspend fun testMcp(name: String) = call { it.agents.agentsTestMcpServer(profile, agentId, name) }
    suspend fun saveMemory(item: MemoryItem, content: String) =
        call { it.agents.agentsPutMemoryItem(profile, agentId, item.id, MemoryItemWrite(content = content, title = item.title, tags = item.tags, revision = item.revision)) }
    suspend fun runJob(job: Schedule) = call { it.schedules.schedulesRunNow(job.profile, job.id) }
    suspend fun pauseJob(job: Schedule, enabled: Boolean) = call { it.schedules.schedulesUpdate(job.profile, job.id, ScheduleWrite(enabled = enabled)) }
    suspend fun setPlugin(key: String, on: Boolean) = call { it.agents.agentsUpdatePlugin(profile, agentId, key, AgentsUpdatePluginRequest(enabled = on)) }
    suspend fun setSetting(section: String, key: String, value: JsonElement) =
        call { it.agents.agentsUpdateSettings(profile, agentId, AgentSettingsPatch(section, mapOf(key to value))) }

    suspend fun presets() = call { it.agents.agentsListPresets(profile, agentId).items }
    suspend fun savePreset(name: String) = call { it.agents.agentsCreatePreset(profile, agentId, AgentPresetWrite(name = name.trim())) }
    suspend fun deletePreset(id: String) = call { it.agents.agentsDeletePreset(profile, agentId, id) }
    suspend fun activatePreset(id: String) = call { it.agents.agentsActivatePreset(profile, agentId, id) }

    suspend fun configFiles() = call { it.agents.agentsListConfigFiles(profile, agentId).items }
    suspend fun configFile(key: String) = call { it.agents.agentsGetConfigFile(profile, agentId, key) }
    suspend fun saveConfigFile(file: ConfigFile, content: String) =
        call { it.agents.agentsPutConfigFile(profile, agentId, file.key, ConfigFileWrite(content = content, revision = file.revision)) }

    suspend fun link(platform: ChannelPlatform, typed: Map<String, String>, allowed: String) =
        call { it.agents.agentsLinkChannel(profile, agentId, platform.platform, ChannelLinks.request(platform, typed, allowed)) }
    suspend fun unlink(platform: String) = call { it.agents.agentsUnlinkChannel(profile, agentId, platform) }
    suspend fun approve(platform: String, requestId: String) = call { it.agents.agentsApprovePairing(profile, agentId, platform, requestId) }
    suspend fun deny(platform: String, requestId: String) = call { it.agents.agentsDenyPairing(profile, agentId, platform, requestId) }
    suspend fun revoke(platform: String, userId: String) = call { it.agents.agentsRevokePairing(profile, agentId, platform, userId) }
}

@Composable
internal fun agentText(text: LocalizedText?): String =
    text?.let { if (LocalContext.current.graph.prefs.effectiveLanguage == AppLanguage.AR) it.ar else it.en }.orEmpty()

@Composable
internal fun rememberOps(agent: Agent, profile: String): AgentOps {
    val context = LocalContext.current
    return remember(agent.id, profile) { AgentOps({ context.graph.store.current?.let(context.graph::apis) }, profile, agent.id) }
}

internal val agentPagePad = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** A line after an action: words of ours in a tone, or the hub's own failure. */
internal data class AgentNote(val text: String? = null, val tone: BadgeTone = BadgeTone.Info, val error: HubError? = null)

@Composable
internal fun AgentNoteView(note: AgentNote?, modifier: Modifier = Modifier) {
    when {
        note == null -> Unit
        note.error != null -> ErrorNotice(note.error, modifier)
        note.text != null -> NoticeBox(note.text, note.tone, modifier)
    }
}

@Composable
internal fun agentApis(): () -> HubApis {
    val context = LocalContext.current
    return { context.graph.apis(context.graph.store.current!!) }
}
