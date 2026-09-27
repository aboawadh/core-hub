package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hub.core.android.AppGraph
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.graph
import hub.core.android.realtime.TASKS_NAMESPACE
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.Loading
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.IconKind
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.api.TasksApi
import hub.core.client.model.Task
import hub.core.client.model.TaskColumns
import hub.core.client.model.TaskMove
import hub.core.client.model.TaskStatus
import hub.core.client.model.BulkResult
import hub.core.client.model.Project
import hub.core.client.model.TaskBulkUpdatePatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The board's rules on the phone, apart from the screen so they are unit-tested. */
object Board {
    /** The columns the board shows, in the hub's order; archived tasks stay off the board. */
    fun columns(board: TaskColumns): List<Pair<TaskStatus, List<Task>>> =
        board.columns.filter { it.status != TaskStatus.ARCHIVED }.map { it.status to it.tasks }

    /** Several profiles on the board: every card carries its profile's badge (ADR 0016). */
    fun showsProfiles(board: TaskColumns): Boolean =
        board.columns.flatMap { it.tasks }.map { it.profile }.distinct().size > 1
}

data class TasksUi(
    val board: TaskColumns? = null,
    val loading: Boolean = true,
    val error: HubError? = null,
    val acting: Boolean = false,
    /** A short word after a drop the board refused. */
    val notice: Int? = null,
    /** The new-task sheet is open (the top bar's + opens it). */
    val creating: Boolean = false,
    /** Tasks II: the selector's profile's projects, and the one the board is narrowed to. */
    val projects: List<Project> = emptyList(),
    val projectId: String? = null,
    /** "Select" is on, and the ticked cards (§103). */
    val selecting: Boolean = false,
    val ticked: Set<String> = emptySet(),
    val bulkBusy: Boolean = false,
    /** How many a bulk edit changed and how many were refused, when some were. */
    val bulkRefused: Pair<Int, Int>? = null,
    /** The projects sheet is open. */
    val managing: Boolean = false,
)

/**
 * Tasks shows every profile the person may enter with no profile filter (ADR 0016 stage 2);
 * anything done to a card goes to the card's own profile.
 */
