package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Job
import hub.core.client.model.JobStatus
import kotlinx.coroutines.launch

/** What the card last started and how it stands: the job while it runs, then its outcome. */
private data class CardRun(val action: AgentCardRules.Action, val job: Job? = null, val error: HubError? = null)

/**
 * The actions on an agent's card (apps batch 8), as the web's card and its Updates card: the one
 * button it needs now (Install, Update, Restart) and «⋯» with the rest (check for updates, update
 * automatically, remove after asking). A job is followed to its end with its progress, then its
 * outcome is said and the list read again ([onChanged]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentCardActions(agent: Agent, profile: String, onChanged: () -> Unit) {
    val ops = rememberToolOps(agent, profile)
    val scope = rememberCoroutineScope()
    var run by remember(agent.id) { mutableStateOf<CardRun?>(null) }
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val running = run?.let { it.error == null && (it.job == null || !AgentCardRules.terminal(it.job.status)) } == true

    fun start(action: AgentCardRules.Action) {
        run = CardRun(action)
        scope.launch {
            ops.act(action).onSuccess { jobId ->
                if (jobId == null) { run = null; onChanged(); return@onSuccess }
                ops.follow(jobId) { job -> run = CardRun(action, job) }
                onChanged()
            }.onFailure { run = CardRun(action, error = it as HubError) }
        }
    }

    val primary = AgentCardRules.primary(agent)
    val more = AgentCardRules.menu(agent)
    AgentCardRules.update(agent)?.let { version ->
        Badge(
            stringResource(if (AgentCardRules.updateUntested(agent)) R.string.agents_card_update_available_untested else R.string.agents_card_update_available, version),
            tone = BadgeTone.Info, modifier = Modifier.testTag("agent.card.${agent.slug}.update"),
        )
    }
    if (agent.install.newerThanTested) Badge(stringResource(R.string.agents_card_newer_than_tested), tone = BadgeTone.Warning)
    if (primary != null || more.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            primary?.let { action ->
                HubButton(
                    actionLabel(action, agent), { if (action == AgentCardRules.Action.UNINSTALL) removing = true else start(action) },
                    kind = if (action == AgentCardRules.Action.INSTALL || action == AgentCardRules.Action.UPGRADE) ButtonKind.Primary else ButtonKind.Secondary,
                    size = ControlSize.Sm, icon = actionIcon(action), loading = running && run?.action == action, enabled = !running,
                    modifier = Modifier.testTag("agent.card.${agent.slug}.${action.name.lowercase()}"),
                )
            }
            if (more.isNotEmpty()) Box {
                HubIconButton(Lucide.Ellipsis, stringResource(R.string.agents_card_actions), { menu = true }, size = 32.dp, iconSize = 16.dp, enabled = !running, modifier = Modifier.testTag("agent.card.${agent.slug}.more"))
                HubMenu(menu, { menu = false }) {
                    more.forEach { action ->
                        MenuItem(
                            actionLabel(action, agent), {
                                menu = false
                                if (action == AgentCardRules.Action.UNINSTALL) removing = true else start(action)
                            },
                            icon = actionIcon(action), danger = action == AgentCardRules.Action.UNINSTALL,
                            modifier = Modifier.testTag("agent.card.${agent.slug}.${action.name.lowercase()}"),
                        )
                    }
                }
            }
        }
    }
    run?.let { r -> RunLine(r, agent) }
    if (removing) {
        ConfirmDialog(
            stringResource(R.string.agents_card_remove_title, agent.name), stringResource(R.string.agents_card_remove_body),
            stringResource(R.string.agents_card_remove), { removing = false; start(AgentCardRules.Action.UNINSTALL) }, { removing = false }, danger = true,
        )
    }
}

@Composable
private fun actionLabel(action: AgentCardRules.Action, agent: Agent): String = when (action) {
    AgentCardRules.Action.INSTALL -> stringResource(R.string.agents_card_install)
    AgentCardRules.Action.UNINSTALL -> stringResource(R.string.agents_card_remove)
    AgentCardRules.Action.RESTART -> stringResource(R.string.agents_card_restart)
    AgentCardRules.Action.CHECK_UPDATE -> stringResource(R.string.agents_card_check_update)
    AgentCardRules.Action.UPGRADE -> AgentCardRules.update(agent)?.let { stringResource(R.string.agents_card_update_to, it) } ?: stringResource(R.string.agents_card_update_now)
    AgentCardRules.Action.AUTO_UPDATE_ON -> stringResource(R.string.agents_card_auto_update)
    AgentCardRules.Action.AUTO_UPDATE_OFF -> stringResource(R.string.agents_card_auto_update_off)
}

private fun actionIcon(action: AgentCardRules.Action): Int = when (action) {
    AgentCardRules.Action.INSTALL -> Lucide.Download
    AgentCardRules.Action.UNINSTALL -> Lucide.Trash
    AgentCardRules.Action.RESTART -> Lucide.RotateCw
    AgentCardRules.Action.CHECK_UPDATE -> Lucide.RefreshCw
    AgentCardRules.Action.UPGRADE -> Lucide.CircleArrowDown
    AgentCardRules.Action.AUTO_UPDATE_ON, AgentCardRules.Action.AUTO_UPDATE_OFF -> Lucide.ClockArrowUp
}

/** The bar and the line under it while a job runs; the outcome in words when it ends. */
@Composable
private fun RunLine(run: CardRun, agent: Agent) {
    val t = LocalTokens.current
    val job = run.job
    when {
        run.error != null -> ToolErrorNotice(run.error)
        job == null || !AgentCardRules.terminal(job.status) -> Column(Modifier.fillMaxWidth().testTag("agent.card.${agent.slug}.progress")) {
            val share = job?.let { AgentCardRules.progress(it) } ?: 0.05f
            Box(Modifier.fillMaxWidth().height(4.dp).background(t.surface2, CircleShape)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().background(t.accent, CircleShape))
            }
            Text(
                listOfNotNull(
                    stringResource(if (job?.status == JobStatus.RUNNING) R.string.agents_card_job_running else R.string.agents_card_job_queued),
                    job?.progress?.message,
                ).joinToString(" · "),
                fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
            )
        }
        job?.status == JobStatus.SUCCEEDED -> NoticeBox(doneText(run.action, job!!, agent), BadgeTone.Success, Modifier.testTag("agent.card.${agent.slug}.done"))
        job?.status == JobStatus.CANCELLED -> NoticeBox(stringResource(R.string.agents_card_job_cancelled), BadgeTone.Warning)
        else -> NoticeBox(
            job?.error?.error?.let { stringResource(R.string.agents_card_failed_with, it) } ?: stringResource(R.string.agents_card_job_failed),
            BadgeTone.Danger, Modifier.testTag("agent.card.${agent.slug}.failed"),
        )
    }
}

@Composable
private fun doneText(action: AgentCardRules.Action, job: Job, agent: Agent): String {
    val version = AgentCardRules.resultVersion(job)
    return when (action) {
        AgentCardRules.Action.INSTALL -> version?.let { stringResource(R.string.agents_card_done_install_version, it) } ?: stringResource(R.string.agents_card_done_install)
        AgentCardRules.Action.UPGRADE -> version?.let { stringResource(R.string.agents_card_done_upgrade_version, it) } ?: stringResource(R.string.agents_card_done_upgrade)
        AgentCardRules.Action.UNINSTALL -> stringResource(R.string.agents_card_done_uninstall)
        AgentCardRules.Action.RESTART -> stringResource(R.string.agents_card_done_restart)
        AgentCardRules.Action.CHECK_UPDATE -> {
            // The check's result carries the answer; the card is read again too.
            val available = (job.result?.get("update_available") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val latest = (job.result?.get("latest_version") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            if (available && latest != null) stringResource(R.string.agents_card_update_available, latest) else stringResource(R.string.agents_card_up_to_date)
        }
        else -> stringResource(R.string.agents_card_done_restart)
    }
}
