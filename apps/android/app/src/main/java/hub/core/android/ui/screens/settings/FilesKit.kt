package hub.core.android.ui.screens

import hub.core.android.chat.HubFile
import hub.core.android.chat.Progress
import hub.core.android.data.HubError
import hub.core.android.data.apiBase
import hub.core.android.data.hubCall
import hub.core.client.api.KnowledgeApi
import hub.core.client.api.SessionsApi
import hub.core.client.model.Attachment
import hub.core.client.model.Session
import hub.core.client.model.WorkspaceFileEntry
import hub.core.client.model.WorkspaceFileTransfer
import hub.core.client.model.WorkspaceFolder
import hub.core.client.model.WorkspacePathBody
import hub.core.client.model.WorkspaceText
import hub.core.client.model.WorkspaceTextWrite
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/*
 * Settings → Files (apps batches 11 and 13; the web's FilesTool, contract decision §65): the calls the
 * page makes and the plain rules it follows, kept apart from the Compose so FilesPageTest checks them
 * against a scripted hub. iOS's FilesRules.swift is the twin.
 */

object FilesRules {
    /** `a/b` + `c` = `a/b/c`; the top folder joins to the bare name. */
    fun join(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    /** The folder a path is in; `""` for anything at the top. */
    fun parent(path: String): String = path.substringBeforeLast('/', "")

    /** The last segment. */
    fun baseName(path: String): String = path.substringAfterLast('/')

    /** One crumb per folder from the top to [path], each with its own path. */
    fun crumbs(path: String): List<Pair<String, String>> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.mapIndexed { i, name -> name to parts.take(i + 1).joinToString("/") }
    }

    /** What a person typed as a name or a destination, in the contract's form: no leading `/` or `./`. */
    fun normalise(typed: String): String = typed.trim()
        .replace('\\', '/')
        .replace(Regex("/{2,}"), "/")
        .trimStart('/')
        .replace(Regex("^(\\./)+"), "")
        .trimEnd('/')

    /** A new name for something in its own folder: one segment, not `.` or `..`. */
    fun validName(typed: String): Boolean {
        val name = typed.trim()
        return name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
    }

