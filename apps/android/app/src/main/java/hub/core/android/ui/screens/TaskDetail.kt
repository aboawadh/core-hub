package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.FormBody
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormKind
import hub.core.android.ui.components.FormOption
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.MarkdownView
import hub.core.android.ui.components.deleteTitle
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Assignee
import hub.core.client.model.Project
import hub.core.client.model.RunStatus
import hub.core.client.model.Task
import hub.core.client.model.TaskAllOfExternal
import hub.core.client.model.TaskAssign
import hub.core.client.model.TaskAssigned
import hub.core.client.model.TaskCreate
import hub.core.client.model.TaskDependencyState
import hub.core.client.model.TaskDetail
import hub.core.client.model.TaskMove
import hub.core.client.model.TaskPatch
import hub.core.client.model.TaskPriority
import hub.core.client.model.TaskStatus
import java.security.SecureRandom
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

/*
 * A task opened on its own (batch 2, Tasks I), the web's task dialog and card menu in one sheet:
 * its words (the description in Markdown), where it stands, who has it, when it is due, what it
 * waits for, the run it started with a way into that conversation — and what a person does to it:
 * move it, give it to an agent (and start), take it back, stop its run, edit, delete. Everything
 * goes to the task's own profile (ADR 0016). iOS's twin is TaskDetailView.swift / TaskRules.swift.
 */

/** What a task's detail offers and what its forms send, apart from the screen so it is unit-tested. */
object TaskRules {
    /** The choice that means "none" in the create form's project and agent pickers. */
    const val NONE = "none"

    val PRIORITIES = listOf(TaskPriority.LOW, TaskPriority.NORMAL, TaskPriority.HIGH, TaskPriority.URGENT)

    /**
     * The moves a person may make from [status] — the board's own table ([BoardRules]), inside its
     * column too (todo → ready) and on to the archive — each meaning once, so the detail never
     * offers a move the hub would refuse nor two buttons that say the same.
     */
    fun moves(status: TaskStatus): List<BoardRules.Drop> {
        val targets = listOf(TaskStatus.TODO, TaskStatus.READY, TaskStatus.SCHEDULED, TaskStatus.BLOCKED, TaskStatus.REVIEW, TaskStatus.DONE, TaskStatus.ARCHIVED)
        val seen = mutableSetOf<BoardRules.Action>()
        return targets.mapNotNull { to -> BoardRules.transitionFor(status, to)?.takeIf { seen.add(it.action) }?.let { BoardRules.Drop(to, it) } }
    }

    /** A card from Hermes's own board: Hermes runs and assigns it (§103). */
    fun fromHermes(external: TaskAllOfExternal?): Boolean = external?.source == TaskAllOfExternal.Source.HERMES

    fun canStop(status: TaskStatus): Boolean = status == TaskStatus.RUNNING

    /** A task can be given to an agent until it is finished; a Hermes card is not (Hermes dispatches it). */
    fun canAssign(status: TaskStatus, hermes: Boolean): Boolean = !hermes && status != TaskStatus.DONE && status != TaskStatus.ARCHIVED

    fun canUnassign(assignee: Assignee?, hermes: Boolean): Boolean = !hermes && assignee?.kind == Assignee.Kind.AGENT

    /** What it still waits for, said only before it runs (§93). */
    fun waiting(status: TaskStatus, waitingOn: List<TaskDependencyState>?): List<TaskDependencyState> =
        if (status in setOf(TaskStatus.RUNNING, TaskStatus.REVIEW, TaskStatus.DONE, TaskStatus.ARCHIVED)) emptyList() else waitingOn.orEmpty()

    /** How many of the tasks it depends on are done already. */
    fun doneDependencies(dependsOn: List<String>, waitingOn: List<TaskDependencyState>?): Int =
        (dependsOn.size - waitingOn.orEmpty().size).coerceAtLeast(0)

    /** The edit form filled from a task. */
    fun editValues(title: String, description: String?, priority: TaskPriority, projectId: String): Map<String, String> =
        mapOf("title" to title, "description" to description.orEmpty(), "priority" to priority.value, "project" to projectId)

