package hub.core.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.chat.ChatInsight
import hub.core.android.chat.HubFile
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.screens.ChatInsightViewModel
import hub.core.android.ui.screens.ChatViewModel
import hub.core.android.ui.screens.localTime
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.RunChanges
import hub.core.client.model.RunFileChange
import hub.core.client.model.RunFileChangeKind
import hub.core.client.model.RunFileDiff
import hub.core.client.model.SessionFileList
import hub.core.client.model.Subagent
import hub.core.client.model.SubagentStatus
import hub.core.client.model.SubagentSupport
import hub.core.client.model.SubagentTail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The chat's work on the phone (apps batch 6): the subagents its agent delegated to (stop, steer,
 * read the end of their transcript, §56), the files each run changed with a unified diff (live while
 * a run goes, §49/§102), and the conversation's own files (§48). Files open through the same opener
 * as a message's files (MessageFiles.kt, #179). Rules and calls: chat/ChatInsight.kt.
 */

/** A back row at the top of a sheet's inner page. */
@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp).testTag("insight.back"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LucideIcon(Lucide.ChevronLeft, stringResource(R.string.back), size = 18.dp, tint = t.accent)
        Text(title, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

// ---------------------------------------------------------------------- subagents

@Composable
fun SubagentsSheet(insight: ChatInsightViewModel, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val ui by insight.ui.collectAsState()
    var viewing by remember { mutableStateOf<String?>(null) }
    var steering by remember { mutableStateOf<Subagent?>(null) }
    var note by remember { mutableStateOf("") }
    var line by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showFinished by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { insight.refreshSubagents() }
    LaunchedEffect(ui.running) { while (ui.running > 0) { delay(1000); now = System.currentTimeMillis() } }
    val unknown = stringResource(R.string.error_unknown)
    val queued = stringResource(R.string.chat_insight_steer_queued)
    val rejected = stringResource(R.string.chat_insight_steer_rejected)
    HubSheet(onDismiss, Modifier.testTag("sheet.subagents"), title = if (viewing == null) stringResource(R.string.chat_insight_subagents) else null) {
        val open = viewing
        if (open != null) {
            SubagentOutput(insight, open) { viewing = null }
            return@HubSheet
        }
        val (running, finished) = ChatInsight.split(ui.subagents)
        val full = ui.support == SubagentSupport.FULL
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            line?.let { (text, failed) ->
                item { Text(text, fontSize = FontTokens.sizeSm.sp, color = if (failed) t.danger else t.textMuted) }
            }
            ui.subagentsError?.let { item { ErrorNotice(it) } }
            if (!ui.subagentsLoaded) item { Loading() }
            else if (ui.subagents.isEmpty()) item {
                hub.core.android.ui.kit.EmptyState(
                    stringResource(R.string.chat_insight_subagents_empty), Modifier.testTag("subagents.empty"),
                    body = stringResource(if (ui.support == SubagentSupport.NONE) R.string.chat_insight_subagents_none else R.string.chat_insight_subagents_empty_hint),
                    icon = Lucide.Bot,
                )
            }
            if (running.isNotEmpty()) item { Label(stringResource(R.string.chat_insight_subagents_running_title)) }
            items(running, key = { it.subagent.id }) { row ->
                SubagentCard(
                    row.subagent, row.indent, now, full,
                    stop = { insight.interrupt(row.subagent.id) { e -> line = (e.text ?: unknown) to true } },
                    steer = { note = ""; steering = row.subagent },
                    output = { viewing = row.subagent.id },
                )
            }
            if (finished.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().clickable { showFinished = !showFinished }.padding(vertical = 6.dp).testTag("subagents.finished"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Label(stringResource(R.string.chat_insight_subagents_finished, finished.size.toString()))
                    LucideIcon(if (showFinished) Lucide.ChevronUp else Lucide.ChevronDown, null, size = 14.dp, tint = t.textMuted)
                }
            }
            if (showFinished) items(finished, key = { "done:" + it.id }) { subagent ->
                SubagentCard(subagent, 0, now, full, stop = null, steer = null, output = { viewing = subagent.id })
            }
        }
    }
    steering?.let { target ->
        HubDialog({ steering = null }, stringResource(R.string.chat_insight_steer_title)) {
            Text(target.goal, fontSize = FontTokens.sizeSm.sp, color = t.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            HubTextField(note, { note = it }, placeholder = stringResource(R.string.chat_insight_steer_placeholder), singleLine = false, maxLines = 4, size = ControlSize.Md, fieldTag = "subagents.note")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HubButton(stringResource(R.string.cancel), { steering = null }, kind = ButtonKind.Secondary, size = ControlSize.Md)
                HubButton(stringResource(R.string.chat_insight_steer_send), {
                    val text = ChatInsight.steerText(note) ?: return@HubButton
                    steering = null
                    insight.steer(target.id, text) { ok, error ->
                        line = when {
                            error != null -> (error.text ?: unknown) to true
                            ok == true -> queued to false
                            else -> rejected to true
                        }
                    }
                }, size = ControlSize.Md, enabled = ChatInsight.steerText(note) != null, modifier = Modifier.testTag("subagents.send"))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold, color = LocalTokens.current.textMuted)
}

/** One subagent: goal, status, clock, model and tools; Stop, Steer and its output where the agent allows them. */
@Composable
private fun SubagentCard(
    subagent: Subagent,
    indent: Int,
    now: Long,
    full: Boolean,
    stop: (() -> Unit)?,
    steer: (() -> Unit)?,
    output: () -> Unit,
) {
    val t = LocalTokens.current
    val running = subagent.status == SubagentStatus.RUNNING
    Column(
        Modifier.fillMaxWidth().padding(start = (indent * 16).dp).clip(RoundedCornerShape(10.dp)).background(t.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("subagents.row"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Badge(stringResource(subagentStatusText(subagent.status)), tone = subagentTone(subagent.status))
            Text(subagent.goal, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val parts = mutableListOf<String>()
        val nowTime = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC)
        ChatInsight.elapsedMs(subagent.startedAt, subagent.finishedAt, nowTime)?.let { parts += ChatInsight.clock(it) }
        subagent.model?.let { parts += it }
        subagent.toolCount?.let { parts += stringResource(R.string.chat_insight_subagent_tools, it.toString()) }
        if (running) subagent.lastTool?.let { parts += it }
        Text(parts.joinToString(" · "), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!running) subagent.summary?.let {
            Text(it, fontSize = FontTokens.sizeXs.sp, color = if (subagent.status == SubagentStatus.FAILED) t.danger else t.textMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (full) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stop != null && running) HubButton(stringResource(R.string.chat_insight_subagent_stop), stop, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.CircleStop, modifier = Modifier.testTag("subagents.stop"))
                if (steer != null && running && subagent.acceptingSteer) HubButton(stringResource(R.string.chat_insight_subagent_steer), steer, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.CornerDownRight, modifier = Modifier.testTag("subagents.steer"))
                HubButton(stringResource(R.string.chat_insight_subagent_output), output, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Eye, modifier = Modifier.testTag("subagents.output"))
            }
        }
    }
}

private fun subagentStatusText(status: SubagentStatus): Int = when (status) {
    SubagentStatus.RUNNING -> R.string.chat_insight_subagent_status_running
    SubagentStatus.COMPLETED -> R.string.chat_insight_subagent_status_completed
    SubagentStatus.FAILED -> R.string.chat_insight_subagent_status_failed
    SubagentStatus.INTERRUPTED -> R.string.chat_insight_subagent_status_interrupted
}

private fun subagentTone(status: SubagentStatus): BadgeTone = when (status) {
    SubagentStatus.RUNNING -> BadgeTone.Warning
    SubagentStatus.COMPLETED -> BadgeTone.Success
    SubagentStatus.FAILED -> BadgeTone.Danger
    SubagentStatus.INTERRUPTED -> BadgeTone.Neutral
}

/** The end of a subagent's own transcript (up to 16 KB), read again every 2 seconds while it runs. */
@Composable
private fun SubagentOutput(insight: ChatInsightViewModel, id: String, onBack: () -> Unit) {
    val t = LocalTokens.current
    val ui by insight.ui.collectAsState()
    val subagent = ui.subagents.firstOrNull { it.id == id }
    var tail by remember { mutableStateOf<SubagentTail?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    LaunchedEffect(id) {
        while (true) {
            val api = insight.api ?: break
            hub.core.android.data.hubCall { api.tail(insight.sessionId, insight.profile, id) }
                .onSuccess { tail = it; error = null }
                .onFailure { error = it as HubError }
            if (insight.ui.value.subagents.firstOrNull { it.id == id }?.status != SubagentStatus.RUNNING) break
            delay(2000)
        }
    }
    BackRow(stringResource(R.string.chat_insight_subagent_output), onBack)
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        subagent?.let { Text(it.goal, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium) }
        ErrorNotice(error)
        val shown = tail
        when {
            shown == null && error == null -> Loading()
            shown != null && shown.available -> {
                if (shown.truncated) Text(stringResource(R.string.chat_insight_output_truncated), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                SelectionContainer {
                    Text(
                        shown.text, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.codeText,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.codeBg).padding(10.dp).testTag("subagent.output"),
                    )
                }
            }
            shown != null -> Text(stringResource(R.string.chat_insight_output_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        }
    }
}

// ---------------------------------------------------------------------- changed files

/** A file one run changed, and whether it is the live (not yet recorded) picture. */
private data class DiffTarget(val runId: String, val file: RunFileChange, val live: Boolean)

/**
 * The files each run changed, newest run first; the running one's so far, read again every few
 * seconds. A file opens its diff; the diff opens the file.
 */
@Composable
fun ChangesSheet(chat: ChatViewModel, insight: ChatInsightViewModel, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val chatUi by chat.ui.collectAsState()
    val seen by insight.ui.collectAsState()
    val activeRun = chatUi.chat.activeRun
    var target by remember { mutableStateOf<DiffTarget?>(null) }
    var live by remember { mutableStateOf<RunChanges?>(null) }
    val list = rememberPagedList<RunChanges>(ChatInsight.changesRevision(seen.ended.map { it.id }), key = { it.runId }) { cursor ->
        val api = insight.api ?: throw HubError(401, "unauthorized", null)
        val page = api.changes(insight.sessionId, insight.profile, cursor)
        ListPage(page.items, page.nextCursor)
    }
    LaunchedEffect(activeRun?.id) {
        live = null
        val run = activeRun?.id ?: return@LaunchedEffect
        while (true) {
            live = insight.api?.let { api -> runCatching { api.liveChanges(insight.sessionId, insight.profile, run) }.getOrNull() }
            delay(LIVE_POLL_MS)
        }
    }
    HubSheet(onDismiss, Modifier.testTag("sheet.changes"), title = if (target == null) stringResource(R.string.chat_insight_changes) else null) {
        val open = target
        if (open != null) {
            DiffPage(insight, open) { target = null }
            return@HubSheet
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (activeRun != null) {
                item { Header(stringResource(R.string.chat_insight_changes_live), live?.let { ChatInsight.counts(it.additions, it.deletions) }) }
                val files = live?.files.orEmpty()
                if (files.isEmpty()) item { Text(stringResource(R.string.chat_insight_changes_live_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
                items(files, key = { "live:" + it.path }) { file -> ChangeRow(file) { target = DiffTarget(activeRun.id, file, live = true) } }
            }
            when {
                list.loading -> item { Loading() }
                list.items.isEmpty() && activeRun == null -> item {
                    if (list.error != null) ErrorNotice(list.error)
                    else hub.core.android.ui.kit.EmptyState(
                        stringResource(R.string.chat_insight_changes_empty), Modifier.testTag("changes.empty"),
                        body = stringResource(R.string.chat_insight_changes_empty_hint), icon = Lucide.FileDiff,
                    )
                }
            }
            list.items.forEach { changes ->
                item(key = "head:" + changes.runId) {
                    Header(
                        localTime(changes.recordedAt),
                        stringResource(R.string.chat_insight_changes_summary, changes.filesChanged.toString(), ChatInsight.counts(changes.additions, changes.deletions).orEmpty()),
                    )
                }
                items(changes.files, key = { changes.runId + ":" + it.path }) { file -> ChangeRow(file) { target = DiffTarget(changes.runId, file, live = false) } }
                if (changes.truncated || !changes.complete) item(key = "note:" + changes.runId) {
                    Text(
                        stringResource(if (changes.truncated) R.string.chat_insight_changes_truncated else R.string.chat_insight_changes_incomplete),
                        fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                    )
                }
            }
            if (list.hasMore) item(key = "more") {
                LaunchedEffect(list.items.size) { list.loadMore() }
                Loading()
            }
        }
    }
}

private const val LIVE_POLL_MS = 4_000L

@Composable
private fun Header(title: String, detail: String?) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted, modifier = Modifier.weight(1f))
        if (!detail.isNullOrBlank()) Text(detail, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
    }
}

/** One changed file: what happened to it, its path (and where a renamed one was), its line counts. */
@Composable
private fun ChangeRow(file: RunFileChange, onClick: (() -> Unit)?) {
    val t = LocalTokens.current
    val (icon, tint) = when (file.change) {
        RunFileChangeKind.ADDED -> Lucide.Plus to t.successSoftText
        RunFileChangeKind.DELETED -> Lucide.Trash to t.danger
        RunFileChangeKind.MODIFIED -> Lucide.Pencil to t.textMuted
        RunFileChangeKind.RENAMED -> Lucide.FileText to t.textMuted
    }
    val kind = stringResource(
        when (file.change) {
            RunFileChangeKind.ADDED -> R.string.chat_insight_change_added
            RunFileChangeKind.MODIFIED -> R.string.chat_insight_change_modified
            RunFileChangeKind.DELETED -> R.string.chat_insight_change_deleted
            RunFileChangeKind.RENAMED -> R.string.chat_insight_change_renamed
        },
    )
    val detail = when {
        file.oldPath != null -> kind + " · " + stringResource(R.string.chat_insight_renamed_from, file.oldPath!!)
        file.binary -> kind + " · " + stringResource(R.string.chat_insight_binary)
        else -> kind
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("changes.file"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LucideIcon(icon, null, size = 16.dp, tint = tint)
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(file.path, fontSize = FontTokens.sizeSm.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.StartEllipsis)
            }
            Text(detail, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (file.additions != null && file.deletions != null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("+${file.additions}", fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.successSoftText)
                    Text("−${file.deletions}", fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.dangerSoftText)
                }
            }
        }
    }
}

/** One file's diff as the hub recorded it: unified, monospaced, numbered, scrolled sideways; «Open» shows the file now. */
@Composable
private fun DiffPage(insight: ChatInsightViewModel, target: DiffTarget, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val opener = rememberOpener(insight.profile)
    var diff by remember { mutableStateOf<RunFileDiff?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val none = ChatInsight.noDiff(target.file.diff, target.live)
    LaunchedEffect(target) {
        if (none != null) return@LaunchedEffect
        val api = insight.api ?: return@LaunchedEffect
        hub.core.android.data.hubCall { api.diff(insight.sessionId, insight.profile, target.runId, target.file.path) }
            .onSuccess { diff = it }
            .onFailure { error = it as HubError }
    }
    BackRow(ChatInsight.fileName(target.file.path), onBack)
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChangeRow(target.file, onClick = null)
        if (ChatInsight.canOpen(target.file.change)) {
            HubButton(
                stringResource(R.string.chat_insight_open_file), {
                    scope.launchOpen(insight, target.file.path) { file -> opener.open(file, scope) }
                }, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.ExternalLink, modifier = Modifier.testTag("diff.open"),
            )
        }
        val shown = diff
        when {
            none != null -> NoticeBox(stringResource(noDiffText(none)), BadgeTone.Info)
            error != null -> ErrorNotice(error)
            shown == null -> Loading()
            else -> {
                if (shown.truncated) NoticeBox(stringResource(R.string.chat_insight_diff_truncated), BadgeTone.Warning)
                val inner = ChatInsight.noDiff(shown.diff, false)
                if (inner != null) NoticeBox(stringResource(noDiffText(inner)), BadgeTone.Info)
                else DiffBody(ChatInsight.parseDiff(shown.text.orEmpty()))
            }
        }
    }
}

/** The file as the folder has it now: its entry in the chat's files (size and time, so a changed file is fetched again), else its path. */
private fun CoroutineScope.launchOpen(insight: ChatInsightViewModel, path: String, open: (HubFile) -> Unit) {
    launch {
        val list = insight.api?.let { api -> runCatching { api.files(insight.sessionId, insight.profile) }.getOrNull() }
        val entry = list?.items?.firstOrNull { it.path == path }
        open(entry?.let { HubFile.of(it, insight.sessionId) } ?: HubFile.Working(insight.sessionId, path, ChatInsight.fileName(path)))
    }
}

private fun noDiffText(none: ChatInsight.NoDiff): Int = when (none) {
    ChatInsight.NoDiff.LIVE -> R.string.chat_insight_diff_live
    ChatInsight.NoDiff.BINARY -> R.string.chat_insight_diff_binary
    ChatInsight.NoDiff.TOO_LARGE -> R.string.chat_insight_diff_too_large
    ChatInsight.NoDiff.UNAVAILABLE -> R.string.chat_insight_diff_unavailable
}

/** The hunks, each line with its old and new number, in one column that scrolls sideways; left to right like code. */
@Composable
fun DiffBody(hunks: List<ChatInsight.DiffHunk>) {
    val t = LocalTokens.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SelectionContainer {
            BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.codeBg).testTag("diff.body")) {
            // Every line as wide as the longest (or the box), so a line's colour runs to the end.
            val least = maxWidth
            Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = least).width(IntrinsicSize.Max)) {
                hunks.forEach { hunk ->
                    Text(
                        hunk.header, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.infoSoftText, softWrap = false,
                        modifier = Modifier.fillMaxWidth().background(t.infoSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    hunk.lines.forEach { line ->
                        val bg = when (line.kind) {
                            ChatInsight.DiffKind.ADD -> t.successSoft
                            ChatInsight.DiffKind.DEL -> t.dangerSoft
                            else -> Color.Transparent
                        }
                        Row(Modifier.fillMaxWidth().background(bg).padding(end = 12.dp)) {
                            Number(line.old)
                            Number(line.new)
                            Text(
                                when (line.kind) { ChatInsight.DiffKind.ADD -> "+"; ChatInsight.DiffKind.DEL -> "−"; else -> " " },
                                fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.textMuted,
                                modifier = Modifier.width(16.dp), textAlign = TextAlign.Center,
                            )
                            Text(
                                line.text.ifEmpty { " " }, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, softWrap = false,
                                color = if (line.kind == ChatInsight.DiffKind.NOTE) t.textMuted else t.codeText,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun Number(value: Int?) {
    Text(
        value?.toString().orEmpty(), fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = LocalTokens.current.textFaint,
        modifier = Modifier.width(38.dp), textAlign = TextAlign.End, softWrap = false,
    )
}

// ---------------------------------------------------------------------- the chat's files

/** What the agent wrote or edited, what is in the chat's folder, and what was attached (§48); each opens as a message's file does. */
@Composable
fun ChatFilesSheet(insight: ChatInsightViewModel, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val seen by insight.ui.collectAsState()
    val revision = ChatInsight.changesRevision(seen.ended.map { it.id })
    var files by remember { mutableStateOf<SessionFileList?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    LaunchedEffect(revision) {
        val api = insight.api ?: return@LaunchedEffect
        hub.core.android.data.hubCall { api.files(insight.sessionId, insight.profile) }
            .onSuccess { files = it; error = null }
            .onFailure { error = it as HubError }
    }
    HubSheet(onDismiss, Modifier.testTag("sheet.files"), title = stringResource(R.string.chat_insight_files)) {
        val list = files
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            error?.let { item { ErrorNotice(it) } }
            when {
                list == null && error == null -> item { Loading() }
                list != null && list.items.isEmpty() -> item {
                    hub.core.android.ui.kit.EmptyState(
                        stringResource(R.string.chat_insight_files_empty), Modifier.testTag("files.empty"),
                        body = stringResource(R.string.chat_insight_files_empty_hint), icon = Lucide.Folder,
                    )
                }
            }
            items(list?.items.orEmpty(), key = { it.key }) { item ->
                Box(Modifier.fillMaxWidth()) { OpenableFile(HubFile.of(item, insight.sessionId), insight.profile) }
            }
            if (list?.truncated == true) item { Text(stringResource(R.string.chat_insight_files_truncated), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            list?.workingDir?.let { dir ->
                item { Text(stringResource(R.string.chat_insight_files_folder, dir), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            }
        }
    }
}