class TasksViewModel(private val graph: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(TasksUi())
    val ui: StateFlow<TasksUi> = _ui.asStateFlow()
    private var pending: Job? = null

    /** A task's own calls (its detail, forms, stop, delete…), in its own profile. */
    val ops = TaskOps { graph.store.current?.let(graph::apis) }

    /** The profile a new task is made in: the one the selector is on, as on the web. */
    val homeProfile: String? get() = graph.store.current?.profile

    init {
        graph.realtime.subscribeAll(TASKS_NAMESPACE)
        reload()
        loadProjects()
        viewModelScope.launch {
            graph.realtime.events.collect { e ->
                if (e.namespace == TASKS_NAMESPACE && (e.event.startsWith("task.") || e.event.startsWith("subtask."))) {
                    // A burst of moves reloads once.
                    pending?.cancel()
                    pending = launch { delay(300); reload() }
                }
            }
        }
    }

    fun reload() {
        val s = graph.store.current ?: return
        viewModelScope.launch {
            val project = _ui.value.projectId
            hubCall { graph.apis(s).tasks.tasksGetColumns(profiles = TasksApi.ProfilesTasksGetColumns.ALL, projectId = project) }
                .onSuccess { b -> _ui.update { it.copy(board = b, loading = false, error = null) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e as HubError) } }
        }
    }

    private fun act(block: suspend () -> Unit) {
        _ui.update { it.copy(acting = true, error = null) }
        viewModelScope.launch {
            hubCall { block() }
                .onSuccess { _ui.update { it.copy(acting = false) }; reload() }
                .onFailure { e -> _ui.update { it.copy(acting = false, error = e as HubError) } }
        }
    }

    fun move(task: Task, to: TaskStatus, reason: String? = null) {
        val s = graph.store.current ?: return
        act { graph.apis(s).tasks.tasksMoveTask(task.profile, task.id, TaskMove(status = to, reason = reason?.ifBlank { null })) }
    }

    /** A card put elsewhere in its own column (`after` null: first), in its own profile. */
    fun reorder(task: Task, after: String?) {
        val s = graph.store.current ?: return
        act { graph.apis(s).tasks.tasksMoveTask(task.profile, task.id, TaskMove(status = task.status, afterTaskId = after)) }
    }

    fun say(message: Int?) = _ui.update { it.copy(notice = message) }

    fun creating(open: Boolean) = _ui.update { it.copy(creating = open) }

    // ------------------------------------------------------------------ Tasks II

    /** The projects of the profile the selector is on, as on the web; a filter on a gone project is dropped. */
    fun loadProjects() {
        val home = homeProfile ?: return
        viewModelScope.launch {
            ops.projects(home).onSuccess { list ->
                val gone = _ui.value.projectId?.let { id -> list.none { it.id == id } } == true
                _ui.update { it.copy(projects = list, projectId = if (gone) null else it.projectId) }
                if (gone) reload()
            }
        }
    }

    fun filter(projectId: String?) {
        _ui.update { it.copy(projectId = projectId) }
        reload()
    }

    fun managing(open: Boolean) = _ui.update { it.copy(managing = open) }

    fun selecting(on: Boolean) = _ui.update { it.copy(selecting = on, ticked = emptySet(), bulkRefused = null) }

    fun tick(task: Task) = _ui.update { it.copy(ticked = if (task.id in it.ticked) it.ticked - task.id else it.ticked + task.id) }

    fun tickedTasks(): List<Task> = _ui.value.board?.columns.orEmpty().flatMap { it.tasks }.filter { it.id in _ui.value.ticked }

    /** The same change on every ticked card; with no refusal the selection ends. */
    fun bulk(patch: TaskBulkUpdatePatch) = settle { ops.bulkUpdate(tickedTasks(), patch) }

    fun bulkDelete() = settle { ops.bulkDelete(tickedTasks()) }

    private fun settle(block: suspend () -> Result<List<BulkResult>>) {
        _ui.update { it.copy(bulkBusy = true, error = null) }
        viewModelScope.launch {
            block()
                .onSuccess { answers ->
                    val (changed, refused) = BulkRules.tally(answers)
                    _ui.update {
                        if (refused > 0) it.copy(bulkBusy = false, ticked = it.ticked.intersect(BulkRules.refused(answers)), bulkRefused = changed to refused)
                        else it.copy(bulkBusy = false, ticked = emptySet(), selecting = false, bulkRefused = null)
                    }
                }
                .onFailure { e -> _ui.update { it.copy(bulkBusy = false, error = e as? HubError ?: HubError(-1, null, e.message)) } }
            reload()
        }
    }
}

/** The top bar's +: a new task in the profile the selector is on. */
@Composable
fun NewTaskButton() {
    val context = LocalContext.current
    val vm: TasksViewModel = viewModel { TasksViewModel(context.graph) }
    HubIconButton(Lucide.Plus, stringResource(R.string.taskd_new_task), { vm.creating(true) }, kind = IconKind.Glass, modifier = Modifier.testTag("tasks.new"))
}

@Composable
fun statusLabel(status: TaskStatus): String = stringResource(
    when (status) {
        TaskStatus.TRIAGE -> R.string.task_triage
        TaskStatus.TODO -> R.string.task_todo
        TaskStatus.READY -> R.string.task_ready
        TaskStatus.SCHEDULED -> R.string.task_scheduled
        TaskStatus.RUNNING -> R.string.task_running
        TaskStatus.BLOCKED -> R.string.task_blocked
        TaskStatus.REVIEW -> R.string.task_review
        TaskStatus.DONE -> R.string.task_done
        TaskStatus.ARCHIVED -> R.string.task_archived
    },
)