    /**
     * Only what changed, or null when nothing did. A cleared description is sent as empty text:
     * the generated client leaves a null field out, so it cannot send `null`.
     */
    fun patch(original: Map<String, String>, values: Map<String, String>): TaskPatch? {
        val title = values["title"].orEmpty().trim().takeIf { it.isNotEmpty() && it != original["title"] }
        val description = values["description"].orEmpty().takeIf { it != original["description"].orEmpty() }
        val priority = values["priority"]?.takeIf { it != original["priority"] }?.let { raw -> TaskPriority.entries.firstOrNull { it.value == raw } }
        val project = values["project"]?.takeIf { it.isNotEmpty() && it != original["project"] }
        if (title == null && description == null && priority == null && project == null) return null
        return TaskPatch(title = title, description = description, priority = priority, projectId = project)
    }

    /**
     * A new task from the create form, and the agent to start it with when asked. Without a
     * project it lands in the profile's own list; given an agent without "start now" it is only
     * assigned. Starting is `tasks.assignTask(start = true)` after the create, the web's "Assign
     * and start".
     */
    fun create(values: Map<String, String>): Pair<TaskCreate, String?> {
        fun clean(key: String) = values[key].orEmpty().trim().takeIf { it.isNotEmpty() && it != NONE }
        val agent = clean("agent")
        val task = TaskCreate(
            title = clean("title").orEmpty(),
            projectId = clean("project"),
            description = clean("description"),
            priority = clean("priority")?.let { raw -> TaskPriority.entries.firstOrNull { it.value == raw } },
            assigneeAgentId = agent,
        )
        return task to agent?.takeIf { values["start"] == "true" }
    }

    /** The assign form's answer: who, what to tell it, whether it starts now. */
    fun assign(values: Map<String, String>): TaskAssign? {
        val agent = values["agent"]?.takeIf { it.isNotEmpty() } ?: return null
        return TaskAssign(agentId = agent, instructions = values["instructions"].orEmpty().trim().ifEmpty { null }, start = values["start"] == "true")
    }

    private val random = SecureRandom()
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A client-made ULID for `Idempotency-Key` (26 Crockford base32 characters, time first). */
    fun ulid(now: Long = System.currentTimeMillis()): String {
        val out = CharArray(26)
        var time = now
        for (i in 9 downTo 0) { out[i] = CROCKFORD[(time % 32).toInt()]; time /= 32 }
        for (i in 10 until 26) out[i] = CROCKFORD[random.nextInt(32)]
        return String(out)
    }
}

/** The fields the sheet shows, from the board's card until the full task arrives. */
data class TaskFacts(
    val id: String,
    val profile: String,
    val title: String,
    val description: String?,
    val status: TaskStatus,
    val priority: TaskPriority,
    val projectId: String,
    val assignee: Assignee?,
    val dueAt: OffsetDateTime?,
    val dependsOn: List<String>,
    val waitingOn: List<TaskDependencyState>?,
    val sessionId: String?,
    val latestSummary: String?,
    val reason: String?,
    val stuckSince: OffsetDateTime?,
    val hermes: Boolean,
) {
    companion object {
        fun of(t: Task) = TaskFacts(
            t.id, t.profile, t.title, t.description, t.status, t.priority, t.projectId, t.assignee, t.dueAt, t.dependsOn,
            t.waitingOn, t.sessionId, t.latestSummary, t.blockedReason ?: t.statusReason, t.stuckSince, TaskRules.fromHermes(t.external),
        )

        fun of(t: TaskDetail) = TaskFacts(
            t.id, t.profile, t.title, t.description, t.status, t.priority, t.projectId, t.assignee, t.dueAt, t.dependsOn,
            t.waitingOn, t.sessionId, t.latestSummary, t.blockedReason ?: t.statusReason, t.stuckSince, TaskRules.fromHermes(t.external),
        )
    }
}

/** A task's calls, each in the task's own profile. [apis] is null while nobody is signed in. */
class TaskOps(private val apis: () -> HubApis?) {
    private suspend fun <T> call(block: suspend (HubApis) -> T): Result<T> {
        val api = apis() ?: return Result.failure(HubError(401, "unauthorized", null))
        return hubCall { block(api) }
    }

    suspend fun detail(profile: String, id: String) = call { it.tasks.tasksGetTask(profile, id) }
    suspend fun projects(profile: String) = call { it.tasks.tasksListProjects(profile).items }
    suspend fun agents(profile: String) = call { ChatAgents.startable(it.agents.agentsList(profile).items) }
    suspend fun update(profile: String, id: String, patch: TaskPatch) = call { it.tasks.tasksUpdateTask(profile, id, patch) }
    suspend fun delete(profile: String, id: String) = call { it.tasks.tasksDeleteTask(profile, id) }
    suspend fun move(profile: String, id: String, to: TaskStatus, reason: String? = null) =
        call { it.tasks.tasksMoveTask(profile, id, TaskMove(status = to, reason = reason?.trim()?.ifEmpty { null })) }
    suspend fun assign(profile: String, id: String, request: TaskAssign): Result<TaskAssigned> = call { it.tasks.tasksAssignTask(profile, id, request) }
    suspend fun unassign(profile: String, id: String) = call { it.tasks.tasksUnassignTask(profile, id) }
    suspend fun stop(profile: String, id: String) = call { it.tasks.tasksStopTask(profile, id) }

