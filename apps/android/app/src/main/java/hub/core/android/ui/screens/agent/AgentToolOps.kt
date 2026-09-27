package hub.core.android.ui.screens

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.client.api.SessionsApi
import hub.core.client.model.AgentPatch
import hub.core.client.model.AgentsInstallPluginRequest
import hub.core.client.model.Job
import hub.core.client.model.MemoryItem
import hub.core.client.model.MemoryItemWrite
import hub.core.client.model.Skill
import hub.core.client.model.SkillImport
import hub.core.client.model.SkillLibraryPatch
import hub.core.client.model.SkillPatch
import hub.core.client.model.SkillWrite
import java.io.File
import kotlinx.coroutines.delay

/**
 * What the Skills, Memory and Plugins pages and the Agents page's cards change (apps batch 8), apart
 * from the screens so it is tested against a scripted hub. Every call names the profile the pages
 * edit (`X-Hub-Profile`), as [AgentOps] does.
 */
class AgentToolOps(private val apis: () -> HubApis?, val profile: String, val agentId: String) {
    private suspend fun <T> call(block: suspend (HubApis) -> T): Result<T> {
        val api = apis() ?: return Result.failure(HubError(401, "unauthorized", null))
        return hubCall { block(api) }
    }

    // ---------------------------------------------------------------- skills
    suspend fun skills() = call { it.agents.agentsListSkills(profile, agentId) }
    suspend fun skill(key: String) = call { it.agents.agentsGetSkill(profile, agentId, key) }
    suspend fun saveSkill(key: String, content: String) = call { it.agents.agentsPutSkill(profile, agentId, key, SkillWrite(content = content)) }
    suspend fun switchSkill(key: String, on: Boolean) = call { it.agents.agentsUpdateSkill(profile, agentId, key, SkillPatch(enabled = on)) }
    suspend fun pinSkill(key: String, pinned: Boolean) = call { it.agents.agentsUpdateSkill(profile, agentId, key, SkillPatch(pinned = pinned)) }
    suspend fun deleteSkill(key: String) = call { it.agents.agentsDeleteSkill(profile, agentId, key) }
    suspend fun restoreSkill(key: String) = call { it.agents.agentsRestoreSkill(profile, agentId, key) }
    suspend fun setLibrary(on: Boolean) = call { it.agents.agentsUpdateSkillLibrary(profile, agentId, SkillLibraryPatch(enabled = on)) }

    /**
     * Uploads the files as skill packs (`purpose: skill`), installs them, and deletes the uploads
     * whether the import succeeded or not (web `useImportSkills`): the installed skill lives in the
     * agent's folder and does not need them. A failed delete never hides the import's own answer.
     */
    suspend fun importSkills(files: List<File>): Result<List<Skill>> = call { api ->
        val ids = mutableListOf<String>()
        try {
            for (file in files) ids += api.sessions.sessionsUploadAttachment(profile, file, SessionsApi.PurposeSessionsUploadAttachment.SKILL).id
            api.agents.agentsImportSkills(profile, agentId, SkillImport(attachmentIds = ids)).items
        } finally {
            for (id in ids) hubCall { api.sessions.sessionsDeleteAttachment(profile, id) }
        }
    }

    // ---------------------------------------------------------------- memory
    suspend fun memory() = call { it.agents.agentsListMemory(profile, agentId).items }
    suspend fun saveMemory(item: MemoryItem, content: String) =
        call { it.agents.agentsPutMemoryItem(profile, agentId, item.id, MemoryItemWrite(content = content, title = item.title, tags = item.tags, revision = item.revision)) }

    /** One entry of a list removed: the list written again without it (the web's Remove). */
    suspend fun removeEntry(item: MemoryItem, index: Int) = saveMemory(item, MemoryRules.join(MemoryRules.without(MemoryRules.listOf(item), index)))

    // ---------------------------------------------------------------- plugins
    suspend fun plugins() = call { it.agents.agentsListPlugins(profile, agentId) }
    suspend fun switchPlugin(key: String, on: Boolean) = call { it.agents.agentsUpdatePlugin(profile, agentId, key, hub.core.client.model.AgentsUpdatePluginRequest(enabled = on)) }
    suspend fun removePlugin(key: String) = call { it.agents.agentsDeletePlugin(profile, agentId, key) }
    suspend fun installPlugin(identifier: String) = call { it.agents.agentsInstallPlugin(profile, agentId, AgentsInstallPluginRequest(identifier = identifier.trim())).jobId }

    // ---------------------------------------------------------------- the card
    /** Starts what the card's button asked for; answers the job to follow, or null for a change with none. */
    suspend fun act(action: AgentCardRules.Action): Result<String?> = when (action) {
        AgentCardRules.Action.INSTALL -> call { it.agents.agentsInstall(profile, agentId).jobId }
        AgentCardRules.Action.UNINSTALL -> call { it.agents.agentsUninstall(profile, agentId).jobId }
        AgentCardRules.Action.RESTART -> call { it.agents.agentsRestart(profile, agentId).jobId }
        AgentCardRules.Action.CHECK_UPDATE -> call { it.agents.agentsCheckUpdate(profile, agentId).jobId }
        AgentCardRules.Action.UPGRADE -> call { it.agents.agentsUpgrade(profile, agentId).jobId }
        AgentCardRules.Action.AUTO_UPDATE_ON -> call { it.agents.agentsUpdate(profile, agentId, AgentPatch(autoUpdate = true)); null }
        AgentCardRules.Action.AUTO_UPDATE_OFF -> call { it.agents.agentsUpdate(profile, agentId, AgentPatch(autoUpdate = false)); null }
    }

    /**
     * Reads the job again every [everyMs] until it ends, telling [onUpdate] each time (web `useJob`).
     * A read that fails is tried again; the caller's scope ends the wait.
     */
    suspend fun follow(jobId: String, everyMs: Long = 1500, onUpdate: (Job) -> Unit): Job {
        while (true) {
            val job = call { it.jobs.jobsGet(profile, jobId) }.getOrNull()
            if (job != null) {
                onUpdate(job)
                if (AgentCardRules.terminal(job.status)) return job
            }
            delay(everyMs)
        }
    }
}
