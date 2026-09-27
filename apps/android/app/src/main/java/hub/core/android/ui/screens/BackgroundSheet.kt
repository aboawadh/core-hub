package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hub.core.android.AppGraph
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.nav.Navigator
import hub.core.android.nav.Route
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.IconKind
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.api.JobsApi
import hub.core.client.model.BackgroundItem
import hub.core.client.model.BackgroundKind
import hub.core.client.model.BackgroundList
import hub.core.client.model.BackgroundStatus
import hub.core.client.model.JobKind
import hub.core.client.model.ResourceRef
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Background (the web's BackgroundTasks, contract decision §56; apps batch 6a, left by the insight
 * batch): what works for the person right now in every profile they may enter — chats, tasks,
 * schedules and workflows, jobs, subagents — with where each lives and Stop where it can be
 * stopped, and what finished in the last day under «Finished (n)». The top bar shows it only while
 * something runs (as the web does on a phone); the chat's «⋯» opens it always. iOS's
 * BackgroundSheet.swift is the twin.
 */

/** The plain rules of the sheet, tested without Compose. */
object BackgroundRules {
    /** With something running the list is read this often; otherwise this rarely (the web's poll). */
    const val RUNNING_POLL_MS = 10_000L
    const val IDLE_POLL_MS = 60_000L

    /** The page a job is about, by its kind: where that work is started and followed (the web's JOB_PAGES). */
    private val jobPages: Map<JobKind, Route> = mapOf(
        JobKind.EXPORT to Route.SettingsPage("workspaces"),
        JobKind.IMPORT to Route.SettingsPage("workspaces"),
        JobKind.INSTALL to Route.Agents,
        JobKind.UPDATE to Route.Agents,
        JobKind.UNINSTALL to Route.Agents,
        JobKind.RESTART to Route.Agents,
        JobKind.CHECK_UPDATE to Route.Agents,
        JobKind.DISCOVER to Route.Agents,
        JobKind.PLUGIN_INSTALL to Route.Agents,
        JobKind.CHANNEL_LOGIN to Route.Agents,
        JobKind.REFRESH_CATALOGUE to Route.SettingsPage("models"),
        JobKind.WORKTREE to Route.Tasks,
        JobKind.WEBHOOK_TEST to Route.SettingsPage("webhooks"),
        JobKind.DEVICE_REQUEST to Route.SettingsPage("device_connections"),
        JobKind.SCHEDULE_RUN to Route.Schedules,
        JobKind.WORKFLOW_RUN to Route.Schedules,
    )

    /**
     * Where an item lives: the task board (a task run), Schedules (a workflow run), its conversation
     * (a chat or schedule run, a subagent — the Subagents sheet is there), or the page its job is
     * about; null when it has no place of its own.
     */
    fun routeOf(item: BackgroundItem): Route? = when {
        item.kind == BackgroundKind.TASK_RUN -> Route.Tasks
        item.kind == BackgroundKind.WORKFLOW_RUN -> Route.Schedules
        item.sessionId != null -> Route.Chat(item.sessionId!!, item.profile)
        item.kind == BackgroundKind.JOB -> item.jobKind?.let(jobPages::get)
            ?: if (item.resource?.kind == ResourceRef.Kind.AGENT) Route.Agents else null
        else -> null
    }

    /** How long it has worked (or worked, once finished); null while it waits in a queue. */
    fun elapsedMs(item: BackgroundItem, nowMs: Long): Long? {
        val start = item.startedAt?.toInstant()?.toEpochMilli() ?: return null
        val end = item.finishedAt?.toInstant()?.toEpochMilli() ?: nowMs
        return (end - start).coerceAtLeast(0)
    }

    /** «0:42», «12:05», «1:02:09»: always Latin digits (§113). */
    fun clock(ms: Long): String {
        val seconds = ms / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** The row's title: the hub's words, a job's kind when it has none, else [untitled]. */
    fun title(item: BackgroundItem, untitled: String): String =
        item.title.trim().ifEmpty { if (item.kind == BackgroundKind.JOB) item.jobKind?.value.orEmpty().ifEmpty { untitled } else untitled }

    /** The badge the top bar wears: how many things are working now. */
    fun count(list: BackgroundList?): Int = list?.running?.size ?: 0
}

/** The two calls, through the generated client only: every profile's list, and one item stopped in its own profile. */
class BackgroundOps(private val profile: () -> String?, private val jobs: () -> JobsApi?) {
    suspend fun list(): Result<BackgroundList> = hubCall {
        val api = jobs() ?: throw HubError(401, "unauthorized", null)
        api.backgroundList(profile() ?: "default", JobsApi.ProfilesBackgroundList.ALL)
    }

