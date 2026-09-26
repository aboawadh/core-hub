package hub.core.android.chat

import hub.core.android.data.HubApis
import hub.core.client.model.AgentCapability
import hub.core.client.model.AgentSettingsPatch
import hub.core.client.model.Choice
import hub.core.client.model.Model
import hub.core.client.model.ModelKind
import hub.core.client.model.RunSteerRequest
import hub.core.client.model.RunSteerResult
import hub.core.client.model.Session
import hub.core.client.model.SessionCompressRequest
import hub.core.client.model.SessionCompression
import hub.core.client.model.SessionForkRequest
import hub.core.client.model.SessionPatch
import hub.core.client.model.SettingsSection
import hub.core.client.model.WorkingDirs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The chat's controls (apps batch 1), from the same contract operations as the web's composer and
 * conversation menu: the model chip (`models.listCatalogue`, `sessions.update`), the approvals chip
 * (the agent's own `approval_mode` / `approvals_mode` setting), a new chat's working folder
 * (`sessions.listWorkingDirs`), and the actions on one chat and one message. Plain rules, tested in
 * ChatControlsTest; iOS `Chat/ChatControls.swift` is their twin.
 */
object ChatControls {
    /** One model the composer offers: [value] is the catalogue's `key` (`<provider>/<model>`), what a session stores. */
    data class ModelOption(val value: String, val label: String, val group: String)

    /** The chat models this profile can run, as the web's composer offers them. */
    fun models(catalogue: List<Model>): List<ModelOption> =
        catalogue.filter { it.kind == ModelKind.CHAT && it.imageOnly != true && it.visible && !it.disabled }
            .map { ModelOption(it.key, it.alias ?: it.model, it.provider) }

    /** The options matching what was typed, in the label, the id or the provider; case does not matter. */
    fun filter(options: List<ModelOption>, query: String): List<ModelOption> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return options
        return options.filter { q in it.label.lowercase() || q in it.value.lowercase() || q in it.group.lowercase() }
    }

    /** The options by provider, in the order the providers first appear. */
    fun groups(options: List<ModelOption>): List<Pair<String, List<ModelOption>>> =
        options.groupBy { it.group }.toList()

    /** The chip's words for the chat's model; `null` is the agent's default. */
    fun modelLabel(value: String?, options: List<ModelOption>): String? {
        if (value.isNullOrEmpty()) return null
        return options.firstOrNull { it.value == value }?.label ?: value.substringAfter('/')
    }

    /** The approval field, whatever the adapter calls it (ACP `approval_mode`, Hermes `approvals_mode`). */
    val APPROVAL_KEYS = listOf("approval_mode", "approvals_mode")

    data class ApprovalField(val section: String, val key: String, val value: String, val options: List<Choice>)

    fun approval(sections: List<SettingsSection>): ApprovalField? {
        for (section in sections) {
            val field = section.fields.firstOrNull { it.key in APPROVAL_KEYS } ?: continue
            // Nothing written is the agent's own default (Hermes: `smart`), not the first option.
            val value = string(field.value) ?: string(field.default) ?: field.options.firstOrNull()?.value ?: "ask"
            return ApprovalField(section.key, field.key, value, field.options)
        }
        return null
    }

    /** Modes that give something away wear the warning mark. */
    fun risky(mode: String): Boolean = mode in setOf("auto_all", "off", "auto_safe", "smart")

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** The part of a path worth reading on a chip: the folder under the root, or its last name. */
    fun shortDir(full: String?, root: String?): String? {
        if (full.isNullOrEmpty()) return null
        if (!root.isNullOrEmpty() && full.startsWith(root)) {
            val rest = full.substring(root.length).trimStart('/', '\\')
            return rest.ifEmpty { "." }
        }
        return full.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: full
    }

    private val GENERATED = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    /** A folder the hub generated is named by a ULID: the chip says «Automatic folder» instead. */
    fun isGenerated(name: String): Boolean = GENERATED.matches(name)

    /** The folder as a person reads it, or `null` for the automatic one. */
    fun folderName(value: String?, root: String?): String? = shortDir(value, root)?.takeUnless(::isGenerated)

    /** A new folder the person named: one name inside the hub's root, never a path. */
    fun newFolder(typed: String): String? {
        val name = typed.trim()
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) return null
        return name
    }

    /** What a rename sends: the trimmed title, at most 200 characters; empty is not a title. */
    fun renameTitle(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }?.take(200)

    /** What «Default model» sends: `model: null` (contract decision §114); a model is sent by its key. */
    fun modelPatch(value: String?): SessionPatch =
        if (value == null) SessionPatch(sendNull = setOf(SessionPatch.Clearable.MODEL)) else SessionPatch(model = value)

    enum class Action { RENAME, AUTO_TITLE, PIN, UNPIN, ARCHIVE, UNARCHIVE, FORK, COMPRESS, EXPORT, DELETE }

    /**
     * The chat's own menu, in order. The global agent's conversation is not a chat of the list
     * (DECISIONS §46): it is never renamed, pinned, archived, forked or deleted from here. A chat
     * with a title can give the naming back to the hub ([Action.AUTO_TITLE]).
     */
    fun actions(pinned: Boolean, archived: Boolean, globalAgent: Boolean, canCompress: Boolean, canExport: Boolean = true, titled: Boolean = false): List<Action> = buildList {
        if (!globalAgent) {
            add(Action.RENAME)
            if (titled) add(Action.AUTO_TITLE)
            add(if (pinned) Action.UNPIN else Action.PIN)
            add(if (archived) Action.UNARCHIVE else Action.ARCHIVE)
            add(Action.FORK)
        }
        if (canCompress) add(Action.COMPRESS)
        if (canExport) add(Action.EXPORT)
        if (!globalAgent) add(Action.DELETE)
    }

    /**
     * The patch an action sends; the other fields stay out (a merge-patch changes only what it names).
     * «Name it automatically» sends `title: null`: the hub renames the chat from its first turn (§26).
     */
    fun patch(action: Action): SessionPatch? = when (action) {
        Action.AUTO_TITLE -> SessionPatch(sendNull = setOf(SessionPatch.Clearable.TITLE))
        Action.PIN -> SessionPatch(pinned = true)
        Action.UNPIN -> SessionPatch(pinned = false)
        Action.ARCHIVE -> SessionPatch(archived = true)
        Action.UNARCHIVE -> SessionPatch(archived = false)
        else -> null
    }

    sealed interface Compression {
        data class Compressed(val before: Int?, val after: Int?) : Compression
        data object Unchanged : Compression
        data object Skipped : Compression
    }

    fun compression(result: SessionCompression): Compression = when (result.status) {
        SessionCompression.Status.COMPRESSED -> Compression.Compressed(result.beforeTokens, result.afterTokens)
        SessionCompression.Status.UNCHANGED -> Compression.Unchanged
        SessionCompression.Status.SKIPPED -> Compression.Skipped
    }

    /** Steering is offered while a reply runs, to an agent that can take it, with words typed. */
    fun canSteer(running: Boolean, text: String, capabilities: List<AgentCapability>): Boolean =
        running && AgentCapability.STEER in capabilities && text.isNotBlank()
}

