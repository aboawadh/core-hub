package hub.core.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import hub.core.android.data.StoredSession
import hub.core.android.nav.Route
import hub.core.client.model.Agent

/*
 * The phone's page registry (docs/clients/phone-pages.md). Every Settings page and every agent
 * page is an entry declared in its own file, next to the page it draws (the `settings/` and
 * `agent/` folders); the lists below name each entry once, in the manifest's order, and are complete:
 * a page that is not native yet already has its file, whose entry says `native = false` and draws
 * the one fallback, [OnTheWebPage]. Making a page native edits only that page's file: draw the
 * page and drop `native = false`. The parity tests check the lists against navigation.json and
 * that a page's `native` flag matches what it draws.
 */

/** What a Settings page gets: the signed-in session, the shell, and the way to open another route. */
class SettingsPageContext(
    val destination: String,
    val session: StoredSession,
    val shell: ShellViewModel,
    val onOpen: (Route) -> Unit,
    /** This device lives in `phone/` and needs the activity's own pieces (MainActivity passes it). */
    val thisDevice: @Composable () -> Unit,
)

/** One Settings page: its destination id, whether the phone draws it, and what it draws. */
class SettingsPageEntry(
    val id: String,
    val native: Boolean = true,
    val content: @Composable (SettingsPageContext) -> Unit,
)

/** One agent-level page (`agent_*`): the agent it edits and the profile it edits it in. */
class AgentPageEntry(
    val id: String,
    val native: Boolean = true,
    val content: @Composable (agent: Agent, profile: String) -> Unit,
)

object PageRegistry {
    /** Every Settings destination of the phone, in the manifest's order (tabs, management, tools). */
    val settings: List<SettingsPageEntry> = listOf(
        accountPage, usersPage, webhooksPage, displayPage, notificationsPage, privacyPage, thisDevicePage, aboutPage,
        modelsPage, deviceConnectionsPage, knowledgePage, linkedHubsPage,
        logsPage, usagePage, skillsUsagePage, performancePage, themePage, workspacesPage, updatesPage, hubPluginsPage, filesPage, terminalPage,
    )

    /** Every agent-level destination, in `agentLevel` order. */
    val agent: List<AgentPageEntry> = listOf(
        agentSkillsPage, agentMcpPage, agentMemoryPage, agentJobsPage, agentChannelsPage, agentPluginsPage, agentConfigFilesPage,
        agentSettingsPage,
    )

    fun settingsPage(id: String): SettingsPageEntry? = settings.firstOrNull { it.id == id }
    fun agentPage(id: String): AgentPageEntry? = agent.firstOrNull { it.id == id }

    /** The Settings pages the phone draws itself; the rest open the same page on the web. */
    val nativeSettings: Set<String> get() = settings.filter { it.native }.map { it.id }.toSet()
}

/** One page under Settings; «Back to Settings» is the top bar's back arrow. */
@Composable
fun SettingsPageScreen(destination: String, shell: ShellViewModel, onOpen: (Route) -> Unit, thisDevice: @Composable () -> Unit) {
    val session by shell.session.collectAsState()
    val s = session ?: return
    val page = PageRegistry.settingsPage(destination)
    if (page == null || !page.native) OnTheWebPage(destination)
    else page.content(SettingsPageContext(destination, s, shell, onOpen, thisDevice))
}

/** An agent's page on the phone, editable as on iOS (B14). */
@Composable
fun AgentPageEditable(page: String, agent: Agent, profile: String) {
    // Settings is every installed agent's page, and the one an unknown id falls back to.
    val entry = PageRegistry.agentPage(page) ?: agentSettingsPage
    if (entry.native) entry.content(agent, profile) else OnTheWebPage(entry.id, agentId = agent.id)
}