    suspend fun stop(item: BackgroundItem): Result<BackgroundItem> = hubCall {
        val api = jobs() ?: throw HubError(401, "unauthorized", null)
        api.backgroundStop(item.profile, item.id)
    }
}

/** The sheet's state, one per activity: the top bar's button and the chat's «⋯» open the same sheet. */
class BackgroundViewModel(private val ops: BackgroundOps) : ViewModel() {
    constructor(graph: AppGraph) : this(
        BackgroundOps(
            profile = { graph.store.current?.profile },
            jobs = { graph.store.current?.let { graph.apis(it).jobs } },
        ),
    )

    private val _list = MutableStateFlow<BackgroundList?>(null)
    val list: StateFlow<BackgroundList?> = _list.asStateFlow()
    private val _error = MutableStateFlow<HubError?>(null)
    val error: StateFlow<HubError?> = _error.asStateFlow()
    private val _stopping = MutableStateFlow<String?>(null)
    val stopping: StateFlow<String?> = _stopping.asStateFlow()
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun show() {
        _open.value = true
        viewModelScope.launch { refresh() }
    }

    fun hide() {
        _open.value = false
    }

    suspend fun refresh() {
        ops.list()
            .onSuccess {
                _list.value = it
                _error.value = null
            }
            .onFailure { _error.value = it as? HubError }
    }

    /** Stops one item; the list is read again, since the hub answers before the work has ended. */
    fun stop(item: BackgroundItem) {
        if (_stopping.value != null) return
        _stopping.value = item.id
        viewModelScope.launch {
            ops.stop(item).onFailure { _error.value = it as? HubError }
            refresh()
            _stopping.update { null }
        }
    }

    /** How long to wait before reading the list again. */
    fun pause(): Long = if (BackgroundRules.count(_list.value) > 0) BackgroundRules.RUNNING_POLL_MS else BackgroundRules.IDLE_POLL_MS
}

@Composable
fun rememberBackground(): BackgroundViewModel {
    val graph = LocalContext.current.graph
    return viewModel(key = "background") { BackgroundViewModel(graph) }
}

/**
 * The top bar's button (beside the pending bell): the Activity icon with how many things run. Like
 * the web on a phone it steps aside while nothing runs; the chat's «⋯» still opens the sheet. The
 * list is read while the screen is in front, often while something runs and rarely otherwise.
 */
@Composable
fun BackgroundButton(shell: ShellViewModel, nav: Navigator) {
    val bg = rememberBackground()
    val list by bg.list.collectAsState()
    val open by bg.open.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(bg, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                bg.refresh()
                delay(bg.pause())
            }
        }
    }
    val count = BackgroundRules.count(list)
    val t = LocalTokens.current
    if (count > 0) {
        Box {
            HubIconButton(
                Lucide.Activity, stringResource(R.string.background_open_count, count), { bg.show() },
                kind = IconKind.Glass, modifier = Modifier.testTag("background.open"),
            )
            Text(
                if (count > 99) "99+" else "$count", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = t.accentText,
                modifier = Modifier.align(Alignment.TopEnd).background(t.accent, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
    if (open) BackgroundSheet(bg, shell, onGo = { route -> bg.hide(); nav.go(route) })
}

/** The sheet itself: running first, then «Finished in the last 24 hours (n)», folded. */
@Composable
fun BackgroundSheet(bg: BackgroundViewModel, shell: ShellViewModel, onGo: (Route) -> Unit) {
    val profiles by shell.profiles.collectAsState()
    val many = profiles.size > 1
    val list by bg.list.collectAsState()
    val error by bg.error.collectAsState()
    val stopping by bg.stopping.collectAsState()
    val running = list?.running.orEmpty()
    val finished = list?.finished.orEmpty()
    var showFinished by remember { mutableStateOf(false) }
    // Running rows count their time while the sheet is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.size) {
        while (running.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val t = LocalTokens.current
    val failed = stringResource(R.string.background_failed)
    HubSheet(onDismiss = bg::hide, title = stringResource(R.string.background_title), modifier = Modifier.testTag("background.sheet")) {
        error?.let { NoticeBox(it.text?.takeIf { s -> s.isNotBlank() } ?: failed, BadgeTone.Danger) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.background_none), body = stringResource(R.string.background_none_body),
                        icon = Lucide.Activity, modifier = Modifier.testTag("background.none"),
                    )
                }
            }
            items(running, key = { it.id }) { item ->
                BackgroundRow(item, now, stopping == item.id, if (many) shell.profileName(item.profile) else null, onStop = { bg.stop(item) }, onGo = onGo)
            }
            if (finished.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { showFinished = !showFinished }.padding(vertical = 8.dp).testTag("background.finished"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LucideIcon(if (showFinished) Lucide.ChevronDown else Lucide.ChevronRight, null, size = 14.dp, tint = t.textMuted)
                        Text(
                            stringResource(R.string.background_finished, finished.size),
                            fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted,
                        )
                    }
                }
                if (showFinished) {
                    items(finished, key = { "done:" + it.id }) { item -> BackgroundRow(item, now, false, if (many) shell.profileName(item.profile) else null, onStop = {}, onGo = onGo) }
                }
            }
        }
    }
}

