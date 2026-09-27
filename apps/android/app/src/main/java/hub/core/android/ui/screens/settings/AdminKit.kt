package hub.core.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import hub.core.android.chat.AttachmentBackend
import hub.core.android.chat.AttachmentUploader
import hub.core.android.data.HubError
import hub.core.android.data.apiBase
import hub.core.android.data.hubCall
import hub.core.android.graph
import hub.core.client.api.AgentsApi
import hub.core.client.api.AuthApi
import hub.core.client.api.DevicesApi
import hub.core.client.api.JobsApi
import hub.core.client.api.SessionsApi
import hub.core.client.model.Agent
import hub.core.client.model.AgentCapability
import hub.core.client.model.AgentStatus
import hub.core.client.model.AttachmentPurpose
import hub.core.client.model.Job
import hub.core.client.model.JobStatus
import hub.core.client.model.ProfileCreate
import hub.core.client.model.ProfileExport
import hub.core.client.model.ProfileImport
import hub.core.client.model.ProfilePatch
import hub.core.client.model.PushProvider
import hub.core.client.model.PushSender
import hub.core.client.model.PushSenderUpdate
import hub.core.client.model.UploadStart
import hub.core.client.model.User
import hub.core.client.model.UserAdminPatch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.io.File

/*
 * The admin pages on the phone (apps batches 12 and 14): People, Profiles, the push senders on
 * Device connections and pairing requests in the pending sheet. The rules are plain functions,
 * apart from the screens, so AdminPagesTest checks them; iOS's AdminPagesRules.swift is the twin.
 */

/** What a row of People offers: the hub's own rules (`auth/users.ts`), asked before the tap. */
object PeopleRules {
    enum class Row {
        /** Somebody else's owner account: nothing to do here, a line says so. */
        OWNER_NOTE,
        /** The owner's own account: the password only. */
        OWN_PASSWORD,
        /** Anyone else: password, role, profiles; disable and delete unless it is the person's own. */
        FULL,
    }

    fun row(user: User, me: String): Row = when {
        user.role.value == "owner" && user.id != me -> Row.OWNER_NOTE
        user.role.value == "owner" -> Row.OWN_PASSWORD
        else -> Row.FULL
    }

    /** Nobody disables or deletes their own account (the hub refuses it). */
    fun canDisableOrDelete(user: User, me: String): Boolean = user.role.value != "owner" && user.id != me

    enum class Reach { EVERY, NONE, LISTED }

    /** Owners and admins enter every profile; a member exactly the ones listed, none when empty. */
    fun reach(user: User): Reach = when {
        user.role.value != "member" -> Reach.EVERY
        user.profiles.isEmpty() -> Reach.NONE
        else -> Reach.LISTED
    }

    /**
     * A member's new list, or an admin made a member with it: the hub refuses a bare role change
     * to member (an admin holds no list), so the list goes with it. Null while nothing is chosen.
     */
    fun profilesPatch(chosen: List<String>, makeMember: Boolean): UserAdminPatch? {
        if (chosen.isEmpty()) return null
        return if (makeMember) UserAdminPatch(role = UserAdminPatch.Role.MEMBER, profiles = chosen) else UserAdminPatch(profiles = chosen)
    }

    /** An admin's `profiles` is every profile, not a choice anyone made: a member-to-be starts empty. */
    fun startingChoice(user: User, makeMember: Boolean): List<String> = if (makeMember) emptyList() else user.profiles

    /** Whose link a messaging account is: the person's name, else the id the hub gave. */
    fun nameOf(userId: String, users: List<User>): String =
        users.firstOrNull { it.id == userId }?.let { it.displayName.ifBlank { it.username } } ?: userId
}

/** Profiles: new, renamed, archived, exported, imported (web `WorkspacesTab` / `ProfileTransfer`). */
object ProfileRules {
    /** The contract's `ProfileSlug`. */
    private val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$")
    /** The contract's `ProfileName`: Hermes's own limit for a display name. */
    const val NAME_MAX = 64
    /** The hub's largest attachment (the contract's `UploadStart.size_bytes` maximum). */
    const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024

    fun slugOk(slug: String): Boolean = SLUG.matches(slug)

