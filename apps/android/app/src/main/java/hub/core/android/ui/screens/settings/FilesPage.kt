package hub.core.android.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.chat.FileDownloads
import hub.core.android.chat.FileKinds
import hub.core.android.chat.FileOpen
import hub.core.android.chat.HubFile
import hub.core.android.chat.Progress
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.nav.Route
import hub.core.android.phone.Share
import hub.core.android.ui.components.AttachmentFiles
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormSheet
import hub.core.android.ui.components.ListPage
import hub.core.android.ui.components.ListScaffold
import hub.core.android.ui.components.PagedList
import hub.core.android.ui.components.PickedFiles
import hub.core.android.ui.components.RowAction
import hub.core.android.ui.components.RowActionsButton
import hub.core.android.ui.components.TextEditorSheet
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberOpener
import hub.core.android.ui.components.rememberPagedList
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.IconKind
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.WorkspaceFileEntry
import hub.core.client.model.WorkspaceFolder
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Files (apps batches 11 and 13; the web's FilesTool, contract decision §65): the working
 * folder of the profile in the top chip, for its owner and admins. Browse by the folder trail, search
 * and sort the folder; open a file the way a chat's file opens (pictures drawn, documents in the
 * phone's viewer, sound and video played), save it to the phone, share it, or attach it to a new
 * chat; upload files and photos with their progress; make a folder or a text file, rename, move,
 * copy, delete (after a confirm); edit text with the save-conflict check. iOS's FilesPage.swift is
 * the twin.
 */
@Composable
private fun FilesPage(ctx: SettingsPageContext) {
    val profile = ctx.session.profile
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val ops = rememberFilesOps(profile)
    val profiles by ctx.shell.profiles.collectAsState()
    val profileName = profiles.firstOrNull { it.slug == profile }?.name ?: profile
    val words = rememberFilesWords()

    var folder by rememberSaveable(profile) { mutableStateOf("") }
    var typed by rememberSaveable(profile, folder) { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FilesRules.Sort.NAME) }
    var shown by remember(profile, folder) { mutableStateOf<WorkspaceFolder?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val arrangeBy by rememberUpdatedState(typed to sort)
    // The folder as read, kept so a search or a new order re-arranges it without asking again.
    val source = remember(profile, folder) { FolderSource() }
    val list = rememberPagedList<WorkspaceFileEntry>(profile, folder, key = { it.path }) { _ ->
        val answer = source.take() ?: ops.folder(folder).getOrElse { throw words.plain(it) }
        source.cached = answer
        shown = answer
        val (query, order) = arrangeBy
        ListPage(FilesRules.arrange(answer.propertyEntries, query, order), null)
    }
    LaunchedEffect(list, typed, sort) { if (source.cached != null) source.rearrange(list) }
    fun reload() {
        source.cached = null
        list.refresh()
    }
    fun open(path: String) {
        folder = path
        problem = null
    }
    BackHandler(enabled = folder.isNotEmpty()) { open(FilesRules.parent(folder)) }

    // ------------------------------------------------------------- opening files

    val opener = rememberOpener(profile)
    val downloads = graph.files
    val states by downloads.states.collectAsState()
    var waiting by remember { mutableStateOf<Pair<HubFile, (File) -> Unit>?>(null) }
    LaunchedEffect(states, waiting) {
        val (file, then) = waiting ?: return@LaunchedEffect
        when (val s = states[file.cacheKey] ?: downloads.state(file)) {
            is FileDownloads.State.Ready -> {
                waiting = null
                then(s.file)
            }
            is FileDownloads.State.Failed -> {
                waiting = null
                problem = words.say(s.error)
            }
            is FileDownloads.State.Loading -> {}
            FileDownloads.State.Idle -> waiting = null
        }
    }

    /** The file on the phone first (fetched once, with its progress on its row), then [then]. */
    fun withLocal(entry: WorkspaceFileEntry, then: (File) -> Unit) {
        val file = FilesRules.hubFile(entry, profile) ?: return
        val now = downloads.state(file)
        if (now is FileDownloads.State.Ready && now.file.isFile) return then(now.file)
        waiting = file to then
        downloads.start(file, profile)
    }
    val savedText = stringResource(R.string.files_saved)
    val notSavedText = stringResource(R.string.files_save_failed)
    val openFailed = stringResource(R.string.attach_open_failed)
    fun saveToPhone(entry: WorkspaceFileEntry) = withLocal(entry) { local ->
        val ok = AttachmentFiles.save(context, local, entry.name, entry.mime)
        Toast.makeText(context, if (ok) savedText else notSavedText, Toast.LENGTH_SHORT).show()
    }
    fun share(entry: WorkspaceFileEntry) = withLocal(entry) { local ->
        if (!AttachmentFiles.share(context, local, entry.mime)) Toast.makeText(context, openFailed, Toast.LENGTH_LONG).show()
    }
    fun attach(entry: WorkspaceFileEntry) = withLocal(entry) { local ->
        // Handed to a new chat the way another app's share is: it lands in the composer's tray and uploads there.
        val copy = File(File(context.cacheDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }, local.name)
        local.copyTo(copy, overwrite = true)
        val picture = FileKinds.openAs(entry.name, entry.mime) == FileOpen.PICTURE
        graph.sharedFiles.value = graph.sharedFiles.value + Share.SharedFile(copy, picture, if (picture) PickedFiles.thumbnail(copy) else null)
        ctx.onOpen(Route.NewChat)
    }
    var zipping by remember { mutableStateOf<String?>(null) }
    fun shareZip(path: String, name: String) {
        zipping = name
        scope.launch {
            ops.zip(path, File(context.cacheDir, "attachments/zips/${UUID.randomUUID()}"))
                .onSuccess { if (!AttachmentFiles.share(context, it, "application/zip")) problem = openFailed }
                .onFailure { problem = words.say(it) }
            zipping = null
        }
    }

    // ------------------------------------------------------------- the text editor

    var editing by remember { mutableStateOf<Editing?>(null) }
    fun edit(path: String) {
        scope.launch { ops.readText(path).onSuccess { editing = Editing(path, it.content, it.etag) }.onFailure { problem = words.say(it) } }
    }

    // ------------------------------------------------------------- uploads

    val uploads = remember(profile) { MutableStateFlow<List<Upload>>(emptyList()) }
    val uploading by uploads.collectAsState()
    var replacing by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    fun uploadAll(into: String, picked: List<Pair<String, File?>>) = scope.launch {
        var done = 0
        for ((name, file) in picked) {
            val id = UUID.randomUUID().toString()
            val max = shown?.limits?.maxUploadBytes
            if (file == null) {
                uploads.update { it + Upload(id, name, null, words.unreadable(name)) }
                continue
            }
            if (FilesRules.tooLarge(file.length(), max)) {
                uploads.update { it + Upload(id, name, null, words.tooLargeFor(name, max ?: 0)) }
                file.delete()
                continue
            }
            uploads.update { it + Upload(id, name, Progress(0, file.length()), null) }
            var last = 0L
            val progress: (Progress) -> Unit = { p ->
                // At most every 64 KB, or the end: enough for a bar, not a storm of states.
                if (p.read - last >= 65_536 || p.read == p.total || p.read < last) {
                    last = p.read
                    uploads.update { list -> list.map { if (it.id == id) it.copy(progress = p) else it } }
                }
            }
            var result = ops.upload(into, file, onProgress = progress)
            if (FilesRules.nameTaken(result.exceptionOrNull())) {
                val answer = CompletableDeferred<Boolean>()
                replacing = name to answer
                if (answer.await()) {
                    result = ops.upload(into, file, overwrite = true, onProgress = progress)
                } else {
                    uploads.update { list -> list.filterNot { it.id == id } }
                    withContext(Dispatchers.IO) { file.delete() }
                    continue
                }
            }
            withContext(Dispatchers.IO) { file.delete() }
            result
                .onSuccess {
                    done++
                    uploads.update { list -> list.filterNot { it.id == id } }
                }
                .onFailure { e -> uploads.update { list -> list.map { if (it.id == id) it.copy(progress = null, error = words.say(e)) else it } } }
        }
        if (done > 0) {
            Toast.makeText(context, words.uploaded(done), Toast.LENGTH_SHORT).show()
            if (into == folder) reload()
        }
    }
    fun readPicked(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val into = folder
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.map { uri -> (PickedFiles.displayName(context, uri) ?: "file") to PickedFiles.copy(context, uri) }
            }
            uploadAll(into, picked)
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { readPicked(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { readPicked(it) }

    // ------------------------------------------------------------- the actions of a row

    var prompt by remember { mutableStateOf<Prompt?>(null) }
    val deleting = rememberConfirmDelete<WorkspaceFileEntry>()

    fun actions(entry: WorkspaceFileEntry): List<RowAction> = buildList {
        val file = entry.kind == WorkspaceFileEntry.Kind.FILE
        val dir = entry.kind == WorkspaceFileEntry.Kind.DIRECTORY
        if (file) add(RowAction(words.open, Lucide.ExternalLink) { FilesRules.hubFile(entry, profile)?.let { opener.open(it, scope) } })
        if (dir) add(RowAction(words.open, Lucide.Folder) { open(entry.path) })
        if (file && entry.editable) add(RowAction(words.edit, Lucide.Pencil) { edit(entry.path) })
        if (file && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(RowAction(words.saveToPhone, Lucide.Download) { saveToPhone(entry) })
        if (file) add(RowAction(words.share, Lucide.Share2) { share(entry) })
        if (dir) add(RowAction(words.shareZip, Lucide.Share2) { shareZip(entry.path, entry.name) })
        if (file) add(RowAction(words.attach, Lucide.Paperclip) { attach(entry) })
        add(RowAction(words.rename, Lucide.SquarePen) { prompt = Prompt.Rename(entry) })
        add(RowAction(words.move, Lucide.FolderInput) { prompt = Prompt.Move(entry) })
        if (entry.kind != WorkspaceFileEntry.Kind.LINK) add(RowAction(words.copy, Lucide.Copy) { prompt = Prompt.Copy(entry) })
        add(RowAction(words.delete, Lucide.Trash, danger = true) { deleting.ask(entry) })
    }

    fun activate(entry: WorkspaceFileEntry) {
        when (entry.kind) {
            WorkspaceFileEntry.Kind.DIRECTORY -> open(entry.path)
            WorkspaceFileEntry.Kind.LINK -> problem = words.linkOutside
            // Text opens where it can be read and edited; anything else the way a chat's file opens.
            WorkspaceFileEntry.Kind.FILE -> if (entry.editable) edit(entry.path) else FilesRules.hubFile(entry, profile)?.let { opener.open(it, scope) }
        }
    }

    ListScaffold(
        list, key = { it.path },
        query = typed, onQuery = { typed = it }, searchHint = words.search,
        emptyTitle = words.empty, emptyBody = words.emptyBody, emptyIcon = Lucide.Folder,
        swipeAction = { entry -> RowAction(words.delete, Lucide.Trash, danger = true) { deleting.ask(entry) } },
        header = {
            FilesHeader(
                profileName = profileName, folder = folder, sort = sort, busy = zipping, problem = problem,
                uploads = uploading, folderInfo = shown,
                onOpen = ::open, onSort = { sort = it },
                onUploadFiles = { pickFiles.launch(arrayOf("*/*")) },
                onUploadPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onNewFolder = { prompt = Prompt.NewFolder }, onNewFile = { prompt = Prompt.NewFile },
                onShareZip = { shareZip(folder, if (folder.isEmpty()) profileName else FilesRules.baseName(folder)) },
                onDismissProblem = { problem = null },
                onDismissUpload = { id -> uploads.update { list -> list.filterNot { it.id == id } } },
            )
        },
        tag = "files.list",
    ) { entry ->
        val file = FilesRules.hubFile(entry, profile)
        FileEntryRow(
            entry, words, file?.let { states[it.cacheKey] }, actions(entry),
            onCancel = { file?.let(downloads::cancel) },
            onClick = { activate(entry) },
        )
    }

    // ------------------------------------------------------------- sheets and dialogs

    prompt?.let { p ->
        val text = when (p) {
            Prompt.NewFolder -> PromptText(words.newFolder, "", words.nameHelp, words.create)
            Prompt.NewFile -> PromptText(words.newFile, "", words.nameHelp, words.create)
            is Prompt.Rename -> PromptText(words.renameTitle(p.entry.name), p.entry.name, words.nameHelp, words.rename)
            is Prompt.Move -> PromptText(words.moveTitle(p.entry.name), p.entry.path, words.destinationHint, words.move)
            is Prompt.Copy -> PromptText(
                words.copyTitle(p.entry.name), FilesRules.join(FilesRules.parent(p.entry.path), FilesRules.copyName(p.entry.name)),
                words.destinationHint, words.copy,
            )
        }
        val destination = p is Prompt.Move || p is Prompt.Copy
        FormSheet(
            title = text.title,
            fields = listOf(FormField("value", if (destination) words.destination else words.name, required = true, help = text.help, mono = destination)),
            initial = mapOf("value" to text.initial),
            onDismiss = { prompt = null },
            saveLabel = text.save,
            tag = "files.prompt",
            onSave = { values ->
                val typedValue = values["value"].orEmpty()
                val result: Result<Any> = when (val step = FilesRules.promptStep(p.kind, typedValue, folder, p.path)) {
                    FilesRules.Step.BadName -> Result.failure(Exception(words.badName))
                    FilesRules.Step.Same -> Result.success(Unit)
                    is FilesRules.Step.MakeFolder -> ops.mkdir(step.path)
                    is FilesRules.Step.WriteNew -> {
                        editing = Editing(step.path, "", null)
                        Result.success(Unit)
                    }
                    is FilesRules.Step.Move -> ops.move(step.from, step.to).onSuccess {
                        if (p is Prompt.Move) Toast.makeText(context, words.moved(step.to), Toast.LENGTH_SHORT).show()
                    }
                    is FilesRules.Step.Copy -> ops.copy(step.from, step.to).onSuccess {
                        Toast.makeText(context, words.copied(step.to), Toast.LENGTH_SHORT).show()
                    }
                }
                result.map { if (p !is Prompt.NewFile) reload() }.recoverCatching { throw words.plain(it) }
            },
        )
    }

    editing?.let { e ->
        val name = FilesRules.baseName(e.path)
        TextEditorSheet(
            title = if (e.etag == null) words.newFileTitle(name) else words.editTitle(name),
            initial = e.text,
            onDismiss = { editing = null },
            onSave = { text ->
                ops.writeText(e.path, text, e.etag).onSuccess {
                    Toast.makeText(context, words.savedText(name), Toast.LENGTH_SHORT).show()
                    reload()
                }.recoverCatching { if (it is HubError && it.reason == "changed") throw it else throw words.plain(it) }
            },
            markdown = FilesRules.markdown(name),
            subtitle = e.path,
            onReload = {
                scope.launch { ops.readText(e.path).onSuccess { editing = Editing(e.path, it.content, it.etag) }.onFailure { problem = words.say(it) } }
            },
            tag = "files.editor",
        )
    }

    replacing?.let { (name, answer) ->
        ConfirmDialog(
            title = words.replaceTitle(name), body = words.replaceBody, confirm = words.replace,
            onConfirm = {
                replacing = null
                answer.complete(true)
            },
            onDismiss = {
                replacing = null
                answer.complete(false)
            },
            danger = true, dismiss = words.skip,
        )
    }

    ConfirmDeleteDialog(
        deleting,
        title = { stringResource(R.string.files_page_delete_title, it.name) },
        onDelete = { ops.delete(it.path).recoverCatching { e -> throw words.plain(e) } },
        onDeleted = {
            Toast.makeText(context, words.deleted(it.name), Toast.LENGTH_SHORT).show()
            reload()
        },
        body = stringResource(
            if (deleting.pending?.kind == WorkspaceFileEntry.Kind.DIRECTORY) R.string.files_page_delete_folder_body else R.string.files_page_delete_body,
        ),
    )
}

/** The folder as last read; a search or a new order re-arranges it without reading it again. */
private class FolderSource {
    var cached: WorkspaceFolder? = null
    private var reuse = false

    fun take(): WorkspaceFolder? = if (reuse) cached.also { reuse = false } else null

    fun rearrange(list: PagedList<WorkspaceFileEntry>) {
        reuse = true
        list.refresh()
    }
}

/** A text file open in the editor: [etag] null is a new file. */
private data class Editing(val path: String, val text: String, val etag: String?)

/** One file on its way up: its progress, or why it did not go. */
private data class Upload(val id: String, val name: String, val progress: Progress?, val error: String?)

private sealed interface Prompt {
    val kind: FilesRules.PromptKind
    val path: String get() = ""

    data object NewFolder : Prompt {
        override val kind get() = FilesRules.PromptKind.NEW_FOLDER
    }

    data object NewFile : Prompt {
        override val kind get() = FilesRules.PromptKind.NEW_FILE
    }

    data class Rename(val entry: WorkspaceFileEntry) : Prompt {
        override val kind get() = FilesRules.PromptKind.RENAME
        override val path get() = entry.path
    }

    data class Move(val entry: WorkspaceFileEntry) : Prompt {
        override val kind get() = FilesRules.PromptKind.MOVE
        override val path get() = entry.path
    }

    data class Copy(val entry: WorkspaceFileEntry) : Prompt {
        override val kind get() = FilesRules.PromptKind.COPY
        override val path get() = entry.path
    }
}

private data class PromptText(val title: String, val initial: String, val help: String, val save: String)

/** The trail, the page's own actions, what is uploading, and anything the page has to say. */
@Composable
private fun FilesHeader(
    profileName: String,
    folder: String,
    sort: FilesRules.Sort,
    busy: String?,
    problem: String?,
    uploads: List<Upload>,
    folderInfo: WorkspaceFolder?,
    onOpen: (String) -> Unit,
    onSort: (FilesRules.Sort) -> Unit,
    onUploadFiles: () -> Unit,
    onUploadPhotos: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onShareZip: () -> Unit,
    onDismissProblem: () -> Unit,
    onDismissUpload: (String) -> Unit,
) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.files_page_intro, profileName), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        Crumbs(profileName, folder, onOpen)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            var picking by remember { mutableStateOf(false) }
            Box {
                HubButton(
                    stringResource(R.string.files_page_upload), { picking = true }, size = ControlSize.Sm, icon = Lucide.Upload,
                    modifier = Modifier.testTag("files.upload"),
                )
                HubMenu(picking, { picking = false }) {
                    MenuItem(stringResource(R.string.files_page_upload_files), { picking = false; onUploadFiles() }, icon = Lucide.File)
                    MenuItem(stringResource(R.string.files_page_upload_photos), { picking = false; onUploadPhotos() }, icon = Lucide.Image)
                }
            }
            HubButton(
                stringResource(R.string.files_page_new_folder), onNewFolder, kind = ButtonKind.Secondary, size = ControlSize.Sm,
                icon = Lucide.FolderPlus, modifier = Modifier.testTag("files.new_folder"),
            )
            HubButton(
                stringResource(R.string.files_page_new_file), onNewFile, kind = ButtonKind.Secondary, size = ControlSize.Sm,
                icon = Lucide.FilePlus, modifier = Modifier.testTag("files.new_file"),
            )
            var sorting by remember { mutableStateOf(false) }
            Box {
                HubIconButton(
                    Lucide.ArrowUpDown, stringResource(R.string.files_page_sort), { sorting = true }, kind = IconKind.Soft, size = 32.dp, iconSize = 16.dp,
                    modifier = Modifier.testTag("files.sort"),
                )
                HubMenu(sorting, { sorting = false }) {
                    FilesRules.Sort.entries.forEach { s ->
                        MenuItem(sortLabel(s), { sorting = false; onSort(s) }, checked = s == sort)
                    }
                }
            }
            if (busy != null) {
                Spinner(16.dp, t.textMuted)
            } else {
                HubIconButton(
                    Lucide.Share2, stringResource(R.string.files_page_share_zip), onShareZip, kind = IconKind.Soft, size = 32.dp, iconSize = 16.dp,
                    modifier = Modifier.testTag("files.zip"),
                )
            }
        }
        problem?.let {
            NoticeBox(it, BadgeTone.Danger, Modifier.testTag("files.problem")) {
                HubButton(stringResource(R.string.close), onDismissProblem, kind = ButtonKind.Ghost, size = ControlSize.Sm)
            }
        }
        uploads.forEach { upload -> UploadRow(upload) { onDismissUpload(upload.id) } }
        if (folderInfo?.truncated == true) NoticeBox(stringResource(R.string.files_page_truncated), BadgeTone.Warning)
        folderInfo?.limits?.let { l ->
            Text(
                stringResource(R.string.files_page_limits, FilesRules.size(l.maxUploadBytes), FilesRules.size(l.maxEditBytes), FilesRules.size(l.maxArchiveBytes)),
                fontSize = FontTokens.sizeXs.sp, color = t.textFaint,
            )
        }
    }
}