/** The calls behind the controls, each in the chat's own profile. */
class ChatActions(private val api: HubApis) {
    suspend fun update(id: String, profile: String, patch: SessionPatch): Session = api.sessions.sessionsUpdate(profile, id, patch)

    suspend fun delete(id: String, profile: String) = api.sessions.sessionsDelete(profile, id)

    /** A new chat with the transcript up to [message] (the whole one when null); the original stays. */
    suspend fun fork(id: String, profile: String, message: String? = null): Session =
        api.sessions.sessionsFork(profile, id, SessionForkRequest(atMessageId = message))

    suspend fun compress(id: String, profile: String): SessionCompression =
        api.sessions.sessionsCompress(profile, id, SessionCompressRequest())

    suspend fun steer(id: String, profile: String, run: String, text: String): RunSteerResult =
        api.sessions.sessionsSteerRun(profile, id, run, RunSteerRequest(text))

    /** Every page of the profile's catalogue (200 a page, ten pages at most, as the web). */
    suspend fun catalogue(profile: String): List<ChatControls.ModelOption> {
        val all = mutableListOf<Model>()
        var cursor: String? = null
        repeat(10) {
            val page = api.models.modelsListCatalogue(profile, visible = null, cursor = cursor, limit = 200)
            all += page.items
            cursor = page.nextCursor ?: return ChatControls.models(all)
        }
        return ChatControls.models(all)
    }

    suspend fun approval(profile: String, agentId: String): ChatControls.ApprovalField? =
        ChatControls.approval(api.agents.agentsGetSettings(profile, agentId).sections)

    suspend fun setApproval(profile: String, agentId: String, field: ChatControls.ApprovalField, value: String) {
        api.agents.agentsUpdateSettings(profile, agentId, AgentSettingsPatch(field.section, mapOf(field.key to JsonPrimitive(value))))
    }

    suspend fun workingDirs(profile: String): WorkingDirs = api.sessions.sessionsListWorkingDirs(profile)
}