    /** Makes the task (once per [key], so a second Save after a failed start makes no second task), then starts it when asked. */
    suspend fun create(profile: String, values: Map<String, String>, key: String): Result<Task> = call { api ->
        val (request, start) = TaskRules.create(values)
        val task = api.tasks.tasksCreateTask(profile, request, key)
        if (start != null) api.tasks.tasksAssignTask(profile, task.id, TaskAssign(agentId = start, start = true))
        task
    }
}

@Composable
private fun runLabel(status: RunStatus): String = stringResource(
    when (status) {
        RunStatus.QUEUED -> R.string.taskd_run_queued
        RunStatus.RUNNING -> R.string.taskd_run_running
        RunStatus.WAITING -> R.string.taskd_run_waiting
        RunStatus.SUCCEEDED -> R.string.taskd_run_succeeded
        RunStatus.FAILED -> R.string.taskd_run_failed
        RunStatus.CANCELLED -> R.string.taskd_run_cancelled
    },
)

@Composable
private fun priorityOptions() = TaskRules.PRIORITIES.map { FormOption(it.value, priorityText(it)) }

internal fun statusTone(status: TaskStatus): BadgeTone = when (status) {
    TaskStatus.BLOCKED -> BadgeTone.Danger
    TaskStatus.RUNNING, TaskStatus.DONE -> BadgeTone.Success
    TaskStatus.REVIEW -> BadgeTone.Review
    TaskStatus.SCHEDULED -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}

/** What the sheet shows in place of the task while a form is open. */
private sealed interface Mode {
    data object Detail : Mode
    data object Edit : Mode
    data class Assign(val agents: List<Agent>) : Mode
}

/**
 * The task's sheet: reads the task (and its profile's projects) when it opens, and after anything
 * done here; [onChanged] lets the board read again.
 */