@Composable
private fun sortLabel(sort: FilesRules.Sort): String = stringResource(
    when (sort) {
        FilesRules.Sort.NAME -> R.string.files_page_sort_name
        FilesRules.Sort.NEWEST -> R.string.files_page_sort_newest
        FilesRules.Sort.LARGEST -> R.string.files_page_sort_largest
    },
)

/** The folder trail: the profile's top folder, then each folder down to this one; «Up» when inside one. */
@Composable
private fun Crumbs(profileName: String, folder: String, onOpen: (String) -> Unit) {
    val t = LocalTokens.current
    val trail = listOf(profileName to "") + FilesRules.crumbs(folder)
    val crumbsLabel = stringResource(R.string.files_page_breadcrumb)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("files.crumbs")
            .semantics { contentDescription = crumbsLabel },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (folder.isNotEmpty()) {
            HubIconButton(
                Lucide.ArrowUp, stringResource(R.string.files_page_up), { onOpen(FilesRules.parent(folder)) }, size = 32.dp, iconSize = 16.dp,
                modifier = Modifier.testTag("files.up"),
            )
        }
        trail.forEachIndexed { i, (name, path) ->
            if (i > 0) LucideIcon(Lucide.ChevronRight, null, size = 14.dp, tint = t.textFaint)
            val last = i == trail.lastIndex
            Text(
                name, fontSize = FontTokens.sizeSm.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) t.text else t.accent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .let { if (last) it else it.clickable { onOpen(path) } }
                    .padding(horizontal = 6.dp, vertical = 4.dp).testTag("files.crumb.$i"),
            )
        }
    }
}

