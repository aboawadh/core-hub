package hub.core.android.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import hub.core.android.ui.components.TriggerDraft
import hub.core.android.ui.components.TriggerEditor
import hub.core.android.ui.components.TriggerRules
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubRadio
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Schedule
import hub.core.client.model.ScheduleAllOfExternal
import hub.core.client.model.ScheduleOverlap
import hub.core.client.model.SchedulePreviewRequest
import hub.core.client.model.ScheduleRun
import hub.core.client.model.ScheduleTarget
import hub.core.client.model.ScheduleTrigger
import hub.core.client.model.ScheduleWrite
import hub.core.client.model.JobStatus
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch

/*
 * Make or change a schedule (batch 3), as the web's Schedules form: a name, when it runs (the shared
 * TriggerEditor with the web's "Common schedules" and the hub's next runs), the agent and what it is
 * asked, and the two run options the hub keeps (Hermes decides them for its own jobs). The rules are
 * plain functions ([ScheduleRules]) and the calls one class ([ScheduleOps]), so a JVM test checks
 * both. iOS's ScheduleRules.swift / ScheduleEditorSheet.swift are the twins.
 */

/** One of the web's "Common schedules" (owner, 2026-09-25): fills the kind and its value. */
data class ScheduleTemplate(val id: String, val kind: ScheduleTrigger.Kind, val value: String, @StringRes val label: Int)

/** A schedule as it is being written. */
data class ScheduleDraft(
    val name: String = "",
    val trigger: TriggerDraft = TriggerDraft(),
    val agentId: String? = null,
    val prompt: String = "",
    val runIfMissed: Boolean = false,
    val overlap: ScheduleOverlap = ScheduleOverlap.WAIT,
)

enum class ScheduleProblem { Name, Trigger, Agent }

/** A string resource and its arguments, so a rule can say what to show without a Context. */
data class Words(@StringRes val res: Int, val args: List<String> = emptyList())

object ScheduleRules {
    val templates = listOf(
        ScheduleTemplate("every_hour", ScheduleTrigger.Kind.CRON, "0 * * * *", R.string.sched_tpl_every_hour),
        ScheduleTemplate("daily_8", ScheduleTrigger.Kind.CRON, "0 8 * * *", R.string.sched_tpl_daily_8),
        ScheduleTemplate("weekdays_9", ScheduleTrigger.Kind.CRON, "0 9 * * 1-5", R.string.sched_tpl_weekdays_9),
        ScheduleTemplate("monday_9", ScheduleTrigger.Kind.CRON, "0 9 * * 1", R.string.sched_tpl_monday_9),
        ScheduleTemplate("monthly_1", ScheduleTrigger.Kind.CRON, "0 9 1 * *", R.string.sched_tpl_monthly_1),
        ScheduleTemplate("every_15", ScheduleTrigger.Kind.INTERVAL, "15", R.string.sched_tpl_every_15),
    )

    val overlaps = listOf(ScheduleOverlap.SKIP, ScheduleOverlap.WAIT, ScheduleOverlap.PARALLEL, ScheduleOverlap.REPLACE)

    /** The history is read this many runs at a time. */
    const val RUNS_PAGE = 20

    fun apply(template: ScheduleTemplate, trigger: TriggerDraft): TriggerDraft =
        if (template.kind == ScheduleTrigger.Kind.INTERVAL) {
            val (every, unit) = TriggerRules.split(template.value.toIntOrNull() ?: 60)
            trigger.copy(kind = template.kind, every = every.toString(), unit = unit)
        } else {
            trigger.copy(kind = template.kind, cron = template.value)
        }

    /** Hermes first: the one agent with a scheduler of its own (as on the web). */
    fun defaultAgent(agents: List<Agent>): String? = (agents.firstOrNull { it.slug == "hermes" } ?: agents.firstOrNull())?.id

    fun isHermes(agentId: String?, agents: List<Agent>): Boolean = agents.any { it.id == agentId && it.slug == "hermes" }

    /** Whether the hub keeps this schedule's run options; Hermes decides them for a job in its scheduler. */
    fun hasRunOptions(schedule: Schedule): Boolean = schedule.external == null