@Composable
fun TaskDetailSheet(
    task: Task,
    ops: TaskOps,
    showProfile: Boolean,
    profileName: (String) -> String,
    onOpenChat: (sessionId: String, profile: String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember(task.id) { mutableStateOf<TaskDetail?>(null) }
    var projects by remember(task.id) { mutableStateOf<List<Project>>(emptyList()) }
    var error by remember(task.id) { mutableStateOf<HubError?>(null) }
    var notice by remember(task.id) { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mode by remember(task.id) { mutableStateOf<Mode>(Mode.Detail) }
    var blocking by remember { mutableStateOf<BoardRules.Drop?>(null) }
    var archiving by remember { mutableStateOf<BoardRules.Drop?>(null) }
    val deleting = rememberConfirmDelete<TaskFacts>()
    val facts = detail?.let { TaskFacts.of(it) } ?: TaskFacts.of(task)

    suspend fun load() {
        ops.detail(task.profile, task.id).onSuccess { detail = it; error = null }.onFailure { error = it as? HubError }
        if (projects.isEmpty()) ops.projects(task.profile).onSuccess { projects = it }
    }
    LaunchedEffect(task.id) { load() }

    fun act(block: suspend () -> Result<*>) {
        busy = true
        scope.launch {
            block().onSuccess { error = null; notice = null }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
            load()
            onChanged()
            busy = false
        }
    }

    fun start(drop: BoardRules.Drop) {
        when {
            drop.transition.requiresReason -> blocking = drop
            drop.transition.confirm -> archiving = drop
            else -> act { ops.move(facts.profile, facts.id, drop.to) }
        }
    }

    HubSheet(onDismiss = onDismiss) {
        when (val m = mode) {
            Mode.Detail -> TaskDetailBody(
                facts, detail, projects.firstOrNull { it.id == facts.projectId }?.name,
                showProfile, profileName, busy, error, notice,
                onMove = ::start,
                onStop = { act { ops.stop(facts.profile, facts.id) } },
                onAssign = {
                    scope.launch {
                        ops.agents(facts.profile)
                            .onSuccess { list -> if (list.isEmpty()) notice = R.string.taskd_assign_no_agents else { notice = null; mode = Mode.Assign(list) } }
                            .onFailure { error = it as? HubError }
                    }
                },
                onUnassign = { act { ops.unassign(facts.profile, facts.id) } },
                onEdit = { mode = Mode.Edit },
                onDelete = { deleting.ask(facts) },
                onOpenChat = { id -> onDismiss(); onOpenChat(id, facts.profile) },
            )
            Mode.Edit -> {
                val initial = TaskRules.editValues(facts.title, facts.description, facts.priority, facts.projectId)
                Text(stringResource(R.string.taskd_edit_title), fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold)
                FormBody(
                    editFields(projects.takeIf { list -> list.any { it.id == facts.projectId } }.orEmpty()), initial,
                    onDone = { mode = Mode.Detail },
                    onSave = { values ->
                        val patch = TaskRules.patch(initial, values) ?: return@FormBody Result.success(Unit)
                        ops.update(facts.profile, facts.id, patch).onSuccess { load(); onChanged() }
                    },
                    tag = "task.form",
                )
            }
            is Mode.Assign -> {
                val current = facts.assignee?.takeIf { it.kind == Assignee.Kind.AGENT }?.id
                val waiting = TaskRules.waiting(facts.status, facts.waitingOn).map { it.title }
                Text(stringResource(R.string.taskd_assign_title), fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold)
                FormBody(
                    assignFields(m.agents),
                    mapOf("agent" to (current?.takeIf { id -> m.agents.any { it.id == id } } ?: m.agents.first().id), "start" to "true"),
                    onDone = { mode = Mode.Detail },
                    onSave = { values ->
                        val request = TaskRules.assign(values) ?: return@FormBody Result.success(Unit)
                        ops.assign(facts.profile, facts.id, request).onSuccess { answer ->
                            // Asked to start and no run came back: the agent keeps its own board (Hermes).
                            notice = if (request.start == true && answer.runId == null) R.string.taskd_assign_not_started else null
                            load()
                            onChanged()
                        }
                    },
                    saveLabel = stringResource(R.string.taskd_assign),
                    intro = waiting.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.taskd_assign_waiting, it.joinToString(", ")) },
                    tag = "task.assign",
                )
            }
        }
    }

    blocking?.let { drop ->
        var reason by remember(drop) { mutableStateOf("") }
        HubDialog({ blocking = null }, stringResource(R.string.board_block_reason)) {
            HubTextField(reason, { reason = it }, placeholder = stringResource(R.string.board_block_reason_hint), singleLine = false, maxLines = 4, fieldTag = "task.reason")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HubButton(stringResource(R.string.cancel), { blocking = null }, kind = ButtonKind.Secondary, size = ControlSize.Md)
                HubButton(
                    actionLabel(drop.transition.action), { blocking = null; act { ops.move(facts.profile, facts.id, drop.to, reason) } },
                    size = ControlSize.Md, enabled = reason.isNotBlank(), modifier = Modifier.testTag("task.reason.ok"),
                )
            }
        }
    }
    archiving?.let { drop ->
        ConfirmDialog(
            stringResource(R.string.board_archive_confirm, facts.title), null, actionLabel(drop.transition.action),
            onConfirm = { archiving = null; act { ops.move(facts.profile, facts.id, drop.to) } }, onDismiss = { archiving = null },
        )
    }
    ConfirmDeleteDialog(
        deleting, { deleteTitle(it.title) },
        onDelete = { ops.delete(it.profile, it.id) },
        onDeleted = { onChanged(); onDismiss() },
    )
}

@Composable
private fun editFields(projects: List<Project>): List<FormField> = buildList {
    add(FormField("title", stringResource(R.string.taskd_form_title), required = true))
    add(FormField("description", stringResource(R.string.taskd_form_description), FormKind.Multiline, help = stringResource(R.string.taskd_form_description_help)))
    add(FormField("priority", stringResource(R.string.taskd_form_priority), FormKind.Choice, required = true, options = priorityOptions()))
    // Another project only when the list came and holds this one.
    if (projects.isNotEmpty()) add(FormField("project", stringResource(R.string.taskd_form_project), FormKind.Choice, required = true, options = projects.map { FormOption(it.id, it.name) }))
}

