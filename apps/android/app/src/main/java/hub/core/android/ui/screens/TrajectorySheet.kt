package hub.core.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.Tone
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.ItemShape
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Trajectory
import hub.core.client.model.TrajectoryLane
import hub.core.client.model.TrajectoryStep
import hub.core.client.model.TrajectoryStepKind
import hub.core.client.model.TrajectoryStepStatus
import hub.core.client.model.TrajectoryTiming
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * A conversation's trajectory (contract decision §43; the web's Trajectory tab), on the phone as a
 * sheet from the chat's ⋯ menu: the metrics the hub has, a timeline of four lanes (input, model,
 * tools, subagents) over one time axis, and the steps as a list that filters (turns, calls),
 * orders by duration and searches; a step opens to its words, its tool's arguments and result.
 * «Session log» shares the same document as a file. Read again every 2 s while a run is going.
 * Native since 2026-09-27.
 */

/** The step list's filter, as the web's (`StepFilter`). */
data class StepFilter(val turns: Boolean = false, val calls: Boolean = false, val byDuration: Boolean = false, val query: String = "")

object TrajectoryRules {
    const val LIVE_REFRESH_MS = 2_000L

    /** How long a step took, a running one up to [now]; null when it was not recorded. */
    fun durationOf(step: TrajectoryStep, now: Long): Long? {
        step.durationMs?.let { return it.toLong() }
        val start = step.startedAt ?: return null
        return if (step.endedAt == null && step.status == TrajectoryStepStatus.RUNNING) (now - start.toInstant().toEpochMilli()).coerceAtLeast(0) else null
    }

    private fun haystack(step: TrajectoryStep): String {
        val call = step.toolCall
        val parts = mutableListOf(step.text.orEmpty())
        if (call != null) {
            parts += listOf(call.name, call.preview.orEmpty(), call.output.orEmpty())
            call.arguments?.let { parts += it.toString() }
        }
        return parts.joinToString("\n").lowercase()
    }

    /** «Turns» and «Calls» narrow to that kind (both: both); «Duration» orders longest first, unknown last. */
    fun filter(steps: List<TrajectoryStep>, filter: StepFilter, now: Long): List<TrajectoryStep> {
        val query = filter.query.trim().lowercase()
        val kept = steps.filter { step ->
            if (filter.turns || filter.calls) {
                val isTurn = step.kind == TrajectoryStepKind.TURN || step.kind == TrajectoryStepKind.REASONING
                val isCall = step.kind == TrajectoryStepKind.TOOL
                if (!((filter.turns && isTurn) || (filter.calls && isCall))) return@filter false
            }
            query.isEmpty() || haystack(step).contains(query)
        }
        if (!filter.byDuration) return kept
        return kept.withIndex().sortedWith(compareByDescending<IndexedValue<TrajectoryStep>> { durationOf(it.value, now) ?: -1L }.thenBy { it.index }).map { it.value }
    }