    fun fromHermes(schedule: Schedule): Boolean = schedule.external?.source == ScheduleAllOfExternal.Source.HERMES

    fun newDraft(agents: List<Agent>, zone: String = ZoneId.systemDefault().id): ScheduleDraft =
        ScheduleDraft(trigger = TriggerRules.draft(null, zone), agentId = defaultAgent(agents))

    fun draft(schedule: Schedule): ScheduleDraft = ScheduleDraft(
        name = schedule.name,
        trigger = TriggerRules.draft(schedule.trigger),
        agentId = schedule.target.agentId,
        prompt = schedule.target.prompt.orEmpty(),
        runIfMissed = schedule.runIfMissed ?: false,
        overlap = schedule.overlap ?: ScheduleOverlap.WAIT,
    )

    /** The same draft in [zone] (the one Hermes asked for). */
    fun withZone(draft: ScheduleDraft, zone: String): ScheduleDraft = draft.copy(trigger = draft.trigger.copy(timezone = zone))

    /** Why it cannot be saved yet. An edit keeps its agent, so it needs none chosen. */
    fun problem(draft: ScheduleDraft, editing: Boolean): ScheduleProblem? = when {
        draft.name.isBlank() -> ScheduleProblem.Name
        TriggerRules.build(draft.trigger) == null -> ScheduleProblem.Trigger
        !editing && draft.agentId == null -> ScheduleProblem.Agent
        else -> null
    }

    private fun text(value: String?): String? = value?.trim()?.ifEmpty { null }

    /**
     * A new schedule: a prompt to the chosen agent. Hermes decides the run options for its own jobs
     * and refuses them in the body, so they are left out for it.
     */
    fun create(draft: ScheduleDraft, hermes: Boolean): ScheduleWrite? {
        if (problem(draft, editing = false) != null) return null
        val trigger = TriggerRules.build(draft.trigger) ?: return null
        return ScheduleWrite(
            name = draft.name.trim(),
            trigger = trigger,
            target = ScheduleTarget(kind = ScheduleTarget.Kind.AGENT_PROMPT, skills = emptyList(), agentId = draft.agentId, prompt = text(draft.prompt)),
            runIfMissed = if (hermes) null else draft.runIfMissed,
            overlap = if (hermes) null else draft.overlap,
        )
    }

    fun same(a: ScheduleTrigger, b: ScheduleTrigger): Boolean =
        a.kind == b.kind && a.expression == b.expression && a.everyMinutes == b.everyMinutes &&
            a.runAt?.toInstant() == b.runAt?.toInstant() && a.timezone == b.timezone

    /** An edit sends only what changed; null when nothing did. */
    fun update(schedule: Schedule, draft: ScheduleDraft): ScheduleWrite? {
        if (problem(draft, editing = true) != null) return null
        val trigger = TriggerRules.build(draft.trigger) ?: return null
        val name = draft.name.trim().takeIf { it != schedule.name }
        val newTrigger = trigger.takeUnless { same(it, schedule.trigger) }
        val target = schedule.target.takeIf { it.kind == ScheduleTarget.Kind.AGENT_PROMPT && text(draft.prompt) != text(it.prompt) }
            ?.copy(prompt = text(draft.prompt))
        val options = hasRunOptions(schedule)
        val missed = draft.runIfMissed.takeIf { options && it != (schedule.runIfMissed ?: false) }
        val overlap = draft.overlap.takeIf { options && it != (schedule.overlap ?: ScheduleOverlap.WAIT) }
        if (name == null && newTrigger == null && target == null && missed == null && overlap == null) return null
        return ScheduleWrite(name = name, trigger = newTrigger, target = target, runIfMissed = missed, overlap = overlap)
    }