@Composable
private fun assignFields(agents: List<Agent>): List<FormField> = listOf(
    FormField("agent", stringResource(R.string.taskd_assign_agent), FormKind.Choice, required = true, options = agents.map { FormOption(it.id, it.name) }),
    FormField("instructions", stringResource(R.string.taskd_assign_instructions), FormKind.Multiline, help = stringResource(R.string.taskd_assign_instructions_help)),
    FormField("start", stringResource(R.string.taskd_form_start), FormKind.Toggle, help = stringResource(R.string.taskd_form_start_help)),
)

/** The task itself, drawn from what is known of it; every action is the caller's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailBody(
    facts: TaskFacts,
    detail: TaskDetail?,
    projectName: String?,
    showProfile: Boolean,
    profileName: (String) -> String,
    busy: Boolean,
    error: HubError?,
    notice: Int?,
    onMove: (BoardRules.Drop) -> Unit,
    onStop: () -> Unit,
    onAssign: () -> Unit,
    onUnassign: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val t = LocalTokens.current
    var moveOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("task.detail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InContentDirection(facts.title) {
                Text(facts.title, fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("task.detail.title"))
            }
            if (showProfile) Badge(profileName(facts.profile), tone = BadgeTone.Accent)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(statusLabel(facts.status), dot = true, tone = statusTone(facts.status), modifier = Modifier.testTag("task.detail.status"))
            Badge(priorityText(facts.priority), tone = if (facts.priority == TaskPriority.URGENT || facts.priority == TaskPriority.HIGH) BadgeTone.Warning else BadgeTone.Neutral)
            if (facts.status == TaskStatus.RUNNING && facts.stuckSince != null) Badge(stringResource(R.string.board_stuck), tone = BadgeTone.Danger)
        }
        if (facts.hermes) Text(stringResource(R.string.taskd_hermes), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ErrorNotice(error)
        notice?.let { NoticeBox(stringResource(it), BadgeTone.Info) }
        facts.reason?.let { NoticeBox(it, if (facts.status == TaskStatus.BLOCKED) BadgeTone.Danger else BadgeTone.Warning) }
        if (facts.status == TaskStatus.RUNNING) facts.stuckSince?.let { NoticeBox(stringResource(R.string.board_stuck_body, localTime(it)), BadgeTone.Danger) }

        GroupedList {
            Item(stringResource(R.string.taskd_form_project), value = projectName ?: "—", icon = Lucide.Folder)
            Item(
                stringResource(R.string.taskd_assignee), icon = Lucide.Bot, tag = "task.detail.assignee",
                value = facts.assignee?.let { "${it.name} · ${stringResource(if (it.kind == Assignee.Kind.AGENT) R.string.taskd_agent else R.string.taskd_person)}" }
                    ?: stringResource(R.string.taskd_nobody),
            )
            Item(stringResource(R.string.taskd_due), value = facts.dueAt?.let { localTime(it) } ?: stringResource(R.string.taskd_no_due), icon = Lucide.CalendarClock)
        }

        SectionTitle(stringResource(R.string.taskd_form_description))
        val description = facts.description?.takeIf { it.isNotBlank() }
        if (description != null) {
            MarkdownView(description, Modifier.fillMaxWidth().testTag("task.detail.description"))
        } else {
            Text(stringResource(R.string.taskd_no_description), fontSize = FontTokens.sizeSm.sp, color = t.textFaint)
        }

        if (facts.dependsOn.isNotEmpty()) {
            val waiting = TaskRules.waiting(facts.status, facts.waitingOn)
            val done = TaskRules.doneDependencies(facts.dependsOn, facts.waitingOn)
            GroupedList(Modifier.testTag("task.detail.dependencies"), title = stringResource(R.string.taskd_depends_on)) {
                waiting.forEach { one -> Item(one.title, value = statusLabel(one.status)) }
                if (done > 0) Item(stringResource(R.string.taskd_dependencies_done, done), icon = Lucide.CircleCheck)
            }
        }

        GroupedList(title = stringResource(R.string.taskd_run)) {
            val run = detail?.runs?.firstOrNull()
            if (run != null) {
                Item(stringResource(R.string.taskd_run_status), value = runLabel(run.status), tag = "task.detail.run")
                Item(stringResource(R.string.taskd_run_started), value = localTime(run.startedAt ?: run.createdAt))
            } else {
                Item(stringResource(R.string.taskd_no_run))
            }
            facts.latestSummary?.takeIf { it.isNotBlank() }?.let { Item(it) }
            facts.sessionId?.let { id ->
                Item(stringResource(R.string.tasks_open_chat), icon = Lucide.MessagesSquare, accent = true, chevron = true, tag = "task.open_chat", onClick = { onOpenChat(id) })
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val moves = TaskRules.moves(facts.status)
            if (moves.isNotEmpty()) {
                Box {
                    HubButton(stringResource(R.string.tasks_move), { moveOpen = true }, size = ControlSize.Md, icon = Lucide.ChevronsUpDown, enabled = !busy, modifier = Modifier.testTag("task.move"))
                    HubMenu(moveOpen, { moveOpen = false }) {
                        moves.forEach { drop ->
                            MenuItem(actionLabel(drop.transition.action), { moveOpen = false; onMove(drop) }, modifier = Modifier.testTag("task.move.${drop.to.value}"))
                        }
                    }
                }
            }
            if (TaskRules.canStop(facts.status)) {
                HubButton(stringResource(R.string.taskd_stop), onStop, kind = ButtonKind.Danger, size = ControlSize.Md, icon = Lucide.CircleStop, enabled = !busy, modifier = Modifier.testTag("task.stop"))
            }
            if (TaskRules.canAssign(facts.status, facts.hermes)) {
                HubButton(
                    stringResource(if (facts.assignee?.kind == Assignee.Kind.AGENT) R.string.taskd_reassign else R.string.taskd_assign), onAssign,
                    kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.UserPlus, enabled = !busy, modifier = Modifier.testTag("task.assign"),
                )
            }
            if (TaskRules.canUnassign(facts.assignee, facts.hermes)) {
                HubButton(stringResource(R.string.taskd_unassign), onUnassign, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.X, enabled = !busy, modifier = Modifier.testTag("task.unassign"))
            }
            HubButton(stringResource(R.string.taskd_edit), onEdit, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.Pencil, enabled = !busy, modifier = Modifier.testTag("task.edit"))
            HubButton(stringResource(R.string.kit_delete), onDelete, kind = ButtonKind.Ghost, size = ControlSize.Md, icon = Lucide.Trash, enabled = !busy, modifier = Modifier.testTag("task.delete"))
        }
    }
}

/**
 * A new task, made in the profile the selector is on, as on the web ("New task in …"): title,
 * description, project, priority, and — when given to an agent — whether it starts now.
 */
