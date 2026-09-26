package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
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
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.ToggleRow
import hub.core.client.model.Agent
import kotlinx.coroutines.launch

@Composable
private fun SkillsPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<HubError?>(null) }
    val load = rememberLoad(agent.id, profile) { apis().agents.agentsListSkills(profile, agent.id).categories }
    LoadView(load) { categories ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.skills")) {
            item { ErrorNotice(error) }
            if (categories.all { it.skills.isEmpty() }) item { EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.Sparkles) }
            categories.filter { it.skills.isNotEmpty() }.forEach { category ->
                item(key = "c" + category.key) {
                    GroupedList(title = category.name) {
                        category.skills.forEach { skill ->
                            Custom {
                                ToggleRow(
                                    skill.name, skill.enabled,
                                    { on -> scope.launch { ops.setSkill(skill.key, on).onFailure { error = it as HubError }.onSuccess { error = null }; load.reload() } },
                                    subtitle = skill.description, modifier = Modifier.testTag("skill.${skill.key}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val agentSkillsPage = AgentPageEntry("agent_skills") { agent, profile -> SkillsPage(agent, profile) }