    /** One line of a step: the words, or the tool's preview (else its arguments), shortened. */
    fun summary(step: TrajectoryStep, max: Int = 140): String {
        val call = step.toolCall
        val text = if (call != null) call.preview?.takeIf { it.isNotBlank() } ?: call.arguments?.let { it.toString() }.orEmpty() else step.text.orEmpty()
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length > max) line.take(max) + "…" else line
    }

    /** The span a step takes on the axis, a running one up to [now]; null without times. */
    fun span(step: TrajectoryStep, now: Long): Pair<Long, Long>? {
        val start = step.startedAt?.toInstant()?.toEpochMilli() ?: return null
        val end = step.endedAt?.toInstant()?.toEpochMilli()
        return when {
            end != null -> start to maxOf(start, end)
            step.status == TrajectoryStepStatus.RUNNING -> start to maxOf(start, now)
            else -> start to start
        }
    }

    /** Longer than this with nothing happening is folded out of the axis (the web's IDLE_MS). */
    const val IDLE_MS = 3_000L
    /** What a folded gap is drawn as (FOLD_MS). */
    const val FOLD_MS = 600L

    /** Where an instant sits on the axis (0 at its start, 1 at its end), and where the folded gaps are. */
    class Axis(val at: (Long) -> Float, val folds: List<Float>)

    /**
     * The axis over some spans with idle stretches folded, as the web's `axisOf`: spans that touch
     * or come within [idleMs] of each other are one stretch of work; between stretches the gap is
     * drawn as [foldMs], so the bars keep their true proportions within each stretch.
     */
    fun axis(spans: List<Pair<Long, Long>>, idleMs: Long = IDLE_MS, foldMs: Long = FOLD_MS): Axis {
        val blocks = mutableListOf<LongArray>()
        for ((start, end) in spans.sortedBy { it.first }) {
            val last = blocks.lastOrNull()
            if (last != null && start - last[1] <= idleMs) last[1] = maxOf(last[1], end) else blocks += longArrayOf(start, end)
        }
        if (blocks.isEmpty()) return Axis({ 0f }, emptyList())
        val offsets = mutableListOf<Long>()
        var total = 0L
        blocks.forEachIndexed { i, b ->
            if (i > 0) total += foldMs
            offsets += total
            total += b[1] - b[0]
        }
        val length = maxOf(total, 1L).toFloat()
        val at: (Long) -> Float = { time ->
            var index = blocks.indexOfFirst { time <= it[1] }
            if (index < 0) index = blocks.lastIndex
            val b = blocks[index]
            val within = time.coerceIn(b[0], b[1]) - b[0]
            ((offsets[index] + within) / length).coerceIn(0f, 1f)
        }
        return Axis(at, offsets.drop(1).map { (it - foldMs / 2f) / length })
    }

    /** Sub-rows for a lane, so calls that ran at the same time do not hide each other (the web's `packRows`). */
    fun packRows(spans: List<Pair<Long, Long>>): List<Int> {
        val rowsEnd = mutableListOf<Long>()
        val rows = IntArray(spans.size)
        spans.withIndex().sortedBy { it.value.first }.forEach { (index, span) ->
            var row = rowsEnd.indexOfFirst { it <= span.first }
            if (row < 0) {
                row = rowsEnd.size
                rowsEnd += span.second
            } else {
                rowsEnd[row] = span.second
            }
            rows[index] = row
        }
        return rows.toList()
    }

    /** A duration as people read it: 850 ms, 4.2 s, 3 min 12 s (Latin digits). */
    fun format(ms: Long, units: Triple<String, String, String>): String {
        val (msUnit, s, min) = units
        if (ms < 1000) return "$ms $msUnit"
        if (ms < 60_000) return "${java.math.BigDecimal(ms).divide(java.math.BigDecimal(1000), 1, RoundingMode.HALF_UP)} $s"
        val minutes = ms / 60_000
        val seconds = Math.round((ms % 60_000) / 1000.0)
        return if (seconds == 0L) "$minutes $min" else "$minutes $min $seconds $s"
    }
}

@Composable
private fun units() = Triple(stringResource(R.string.traj_unit_ms), stringResource(R.string.traj_unit_s), stringResource(R.string.traj_unit_min))

@Composable
private fun laneLabel(lane: TrajectoryLane): String = stringResource(
    when (lane) {
        TrajectoryLane.INPUT -> R.string.traj_lane_input
        TrajectoryLane.MODEL -> R.string.traj_lane_model
        TrajectoryLane.TOOLS -> R.string.traj_lane_tools
        TrajectoryLane.SUBAGENTS -> R.string.traj_lane_subagents
    },
)

@Composable
private fun kindLabel(kind: TrajectoryStepKind): String = stringResource(
    when (kind) {
        TrajectoryStepKind.INPUT -> R.string.traj_kind_input
        TrajectoryStepKind.TURN -> R.string.traj_kind_turn
        TrajectoryStepKind.REASONING -> R.string.traj_kind_reasoning
        TrajectoryStepKind.TOOL -> R.string.traj_kind_tool
        TrajectoryStepKind.SUBAGENT -> R.string.traj_kind_subagent
    },
)

private fun laneIcon(lane: TrajectoryLane): Int = when (lane) {
    TrajectoryLane.INPUT -> Lucide.MessageSquareText
    TrajectoryLane.MODEL -> Lucide.Sparkles
    TrajectoryLane.TOOLS -> Lucide.Wrench
    TrajectoryLane.SUBAGENTS -> Lucide.Bot
}

