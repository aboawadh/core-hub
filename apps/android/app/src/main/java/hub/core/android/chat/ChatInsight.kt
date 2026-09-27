package hub.core.android.chat

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.client.model.ContextUsage
import hub.core.client.model.Money
import hub.core.client.model.Run
import hub.core.client.model.RunChanges
import hub.core.client.model.RunChangesList
import hub.core.client.model.RunFileChangeKind
import hub.core.client.model.RunFileDiff
import hub.core.client.model.RunFileDiffState
import hub.core.client.model.RunStatus
import hub.core.client.model.SessionCompressRequest
import hub.core.client.model.SessionContextBreakdown
import hub.core.client.model.SessionContextCategory
import hub.core.client.model.SessionFileList
import hub.core.client.model.SessionsListRuns200Response
import hub.core.client.model.Subagent
import hub.core.client.model.SubagentList
import hub.core.client.model.SubagentStatus
import hub.core.client.model.SubagentSteer
import hub.core.client.model.SubagentSteerResult
import hub.core.client.model.SubagentTail
import java.time.OffsetDateTime
import java.util.Locale

/**
 * What a chat can tell about itself (apps batch 6), from the same contract operations as the web's
 * chat: how full the model's window is and what fills it (`sessions.getContextBreakdown`, §57/§102),
 * compressing with a focus (`sessions.compress`), the chat's runs (`sessions.listRuns`), the
 * subagents its agent delegated to (`sessions.listSubagents`, `.interruptSubagent`, `.steerSubagent`,
 * `.tailSubagent`, §56), the files each run changed with their diffs (`sessions.listChanges`,
 * `.getRunChanges`, `.getRunChangeDiff`, §49/§102), and the conversation's files (`sessions.listFiles`,
 * §48). Plain rules, tested in ChatInsightTest; iOS `Chat/ChatInsight.swift` is their twin.
 */
object ChatInsight {
    // ------------------------------------------------------------------ the context window

    /** Where the figure came from; it is always said, never implied (web `ContextRing.tsx`). */
    enum class Source(val key: String) { REPORTED("reported"), AGENT_ESTIMATE("agent_estimate"), ESTIMATE("estimate") }

    data class Use(val used: Int, val window: Int, val ratio: Double, val source: Source)

    /**
     * How full the window is: the agent's report, else the last turn the provider counted against the
     * catalogue's `context_window`. Nothing known is no ring — never an invented number.
     */
    fun use(reported: ContextUsage?, window: Int?, runs: List<Run>): Use? {
        val reportedWindow = reported?.windowTokens ?: window
        if (reported != null && reportedWindow != null && reportedWindow > 0) {
            return Use(
                reported.usedTokens, reportedWindow, minOf(1.0, reported.usedTokens.toDouble() / reportedWindow),
                if (reported.estimated == true) Source.AGENT_ESTIMATE else Source.REPORTED,
            )
        }
        if (window == null || window <= 0) return null
        val last = runs.filter { (it.usage?.inputTokens ?: 0) > 0 }.maxByOrNull { it.startedAt ?: it.createdAt } ?: return null
        val usage = last.usage ?: return null
        val used = usage.inputTokens + usage.outputTokens
        return Use(used, window, minOf(1.0, used.toDouble() / window), Source.ESTIMATE)
    }

    /** A whole percentage, floored: 99.6 % of a window is not «100 %». */
    fun percent(use: Use): Int = kotlin.math.floor(use.ratio * 100).toInt()

    enum class Band { NORMAL, WARNING, DANGER }

    /** Three bands, because a number alone does not say whether to act. */
    fun band(use: Use): Band = when {
        use.ratio >= 0.9 -> Band.DANGER
        use.ratio >= 0.7 -> Band.WARNING
        else -> Band.NORMAL
    }

    /** The categories named in the person's language (Hermes's today, §102); any other keeps the agent's label. */
    val KNOWN_CATEGORIES = listOf(
        "system_prompt", "tool_definitions", "rules", "skills", "mcp", "subagent_definitions", "memory", "conversation",
    )

    /** Each category's share of the bar: over the whole window, or over their sum when the rough counts add up to more. */
    fun shares(categories: List<SessionContextCategory>, window: Int): List<Double> {
        val whole = maxOf(window, categories.sumOf { it.tokens })
        if (whole <= 0) return categories.map { 0.0 }
        return categories.map { it.tokens.toDouble() / whole }
    }