@Composable
fun NewTaskSheet(profile: String, title: String, ops: TaskOps, onDismiss: () -> Unit, onCreated: (Task) -> Unit) {
    var projects by remember(profile) { mutableStateOf<List<Project>?>(null) }
    var agents by remember(profile) { mutableStateOf<List<Agent>>(emptyList()) }
    val key = remember { TaskRules.ulid() }
    LaunchedEffect(profile) {
        agents = ops.agents(profile).getOrDefault(emptyList())
        projects = ops.projects(profile).getOrDefault(emptyList())
    }
    HubSheet(onDismiss = onDismiss, title = title) {
        val list = projects
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
        } else {
            FormBody(
                newTaskFields(list, agents),
                mapOf("project" to TaskRules.NONE, "priority" to TaskPriority.NORMAL.value, "agent" to TaskRules.NONE, "start" to "true"),
                onDone = onDismiss,
                onSave = { values -> ops.create(profile, values, key).onSuccess(onCreated) },
                saveLabel = stringResource(R.string.taskd_form_create),
                tag = "task.new",
            )
        }
    }
}

@Composable
private fun newTaskFields(projects: List<Project>, agents: List<Agent>): List<FormField> = listOf(
    FormField("title", stringResource(R.string.taskd_form_title), required = true),
    FormField("description", stringResource(R.string.taskd_form_description), FormKind.Multiline, help = stringResource(R.string.taskd_form_description_help)),
    FormField(
        "project", stringResource(R.string.taskd_form_project), FormKind.Choice, required = true,
        options = listOf(FormOption(TaskRules.NONE, stringResource(R.string.taskd_form_project_own))) + projects.map { FormOption(it.id, it.name) },
    ),
    FormField("priority", stringResource(R.string.taskd_form_priority), FormKind.Choice, required = true, options = priorityOptions()),
    FormField(
        "agent", stringResource(R.string.taskd_form_agent), FormKind.Choice, required = true,
        options = listOf(FormOption(TaskRules.NONE, stringResource(R.string.taskd_form_agent_none))) + agents.map { FormOption(it.id, it.name) },
    ),
    FormField("start", stringResource(R.string.taskd_form_start), FormKind.Toggle, help = stringResource(R.string.taskd_form_start_help)),
)