    /** `notes.md` → `notes copy.md`: the suggested name of a copy beside the original. */
    fun copyName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name copy" else "${name.substring(0, dot)} copy${name.substring(dot)}"
    }

    enum class Sort(val id: String) { NAME("name"), NEWEST("newest"), LARGEST("largest") }

    /**
     * What the list shows: the entries whose name holds [query] (any case), folders first, then by
     * [sort] — the name (as the hub lists them), the newest change first, or the largest first.
     */
    fun arrange(entries: List<WorkspaceFileEntry>, query: String, sort: Sort): List<WorkspaceFileEntry> {
        val needle = query.trim().lowercase()
        val shown = if (needle.isEmpty()) entries else entries.filter { needle in it.name.lowercase() }
        val byName = compareBy<WorkspaceFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order = when (sort) {
            Sort.NAME -> byName
            Sort.NEWEST -> compareByDescending<WorkspaceFileEntry> { it.modifiedAt }.then(byName)
            Sort.LARGEST -> compareByDescending<WorkspaceFileEntry> { it.sizeBytes ?: -1L }.then(byName)
        }
        return shown.sortedWith(compareBy<WorkspaceFileEntry> { if (it.kind == WorkspaceFileEntry.Kind.DIRECTORY) 0 else 1 }.then(order))
    }

    /** The entry as a file the chat's opener fetches and opens; null for a folder or a link that leads out. */
    fun hubFile(entry: WorkspaceFileEntry, profile: String): HubFile.Profile? =
        if (entry.kind != WorkspaceFileEntry.Kind.FILE) null
        else HubFile.Profile(profile, entry.path, entry.name, entry.mime, entry.sizeBytes, entry.modifiedAt?.toString())

    /** Text the editor shows as Markdown (Edit/Preview); anything else is plain text in monospace. */
    fun markdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

    /** A size a person reads, in Latin digits in every language, kept left to right inside a sentence. */
    fun size(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val number = if (unit == 0) bytes.toString() else String.format(java.util.Locale.ROOT, "%.1f", value).removeSuffix(".0")
        return "\u2066$number ${units[unit]}\u2069"
    }

    /** How many of the profile's recent chats «Attach to chat» offers besides a new one (as the web). */
    const val RECENT_CHATS = 8

    /**
     * The chats «Attach to chat» offers: this profile's recent ones as the hub lists them, without
     * the global agent's conversation (it is not in the chats list, §46), at most [RECENT_CHATS].
     */
    fun recentChats(sessions: List<Session>): List<Session> = sessions.filter(ChatsList::visible).take(RECENT_CHATS)

    /** A file the phone need not even send: over the hub's upload cap. */
    fun tooLarge(size: Long, maxUploadBytes: Long?): Boolean = maxUploadBytes != null && maxUploadBytes > 0 && size > maxUploadBytes

    /** «12 KB · 27 Sep 2026, 14:05»: the size (a file's) and the last change, when the hub gave them. */
    fun detail(entry: WorkspaceFileEntry, time: java.time.format.DateTimeFormatter): String? =
        listOfNotNull(entry.sizeBytes?.let(::size), entry.modifiedAt?.let(time::format)).joinToString(" · ").ifEmpty { null }

    /** An upload refused because a file of that name is there (`409`, not a text save's `changed`): ask to replace. */
    fun nameTaken(error: Throwable?): Boolean = error is HubError && error.status == 409 && error.reason != "changed"

    enum class PromptKind { NEW_FOLDER, NEW_FILE, RENAME, MOVE, COPY }

    /** What a name or destination typed in a prompt makes the page do. */
    sealed interface Step {
        data object BadName : Step
        data object Same : Step
        data class MakeFolder(val path: String) : Step
        data class WriteNew(val path: String) : Step
        data class Move(val from: String, val to: String) : Step
        data class Copy(val from: String, val to: String) : Step
    }

    /**
     * [typed] in the prompt of [kind], in [folder], for the entry at [path]: a new folder or file may
     * name a path under the folder (`a/b`); a rename is one name; a move or copy names its whole
     * destination. Nothing changes when the name or place is the same.
     */
    fun promptStep(kind: PromptKind, typed: String, folder: String, path: String): Step {
        val clean = normalise(typed)
        val usable = clean.isNotEmpty() && clean.split('/').none { it == "." || it == ".." }
        return when (kind) {
            PromptKind.NEW_FOLDER -> if (usable) Step.MakeFolder(join(folder, clean)) else Step.BadName
            PromptKind.NEW_FILE -> if (usable) Step.WriteNew(join(folder, clean)) else Step.BadName
            PromptKind.RENAME -> when {
                !validName(typed) -> Step.BadName
                typed.trim() == baseName(path) -> Step.Same
                else -> Step.Move(path, join(parent(path), typed.trim()))
            }
            PromptKind.MOVE -> when {
                !usable -> Step.BadName
                clean == path -> Step.Same
                else -> Step.Move(path, clean)
            }
            PromptKind.COPY -> if (!usable || clean == path) Step.BadName else Step.Copy(path, clean)
        }
    }

    /** Why the hub refused, as the page says it in one line. */
    enum class Refusal { NOT_ALLOWED, EXISTS, CHANGED, TOO_LARGE, NOT_TEXT, OUTSIDE, ROOT, INTO_ITSELF, GONE, OTHER }

    fun refusal(error: HubError): Refusal = when {
        error.status == 403 -> Refusal.NOT_ALLOWED
        error.status == 409 && error.reason == "changed" -> Refusal.CHANGED
        error.status == 409 -> Refusal.EXISTS
        error.status == 413 -> Refusal.TOO_LARGE
        error.status == 415 -> Refusal.NOT_TEXT
        error.status == 404 -> Refusal.GONE
        error.reason == "root" -> Refusal.ROOT
        error.reason == "into_itself" -> Refusal.INTO_ITSELF
        error.reason in setOf("absolute", "outside_root", "symlink_outside", "invalid") -> Refusal.OUTSIDE
        else -> Refusal.OTHER
    }
}

/** The generated client these calls use: the knowledge operations, in the page's profile. */
class FilesApis(hub: String, private val client: OkHttpClient) {
    private val base = apiBase(hub)
    val knowledge = KnowledgeApi(base, client)
    val sessions = SessionsApi(base, client)

