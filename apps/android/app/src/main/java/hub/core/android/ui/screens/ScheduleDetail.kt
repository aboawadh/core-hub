package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.ListPage
import hub.core.android.ui.components.Loading
import hub.core.android.ui.components.StatusBadge
import hub.core.android.ui.components.Tone
import hub.core.android.ui.components.rememberPagedList
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.DeliveryTarget
import hub.core.client.model.JobStatus
import hub.core.client.model.Schedule
import hub.core.client.model.ScheduleRun
import hub.core.client.model.ScheduleTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * One schedule on its own (batch 3): when it runs and what it asks, run now, pause or resume, edit,
 * delete (asks first), and its history page by page — each run's status, when it started, how long
 * it took, what it said, and the conversation it ran in. iOS's ScheduleDetailView.swift is its twin.
 */

/**
 * The detail, for a sheet. [profileName] is the schedule's profile's name when there is more than
 * one; [agentName] names an agent of the selector's profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleDetailBody(
    initial: Schedule,
    ops: ScheduleOps,
    profileName: String?,
    agentName: (String) -> String?,
    onChanged: () -> Unit,
    onEdit: (Schedule) -> Unit,
    onDelete: (Schedule) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var schedule by remember(initial) { mutableStateOf(initial) }
    var note by remember { mutableStateOf<ScheduleNote?>(null) }
    var busy by remember { mutableStateOf(false) }
    val runs = rememberPagedList<ScheduleRun>(schedule.id, key = { it.id }) { cursor ->
        ops.runs(schedule, cursor).getOrThrow().let { ListPage(it.items, it.nextCursor) }
    }
    // A run still going is asked about again every few seconds until it ends, as on the web.
    val live = ScheduleRules.live(runs.items)
    LaunchedEffect(live) {
        while (live) {
            delay(3_000)
            runs.refresh()
        }
    }
    fun act(block: suspend () -> Result<*>, done: (Any?) -> Unit = {}) {
        busy = true
        scope.launch {
            block().onSuccess { done(it) }.onFailure { note = ScheduleNote(error = it as HubError) }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("schedule.sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InContentDirection(schedule.name) {
            Text(schedule.name, fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("schedule.sheet.name"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StateBadge(schedule)
            if (ScheduleRules.fromHermes(schedule)) Badge(stringResource(R.string.sched_hermes_origin))
            profileName?.let { Badge(it, tone = BadgeTone.Accent) }
        }
        GroupedList {
            Item(stringResource(R.string.sched_detail_when), value = schedule.trigger.display ?: schedule.trigger.expression ?: schedule.trigger.runAt?.let(::localTime))
            Item(stringResource(R.string.sched_detail_next), value = schedule.nextRunAt?.let(::localTime) ?: stringResource(R.string.sched_detail_never))
            schedule.lastRunAt?.let { Item(stringResource(R.string.sched_detail_last), value = localTime(it)) }
            if (schedule.target.kind == ScheduleTarget.Kind.WORKFLOW) {
                Item(stringResource(R.string.sched_detail_agent), value = stringResource(R.string.sched_detail_workflow))
            } else {
                schedule.target.agentId?.let(agentName)?.let { Item(stringResource(R.string.sched_detail_agent), value = it) }
            }
        }
        schedule.target.prompt?.takeIf { it.isNotBlank() }?.let { p ->
            Text(stringResource(R.string.sched_detail_prompt), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            InContentDirection(p) { Text(p, fontSize = FontTokens.sizeSm.sp, maxLines = 8) }
        }
        if (schedule.delivery.kind == DeliveryTarget.Kind.CHANNEL) schedule.delivery.channel?.let {
            Text(stringResource(R.string.sched_detail_delivers_to, it), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        }
        schedule.lastError?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.sched_detail_last_error, it), fontSize = FontTokens.sizeSm.sp, color = t.danger)
        }
        ScheduleNoteView(note)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(
                stringResource(R.string.schedules_run_now), {
                    act({ ops.runNow(schedule) }) { note = ScheduleNote(ScheduleRules.fired(schedule)); runs.refresh(); onChanged() }
                },
                size = ControlSize.Md, icon = Lucide.Play, enabled = !busy, modifier = Modifier.testTag("schedule.run"),
            )
            HubButton(
                stringResource(R.string.kit_edit), { onEdit(schedule) }, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.Pencil,
                modifier = Modifier.testTag("schedule.edit"),
            )
            HubButton(
                stringResource(R.string.kit_delete), { onDelete(schedule) }, kind = ButtonKind.Ghost, size = ControlSize.Md, icon = Lucide.Trash,
                modifier = Modifier.testTag("schedule.delete"),
            )
        }
        ToggleRow(
            stringResource(R.string.sched_detail_enabled), schedule.enabled, { on ->
                act({ ops.setEnabled(schedule, on) }) { saved -> (saved as? Schedule)?.let { schedule = it }; onChanged() }
            },
            Modifier.fillMaxWidth().testTag("schedule.enabled"), enabled = !busy,
        )

        SectionTitle(stringResource(R.string.schedules_history))
        when {
            runs.loading -> Loading()
            runs.items.isEmpty() && runs.error != null -> ErrorNotice(runs.error)
            runs.items.isEmpty() -> Text(
                stringResource(R.string.schedules_no_runs), fontSize = FontTokens.sizeSm.sp, color = t.textMuted,
                modifier = Modifier.testTag("schedule.history.empty"),
            )
            else -> GroupedList(Modifier.testTag("schedule.history")) {
                runs.items.forEach { run -> RunItem(run) { id -> onOpenChat(id) } }
            }
        }
        if (runs.hasMore) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                if (runs.loadingMore) Spinner()
                else HubButton(stringResource(R.string.kit_load_more), runs::loadMore, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("schedule.history.more"))
            }
        }
    }
}

/** One line of a schedule's history. */
@Composable
private fun hub.core.android.ui.kit.GroupScope.RunItem(run: ScheduleRun, onOpenChat: (String) -> Unit) {
    val trigger = stringResource(if (run.trigger == ScheduleRun.Trigger.MANUAL) R.string.sched_run_manual else R.string.sched_run_on_time)
    val took = ScheduleRules.seconds(run)?.let { stringResource(R.string.sched_run_took, words(ScheduleRules.duration(it))) }
    val meta = listOfNotNull(trigger, (run.startedAt ?: run.finishedAt)?.let(::localTime), took).joinToString(" · ")
    val said = listOfNotNull(
        run.error?.takeIf { it.isNotBlank() } ?: run.outputPreview?.takeIf { it.isNotBlank() },
        run.deliveryError?.takeIf { run.deliveryStatus == ScheduleRun.DeliveryStatus.FAILED }?.let { stringResource(R.string.sched_run_not_delivered, it) },
    ).joinToString("\n").ifEmpty { null }
    Item(
        meta,
        subtitle = said,
        chevron = run.sessionId != null,
        tag = "schedule.run.${run.id}",
        onClick = run.sessionId?.let { id -> { onOpenChat(id) } },
        trailing = {
            if (run.waiting) StatusBadge(stringResource(R.string.sched_run_waiting), Tone.WARNING)
            else StatusBadge(
                jobLabel(run.status),
                when (run.status) {
                    JobStatus.SUCCEEDED -> Tone.SUCCESS
                    JobStatus.FAILED -> Tone.DANGER
                    JobStatus.RUNNING -> Tone.INFO
                    else -> null
                },
            )
        },
    )
}