/** A column's colour, the web board's (status-* tokens). */
@Composable
fun statusColor(status: TaskStatus): androidx.compose.ui.graphics.Color {
    val t = LocalTokens.current
    return when (status) {
        TaskStatus.RUNNING -> t.statusRunning
        TaskStatus.BLOCKED -> t.statusBlocked
        TaskStatus.SCHEDULED -> t.statusScheduled
        TaskStatus.REVIEW -> t.statusReview
        TaskStatus.READY -> t.statusReady
        TaskStatus.DONE -> t.successSoftText
        else -> t.textFaint
    }
}

/**
 * Tasks on the phone, as on iOS: the board's columns one under another — each titled with its
 * colour and count — and a card per task (title, profile, priority, who has it, the latest line).
 * A tap opens the task's sheet.
 */
@Composable
fun TasksScreen(shell: ShellViewModel, onOpenChat: (sessionId: String, profile: String) -> Unit) {
    val context = LocalContext.current
    val vm: TasksViewModel = viewModel { TasksViewModel(context.graph) }
    val ui by vm.ui.collectAsState()
    val board = ui.board
    var opened by remember { mutableStateOf<Task?>(null) }
    // «Open» on a task run, a notice or a push leads to the task itself (nav/Focus.kt).
    val focus by hub.core.android.nav.Focus.item.collectAsState()
    LaunchedEffect(focus, board != null) {
        val b = board ?: return@LaunchedEffect
        if (focus?.kind != hub.core.android.nav.FocusItem.Kind.TASK) return@LaunchedEffect
        val f = hub.core.android.nav.Focus.take(hub.core.android.nav.FocusItem.Kind.TASK) ?: return@LaunchedEffect
        Board.columns(b).flatMap { it.second }.firstOrNull { it.id == f.id }?.let { opened = it }
    }
    Column(Modifier.fillMaxSize()) {
        ui.error?.let { ErrorNotice(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (board == null) {
            if (ui.loading) Loading()
            return@Column
        }
        ui.notice?.let { NoticeBox(stringResource(it), BadgeTone.Warning, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LaunchedEffect(ui.notice) { if (ui.notice != null) { delay(2_500); vm.say(null) } }
        BoardTools(ui, vm)
        if (Board.columns(board).all { it.second.isEmpty() }) {
            EmptyState(stringResource(R.string.tasks_empty_column), icon = Lucide.ListChecks)
            return@Column
        }
        // Columns side by side with drag between them (B14/B15), as on the web. While selecting, a
        // tap ticks a card instead of opening it.
        Box(Modifier.weight(1f)) {
            TaskBoard(
                board, Board.showsProfiles(board), shell::profileName, vm,
                onOpen = { if (ui.selecting) vm.tick(it) else opened = it },
                selected = if (ui.selecting) ui.ticked else null,
            )
        }
        if (ui.selecting) BulkBar(ui, vm)
    }
    if (ui.managing) {
        val many by shell.profiles.collectAsState()
        val home = vm.homeProfile
        if (home != null) {
            ProjectsSheet(
                home, if (many.size > 1) stringResource(R.string.taskl_projects_title_in, shell.profileName(home)) else stringResource(R.string.taskl_projects_title),
                vm.ops, onChanged = { vm.loadProjects(); vm.reload() }, onDismiss = { vm.managing(false) },
            )
        }
    }
    opened?.let { task ->
        TaskDetailSheet(
            task, vm.ops, board?.let(Board::showsProfiles) == true, shell::profileName,
            onOpenChat = { id, profile -> opened = null; onOpenChat(id, profile) },
            onChanged = vm::reload,
            onDismiss = { opened = null },
        )
    }
    val home = vm.homeProfile
    if (ui.creating && home != null) {
        val many by shell.profiles.collectAsState()
        NewTaskSheet(
            home,
            if (many.size > 1) stringResource(R.string.taskd_new_task_in, shell.profileName(home)) else stringResource(R.string.taskd_new_task),
            vm.ops,
            onDismiss = { vm.creating(false) },
            onCreated = { vm.creating(false); vm.reload() },
        )
    }
}
