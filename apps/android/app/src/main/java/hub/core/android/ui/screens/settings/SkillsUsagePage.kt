package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.Load
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ActiveAgent
import hub.core.client.model.Profile
import hub.core.client.model.SkillUsageReport
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → Skills usage (`audit.getSkillUsage`, decision §50; the web's SkillsUsagePage): which
 * skills the agents loaded in a period, how often, and which enabled skills no run loaded — the
 * period as a segmented row, the profile (every one, or one) and the agent as menus, the totals, the
 * per-day chart as a compact list of bars, the top skills. iOS's SkillsUsagePage.swift is the twin.
 */
@Composable
private fun SkillsUsagePage(profile: String, shell: ShellViewModel) {
    val ops = rememberHubDataOps(profile)
    val profiles by shell.profiles.collectAsState()
    var days by rememberSaveable { mutableStateOf(30) }
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    var agent by rememberSaveable { mutableStateOf<String?>(null) }
    // The agents offered survive a reload, so the menu does not empty while the next answer comes.
    var agents by remember { mutableStateOf<List<ActiveAgent>>(emptyList()) }
    val report = rememberLoad("skills", profile, days, only, agent) {
        // «All» still names a profile in the header, the one the person is in (ADR 0016).
        ops.skillUsage(days, only ?: profile, everyProfile = only == null, agent = agent).getOrThrow()
    }
    val ready = (report.state as? Load.Ready)?.value
    LaunchedEffect(ready) { if (ready != null) agents = ready.agents }
    LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("skills.page")) {
        item {
            Segmented(
                SkillsUsageRules.PERIODS.map { Segment(it, periodLabel(it), tag = "skills.days.$it") }, days, { days = it },
                Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        item { SkillsUsageFilters(profiles, only, { only = it }, SkillsUsageRules.agentChoices(agents, agent), agent, { agent = it }) }
        item { LoadView(report) { SkillsUsageBody(it) } }
    }
}

@Composable
private fun periodLabel(days: Int): String = stringResource(
    when (days) {
        7 -> R.string.knowledge_skills_period_7
        90 -> R.string.knowledge_skills_period_90
        365 -> R.string.knowledge_skills_period_365
        else -> R.string.knowledge_skills_period_30
    },
)

/** The profile menu (only with more than one profile) and the agent menu, side by side. */
@Composable
private fun SkillsUsageFilters(
    profiles: List<Profile>, only: String?, onOnly: (String?) -> Unit,
    agents: List<ActiveAgent>, agent: String?, onAgent: (String?) -> Unit,
) {
    val allProfiles = stringResource(R.string.knowledge_skills_all_profiles)
    val allAgents = stringResource(R.string.knowledge_skills_all_agents)
    val removed = stringResource(R.string.knowledge_skills_removed_agent)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (profiles.size > 1) {
            MenuButton(
                only?.let { slug -> profiles.firstOrNull { it.slug == slug }?.name ?: slug } ?: allProfiles,
                listOf<Pair<String?, String>>(null to allProfiles) + profiles.map { it.slug to it.name }, onOnly, "skills.profile", Modifier.weight(1f),
            )
        }
        MenuButton(
            agent?.let { id -> agents.firstOrNull { it.agentId == id }?.name ?: removed } ?: allAgents,
            listOf<Pair<String?, String>>(null to allAgents) + agents.map { it.agentId to (it.name ?: removed) }, onAgent, "skills.agent", Modifier.weight(1f),
        )
    }
}

@Composable
private fun MenuButton(label: String, options: List<Pair<String?, String>>, onPick: (String?) -> Unit, tag: String, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        HubButton(label, { open = true }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.ChevronsUpDown, modifier = Modifier.fillMaxWidth().testTag(tag))
        HubMenu(open, { open = false }) {
            options.forEach { (value, text) -> MenuItem(text, { open = false; onPick(value) }) }
        }
    }
}

