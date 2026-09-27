package hub.core.android.ui.screens

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.client.api.AgentsApi
import hub.core.client.model.AgentSettingsPatch
import hub.core.client.model.ChannelModeWrite
import hub.core.client.model.ChannelReplyHeaderWrite
import hub.core.client.model.ChannelSettingsWrite
import hub.core.client.model.ChannelWrite
import hub.core.client.model.HermesWebhookCreate
import hub.core.client.model.HubToolGroupId
import hub.core.client.model.HubToolsPatch
import hub.core.client.model.HubToolsPatchGroupsInner
import hub.core.client.model.McpServerPatch
import hub.core.client.model.McpServerWrite
import hub.core.client.model.PendingWrite
import hub.core.client.model.ProfileSettingsPatch
import kotlinx.serialization.json.JsonElement

/**
 * What the MCP servers, Settings cards and Channels pages change (apps batch 9, Agents II), apart
 * from the screens so it is tested against a scripted hub. Every call names the profile the pages
 * edit (`X-Hub-Profile`), as [AgentOps] and [AgentToolOps] do.
 */
class AgentsTwoOps(private val apis: () -> HubApis?, val profile: String, val agentId: String) {
    private suspend fun <T> call(block: suspend (HubApis) -> T): Result<T> {
        val api = apis() ?: return Result.failure(HubError(401, "unauthorized", null))
        return hubCall { block(api) }
    }

    // ---------------------------------------------------------------- MCP servers
    suspend fun servers() = call { it.agents.agentsListMcpServers(profile, agentId).items }
    suspend fun createServer(name: String, config: Map<String, JsonElement>) =
        call { it.agents.agentsCreateMcpServer(profile, agentId, McpServerWrite(name = name.trim(), transport = McpRules.transportOf(config), config = config, enabled = true)) }

    /** A changed config; the transport too when the form moved between a command and an address. */
    suspend fun saveServer(name: String, config: Map<String, JsonElement>, transport: McpServerPatch.Transport? = null) =
        call { it.agents.agentsUpdateMcpServer(profile, agentId, name, McpServerPatch(transport = transport, config = config)) }
    suspend fun switchServer(name: String, on: Boolean) = call { it.agents.agentsUpdateMcpServer(profile, agentId, name, McpServerPatch(enabled = on)) }
    suspend fun deleteServer(name: String) = call { it.agents.agentsDeleteMcpServer(profile, agentId, name) }
    suspend fun testServer(name: String) = call { it.agents.agentsTestMcpServer(profile, agentId, name) }

    // ---------------------------------------------------------------- the hub's own tools (decision §67)
    suspend fun hubTools() = call { it.agents.agentsGetHubTools(profile, agentId) }
    suspend fun switchHubTools(on: Boolean) = call { it.agents.agentsUpdateHubTools(profile, agentId, HubToolsPatch(enabled = on)) }
    suspend fun setGroup(id: HubToolGroupId, enabled: Boolean? = null, allowWrites: Boolean? = null) =
        call { it.agents.agentsUpdateHubTools(profile, agentId, HubToolsPatch(groups = listOf(HubToolsPatchGroupsInner(id = id, enabled = enabled, allowWrites = allowWrites)))) }

    // ---------------------------------------------------------------- Settings cards
    suspend fun settings() = call { it.agents.agentsGetSettings(profile, agentId) }
    suspend fun setSetting(section: String, key: String, value: JsonElement) =
        call { it.agents.agentsUpdateSettings(profile, agentId, AgentSettingsPatch(section, mapOf(key to value))) }
    suspend fun pendingWrites() = call { it.agents.agentsListPendingWrites(profile, agentId).items }
    suspend fun approveWrite(write: PendingWrite) =
        call { it.agents.agentsApprovePendingWrite(profile, agentId, AgentsApi.WriteKindAgentsApprovePendingWrite.valueOf(write.kind.name), write.id) }
    suspend fun rejectWrite(write: PendingWrite) =
        call { it.agents.agentsRejectPendingWrite(profile, agentId, AgentsApi.WriteKindAgentsRejectPendingWrite.valueOf(write.kind.name), write.id) }
    suspend fun startSignIn() = call { it.agents.agentsStartSignIn(profile, agentId) }
    suspend fun signIn(id: String) = call { it.agents.agentsGetSignIn(profile, agentId, id) }

