package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import kotlinx.coroutines.launch

@Composable
private fun JobsPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var notice by remember { mutableStateOf<AgentNote?>(null) }
    val started = stringResource(R.string.schedules_started)
    val load = rememberLoad(agent.id, profile) { apis().schedules.schedulesList(profile = profile, agentId = agent.id).items }
    LoadView(load) { jobs ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.jobs")) {
            item { AgentNoteView(notice) }
            if (jobs.isEmpty()) item { EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.RotateCcwClock) }
            items(jobs, key = { it.id }) { job ->
                HubCard(Modifier.testTag("job.${job.id}"), padding = 14.dp) {
                    InContentDirection(job.name) { Text(job.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold) }
                    Text(
                        listOfNotNull(job.trigger.display ?: job.trigger.expression, job.nextRunAt?.let { stringResource(R.string.schedules_next, localTime(it)) }).joinToString(" · "),
                        fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                    )
                    job.lastError?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger, maxLines = 2) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubButton(stringResource(R.string.schedules_run_now), {
                            scope.launch {
                                ops.runJob(job).onSuccess { notice = AgentNote(started, BadgeTone.Success) }
                                    .onFailure { notice = AgentNote(error = it as HubError) }
                                load.reload()
                            }
                        }, size = ControlSize.Sm, icon = Lucide.Play, modifier = Modifier.testTag("job.${job.id}.run"))
                        HubButton(
                            stringResource(if (job.enabled) R.string.schedules_pause else R.string.schedules_resume), {
                                scope.launch { ops.pauseJob(job, !job.enabled); load.reload() }
                            },
                            kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = if (job.enabled) Lucide.Pause else Lucide.Play,
                        )
                    }
                }
            }
        }
    }
}

internal val agentJobsPage = AgentPageEntry("agent_jobs") { agent, profile -> JobsPage(agent, profile) }