/** One file going up: its name and progress bar, or why it failed (with a close). */
@Composable
private fun UploadRow(upload: Upload, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("files.uploading"), padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIcon(
                if (upload.error != null) Lucide.TriangleAlert else Lucide.Upload, null, size = 16.dp,
                tint = if (upload.error != null) t.danger else t.textMuted,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (upload.error == null) stringResource(R.string.files_page_uploading, upload.name) else upload.name,
                    fontSize = FontTokens.sizeSm.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
                upload.error?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                upload.progress?.let { p -> ProgressLine(p) }
            }
            if (upload.error != null) HubIconButton(Lucide.X, stringResource(R.string.close), onDismiss, size = 28.dp, iconSize = 14.dp)
        }
    }
}

/** «40% · 4 MB of 10 MB» and its bar. */
@Composable
private fun ProgressLine(p: Progress) {
    val t = LocalTokens.current
    val f = p.fraction
    Text(
        if (f != null) stringResource(R.string.files_progress, (f * 100).toInt(), FilesRules.size(p.read), FilesRules.size(p.total ?: 0))
        else FilesRules.size(p.read),
        fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1,
    )
    Box(Modifier.padding(top = 2.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(t.surface3)) {
        Box(Modifier.fillMaxWidth(f ?: 0f).height(3.dp).background(t.accent))
    }
}

/**
 * One entry: its kind's icon, its name, its size and time (or how much of it has arrived), badges
 * for a link, and «⋯» with its actions.
 */
@Composable
internal fun FileEntryRow(
    entry: WorkspaceFileEntry,
    words: FilesWords,
    state: FileDownloads.State?,
    actions: List<RowAction>,
    onCancel: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val t = LocalTokens.current
    val loading = state as? FileDownloads.State.Loading
    HubCard(Modifier.testTag("files.entry.${entry.path}"), onClick = onClick, padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).background(t.surface2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                LucideIcon(entryIcon(entry), null, size = 18.dp, tint = if (entry.kind == WorkspaceFileEntry.Kind.DIRECTORY) t.accent else t.textMuted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name, Modifier.weight(1f, fill = false), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium,
                        color = if (entry.kind == WorkspaceFileEntry.Kind.LINK) t.textMuted else t.text,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                        // A name reads in its own direction: «خطة الإطلاق.md» keeps its extension at the end.
                        style = TextStyle(textDirection = TextDirection.Content),
                    )
                    if (entry.kind == WorkspaceFileEntry.Kind.LINK) Badge(words.linkOutsideBadge, tone = BadgeTone.Warning)
                    else if (entry.link) Badge(words.linkBadge)
                }
                when {
                    loading?.progress != null -> ProgressLine(loading.progress)
                    loading != null -> Text(stringResource(R.string.files_fetching), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    else -> FilesRules.detail(entry, fileTime)?.let {
                        Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (loading != null) {
                HubIconButton(Lucide.X, stringResource(R.string.files_cancel), onCancel, size = 32.dp, iconSize = 14.dp)
            } else if (actions.isNotEmpty()) {
                RowActionsButton(actions, Modifier.testTag("files.entry.${entry.path}.actions"))
            }
        }
    }
}

/** «27 Sep 2026, 14:05» in the phone's zone and the app's language, with Latin digits. */
private val fileTime: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun entryIcon(entry: WorkspaceFileEntry): Int = when (entry.kind) {
    WorkspaceFileEntry.Kind.DIRECTORY -> Lucide.Folder
    WorkspaceFileEntry.Kind.LINK -> Lucide.Link
    WorkspaceFileEntry.Kind.FILE -> {
        val type = FileKinds.mimeOf(entry.name, entry.mime)
        when {
            type.startsWith("image/") -> Lucide.Image
            type.startsWith("audio/") -> Lucide.Music
            type.startsWith("video/") -> Lucide.Film
            entry.editable || FileKinds.openAs(entry.name, entry.mime) == FileOpen.VIEWER -> Lucide.FileText
            else -> Lucide.File
        }
    }
}

/**
 * The page's words, read once in composition so the actions and the coroutines (which are not
 * composable) can use them; [say] turns a failure into the one line the page shows.
 */
internal class FilesWords(
    val open: String, val edit: String, val saveToPhone: String, val share: String, val shareZip: String, val attach: String,
    val rename: String, val move: String, val copy: String, val delete: String, val search: String, val empty: String,
    val emptyBody: String, val newFolder: String, val newFile: String, val name: String, val nameHelp: String, val badName: String,
    val create: String, val destination: String, val destinationHint: String, val linkOutside: String, val linkBadge: String,
    val linkOutsideBadge: String, val replaceBody: String, val replace: String, val skip: String,
    private val refusals: Map<FilesRules.Refusal, String>,
    private val failed: String,
    private val offline: String,
    private val templates: Map<String, String>,
) {
    fun say(error: Throwable): String {
        val e = error as? HubError ?: return error.message ?: failed.format("—")
        if (e.offline) return offline
        return refusals[FilesRules.refusal(e)] ?: failed.format(e.text?.takeIf { it.isNotBlank() } ?: e.code ?: e.status.toString())
    }

    /** A failure as a plain one-line error, which the shared sheets show as it is. */
    fun plain(error: Throwable): Throwable = Exception(say(error))

    private fun fill(key: String, vararg args: Any): String = templates.getValue(key).format(*args)
    fun renameTitle(name: String) = fill("rename_title", name)
    fun moveTitle(name: String) = fill("move_title", name)
    fun copyTitle(name: String) = fill("copy_title", name)
    fun moved(path: String) = fill("moved", path)
    fun copied(path: String) = fill("copied", path)
    fun deleted(name: String) = fill("deleted", name)
    fun editTitle(name: String) = fill("edit_title", name)
    fun newFileTitle(name: String) = fill("new_file_title", name)
    fun savedText(name: String) = fill("saved_text", name)
    fun replaceTitle(name: String) = fill("replace_title", name)
    fun unreadable(name: String) = fill("upload_unreadable", name)
    fun tooLargeFor(name: String, max: Long) = fill("upload_too_large", name, FilesRules.size(max))
    fun uploaded(count: Int) = fill("uploaded", count)
}

@Composable
internal fun rememberFilesWords(): FilesWords {
    val refusals = mapOf(
        FilesRules.Refusal.NOT_ALLOWED to stringResource(R.string.files_page_not_allowed),
        FilesRules.Refusal.EXISTS to stringResource(R.string.files_page_exists),
        FilesRules.Refusal.TOO_LARGE to stringResource(R.string.files_page_too_large),
        FilesRules.Refusal.NOT_TEXT to stringResource(R.string.files_page_not_text),
        FilesRules.Refusal.OUTSIDE to stringResource(R.string.files_page_outside),
        FilesRules.Refusal.ROOT to stringResource(R.string.files_page_root_refused),
        FilesRules.Refusal.INTO_ITSELF to stringResource(R.string.files_page_into_itself),
        FilesRules.Refusal.GONE to stringResource(R.string.files_page_gone),
    )
    val templates = mapOf(
        "rename_title" to stringResource(R.string.files_page_rename_title), "move_title" to stringResource(R.string.files_page_move_title),
        "copy_title" to stringResource(R.string.files_page_copy_title), "moved" to stringResource(R.string.files_page_moved),
        "copied" to stringResource(R.string.files_page_copied), "deleted" to stringResource(R.string.files_page_deleted),
        "edit_title" to stringResource(R.string.files_page_edit_title), "new_file_title" to stringResource(R.string.files_page_new_file_title),
        "saved_text" to stringResource(R.string.files_page_saved_text), "replace_title" to stringResource(R.string.files_page_replace_title),
        "upload_unreadable" to stringResource(R.string.files_page_upload_unreadable),
        "upload_too_large" to stringResource(R.string.files_page_upload_too_large),
        "uploaded" to stringResource(R.string.files_page_uploaded),
    )
    return FilesWords(
        open = stringResource(R.string.files_page_open), edit = stringResource(R.string.files_page_edit),
        saveToPhone = stringResource(R.string.files_page_save_to_phone), share = stringResource(R.string.files_page_share),
        shareZip = stringResource(R.string.files_page_share_zip), attach = stringResource(R.string.files_page_attach),
        rename = stringResource(R.string.files_page_rename), move = stringResource(R.string.files_page_move),
        copy = stringResource(R.string.files_page_copy), delete = stringResource(R.string.kit_delete),
        search = stringResource(R.string.files_page_search), empty = stringResource(R.string.files_page_empty),
        emptyBody = stringResource(R.string.files_page_empty_body), newFolder = stringResource(R.string.files_page_new_folder),
        newFile = stringResource(R.string.files_page_new_file), name = stringResource(R.string.files_page_name),
        nameHelp = stringResource(R.string.files_page_name_help), badName = stringResource(R.string.files_page_bad_name),
        create = stringResource(R.string.files_page_create), destination = stringResource(R.string.files_page_destination),
        destinationHint = stringResource(R.string.files_page_destination_hint), linkOutside = stringResource(R.string.files_page_link_outside_hint),
        linkBadge = stringResource(R.string.files_page_link), linkOutsideBadge = stringResource(R.string.files_page_link_outside),
        replaceBody = stringResource(R.string.files_page_replace_body), replace = stringResource(R.string.files_page_replace),
        skip = stringResource(R.string.files_page_skip),
        refusals = refusals, failed = stringResource(R.string.files_page_failed), offline = stringResource(R.string.error_offline),
        templates = templates,
    )
}

/** The page's calls, in [profile]. */
@Composable
internal fun rememberFilesOps(profile: String): FilesOps {
    val graph = LocalContext.current.graph
    val hub = graph.store.current?.hub.orEmpty()
    return remember(profile, hub) {
        val apis by lazy { FilesApis(hub, graph.http.authed) }
        FilesOps(profile) { apis }
    }
}

internal val filesPage = SettingsPageEntry("files") { FilesPage(it) }