    /** The same client, telling [onProgress] how much of a request's body has gone. */
    fun uploading(onProgress: (Progress) -> Unit): KnowledgeApi = KnowledgeApi(
        base,
        client.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val body = request.body ?: return@addNetworkInterceptor chain.proceed(request)
            chain.proceed(request.newBuilder().method(request.method, CountingRequestBody(body, onProgress)).build())
        }.build(),
    )
}

class FilesOps(private val profile: String, private val apis: () -> FilesApis) {
    suspend fun folder(path: String): Result<WorkspaceFolder> = hubCall { apis().knowledge.knowledgeListWorkspaceFiles(profile, path) }

    suspend fun mkdir(path: String): Result<WorkspaceFileEntry> =
        hubCall { apis().knowledge.knowledgeCreateWorkspaceFolder(profile, WorkspacePathBody(path)) }

    suspend fun move(from: String, to: String): Result<WorkspaceFileEntry> =
        hubCall { apis().knowledge.knowledgeMoveWorkspaceFile(profile, WorkspaceFileTransfer(from = from, to = to)) }

    suspend fun copy(from: String, to: String): Result<WorkspaceFileEntry> =
        hubCall { apis().knowledge.knowledgeCopyWorkspaceFile(profile, WorkspaceFileTransfer(from = from, to = to)) }

    suspend fun delete(path: String): Result<Unit> = hubCall { apis().knowledge.knowledgeDeleteWorkspaceFile(profile, path) }

    suspend fun readText(path: String): Result<WorkspaceText> = hubCall { apis().knowledge.knowledgeReadWorkspaceText(profile, path) }

    /** Saves [content] against the [etag] read (null makes a new file); a change on disk since is refused. */
    suspend fun writeText(path: String, content: String, etag: String?): Result<WorkspaceText> =
        hubCall { apis().knowledge.knowledgeWriteWorkspaceText(profile, WorkspaceTextWrite(path = path, content = content, etag = etag)) }

    /** Puts [file] (named as it should be named there) into [folder]; [overwrite] replaces a file of that name. */
    suspend fun upload(folder: String, file: File, overwrite: Boolean = false, onProgress: (Progress) -> Unit = {}): Result<WorkspaceFileEntry> =
        hubCall { apis().uploading(onProgress).knowledgeUploadWorkspaceFile(profile, file, folder, overwrite.takeIf { it }) }

    /**
     * The file as an attachment of this profile, copied on the hub (`knowledge.attachWorkspaceFile`):
     * nothing is fetched to the phone or sent back; a chat's composer takes it ready ([hub.core.android.chat.AttachmentHandOff]).
     */
    suspend fun attach(path: String): Result<Attachment> =
        hubCall { apis().knowledge.knowledgeAttachWorkspaceFile(profile, WorkspacePathBody(path)) }

    /** The chats «Attach to chat» offers ([FilesRules.recentChats]). */
    suspend fun recentChats(): Result<List<Session>> = hubCall {
        FilesRules.recentChats(apis().sessions.sessionsList(profile, archived = SessionsApi.ArchivedSessionsList.FALSE, limit = 20).items)
    }

    /** A folder as one zip in [into], named after it (the top folder after the profile). */
    suspend fun zip(path: String, into: File): Result<File> = hubCall {
        val downloaded = apis().knowledge.knowledgeDownloadWorkspaceFolder(profile, path)
        withContext(Dispatchers.IO) {
            val name = (if (path.isEmpty()) profile else FilesRules.baseName(path)).replace('/', '_').ifBlank { "files" }
            val target = File(into, "$name.zip")
            into.mkdirs()
            try {
                downloaded.copyTo(target, overwrite = true)
            } finally {
                downloaded.delete()
            }
            target
        }
    }
}

/** A request body that tells how much of it has been written. */
private class CountingRequestBody(private val body: RequestBody, private val onProgress: (Progress) -> Unit) : RequestBody() {
    override fun contentType(): MediaType? = body.contentType()
    override fun contentLength(): Long = body.contentLength()
    override fun isOneShot(): Boolean = body.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().takeIf { it >= 0 }
        var sent = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(Progress(sent, total))
            }
        }.buffer()
        body.writeTo(counting)
        counting.flush()
    }
}