@Composable
private fun BackgroundRow(item: BackgroundItem, now: Long, stopping: Boolean, profileName: String?, onStop: () -> Unit, onGo: (Route) -> Unit) {
    val t = LocalTokens.current
    val route = BackgroundRules.routeOf(item)
    val ms = BackgroundRules.elapsedMs(item, now)
    HubCard(Modifier.testTag("background.item.${item.id}"), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(kindLabel(item.kind))
            if (profileName != null) Badge(profileName, tone = BadgeTone.Accent)
            Spacer(Modifier.weight(1f))
            Badge(statusLabel(item.status), tone = statusTone(item.status), dot = item.status == BackgroundStatus.RUNNING)
        }
        Text(
            BackgroundRules.title(item, stringResource(R.string.background_untitled)),
            fontSize = FontTokens.sizeMd.sp, color = t.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ms?.let { Text(BackgroundRules.clock(it), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            Spacer(Modifier.weight(1f))
            if (route != null) {
                HubButton(
                    stringResource(R.string.background_open_item),
                    {
                        // The task or workflow run itself opens on its page (nav/Focus.kt).
                        hub.core.android.nav.Focus.item.value = hub.core.android.nav.Focus.of(item.resource, item.profile)
                        onGo(route)
                    },
                    kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.ExternalLink,
                )
            }
            if (item.stoppable) {
                HubButton(
                    stringResource(R.string.background_stop), onStop, kind = ButtonKind.Ghost, size = ControlSize.Sm,
                    icon = Lucide.CircleStop, loading = stopping, modifier = Modifier.testTag("background.stop.${item.id}"),
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: BackgroundKind): String = stringResource(
    when (kind) {
        BackgroundKind.CHAT_RUN -> R.string.background_kind_chat_run
        BackgroundKind.TASK_RUN -> R.string.background_kind_task_run
        BackgroundKind.SCHEDULE_RUN -> R.string.background_kind_schedule_run
        BackgroundKind.WORKFLOW_RUN -> R.string.background_kind_workflow_run
        BackgroundKind.JOB -> R.string.background_kind_job
        BackgroundKind.SUBAGENT -> R.string.background_kind_subagent
    },
)

@Composable
private fun statusLabel(status: BackgroundStatus): String = stringResource(
    when (status) {
        BackgroundStatus.QUEUED -> R.string.background_status_queued
        BackgroundStatus.RUNNING -> R.string.background_status_running
        BackgroundStatus.SUCCEEDED -> R.string.background_status_succeeded
        BackgroundStatus.FAILED -> R.string.background_status_failed
        BackgroundStatus.CANCELLED -> R.string.background_status_cancelled
    },
)

private fun statusTone(status: BackgroundStatus): BadgeTone = when (status) {
    BackgroundStatus.QUEUED -> BadgeTone.Neutral
    BackgroundStatus.RUNNING -> BadgeTone.Info
    BackgroundStatus.SUCCEEDED -> BadgeTone.Success
    BackgroundStatus.FAILED -> BadgeTone.Danger
    BackgroundStatus.CANCELLED -> BadgeTone.Warning
}