    /** The hub's refusal in the person's words; Hermes's own in Hermes's. Null: say it as every screen does. */
    fun refusal(error: HubError): Words? = when (error.reason) {
        "hermes_refused" -> Words(R.string.sched_hermes_refused, listOf(error.text.orEmpty()))
        "hermes_unreachable" -> Words(R.string.sched_hermes_unreachable)
        "hermes_timezone" -> Words(R.string.sched_hermes_timezone, listOf(error.timezone.orEmpty()))
        "hermes_delivery" -> Words(R.string.sched_hermes_delivery)
        "hermes_prompt_required" -> Words(R.string.sched_hermes_prompt_required)
        "hermes_run_options" -> Words(R.string.sched_hermes_run_options)
        "target_unavailable" -> Words(R.string.sched_target_unavailable, listOf(error.text.orEmpty()))
        else -> null
    }

    /** The zone Hermes runs its cron in, when that is why it refused: offered as «Use {zone}». */
    fun askedZone(error: HubError): String? = error.timezone?.takeIf { error.reason == "hermes_timezone" && it.isNotBlank() }

    /** What «Run now» says it did: Hermes runs its own on its next tick. */
    fun fired(schedule: Schedule): Words =
        if (fromHermes(schedule)) Words(R.string.sched_hermes_fired) else Words(R.string.sched_fired, listOf(schedule.name))

    /** Whole seconds a run took; null until it ended. */
    fun seconds(run: ScheduleRun): Long? {
        val start = run.startedAt ?: return null
        val end = run.finishedAt ?: return null
        return Duration.between(start, end).plusMillis(500).seconds.coerceAtLeast(0)
    }

    /** «41s», «2m 5s», «1h 3m», with Latin digits in both languages. */
    fun duration(seconds: Long): Words = when {
        seconds < 60 -> Words(R.string.sched_dur_s, listOf(seconds.toString()))
        seconds < 3600 -> Words(R.string.sched_dur_m, listOf((seconds / 60).toString(), (seconds % 60).toString()))
        else -> Words(R.string.sched_dur_h, listOf((seconds / 3600).toString(), (seconds % 3600 / 60).toString()))
    }

    fun live(runs: List<ScheduleRun>): Boolean = runs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }

    @StringRes fun overlapLabel(overlap: ScheduleOverlap): Int = when (overlap) {
        ScheduleOverlap.SKIP -> R.string.sched_opt_skip
        ScheduleOverlap.WAIT -> R.string.sched_opt_wait
        ScheduleOverlap.PARALLEL -> R.string.sched_opt_parallel
        ScheduleOverlap.REPLACE -> R.string.sched_opt_replace
    }

    @StringRes fun overlapHint(overlap: ScheduleOverlap): Int = when (overlap) {
        ScheduleOverlap.SKIP -> R.string.sched_opt_skip_hint
        ScheduleOverlap.WAIT -> R.string.sched_opt_wait_hint
        ScheduleOverlap.PARALLEL -> R.string.sched_opt_parallel_hint
        ScheduleOverlap.REPLACE -> R.string.sched_opt_replace_hint
    }
}

@Composable
fun words(w: Words): String = stringResource(w.res, *w.args.toTypedArray())

/** The hub's refusal of a schedule call, in the words [ScheduleRules.refusal] picks or the hub's own. */
@Composable
fun scheduleRefusal(error: HubError): String = ScheduleRules.refusal(error)?.let { words(it) } ?: hub.core.android.ui.components.errorText(error)

/** Every call of the Schedules page, each to the schedule's own profile. */
class ScheduleOps(private val apis: () -> HubApis?) {
    private suspend fun <T> call(block: suspend (HubApis) -> T): Result<T> {
        val api = apis() ?: return Result.failure(HubError(401, "unauthorized", null))
        return hubCall { block(api) }
    }