private val sinceFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val barDay: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/** The report: since when it counts, the four totals, the days as bars, the top skills, the never-used ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillsUsageBody(report: SkillUsageReport) {
    val t = LocalTokens.current
    val totals = report.totals
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            (report.countingSince?.let { stringResource(R.string.knowledge_skills_counting_since, it.toLocalDate().format(sinceFormat)) }
                ?: stringResource(R.string.knowledge_skills_not_counting)) + " " + stringResource(R.string.knowledge_skills_not_counted_acp),
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.testTag("skills.since"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stringResource(R.string.knowledge_skills_total_uses), totals.uses.toString(), null, "skills.total.uses", Modifier.weight(1f))
            Stat(stringResource(R.string.knowledge_skills_distinct), totals.distinctSkills.toString(), null, "skills.total.distinct", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(
                stringResource(R.string.knowledge_skills_top), totals.topSkill?.skill ?: stringResource(R.string.knowledge_skills_none),
                totals.topSkill?.let { stringResource(R.string.knowledge_skills_uses_n, it.uses.toString()) }, "skills.total.top", Modifier.weight(1f),
            )
            Stat(
                stringResource(R.string.knowledge_skills_never_used), totals.neverUsedCount?.toString() ?: stringResource(R.string.knowledge_skills_unknown),
                if (totals.neverUsedCount == null) stringResource(R.string.knowledge_skills_unknown_hint) else null, "skills.total.never", Modifier.weight(1f),
            )
        }
        if (totals.uses == 0) {
            EmptyState(stringResource(R.string.knowledge_skills_nothing), Modifier.testTag("skills.nothing"), icon = Lucide.ChartColumn)
        } else {
            SectionTitle(stringResource(R.string.knowledge_skills_daily))
            DailyBars(report)
            GroupedList(Modifier.testTag("skills.top"), title = stringResource(R.string.knowledge_skills_table)) {
                report.topSkills.forEach { s ->
                    Item(
                        s.skill,
                        subtitle = stringResource(R.string.knowledge_skills_row, s.uses.toString(), SkillsUsageRules.percent(s.share), localTime(s.lastUsedAt)),
                        value = s.uses.toString(), tag = "skills.skill.${s.skill}",
                    )
                }
            }
        }
        val never = report.neverUsed.orEmpty()
        if (never.isNotEmpty()) {
            SectionTitle(stringResource(R.string.knowledge_skills_never_used_list))
            FlowRow(Modifier.testTag("skills.never"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                never.forEach { Badge(it) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, hint: String?, tag: String, modifier: Modifier) {
    val t = LocalTokens.current
    HubCard(modifier.testTag(tag), padding = 12.dp) {
        Text(value, fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        hint?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textFaint) }
    }
}

/** The web's stacked chart as a phone list: each day with a use, its bar beside the busiest day, and what was loaded. */
@Composable
private fun DailyBars(report: SkillUsageReport) {
    val t = LocalTokens.current
    val days = SkillsUsageRules.activeDays(report)
    val most = days.maxOfOrNull { it.uses } ?: 0
    val other = stringResource(R.string.knowledge_skills_other)
    HubCard(Modifier.testTag("skills.daily"), padding = 12.dp) {
        days.forEach { day ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.date.format(barDay), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.width(56.dp))
                    Box(Modifier.weight(1f).height(8.dp).background(t.surface2, RoundedCornerShape(4.dp))) {
                        Box(
                            Modifier.fillMaxWidth(SkillsUsageRules.fraction(day.uses, most)).height(8.dp)
                                .background(t.accent, RoundedCornerShape(4.dp)),
                        )
                    }
                    Text(day.uses.toString(), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    SkillsUsageRules.daySkills(day).joinToString(" · ") { (skill, uses) -> "${skill ?: other} $uses" },
                    fontSize = FontTokens.sizeXs.sp, color = t.textFaint, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 64.dp),
                )
            }
        }
    }
}

internal val skillsUsagePage = SettingsPageEntry("skills_usage") { SkillsUsagePage(it.session.profile, it.shell) }
