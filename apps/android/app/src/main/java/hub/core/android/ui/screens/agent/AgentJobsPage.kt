package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Schedule
import kotlinx.coroutines.launch

/**
 * The agent's jobs: the Schedules list narrowed to this agent and profile (§٤ rule 8), as on the
 * web — run one now, pause or resume it, delete it (asks first; a Hermes job goes from Hermes's
 * scheduler too). Making or editing one is the Schedules page's.
 */
@Composable
private fun JobsPage(agent: Agent, profile: String) {
    val context = LocalContext.current
    val ops = remember { ScheduleOps { context.graph.store.current?.let(context.graph::apis) } }
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var note by remember { mutableStateOf<ScheduleNote?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val confirm = rememberConfirmDelete<Schedule>()
    val load = rememberLoad(agent.id, profile) { ops.jobs(profile, agent.id).getOrThrow() }
    fun act(job: Schedule, block: suspend () -> Result<*>, done: () -> Unit = {}) {
        busy = job.id
        scope.launch {
            block().onSuccess { done() }.onFailure { note = ScheduleNote(error = it as HubError) }
            busy = null
            load.reload()
        }
    }
    LoadView(load) { jobs ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.jobs")) {
            item { Text(stringResource(R.string.sched_jobs_note), fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
            item { ScheduleNoteView(note) }
            if (jobs.isEmpty()) item {
                EmptyState(stringResource(R.string.sched_jobs_none), body = stringResource(R.string.sched_jobs_none_body), icon = Lucide.RotateCcwClock)
            }
            items(jobs, key = { it.id }) { job ->
                HubCard(Modifier.testTag("job.${job.id}"), padding = 14.dp) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InContentDirection(job.name) {
                            Text(job.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                        StateBadge(job)
                    }
                    Text(
                        listOfNotNull(job.trigger.display ?: job.trigger.expression, job.nextRunAt?.let { stringResource(R.string.schedules_next, localTime(it)) }).joinToString(" · "),
                        fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                    )
                    job.target.prompt?.takeIf { it.isNotBlank() }?.let { p ->
                        InContentDirection(p) { Text(p, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 2) }
                    }
                    job.lastError?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger, maxLines = 2) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HubButton(
                            stringResource(R.string.schedules_run_now), { act(job, { ops.runNow(job) }) { note = ScheduleNote(ScheduleRules.fired(job)) } },
                            size = ControlSize.Sm, icon = Lucide.Play, enabled = busy != job.id, modifier = Modifier.testTag("job.${job.id}.run"),
                        )
                        HubButton(
                            stringResource(if (job.enabled) R.string.schedules_pause else R.string.schedules_resume), { act(job, { ops.setEnabled(job, !job.enabled) }) },
                            kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = if (job.enabled) Lucide.Pause else Lucide.Play,
                            enabled = busy != job.id, modifier = Modifier.testTag("job.${job.id}.pause"),
                        )
                        Spacer(Modifier.weight(1f))
                        HubIconButton(
                            Lucide.Trash, stringResource(R.string.kit_delete), { confirm.ask(job) }, size = 32.dp, iconSize = 16.dp,
                            modifier = Modifier.testTag("job.${job.id}.delete"),
                        )
                    }
                }
            }
        }
    }
    ConfirmDeleteDialog(
        confirm, { stringResource(R.string.kit_delete_confirm, it.name) },
        onDelete = { ops.delete(it) },
        onDeleted = { note = null; load.reload() },
        // A job in Hermes's scheduler goes from there too.
        body = stringResource(if (confirm.pending?.let(ScheduleRules::fromHermes) == true) R.string.sched_jobs_delete_body else R.string.kit_delete_body),
    )
}

internal val agentJobsPage = AgentPageEntry("agent_jobs") { agent, profile -> JobsPage(agent, profile) }