    suspend fun list(cursor: String?) = call {
        it.schedules.schedulesList(profiles = hub.core.client.api.SchedulesApi.ProfilesSchedulesList.ALL, cursor = cursor, limit = 50)
    }
    suspend fun jobs(profile: String, agentId: String) = call { it.schedules.schedulesList(profile = profile, agentId = agentId).items }
    suspend fun agents(profile: String) = call { ChatAgents.startable(it.agents.agentsList(profile).items) }
    suspend fun create(profile: String, write: ScheduleWrite) = call { it.schedules.schedulesCreate(profile, write) }
    suspend fun update(schedule: Schedule, write: ScheduleWrite) = call { it.schedules.schedulesUpdate(schedule.profile, schedule.id, write) }
    suspend fun setEnabled(schedule: Schedule, enabled: Boolean) = update(schedule, ScheduleWrite(enabled = enabled))
    suspend fun delete(schedule: Schedule) = call { it.schedules.schedulesDelete(schedule.profile, schedule.id) }
    suspend fun runNow(schedule: Schedule) = call { it.schedules.schedulesRunNow(schedule.profile, schedule.id) }
    suspend fun get(schedule: Schedule) = call { it.schedules.schedulesGet(schedule.profile, schedule.id) }
    suspend fun byId(profile: String, id: String) = call { it.schedules.schedulesGet(profile, id) }
    suspend fun runs(schedule: Schedule, cursor: String?) = call {
        it.schedules.schedulesListRuns(schedule.profile, schedule.id, cursor = cursor, limit = ScheduleRules.RUNS_PAGE)
    }
    suspend fun preview(profile: String, trigger: ScheduleTrigger): List<OffsetDateTime> {
        val api = apis() ?: throw HubError(401, "unauthorized", null)
        return api.schedules.schedulesPreviewTrigger(profile, SchedulePreviewRequest(trigger = trigger, count = 3)).nextRuns
    }
}

/**
 * The form as a sheet: [schedule] null makes a new one in [profile] with one of [agents]; otherwise
 * it edits that schedule in its own profile (its agent stays). [onSaved] gets what the hub kept.
 */
