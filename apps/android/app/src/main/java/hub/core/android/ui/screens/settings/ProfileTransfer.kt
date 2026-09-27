package hub.core.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.chat.FileDownloads
import hub.core.android.chat.HubFile
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.AttachmentFiles
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.PickedFiles
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Job
import hub.core.client.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A job on its way: a spinner and the job's own line, else ours. */
@Composable
private fun Working(text: String, tag: String) {
    val t = LocalTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(tag)) {
        Spinner(16.dp, t.textMuted)
        Text(text, fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
    }
}

/**
 * Export: with or without the providers (asked, «without» first, decision §37), then a job while
 * Hermes writes the archive; the phone downloads it and hands it to the share sheet or Downloads.
 * The sheet says what is in the file and what is not before anything starts.
 */
@Composable
internal fun ExportProfileSheet(ops: AdminTwoOps, p: Profile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val downloads = context.graph.files
    var providers by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var started by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var saved by remember { mutableStateOf(false) }
    val result = ProfileRules.exported(job)
    val file = result?.let { HubFile.Attachment(it.attachmentId, it.name, "application/gzip", it.sizeBytes) }
    val states by downloads.states.collectAsState()
    val download = file?.let { states[it.cacheKey] ?: downloads.state(it) }
    // The archive is fetched the moment it is ready, once.
    LaunchedEffect(file?.id) { file?.let { downloads.start(it, ops.profile) } }
    HubSheet(onDismiss = onDismiss, title = stringResource(R.string.admin_profiles_export_title, p.name)) {
        Text(stringResource(R.string.admin_profiles_export_what), fontSize = FontTokens.sizeSm.sp)
        Text(stringResource(R.string.admin_profiles_export_providers), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        Segmented(
            listOf(Segment(false, stringResource(R.string.admin_profiles_export_without)), Segment(true, stringResource(R.string.admin_profiles_export_with))),
            providers, { if (!started) providers = it }, Modifier.fillMaxWidth().testTag("export.providers"), size = ControlSize.Sm,
        )
        if (providers) NoticeBox(stringResource(R.string.admin_profiles_export_keys_warning), BadgeTone.Warning, Modifier.testTag("export.keys_warning"))
        else NoticeBox(stringResource(R.string.admin_profiles_export_secrets), BadgeTone.Info)
        ErrorNotice(error)
        ProfileRules.failure(job)?.let { NoticeBox(it, BadgeTone.Danger) }
        if (started && !ProfileRules.finished(job)) Working(job?.progress?.message ?: stringResource(R.string.admin_profiles_export_running), "export.running")
        if (result != null) {
            val lines = buildList {
                add(stringResource(R.string.admin_profiles_export_ready, result.name, ProfileRules.size(result.sizeBytes)))
                if (result.removed.isNotEmpty()) add(stringResource(R.string.admin_profiles_export_removed, result.removed.joinToString(", ")))
                if (result.masked.isNotEmpty()) add(stringResource(R.string.admin_profiles_export_masked, result.masked.joinToString(", ")))
                if (result.providers > 0) add(stringResource(R.string.admin_profiles_export_providers_carried, result.providers))
            }
            NoticeBox(lines.joinToString("\n"), BadgeTone.Success, Modifier.testTag("export.ready"))
            when (download) {
                is FileDownloads.State.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubButton(stringResource(R.string.admin_profiles_export_share), { AttachmentFiles.share(context, download.file, "application/gzip") },
                        icon = Lucide.Share2, size = ControlSize.Md, modifier = Modifier.testTag("export.share"))
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        HubButton(stringResource(R.string.admin_profiles_export_save), { saved = AttachmentFiles.save(context, download.file, result.name, "application/gzip") },
                            kind = ButtonKind.Secondary, icon = Lucide.Download, size = ControlSize.Md)
                    }
                }
                is FileDownloads.State.Failed -> {
                    ErrorNotice(download.error)
                    HubButton(stringResource(R.string.admin_profiles_export_share), { file.let { downloads.start(it, ops.profile) } }, kind = ButtonKind.Secondary, icon = Lucide.RotateCw, size = ControlSize.Md)
                }
                else -> Working(stringResource(R.string.admin_profiles_export_downloading), "export.downloading")
            }
            if (saved) NoticeBox(stringResource(R.string.admin_profiles_export_saved), BadgeTone.Success)
        } else if (!started || ProfileRules.failure(job) != null) {
            HubButton(stringResource(R.string.admin_profiles_export_start), {
                started = true
                error = null
                job = null
                scope.launch {
                    ops.export(p.id, providers)
                        .onSuccess { id -> job = ops.follow(id) { job = it } }
                        .onFailure { error = it as? HubError; started = false }
                }
            }, icon = Lucide.Download, fill = true, modifier = Modifier.fillMaxWidth().testTag("export.start"))
        }
    }
}

/**
 * Import: an archive picked from the phone's files, the slug and name it suggests, what will
 * happen said in one line and asked once more; then the upload, and a job while Hermes makes the
 * profile. A slug already used is refused here, before the hub has to.
 */