    /** What «Compress» sends: the focus typed (trimmed, at most 2000 characters), or an empty body — the whole chat. */
    fun compressRequest(focus: String): SessionCompressRequest {
        val text = focus.trim()
        return if (text.isEmpty()) SessionCompressRequest() else SessionCompressRequest(focus = text.take(2000))
    }

    /** A count with Latin digits and grouping, in both languages (DECISIONS §113). */
    fun number(value: Int): String = String.format(Locale.US, "%,d", value)

    // ------------------------------------------------------------------ runs

    /** Newest first; a run not started yet by when it was asked for. */
    fun history(runs: List<Run>): List<Run> = runs.sortedByDescending { it.startedAt ?: it.createdAt }

    /** How long it ran, or has been running up to [now]; null before it started. */
    fun elapsedMs(started: OffsetDateTime?, finished: OffsetDateTime?, now: OffsetDateTime = OffsetDateTime.now()): Long? {
        started ?: return null
        return maxOf(0L, java.time.Duration.between(started, finished ?: now).toMillis())
    }

    /** `0:05`, `12:40`, `1:02:03` — a clock, the same digits in both languages (web `subagents.ts`). */
    fun clock(ms: Long): String {
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        fun two(n: Long) = if (n < 10) "0$n" else "$n"
        return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "$minutes:${two(seconds)}"
    }

    /** What a run cost, as the web's turn says it: none without a price, `0 USD` when free, else rounded where the digits stop being noise. */
    fun cost(money: Money?): String? {
        money ?: return null
        val value = money.amount.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        if (value == 0.0) return "0 ${money.currency}"
        val digits = if (value < 0.01) 4 else 2
        return String.format(Locale.US, "%.${digits}f", value) + " ${money.currency}"
    }

    enum class RunTone { RUNNING, GOOD, BAD, QUIET }

    fun tone(status: RunStatus): RunTone = when (status) {
        RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.WAITING -> RunTone.RUNNING
        RunStatus.SUCCEEDED -> RunTone.GOOD
        RunStatus.FAILED -> RunTone.BAD
        RunStatus.CANCELLED -> RunTone.QUIET
    }

    // ------------------------------------------------------------------ subagents

    data class SubagentRow(val subagent: Subagent, val indent: Int)

    /**
     * The running ones as a tree — each after the one that started it, siblings in the order they
     * started — and the finished ones, newest first. One whose parent is not running is drawn at its
     * own depth, never lost (web `splitSubagents`).
     */
    fun split(items: List<Subagent>): Pair<List<SubagentRow>, List<Subagent>> {
        val running = items.filter { it.status == SubagentStatus.RUNNING }.sortedBy { it.startedAt }
        val ids = running.map { it.id }.toSet()
        val children = mutableMapOf<String, MutableList<Subagent>>()
        val roots = mutableListOf<Subagent>()
        for (item in running) {
            val parent = item.parentId
            if (parent != null && parent != item.id && parent in ids) children.getOrPut(parent) { mutableListOf() } += item
            else roots += item
        }
        val rows = mutableListOf<SubagentRow>()
        val seen = mutableSetOf<String>()
        fun walk(item: Subagent, indent: Int) {
            if (!seen.add(item.id)) return
            rows += SubagentRow(item, indent)
            children[item.id].orEmpty().forEach { walk(it, indent + 1) }
        }
        roots.forEach { walk(it, if (it.parentId != null) maxOf(0, it.depth) else 0) }
        val finished = items.filter { it.status != SubagentStatus.RUNNING }.sortedByDescending { it.finishedAt ?: it.startedAt }
        return rows to finished
    }

    /** One subagent's latest state into the list, in place, or added. */
    fun upsert(list: List<Subagent>, next: Subagent): List<Subagent> {
        val at = list.indexOfFirst { it.id == next.id }
        return if (at < 0) list + next else list.toMutableList().also { it[at] = next }
    }

    /** A note for a subagent: trimmed, at most 4000 characters; nothing typed is no note. */
    fun steerText(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }?.take(4000)

    // ------------------------------------------------------------------ changed files and their diffs

    enum class DiffKind { CONTEXT, ADD, DEL, NOTE }

    data class DiffLine(val kind: DiffKind, val old: Int?, val new: Int?, val text: String)

    data class DiffHunk(val header: String, val lines: List<DiffLine>)