    /** A name as a slug: lowercase, dashes, nothing else. An Arabic name gives nothing: typed then. */
    fun suggest(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    /** The slug follows the name until somebody types one. */
    fun followName(slug: String, oldName: String, newName: String): String =
        if (slug.isEmpty() || slug == suggest(oldName)) suggest(newName) else slug

    enum class SlugProblem { BAD, TAKEN }

    fun slugProblem(slug: String, taken: Collection<String>): SlugProblem? = when {
        slug.isEmpty() -> null
        !slugOk(slug) -> SlugProblem.BAD
        slug in taken -> SlugProblem.TAKEN
        else -> null
    }

    /** A new profile, from scratch or as a copy of [cloneFrom] (asked, never defaulted); null until ready. */
    fun create(name: String, slug: String, copy: Boolean, cloneFrom: String?, taken: Collection<String>): ProfileCreate? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > NAME_MAX || slug.isEmpty() || slugProblem(slug, taken) != null) return null
        if (copy && cloneFrom == null) return null
        return ProfileCreate(slug = slug, name = trimmed, cloneFrom = if (copy) cloneFrom else null)
    }

    /** The name as sent: trimmed; null when empty or unchanged. */
    fun rename(current: String, typed: String): ProfilePatch? {
        val name = typed.trim()
        if (name.isEmpty() || name.length > NAME_MAX || name == current) return null
        return ProfilePatch(name = name)
    }

    /** `default` always exists and the hub refuses to archive it. */
    fun canArchive(slug: String): Boolean = slug != "default"

    /** `design-20260924-101500.tar.gz` → `design`: the slug an archive suggests. */
    fun slugFromArchive(fileName: String): String =
        fileName.lowercase().replace(Regex("\\.(tar\\.gz|tgz)$"), "").replace(Regex("-\\d{8}-\\d{6}$"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')

    /** `الرئيسي-20260925-101500.tar.gz` → `الرئيسي`: an export is named after the profile's name. */
    fun nameFromArchive(fileName: String): String =
        fileName.replace(Regex("\\.(tar\\.gz|tgz)$", RegexOption.IGNORE_CASE), "").replace(Regex("-\\d{8}-\\d{6}$"), "")
            .trim().take(NAME_MAX).trim()

    /** The suggestion made free: `design`, else `design-2`, `design-3` … */
    fun freeSlug(base: String, taken: Collection<String>): String {
        if (base.isEmpty()) return ""
        if (base !in taken) return base
        for (n in 2 until 100) {
            val candidate = "${base.take(37)}-$n"
            if (candidate !in taken) return candidate
        }
        return ""
    }

    /** What a chosen archive fills in: the slug (made free) and the name it offers. */
    fun fromArchive(fileName: String, taken: Collection<String>): Pair<String, String> {
        val base = slugFromArchive(fileName)
        val slug = freeSlug(base, taken)
        val named = nameFromArchive(fileName)
        return slug to (if (named.isNotEmpty() && named != base) named else slug)
    }

    fun import(attachmentId: String, slug: String, name: String, replaceDefault: Boolean = false): ProfileImport =
        if (!replaceDefault) {
            ProfileImport(attachmentId = attachmentId, slug = slug, name = name.trim().ifEmpty { null })
        } else {
            // Replacing the default makes no new profile: the hub does not use the slug, which is still sent (decision §116).
            ProfileImport(
                attachmentId = attachmentId,
                slug = if (slug.isNotEmpty() && slugProblem(slug, emptyList()) == null) slug else "imported",
                name = name.trim().ifEmpty { null },
                replaceDefault = true,
            )
        }

    /**
     * The profile an import into the default kept the old default as (`default-backup`, `-2`, …; decision §116).
     * Null for any other import — and for a hub older than the option, which made a new profile instead.
     */
    fun replacedBackup(job: Job?): String? {
        if (job?.status != JobStatus.SUCCEEDED) return null
        val result = job.result ?: return null
        if ((result["replaced_default"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return (result["backup"] as? JsonObject)?.text("slug")
    }

    fun finished(job: Job?): Boolean = job != null && job.status in setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED)

    /** What an export's job answers (contract `auth.exportProfile`). */
    data class Exported(
        val attachmentId: String,
        val name: String,
        val sizeBytes: Long,
        val removed: List<String>,
        val masked: List<String>,
        val providers: Int,
    )

    fun exported(job: Job?): Exported? {
        if (job?.status != JobStatus.SUCCEEDED) return null
        val result = job.result ?: return null
        val id = result.text("attachment_id") ?: return null
        return Exported(
            attachmentId = id,
            name = result.text("name") ?: "$id.tar.gz",
            sizeBytes = (result["size_bytes"] as? JsonPrimitive)?.longOrNull ?: 0,
            removed = result.texts("removed"),
            masked = result.texts("masked"),
            providers = ((result["providers"] as? JsonPrimitive)?.longOrNull ?: 0).toInt(),
        )
    }

    /** The name of the profile an import made. */
    fun importedName(job: Job?): String? =
        if (job?.status == JobStatus.SUCCEEDED) job.result?.let { it.text("name") ?: it.text("slug") } else null

    /** Why an export or import ended without a result: the hub's sentence, else the status. */
    fun failure(job: Job?): String? =
        if (job != null && job.status in setOf(JobStatus.FAILED, JobStatus.CANCELLED)) job.error?.error ?: job.status.value else null

    /** Hermes's own words when it refused (`details.reason = hermes_refused`); null otherwise. */
    fun hermesRefusal(error: HubError?): String? =
        if (error?.reason == "hermes_refused") error.detailMessage?.takeIf { it.isNotBlank() } else null

    /** «3.4 MB» / «120 KB», with Latin digits. */
    fun size(bytes: Long): String =
        if (bytes >= 1_048_576) String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        else "${maxOf(1L, Math.round(bytes / 1024.0))} KB"

    private fun Map<String, JsonElement>.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun Map<String, JsonElement>.texts(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

/**
 * The push senders (Device connections, admin): read from the files Apple and Firebase hand out,
 * checked here before anything is sent; the hub checks again (and signs a test token) on save.
 * A secret is never shown back: a stored one reads `[stored]` and the form never fills it in.
 */
object PushSenderRules {
    const val STORED = "[stored]"
    private val KEY_ID = Regex("^[A-Z0-9]{10}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** Why a service-account file is not the one; null when it is. */
    enum class AccountProblem { NOT_JSON, GOOGLE_SERVICES, NOT_SERVICE_ACCOUNT, MISSING }

    data class AccountCheck(val projectId: String? = null, val problem: AccountProblem? = null) {
        val ok: Boolean get() = problem == null
    }

    fun inspectServiceAccount(text: String): AccountCheck {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return AccountCheck(problem = AccountProblem.NOT_JSON)
        if ("project_info" in parsed && "client" in parsed) return AccountCheck(problem = AccountProblem.GOOGLE_SERVICES)
        val type = parsed["type"]
        if (type != null && (type as? JsonPrimitive)?.contentOrNull != "service_account") return AccountCheck(problem = AccountProblem.NOT_SERVICE_ACCOUNT)
        fun field(key: String) = (parsed[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (listOf("type", "project_id", "client_email", "private_key").any { field(it) == null }) return AccountCheck(problem = AccountProblem.MISSING)
        if (!Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----").containsMatchIn(field("private_key")!!)) return AccountCheck(problem = AccountProblem.MISSING)
        return AccountCheck(projectId = field("project_id"))
    }

    enum class KeyProblem { NOT_A_KEY, NOT_P8 }

    data class KeyCheck(val keyId: String? = null, val problem: KeyProblem? = null) {
        val ok: Boolean get() = problem == null
    }

    /** The key id in `AuthKey_<KEY ID>.p8` (a phone may add ` (1)`), or null for any other name. */
    fun keyIdFromFileName(name: String): String? {
        val id = Regex("^AuthKey_([A-Za-z0-9]+)(?: ?\\(\\d+\\))?\\.p8$").find(name.trim())?.groupValues?.get(1)?.uppercase()
        return id?.takeIf { KEY_ID.matches(it) }
    }

    fun inspectP8(fileName: String?, text: String): KeyCheck {
        if (fileName != null && !fileName.trim().lowercase().endsWith(".p8") && !text.contains("PRIVATE KEY")) {
            return KeyCheck(problem = KeyProblem.NOT_P8)
        }
        if (!Regex("-----BEGIN PRIVATE KEY-----[\\s\\S]+-----END PRIVATE KEY-----").containsMatchIn(text)) return KeyCheck(problem = KeyProblem.NOT_A_KEY)
        return KeyCheck(keyId = fileName?.let(::keyIdFromFileName))
    }

    /** The last characters of an identifier, for «key ending …XXXX». */
    fun ending(value: String, size: Int = 4): String = if (value.length <= size) value else value.takeLast(size)

    /** Set up or changed here; Web Push is the hub's own and the environment wins over Settings. */
    fun editable(sender: PushSender): Boolean = sender.provider != PushProvider.WEBPUSH && sender.source != PushSender.Source.ENVIRONMENT

    fun stored(sender: PushSender): Boolean = sender.source == PushSender.Source.SETTINGS

    /** The form of one sender, as typed. */
    data class Form(
        val enabled: Boolean,
        val serviceAccount: String = "",
        val keyId: String = "",
        val teamId: String = "",
        val bundleId: String = "",
        val sandbox: Boolean = false,
        val privateKey: String = "",
    )

    /** The form opened on [sender]: what is stored and not secret, never a secret. */
    fun form(sender: PushSender): Form = Form(
        enabled = sender.state != PushSender.State.DISABLED,
        keyId = sender.details["key_id"].orEmpty(),
        teamId = sender.details["team_id"].orEmpty(),
        bundleId = sender.details["bundle_id"].orEmpty(),
        sandbox = sender.details["environment"] == "sandbox",
    )

    /** Save is on when the form holds what the hub needs; an empty secret keeps the stored one. */
    fun ready(sender: PushSender, form: Form): Boolean = when (sender.provider) {
        PushProvider.FCM -> if (form.serviceAccount.isBlank()) stored(sender) else inspectServiceAccount(form.serviceAccount).ok
        PushProvider.APNS -> form.keyId.isNotBlank() && form.teamId.isNotBlank() && form.bundleId.isNotBlank() &&
            (if (form.privateKey.isBlank()) stored(sender) else inspectP8(null, form.privateKey).ok)
        PushProvider.WEBPUSH -> false
    }

    /** What is sent: a secret left empty is `[stored]` (FCM) or left out (APNs), both «unchanged». */
    fun body(sender: PushSender, form: Form): PushSenderUpdate = when (sender.provider) {
        PushProvider.FCM -> PushSenderUpdate(enabled = form.enabled, serviceAccount = form.serviceAccount.trim().ifEmpty { STORED })
        else -> PushSenderUpdate(
            enabled = form.enabled,
            keyId = form.keyId.trim(),
            teamId = form.teamId.trim(),
            bundleId = form.bundleId.trim(),
            environment = if (form.sandbox) PushSenderUpdate.Environment.SANDBOX else PushSenderUpdate.Environment.PRODUCTION,
            privateKey = form.privateKey.trim().ifEmpty { null },
        )
    }

    /** What is stored, without a secret: the project; the key's last characters, the team, sandbox. */
    sealed interface Saved {
        data class Project(val id: String) : Saved
        data class Key(val ending: String) : Saved
        data class Team(val id: String) : Saved
        data object Sandbox : Saved
    }

    fun saved(sender: PushSender): List<Saved> {
        if (sender.source == PushSender.Source.NONE || sender.provider == PushProvider.WEBPUSH) return emptyList()
        val d = sender.details
        return if (sender.provider == PushProvider.FCM) listOfNotNull(d["project_id"]?.let { Saved.Project(it) })
        else listOfNotNull(
            d["key_id"]?.let { Saved.Key(ending(it)) },
            d["team_id"]?.let { Saved.Team(it) },
            Saved.Sandbox.takeIf { d["environment"] == "sandbox" },
        )
    }

    enum class Outcome { VALID, INCOMPLETE, REFUSED }

    /** What the hub made of a save: the key looks valid, it is not complete yet, or why not. */
    fun outcome(saved: PushSender): Outcome = when (saved.state) {
        PushSender.State.READY, PushSender.State.DISABLED -> Outcome.VALID
        PushSender.State.NOT_CONFIGURED -> Outcome.INCOMPLETE
        PushSender.State.ERROR -> Outcome.REFUSED
    }

    /** The names a compose file takes instead (shown left to right, whatever the language). */
    fun environmentNames(provider: PushProvider): List<String> = when (provider) {
        PushProvider.FCM -> listOf("COREHUB_FCM_SERVICE_ACCOUNT")
        PushProvider.APNS -> listOf("COREHUB_APNS_KEY_ID", "COREHUB_APNS_TEAM_ID", "COREHUB_APNS_BUNDLE_ID", "COREHUB_APNS_KEY", "COREHUB_APNS_ENVIRONMENT")
        PushProvider.WEBPUSH -> emptyList()
    }
}

/** Senders waiting to pair with the agent's channels: an admin's errand, in the profile they are in. */
object PairingRules {
    private val INSTALLED = setOf(AgentStatus.AVAILABLE, AgentStatus.UPDATING, AgentStatus.LIMITED)

    /** The agent whose channels are asked (the web's `usePendingActions`): installed, on, with channels. */
    fun channelAgent(agents: List<Agent>): Agent? =
        agents.firstOrNull { it.enabled && it.status in INSTALLED && AgentCapability.CHANNELS in it.capabilities }
}

/**
 * The generated clients the admin pages call. `auth.exportProfile` / `auth.importProfile` are
 * `x-scope: global` (no profile parameter), yet the hub records their job in the profile the request
 * names, as the web sends it: that client carries [profile] as a header, so the job, the archive and
 * the upload all live in the profile the person is in.
 */
class AdminApis(hub: String, client: OkHttpClient, profile: String) {
    private val base = apiBase(hub)
    private val scoped = client.newBuilder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header(HubDataApis.PROFILE_HEADER, profile).build()) }
        .build()
    val auth = AuthApi(base, client)
    val authInProfile = AuthApi(base, scoped)
    val devices = DevicesApi(base, client)
    val agents = AgentsApi(base, client)
    val jobs = JobsApi(base, client)
    val sessions = SessionsApi(base, client)
}

/** An archive uploaded as the import's attachment (`purpose: import`), in one piece or in chunks. */
private class ImportBackend(private val sessions: SessionsApi, private val profile: String) : AttachmentBackend {
    override suspend fun oneShot(file: File) =
        sessions.sessionsUploadAttachment(profile, file, SessionsApi.PurposeSessionsUploadAttachment.IMPORT)

    override suspend fun start(name: String, mime: String, sizeBytes: Long) =
        sessions.sessionsStartUpload(profile, UploadStart(name = name, mime = mime, sizeBytes = sizeBytes, purpose = AttachmentPurpose.IMPORT))

    override suspend fun chunk(uploadId: String, offset: Long, body: File) = sessions.sessionsUploadChunk(profile, uploadId, offset.toInt(), body)
    override suspend fun complete(uploadId: String) = sessions.sessionsCompleteUpload(profile, uploadId)
    override suspend fun abort(uploadId: String) = sessions.sessionsAbortUpload(profile, uploadId)
}

/** What the admin pages change, in [profile] where a call is scoped. */
class AdminTwoOps(val profile: String, private val apis: () -> AdminApis) {
    suspend fun users() = hubCall { apis().auth.authListUsers(limit = 200).items }
    suspend fun addUser(body: hub.core.client.model.UserCreate) = hubCall { apis().auth.authCreateUser(body) }
    suspend fun deleteUser(id: String) = hubCall { apis().auth.authDeleteUser(id) }
    suspend fun updateUser(id: String, patch: UserAdminPatch) = hubCall { apis().auth.authUpdateUser(id, patch) }
    suspend fun lockouts() = hubCall { apis().auth.authListLockouts().items }
    /** Every lockout, or the one of [ip]. */
    suspend fun clearLockouts(ip: String? = null) = hubCall { apis().auth.authClearLockouts(ip).cleared }
    suspend fun links() = hubCall { apis().auth.authListChannelIdentities().items }
    suspend fun removeLink(id: String) = hubCall { apis().auth.authDeleteChannelIdentity(id) }

    suspend fun profiles() = hubCall { apis().auth.authListProfiles().items }
    suspend fun createProfile(body: ProfileCreate) = hubCall { apis().auth.authCreateProfile(body) }
    suspend fun renameProfile(id: String, patch: ProfilePatch) = hubCall { apis().auth.authUpdateProfile(id, patch) }
    suspend fun archiveProfile(id: String) = hubCall { apis().auth.authDeleteProfile(id) }
    suspend fun export(id: String, providers: Boolean) = hubCall { apis().authInProfile.authExportProfile(id, ProfileExport(providers = providers)).jobId }
    suspend fun import(body: ProfileImport) = hubCall { apis().authInProfile.authImportProfile(body).jobId }
    suspend fun upload(file: File) = hubCall { AttachmentUploader(ImportBackend(apis().sessions, profile)).upload(file, "application/gzip") }

    /** Reads the job every [everyMs] until it ends, telling [onUpdate] each time; a failed read is tried again. */
    suspend fun follow(jobId: String, everyMs: Long = 1000, onUpdate: (Job) -> Unit = {}): Job {
        while (true) {
            val job = hubCall { apis().jobs.jobsGet(profile, jobId) }.getOrNull()
            if (job != null) {
                onUpdate(job)
                if (ProfileRules.finished(job)) return job
            }
            delay(everyMs)
        }
    }

    suspend fun senders() = hubCall { apis().devices.devicesListPushSenders().items }
    suspend fun saveSender(provider: PushProvider, body: PushSenderUpdate) = hubCall { apis().devices.devicesSetPushSender(provider, body) }
    suspend fun forgetSender(provider: PushProvider) = hubCall { apis().devices.devicesDeletePushSender(provider) }
}

@Composable
internal fun rememberAdminTwoOps(profile: String): AdminTwoOps {
    val graph = LocalContext.current.graph
    val hub = graph.store.current?.hub.orEmpty()
    return remember(profile, hub) {
        val apis by lazy { AdminApis(hub, graph.http.authed, profile) }
        AdminTwoOps(profile) { apis }
    }
}

/** A sender waiting to pair, with where it waits: the profile and the agent whose channel it wrote to. */
data class PendingPairing(val profile: String, val agentId: String, val request: hub.core.client.model.PairingRequest)
