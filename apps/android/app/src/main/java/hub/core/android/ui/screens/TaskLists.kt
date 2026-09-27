package hub.core.android.ui.screens

import hub.core.client.model.BulkResult
import hub.core.client.model.Project
import hub.core.client.model.ProjectStatus
import hub.core.client.model.ProjectWrite
import hub.core.client.model.Subtask
import hub.core.client.model.SubtaskWrite
import hub.core.client.model.Task
import hub.core.client.model.TaskCheckItem
import hub.core.client.model.TaskCheckItemWrite
import hub.core.client.model.TaskPatch
import hub.core.client.model.TaskStatus

/*
 * Tasks II (batch 5), apart from the screens so they are unit-tested: a task's checklist
 * (subtasks), its definition of done and constraints (§104), the board's bulk edits (§103) and the
 * profile's projects. The web's rules (packages/web/src/tasks/CheckList.tsx, TasksScreen.tsx,
 * ProjectDialog.tsx, queries.ts) in the phone's words; iOS's TaskListsRules.swift is the twin.
 */

/** A task's checklist: lines ticked, added, dragged into another order, deleted. */
object SubtaskRules {
    const val TITLE_MAX = 300

    /** A tick: a done line goes back to do, any other line is done. */
    fun toggled(status: Subtask.Status): SubtaskWrite.Status =
        if (status == Subtask.Status.DONE) SubtaskWrite.Status.TODO else SubtaskWrite.Status.DONE

    /** The title to add, or null when there is nothing to add. */
    fun title(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }?.take(TITLE_MAX)

    /** The lines' ids with the one at [from] put at [to] (both places in the list as it reads). */
    fun order(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices) return ids
        val out = ids.toMutableList()
        val moved = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), moved)
        return out
    }

    /**
     * What the hub is told after a drag: each line's new place, only for the lines whose place
     * changed (the hub keeps an `index` per line and orders by it).
     */
    fun reindex(lines: List<Subtask>, order: List<String>): List<Pair<String, Int>> {
        val now = lines.associate { it.id to it.index }
        return order.mapIndexedNotNull { place, id -> now[id]?.takeIf { it != place }?.let { id to place } }
    }

    /** Where a line dragged by [dy] pixels lands, rows being [rowHeight] tall. */
    fun landing(from: Int, dy: Float, rowHeight: Float, count: Int): Int =
        if (rowHeight <= 0f) from else (from + Math.round(dy / rowHeight)).coerceIn(0, (count - 1).coerceAtLeast(0))

    fun doneCount(lines: List<Subtask>): Int = lines.count { it.status == Subtask.Status.DONE }
}

/**
 * A definition of done or the constraints (§104): lines the person writes, the agent is given
 * with every run, and the reviewer ticks while the task is in review. The whole list is sent.
 */
object CheckLines {
    enum class Kind { DONE, CONSTRAINTS }

    const val LINE_MAX = 500
    const val LINES_MAX = 30

    /** Only the reviewer ticks, and only while the task is in review. */
    fun canTick(status: TaskStatus): Boolean = status == TaskStatus.REVIEW

    /** The list with a new line, or null when the line is empty or the list is full. */
    fun adding(items: List<TaskCheckItem>, typed: String): List<TaskCheckItem>? {
        val t = typed.trim()
        if (t.isEmpty() || items.size >= LINES_MAX) return null
        return items + TaskCheckItem(text = t.take(LINE_MAX), checked = false)
    }

    fun removing(items: List<TaskCheckItem>, at: Int): List<TaskCheckItem> = items.filterIndexed { i, _ -> i != at }

    fun ticking(items: List<TaskCheckItem>, at: Int, checked: Boolean): List<TaskCheckItem> =
        items.mapIndexed { i, item -> if (i == at) item.copy(checked = checked) else item }