    private val HUNK = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")

    /** The hunks of a unified diff, each line with its old and new number (web `parseUnifiedDiff`). */
    fun parseDiff(text: String): List<DiffHunk> {
        val hunks = mutableListOf<Pair<String, MutableList<DiffLine>>>()
        var oldLine = 0
        var newLine = 0
        val lines = text.split("\n").let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        for (line in lines) {
            val header = HUNK.find(line)
            if (header != null) {
                hunks += line to mutableListOf()
                oldLine = header.groupValues[1].toInt()
                newLine = header.groupValues[2].toInt()
                continue
            }
            val current = hunks.lastOrNull()?.second ?: continue
            val body = line.drop(1)
            when (line.firstOrNull()) {
                '+' -> current += DiffLine(DiffKind.ADD, null, newLine++, body)
                '-' -> current += DiffLine(DiffKind.DEL, oldLine++, null, body)
                '\\' -> current += DiffLine(DiffKind.NOTE, null, null, line.drop(2))
                else -> current += DiffLine(DiffKind.CONTEXT, oldLine++, newLine++, body)
            }
        }
        return hunks.map { DiffHunk(it.first, it.second) }
    }

    /** `+14 −2`, or nothing when the hub could not count the lines (binary, no copy of before). */
    fun counts(additions: Int?, deletions: Int?): String? =
        if (additions == null && deletions == null) null else "+${additions ?: 0} −${deletions ?: 0}"

    /** A file the run left behind opens in the viewer; a deleted one has nothing to open. */
    fun canOpen(change: RunFileChangeKind): Boolean = change != RunFileChangeKind.DELETED

    enum class NoDiff { LIVE, BINARY, TOO_LARGE, UNAVAILABLE }

    /** What the diff page says instead of a diff, as the file's entry tells it; null when there is one to read. */
    fun noDiff(state: RunFileDiffState, live: Boolean): NoDiff? = when {
        live -> NoDiff.LIVE
        state == RunFileDiffState.AVAILABLE -> null
        state == RunFileDiffState.BINARY -> NoDiff.BINARY
        state == RunFileDiffState.TOO_LARGE -> NoDiff.TOO_LARGE
        else -> NoDiff.UNAVAILABLE
    }

    /** Which recorded changes are still current: the runs that ended (a new one means read again). */
    fun changesRevision(runIds: Collection<String>): String = runIds.sorted().joinToString(",")

    /** The last part of a path, for a file's row and the viewer. */
    fun fileName(path: String): String = path.substringAfterLast('/').ifEmpty { path }
}

/** The calls behind the insight, each in the chat's own profile. */
class ChatInsightApi(private val api: HubApis) {
    suspend fun breakdown(id: String, profile: String): SessionContextBreakdown = api.sessions.sessionsGetContextBreakdown(profile, id)

    suspend fun runs(id: String, profile: String, cursor: String?): SessionsListRuns200Response =
        api.sessions.sessionsListRuns(profile, id, cursor = cursor, limit = 30)

    suspend fun subagents(id: String, profile: String): SubagentList = api.sessions.sessionsListSubagents(profile, id)

    suspend fun interrupt(id: String, profile: String, subagent: String): Subagent = api.sessions.sessionsInterruptSubagent(profile, id, subagent)

    suspend fun steer(id: String, profile: String, subagent: String, text: String): SubagentSteerResult =
        api.sessions.sessionsSteerSubagent(profile, id, subagent, SubagentSteer(text))

    suspend fun tail(id: String, profile: String, subagent: String): SubagentTail = api.sessions.sessionsTailSubagent(profile, id, subagent)

    suspend fun changes(id: String, profile: String, cursor: String?): RunChangesList = api.sessions.sessionsListChanges(profile, id, cursor = cursor, limit = 20)

    /** What a run still going has changed so far (`live: true`); null when it has nothing to compare or has just ended. */
    suspend fun liveChanges(id: String, profile: String, run: String): RunChanges? =
        try {
            api.sessions.sessionsGetRunChanges(profile, id, run)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = HubError.from(e)
            if (error.status == 404) null else throw error
        }

    suspend fun diff(id: String, profile: String, run: String, path: String): RunFileDiff = api.sessions.sessionsGetRunChangeDiff(profile, id, run, path)

    suspend fun files(id: String, profile: String): SessionFileList = api.sessions.sessionsListFiles(profile, id)
}
