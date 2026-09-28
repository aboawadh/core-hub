package hub.core.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.chat.ChatInsight
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.screens.ChatInsightViewModel
import hub.core.android.ui.screens.ChatViewModel
import hub.core.android.ui.screens.InsightSheet
import hub.core.android.ui.screens.localTime
import hub.core.android.ui.screens.rememberChatInsightViewModel
import hub.core.android.ui.screens.rememberChatViewModel
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.AgentCapability
import hub.core.client.model.Run
import hub.core.client.model.SessionContextBreakdown
import kotlinx.coroutines.delay

/*
 * The chat's insight on the phone (apps batch 6): a small context ring in the top bar (and a count
 * while subagents run), the same five places in the chat's «⋮», and their sheets — the context
 * window with compress, and the chat's runs. Subagents, changed files and the chat's files are in
 * ChatInsightWork.kt; rules and calls in chat/ChatInsight.kt. Nothing is on screen until asked,
 * except the ring (web: beside the mic) and the running subagents' count.
 */

/** How full the chat's window is, when known (ChatInsight.use). */
@Composable
fun rememberContextUse(chat: ChatViewModel, insight: ChatInsightViewModel): ChatInsight.Use? {
    val ui by chat.ui.collectAsState()
    val seen by insight.ui.collectAsState()
    val session = ui.chat.session
    val window = ui.models.firstOrNull { it.value == session?.model }?.window
    return ChatInsight.use(session?.context, window, seen.ended + listOfNotNull(ui.chat.activeRun))
}

/**
 * The top bar's compact part: the running subagents (only while some run) and the context ring (only
 * when the window is known). It also hosts the insight's sheets, which the «⋮» opens too.
 */
@Composable
fun ChatInsightBar(sessionId: String, profile: String) {
    val chat = rememberChatViewModel(sessionId, profile)
    val insight = rememberChatInsightViewModel(sessionId, profile)
    val ui by insight.ui.collectAsState()
    val use = rememberContextUse(chat, insight)
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ui.running > 0) {
            val label = stringResource(R.string.chat_insight_subagents_running, ui.running.toString())
            Row(
                Modifier.clip(CircleShape).background(t.accentSoft).clickable(onClickLabel = label) { insight.show(InsightSheet.SUBAGENTS) }
                    .padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = label }.testTag("chat.insight.subagents"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LucideIcon(Lucide.Bot, null, size = 15.dp, tint = t.accentSoftText)
                Text(ui.running.toString(), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold, color = t.accentSoftText)
            }
        }
        if (use != null) {
            val label = ringLabel(use)
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = label) { insight.show(InsightSheet.CONTEXT) }
                    .semantics { contentDescription = label }.testTag("chat.insight.ring"),
                contentAlignment = Alignment.Center,
            ) { ContextRing(use, 20.dp) }
        }
    }
    ChatInsightSheets(chat, insight)
}

@Composable
private fun ringLabel(use: ChatInsight.Use): String = stringResource(
    R.string.chat_insight_ring, ChatInsight.percent(use).toString(), ChatInsight.number(use.used), ChatInsight.number(use.window),
)

@Composable
fun bandColor(band: ChatInsight.Band): Color {
    val t = LocalTokens.current
    return when (band) {
        ChatInsight.Band.NORMAL -> t.accent
        ChatInsight.Band.WARNING -> t.warningSoftText
        ChatInsight.Band.DANGER -> t.danger
    }
}

