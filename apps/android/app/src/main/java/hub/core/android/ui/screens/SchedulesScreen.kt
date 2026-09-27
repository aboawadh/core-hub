package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hub.core.android.AppGraph
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.realtime.SCHEDULES_NAMESPACE
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.ListPage
import hub.core.android.ui.components.ListScaffold
import hub.core.android.ui.components.PagedList
import hub.core.android.ui.components.RowAction
import hub.core.android.ui.components.RowActionsButton
import hub.core.android.ui.components.StatusBadge
import hub.core.android.ui.components.Tone
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.JobStatus
import hub.core.client.model.Schedule
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the page says after an action: its words, or the hub's refusal. */
data class ScheduleNote(val words: Words? = null, val error: HubError? = null)

/**
 * Schedules across every profile the person may enter, with no profile filter (ADR 0016), read
 * page by page; a new one is made in the selector's profile. Anything done to one goes to its own
 * profile. The list follows `/rt/schedules`, so a change made anywhere redraws it.
 */
class SchedulesViewModel(private val graph: AppGraph) : ViewModel() {
    val ops = ScheduleOps { graph.store.current?.let(graph::apis) }
    val list = PagedList<Schedule>(viewModelScope, { it.id }) { cursor ->
        ops.list(cursor).getOrThrow().let { ListPage(it.items, it.nextCursor) }
    }
    private val _note = MutableStateFlow<ScheduleNote?>(null)
    val note: StateFlow<ScheduleNote?> = _note.asStateFlow()
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    /** The agents a new schedule may ask, in the selector's profile. */
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    private var pending: Job? = null

    init {
        graph.realtime.subscribeAll(SCHEDULES_NAMESPACE)
        list.refresh()
        viewModelScope.launch {
            graph.realtime.events.collect { e ->
                if (e.namespace == SCHEDULES_NAMESPACE && e.event.startsWith("schedule")) {
                    pending?.cancel()
                    pending = launch { delay(300); list.refresh() }
                }
            }
        }
    }

    fun loadAgents(profile: String) {
        viewModelScope.launch { ops.agents(profile).onSuccess { _agents.value = it } }
    }

    fun runNow(schedule: Schedule) {
        viewModelScope.launch {
            ops.runNow(schedule)
                .onSuccess { _note.value = ScheduleNote(ScheduleRules.fired(schedule)); list.refresh() }
                .onFailure { _note.value = ScheduleNote(error = it as HubError) }
        }
    }

    fun setEnabled(schedule: Schedule, enabled: Boolean) {
        viewModelScope.launch {
            ops.setEnabled(schedule, enabled)
                .onSuccess { list.refresh() }
                .onFailure { _note.value = ScheduleNote(error = it as HubError) }
        }
    }

    fun deleted(schedule: Schedule) = list.remove(schedule)

    fun changed() = list.refresh()

    fun say(note: ScheduleNote?) {
        _note.value = note
    }
}