    /** The edit that replaces one list. */
    fun patch(kind: Kind, items: List<TaskCheckItem>): TaskPatch {
        val lines = items.map { TaskCheckItemWrite(text = it.text, checked = it.checked) }
        return if (kind == Kind.DONE) TaskPatch(definitionOfDone = lines) else TaskPatch(constraints = lines)
    }
}

/**
 * The same change on many cards (§103). The board holds every profile, so the cards go in one
 * call per profile they are in, at most a hundred ids a call.
 */
object BulkRules {
    const val PER_CALL = 100

    /** The ticked cards by profile, in the order they were met, each list cut to calls. */
    fun calls(tasks: List<Task>): List<Pair<String, List<String>>> =
        tasks.groupBy({ it.profile }, { it.id }).flatMap { (profile, ids) -> ids.chunked(PER_CALL).map { profile to it } }

    /** `tasks.bulkDeleteTasks` takes the ids in one query value, separated by commas. */
    fun idsParam(ids: List<String>): String = ids.joinToString(",")

    /** How many were changed and how many the hub (or Hermes) refused. */
    fun tally(answers: List<BulkResult>): Pair<Int, Int> {
        val all = answers.flatMap { it.results }
        val changed = all.count { it.ok }
        return changed to all.size - changed
    }

    /** The ids the hub refused: they stay ticked. */
    fun refused(answers: List<BulkResult>): Set<String> = answers.flatMap { it.results }.filterNot { it.ok }.map { it.id }.toSet()

    /** Archiving is for finished work: only a done card can go (the hub refuses the rest). */
    fun archivable(tasks: List<Task>): Boolean = tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.DONE }
}

/** A profile's projects: made, renamed, paused or archived, their repository set, deleted. */
object ProjectRules {
    const val NAME_MAX = 120
    val STATUSES = listOf(ProjectStatus.ACTIVE, ProjectStatus.PAUSED, ProjectStatus.ARCHIVED)

    /** The edit form filled from a project; empty for a new one. */
    fun values(project: Project?): Map<String, String> = mapOf(
        "name" to project?.name.orEmpty(),
        "status" to (project?.status ?: ProjectStatus.ACTIVE).value,
        "repository" to project?.workingDir.orEmpty(),
        "branch" to project?.defaultBranch.orEmpty(),
    )

    /** A new project: its name, and its repository and branch when given. */
    fun create(values: Map<String, String>): ProjectWrite = ProjectWrite(
        name = values["name"].orEmpty().trim().take(NAME_MAX),
        workingDir = values["repository"].orEmpty().trim().ifEmpty { null },
        defaultBranch = values["branch"].orEmpty().trim().ifEmpty { null },
    )

    /**
     * Only what changed, or null. A repository emptied is sent as `null` (§114): the project's
     * tasks then work in their conversation's own folder. An emptied branch is left as it was
     * (the hub keeps one), as on the web.
     */
    fun patch(original: Map<String, String>, values: Map<String, String>): ProjectWrite? {
        val name = values["name"].orEmpty().trim().takeIf { it.isNotEmpty() && it != original["name"] }?.take(NAME_MAX)
        val status = values["status"]?.takeIf { it != original["status"] }?.let { raw -> ProjectStatus.entries.firstOrNull { it.value == raw } }
        val repository = values["repository"].orEmpty().trim().takeIf { it != original["repository"].orEmpty() }
        val branch = values["branch"].orEmpty().trim().takeIf { it.isNotEmpty() && it != original["branch"].orEmpty() }
        if (name == null && status == null && repository == null && branch == null) return null
        return ProjectWrite(
            name = name, status = status, workingDir = repository?.ifEmpty { null }, defaultBranch = branch,
            sendNull = if (repository?.isEmpty() == true) setOf(ProjectWrite.Clearable.WORKING_DIR) else emptySet(),
        )
    }

    /** Archive, or bring back from the archive. */
    fun toggledArchive(status: ProjectStatus): ProjectStatus = if (status == ProjectStatus.ARCHIVED) ProjectStatus.ACTIVE else ProjectStatus.ARCHIVED
}