@Composable
internal fun ImportProfileSheet(ops: AdminTwoOps, taken: List<String>, onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var file by remember { mutableStateOf<File?>(null) }
    var slug by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<Int?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var asking by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    // «Replace the default profile with this one» (decision §116), and what it ended with.
    var replaceDefault by remember { mutableStateOf(false) }
    var replaced by remember { mutableStateOf<Pair<String, String>?>(null) }
    var outdated by remember { mutableStateOf<String?>(null) }
    val unreadable = stringResource(R.string.admin_profiles_import_unreadable)
    val tooLarge = stringResource(R.string.admin_profiles_import_too_large)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) { PickedFiles.copy(context, uri, "profile.tar.gz") }
            failure = null
            error = null
            if (copied == null) { failure = unreadable; return@launch }
            file = copied
            if (slug.isEmpty()) {
                val (s, n) = ProfileRules.fromArchive(copied.name, taken)
                slug = s
                if (name.isEmpty()) name = n
            }
        }
    }
    val busy = stage != null
    val finished = replaced != null || outdated != null
    val ready = file != null && !busy && !finished &&
        (replaceDefault || (slug.isNotEmpty() && ProfileRules.slugProblem(slug, taken) == null))
    HubSheet(onDismiss = { if (!busy) onDismiss() }, title = stringResource(R.string.admin_profiles_import_title)) {
        Text(stringResource(R.string.admin_profiles_import_what), fontSize = FontTokens.sizeSm.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubButton(
                stringResource(if (file == null) R.string.admin_profiles_import_choose else R.string.admin_profiles_import_other),
                { picker.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream", "*/*")) },
                kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.File, enabled = !busy, modifier = Modifier.testTag("import.choose"),
            )
            Text(file?.name ?: stringResource(R.string.admin_profiles_import_no_file), fontSize = FontTokens.sizeSm.sp, color = t.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
        }
        ToggleRow(
            stringResource(R.string.admin_profiles_import_replace_default), replaceDefault, { replaceDefault = it },
            subtitle = stringResource(R.string.admin_profiles_import_replace_default_hint), enabled = !busy && !finished,
            modifier = Modifier.testTag("import.replace_default"),
        )
        if (!replaceDefault) SlugField(slug, taken) { slug = it }
        HubTextField(name, { name = it.take(ProfileRules.NAME_MAX) }, label = stringResource(R.string.admin_profiles_name), size = ControlSize.Md, fieldTag = "import.name")
        if (replaceDefault) Text(stringResource(R.string.admin_profiles_import_replace_name_hint), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        replaced?.let { (backup, made) ->
            NoticeBox(stringResource(R.string.admin_profiles_import_replace_done, backup), BadgeTone.Success, Modifier.testTag("import.replaced"))
            HubButton(stringResource(R.string.close), { onImported(made) }, kind = ButtonKind.Secondary, fill = true, modifier = Modifier.fillMaxWidth())
        }
        outdated?.let { made ->
            NoticeBox(stringResource(R.string.admin_profiles_import_replace_unsupported, made), BadgeTone.Warning)
            HubButton(stringResource(R.string.close), { onImported(made) }, kind = ButtonKind.Secondary, fill = true, modifier = Modifier.fillMaxWidth())
        }
        file?.let { f ->
            if (ready && !replaceDefault) NoticeBox(
                stringResource(R.string.admin_profiles_import_summary, f.name, ProfileRules.size(f.length()), name.trim().ifEmpty { slug }, slug),
                BadgeTone.Info, Modifier.testTag("import.summary"),
            )
        }
        when (stage) {
            UPLOADING -> Working(stringResource(R.string.admin_profiles_import_uploading), "import.uploading")
            RUNNING -> Working(job?.progress?.message ?: stringResource(R.string.admin_profiles_import_running), "import.running")
        }
        failure?.let { NoticeBox(it, BadgeTone.Danger, Modifier.testTag("import.failure")) }
        ErrorNotice(error)
        HubButton(
            stringResource(if (replaceDefault) R.string.admin_profiles_import_replace_confirm else R.string.admin_profiles_import),
            { asking = true }, icon = Lucide.File, fill = true, loading = busy, enabled = ready,
            modifier = Modifier.fillMaxWidth().testTag("import.start"),
        )
    }
    // For the default, the second and explicit warning (decision §116).
    if (asking) ConfirmDialog(
        stringResource(if (replaceDefault) R.string.admin_profiles_import_replace_confirm_title else R.string.admin_profiles_import_confirm_title),
        if (replaceDefault) stringResource(R.string.admin_profiles_import_replace_confirm_body)
        else stringResource(R.string.admin_profiles_import_confirm_body, slug),
        stringResource(if (replaceDefault) R.string.admin_profiles_import_replace_confirm else R.string.admin_profiles_import),
        danger = replaceDefault,
        onConfirm = {
            asking = false
            val chosen = file ?: return@ConfirmDialog
            failure = null
            error = null
            if (chosen.length() > ProfileRules.MAX_ARCHIVE_BYTES) { failure = tooLarge; return@ConfirmDialog }
            stage = UPLOADING
            scope.launch {
                val stored = ops.upload(chosen).getOrElse { e ->
                    val hub = e as? HubError
                    if (hub?.status == 413) failure = tooLarge else error = hub
                    stage = null
                    return@launch
                }
                stage = RUNNING
                val id = ops.import(ProfileRules.import(stored.id, slug, name, replaceDefault)).getOrElse { e -> error = e as? HubError; stage = null; return@launch }
                val done = ops.follow(id) { job = it }
                stage = null
                val made = ProfileRules.importedName(done)
                val backup = ProfileRules.replacedBackup(done)
                when {
                    made == null -> failure = ProfileRules.failure(done)
                    // The sheet stays: it names the backup the old default is kept as.
                    backup != null -> replaced = backup to made
                    // A hub older than the option made a new profile instead; say so.
                    replaceDefault -> outdated = made
                    else -> onImported(made)
                }
            }
        },
        onDismiss = { asking = false },
    )
}

private const val UPLOADING = 1
private const val RUNNING = 2