    /** The profile's id for its slug: compression lives in the profile's settings (decision §57). */
    suspend fun profileId() = call { api -> api.auth.authListProfiles().items.firstOrNull { it.slug == profile }?.id }
    suspend fun compression(profileId: String) = call { it.auth.authGetProfileSettings(profileId).compression }
    suspend fun saveCompression(profileId: String, patch: Map<String, JsonElement>) =
        call { it.auth.authUpdateProfileSettings(profileId, ProfileSettingsPatch(compression = patch)).settings.compression }

    // ---------------------------------------------------------------- Channels
    suspend fun channels() = call { it.agents.agentsListChannels(profile, agentId) }
    suspend fun platforms() = call { it.agents.agentsListChannelPlatforms(profile, agentId).items }
    suspend fun pairing() = call { it.agents.agentsListPairing(profile, agentId) }
    suspend fun switchChannel(platform: String, on: Boolean) = call { it.agents.agentsUpdateChannel(profile, agentId, platform, ChannelWrite(enabled = on)) }
    suspend fun saveChannel(platform: String, write: ChannelWrite) = call { it.agents.agentsUpdateChannel(profile, agentId, platform, write) }
    suspend fun clearChannel(platform: String) = call { it.agents.agentsClearChannel(profile, agentId, platform) }
    suspend fun unlink(platform: String) = call { it.agents.agentsUnlinkChannel(profile, agentId, platform) }
    suspend fun setMode(platform: String, mode: ChannelModeWrite.Mode) = call { it.agents.agentsSetChannelMode(profile, agentId, platform, ChannelModeWrite(mode)) }
    suspend fun setReplyHeader(platform: String, custom: Boolean, title: String) = call {
        it.agents.agentsSetChannelReplyHeader(
            profile, agentId, platform,
            if (custom) ChannelReplyHeaderWrite(ChannelReplyHeaderWrite.Use.CUSTOM, title) else ChannelReplyHeaderWrite(ChannelReplyHeaderWrite.Use.AGENT_NAME),
        )
    }
    suspend fun channelSettings(platform: String) = call { it.agents.agentsGetChannelSettings(profile, agentId, platform) }
    suspend fun saveChannelSettings(platform: String, values: Map<String, JsonElement>) =
        call { it.agents.agentsUpdateChannelSettings(profile, agentId, platform, ChannelSettingsWrite(values)) }
    suspend fun restart() = call { it.agents.agentsRestart(profile, agentId).jobId }

    // ---------------------------------------------------------------- webhooks (decision §97)
    suspend fun webhooks() = call { it.agents.agentsListWebhooks(profile, agentId) }
    suspend fun createWebhook(name: String, prompt: String, description: String, events: String, deliver: String) = call {
        it.agents.agentsCreateWebhook(
            profile, agentId,
            HermesWebhookCreate(
                name = name.trim().lowercase(), prompt = prompt, description = description.trim().ifEmpty { null },
                events = WebhookRules.eventsOf(events), deliver = deliver,
                // No description is sent as an explicit null, as the web does (§114).
                sendNull = if (description.isBlank()) setOf(HermesWebhookCreate.Clearable.DESCRIPTION) else emptySet(),
            ),
        )
    }
    suspend fun deleteWebhook(name: String) = call { it.agents.agentsDeleteWebhook(profile, agentId, name) }
    suspend fun testWebhook(name: String) = call { it.agents.agentsTestWebhook(profile, agentId, name) }
}