/** The chat's ⋯ entry opens this. */
@Composable
fun TrajectorySheet(sessionId: String, profile: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var data by remember(sessionId) { mutableStateOf<Trajectory?>(null) }
    var error by remember(sessionId) { mutableStateOf<HubError?>(null) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId, profile) {
        while (true) {
            val s = graph.store.current ?: break
            hubCall { graph.apis(s).sessions.sessionsGetTrajectory(profile, sessionId) }
                .onSuccess { data = it; error = null }.onFailure { error = it as HubError }
            if (data?.live != true) break
            delay(TrajectoryRules.LIVE_REFRESH_MS)
        }
    }
    HubSheet(onClose, Modifier.testTag("trajectory.sheet"), title = stringResource(R.string.traj_tab)) {
        val d = data
        when {
            d == null && error == null -> Spinner(20.dp, LocalTokens.current.textMuted)
            d == null -> ErrorNotice(error)
            else -> {
                HubButton(
                    stringResource(R.string.traj_download),
                    {
                        sharing = true
                        scope.launch {
                            // The file the hub sends (`download=true`), as it is, to the share sheet.
                            val file = graph.store.current?.let { TrajectoryLog.download(graph, it, profile, sessionId, java.io.File(context.cacheDir, "attachments")) }
                            if (file != null) hub.core.android.ui.components.AttachmentFiles.share(context, file, "application/json")
                            sharing = false
                        }
                    },
                    kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Download, loading = sharing, modifier = Modifier.testTag("trajectory.download"),
                )
                TrajectoryBody(d)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrajectoryBody(data: Trajectory, now: Long = System.currentTimeMillis()) {
    val t = LocalTokens.current
    var filter by remember { mutableStateOf(StepFilter()) }
    var open by remember { mutableStateOf<String?>(null) }
    val u = units()
    Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (data.timing) {
            TrajectoryTiming.NONE -> if (data.steps.isNotEmpty()) Notice(stringResource(R.string.traj_untimed), Tone.INFO)
            TrajectoryTiming.PARTIAL -> Notice(stringResource(R.string.traj_partial), Tone.INFO)
            else -> Unit
        }
        if (data.steps.isEmpty()) {
            EmptyState(stringResource(R.string.traj_empty_title), Modifier.testTag("trajectory.empty"), body = stringResource(R.string.traj_empty_body), icon = Lucide.Route)
            return@Column
        }
        Metrics(data, u)
        Timeline(data, now) { id ->
            // The step opens in the list; a filter that hides it is cleared first.
            if (TrajectoryRules.filter(data.steps, filter, now).none { it.id == id }) filter = StepFilter()
            open = id
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(stringResource(R.string.traj_filter_turns), filter.turns, { filter = filter.copy(turns = !filter.turns) }, Modifier.testTag("trajectory.filter.turns"), size = ControlSize.Sm)
            Chip(stringResource(R.string.traj_filter_calls), filter.calls, { filter = filter.copy(calls = !filter.calls) }, Modifier.testTag("trajectory.filter.calls"), size = ControlSize.Sm)
            Chip(stringResource(R.string.traj_filter_duration), filter.byDuration, { filter = filter.copy(byDuration = !filter.byDuration) }, Modifier.testTag("trajectory.filter.duration"), size = ControlSize.Sm)
        }
        HubTextField(
            filter.query, { filter = filter.copy(query = it) }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.traj_search),
            leadingIcon = Lucide.Search, size = ControlSize.Md, fieldTag = "trajectory.search",
        )
        val steps = TrajectoryRules.filter(data.steps, filter, now)
        if (steps.isEmpty()) Text(stringResource(R.string.traj_no_match), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        steps.forEach { step -> StepRow(step, open == step.id, now, u) { open = if (open == step.id) null else step.id } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Metrics(data: Trajectory, u: Triple<String, String, String>) {
    val m = data.metrics
    val cells = buildList {
        add(stringResource(R.string.traj_metric_turns) to m.turns.toString())
        add(stringResource(R.string.traj_metric_steps) to m.steps.toString())
        m.modelMs?.let { add(stringResource(R.string.traj_metric_model_time) to TrajectoryRules.format(it.toLong(), u)) }
        m.toolMs?.let { add(stringResource(R.string.traj_metric_tool_time) to TrajectoryRules.format(it.toLong(), u)) }
        m.avgFirstTokenMs?.let { add(stringResource(R.string.traj_metric_first_token) to TrajectoryRules.format(it.toLong(), u)) }
        m.outputTokensPerSecond?.let { add(stringResource(R.string.traj_metric_tokens_per_second) to it.setScale(1, RoundingMode.HALF_UP).toPlainString()) }
        m.cacheHitPct?.let { add(stringResource(R.string.traj_metric_cache_hit) to it.setScale(0, RoundingMode.HALF_UP).toPlainString() + "%") }
        m.inputTokens?.let { add(stringResource(R.string.traj_metric_input_tokens) to it.toString()) }
        m.outputTokens?.let { add(stringResource(R.string.traj_metric_output_tokens) to it.toString()) }
    }
    val t = LocalTokens.current
    FlowRow(Modifier.fillMaxWidth().testTag("trajectory.metrics"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.forEach { (label, value) ->
            Column(Modifier.clip(ItemShape).background(t.surface2).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(value, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                Text(label, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
        }
    }
}

/**
 * The four lanes over one time axis (the web's timeline): idle stretches folded, calls that ran at
 * the same time on their own rows, and time running in the reading direction — right to left in
 * Arabic. A tap on a bar opens its step in the list below.
 */
@Composable
private fun Timeline(data: Trajectory, now: Long, onStep: (String) -> Unit) {
    val placed = data.steps.mapNotNull { s -> TrajectoryRules.span(s, now)?.let { s to it } }
    if (placed.isEmpty()) return
    val t = LocalTokens.current
    val axis = TrajectoryRules.axis(placed.map { it.second })
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().testTag("trajectory.timeline"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.traj_timeline), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted)
        TrajectoryLane.entries.forEach { lane ->
            val inLane = placed.filter { it.first.lane == lane }
            val rows = TrajectoryRules.packRows(inLane.map { it.second })
            val count = (rows.maxOrNull() ?: 0) + 1
            val colour = when (lane) {
                TrajectoryLane.INPUT -> t.chart3
                TrajectoryLane.MODEL -> t.accent
                TrajectoryLane.TOOLS -> t.chart2
                TrajectoryLane.SUBAGENTS -> t.chart4
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(laneLabel(lane), Modifier.width(72.dp), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val rowHeight = 10.dp
                val gap = 3.dp
                Canvas(
                    Modifier.weight(1f).height(rowHeight * count + gap * (count - 1)).clip(ItemShape).background(t.surface2)
                        .testTag("trajectory.lane.${lane.value}")
                        .pointerInput(inLane, rtl) {
                            detectTapGestures { tap ->
                                val row = (tap.y / (rowHeight + gap).toPx()).toInt()
                                val x = if (rtl) size.width - tap.x else tap.x
                                inLane.withIndex().firstOrNull { (i, entry) ->
                                    val x0 = axis.at(entry.second.first) * size.width
                                    val x1 = maxOf(axis.at(entry.second.second) * size.width, x0 + 12f)
                                    rows[i] == row && x in (x0 - 6f)..(x1 + 6f)
                                }?.let { onStep(it.value.first.id) }
                            }
                        },
                ) {
                    val h = rowHeight.toPx()
                    val g = gap.toPx()
                    fun edge(fraction: Float) = if (rtl) size.width * (1f - fraction) else size.width * fraction
                    inLane.forEachIndexed { i, (step, span) ->
                        val a = edge(axis.at(span.first))
                        val b = edge(axis.at(span.second))
                        val left = minOf(a, b)
                        drawRoundRect(
                            if (step.status == TrajectoryStepStatus.FAILED) t.danger else colour,
                            topLeft = Offset(left, rows[i] * (h + g)), size = Size(maxOf(kotlin.math.abs(b - a), 3f), h), cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                    // A folded gap: a thin break mark across the lane.
                    axis.folds.forEach { f -> drawRect(t.textFaint, topLeft = Offset(edge(f) - 0.75f, 0f), size = Size(1.5f, size.height)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepRow(step: TrajectoryStep, open: Boolean, now: Long, u: Triple<String, String, String>, onToggle: () -> Unit) {
    val t = LocalTokens.current
    val duration = TrajectoryRules.durationOf(step, now)
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(open) { if (open) bring.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(ItemShape).background(if (open) t.surface2 else t.surface).clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp).testTag("trajectory.step.${step.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIcon(laneIcon(step.lane), null, size = 14.dp, tint = t.textMuted)
            Text(
                step.toolCall?.name ?: kindLabel(step.kind), Modifier.weight(1f), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (step.status != TrajectoryStepStatus.SUCCEEDED) {
                Badge(
                    stringResource(
                        when (step.status) {
                            TrajectoryStepStatus.RUNNING -> R.string.traj_status_running
                            TrajectoryStepStatus.FAILED -> R.string.traj_status_failed
                            TrajectoryStepStatus.CANCELLED -> R.string.traj_status_cancelled
                            else -> R.string.traj_status_succeeded
                        },
                    ),
                    tone = if (step.status == TrajectoryStepStatus.FAILED) BadgeTone.Danger else if (step.status == TrajectoryStepStatus.RUNNING) BadgeTone.Info else BadgeTone.Neutral,
                )
            }
            duration?.let { Text(TrajectoryRules.format(it, u), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
        }
        val summary = TrajectoryRules.summary(step)
        Text(
            summary.ifEmpty { stringResource(if (step.toolCallOnly) R.string.traj_tool_call_only else R.string.traj_no_text) },
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(textDirection = TextDirection.Content),
        )
        if (open) StepDetail(step, u)
    }
}

@Composable
private fun StepDetail(step: TrajectoryStep, u: Triple<String, String, String>) {
    val t = LocalTokens.current
    var any = false
    step.model?.let { any = true; Text(stringResource(R.string.traj_model, it), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
    step.fallback?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
        any = true
        Text(stringResource(R.string.traj_fallback, failed.joinToString(", ") { it.model }), fontSize = FontTokens.sizeXs.sp, color = t.warningSoftText)
    }
    step.firstTokenMs?.let { any = true; Text(stringResource(R.string.traj_first_token_here, TrajectoryRules.format(it.toLong(), u)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
    if (step.toolCall == null && !step.text.isNullOrBlank()) {
        any = true
        Text(step.text!!, fontSize = FontTokens.sizeSm.sp, style = TextStyle(textDirection = TextDirection.Content))
    }
    step.toolCall?.let { call ->
        call.arguments?.takeIf { it.isNotEmpty() }?.let {
            any = true
            Text(Serializer.kotlinxSerializationJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(it)),
                fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.text)
        }
        call.output?.takeIf { it.isNotBlank() }?.let {
            any = true
            Text(it.take(4000), fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
        }
    }
    if (!any) Text(stringResource(R.string.traj_no_details), fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
}

/** «Session log»: the document the hub sends with `download=true`, saved as it came, to share. */
object TrajectoryLog {
    fun fileName(sessionId: String, disposition: String?): String =
        disposition?.let { Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.takeIf { it.isNotBlank() } ?: "session-$sessionId-log.json"

    suspend fun download(
        graph: hub.core.android.AppGraph,
        session: hub.core.android.data.StoredSession,
        profile: String,
        sessionId: String,
        root: java.io.File,
    ): java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val config = graph.apis(session).sessions.sessionsGetTrajectoryRequestConfig(profile, sessionId, true)
        val base = (hub.core.android.data.apiBase(session.hub) + config.path).toHttpUrlOrNull() ?: return@withContext null
        val url = base.newBuilder().apply { config.query.forEach { (k, v) -> v.forEach { value -> addQueryParameter(k, value) } } }.build()
        val request = okhttp3.Request.Builder().url(url).apply { config.headers.forEach { (k, v) -> header(k, v) } }.build()
        runCatching {
            graph.http.authed.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                val out = java.io.File(java.io.File(root, "trajectory-$sessionId"), fileName(sessionId, response.header("Content-Disposition")))
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
                out
            }
        }.getOrNull()
    }
}