/** The ring itself: the track, and the used part from the top, in its band's colour. */
@Composable
fun ContextRing(use: ChatInsight.Use, size: Dp = 18.dp) {
    val track = LocalTokens.current.border
    val fill = bandColor(ChatInsight.band(use))
    Canvas(Modifier.size(size)) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(fill, -90f, 360f * maxOf(0.02, use.ratio).toFloat(), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** The insight's five places in the chat's «⋮» (the menu draws the divider after them). */
@Composable
fun ChatInsightMenuItems(sessionId: String, profile: String, close: () -> Unit) {
    val insight = rememberChatInsightViewModel(sessionId, profile)
    listOf(
        Triple(InsightSheet.CONTEXT, R.string.chat_insight_context, Lucide.Gauge),
        Triple(InsightSheet.RUNS, R.string.chat_insight_runs, Lucide.RotateCcwClock),
        Triple(InsightSheet.SUBAGENTS, R.string.chat_insight_subagents, Lucide.Bot),
        Triple(InsightSheet.CHANGES, R.string.chat_insight_changes, Lucide.FileDiff),
        Triple(InsightSheet.FILES, R.string.chat_insight_files, Lucide.Folder),
    ).forEach { (sheet, label, icon) ->
        MenuItem(stringResource(label), { close(); insight.show(sheet) }, Modifier.testTag("chat.insight.${sheet.name.lowercase()}"), icon = icon)
    }
}

/** Which sheet is open, drawn. */
@Composable
fun ChatInsightSheets(chat: ChatViewModel, insight: ChatInsightViewModel) {
    val ui by insight.ui.collectAsState()
    val close = { insight.show(null) }
    when (ui.sheet) {
        InsightSheet.CONTEXT -> ContextSheet(chat, insight, close)
        InsightSheet.RUNS -> RunsSheet(insight, close)
        InsightSheet.SUBAGENTS -> SubagentsSheet(insight, close)
        InsightSheet.CHANGES -> ChangesSheet(chat, insight, close)
        InsightSheet.FILES -> ChatFilesSheet(insight, close)
        null -> {}
    }
}

// ---------------------------------------------------------------------- context

/** Distinct colours for the categories, in order. */
@Composable
private fun palette(): List<Color> {
    val t = LocalTokens.current
    return listOf(t.accent, t.infoSoftText, t.successSoftText, t.warningSoftText, t.dangerSoftText, t.statusRunning, t.link, t.textMuted)
}

/**
 * How full the window is, what fills it (read only while the sheet is open, §102), and Compress with
 * an optional focus — where the agent can compress, and only between replies.
 */
@Composable
private fun ContextSheet(chat: ChatViewModel, insight: ChatInsightViewModel, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val ui by chat.ui.collectAsState()
    val use = rememberContextUse(chat, insight)
    val agent = rememberAgents(insight.profile).firstOrNull { it.id == ui.chat.session?.agentId }
    val canCompress = AgentCapability.COMPRESS in agent?.capabilities.orEmpty()
    var breakdown by remember { mutableStateOf<SessionContextBreakdown?>(null) }
    var focus by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(use?.used) {
        breakdown = insight.api?.let { api -> runCatching { api.breakdown(insight.sessionId, insight.profile) }.getOrNull() }
    }
    HubSheet(onDismiss, Modifier.testTag("sheet.context"), title = stringResource(R.string.chat_insight_context_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (use == null) {
                Text(stringResource(R.string.chat_insight_context_unknown), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            } else {
                Text(
                    stringResource(R.string.chat_insight_percent, ChatInsight.percent(use).toString()),
                    fontSize = FontTokens.size2xl.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("context.percent"),
                )
                Bar(listOf(use.ratio), listOf(bandColor(ChatInsight.band(use))))
                Text(stringResource(R.string.chat_insight_used, ChatInsight.number(use.used), ChatInsight.number(use.window)), fontSize = FontTokens.sizeSm.sp)
                Text(
                    stringResource(sourceText(use.source)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                    modifier = Modifier.testTag("context.source"),
                )
            }
            val b = breakdown
            if (b != null && b.available && b.categories.isNotEmpty()) {
                SectionLabel(stringResource(R.string.chat_insight_breakdown_title))
                val colors = palette()
                Bar(ChatInsight.shares(b.categories, b.windowTokens ?: use?.window ?: 0), b.categories.indices.map { colors[it % colors.size] })
                b.categories.forEachIndexed { index, category ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("context.category")) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors[index % colors.size]))
                        Text(
                            if (category.id in ChatInsight.KNOWN_CATEGORIES) categoryName(category.id) else category.label,
                            fontSize = FontTokens.sizeSm.sp, modifier = Modifier.weight(1f),
                        )
                        Text(ChatInsight.number(category.tokens), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                    }
                }
                Text(stringResource(R.string.chat_insight_breakdown_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            if (canCompress) {
                SectionLabel(stringResource(R.string.chat_insight_compress_title))
                HubTextField(
                    focus, { focus = it }, placeholder = stringResource(R.string.chat_insight_focus_placeholder),
                    singleLine = false, minLines = 2, maxLines = 5, size = ControlSize.Md, fieldTag = "context.focus",
                )
                Text(stringResource(R.string.chat_insight_focus_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                HubButton(
                    stringResource(if (ui.compressing) R.string.chat_insight_compressing else R.string.chat_insight_compress),
                    { chat.compress(focus) }, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.Shrink,
                    enabled = !ui.chat.running && !ui.compressing, loading = ui.compressing, modifier = Modifier.testTag("context.compress"),
                )
                if (ui.chat.running) Text(stringResource(R.string.chat_insight_compress_wait), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                ui.notice?.takeIf { !ui.compressing }?.let { ChatNoticeLine(it, chat::dismissNotice) }
                ErrorNotice(ui.error)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold, color = LocalTokens.current.textMuted, modifier = Modifier.padding(top = 6.dp))
}

/** A bar of segments over the whole; the free part is the track showing through. */
@Composable
private fun Bar(shares: List<Double>, colors: List<Color>) {
    val t = LocalTokens.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(t.surface2), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            shares.forEachIndexed { index, share ->
                if (share > 0) Box(Modifier.weight(share.toFloat().coerceAtLeast(0.005f)).height(8.dp).background(colors[index]))
            }
            val rest = 1.0 - shares.sum()
            if (rest > 0.0001) Box(Modifier.weight(rest.toFloat()).height(8.dp))
        }
    }
}

private fun sourceText(source: ChatInsight.Source): Int = when (source) {
    ChatInsight.Source.REPORTED -> R.string.chat_insight_source_reported
    ChatInsight.Source.AGENT_ESTIMATE -> R.string.chat_insight_source_agent_estimate
    ChatInsight.Source.ESTIMATE -> R.string.chat_insight_source_estimate
}

@Composable
private fun categoryName(id: String): String = stringResource(
    when (id) {
        "system_prompt" -> R.string.chat_insight_category_system_prompt
        "tool_definitions" -> R.string.chat_insight_category_tool_definitions
        "rules" -> R.string.chat_insight_category_rules
        "skills" -> R.string.chat_insight_category_skills
        "mcp" -> R.string.chat_insight_category_mcp
        "subagent_definitions" -> R.string.chat_insight_category_subagent_definitions
        "memory" -> R.string.chat_insight_category_memory
        else -> R.string.chat_insight_category_conversation
    },
)

// ---------------------------------------------------------------------- runs

/** The chat's runs, newest first: status, model, when, how long, tokens and cost. */
@Composable
private fun RunsSheet(insight: ChatInsightViewModel, onDismiss: () -> Unit) {
    val seen by insight.ui.collectAsState()
    val revision = ChatInsight.changesRevision(seen.ended.map { it.id })
    val list = rememberPagedList<Run>(revision, key = { it.id }) { cursor ->
        val api = insight.api ?: throw hub.core.android.data.HubError(401, "unauthorized", null)
        val page = api.runs(insight.sessionId, insight.profile, cursor)
        ListPage(ChatInsight.history(page.items), page.nextCursor)
    }
    HubSheet(onDismiss, Modifier.testTag("sheet.runs"), title = stringResource(R.string.chat_insight_runs)) {
        ListScaffold(
            list, key = { it.id }, modifier = Modifier.heightIn(max = 640.dp),
            emptyTitle = stringResource(R.string.chat_insight_runs_empty), emptyIcon = Lucide.RotateCcwClock, tag = "runs",
            contentPadding = PaddingValues(vertical = 4.dp),
        ) { run -> RunRow(run) }
    }
}

@Composable
fun RunRow(run: Run) {
    val t = LocalTokens.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val live = ChatInsight.tone(run.status) == ChatInsight.RunTone.RUNNING
    LaunchedEffect(live) { while (live) { delay(1000); now = System.currentTimeMillis() } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surface).padding(horizontal = 12.dp, vertical = 8.dp).testTag("runs.row"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hub.core.android.ui.kit.Badge(stringResource(runStatusText(run.status)), tone = runTone(run.status))
            run.model?.let {
                Text(it.substringAfterLast('/'), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            } ?: Box(Modifier.weight(1f))
            Text(localTime(run.startedAt ?: run.createdAt), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(runDetails(run, now, live), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        if (run.status == hub.core.client.model.RunStatus.FAILED) {
            run.error?.error?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun runDetails(run: Run, now: Long, live: Boolean): String {
    val parts = mutableListOf<String>()
    val nowTime = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC)
    ChatInsight.elapsedMs(run.startedAt, if (live) null else (run.finishedAt ?: run.updatedAt), nowTime)?.let { parts += ChatInsight.clock(it) }
    run.usage?.let { usage ->
        parts += stringResource(R.string.chat_insight_tokens, ChatInsight.number(usage.inputTokens), ChatInsight.number(usage.outputTokens))
        ChatInsight.cost(usage.cost)?.let { parts += it }
    }
    if (run.interrupted) parts += stringResource(R.string.chat_insight_run_interrupted)
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

private fun runStatusText(status: hub.core.client.model.RunStatus): Int = when (status) {
    hub.core.client.model.RunStatus.QUEUED -> R.string.chat_insight_run_status_queued
    hub.core.client.model.RunStatus.RUNNING -> R.string.chat_insight_run_status_running
    hub.core.client.model.RunStatus.WAITING -> R.string.chat_insight_run_status_waiting
    hub.core.client.model.RunStatus.SUCCEEDED -> R.string.chat_insight_run_status_succeeded
    hub.core.client.model.RunStatus.FAILED -> R.string.chat_insight_run_status_failed
    hub.core.client.model.RunStatus.CANCELLED -> R.string.chat_insight_run_status_cancelled
}

private fun runTone(status: hub.core.client.model.RunStatus): BadgeTone = when (ChatInsight.tone(status)) {
    ChatInsight.RunTone.RUNNING -> BadgeTone.Warning
    ChatInsight.RunTone.GOOD -> BadgeTone.Success
    ChatInsight.RunTone.BAD -> BadgeTone.Danger
    ChatInsight.RunTone.QUIET -> BadgeTone.Neutral
}