@Composable
fun ScheduleEditorSheet(
    schedule: Schedule?,
    profile: String,
    profileNote: String?,
    agents: List<Agent>,
    ops: ScheduleOps,
    onDismiss: () -> Unit,
    onSaved: (Schedule) -> Unit,
) {
    HubSheet(onDismiss = onDismiss, title = stringResource(if (schedule == null) R.string.sched_new else R.string.sched_edit_title)) {
        ScheduleEditorBody(schedule, profile, profileNote, agents, ops, onDismiss, onSaved)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorBody(
    schedule: Schedule?,
    profile: String,
    profileNote: String?,
    agents: List<Agent>,
    ops: ScheduleOps,
    onDismiss: () -> Unit,
    onSaved: (Schedule) -> Unit,
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var draft by remember(schedule?.id) { mutableStateOf(schedule?.let(ScheduleRules::draft) ?: ScheduleRules.newDraft(agents)) }
    var tried by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<HubError?>(null) }
    var templates by remember { mutableStateOf(false) }
    val editing = schedule != null
    val hermes = schedule?.let { !ScheduleRules.hasRunOptions(it) } ?: ScheduleRules.isHermes(draft.agentId, agents)
    val problem = ScheduleRules.problem(draft, editing)
    // The draft's agent is chosen once the agents arrive.
    androidx.compose.runtime.LaunchedEffect(agents) {
        if (!editing && draft.agentId == null && agents.isNotEmpty()) draft = draft.copy(agentId = ScheduleRules.defaultAgent(agents))
    }

    fun save() {
        tried = true
        if (ScheduleRules.problem(draft, editing) != null) return
        saving = true
        scope.launch {
            val result = if (schedule != null) {
                val write = ScheduleRules.update(schedule, draft)
                if (write == null) Result.success(schedule) else ops.update(schedule, write)
            } else {
                ops.create(profile, ScheduleRules.create(draft, hermes)!!)
            }
            saving = false
            result.onSuccess { failure = null; onSaved(it); onDismiss() }.onFailure { failure = it as HubError }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("schedule.form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        failure?.let { e ->
            val zone = ScheduleRules.askedZone(e)
            // Hermes runs every cron in one zone: one tap saves again in it.
            val useZone: @Composable () -> Unit = {
                HubButton(stringResource(R.string.sched_form_use_zone, zone.orEmpty()), {
                    draft = ScheduleRules.withZone(draft, zone.orEmpty())
                    save()
                }, kind = ButtonKind.Secondary, size = ControlSize.Sm, modifier = Modifier.testTag("schedule.form.use_zone"))
            }
            NoticeBox(scheduleRefusal(e), BadgeTone.Danger, action = if (zone != null) useZone else null)
        }
        HubTextField(
            draft.name, { draft = draft.copy(name = it) }, label = stringResource(R.string.sched_form_name), size = ControlSize.Md,
            error = if (tried && problem == ScheduleProblem.Name) stringResource(R.string.sched_form_need_name) else null, fieldTag = "schedule.form.name",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sched_form_when), fontSize = FontTokens.sizeSm.sp, color = t.textMuted, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box {
                HubButton(
                    stringResource(R.string.sched_form_templates), { templates = true }, kind = ButtonKind.Secondary, size = ControlSize.Sm,
                    icon = Lucide.CalendarClock, modifier = Modifier.testTag("schedule.form.templates"),
                )
                HubMenu(templates, { templates = false }) {
                    ScheduleRules.templates.forEach { template ->
                        MenuItem(stringResource(template.label), {
                            templates = false
                            draft = draft.copy(trigger = ScheduleRules.apply(template, draft.trigger))
                        })
                    }
                }
            }
        }
        TriggerEditor(draft.trigger, { draft = draft.copy(trigger = it) }, preview = { ops.preview(profile, it) }, tag = "schedule.form.trigger")
        if (tried && problem == ScheduleProblem.Trigger) Text(stringResource(R.string.sched_form_need_trigger), fontSize = FontTokens.sizeXs.sp, color = t.danger)

        Text(stringResource(R.string.sched_form_agent), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        when {
            schedule != null -> Text(
                agents.firstOrNull { it.id == schedule.target.agentId }?.name
                    ?: if (schedule.target.kind == ScheduleTarget.Kind.WORKFLOW) stringResource(R.string.sched_detail_workflow) else "—",
                fontSize = FontTokens.sizeMd.sp,
            )
            agents.isEmpty() -> Text(stringResource(R.string.sched_form_no_agents), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                agents.forEach { agent ->
                    Chip(agent.name, draft.agentId == agent.id, { draft = draft.copy(agentId = agent.id) }, size = ControlSize.Sm, modifier = Modifier.testTag("schedule.form.agent.${agent.slug}"))
                }
            }
        }
        if (tried && problem == ScheduleProblem.Agent) Text(stringResource(R.string.sched_form_need_agent), fontSize = FontTokens.sizeXs.sp, color = t.danger)
        HubTextField(
            draft.prompt, { draft = draft.copy(prompt = it) }, label = stringResource(R.string.sched_form_prompt), singleLine = false, minLines = 3,
            size = ControlSize.Md, fieldTag = "schedule.form.prompt",
        )

        SectionTitle(stringResource(R.string.sched_opt_title))
        if (hermes) {
            Text(stringResource(R.string.sched_opt_hermes), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        } else {
            ToggleRow(
                stringResource(R.string.sched_opt_missed), draft.runIfMissed, { draft = draft.copy(runIfMissed = it) },
                Modifier.fillMaxWidth().testTag("schedule.form.missed"), subtitle = stringResource(R.string.sched_opt_missed_hint),
            )
            Text(stringResource(R.string.sched_opt_overlap), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium)
            ScheduleRules.overlaps.forEach { overlap ->
                Row(
                    Modifier.fillMaxWidth().clickable { draft = draft.copy(overlap = overlap) }.padding(vertical = 6.dp).testTag("schedule.form.overlap.${overlap.value}"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
                ) {
                    HubRadio(draft.overlap == overlap, Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(ScheduleRules.overlapLabel(overlap)), fontSize = FontTokens.sizeMd.sp)
                        Text(stringResource(ScheduleRules.overlapHint(overlap)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    }
                }
            }
            Text(stringResource(R.string.sched_opt_overlap_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        }
        profileNote?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(stringResource(R.string.save), { save() }, size = ControlSize.Md, loading = saving, modifier = Modifier.testTag("schedule.form.save"))
        }
    }
}