private val whenFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * A time in the phone's own zone, written in the app's language (month names and order follow the
 * in-app choice, not the phone's: [hub.core.android.Digits.useAppLocale]) with Latin digits (§113).
 */
fun localTime(
    time: OffsetDateTime,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String = time.atZoneSameInstant(zone).format(whenFormat.withLocale(locale))

@Composable
fun jobLabel(status: JobStatus): String = stringResource(
    when (status) {
        JobStatus.QUEUED -> R.string.job_queued
        JobStatus.RUNNING -> R.string.job_running
        JobStatus.SUCCEEDED -> R.string.job_succeeded
        JobStatus.FAILED -> R.string.job_failed
        JobStatus.CANCELLED -> R.string.job_cancelled
    },
)

@Composable
internal fun StateBadge(schedule: Schedule) {
    val (label, tone) = when (schedule.state) {
        Schedule.State.RUNNING -> R.string.schedule_running to Tone.SUCCESS
        Schedule.State.PAUSED -> R.string.schedule_paused to Tone.WARNING
        Schedule.State.EXHAUSTED -> R.string.schedule_finished to null
        else -> R.string.schedule_scheduled to Tone.INFO
    }
    StatusBadge(stringResource(label), tone)
}

/** A note the Schedules pages show after an action. */
@Composable
internal fun ScheduleNoteView(note: ScheduleNote?, modifier: Modifier = Modifier) {
    note?.words?.let { NoticeBox(words(it), BadgeTone.Success, modifier) }
    note?.error?.let { NoticeBox(scheduleRefusal(it), BadgeTone.Danger, modifier) }
}

/**
 * Schedules, as a card per schedule: its name and profile, when it runs (and next), its state as
 * a tinted badge, a last error in red, and its actions under «⋯». A tap opens it on its own.
 */
@Composable
fun SchedulesScreen(shell: ShellViewModel, onOpenChat: (String, String) -> Unit) {
    // Schedules and workflows share the page, as on the web: one segmented switch above them.
    var half by rememberSaveable { mutableStateOf(SchedulesHalf.SCHEDULES) }
    Column(Modifier.fillMaxSize()) {
        Segmented(
            listOf(
                Segment(SchedulesHalf.SCHEDULES, stringResource(R.string.schedules_tab_jobs), Lucide.CalendarClock, "schedules.tab.jobs"),
                Segment(SchedulesHalf.WORKFLOWS, stringResource(R.string.schedules_tab_workflows), Lucide.Workflow, "schedules.tab.workflows"),
            ),
            half, { half = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (half) {
            SchedulesHalf.SCHEDULES -> SchedulesList(shell, onOpenChat)
            SchedulesHalf.WORKFLOWS -> WorkflowsList(shell, onOpenChat)
        }
    }
}

enum class SchedulesHalf { SCHEDULES, WORKFLOWS }

@Composable
private fun SchedulesList(shell: ShellViewModel, onOpenChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val vm: SchedulesViewModel = viewModel { SchedulesViewModel(context.graph) }
    val note by vm.note.collectAsState()
    val agents by vm.agents.collectAsState()
    val session by shell.session.collectAsState()
    val profiles by shell.profiles.collectAsState()
    val profile = session?.profile ?: return
    LaunchedEffect(profile) { vm.loadAgents(profile) }
    var opened by remember { mutableStateOf<Schedule?>(null) }
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val confirm = rememberConfirmDelete<Schedule>()
    val many = profiles.size > 1 || vm.list.items.map { it.profile }.distinct().size > 1
    val pause = stringResource(R.string.schedules_pause)
    val resume = stringResource(R.string.schedules_resume)
    val runNow = stringResource(R.string.schedules_run_now)
    val edit = stringResource(R.string.kit_edit)
    val delete = stringResource(R.string.kit_delete)
    fun actions(schedule: Schedule) = listOf(
        RowAction(runNow, Lucide.Play) { vm.runNow(schedule) },
        RowAction(if (schedule.enabled) pause else resume, if (schedule.enabled) Lucide.Pause else Lucide.Play) { vm.setEnabled(schedule, !schedule.enabled) },
        RowAction(edit, Lucide.Pencil) { editing = schedule },
        RowAction(delete, Lucide.Trash, danger = true) { confirm.ask(schedule) },
    )

    ListScaffold(
        vm.list, key = { it.id },
        emptyTitle = stringResource(R.string.schedules_empty), emptyIcon = Lucide.CalendarClock,
        swipeAction = { RowAction(delete, Lucide.Trash, danger = true) { confirm.ask(it) } },
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HubButton(
                    stringResource(R.string.sched_new), { creating = true }, size = ControlSize.Md, icon = Lucide.Plus,
                    modifier = Modifier.testTag("schedules.new"),
                )
                ScheduleNoteView(note)
            }
        },
        tag = "schedules.list",
    ) { schedule ->
        ScheduleCard(schedule, if (many) shell.profileName(schedule.profile) else null, actions(schedule)) { opened = schedule }
    }

    opened?.let { schedule ->
        HubSheet(onDismiss = { opened = null }) {
            ScheduleDetailBody(
                schedule, vm.ops, if (many) shell.profileName(schedule.profile) else null,
                agentName = { id -> agents.firstOrNull { it.id == id }?.name },
                onChanged = { vm.changed() },
                onEdit = { opened = null; editing = it },
                onDelete = { confirm.ask(it) },
                onOpenChat = { id -> opened = null; onOpenChat(id, schedule.profile) },
            )
        }
    }
    editing?.let { schedule ->
        ScheduleEditorSheet(schedule, schedule.profile, null, agents, vm.ops, onDismiss = { editing = null }) { vm.changed() }
    }
    if (creating) {
        val note = if (many) stringResource(R.string.sched_profile_note, shell.profileName(profile)) else null
        ScheduleEditorSheet(null, profile, note, agents, vm.ops, onDismiss = { creating = false }) { vm.changed() }
    }
    ConfirmDeleteDialog(
        confirm, { stringResource(R.string.kit_delete_confirm, it.name) },
        onDelete = { vm.ops.delete(it) },
        onDeleted = { opened = null; vm.deleted(it) },
        body = stringResource(R.string.kit_delete_body),
    )
}

/** One schedule in the list. [profile] is its profile's name when there is more than one. */
@Composable
internal fun ScheduleCard(schedule: Schedule, profile: String?, actions: List<RowAction>, onOpen: () -> Unit) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("schedule.card.${schedule.id}"), onClick = onOpen, padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InContentDirection(schedule.name) {
                Text(schedule.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (profile != null) Badge(profile, tone = BadgeTone.Accent)
            if (actions.isNotEmpty()) RowActionsButton(actions, Modifier.testTag("schedule.card.${schedule.id}.more"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateBadge(schedule)
            LucideIcon(Lucide.Clock, null, size = 13.dp, tint = t.textMuted)
            Text(
                listOfNotNull(
                    schedule.trigger.display ?: schedule.trigger.expression,
                    schedule.nextRunAt?.let { stringResource(R.string.schedules_next, localTime(it)) },
                ).joinToString(" · "),
                fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        schedule.lastError?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger, maxLines = 2) }
    }
}
